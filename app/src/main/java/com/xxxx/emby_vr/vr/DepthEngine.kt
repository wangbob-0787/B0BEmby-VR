package com.xxxx.emby_vr.vr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时 2D→3D 的深度推理（2026-11-01）。
 *
 * 数据流：原生渲染线程把当前画面缩到模型输入尺寸读回 CPU → 交给这里 →
 * 后台线程跑模型 → 结果回传原生上传成纹理。渲染线程只做读回，不等模型。
 *
 * 模型：ZipDepth（ECCV 2026，MIT 授权）的 512x288 fp16 版，12MB。
 * 选它的原因：单帧几毫秒级，头显上能跑到实时；MiDaS v2.1 small 同尺寸要 30MB 且慢一档。
 */
object DepthEngine {

    private const val TAG = "B0BEmbyVR"
    private const val MODEL = "models/zipdepth_512x288_fp16.tflite"

    /** 模型是否可用；不可用时原生保持假深度，2D→3D 仍能开但只有条纹视差 */
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
     * 为什么要它（2026-11-01）：单帧深度本身够用，但逐帧会轻微跳，
     * 跳在立体画面里就是"物体在抖"，看久了累。平滑几乎不花算力，观感提升比换模型大。
     * 取 0.8 是折中：跟得上镜头移动，又压得住抖动。
     */
    @Volatile
    var smooth = 0.8f

    private var prevDepth: ByteArray? = null
    private var inferCount = 0

    private var interp: Interpreter? = null
    private var nnapi: NnApiDelegate? = null
    private var gpu: GpuDelegate? = null
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    private var inIsFloat = true
    private var outIsFloat = true
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depth-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)

    /** 挂到原生：把本对象交给 native 当回调目标，并让 native 知道模型输入尺寸 */
    fun attach(context: Context) {
        if (!VrNative.loaded) return
        VrNative.setDepthSink(this)
        if (ready) {
            VrNative.setDepthInputSize(inW, inH)
            return
        }
        worker.execute { init(context.applicationContext) }
    }

    fun detach() {
        if (VrNative.loaded) VrNative.setDepthSink(null)
        /* GPU 委托会长期占显存，退出 VR 就释放 */
        runCatching { gpu?.close() }
        gpu = null
    }

    private fun init(context: Context) {
        synchronized(this) {
            if (ready) return
            try {
                val model = loadModel(context)
                val opts = Interpreter.Options()
                /*
                 * 委托优先级：GPU → NNAPI → CPU 多线程。
                 *
                 * GPU 排第一有依据：ZipDepth 官方在 5W 的红米 Poco X3 上靠手机 GPU 就有 16 帧，
                 * XR2 的 Adreno 比它强得多，跑 GPU 比 CPU 快一档，省下的算力可以换更高分辨率。
                 * 建不起来就退 NNAPI，再不行退 CPU 多线程（模型不大，CPU 也能跑十来赫兹）。
                 */
                try {
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        /* 默认参数即可：精度损失本来就允许（深度图不追求数值），只求速度 */
                        val d = GpuDelegate()
                        opts.addDelegate(d)
                        gpu = d
                        Log.i(TAG, "深度模型：走 GPU 加速")
                    } else {
                        Log.i(TAG, "深度模型：本机不支持 GPU 委托")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "深度模型：GPU 不可用，改试 NNAPI（${t.message}）")
                }
                if (gpu == null) {
                    try {
                        val d = NnApiDelegate()
                        opts.addDelegate(d)
                        nnapi = d
                    } catch (t: Throwable) {
                        Log.w(TAG, "深度模型：NNAPI 不可用，改用 CPU（${t.message}）")
                    }
                }
                opts.setNumThreads(3)
                val it = Interpreter(model, opts)

                val ishape = it.getInputTensor(0).shape()   // [1,h,w,3]
                val oshape = it.getOutputTensor(0).shape()  // [1,h,w,1] 或 [1,h,w]
                inH = ishape[1]
                inW = ishape[2]
                inIsFloat = it.getInputTensor(0).dataType() == DataType.FLOAT32
                outIsFloat = it.getOutputTensor(0).dataType() == DataType.FLOAT32
                outH = oshape[1]
                outW = if (oshape.size >= 4) oshape[2] else oshape[oshape.size - 1]
                if (oshape.size == 3) outW = oshape[2]

                val bytesPerIn = if (inIsFloat) 4 else 2
                inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * bytesPerIn)
                    .order(ByteOrder.nativeOrder())
                val bytesPerOut = if (outIsFloat) 4 else 2
                outBuf = ByteBuffer.allocateDirect(outW * outH * bytesPerOut)
                    .order(ByteOrder.nativeOrder())

                interp = it
                ready = true
                Log.i(TAG, "深度模型就绪：输入 ${inW}x${inH}，输出 ${outW}x${outH}")
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
     *
     * slot 是原生侧画面缓冲的槽号，结果原路带回：那一帧的深度只能贴回那一帧，
     * 贴到别的画面上就是错位。
     */
    fun submitFrame(rgba: ByteArray, w: Int, h: Int, slot: Int) {
        if (!ready || w <= 0 || h <= 0) return
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                runInfer(rgba, w, h, slot)
            } catch (t: Throwable) {
                Log.e(TAG, "深度推理失败：${t.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun runInfer(rgba: ByteArray, w: Int, h: Int, slot: Int) {
        val it = interp ?: return
        val ib = inBuf ?: return
        val ob = outBuf ?: return
        if (rgba.size < w * h * 4) return

        ib.rewind()
        /*
         * 最近邻缩放到模型输入尺寸，RGB 归一到 0..1。
         * 不做双线性：512x288 上这点差别看不出来，最近邻省下的时间够多跑两次推理。
         */
        for (y in 0 until inH) {
            val sy = (y.toLong() * h / inH).toInt()
            val row = sy * w
            for (x in 0 until inW) {
                val sx = (x.toLong() * w / inW).toInt()
                val o = (row + sx) * 4
                if (inIsFloat) {
                    ib.putFloat((rgba[o].toInt() and 0xFF) / 255f)
                    ib.putFloat((rgba[o + 1].toInt() and 0xFF) / 255f)
                    ib.putFloat((rgba[o + 2].toInt() and 0xFF) / 255f)
                } else {
                    ib.putShort(((rgba[o].toInt() and 0xFF) * 257).toShort())
                    ib.putShort(((rgba[o + 1].toInt() and 0xFF) * 257).toShort())
                    ib.putShort(((rgba[o + 2].toInt() and 0xFF) * 257).toShort())
                }
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
            val v = if (outIsFloat) ob.float else halfToFloat(ob.short.toInt() and 0xFFFF)
            vals[i] = v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = (mx - mn).let { if (it > 1e-5f) it else 1f }
        val out = ByteArray(n)
        var prev = prevDepth
        val a = smooth.coerceIn(0f, 1f)
        /*
         * 换镜头检测：切镜、换集、跳转时深度会突变，这时绝不能拿上一张去混合，
         * 否则新画面前半秒还在用旧场景的深度 —— 看到的就是"物体缓慢滑到正确位置"。
         * 抽样比对即可，几百个点足够判出来。
         */
        if (prev != null && prev.size == n && a < 1f) {
            var acc = 0L
            var cnt = 0
            var i = 0
            while (i < n) {
                var x = (vals[i] - mn) / range
                if (invert) x = 1f - x
                acc += kotlin.math.abs(prev[i].toInt().and(0xFF) -
                        (x.coerceIn(0f, 1f) * 255f).toInt())
                cnt++
                i += 64
            }
            if (cnt > 0 && acc / cnt > 40) prev = null
        }
        for (i in 0 until n) {
            var v = (vals[i] - mn) / range
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
        }
        VrNative.pushDepth(out, outW, outH, slot)
    }

    private fun halfToFloat(h: Int): Float {
        val sign = h and 0x8000
        val exp = (h shr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 -> if (mant == 0) sign shl 16 else {
                var e = -1
                var m = mant
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                ((sign or ((e + 15 + 127) shl 10) or ((m and 0x3FF) shl 1)) shl 16) and 0xFFFF0000.toInt()
            }
            exp == 0x1F -> (sign or 0x7C00 or mant) shl 16
            else -> (sign or ((exp - 15 + 127) shl 10) or mant) shl 16
        }
        return Float.fromBits(bits)
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
