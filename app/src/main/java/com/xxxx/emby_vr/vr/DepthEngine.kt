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
    /**
     * 两套模型，运行时按 depth_model 选（默认 1 = 参考实现的默认款）。
     *
     * 0 = `zipdepth_256_int8dr.tflite`（6MB，动态范围量化）：体积小，但动态范围量化的
     * 卷积走的是参考实现、吃不到 XNNPACK 的加速内核，实测单次推理 110ms 上下。
     * 1 = `zipdepth_512x288_fp16.tflite`（12MB，fp16）：参考实现（DepthRay）的默认款，
     * 它同一台头显上 25~60ms。
     */
    private fun modelAsset(): String {
        val m = VrTuning.depthModel
        val fp16 = when (m) {
            0 -> false
            1 -> true
            else -> VrTuning.depthGpu == 1     // 自动：显卡配 fp16（显卡跑 fp16 才快），CPU 配 int8
        }
        return if (fp16) "models/zipdepth_512x288_fp16.tflite"
        else "models/zipdepth_256_int8dr.tflite"
    }

    /**
     * 显卡委托建不起来时要不要自动换回 int8 小模型（`vr-tuning.txt` 的 `depth_auto`，默认开）。
     *
     * 默认组合是"显卡推理 + 512×288 fp16 自动档"：显卡那条路一旦建不起来，代码原本是
     * 静默退回 CPU、却仍用大模型 —— 那是 CPU 上最慢的一条组合（比 int8 小模型慢一倍）。
     * 打开这个开关就换回小模型；关掉是为了 A/B 对照"大模型跑 CPU 到底差多少"。
     */
    @Volatile
    var autoFallback: Boolean = true

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

    /* 远程服务端的模型输入尺寸（点 15 上的 depth512.onnx 是 512x288） */
    private const val REMOTE_IN_W = 512
    private const val REMOTE_IN_H = 288
    /* 一批几帧走调参（depth_batch），排满或最老一帧等了 40 毫秒就发 */
    private const val REMOTE_BATCH_DEFAULT = 3
    private const val REMOTE_BATCH_WAIT_MS = 40L
    private const val REMOTE_QUEUE_MAX = 12

    /** 模型是否可用；不可用时原生保持平面出画，2D→3D 开关仍可开但没有立体 */
    @Volatile
    var ready = false
        private set

    /* 远程深度服务（点 15）：长连接，断了下一帧重连 */
    private var sock: java.net.Socket? = null
    private var sockKey = ""
    /*
     * 复用缓冲：以前每帧都新建 rgb / vals / out 三个大数组，
     * 一秒几十帧就是几十兆垃圾，头显的 Java 堆顶不住（实测报 OutOfMemory 崩掉）。
     */
    private var jpegBuf = java.io.ByteArrayOutputStream(64 * 1024)
    private var pixBuf = IntArray(0)
    private var bmpBuf: android.graphics.Bitmap? = null
    private var rgbBuf = ByteArray(0)
    private var valsBuf = FloatArray(0)
    private var outU8Buf = ByteArray(0)
    /* 远程批量：队列、批次大小、最长等待 */
    private val pending = java.util.ArrayDeque<RemoteFrame>()
    @Volatile private var batchThread: Thread? = null
    @Volatile private var batchStop = false

    /** 最近一次推理耗时（毫秒），排查用 */
    @Volatile
    var lastInferMs = 0L
        private set

    /*
     * 推理耗时统计：每 20 次打一行平均/最大，A/B 切档（模型、线程、委托、遮挡表档位）
     * 之后直接对比数字，不靠一次测量下结论 —— 单次最快值会被缓存和调度带偏。
     */
    private var inferSumMs = 0L
    private var inferMaxMs = 0L
    private var inferSamples = 0

    /**
     * Kotlin 侧要不要把归一化后的深度取反（1 − v）。
     *
     * 默认 false = **原样透传**：(v − 最小值) / 跨度 之后直接交回原生。
     * 深度方向的权威口径在原生侧（`depth_invert`，纹理里一律"越大越远"），这里留一个
     * 开关只是为了"换了模型、输出极性反过来"时就地翻，不用动原生代码。
     */
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
    /** 解释器是用哪套后端/模型/线程建起来的；调参一变就重建 */
    private var initedSig = ""
    /** 显卡委托对象：必须自己留着，解释器关闭后再关它 */
    private var gpu: GpuDelegate? = null
    private var nnapi: NnApiDelegate? = null
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
        runCatching { gpu?.close() }
        gpu = null
        runCatching { nnapi?.close() }
        nnapi = null
        ready = false
        batchStop = true
        batchThread = null
        synchronized(pending) { pending.clear() }
        runCatching { sock?.close() }
        sock = null
        prevDepth = null
        refInit = false
        inferSumMs = 0L
        inferMaxMs = 0L
        inferSamples = 0
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
            /*
             * 远程模式不需要本地解释器：建了也用不上，还白占内存和线程。
             * 只报一个输入尺寸给原生（尺寸决定上采样的源大小），然后就算就绪。
             */
            if (VrTuning.depthRemote == 1) {
                inW = REMOTE_IN_W
                inH = REMOTE_IN_H
                ready = true
                initedSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|${VrTuning.depthThreads}|1"
                if (VrNative.loaded) VrNative.setDepthInputSize(inW, inH)
                Log.i(TAG, "深度推理：远程模式（点 15，输入 ${inW}x${inH}），本地解释器不建")
                return
            }
            try {
                var asset = modelAsset()
                var model = loadModel(context, asset)
                val opts = Interpreter.Options().setNumThreads(VrTuning.depthThreads)
                /*
                 * 显卡委托：建不起来（老驱动、显存不足、机型不支持）就安静退回 CPU，
                 * 不能让 3D 整条链路因为这一步挂掉。委托对象要留着，close 时一起关。
                 */
                var gpuDelegate: GpuDelegate? = null
                if (VrTuning.depthGpu == 1) {
                    gpuDelegate = runCatching {
                        val compat = CompatibilityList()
                        check(compat.isDelegateSupportedOnThisDevice) { "本机显卡委托不支持" }
                        val gpuOpts = compat.bestOptionsForThisDevice
                        GpuDelegate(gpuOpts).also { opts.addDelegate(it) }
                    }.onFailure {
                        Log.w(TAG, "深度推理：显卡委托建不起来，退回 CPU（${it.message}）")
                    }.getOrNull()
                }
                /*
                 * NNAPI 委托（调参 depth_backend=1）：参考实现在同一颗芯片上用处理器跑到
                 * 45 毫秒，我们走 XNNPACK 是 113 毫秒，模型文件却是同一个 —— 差别只能在
                 * 驱动层整型加速，这条路就是那一层。建不起来就当没设，不换模型。
                 */
                if (gpuDelegate == null && VrTuning.depthBackend == 1) {
                    nnapi = runCatching { NnApiDelegate() }
                        .onFailure { Log.w(TAG, "深度推理：NNAPI 委托建不起来（${it.message}）") }
                        .getOrNull()
                    nnapi?.let { opts.addDelegate(it) }
                }
                /* depth_backend=2：关掉 XNNPACK，对照默认那条到底有没有把加速用上 */
                if (VrTuning.depthBackend == 2) opts.setUseXNNPACK(false)
                /*
                 * 退回 CPU 时别继续跑大模型：fp16 那款是给显卡准备的，跑在 CPU 上比 int8
                 * 小模型慢一倍，是这块芯片上最差的一条组合。换模型只在这里做一次，
                 * 不进热路径。
                 */
                if (gpuDelegate == null && autoFallback && asset.contains("512x288")) {
                    Log.w(TAG, "深度推理：显卡委托不可用，自动换回 int8 小模型（depth_auto=0 可关掉）")
                    asset = "models/zipdepth_256_int8dr.tflite"
                    model = loadModel(context, asset)
                }
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
                gpu = gpuDelegate
                ready = true
                initedSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|${VrTuning.depthThreads}|${VrTuning.depthRemote}|${VrTuning.depthBackend}"
                val backendName = when {
                    gpuDelegate != null -> "显卡推理"
                    nnapi != null -> "NNAPI 委托"
                    VrTuning.depthBackend == 2 -> "CPU ${VrTuning.depthThreads} 线程（已关 XNNPACK）"
                    else -> "CPU ${VrTuning.depthThreads} 线程"
                }
                Log.i(TAG, "深度模型就绪：${asset.substringAfterLast('/')} 输入 ${inW}x${inH}，" +
                    "输出 ${outW}x${outH}，$backendName")
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
        if (!active || w <= 0 || h <= 0) return
        /*
         * 调参换了推理后端（CPU/显卡）或模型：当场重建解释器，不用重装、不用重起播。
         * 只在两帧之间做，推理进行中不打断。
         */
        /*
         * 远程/本地也算"后端"的一种：从远程切回本地时必须重建解释器，
         * 否则 ready 还是 false，会一直空转不出深度（切回本地却没 3D 就是这个）。
         */
        val wantSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|${VrTuning.depthThreads}|${VrTuning.depthRemote}"
        if (wantSig != initedSig) {
            val ctx = appContext
            if (ctx != null && !busy.get()) {
                worker.execute {
                    shutdown()
                    init(ctx)
                }
                return
            }
        }
        if (!ready) return
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
        /*
         * 远程模式（父亲 2026-11-02）：深度改在点 15 上算，输入更大、单次更快。
         * 这条路径不依赖本地解释器，所以不走上面的 ready 判定。
         */
        if (VrTuning.depthRemote == 1) {
            busy.set(false)
            enqueueRemote(rgba, w, h, seq)
            return
        }
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

    /**
     * 远程深度（批量）：把最近几帧一起发给点 15，回来的深度按帧号各归各的帧。
     *
     * 为什么批量：头显到点 15 一个来回约 180 毫秒，而一帧推理只要 18 毫秒，
     * 单帧发等于每帧都白等一次往返。五帧一批就是 5×18+180 毫秒，
     * 摊到每帧五十来毫秒；批与批之间不停顿，路上时间和计算时间叠着走。
     * 迟到会到两百多毫秒，靠原生的"迟到深度垫给最新一槽"兜住。
     */
    private data class RemoteFrame(val seq: Long, val w: Int, val h: Int, val rgb: ByteArray, val ts: Long)

    private fun enqueueRemote(rgba: ByteArray, w: Int, h: Int, seq: Long) {
        val rw = REMOTE_IN_W
        val rh = REMOTE_IN_H
        val need = rw * rh
        val pix = if (pixBuf.size == need) pixBuf else IntArray(need).also { pixBuf = it }
        var o = 0
        for (y in 0 until rh) {
            val sy = (y.toLong() * h / rh).toInt()
            val row = sy * w
            for (x in 0 until rw) {
                val sx = (x.toLong() * w / rw).toInt()
                val i = (row + sx) * 4
                pix[o++] = (0xFF shl 24) or
                        ((rgba[i].toInt() and 0xFF) shl 16) or
                        ((rgba[i + 1].toInt() and 0xFF) shl 8) or
                        (rgba[i + 2].toInt() and 0xFF)
            }
        }
        val jpeg = runCatching {
            val bmp = bmpBuf ?: android.graphics.Bitmap.createBitmap(
                rw, rh, android.graphics.Bitmap.Config.ARGB_8888,
            ).also { bmpBuf = it }
            bmp.setPixels(pix, 0, rw, 0, 0, rw, rh)
            jpegBuf.reset()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, jpegBuf)
            jpegBuf.toByteArray()
        }.getOrNull() ?: return
        synchronized(pending) {
            /* 排太多说明网络在堵：丢掉最老的，宁可少几帧也别让深度越拖越旧 */
            while (pending.size > REMOTE_QUEUE_MAX) pending.pollFirst()
            pending.addLast(RemoteFrame(seq, rw, rh, jpeg, SystemClock.elapsedRealtime()))
        }
        ensureBatchThread()
    }

    private fun ensureBatchThread() {
        synchronized(this) {
            if (batchThread != null) return
            batchStop = false
            val th = Thread({
                while (!batchStop) {
                    val batchSize = VrTuning.depthBatch.coerceIn(1, 10)
                    val batch = ArrayList<RemoteFrame>(batchSize)
                    synchronized(pending) {
                        val head = pending.peekFirst()
                        val due = head != null &&
                                  SystemClock.elapsedRealtime() - head.ts >= REMOTE_BATCH_WAIT_MS
                        if (pending.size >= batchSize || due) {
                            while (batch.size < batchSize && pending.isNotEmpty()) {
                                batch.add(pending.pollFirst()!!)
                            }
                        }
                    }
                    if (batch.isEmpty()) {
                        Thread.sleep(3)
                        continue
                    }
                    try {
                        sendBatch(batch)
                    } catch (t: Throwable) {
                        Log.w(TAG, "远程深度：一批失败（${t.message}），重连")
                        runCatching { sock?.close() }
                        sock = null
                    }
                }
            }, "b0b-depth-remote")
            th.isDaemon = true
            batchThread = th
            th.start()
        }
    }

    private fun sendBatch(batch: List<RemoteFrame>) {
        val t0 = SystemClock.elapsedRealtime()
        val s = ensureSocket() ?: return
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val head = java.io.ByteArrayOutputStream(16 + batch.size * (6 + 40 * 1024))
        head.write(byteArrayOf(0x42, 0x30, 0x42, 0x4B))   // B0BK（压缩上行 + 压缩回传）
        head.write(byteArrayOf((batch.size and 0xFF).toByte(), ((batch.size shr 8) and 0xFF).toByte()))
        for (f in batch) {
            val len = f.rgb.size
            head.write(byteArrayOf((f.w and 0xFF).toByte(), ((f.w shr 8) and 0xFF).toByte(),
                                   (f.h and 0xFF).toByte(), ((f.h shr 8) and 0xFF).toByte(),
                                   (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
                                   ((len shr 16) and 0xFF).toByte(), ((len shr 24) and 0xFF).toByte()))
            head.write(f.rgb)
        }
        out.write(head.toByteArray())
        out.flush()

        val cntBuf = ByteArray(2)
        readFully(inp, cntBuf, 2)
        val n = (cntBuf[0].toInt() and 0xFF) or ((cntBuf[1].toInt() and 0xFF) shl 8)
        if (n != batch.size) throw java.io.IOException("远程深度：批量数目对不上（$n/${batch.size}）")
        for (f in batch) {
            /*
             * 回传的深度是灰度 JPEG（512x288 原始 147KB，压完约 20KB）。
             * 解回像素后只取红通道 —— 灰度图三通道相同。
             */
            val whl = ByteArray(8)
            readFully(inp, whl, 8)
            val ow = (whl[0].toInt() and 0xFF) or ((whl[1].toInt() and 0xFF) shl 8)
            val oh = (whl[2].toInt() and 0xFF) or ((whl[3].toInt() and 0xFF) shl 8)
            val ln = (whl[4].toInt() and 0xFF) or ((whl[5].toInt() and 0xFF) shl 8) or
                     ((whl[6].toInt() and 0xFF) shl 16) or ((whl[7].toInt() and 0xFF) shl 24)
            if (ln <= 0) continue
            val jp = ByteArray(ln)
            readFully(inp, jp, ln)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jp, 0, ln) ?: continue
            val cnt = bmp.width * bmp.height
            if (cnt <= 0) { bmp.recycle(); continue }
            val px = if (pixBuf.size == cnt) pixBuf else IntArray(cnt).also { pixBuf = it }
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            val vals = if (valsBuf.size == cnt) valsBuf else FloatArray(cnt).also { valsBuf = it }
            for (i in 0 until cnt) vals[i] = ((px[i] shr 16) and 0xFF) / 255f
            lastInferMs = (SystemClock.elapsedRealtime() - f.ts)
            finishDepth(vals, bmp.width, bmp.height, f.seq)
        }
        lastInferMs = SystemClock.elapsedRealtime() - t0
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r <= 0) throw java.io.IOException("远程深度：连接提前关闭")
            off += r
        }
    }

    private fun ensureSocket(): java.net.Socket? {
        val key = VrTuning.depthRemoteHost
        sock?.let { if (sockKey == key && it.isConnected && !it.isClosed) return it }
        runCatching { sock?.close() }
        sock = null
        sockKey = key
        val parts = key.split(":")
        val host = parts.getOrNull(0) ?: "192.168.150.15"
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 7799
        return runCatching {
            java.net.Socket().apply {
                tcpNoDelay = true
                connect(java.net.InetSocketAddress(host, port), 400)
                soTimeout = 1500
            }
        }.onFailure {
            Log.w(TAG, "远程深度：连不上 $key（${it.message}），下一帧再试")
        }.getOrNull().also { sock = it }
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
        inferSumMs += lastInferMs
        inferSamples++
        if (lastInferMs > inferMaxMs) inferMaxMs = lastInferMs

        ob.rewind()
        val n = outW * outH
        val vals = if (valsBuf.size == n) valsBuf else FloatArray(n).also { valsBuf = it }
        for (i in 0 until n) vals[i] = ob.float
        finishDepth(vals, outW, outH, seq)
    }

    /**
     * 推理之后共用的收尾：参考范围归一化 → 切镜判定 → 时间平滑 → 交给原生。
     *
     * 本地和远程两条推理路径都走这里，保证两边出来的深度口径一致，
     * 切换时才只是"清晰度和延迟"的差别，不会因为两套处理而看出别的差异。
     */
    private fun finishDepth(vals: FloatArray, ow: Int, oh: Int, seq: Long) {
        val n = vals.size
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until n) {
            val v = vals[i]
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

        val out = if (outU8Buf.size == n) outU8Buf else ByteArray(n).also { outU8Buf = it }
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
            /*
             * 一行把 A/B 需要的三样都带上：本次耗时、最近 20 次的平均/最大、当前档位。
             * 平均比单次可信 —— 单次最快值会被缓存和调度带偏。
             */
            val avg = if (inferSamples > 0) inferSumMs / inferSamples else 0L
            Log.i(TAG, "深度推理：本次 ${lastInferMs}ms｜最近 $inferSamples 次 平均 ${avg}ms 最大 ${inferMaxMs}ms"
                    + "｜后端 ${if (gpu != null) "显卡委托" else "CPU ${VrTuning.depthThreads} 线程"}"
                    + "｜模型 ${if (inW == 512) "512x288" else "${inH}x${inW}"} 深度 ${ow}x${oh}")
            inferSumMs = 0L
            inferMaxMs = 0L
            inferSamples = 0
            Log.i(TAG, "深度切镜：diff " + "%.3f".format(lastDiff) +
                    " hist " + "%.3f".format(lastHist) +
                    " corr " + "%.2f".format(lastCorr) +
                    " 换镜头 ${shotCount} 次 平滑 ${smooth}")
        }
        lastOkMs = System.currentTimeMillis()
        /* 帧号原样带回原生：环形缓冲靠它把深度还给"本来就是它"的那一帧 */
        VrNative.pushDepth(out, ow, oh, seq)
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

    private fun loadModel(context: Context, asset: String): MappedByteBuffer {
        context.assets.openFd(asset).use { fd ->
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
