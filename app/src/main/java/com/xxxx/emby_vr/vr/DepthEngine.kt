package com.xxxx.emby_vr.vr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时 2D→3D 的深度推理。
 *
 * 数据流：原生渲染线程把当前画面缩到模型输入尺寸读回 CPU → 交到这里 →
 * 后台线程跑模型 → 结果回传原生上传成纹理。渲染线程只做读回，不等模型。
 *
 * 模型：ZipDepth 的 .tflite，与 DepthRay / 4XVR 同一份。
 *
 * 引擎：LiteRT 2.4（TFLite 的新一代运行时）。走 OpenCL 显卡推理 —— 旧 TFLite 的 GPU 委托
 * 在这块头显上单次 58ms，画面 50 帧（20ms/帧）配不上深度，错位的根因就在这里。
 * 4XVR 用同一个模型每帧都能配上，差别只在引擎；litert-gpu 里的
 * libLiteRtClGlAccelerator.so 就是那条链路。
 * depth_gpu=0 时走 CPU（A/B 对照用）；显卡建不起来也退回 CPU。
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
     * 推理耗时统计：每 20 次打一行平均/最大，A/B 切档之后直接对比数字，不靠一次测量下结论。
     *
     * 分四段记：喂输入（缩放+writeFloat）、提交（run 返回）、读回（readFloat，含等显卡算完）、
     * 原生自报（lastNativeInferenceDurationNanoseconds）。
     * 为什么要分段：OpenCL 是异步队列，run 返回只代表命令交出去了 —— 只测 run 会得到
     * 0~2ms 的假数字，真正花时间的是 readFloat 等结果。不分开就永远不知道慢在哪。
     */
    private var feedSumMs = 0L
    private var submitSumMs = 0L
    private var readSumMs = 0L
    private var nativeSumMs = 0L
    private var workMaxMs = 0L
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
    private var outFBuf = FloatArray(0)
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

    private var model: CompiledModel? = null
    /** 输入/输出缓冲：CompiledModel 建一次复用，每帧只写输入读输出 */
    private var ins: List<TensorBuffer>? = null
    private var outs: List<TensorBuffer>? = null
    /** 这次建的是不是显卡（OpenCL）后端；日志与"CPU 跑大模型不划算"的判断都看它 */
    private var gpuActive = false
    /** 解释器是用哪套后端/模型/线程建起来的；调参一变就重建 */
    private var initedSig = ""
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    /* 限速用：每像素"画面这里有没有边缘"（1 = 有，放宽），复用避免每帧分配 */
    private var rampAllow = FloatArray(0)
    private var lumaBuf = FloatArray(0)
    /* 归一化用 64 档直方图算 2%/98% 分位（照 4XVR 的做法），范围按 150ms 平滑 */
    private var histBuf = IntArray(0)
    private var lastNormMs = 0L
    /*
     * 模型输入按 NHWC 摊平的 RGB 0..1（512x288x3 = 44 万个数）。复用：
     * 每帧新建就是 1.7MB 垃圾，一秒 20 帧直接把头显堆顶爆（旧实现踩过 OutOfMemory）。
     */
    private var rgbFloats = FloatArray(0)
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
        runCatching { ins?.forEach { it.close() } }
        ins = null
        runCatching { outs?.forEach { it.close() } }
        outs = null
        runCatching { model?.close() }
        model = null
        gpuActive = false
        ready = false
        batchStop = true
        batchThread = null
        synchronized(pending) { pending.clear() }
        runCatching { sock?.close() }
        sock = null
        prevDepth = null
        refInit = false
        feedSumMs = 0L
        submitSumMs = 0L
        readSumMs = 0L
        nativeSumMs = 0L
        workMaxMs = 0L
        inferSamples = 0
        rgbFloats = FloatArray(0)
        inW = 0
        inH = 0
        busy.set(false)
        /* 释放后签名必须清空：否则下次 init 失败或中途换配置时，wantSig 与旧签名相等就再也不重建 */
        initedSig = ""
        if (VrNative.loaded) VrNative.setDepthInputSize(0, 0)
        Log.i(TAG, "深度推理：3D 已关，推理引擎已释放（推理线程收工）")
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
                /*
                 * 签名必须与 submitFrame 的 wantSig **逐段一致**（2026-11-08 修）。
                 *
                 * 原来这里只拼了四段（少了 depthBackend），而 wantSig 是五段 —— 两者永远
                 * 不相等，于是每一帧都判「后端换了」→ shutdown + init 无限循环：日志里
                 * 「3D 已关」和「远程模式」交替刷屏，深度帧一次都没发出去，点 15 上一个
                 * 连接都没有。表现就是深度就绪长期 0、快进后 3D 起不来、看着看着立体消失。
                 */
                initedSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|" +
                        "${VrTuning.depthThreads}|${VrTuning.depthRemote}|${VrTuning.depthBackend}"
                if (VrNative.loaded) VrNative.setDepthInputSize(inW, inH)
                Log.i(TAG, "深度推理：远程模式（点 15，输入 ${inW}x${inH}），本地解释器不建")
                return
            }
            try {
                var asset = modelAsset()
                var gpuWanted = VrTuning.depthGpu == 1
                var cm: CompiledModel? = null
                /*
                 * 显卡（OpenCL）：建不起来（驱动、显存、机型）就退回 CPU，不能让 3D 整条
                 * 链路因为这一步挂掉 —— 旧引擎在这里踩过，退回 CPU 时用大模型是这块芯片
                 * 上最慢的组合，所以下面还要换小模型。
                 */
                if (gpuWanted) {
                    cm = runCatching { createModel(context, asset, true) }
                        .onFailure {
                            Log.w(TAG, "深度推理：OpenCL 显卡建不起来，退回 CPU（${it.message}）")
                        }
                        .getOrNull()
                    if (cm == null) gpuWanted = false
                }
                /*
                 * 退回 CPU 时别继续跑大模型：fp16 那款是给显卡准备的，跑在 CPU 上比 int8
                 * 小模型慢一倍，是这块芯片上最差的一条组合。换模型只在这里做一次，
                 * 不进热路径。
                 */
                if (cm == null && autoFallback && asset.contains("512x288")) {
                    Log.w(TAG, "深度推理：CPU 跑大模型不划算，自动换回 int8 小模型（depth_auto=0 可关掉）")
                    asset = "models/zipdepth_256_int8dr.tflite"
                }
                if (cm == null) cm = createModel(context, asset, false)

                inW = if (asset.contains("512x288")) 512 else 256
                inH = if (asset.contains("512x288")) 288 else 256
                /* ZipDepth 输出与输入同尺寸（逐像素深度），不需要再查 tensor 形状 */
                outW = inW
                outH = inH

                ins = cm.createInputBuffers(0)
                outs = cm.createOutputBuffers(0)
                rgbFloats = FloatArray(inW * inH * 3)
                model = cm
                gpuActive = gpuWanted
                ready = true
                initedSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|${VrTuning.depthThreads}|${VrTuning.depthRemote}|${VrTuning.depthBackend}"
                val backendName = if (gpuWanted) "LiteRT 显卡（OpenCL）" else "LiteRT CPU ${VrTuning.depthThreads} 线程"
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
        val wantSig = "${VrTuning.depthGpu}|${VrTuning.depthModel}|${VrTuning.depthThreads}|${VrTuning.depthRemote}|${VrTuning.depthBackend}"
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
        /*
         * 上行永远是压缩 JPEG；回传格式按调参 depth_bits 选（默认 16）：
         *  8  → B0BK：灰度 JPEG，约 20KB
         *  16 → B0BP：uint16 + zlib，约 80~150KB
         *  32 → B0BF：原始 float32，590KB
         * 三种回传都由服务端回「宽、高、长度」八字节头再跟载荷；32 位那路曾经少回长度，
         * 客户端按错位字节读出一 GB 级长度当场分配失败，深度一帧不到（2026-11-27 服务端已补）。
         */
        val bits = VrTuning.depthBits
        when (bits) {
            32 -> head.write(byteArrayOf(0x42, 0x30, 0x42, 0x46))   // B0BF
            16 -> head.write(byteArrayOf(0x42, 0x30, 0x42, 0x50))   // B0BP
            else -> head.write(byteArrayOf(0x42, 0x30, 0x42, 0x4B)) // B0BK
        }
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
            val payload = ByteArray(ln)
            readFully(inp, payload, ln)
            if (bits >= 16) {
                /* 16 位：zlib 压的 uint16；32 位：原始 float32 */
                val cnt = ow * oh
                if (cnt <= 0) continue
                val vals = if (valsBuf.size == cnt) valsBuf else FloatArray(cnt).also { valsBuf = it }
                if (bits >= 32) {
                    if (ln != cnt * 4) continue
                    java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().get(vals, 0, cnt)
                } else {
                    val raw = java.io.ByteArrayOutputStream(cnt * 2)
                    val inf = java.util.zip.Inflater()
                    inf.setInput(payload)
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val got = inf.inflate(buf)
                        if (got <= 0) break
                        raw.write(buf, 0, got)
                    }
                    inf.end()
                    val bytes = raw.toByteArray()
                    if (bytes.size != cnt * 2) continue
                    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until cnt) {
                        vals[i] = (bb.getShort().toInt() and 0xFFFF) / 65535f
                    }
                }
                lastInferMs = (SystemClock.elapsedRealtime() - f.ts)
                finishDepth(vals, ow, oh, f.seq, emitFloat = true, precision = bits)
                continue
            }
            val jp = payload
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
        val cm = model ?: return
        val inputs = ins ?: return
        val outputs = outs ?: return
        if (rgba.size < w * h * 4) return

        /*
         * 最近邻缩放到模型输入尺寸，RGB 归一到 0..1，按 NHWC 摊平。
         * 不做双线性：这点差别看不出来（后面还有引导上采样把边缘贴回去），
         * 最近邻省下的时间够多跑一次推理。
         */
        val rgb = rgbFloats
        if (rgb.size != inW * inH * 3) return
        var o = 0
        for (y in 0 until inH) {
            val sy = (y.toLong() * h / inH).toInt()
            val row = sy * w
            for (x in 0 until inW) {
                val sx = (x.toLong() * w / inW).toInt()
                val i = (row + sx) * 4
                rgb[o++] = (rgba[i].toInt() and 0xFF) / 255f
                rgb[o++] = (rgba[i + 1].toInt() and 0xFF) / 255f
                rgb[o++] = (rgba[i + 2].toInt() and 0xFF) / 255f
            }
        }
        val tFeed0 = SystemClock.elapsedRealtime()
        inputs[0].writeFloat(rgb)
        val tSubmit0 = SystemClock.elapsedRealtime()
        cm.run(inputs, outputs, 0)
        val tSubmit1 = SystemClock.elapsedRealtime()

        /*
         * readFloat 每次返回新数组（LiteRT 没有读进现有数组的接口），147k 个数约 590KB。
         * 它同时是**等显卡算完**的地方：OpenCL 队列异步，run 返回时结果通常还没出来。
         */
        val raw = outputs[0].readFloat()
        val tRead1 = SystemClock.elapsedRealtime()

        val feedMs = tSubmit0 - tFeed0
        val submitMs = tSubmit1 - tSubmit0
        val readMs = tRead1 - tSubmit1
        val nativeMs = cm.lastNativeInferenceDurationNanoseconds / 1_000_000
        feedSumMs += feedMs
        submitSumMs += submitMs
        readSumMs += readMs
        nativeSumMs += nativeMs
        val work = submitMs + readMs
        if (work > workMaxMs) workMaxMs = work
        inferSamples++
        /* 对外的一行仍报"这一帧从提交到拿到结果"，与旧引擎 58ms 同一口径 */
        lastInferMs = work
        val n = outW * outH
        val vals = if (valsBuf.size == n) valsBuf else FloatArray(n).also { valsBuf = it }
        System.arraycopy(raw, 0, vals, 0, minOf(n, raw.size))
        buildRampAllow(rgba, w, h)
        finishDepth(vals, outW, outH, seq)
    }

    /**
     * 建引擎：显卡走 GPU + OpenCL（4XVR 同一条链路），CPU 按调参线程数。
     *
     * 只在这里设后端，不在别的分支再拼一套 options —— 两处配置同一个模型，
     * 换档时容易两边不一致。
     */
    private fun createModel(context: Context, asset: String, gpu: Boolean): CompiledModel {
        val opts = if (gpu) {
            CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(
                    backend = CompiledModel.GpuOptions.Backend.OPENCL,
                )
            }
        } else {
            CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = VrTuning.depthThreads)
            }
        }
        return CompiledModel.create(context.assets, asset, opts)
    }

    /**
     * 推理之后共用的收尾：参考范围归一化 → 切镜判定 → 时间平滑 → 交给原生。
     *
     * 本地和远程两条推理路径都走这里，保证两边出来的深度口径一致，
     * 切换时才只是"清晰度和延迟"的差别，不会因为两套处理而看出别的差异。
     */
    /** 直方图分位：返回第 q 分位对应的值（0..1）。total 为样本总数。 */
    private fun quantile(hist: IntArray, total: Int, q: Float): Float {
        val target = (total * q).toInt().coerceIn(1, total)
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i.toFloat() / (hist.size - 1)
        }
        return 1f
    }

    /**
     * 标记"画面里的强边缘"（每像素 0 或 1）。
     *
     * 用当前帧亮度做水平梯度：梯度大 = 真实物体轮廓，位移允许在那里跳；
     * 梯度小 = 平坦区（雾、天空、墙面），位移必须慢变，否则一步跳过去就是水渍状拉伸。
     * 结果做一次 3 像素膨胀，让轮廓两侧都放宽，避免把边削掉。
     */
    private fun buildRampAllow(rgba: ByteArray, w: Int, h: Int) {
        val n = outW * outH
        if (n <= 0 || rgba.size < w * h * 4) return
        if (lumaBuf.size != n) lumaBuf = FloatArray(n)
        if (rampAllow.size != n) rampAllow = FloatArray(n)
        for (y in 0 until outH) {
            val sy = (y.toLong() * h / outH).toInt()
            val row = sy * w
            for (x in 0 until outW) {
                val sx = (x.toLong() * w / outW).toInt()
                val i = (row + sx) * 4
                lumaBuf[y * outW + x] = ((rgba[i].toInt() and 0xFF) * 299 +
                        (rgba[i + 1].toInt() and 0xFF) * 587 +
                        (rgba[i + 2].toInt() and 0xFF) * 114) / 1000f
            }
        }
        for (y in 0 until outH) {
            val base = y * outW
            for (x in 0 until outW) {
                val l = lumaBuf[base + x]
                var edge = false
                if (x > 0 && kotlin.math.abs(l - lumaBuf[base + x - 1]) > 18f) edge = true
                if (!edge && x < outW - 1 && kotlin.math.abs(lumaBuf[base + x + 1] - l) > 18f) edge = true
                rampAllow[base + x] = if (edge) 1f else 0f
            }
        }
    }

    /**
     * 位移场水平限速（`stereo_ramp` > 0 时生效）。
     *
     * 左右各扫一遍，相邻像素深度差不超过 step；有画面边缘的像素放宽到 step*8，
     * 真实轮廓因此保留，平坦区的硬跳变被摊成几十像素的缓坡，画面不会再"隔着水渍"。
     */
    /** 与 limitRamp 同一套限速规则，作用在浮点缓冲区上（0..255 量纲，阈值口径不变） */
    private fun limitRampF(out: FloatArray, ow: Int, oh: Int) {
        val ramp = VrTuning.stereoRamp
        if (ramp <= 0) return
        val relaxed = ramp * 8
        val has = rampAllow.size == ow * oh
        for (y in 0 until oh) {
            val base = y * ow
            for (x in 1 until ow) {
                val step = if (has && rampAllow[base + x] > 0.5f) relaxed else ramp
                val p = out[base + x - 1]
                val c = out[base + x]
                if (c > p + step) out[base + x] = p + step
                else if (c < p - step) out[base + x] = p - step
            }
            for (x in ow - 2 downTo 0) {
                val step = if (has && rampAllow[base + x] > 0.5f) relaxed else ramp
                val n2 = out[base + x + 1]
                val c = out[base + x]
                if (c > n2 + step) out[base + x] = n2 + step
                else if (c < n2 - step) out[base + x] = n2 - step
            }
        }
    }

    private fun limitRamp(out: ByteArray, ow: Int, oh: Int) {
        val ramp = VrTuning.stereoRamp
        if (ramp <= 0) return
        val relaxed = ramp * 8
        val has = rampAllow.size == ow * oh
        for (y in 0 until oh) {
            val base = y * ow
            for (x in 1 until ow) {
                val step = if (has && rampAllow[base + x] > 0.5f) relaxed else ramp
                val prev = out[base + x - 1].toInt() and 0xFF
                val cur = out[base + x].toInt() and 0xFF
                if (cur > prev + step) out[base + x] = (prev + step).toByte()
                else if (cur < prev - step) out[base + x] = (prev - step).toByte()
            }
            for (x in ow - 2 downTo 0) {
                val step = if (has && rampAllow[base + x] > 0.5f) relaxed else ramp
                val next = out[base + x + 1].toInt() and 0xFF
                val cur = out[base + x].toInt() and 0xFF
                if (cur > next + step) out[base + x] = (next + step).toByte()
                else if (cur < next - step) out[base + x] = (next - step).toByte()
            }
        }
    }

    /**
     * emitFloat = true 时按浮点/半浮点直接回传原生（精度由 precision 决定：16 或 32），
     * 否则仍按 8 位字节回传（8 位档与本地推理那条路）。
     */
    private fun finishDepth(
        vals: FloatArray,
        ow: Int,
        oh: Int,
        seq: Long,
        emitFloat: Boolean = false,
        precision: Int = 8,
    ) {
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
        /*
         * 逐帧分位归一化（照 4XVR）：取 2%/98% 分位拉满量程，范围按 150ms 时间常数平滑，
         * 切镜时由下面的 jumpStreak 把 refInit 置假、直接跳到新范围。
         * 用 64 档直方图近似分位 —— 147k 个元素排序太慢，直方图一遍扫完。
         */
        val buckets = 64
        if (histBuf.size != buckets) histBuf = IntArray(buckets)
        java.util.Arrays.fill(histBuf, 0)
        for (i in 0 until n) {
            val b = (vals[i] * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
            histBuf[b]++
        }
        val lo2 = quantile(histBuf, n, 0.02f)
        val hi98 = quantile(histBuf, n, 0.98f)
        val nowN = SystemClock.elapsedRealtime()
        if (refInit) {
            val dt = ((nowN - lastNormMs).coerceAtLeast(1L)) / 1000f
            val aN = 1f - kotlin.math.exp(-dt / 0.150f)
            refMn += (lo2 - refMn) * aN
            refMx += (hi98 - refMx) * aN
        } else {
            refMn = lo2
            refMx = hi98
            refInit = true
        }
        lastNormMs = nowN
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
        /*
         * 浮点档要保住精度：量化到 8 位只是给"上一帧平滑"和限速用的中间量，
         * 真正回传的是这里按 0..255 连续值保存的浮点数组。
         */
        val outF = if (outFBuf.size == n) outFBuf else FloatArray(n).also { outFBuf = it }
        for (i in 0 until n) {
            var v = (vals[i] - useMn) / range
            if (invert) v = 1f - v
            var b = v.coerceIn(0f, 1f) * 255f
            /* 尺寸变了（换片源 / 模型换了）就直接用新值，别拿旧图去插值 */
            if (prev != null && prev.size == n && a < 1f) {
                b = prev[i].toInt().and(0xFF) * (1f - a) + b * a
            }
            outF[i] = b.coerceIn(0f, 255f)
            out[i] = b.toInt().coerceIn(0, 255).toByte()
        }
        limitRamp(out, ow, oh)
        limitRampF(outF, ow, oh)
        if (emitFloat) {
            val f = FloatArray(n)
            for (i in 0 until n) f[i] = outF[i] / 255f
            VrNative.pushDepthF32(f, ow, oh, seq, if (precision >= 32) 2 else 1)
        }
        prevDepth = out
        inferCount++
        if (inferCount % 20 == 1) {
            /*
             * 一行把 A/B 需要的三样都带上：本次耗时、最近 20 次的平均/最大、当前档位。
             * 平均比单次可信 —— 单次最快值会被缓存和调度带偏。
             */
            val n20 = if (inferSamples > 0) inferSamples else 1
            Log.i(TAG, "深度推理：本次 ${lastInferMs}ms（提交 ${submitSumMs / n20} 读回 ${readSumMs / n20}）"
                    + "｜最近 $inferSamples 次 平均 喂入 ${feedSumMs / n20} 原生 ${nativeSumMs / n20} 最大 ${workMaxMs}ms"
                    + "｜后端 ${if (gpuActive) "LiteRT 显卡（OpenCL）" else "LiteRT CPU ${VrTuning.depthThreads} 线程"}"
                    + "｜模型 ${if (inW == 512) "512x288" else "${inH}x${inW}"} 深度 ${ow}x${oh}")
            feedSumMs = 0L
            submitSumMs = 0L
            readSumMs = 0L
            nativeSumMs = 0L
            workMaxMs = 0L
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

}
