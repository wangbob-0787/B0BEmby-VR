package com.xxxx.emby_vr.vr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时 2D→3D 的深度推理。
 *
 * 数据流：原生渲染线程把当前画面缩到模型输入尺寸读回 CPU → 交到这里 →
 * 后台线程跑模型 → 结果回传原生上传成纹理。渲染线程只做读回，不等模型。
 *
 * 模型：ZipDepth 的 int8 动态范围量化版（256x256 输入，6MB），与 DepthRay 在同一台
 * PICO 4 上跑的是同一个文件。选它而不是 512x288 fp16 版有两个原因：体积一半、单帧
 * 推理快一档；在这个尺寸上深度图的边缘精度由后面的引导上采样补，不靠模型自己给。
 *
 * 推理固定在 CPU 四线程，不用 GPU / NNAPI 委托：GPU 推理和画面渲染抢同一块显卡，
 * 实测开 GPU 委托反而掉帧（渲染是这条链路的瓶颈，不是推理）。
 */
object DepthEngine {

    private const val TAG = "B0BEmbyVR"
    private const val MODEL = "models/zipdepth_256_int8dr.tflite"
    /** 推理线程数：XR2 八个核里留一半给渲染与解码 */
    private const val THREADS = 4

    /**
     * 换镜头检测的三个量（照 DepthRay 的 depth cut 口径）。
     *
     * 单看一个量都不稳：差值对整体亮度变化过敏，直方图看不到空间结构，相关受过曝/过暗
     * 区域影响。三个一起投票（任意两个越界即判切）才能既不漏切镜、也不把镜头内的大幅
     * 运动当成切镜 —— 误判的代价是新画面前几帧还挂着旧场景的深度（物体像在缓慢滑动）。
     */
    private const val CUT_DIFF = 0.15f   // 归一化后的平均绝对差（它在 0.15~0.27 之间判切）
    private const val CUT_HIST = 0.20f   // 16 段直方图的 L1 距离（归一化到 0~1）
    private const val CUT_CORR = 0.40f   // 相关系数低于这个值就是两张无关的图
    /** 抽样步长：每 16 个像素取一个点，256x256 上约 4096 个样本，三个量都用它算 */
    private const val SAMPLE_STRIDE = 16
    private const val HIST_BINS = 16

    /** 模型是否可用；不可用时原生保持平面出画，2D→3D 开关仍可开但没有立体 */
    @Volatile
    var ready = false
        private set

    /** 最近一次推理耗时（毫秒），排查用 */
    @Volatile
    var lastInferMs = 0L
        private set

    /** 模型输出是"近大远小"还是相反：默认按视差（越大越近）取反一次 */
    @Volatile
    var invert = false

    /**
     * 时间平滑系数：新深度只占这么多，其余沿用上一帧（0 = 完全不动，1 = 不平滑）。
     *
     * 单帧深度本身够用，但逐帧会轻微跳，跳在立体画面里就是"物体在抖"。平滑几乎不花算力。
     * 取 0.8 是折中：跟得上镜头移动，又压得住抖动。
     */
    @Volatile
    var smooth = 0.8f

    /** 3D 是否开着。关掉时解释器整个释放，四个推理线程随之收工，2D 播放不吃这份 CPU */
    @Volatile
    private var active = false

    private var appContext: Context? = null
    private var prevDepth: ByteArray? = null
    private var inferCount = 0
    /* 连续几次判定为"换了镜头"：单帧超阈值可能只是画面里有物体快速移动，不算换镜头 */
    private var jumpStreak = 0
    private var lastDiff = 0f
    private var lastHist = 0f
    private var lastCorr = 1f
    private var shotCount = 0
    private var refMn = 0f
    private var refMx = 1f
    private var refInit = false
    @Volatile private var lastOkMs = System.currentTimeMillis()

    private var interp: Interpreter? = null
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depth-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)

    /** 挂到原生：把本对象交给 native 当回调目标。此时不加载模型，等 3D 真的打开再加载 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (!VrNative.loaded) return
        VrNative.setDepthSink(this)
    }

    fun detach() {
        if (VrNative.loaded) VrNative.setDepthSink(null)
        worker.execute { shutdown() }
    }

    /**
     * 3D 开关（VrNative 在 stereo3d 调参生效时回调）。
     *
     * 关掉必须真的释放解释器：TFLite 的四个线程只有在有推理时才占 CPU，但解释器本身的
     * 内存与线程池一直挂着；释放它才能保证 2D 播放时这条链路零开销。
     */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        val ctx = appContext
        if (on) {
            if (ctx == null) {
                Log.w(TAG, "深度推理：还没有上下文，等 attach")
                return
            }
            worker.execute { init(ctx) }
        } else {
            worker.execute { shutdown() }
        }
    }

    private fun shutdown() {
        runCatching { interp?.close() }
        interp = null
        ready = false
        prevDepth = null
        refInit = false
        inBuf = null
        outBuf = null
        inW = 0
        inH = 0
        busy.set(false)
        if (VrNative.loaded) VrNative.setDepthInputSize(0, 0)
        Log.i(TAG, "深度推理：3D 已关，解释器释放（推理线程收工）")
    }

    private fun init(context: Context) {
        if (!active) return
        synchronized(this) {
            if (ready) return
            try {
                val model = loadModel(context)
                /* 只走 CPU：GPU 委托和画面渲染抢同一块显卡，父测开 GPU 反而掉帧 */
                val opts = Interpreter.Options().setNumThreads(THREADS)
                val it = Interpreter(model, opts)

                val ishape = it.getInputTensor(0).shape()   // [1,h,w,3]
                val oshape = it.getOutputTensor(0).shape()  // [1,h,w]
                if (it.getInputTensor(0).dataType() != DataType.FLOAT32 ||
                    it.getOutputTensor(0).dataType() != DataType.FLOAT32
                ) {
                    Log.e(TAG, "深度模型输入输出必须是 float32，实际 ${it.getInputTensor(0).dataType()}")
                    it.close()
                    return
                }
                inH = ishape[1]
                inW = ishape[2]
                outH = oshape[1]
                outW = oshape[oshape.size - 1]

                inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                outBuf = ByteBuffer.allocateDirect(outW * outH * 4)
                    .order(ByteOrder.nativeOrder())

                interp = it
                ready = true
                Log.i(TAG, "深度模型就绪：输入 ${inW}x${inH}，输出 ${outW}x${outH}，CPU ${THREADS} 线程")
                VrNative.setDepthInputSize(inW, inH)
            } catch (t: Throwable) {
                Log.e(TAG, "深度模型加载失败：${t.message}")
                ready = false
            }
        }
    }

    /**
     * 原生渲染线程调用（必须在几毫秒内返回）：只入队，不推理。
     * 上一帧还没算完就丢掉这一帧 —— 深度不需要每帧都新。
     */
    fun submitFrame(rgba: ByteArray, w: Int, h: Int, seq: Long) {
        if (!ready || !active || w <= 0 || h <= 0) return
        /*
         * 看门狗：推理线程卡住（模型内部报错）时 busy 会一直是 true，之后就再也不推理，
         * 表现是立体感过一会儿就没了。超过三秒没结果就强行放开。
         */
        val nowMs = System.currentTimeMillis()
        if (busy.get() && nowMs - lastOkMs > 3000) {
            Log.w(TAG, "深度推理卡住超过 3 秒，强行恢复")
            busy.set(false)
        }
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                runInfer(rgba, w, h, seq)
            } catch (t: Throwable) {
                Log.e(TAG, "深度推理失败：${t.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun runInfer(rgba: ByteArray, w: Int, h: Int, seq: Long) {
        val it = interp ?: return
        val ib = inBuf ?: return
        val ob = outBuf ?: return
        if (rgba.size < w * h * 4) return

        ib.rewind()
        /*
         * 最近邻缩放到模型输入尺寸，RGB 归一到 0..1。
         * 不做双线性：256x256 上这点差别看不出来（后面还有引导上采样把边缘贴回去），
         * 最近邻省下的时间够多跑一次推理。
         */
        for (y in 0 until inH) {
            val sy = (y.toLong() * h / inH).toInt()
            val row = sy * w
            for (x in 0 until inW) {
                val sx = (x.toLong() * w / inW).toInt()
                val o = (row + sx) * 4
                ib.putFloat((rgba[o].toInt() and 0xFF) / 255f)
                ib.putFloat((rgba[o + 1].toInt() and 0xFF) / 255f)
                ib.putFloat((rgba[o + 2].toInt() and 0xFF) / 255f)
            }
        }
        ib.rewind()
        ob.rewind()

        val t0 = SystemClock.elapsedRealtime()
        it.run(ib, ob)
        lastInferMs = SystemClock.elapsedRealtime() - t0

        ob.rewind()
        val n = outW * outH
        val vals = FloatArray(n)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until n) {
            val v = ob.float
            vals[i] = v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        /*
         * 归一化用慢速跟踪的参考范围，不用这一帧自己的极值。
         * 每帧各自拉伸会让同一场景的立体强度随画面内容起伏，看着就是"一会有一会没有"。
         * 参考值只按 2% 的步长跟随，单帧的极值不会立刻改变整体映射；换镜头时参考作废重取。
         */
        if (refInit) {
            refMn = refMn * 0.98f + mn * 0.02f
            refMx = refMx * 0.98f + mx * 0.02f
        } else {
            refMn = mn
            refMx = mx
            refInit = true
        }
        val useMn = refMn
        val useMx = if (refMx - refMn > 0.05f) refMx else refMn + 0.05f
        val range = useMx - useMn

        var prev = prevDepth
        val a = smooth.coerceIn(0f, 1f)
        if (prev != null && prev.size == n && a < 1f) {
            val stats = cutStats(vals, useMn, range, prev)
            lastDiff = stats[0]
            lastHist = stats[1]
            lastCorr = stats[2]
            /*
             * 三个量投票，任意两个越界判切。这里仍然要求连续两次才真切：
             * 单个物体快速横穿画面时三个量可能一起抖，但持续两帧的"全画面换掉"只可能是切镜。
             */
            var votes = 0
            if (lastDiff > CUT_DIFF) votes++
            if (lastHist > CUT_HIST) votes++
            if (lastCorr < CUT_CORR) votes++
            if (votes >= 2) {
                jumpStreak++
                if (jumpStreak >= 2) {
                    prev = null
                    refInit = false
                    shotCount++
                    jumpStreak = 0
                }
            } else {
                jumpStreak = 0
            }
        } else {
            lastDiff = 0f
            lastHist = 0f
            lastCorr = 1f
            jumpStreak = 0
        }

        val out = ByteArray(n)
        for (i in 0 until n) {
            var v = (vals[i] - useMn) / range
            if (invert) v = 1f - v
            var b = v.coerceIn(0f, 1f) * 255f
            /* 尺寸变了（换片源 / 模型换了）就直接用新值，别拿旧图去插值 */
            if (prev != null && prev.size == n && a < 1f) {
                b = prev[i].toInt().and(0xFF) * (1f - a) + b * a
            }
            out[i] = b.toInt().coerceIn(0, 255).toByte()
        }
        prevDepth = out
        inferCount++
        if (inferCount % 20 == 1) {
            Log.i(TAG, "深度推理：${lastInferMs}ms 第 ${inferCount} 次 输入 ${w}x${h}")
            Log.i(TAG, "深度切镜：diff %.3f hist %.3f corr %.2f 换镜头 ${shotCount} 次 平滑 ${smooth}",
                lastDiff, lastHist, lastCorr)
        }
        lastOkMs = System.currentTimeMillis()
        /* 帧号原样带回原生：环形缓冲靠它把深度还给"本来就是它"的那一帧 */
        VrNative.pushDepth(out, outW, outH, seq)
    }

    /**
     * 两张深度图有多不像：[平均绝对差, 直方图距离, 相关系数]。
     *
     * 都在抽样点上算（约 4096 个点），三个量一次遍历取齐 —— 分开遍历是三次内存往返。
     */
    private fun cutStats(vals: FloatArray, mn: Float, range: Float, prev: ByteArray): FloatArray {
        val inv = 1f / range
        val histA = IntArray(HIST_BINS)
        val histB = IntArray(HIST_BINS)
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var absSum = 0.0
        var cnt = 0
        var i = 0
        while (i < vals.size) {
            var a = (vals[i] - mn) * inv
            if (invert) a = 1f - a
            a = a.coerceIn(0f, 1f)
            val b = prev[i].toInt().and(0xFF) / 255f
            absSum += kotlin.math.abs(a - b)
            sumA += a
            sumB += b
            sumAA += a * a
            sumBB += b * b
            sumAB += a * b
            histA[(a * (HIST_BINS - 1)).toInt()]++
            histB[(b * (HIST_BINS - 1)).toInt()]++
            cnt++
            i += SAMPLE_STRIDE
        }
        if (cnt == 0) return floatArrayOf(0f, 0f, 1f)
        var histL1 = 0.0
        for (k in 0 until HIST_BINS) {
            histL1 += kotlin.math.abs(histA[k] - histB[k]).toDouble()
        }
        val ma = sumA / cnt
        val mb = sumB / cnt
        val va = sumAA / cnt - ma * ma
        val vb = sumBB / cnt - mb * mb
        val cov = sumAB / cnt - ma * mb
        val corr = if (va > 1e-8 && vb > 1e-8) (cov / kotlin.math.sqrt(va * vb)).toFloat() else 1f
        return floatArrayOf(
            (absSum / cnt).toFloat(),
            (histL1 / (2.0 * cnt)).toFloat(),
            corr
        )
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }
}
