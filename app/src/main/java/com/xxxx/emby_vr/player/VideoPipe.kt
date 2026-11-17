package com.xxxx.emby_vr.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自管视频解码：MediaCodec 直接解到 SurfaceTexture。
 *
 * 为什么要有它：4XVR 的 4K 杜比 P5 每帧只花 14~22 毫秒，我们的 mpv 链路要 68 毫秒。
 * 拆开看，差别不在有没有硬解，而在 mpv 把画面渲染出去那一层还跑了一整套色彩管理，
 * 而且每帧要多搬一趟 4K。绕开 mpv 的这层输出（vo），解码出的画面就直接是渲染层
 * 要采样的那张 OES 纹理，中间不再经过任何一次搬运或转换。
 *
 * 输入是**裸码流**，不是完整文件：容器解复用由 [MediaExtractor] 完成（它能直接吃
 * http 地址并带 header，Emby 的认证就靠这个）。容器里剩下的活只有视频轨。
 *
 * 音频与播放控制暂不在这里，理由见 docs/handoffs/b0bemby-vr-videopipe-2026-11-15.md
 * 的「分阶段」：先把画面通起来，再谈同步。
 */
class VideoPipe {

    companion object {
        private const val TAG = "B0BEmbyVR-VideoPipe"

        /** 解码器输出缓冲等不到时最多睡这么久，避免空转吃满一个核 */
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * 落后多少就丢帧：施工图定的是 P95+20ms（P95 = 4K 解码延迟基线）。
         * 初值 80ms，实测后按 P95 替换 —— 这不是可调参数，是同步上限。
         */
        private const val DROP_BEHIND_SEC = 0.080

        @Volatile
        var lastError: String? = null
            private set
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 播放位置来源（音频侧）。为空时按自己的时间戳放。 */
    @Volatile private var clockProvider: (() -> Double)? = null

    /**
     * 这个时钟是否已经可用。
     *
     * 不能用「位置 > 0」判断：起播位置本身可能就是 0，那样会被当成「时钟还没开始」
     * 而跳过同步，画面一路快进（2026-11-16 父亲实测「播放速度没控制」）。
     */
    @Volatile private var clockValidProvider: (() -> Boolean)? = null

    /** 卡顿诊断：返回（推进窗口时长 ms，窗口内累计增量 s） */
    @Volatile private var clockProbe: (() -> Pair<Long, Double>)? = null

    /** 卡顿诊断：最新位置采样的年龄（ms）——直接看出是不是"新鲜度"判掉的 */
    @Volatile private var clockAgeProvider: (() -> Long)? = null

    /*
     * 重启目标只能取**真实读数**（外推可以判帧，不许命令播放器跳）。
     * 用外推值当 seek 目标会跳到超前处 → 解出来全是超前帧 → 等待/丢 → 又落后 → 又重启，
     * 现场就是"每几秒跳一次"。
     */
    @Volatile private var clockRealProvider: (() -> Double)? = null


    /**
     * 是否正在被人为停止。
     *
     * 存在的理由（2026-11-17 实测）：`stop()` 掉的实例，解码器可能补一个 EOS 标志，
     * 我们据此回调 onEos → 上层把 mpv 暂停 → 主时钟停走 → **新实例持帧等到死**（画面永久冻住）。
     * 现场日志：`失步重启（先停旧实例）` 与 `画面已到结尾 → 内核暂停收场` 同一毫秒。
     */
    @Volatile private var stopping = false

    /**
     * 杜比参数落盘路径（与内核那份同一个文件，渲染层读它做还原）。
     *
     * 自管解码之后内核不再解视频，也就没人写这份参数了 —— 这里补上：
     * 每帧从码流里解出 RPU，参数变了才落盘（渲染层按 mtime 判断要不要重读）。
     */
    @Volatile private var doviParamPath: String? = null

    /** 上一份写出去的参数，用来判断变没变 */
    private var lastDovi: FloatArray? = null

    /**
     * 首帧之后的闸门：返回 true 时先把画面停住（声音还没开始，画面不能自己往前跑）。
     *
     * 自管解码起播时，第一帧解出来要立刻显示，但后面不能继续放 —— 声音那边还挂在
     * 起播位置等我们，画面先跑就变成有画无声的假播放，等声音放开再对齐会明显跳一下。
     */
    @Volatile private var holdGate: (() -> Boolean)? = null

    /*
     * 出帧资源账（施工图 §5 I1-I4）。
     *
     * `heldIdx` 是**唯一**的持有状态：-1 = 没持有。MediaCodec 只在 release 之后才把格子还回来，
     * 所以"取到帧不归还又回头再取"会一帧一帧把池子占满（实测 880ms 占满 → 恒 TRY_AGAIN → 0 帧）。
     * 禁止再出现"持有布尔 + 下标"两件套 —— 两个东西必然不同步。
     */
    private var heldIdx = -1

    /** 预热帧数（图 §4.2.1 ③：三帧；首帧慢、RPU 常到第三帧才出） */
    private var preheatFrames = 3
    private var preheatCount = 0
    private var preheatFirstPts = -1.0
    private var preheatLastPts = -1.0
    /** 预热是否已完成（完成之前不上屏、放行信号也还不该理） */
    @Volatile private var preheated = false
    /** 预热完成时回调：三帧跨度（ms）、RPU 是否已解出 */
    @Volatile private var onPreheatReady: ((Long, Boolean) -> Unit)? = null
    /** 连续取不到输出帧的次数（成功一次就清零）：只进健康行做可见性，不据此判死 */
    @Volatile var tryAgainStreak = 0L
        private set
    /** 累计取帧成功次数（健康行用） */
    @Volatile var dequeuedFrames = 0L
        private set
    /** 最近一次上屏的时刻（elapsedRealtime）——没有主时钟时按墙钟自走用 */
    @Volatile private var lastShownAtMs = 0L
    /** 最近一次上屏帧的换算位置（秒；-1 = 还没上过屏），健康行算 nexus 用 */
    @Volatile var lastShownPts = -1.0
        private set

    /** 当前持有的格子（-1 = 没持有）。健康行只做可见性，不作判据 */
    fun heldSlot(): Int = heldIdx

    /** 杜比参数解出来没有（颜色是否已具备还原条件） */
    fun rpuReadyNow(): Boolean = lastDovi != null

    /** 首帧上屏只回调一次 */
    private var showLogged = false
    /** 最近一次入队样本的 pts（秒）：RPU 参数到达日志用 */
    @Volatile private var lastSeenInputPts = -1.0
    /** 本轮起解（含重建）的时刻：给"RPU 参数到达|距重建="算差值 */
    @Volatile private var startedAtMs = 0L

    /** 首帧已送进画面。用来通知外面放开声音。 */

    /** 首帧出来时回调一次（外面在这里放开声音） */
    @Volatile private var onFirstFrame: (() -> Unit)? = null

    /** 第一次解出杜比参数时回调一次（外面据此确认"本片确实是 P5"，才允许走还原） */
    @Volatile private var onDoviParams: (() -> Unit)? = null

    /**
     * 画面解到结尾时回调一次（2026-11-16 父亲定：新路径要主动告诉内核"放完了"）。
     *
     * 内核那边视频轨是关掉的，它自己只能等到音频播完；两边时间轴不一致时，
     * 界面就要靠这个信号收场，否则会出现"画面早就没了、控制条还在走"。
     */
    @Volatile private var onEos: (() -> Unit)? = null

    /**
     * 失步兜底：偏差持续过大、靠丢帧追不回来时，请外面"从主时钟当前位置重启"（图 §5 失步兜底）。
     * 参数是建议的重启位置（秒）。
     */
    @Volatile private var onNeedRestart: ((Double) -> Unit)? = null

    /** 复用的取样缓冲：每帧临时 new 一个几 MB 的数组会把 GC 拖垮 */
    private val sampleBuf = ByteArray(8 * 1024 * 1024)

    /** 参数落盘的最小间隔（帧）：每帧都写会让渲染层每帧重读文件 */
    private var doviWriteSkip = 0

    /** 解不出参数的前几帧各打一条日志，别刷屏 */
    private var doviMissLog = 0

    /*
     * 零点标定（图 §5：appliedOffset 冻结 / observedOffsetN 只诊断）。
     *
     * originPts = 预卷后第一帧的容器 PTS；appliedOffset = audioStart - originPts，
     * 标定一次后**冻结**，后续所有帧都用它换算，不再重算 ——
     * 重算会让已经上屏的帧与新基准不一致，表现是每部片偏差都不同、画面忽快忽慢。
     */
    /** 本次起播的锚点位置（秒）：标定与预卷判定都对着它，pump 里要用所以存成字段 */
    @Volatile private var audioStartSec = 0.0
    private var originPts = -1.0
    private var appliedOffset = Double.NaN
    /** 预卷帧（pts < audioStart，不许上屏）丢弃计数，写日志用 */
    private var prerollDropped = 0
    /** 偏差首次超过 500ms 的时刻（elapsedRealtime）；连续 3s 未追平才请求重启 */
    private var desyncSinceMs = 0L
    /** 时钟无效时连续挂起的次数（只用于日志节流；何时收场由上层按"无进度"判） */
    private var droppedNoClock = 0
    /** 首帧已真正上屏（≠ 已解出）：节拍判断只在它之后生效 */
    @Volatile private var firstFrameShown = false

    /** 解码出来的实际宽高，起播后才有值 */
    @Volatile var videoWidth = 0
        private set
    @Volatile var videoHeight = 0
        private set

    /** **已上屏**帧数（render=true 才算）；健康行 `decoded=` 用它，判"有没有出画"不能再被丢弃污染 */
    @Volatile var decodedFrames = 0L
        private set
    /** 所有归还的帧（上屏 + 丢弃 + 预热）——诊断用，健康行 `released=` */
    @Volatile var releasedFrames = 0L
        private set

    /**
     * 起播。
     *
     * @param url     视频地址（Emby 直链；容器可以是 mp4/mkv，解复用交给系统）
     * @param headers 需要带给服务器的头（Emby 的 token 走这个，别拼进 URL）
     * @param surface 目标画面。必须来自渲染线程建的那张 OES 纹理对应的 SurfaceTexture，
     *                否则画面会贴到别处或者干脆不显示
     * @param clock   播放位置（秒）。音频由 mpv 播，画面以它为准做同步：超前就等、
     *                落后就立刻放。传 null 时退回按自己的时间戳走（会与音频漂移）。
     * @param startSec 起播位置（秒）。续播时两边必须从同一处开始，否则画面会把前面的
     *                内容快速冲过去才追上声音。
     * @return 起播是否成功（失败原因写 [lastError]）
     */
    fun start(
        url: String,
        headers: Map<String, String>,
        surface: Surface,
        clock: (() -> Double)? = null,
        clockValid: (() -> Boolean)? = null,
        startSec: Double = 0.0,
        doviPath: String? = null,
        holdGate: (() -> Boolean)? = null,
        /** 卡顿诊断用（可选）：返回推进窗口时长与窗口内累计增量 */
        clockProbe: (() -> Pair<Long, Double>)? = null,
        /** 卡顿诊断用（可选）：返回最新位置采样年龄 ms */
        clockAgeProvider: (() -> Long)? = null,
        /** 重启目标用的真实位置（不含外推）；缺省退回 clock */
        clockReal: (() -> Double)? = null,
        /** 起播基准对不齐时回调：请上层把声音跳到这个位置 */
        onAudioAlign: ((Double) -> Unit)? = null,
        onFirstFrame: (() -> Unit)? = null,
        onDoviParams: (() -> Unit)? = null,
        onEos: (() -> Unit)? = null,
        onNeedRestart: ((Double) -> Unit)? = null,
        /** 预热帧数（图 §4.2.1 ③） */
        preheatFrames: Int = 3,
        /** 预热完成（三帧全部解出并归还）→ 报跨度与 RPU 状态，等外部放行 */
        onPreheatReady: ((Long, Boolean) -> Unit)? = null,
    ): Boolean {
        /*
         * 旧实例必须先确认退出。`stop()` 返回 false = 旧线程还活着、可能仍占着 MediaCodec
         * 与 surface 连接；这时新建会让两路解码抢同一张画面（图 §5 I4）。
         * 宁可不重启，也不进那个状态。
         */
        if (!stop()) {
            lastError = "zombie=旧解码线程未退出，拒绝新建（需重启应用）"
            Log.e(TAG, lastError!!)
            return false
        }
        lastError = null
        clockProvider = clock
        clockValidProvider = clockValid
        doviParamPath = doviPath
        this.holdGate = holdGate
        this.clockProbe = clockProbe
        this.clockAgeProvider = clockAgeProvider
        this.clockRealProvider = clockReal
        this.onAudioAlign = onAudioAlign
        stopping = false
        this.onFirstFrame = onFirstFrame
        this.onDoviParams = onDoviParams
        this.onEos = onEos
        this.onNeedRestart = onNeedRestart
        firstFrameShown = false
        heldIdx = -1
        this.preheatFrames = preheatFrames.coerceAtLeast(1)
        preheatCount = 0
        preheatFirstPts = -1.0
        preheatLastPts = -1.0
        preheated = false
        this.onPreheatReady = onPreheatReady
        tryAgainStreak = 0L
        showLogged = false
        pendingSeekSec = -1.0
        readySignalled = false
        preheatCount = 0
        preheatFirstPts = -1.0
        preheatLastPts = -1.0
        droppedStale = 0
        forcedRender = 0
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        lastDovi = null
        doviWriteSkip = 0
        /*
         * 起播先删旧参数文件。
         *
         * 内核那边也往同一个路径写过参数，但格式与版本号都不一样；留着它，
         * 渲染层在本进程写出第一份之前会一直读到那份错位的数据，
         * 而「版本>0.5」这种宽松判断拦不住（内核写 2 也过），画面就偏紫。
         */
        runCatching { doviPath?.let { java.io.File(it).delete() } }
        audioStartSec = startSec
        originPts = -1.0
        appliedOffset = Double.NaN
        prerollDropped = 0
        desyncSinceMs = 0L
        return try {
            val ex = MediaExtractor()
            if (headers.isEmpty()) {
                ex.setDataSource(url)
            } else {
                ex.setDataSource(url, headers)
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                ex.release()
                lastError = "容器里没有视频轨"
                Log.e(TAG, lastError!!)
                return false
            }
            ex.selectTrack(trackIndex)
            if (startSec > 0.5) {
                // 续播：跳到与声音相同的位置。不按关键帧对齐会从更早的地方开始，
                // 那样画面要先冲过一段才追上声音。
                ex.seekTo((startSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                Log.i(TAG, "跳到 ${"%.1f".format(startSec)} 秒起播")
            }
            extractor = ex

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            /*
             * 不传 Crypto，也不设 KEY_COLOR_* —— 杜比 P5 的颜色要由我们自己的着色器算，
             * 这里让它按普通 HEVC 解即可。硬件若擅自做色调映射，画面会显得亮度被压平，
             * 那属于要在实测里发现的问题，不在这里猜。
             */
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "起播 $mime ${videoWidth}x$videoHeight")

            running.set(true)
            thread = Thread({ pump() }, "VideoPipe").apply {
                priority = Thread.NORM_PRIORITY + 2
                start()
            }
            /*
             * 监视线程：主循环卡在某个调用上时，解码线程本身打不出日志，
             * 由它每 2 秒报一次读流计数，能分清是"读数据卡住"还是"要不到输入缓冲"。
             */
            Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(2000)
                    } catch (t: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (!running.get()) break
                    Log.i(
                        TAG,
                        "PIPEREAD|样本=$statSamples|字节=$statBytes|读返回=$statLastReadRet" +
                            "|要不到输入格=$statInMiss|取不到输出帧=$statOutMiss|held=$heldIdx",
                    )
                }
            }, "PipeWatch").apply { isDaemon = true; start() }
            Log.i(TAG, "PipeWatch 已启动（每 2s 报读流计数）")
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "起播失败：$lastError")
            stop()
            false
        }
    }

    /** 解码主循环：喂码流、取画面，按时间轴送出去，直到停止或码流结束。 */
    /**
     * 解码主循环：喂码流、取画面，按时间轴送出去，直到停止或码流结束。
     *
     * **出帧资源账（施工图 §5 的 I1-I4，2026-11-17 按图重写）**
     *  · 输出缓冲是有限池，MediaCodec 只在 release 之后才还回来；
     *  · I1 至多持有一帧：`heldIdx` 是**唯一**的持有状态（-1 = 没持有），归还后立刻置 -1；
     *  · I2 取帧前置条件唯一：只有 `heldIdx < 0` 时才允许 `dequeueOutputBuffer`；
     *  · I3 持有期间只允许 release 或**原地等待且不取帧**
     *    （老实现是"睡 5ms 再回去取"，880ms 就占满池子 → 恒 TRY_AGAIN → 0 帧、有声无画）；
     *  · 门卡在**取输出缓冲之前**：门关着整轮跳过 —— 不取、不喂、不动。
     */
    private fun pump() {
        Log.i(TAG, "pump 开始 running=${running.get()} gate=${holdGate?.invoke()}")
        val ex = extractor ?: run { Log.e(TAG, "pump 退出：extractor 为空"); return }
        val codec = decoder ?: run { Log.e(TAG, "pump 退出：decoder 为空"); return }
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        /*
         * 可选探测（调参 `pipe_pool_probe=1`）：量这台机器这部片到底给了几个输出格。
         * 连续取帧不归还 → 取不到为止 → 数目就是池子大小；探完全部按"不上屏"归还，不影响播放。
         */
        if (com.xxxx.emby_vr.vr.VrTuning.pipePoolProbe == 1) {
            val held = ArrayList<Int>()
            var guard = 0
            while (guard++ < 64) {
                val idx = try {
                    codec.dequeueOutputBuffer(info, 5_000L)
                } catch (t: Throwable) {
                    -1
                }
                if (idx >= 0) held.add(idx) else break
            }
            for (i in held) runCatching { codec.releaseOutputBuffer(i, false) }
            Log.i(TAG, "解码器输出缓冲池=${held.size} 格（探测完成，已全部归还；pipe_pool_probe=1 才会探）")
        }

        while (running.get() && !outputDone) {
            /* 待办：解码器内跳转（在两次 buffer 操作之间做，flush 时手里不能攥着 buffer） */
            val wantSeek = pendingSeekSec
            if (wantSeek >= 0.0) {
                pendingSeekSec = -1.0
                seekInPlace(wantSeek)
            }
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx < 0) {
                    statInMiss++
                } else {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = ex.readSampleData(buf, 0)
                    statLastReadRet = size.toLong()
                    if (size >= 0) {
                        statSamples++
                        statBytes += size
                    }
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = ex.sampleTime
                        /*
                         * 趁这帧还在手里，先解出杜比参数。
                         *
                         * 必须在 queueInputBuffer 之前读：入队之后缓冲归解码器所有，
                         * 内容随时可能被改写。**参数来自输入端**，所以"预热三帧全部归还、
                         * 不上屏"不影响颜色 —— 这是不留首帧的依据。
                         */
                        lastSeenInputPts = pts / 1_000_000.0
                        harvestDovi(buf, size)
                        codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                        ex.advance()
                    }
                }
            }

            /* I2：持有帧时绝不取下一帧。走到这里说明上一轮没归还，按错误处理并强制归还 */
            if (heldIdx >= 0) {
                Log.e(TAG, "ASSERT 持有帧未归还又取帧（heldIdx=$heldIdx）→ 丢弃该帧后继续")
                runCatching { codec.releaseOutputBuffer(heldIdx, false) }
                heldIdx = -1
            }

            val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (outIdx < 0 && outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) statOutMiss++
            when {
                outIdx >= 0 -> {
                    heldIdx = outIdx
                    dequeuedFrames++
                    if (outputFrame(codec, info, outIdx)) {
                        outputDone = true
                    }
                    /* 归还后立刻清账：任何分支都必须恰好 release 一次（I1） */
                    heldIdx = -1
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    videoWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "输出格式确定 ${videoWidth}x$videoHeight")
                }
                /* INFO_TRY_AGAIN_LATER：这一轮没东西，继续（次数进健康行，不据此判死） */
                else -> tryAgainStreak++
            }
        }
        /*
         * 画面放完了：告诉外面一次（父亲 2026-11-16 要求）。
         * 正常播完与被停止都会走到这里，外面按"是不是还在播"决定要不要当结束处理。
         */
        /* 被人为停止的实例不许回调"播完了"——否则会把内核暂停，新实例直接等死 */
        if (outputDone && !stopping) runCatching { onEos?.invoke() }
        Log.i(TAG, "解码循环结束，共 $decodedFrames 帧（预卷丢弃=$prerollDropped tryAgain=$tryAgainStreak）")
        /*
         * 图 §5 I4：**释放权归线程自己** —— 线程退出时释放自己持有的帧与解码器。
         * 外部 `join` 超时不得代它 release（那等于拆一个正在用的实例）。
         */
        if (heldIdx >= 0) {
            runCatching { decoder?.releaseOutputBuffer(heldIdx, false) }
            heldIdx = -1
        }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor?.release() }
        decoder = null
        extractor = null
    }

    /**
     * 处理一帧输出缓冲（调用方已把 outIdx 记进 `heldIdx`）。返回 true = 要求解码循环结束。
     *
     * 所有分支都**恰好 release 一次**：预热帧归还不上屏；时钟无效时**持有这一帧原地等**、
     * 不再取下一帧；节拍到了才上屏。
     */
    /**
     * 处理一帧输出缓冲（调用方已把 outIdx 记进 `heldIdx`）。返回 true = 要求解码循环结束。
     *
     * **2026-11-17 父亲定：约束一条条加，先把花活删干净。** 现在只有两条规则：
     *  ① 主时钟可用时，偏差在 ±0.5 秒内 → 照常上屏（不做"超前等/落后丢"的分档）；
     *  ② 偏差超过 0.5 秒 → **在同一个解码器里跳过去**（extractor.seekTo + codec.flush），
     *     不重建解码器、不空转丢帧。
     * 时钟不可用（没有读数）时**不压画面**：按最后位置 + 墙钟自己走，照常出帧。
     */
    private fun outputFrame(codec: MediaCodec, info: MediaCodec.BufferInfo, outIdx: Int): Boolean {
        val ptsRaw = info.presentationTimeUs / 1_000_000.0
        tryAgainStreak = 0L

        fun finish(render: Boolean): Boolean {
            codec.releaseOutputBuffer(outIdx, render)
            releasedFrames++
            if (render) {
                decodedFrames++
                lastShownPts = ptsRaw + appliedOffset
                lastShownAtMs = android.os.SystemClock.elapsedRealtime()
                firstFrameShown = true
                if (!showLogged) {
                    showLogged = true
                    runCatching { onFirstFrame?.invoke() }
                }
            }
            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        }

        /* 预卷：早于起点的帧丢弃、不上屏；第一帧到齐时定基准 */
        if (originPts < 0.0 && ptsRaw < audioStartSec) {
            prerollDropped++
            return finish(false)
        }
        if (originPts < 0.0) {
            originPts = ptsRaw
            /*
             * 起播只要"两边从同一个位置开始"，原则是**声音只能往前，不能往回**。
             * 用当前真实主时钟（不是请求位置）跟首帧比：
             *  · 画面落在声音后面超过 0.3 秒 → 声音往前跳到画面位置（跳过一小段，听不出问题）；
             *  · 画面落在声音前面/持平 → 不动声音，让画面自己跳到时钟处追上来。
             * 实测教训：拿"我们请求的位置"当基准，会出现"声音被拉回 5 秒、同一句话反复重播"。
             */
            val clockAtFix = clockNow()
            val gap = originPts - clockAtFix
            if (clockAtFix > 0.0 && gap > 0.3) {
                Log.w(
                    TAG,
                    "起播对齐：画面比声音晚 ${"%.2f".format(gap)}s → 声音往前跳到 ${"%.2f".format(originPts)}s",
                )
                audioStartSec = originPts
                appliedOffset = 0.0
                runCatching { onAudioAlign?.invoke(originPts) }
            } else {
                appliedOffset = audioStartSec - originPts
                if (clockAtFix > 0.0 && -gap > 0.5) {
                    Log.w(TAG, "起播对齐：画面落后声音 ${"%.2f".format(-gap)}s → 画面跳到时钟处追")
                    pendingSeekSec = (clockAtFix - 0.15).coerceAtLeast(0.0)
                }
            }
            Log.i(
                TAG,
                "零点标定：audioStart=$audioStartSec origin=$originPts offset=$appliedOffset" +
                    " 预卷丢弃=$prerollDropped 主时钟=${"%.2f".format(clockAtFix)}" +
                    " 基准差=${"%.3f".format(clockAtFix - originPts)}s",
            )
        }

        /* 画面侧就绪信号：预热满三帧才发（父规：三帧 + 声音就绪才一起放） */
        if (!readySignalled) {
            preheatCount++
            preheatFirstPts = if (preheatFirstPts < 0.0) ptsRaw else preheatFirstPts
            preheatLastPts = ptsRaw
            if (preheatCount < preheatFrames) return finish(false)
            readySignalled = true
            val spanMs = ((preheatLastPts - preheatFirstPts) * 1000.0).toLong()
            Log.i(TAG, "画面侧就绪：预热 $preheatCount 帧（跨度 ${spanMs}ms，pts=$ptsRaw），发信号给上层")
            runCatching { onPreheatReady?.invoke(spanMs, lastDovi != null) }
            return finish(false)
        }

        /* 起播放行闸门：门关着就持这一帧等，门一开立刻上屏 */
        if (!firstFrameShown && holdGate?.invoke() == true) {
            while (running.get() && holdGate?.invoke() == true) {
                try {
                    Thread.sleep(5)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!running.get()) return finish(false)
            return finish(true)
        }

        /*
         * 对齐判决照抄 ExoPlayer（androidx/media3）MediaCodecVideoRenderer，数值不改：
         *   MIN_EARLY_US_LATE_THRESHOLD      = -30_000  → 迟到的帧丢掉
         *   MIN_EARLY_US_VERY_LATE_THRESHOLD = -500_000 → 严重迟到：丢到关键帧重新解
         *   shouldForceRenderOutputBuffer    = 迟到但 100ms 没出过画面 → 先出一帧
         * earlyUs = 这帧该显示的时刻 − 现在（µs）；负数=迟到。
         */
        val clockUs = (clockNowOrWall() * 1_000_000.0).toLong()
        val earlyUs = ((ptsRaw + appliedOffset - FRAME_DELAY_SEC) * 1_000_000.0).toLong() - clockUs
        val nowUs = android.os.SystemClock.elapsedRealtime() * 1000L
        val sinceLastRenderUs = if (lastShownAtMs > 0L) nowUs - lastShownAtMs * 1000L else Long.MAX_VALUE

        if (earlyUs < MIN_EARLY_US_VERY_LATE_THRESHOLD) {
            val target = (clockNowOrWall() - 0.15).coerceAtLeast(0.0)
            Log.w(TAG, "严重迟到 ${"%.2f".format(-earlyUs / 1e6)}s → 丢掉这一段，跳到 ${"%.2f".format(target)}s")
            pendingSeekSec = target
            return finish(false)
        }
        if (earlyUs < MIN_EARLY_US_LATE_THRESHOLD && sinceLastRenderUs > 100_000L) {
            forcedRender++
            return finish(true)
        }
        if (earlyUs < MIN_EARLY_US_LATE_THRESHOLD) {
            droppedStale++
            if (droppedStale % 25 == 1) {
                Log.w(TAG, "丢掉迟到帧|晚 ${"%.0f".format(-earlyUs / 1000.0)}ms（累计 $droppedStale 帧）")
            }
            return finish(false)
        }
        if (earlyUs > 0L) {
            /* 来早了：等到该显示的时刻，最多等一帧 */
            var leftMs = (earlyUs / 1000L).coerceAtMost((FRAME_DELAY_SEC * 1000).toLong())
            while (leftMs > 0 && running.get()) {
                val step = leftMs.coerceAtMost(10L)
                try {
                    Thread.sleep(step)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                leftMs -= step
            }
            if (!running.get()) return finish(false)
        }
        return finish(true)
    }

    /**
     * 请解码线程在同一个解码器里跳到目标位置（快进/落后过大时用）。
     * 不重建解码器：实测重建 6~21 秒，热解码器内跳是几百毫秒。
     */
    fun requestSeek(targetSec: Double) {
        pendingSeekSec = targetSec
    }

    /** 是否在跑（供上层判断能不能走"热解码器跳转"而不是重建） */
    fun isRunning(): Boolean = running.get()

    /**
     * 在**同一个解码器**里跳到目标位置。
     *
     * 为什么不再重建解码器（2026-11-17 父亲定）：重建要重新打开流、重建 MediaCodec 并跳到关键帧，
     * 实测 6~9 秒；而 flush + seek 是同一个解码器内的动作，几百毫秒级。
     */
    private fun seekInPlace(targetSec: Double): Boolean {
        val ex = extractor ?: return false
        val c = decoder ?: return false
        return try {
            ex.seekTo((targetSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            c.flush()
            /* 重定基准：跳到新处后第一帧重新定 offset，预卷丢弃重新计 */
            originPts = -1.0
            appliedOffset = Double.NaN
            firstFrameShown = false
            Log.i(TAG, "解码器内跳转完成 → ${"%.2f".format(targetSec)}s（未重建）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "解码器内跳转失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 抖动缓冲：画面比声音整体晚这么多秒上屏（约两帧 @24fps）。
     * 有了它，解码慢一拍不会立刻变成"丢帧/等待"的来回切换。
     */
    /* 以下三个数值取自 ExoPlayer MediaCodecVideoRenderer，不改 */
    private val FRAME_DELAY_SEC = 0.05          /* 目标延迟 ≈ 两帧 @24fps */
    private val MIN_EARLY_US_LATE_THRESHOLD = -30_000L
    private val MIN_EARLY_US_VERY_LATE_THRESHOLD = -500_000L

    /** 起播基准对不齐时，请上层把声音跳到实际首帧位置（一次性） */
    @Volatile private var onAudioAlign: ((Double) -> Unit)? = null

    /** 画面侧就绪信号是否已发（手上有第一帧时才发） */
    @Volatile private var readySignalled = false

    /* 读流计数（给独立监视线程用：主循环卡住时它照常报数） */
    @Volatile private var statSamples = 0L
    @Volatile private var statBytes = 0L
    @Volatile private var statInMiss = 0L
    @Volatile private var statLastReadRet = 0L
    @Volatile private var statOutMiss = 0L

    /** 需要"解码器内跳转"到的位置（秒，<0 表示没有待办）。由解码线程自己执行 */
    @Volatile private var pendingSeekSec = -1.0

    /** 丢掉迟到帧的累计数（观测量：它一直涨说明解码跟不上） */
    private var droppedStale = 0
    /** 迟到但被强制上屏的次数（ExoPlayer 的 shouldForceRenderOutputBuffer） */
    private var forcedRender = 0

    /** 主时钟：读不到就用"最后位置 + 墙钟"自己走（不压画面） */
    private fun clockNowOrWall(): Double {
        val v = clockNow()
        if (v >= 0.0) return v
        val base = lastShownPts
        if (base <= 0.0) return if (audioStartSec >= 0.0) audioStartSec else 0.0
        val last = lastShownAtMs
        if (last == 0L) return base
        return base + (android.os.SystemClock.elapsedRealtime() - last) / 1000.0
    }


    /**
     * 主时钟"现在到第几秒"。
     *
     * **不判断可用性**（父亲 2026-11-17）：注入的提供者本身就是"最后读数 + 墙钟推算"，
     * 它永远给得出一个当下的时间；读不到数返回 -1 才走墙钟兜底。
     */
    private fun clockNow(): Double = clockProvider?.invoke() ?: -1.0

    /**
     * 从这一帧的码流里解出杜比还原参数，变了就落盘给渲染层。
     *
     * 为什么要节流：这批参数每帧都在变（实测相隔 12 秒两次采样 md5 不同），
     * 但每帧写一次文件、渲染层每帧再读一次，等于拿磁盘当内存用。
     * 隔几帧写一次足够 —— 颜色参数的变化人眼跟不上。
     */
    private fun harvestDovi(buf: java.nio.ByteBuffer, size: Int) {
        val path = doviParamPath ?: return
        if (size <= 0 || size > sampleBuf.size) return
        if (doviWriteSkip > 0) {
            doviWriteSkip--
            return
        }
        try {
            buf.position(0)
            buf.get(sampleBuf, 0, size)
            val params = DoviRpu.parseFrame(sampleBuf, size)
            if (params == null) {
                if (doviMissLog++ < 3) Log.w(TAG, "这帧没解出杜比参数（size=$size）")
                return
            }
            val prev = lastDovi
            if (prev != null && prev.contentEquals(params)) return
            if (lastDovi == null) {
                /*
                 * 与"跳转重启完成：位置=…"同屏对账：重建后头几帧渲染层还没拿到参数，
                 * 会先按非杜比画一小下（父亲看到的"快进瞬间偏紫"）。这条日志能证实差多少毫秒。
                 */
                val sinceMs = if (startedAtMs == 0L) -1L else android.os.SystemClock.elapsedRealtime() - startedAtMs
                Log.i(
                    TAG,
                    "RPU 参数到达|pts=${"%.3f".format(lastSeenInputPts)}|距重建=${sinceMs}ms" +
                        "（分段数=${params[2].toInt()} 通道=${params[1].toInt()}）",
                )
                /* 解出参数就等于确认了"这是 P5 片源"，此时才允许渲染层走还原 */
                onDoviParams?.invoke()
            }
            lastDovi = params
            /*
             * 参数冻结（2026-11-16 定，父亲实测「一会儿偏蓝马上又正常」）：
             * 片源里每段场景都带一套新参数，我们按几帧一更新，画面用的参数和当前帧对不上，
             * 颜色就会跳一下。稳定优先 —— 第一条参数定下来之后不再更新，
             * 调参文件写 dovi_freeze=0 可恢复逐帧更新。
             */
            doviWriteSkip = if (com.xxxx.emby_vr.vr.VrTuning.doviFreeze) Int.MAX_VALUE else 3
            java.io.File(path).outputStream().use { out ->
                val bb = java.nio.ByteBuffer.allocate(params.size * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                params.forEach { bb.putFloat(it) }
                out.write(bb.array())
            }
        } catch (t: Throwable) {
            // 解析失败不影响播放，下一帧再试
        }
    }

    /** 停止并释放。可重复调用。 */
    /**
     * 停止解码并释放解码器。
     *
     * @return true = 旧线程已确认退出、资源已释放，可以新建实例；
     *         false = 旧线程 600ms 内没退出（僵尸）—— 此时**不许新建**，
     *                 否则两路解码同时往同一张画面送帧，是最难查的状态（图 §5 I4）。
     */
    fun stop(): Boolean {
        Log.i(TAG, "stop() 被调用（running→false，等待解码线程退出）")
        /* 释放权归线程自己：这里只置停止标志，不代解码线程 release（图 §5 I4） */
        stopping = true
        running.set(false)
        holdGate = null
        onNeedRestart = null
        onAudioAlign = null
        droppedNoClock = 0
        desyncSinceMs = 0L
        onFirstFrame = null
        val t0 = thread
        t0?.let {
            try {
                it.join(600)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        if (t0?.isAlive == true) {
            /*
             * 图 §5 I4：线程还没退出时**禁止外部代它 release**（那是拆正在用的实例）。
             * 释放留给线程自己的退出路径；这里只留痕，下次 start 前会确认旧实例已死。
             */
            Log.e(TAG, "ASSERT joinTimeout thread=VideoPipe（600ms 未退出，不代它释放解码器，禁止新建）")
            return false
        }
        try {
            decoder?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "解码器停止异常：${t.message}")
        }
        try {
            decoder?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "解码器释放异常：${t.message}")
        }
        decoder = null
        try {
            extractor?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "解复用器释放异常：${t.message}")
        }
        return true
        extractor = null
    }
}
