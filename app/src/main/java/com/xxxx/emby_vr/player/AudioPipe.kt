package com.xxxx.emby_vr.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频自管：解复用 → 解码 → 写音频设备。
 *
 * 存在的理由：P5 片子的声音原来交给播放内核，内核与自管画面各自持有一个解码器、
 * 各自一条网盘连接、各自一个时间轴，靠中间一层缓冲去对齐 —— 两台东西抢资源的表现
 * 就是起播黑屏与黑纹。现在声音也归我们，全链路只有一个解码器、一个时间轴。
 *
 * 时间轴的规则（照 `.15` 上验过的原型，`refs/p5render-2026-11-20.c`）：
 *  1. 音频位置一律由"已写进设备的样本数"往下加，不用解码器给的时间戳；
 *  2. 第一块数据的起点由它自己的内容时间与画面起点相减得出，晚就补等长静音，早才裁；
 *  3. 跳转时设备、解码器、时钟三样一起重置，顺序固定：停设备 → 清解码器 → 定新起点。
 */
class AudioPipe {

    companion object {
        private const val TAG = "AudioPipe"

        /** 十进制秒 → 微秒 */
        private const val US = 1_000_000.0

        /**
         * 可解的音轨优先级。
         *
         * 系统解不了 DTS / TrueHD（P5 片子里很常见），那类片子只能退回原路，
         * 所以这里按"能解的排前面"挑一条，而不是无条件用第一条。
         */
        private val MIME_PRIORITY = listOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_EAC3,
            MediaFormat.MIMETYPE_AUDIO_AC3,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.MIMETYPE_AUDIO_VORBIS,
            MediaFormat.MIMETYPE_AUDIO_MPEG,
            MediaFormat.MIMETYPE_AUDIO_FLAC,
        )

        /** 头显自带解码器解不了的编码 —— 交给自带库（libaudiodec.so） */
        private val NATIVE_ONLY = listOf(
            MediaFormat.MIMETYPE_AUDIO_EAC3,
            MediaFormat.MIMETYPE_AUDIO_AC3,
            "audio/truehd",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
        )
    }

    @Volatile
    var lastError: String? = null
        private set

    /** 起了哪条轨、什么编码、什么采样率，进日志对账 */
    @Volatile
    var trackDesc: String = ""
        private set

    /** 首帧真正被写进设备的时刻（elapsedRealtime），起播耗时靠它算 */
    @Volatile
    var firstPcmAtMs: Long = 0L
        private set

    /** 音频侧内容起点（微秒，片源时间轴），探测一次即冻结 */
    @Volatile
    var audioOriginUs: Long = 0L
        private set


    /*
     * 读线程 + 包队列（与画面那套完全对称，2026-11-21 父亲定）。
     *
     * 为什么声音也要队列：之前声音是"边读边解"，网盘抖一下就断供，
     * 而画面有三十多兆存货顶着 —— 所以每次卡顿都只打中声音，断一段就变成永久落后。
     */
    private var feedThread: Thread? = null
    /* 音频码率低（128~512kbps），8MB 已是几分钟存货，够用，把内存留给画面 */
    private val feedQueue = SampleQueue(8 * 1024 * 1024)

    /** 全包档：连接与读线程归 [DemuxSession]，本类不建 feed 线程、不关连接 */
    @Volatile private var fullMode = false
    @Volatile private var sourceQueue: SampleQueue = feedQueue
    @Volatile private var session: DemuxSession? = null
    @Volatile private var feedSeekSec = -1.0
    private var prepareLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var preparedFormat: MediaFormat? = null
    @Volatile private var prepareError: String? = null
    @Volatile private var feedUrl = ""
    @Volatile private var feedHeaders: Map<String, String> = emptyMap()
    @Volatile private var feedStartSec = 0.0
    @Volatile private var feedTrackIndex = -1
    @Volatile private var feedMemWindowSec = 0
    private var codec: MediaCodec? = null
    /*
     * 两个后端二选一：
     *  system = 头显自带解码器（AAC/MP3/FLAC 这些够用，EAC3/TrueHD/DTS 解不了）
     *  ffmpeg = 自带库（libaudiodec.so），覆盖杜比数字+ / TrueHD / DTS
     * 起播时定一次，运行中不热切（热切要在同一线程保存两套解码器状态，风险大于收益）。
     */
    private var ffDec: FfmpegAudioDecoder? = null
    @Volatile private var decoderKind = "system"
    /** 起播时定下的后端偏好（auto/system/ffmpeg），选轨阶段要用它决定哪些编码能选 */
    @Volatile private var prefBackend = "auto"
    private var trackMime = ""
    private var trackFormat: MediaFormat? = null

    /*
     * 音频那一路的解封装句柄（FFmpeg avformat）。
     * 为什么换：系统解封装一次只能读一条轨，音频+字幕要多开一路；网盘只给两路，
     * 第三路必被拒（2026-11-22 实测）。avformat 一次打开就能把音频包和字幕包都拿到。
     */
    private var demuxHandle = 0L
    private var demuxCodec = ""
    private var demuxDiscardSetting = 1
    private var demuxOffSetting = 1
    private var demuxSubN = 0
    /** 容器时长（毫秒，0=未知）：判"真结尾"用 */
    private var demuxDurationMs = 0L

    /** 这一路读到真结尾时回调（收场要等两路都结束，见豆豆 2026-11-22） */
    var onFeedEnd: (() -> Unit)? = null

    /** 字幕文本回调：起点毫秒、结束毫秒、文本、第几条字幕轨 */
    var onSubtitle: ((Long, Long, String, Int) -> Unit)? = null
    /** 自带库累计解出的 PCM 字节（判据：应与写入设备字节大致一比一） */
    @Volatile private var ffmpegBytes = 0L
    private var track: AudioTrack? = null

    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    /** 是否已经放行（放行前不写、不播） */
    private val started = AtomicBoolean(false)

    @Volatile
    private var pendingSeekSec = -1.0

    private var sampleRate = 48000
    private var channelCount = 2
    private var bytesPerFrame = 4

    /** 与画面共用的起点（微秒） */
    @Volatile
    private var originUs = 0L

    /** 自 origin 起已写进设备的帧数（时间轴唯一依据） */
    @Volatile
    private var writtenFrames = 0L

    /** 首块数据是否已经处理过（补静音 / 裁头只做一次） */
    private var firstBlockHandled = false

    /**
     * 首块裁剪要跨块累计。
     *
     * 续播/跳转时，音频首帧可能比画面起点早几秒到十几秒，折成字节远大于一次解码输出的一块；
     * 只在第一块上裁，剩下的会原样播出来，听感就是"声音早十几秒"（2026-11-21 实测）。
     */
    private var pendingSkipBytes = 0L

    /** 跳转后重新对齐首块 */
    private var seekFlushCount = 0

    private val ts = AudioTimestamp()

    /** 起播是否允许直接出声（false = 等放行，见 startNow） */
    private var autoPlayOn = true

    /** 写入被设备拒收的次数（只用于限制日志量） */
    private var zeroWrites = 0

    /** 位置日志节流 */
    private var lastPosLogMs = 0L

    /**
     * 放行时刻（设备真正开始出声的那一刻）。
     *
     * 位置用它推算，而不是用「已播帧数 ÷ 标称采样率」：
     * 实测这台设备每秒只放 43640 个样本（标称 44100，差 1%），
     * 按标称值算位置会每分钟慢半秒多，播四分钟就差 2.5 秒（2026-11-21 实测）。
     */
    @Volatile
    private var startedAtMs = 0L

    /** 暂停开始的时刻（0 = 没在暂停）；恢复时把这段时长从计时里补掉 */
    private var pausedAtMs = 0L

    /** 写时钟的令牌：放行时由上层绑定；未绑定（-1）时上报会被时钟丢弃 */
    @Volatile
    private var clockToken = -1L

    /** 放行时绑定写权（时钟在放行那一刻才换发新令牌） */
    fun bindClockToken(t: Long) {
        clockToken = t
    }
    private var frameBase = 0L
    private var latencyUs = 0L

    /** 时钟：位置由本类上报，画面按它出帧 */
    private var clock: PlaybackClock? = null

    /**
     * 声音这一路停写/断流时通知上层（true=停了，false=恢复）。
     *
     * 父亲 2026-11-21 定的规矩是双向的：画面停声音停、声音停画面也停。
     * 这里报的是"写不进去"，通常意味着上游数据断了或设备卡住。
     */
    @Volatile
    var onStall: ((Boolean) -> Unit)? = null

    /** 最近一次成功写入的时刻；断流判定只用它 */
    @Volatile
    private var lastWriteMs = 0L

    /** 当前是否处于"报过停"的状态（避免每轮重复回调） */
    private val stalled = AtomicBoolean(false)

    /**
     * 起播。
     *
     * @param originSec 画面首个真实帧的时间（秒）。音频按它对齐：晚就补静音、早才裁。
     * @param trackIndex 指定音轨；-1 表示按 [MIME_PRIORITY] 自己挑。
     */
    fun start(
        url: String,
        headers: Map<String, String>,
        startSec: Double,
        originSec: Double,
        clock: PlaybackClock,
        trackIndex: Int = -1,
        /**
         * 建轨后是否立刻出声。
         *
         * 放行之前必须为 false：声音先响、画面还握着三帧预热，就是"先说话后出人"。
         */
        autoPlay: Boolean = true,
        /**
         * 内存缓冲要攒多少秒（0 = 直读）。
         *
         * 音频码率只有一百多 kbps，按秒折算出来的预算远小于画面，所以这里固定给 32MB ——
         * 足够几分钟音频，再大只是白占内存。档位只影响画面那一路的预算。
         */
        memWindowSec: Int = 0,
        /**
         * 音频解码后端：auto / system / ffmpeg。
         *
         * auto = 系统解不了的编码（EAC3/TrueHD/DTS）用自带库，其余走系统 ——
         * 不去动一条已经验证过的路。调参项 audio_decoder 可在 vr-tuning 里覆盖。
         */
        decoderPref: String = "auto",
        /** 1=解封装时丢弃非本路轨（默认）；0=不丢弃（排查用，验证通过后连开关一起删） */
        demuxDiscard: Int = 1,
        /** 1=关掉 avformat 那一路，回到系统解封装读包 + 自带解码（默认）；0=用 avformat 路 */
        demuxOff: Int = 1,
        /** 全包档：Session 与音频格式直接交进来，本类不起 feed 线程（图 §3 铁律） */
        demuxSession: DemuxSession? = null,
        formatOverride: MediaFormat? = null,
    ): Boolean {
        stop()
        prefBackend = decoderPref.trim().lowercase()
        demuxDiscardSetting = demuxDiscard
        demuxOffSetting = demuxOff
        lastError = null
        firstPcmAtMs = 0L
        writtenFrames = 0L
        firstBlockHandled = false
        this.clock = clock
        originUs = (originSec * US).toLong()

        /* 解封装的打开、选轨、跳转、读全部交给读线程（系统接口要求同一线程） */
        feedUrl = url
        feedHeaders = headers
        feedStartSec = startSec
        feedTrackIndex = trackIndex
        feedMemWindowSec = memWindowSec
        fullMode = demuxSession != null
        session = demuxSession
        sourceQueue = demuxSession?.audioQueue ?: feedQueue
        if (!fullMode) feedQueue.reset()
        prepareLatch = java.util.concurrent.CountDownLatch(1)
        prepareError = null
        running.set(true)
        return try {
            if (fullMode) {
                /* 全包档：参数由 Session 给，读线程已在 Session 里跑；本类只等第一次取包 */
                preparedFormat = formatOverride
                if (formatOverride == null) {
                    lastError = "全包档没有给出音频格式"
                    Log.e(TAG, lastError!!)
                    stop()
                    return false
                }
                /* 音频内容起点取 Session 记下的首包时间；还没读到就退回起点（首块对齐会再校） */
                audioOriginUs = demuxSession?.audioFirstPtsUs?.takeIf { it > 0L }
                    ?: (startSec * US).toLong()
                prepareLatch.countDown()
                Log.i(TAG, "全包档音频：编码=${formatOverride.getString(MediaFormat.KEY_MIME)} 起点=${"%.3f".format(audioOriginUs / US)}s")
            } else {
                preparedFormat = null
                feedThread = Thread({ audioFeedLoop() }, "AudioFeed").apply { isDaemon = true; start() }
                if (!prepareLatch.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    lastError = "音频解封装准备超时"
                    Log.e(TAG, lastError!!)
                    stop()
                    return false
                }
            }
            val fmt = preparedFormat
            val perr = prepareError
            if (perr != null || (fmt == null && demuxHandle == 0L)) {
                lastError = perr ?: "音频解封装准备失败"
                Log.e(TAG, lastError!!)
                stop()
                return false
            }
            /* 解封装模式没有 MediaFormat：采样率/声道由 demuxFeedLoop 从流参数里填好 */
            /*
             * 格式名可能为空：走系统解封装时 demuxCodec 是空的，拼出来就是 "audio/"，
             * 解码器拿到空格式名会直接起不来（Failed to initialize audio/）。
             */
            val mimeRaw = runCatching { fmt?.getString(MediaFormat.KEY_MIME) }.getOrNull()
            val mime = when {
                !mimeRaw.isNullOrBlank() -> mimeRaw
                demuxCodec.isNotBlank() -> "audio/$demuxCodec"
                else -> runCatching { fmt?.getString("mime") }.getOrNull().orEmpty()
            }
            if (mime.isBlank()) {
                lastError = "音频格式名为空（解封装未给出 mime）"
                Log.e(TAG, lastError!!)
                stop()
                return false
            }
            if (fmt != null) {
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channelCount = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            }
            if (channelCount !in 1..8) channelCount = 2
            trackMime = mime
            trackFormat = fmt
            trackDesc = "编码=$mime 采样率=$sampleRate 声道=$channelCount"
            Log.i(TAG, "音轨选择：$trackDesc（走队列，读线程已起）")

            val want = decoderPref.trim().lowercase()
            val useFfmpeg = when (want) {
                "ffmpeg" -> true
                "system" -> false
                else -> isNativeOnlyCodec(mime)
            }
            if (useFfmpeg) {
                val d = FfmpegAudioDecoder(mime, fmt ?: MediaFormat())
                val opened = if (fmt != null) d.open() else d.openWithParams(demuxCodec, channelCount)
                if (opened) {
                    ffDec = d
                    decoderKind = "ffmpeg(${d.desc})"
                } else {
                    Log.w(TAG, "自带解码器打开失败 → 退回系统解码器（$mime）")
                }
            }
            if (ffDec == null) {
                val c = MediaCodec.createDecoderByType(mime)
                c.configure(fmt, null, null, 0)
                c.start()
                codec = c
                decoderKind = "system"
            }
            Log.i(TAG, "音频后端=$decoderKind 编码=$mime")

            val at = buildTrack()
            if (at == null) {
                lastError = "音频设备建不起来"
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
                ffDec?.close()
                ffDec = null
                feedQueue.abort()
                return false
            }
            track = at
            /*
             * 输出延迟：设备不提供可用的延迟读数时，按缓冲区一半保守估计。
             * 减多了会让位置略偏早（画面略微超前），减少了会让位置偏晚（画面略滞后），
             * 宁可偏早不可偏晚 —— 偏晚在主观上就是"声音比画面慢"。
             */
            /* 只在拿不到硬件时间戳时才用：取缓冲四分之一（原来是二分之一，实测把位置整体拖早了 200ms） */
            latencyUs = runCatching {
                val frames = at.bufferSizeInFrames
                if (frames > 0) frames * 1_000_000L / sampleRate / 4 else 0L
            }.getOrDefault(0L)
            autoPlayOn = autoPlay
            /*
             * 放行前既不起动设备、也不写数据。
             *
             * 实测（PICO 4，2026-11-20）：设备未起动时 write 一律返回 0，数据进不去；
             * 而起动了就等于开播，声音会抢在画面之前。两条都走不通，所以只能让
             * "起动"和"开始写"发生在同一刻 —— 那一该由上层的放行来触发（见 resume）。
             */
            started.set(autoPlay)
            if (autoPlay) {
                /* 位置推算要用它：不设的话位置会整体偏大（2026-11-21） */
                startedAtMs = SystemClock.elapsedRealtime()
                at.play()
            }
            frameBase = runCatching { at.playbackHeadPosition.toLong() }.getOrDefault(0L)

            running.set(true)
            thread = Thread({ loop() }, "audio-pipe").also { it.start() }
            Log.i(TAG, "音频起播：起点=${"%.2f".format(startSec)}s 画面起点=${"%.2f".format(originSec)}s " +
                "音频首帧=${"%.3f".format(audioOriginUs / US)}s 设备延迟=${latencyUs / 1000}ms")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "音频起播失败：$lastError", t)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { track?.release() }
            feedQueue.abort()
            codec = null
            track = null
            false
        }
    }

    /**
     * 放行：起动设备并开始写数据。
     *
     * 只有这一处会把设备从"准备好但没动"变成"正在放"，所以声音与画面必然同刻开始。
     */
    /** 设备是否已经起动（看门狗放行后要判断"声音补启动了没有"） */
    fun isStarted(): Boolean = started.get()

    fun startNow() {
        if (started.get()) return
        started.set(true)
        startedAtMs = SystemClock.elapsedRealtime()
        pausedAtMs = 0L
        paused.set(false)
        runCatching { track?.play() }
        Log.i(TAG, "音频放行：设备起动 + 开始写数据（位置中枢=${clock?.positionUs()?.div(1000L) ?: 0L}ms）")
    }

    fun pause() {
        /* 记下暂停时刻：这段时间不算播放时间，否则恢复后位置会一下多出几十秒 */
        if (pausedAtMs == 0L) pausedAtMs = SystemClock.elapsedRealtime()
        paused.set(true)
        runCatching { track?.pause() }
        clock?.pause()
    }

    fun resume() {
        /* 把暂停时长从"开始计时点"里补回来，位置才只包含真正在放的时间 */
        if (pausedAtMs > 0L) {
            if (startedAtMs > 0L) startedAtMs += SystemClock.elapsedRealtime() - pausedAtMs
            pausedAtMs = 0L
        }
        started.set(true)
        paused.set(false)
        runCatching { track?.play() }
        clock?.resume()
    }

    fun seekTo(sec: Double) {
        pendingSeekSec = sec
        /* 设备立刻静音，避免跳转后旧数据先响一声 */
        runCatching { track?.pause() }
    }

    fun positionUs(): Long = clock?.positionUs() ?: 0L

    fun stop(): Boolean {
        running.set(false)
        /*
         * 先让设备立刻闭嘴，再停线程（2026-11-21 父亲实测「两个声音同时响」）。
         *
         * 线程可能正卡在一次写入里，join 要等一会儿；这期间设备缓冲里还有几百毫秒的声音在放。
         * pause + flush 是瞬时的，先把声音止住，再去收线程。
         */
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        /* 解封装：先打断阻塞中的读，再收线程（MediaExtractor 打断不了，avformat 可以） */
        runCatching { if (demuxHandle != 0L) AudioDemuxNative.nativeDemuxAbort(demuxHandle) }
        /* 全包档的队列归 Session：abort 会让消费端永久 null，这里不许碰 */
        if (!fullMode) feedQueue.abort()
        /*
         * 读线程持连接：它没退出就等于连接没归还，必须进返回值（豆豆 2026-11-21 审出）。
         * 等 3 秒：跳转后读线程可能正处在"刚跳过、最多盲等 2.02 秒"的重试窗口里，
         * 1.5 秒会在窗口没走完时就判它没退出（豆豆 2026-11-22 二审的 P0）。
         */
        val ft = feedThread
        ft?.let { runCatching { it.join(3000) } }
        feedThread = null
        val feedDead = ft == null || !ft.isAlive
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            runCatching { t.join(1500) }
        }
        runCatching { ffDec?.close() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { track?.release() }
        /*
         * 解封装不在这里关闭：它归读线程所有，由读循环退出时关。
         * 读线程还没退出就从这里拆，它下一次取样本必抛（豆豆 2026-11-21 第二遍审出）。
         */
        codec = null
        ffDec = null
        track = null
        /* 退出时连接没归零 = 泄漏的铁证（豆豆 2026-11-22） */
        runCatching {
            val st = IntArray(5)
            AudioDemuxNative.nativeDemuxStats(st)
            if (st[4] != 0) {
                Log.e(TAG, "CONN 泄漏：退出时 inFlight=${st[4]}|open=${st[0]}|close=${st[1]}|fail=${st[2]}")
            }
        }
        paused.set(false)
        return (t == null || !t.isAlive) && feedDead
    }

    // ------------------------------------------------------------------ 内部

    private fun pickAudioTrack(ex: MediaExtractor, forced: Int): Int {
        if (forced >= 0) {
            val mime = ex.getTrackFormat(forced).getString(MediaFormat.KEY_MIME) ?: ""
            return if (isDecodable(mime)) forced else -1
        }
        var best = -1
        var bestRank = Int.MAX_VALUE
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            val rank = pickRank(mime)
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank
                best = i
            }
        }
        return best
    }

    private fun isDecodable(mime: String): Boolean = pickRank(mime) >= 0

    /** 排序用；-1 表示这条轨在当前后端下不可用 */
    private fun pickRank(mime: String): Int {
        val i = MIME_PRIORITY.indexOf(mime)
        if (i >= 0) return i
        if (prefBackend == "system") return -1
        val j = NATIVE_ONLY.indexOf(mime)
        return if (j >= 0) MIME_PRIORITY.size + j else -1
    }

    private fun isNativeOnlyCodec(mime: String): Boolean = NATIVE_ONLY.contains(mime) ||
        mime.contains("dts", true) || mime.contains("truehd", true)

    private fun buildTrack(): AudioTrack? {
        /*
         * 声道必须按**解码器真实输出**来建（豆豆 2026-11-21 查实）。
         *
         * 这部片音轨是 6 声道（DDP5.1）。原来一律按双声道建设备、bytesPerFrame 固定 4，
         * 而解码器吐出的是每帧 12 字节 → 设备把 12 字节当 3 帧消费 → 声音三倍速，
         * writtenFrames 也跟着放大三倍，时钟被拽回起点附近，画面被判"来得早"一路等。
         */
        val (chMask, outCh) = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO to 1
            2 -> AudioFormat.CHANNEL_OUT_STEREO to 2
            3 -> AudioFormat.CHANNEL_OUT_STEREO to 2   /* 3 声道极少，按立体声下混由解码器保证；见下方一致性校验 */
            4 -> AudioFormat.CHANNEL_OUT_QUAD to 4
            5, 6 -> AudioFormat.CHANNEL_OUT_5POINT1 to 6
            7, 8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND to 8
            else -> AudioFormat.CHANNEL_OUT_STEREO to 2
        }
        bytesPerFrame = outCh * 2
        Log.i(TAG, "音频设备：声道=$channelCount → 输出=${outCh}ch 每帧=${bytesPerFrame}字节")
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        /* 半秒缓冲：太短在 4K 解码抢 CPU 时会欠载，太长会让暂停/跳转感知变迟钝 */
        val bufBytes = maxOf(minBuf * 2, sampleRate * bytesPerFrame / 2)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(chMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack 创建失败：${t.message}", t)
            null
        }
    }

    /**
     * 读线程：打开、选轨、跳转、读包都在这个线程（系统解封装要求同一线程）。
     *
     * 与画面那套（VideoPipe.audioFeedLoop 式的结构）完全对称：读到的包进队列，
     * 解码线程只从队列取，网络抖动被队列挡住。
     */
    /**
     * 解封装读线程：一次打开，音频包进队列、字幕文本直接回调。
     *
     * 线程约束：同一个 AVFormatContext 只能在这一个线程里 open/read/seek/close，
     * 解码线程只从 feedQueue 取（豆豆 2026-11-22）。
     */
    private fun demuxFeedLoop() {
        val h = AudioDemuxNative.nativeDemuxOpen(feedUrl, (feedStartSec * 1000).toLong(), demuxDiscardSetting)
        if (h == 0L) {
            prepareError = "解封装打开失败"
            prepareLatch.countDown()
            return
        }
        demuxHandle = h
        val info = IntArray(6)
        if (AudioDemuxNative.nativeDemuxInfo(h, info) != 0) {
            prepareError = "解封装参数读取失败"
            runCatching { AudioDemuxNative.nativeDemuxClose(h) }
            demuxHandle = 0L
            prepareLatch.countDown()
            return
        }
        sampleRate = info[0].takeIf { it > 0 } ?: 48000
        channelCount = info[1].takeIf { it in 1..8 } ?: 2
        demuxSubN = info[3]
        demuxDurationMs = info[4].takeIf { it > 0 }?.toLong() ?: 0L
        if (info[5] != 0) {
            /* 定位失败不等于文件不能播：报上去，让上层决定，不要说成"打开失败" */
            Log.w(TAG, "解封装：不能从 ${"%.1f".format(feedStartSec)}s 定位（文件本身可播）")
            lastError = "音频不能从当前进度定位"
            runCatching { onStall?.invoke(true) }
            prepareLatch.countDown()
            return
        }
        demuxCodec = runCatching { AudioDemuxNative.nativeDemuxCodecName(h) }.getOrDefault("")
        /*
         * 音频内容起点：旧路径靠 ex.sampleTime 给出；解封装这条没有，必须自己填。
         * 不填的话首块对齐会把整片音频当成"早于画面"裁光（豆豆 2026-11-22 审出的必现无声）。
         */
        audioOriginUs = (feedStartSec * US).toLong().coerceAtLeast(0L)
        prepareLatch.countDown()
        Log.i(TAG, "解封装就绪：采样率=$sampleRate 声道=$channelCount 解码器=$demuxCodec 文字字幕轨=$demuxSubN")

        val buf = ByteArray(512 * 1024)
        val meta = IntArray(5)
        var firstAudioPts = true
        var waitMs = 0L
        var netRetry = 0
        var lastConnLog = 0L
        while (running.get()) {
            /* 每 5 秒一行连接账：在飞数、打开/关闭/失败/重复关闭次数（豆豆 2026-11-22） */
            val nowConn = android.os.SystemClock.elapsedRealtime()
            if (nowConn - lastConnLog >= 5000L) {
                lastConnLog = nowConn
                val st = IntArray(5)
                runCatching { AudioDemuxNative.nativeDemuxStats(st) }
                Log.i(TAG, "CONN|inFlight=${st[4]}|open=${st[0]}|close=${st[1]}|fail=${st[2]}|dupe=${st[3]}")
            }
            val target = feedSeekSec
            if (target >= 0.0) {
                feedSeekSec = -1.0
                runCatching { AudioDemuxNative.nativeDemuxSeek(h, (target * 1000).toLong()) }
                feedQueue.flush()
                audioOriginUs = (target * US).toLong().coerceAtLeast(0L)
                firstAudioPts = true
                Log.i(TAG, "解封装跳转到 ${"%.2f".format(target)}s")
            }
            val n = try {
                AudioDemuxNative.nativeDemuxRead(h, buf, meta)
            } catch (t: Throwable) {
                Log.w(TAG, "解封装读异常：${t.javaClass.simpleName}")
                -9
            }
            if (n < 0) {
                when (n) {
                    -1 -> {
                        /* 真结尾（解封装侧已连击三次确认） */
                        Log.i(TAG, "解封装真结尾（时长=${demuxDurationMs / 1000}s）")
                        feedQueue.putEos()
                        runCatching { onFeedEnd?.invoke() }
                        break
                    }
                    -2 -> {
                        /*
                         * 暂时没数据：分片等待，每片都检查 abort（用户按返回要立刻能退）。
                         * 等太久也不许发 EOS —— 那是"把等待当结尾"，会让上层误判播完（豆豆 2026-11-22）。
                         */
                        waitMs += 300L
                        if (waitMs > 8_000L) {
                            Log.w(TAG, "解封装连续 8 秒没有数据 → 上报网络错误（不发 EOS）")
                            lastError = "音频解封装等待超时"
                            runCatching { onStall?.invoke(true) }
                            break
                        }
                        var slept = 0L
                        while (slept < 300L && running.get()) {
                            Thread.sleep(100)
                            slept += 100
                        }
                        continue
                    }
                    -3 -> {
                        /* 网络类错误：退避重试；仍失败只上报，不发 EOS（断网不等于播完） */
                        netRetry++
                        if (netRetry > 2) {
                            Log.w(TAG, "解封装网络错误连续 $netRetry 次 → 停止这一路（不发 EOS）")
                            lastError = "音频解封装网络错误"
                            runCatching { onStall?.invoke(true) }
                            break
                        }
                        Log.w(TAG, "解封装网络错误，第 $netRetry 次后重试")
                        var slept2 = 0L
                        while (slept2 < 500L && running.get()) {
                            Thread.sleep(100)
                            slept2 += 100
                        }
                        continue
                    }
                    else -> {
                        /* 致命错误也不许发 EOS：那会把"坏了"报成"播完"（豆豆 2026-11-22） */
                        Log.e(TAG, "解封装致命错误 rc=$n → 停止这一路（不发 EOS）")
                        lastError = "音频解封装致命错误 rc=$n"
                        runCatching { onStall?.invoke(true) }
                        break
                    }
                }
            }
            waitMs = 0L
            netRetry = 0
            when (meta[0]) {
                0 -> {
                    val ptsUs = if (meta[2] >= 0) meta[2] * 1000L else 0L
                    /* 第一包的真实时间用来校正起点（与旧路径 audioOriginUs=sampleTime 的语义对齐） */
                    if (firstAudioPts && ptsUs > 0L) {
                        firstAudioPts = false
                        /*
                         * 只在"离起点不远"时才拿它当真实起点：seek 没落到目标附近时，
                         * 第一个包可能差几十秒，盲目覆盖会变成"起播位置错"，比无声更难查（豆豆 2026-11-22）。
                         */
                        if (kotlin.math.abs(ptsUs - audioOriginUs) < 3_000_000L) {
                            audioOriginUs = ptsUs
                        } else {
                            Log.w(TAG, "首个音频包离起点差 ${"%.1f".format((ptsUs - audioOriginUs) / US)}s，不用于校正起点")
                        }
                    }
                    if (!feedQueue.put(SampleQueue.Packet(buf.copyOf(n), n, ptsUs))) break
                }
                2 -> {
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    if (text.isNotBlank()) {
                        val startMs = if (meta[2] >= 0) meta[2].toLong() else 0L
                                val endMs = if (meta[4] > startMs) meta[4].toLong() else 0L
                        runCatching { onSubtitle?.invoke(startMs, endMs, text, meta[3]) }
                    }
                }
            }
        }
        runCatching { AudioDemuxNative.nativeDemuxClose(h) }
        if (demuxHandle == h) demuxHandle = 0L
    }

    private fun audioFeedLoop() {
        /* 本次运行的 latch 先抓在手里：旧读线程 join 超时后仍可能活到下一次 start()，
         * 那时字段已被换成新 latch，扣错会让新一次起播误报"准备失败"（豆豆 2026-11-22 二审） */
        val latch = prepareLatch
        /*
         * avformat 那一路在本机读不到完整响应（起播只到 7.7KB 就结束、跳转重开 500），
         * 默认关掉，回到系统解封装读包 + 自带解码；开关只为对照保留。
         */
        if (demuxOffSetting == 0 && AudioDemuxNative.available && feedMemWindowSec == 0) {
            demuxFeedLoop()
            return
        }
        if (AudioDemuxNative.available) {
            Log.w(TAG, "内存窗口档位下仍走系统解封装：这一路拿不到字幕（解封装直供只在直读模式生效）")
        }
        val src = newPacketSource()
        /* 读坏过的标志：解封装抛异常时不能补 EOS，否则"坏了"会被上层当成"播完了" */
        var diedByError = false
        try {
            if (!src.open()) {
                prepareError = src.lastError ?: "音频解封装失败"
                Log.e(TAG, prepareError!!)
                return
            }
            /*
             * 音频内容起点：旧路径靠解封装给出的样本时间；队列档从 seek 目标推出。
             * 不填的话首块对齐会把整片音频当成"早于画面"裁光（豆豆 2026-11-22 审出的必现无声）。
             */
            audioOriginUs = src.startUs.coerceAtLeast(0L)
            preparedFormat = src.format
            latch.countDown()
            Log.i(TAG, "音频解封装=${src.name}")

            while (running.get()) {
                val target = feedSeekSec
                if (target >= 0.0) {
                    feedSeekSec = -1.0
                    src.seekTo(target)
                    audioOriginUs = src.startUs.coerceAtLeast(0L)
                    feedQueue.flush()
                    Log.i(TAG, "音频读线程跳转到 ${"%.2f".format(target)}s（首帧=${"%.3f".format(audioOriginUs / US)}s）")
                }
                val s = try {
                    src.next()
                } catch (t: Throwable) {
                    Log.w(TAG, "读样本异常：${t.javaClass.simpleName}: ${t.message}")
                    diedByError = true
                    null
                }
                if (s == null) {
                    /* 真读完才补 EOS：解封装坏了不等于播完 */
                    if (!diedByError && !src.endedByError) {
                        feedQueue.putEos()
                        Log.i(TAG, "音频读线程到结尾")
                    }
                    break
                }
                if (!feedQueue.put(SampleQueue.Packet(s.data, s.data.size, s.ptsUs))) break
                if (src.endedByError) break
            }
        } catch (t: Throwable) {
            /* 读线程意外死掉也必须把连接还回去，否则下一个窗口打不开（403/500 的老账） */
            prepareError = "音频解封装异常：${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, prepareError!!, t)
        } finally {
            latch.countDown()
            runCatching { src.close() }
        }
    }

    /** 声音这一路的解封装做法：系统解封装读音轨（全包档下一步接 QueuePacketSource） */
    private fun newPacketSource(): PacketSource = ExtractorAudioSource(
        TAG, feedUrl, feedHeaders, feedMemWindowSec, feedStartSec,
    ) { ex -> pickAudioTrack(ex, feedTrackIndex) }

    /**
     * 全包档：Session 跳转后由上层调用，清设备与解码器、按新起点重对齐首块。
     *
     * 这不是第二个跳转入口 —— 定位只由 Session 做，这里只做"本 Pipe 的收尾"（图 §2-7）。
     */
    /**
     * 全包档跳转第一步：立刻闭嘴。
     *
     * 落点要等会话拿到首个视频包才回灌（几百毫秒到两秒），这段里设备缓冲里还有旧位置的
     * 零点几秒声音。off 档跳转会重建整个音频实例所以听不到，全包档必须显式静音（豆豆 2026-11-22 P1-A）。
     */
    fun silenceForSessionSeek() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        writtenFrames = 0L
        firstBlockHandled = false
        pendingSkipBytes = 0L
        Log.i(TAG, "全包档跳转：设备已静音（等落点回灌再重建解码器）")
    }

    fun onSessionSeek(targetSec: Double, originSec: Double, audioFirstUs: Long = -1L) {
        /* 死实例不许被唤起：下面的 track.play() 会把已停的设备重新放起来（豆豆 2026-11-22 P1-B） */
        if (!fullMode || !running.get()) {
            Log.w(TAG, "全包档跳转回灌被忽略：实例未运行")
            return
        }
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        if (ffDec != null) {
            /* 自带库没有清缓存这条路：重建上下文（与 off 档跳转同一做法） */
            ffDec?.close()
            /* 先置空：重建失败时绝不能留着指向已关闭实例（下次 decode 必炸，豆豆 2026-11-22） */
            ffDec = null
            val d = FfmpegAudioDecoder(trackMime, trackFormat ?: MediaFormat())
            if (d.open()) {
                ffDec = d
            } else {
                lastError = "跳转后重建自带解码器失败（$trackMime）"
                Log.e(TAG, lastError!!)
            }
        } else {
            runCatching { codec?.flush() }
        }
        originUs = (originSec * US).toLong()
        /*
         * 音频起点优先用 Session 回灌的真实首包时间；没拿到就留 -1，
         * 由第一包到达时定（用请求目标顶会在每条跳上偏一个关键帧间隔，豆豆 2026-11-22）。
         */
        audioOriginUs = audioFirstUs.takeIf { it > 0L } ?: -1L
        firstBlockHandled = false
        pendingSkipBytes = 0L
        seekFlushCount++
        writtenFrames = 0L
        frameBase = runCatching { track?.playbackHeadPosition?.toLong() }.getOrDefault(0L)
        clock?.resetTo(originUs)
        if (!paused.get()) {
            startedAtMs = SystemClock.elapsedRealtime()
            runCatching { track?.play() }
        }
        Log.i(TAG, "全包档音频跳转：目标=${"%.2f".format(targetSec)}s 起点=${"%.3f".format(audioOriginUs / US)}s 画面起点=${"%.3f".format(originSec)}s")
    }

    /** 全包档：音频起点以真实首包为准（off 档由 extractor 的 sampleTime 给） */
    private fun noteFirstSourcePacket(ptsUs: Long) {
        if (!fullMode || audioOriginUs > 0L) return
        audioOriginUs = ptsUs
        Log.i(TAG, "全包档音频起点取首包：${"%.3f".format(ptsUs / US)}s")
    }

    private fun loop() {
        /* 解封装归读线程；这里只从队列取数据 */
        val c = codec
        if (c == null && ffDec == null) return
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var eos = false

        while (running.get()) {
            try {
                /* 跳转：顺序固定 —— 停设备 → 清解码器 → 定新起点 → 重新起播 */
                val seekTarget = pendingSeekSec
                if (seekTarget >= 0.0 && fullMode) {
                    /* 全包档跳转只由 Session 一个入口发起；这里收到的是旧调用，忽略并记一笔 */
                    pendingSeekSec = -1.0
                    Log.w(TAG, "全包档忽略 Pipe 侧跳转请求 ${"%.2f".format(seekTarget)}s（跳转归 Session）")
                } else if (seekTarget >= 0.0) {
                    pendingSeekSec = -1.0
                    at.pause()
                    at.flush()
                    if (ffDec != null) {
                        /*
                         * 自带库没有"清缓存"这条路：直接重建解码上下文。
                         * 同一时刻只允许一个 AVCodecContext，所以先 close 再 open。
                         */
                        ffDec?.close()
                        val d = FfmpegAudioDecoder(trackMime, trackFormat ?: MediaFormat())
                        if (d.open()) {
                            ffDec = d
                        } else {
                            lastError = "跳转后重建自带解码器失败（$trackMime）"
                            Log.e(TAG, lastError!!)
                            break
                        }
                    } else {
                        c?.flush()
                    }
                    /* 跳转交给读线程（解封装归它）；这里只清队列与本地计数 */
                    feedSeekSec = seekTarget
                    feedQueue.flush()
                    originUs = (seekTarget * US).toLong()
                    writtenFrames = 0L
                    firstBlockHandled = false
                    pendingSkipBytes = 0L
                    seekFlushCount++
                    /* flush 之后设备帧计数不一定归零（各机型行为不同），基准取当下值 */
                    frameBase = runCatching { at.playbackHeadPosition.toLong() }.getOrDefault(0L)
                    inputDone = false
                    eos = false
                    clock?.resetTo(originUs)
                    pendingSkipBytes = 0L
                    if (!paused.get()) {
                        startedAtMs = SystemClock.elapsedRealtime()
                        at.play()
                    }
                    Log.i(TAG, "音频跳转：目标=${"%.2f".format(seekTarget)}s 首帧=${"%.3f".format(audioOriginUs / US)}s")
                }

                if (!started.get() || paused.get()) {
                    Thread.sleep(10)
                    continue
                }
                /* 声音停写超过 1.5 秒：通知上层把画面一起停住（父亲定的双向规矩） */
                val lw = lastWriteMs
                if (lw > 0L && SystemClock.elapsedRealtime() - lw > 1500L &&
                    stalled.compareAndSet(false, true)
                ) {
                    Log.w(TAG, "声音停写超过 1.5 秒 → 通知画面一起停")
                    runCatching { onStall?.invoke(true) }
                }

                if (ffDec != null) {
                    val dec = ffDec!!
                    if (!inputDone) {
                        val pkt = sourceQueue.get()
                        if (pkt != null) noteFirstSourcePacket(pkt.ptsUs)
                        if (pkt == null) {
                            inputDone = true
                        } else {
                            val chunk = dec.decode(pkt.data, pkt.size, pkt.ptsUs)
                            if (chunk != null && chunk.isNotEmpty()) {
                                info.set(0, chunk.size, pkt.ptsUs.coerceAtLeast(0L), 0)
                                writePcm(java.nio.ByteBuffer.wrap(chunk), info)
                                ffmpegBytes += chunk.size
                            }
                        }
                    } else {
                        /* 收尾：把解码器里残留的 PCM 取干净（不 drain 会丢尾部几十毫秒） */
                        val tail = dec.flush()
                        if (tail != null && tail.isNotEmpty()) {
                            info.set(0, tail.size, 0L, 0)
                            writePcm(java.nio.ByteBuffer.wrap(tail), info)
                            ffmpegBytes += tail.size
                        } else {
                            eos = true
                        }
                    }
                } else {
                    val cc = c ?: break
                    if (!inputDone) {
                        val ci = cc.dequeueInputBuffer(5_000)
                        if (ci >= 0) {
                            val pkt = sourceQueue.get()
                            if (pkt != null) noteFirstSourcePacket(pkt.ptsUs)
                            if (pkt == null) {
                                cc.queueInputBuffer(ci, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val buf = cc.getInputBuffer(ci)
                                if (buf != null) {
                                    buf.clear()
                                    buf.put(pkt.data, 0, pkt.size)
                                    cc.queueInputBuffer(ci, 0, pkt.size, pkt.ptsUs.coerceAtLeast(0L), 0)
                                } else {
                                    cc.queueInputBuffer(ci, 0, 0, 0, 0)
                                }
                            }
                        }
                    }

                    val oi = cc.dequeueOutputBuffer(info, 5_000)
                    if (oi >= 0) {
                        val out = cc.getOutputBuffer(oi)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            writePcm(out, info)
                        }
                        cc.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = cc.outputFormat
                        val r = runCatching { nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                        if (r != sampleRate) {
                            Log.w(TAG, "解码输出采样率与轨声明不一致：$sampleRate → $r（设备仍按 $sampleRate）")
                        }
                        val rc = runCatching { nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channelCount)
                        if (rc != channelCount) {
                            Log.e(TAG, "解码输出声道与设备不一致：设备=${channelCount} 实际=$rc → 停掉声音")
                            lastError = "声道不一致（设备=$channelCount 实际=$rc）"
                            runCatching { at.pause() }
                            runCatching { at.flush() }
                            break
                        }
                    }
                }

                if (eos) break
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = "解码线程异常：" + (t.message ?: t.javaClass.simpleName)
                Log.e(TAG, lastError.orEmpty(), t)
                break
            }
        }
        Log.i(TAG, "音频线程退出：已写 ${writtenFrames} 帧 跳转 ${seekFlushCount} 次" +
            (lastError?.let { " 错误=$it" } ?: ""))
    }

    private fun writePcm(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val at = track ?: return
        var offsetBytes = 0
        var bytes = info.size

        /*
         * 首块数据对齐（原型第 4 条）：
         * 音频首帧的内容时间晚于画面起点 → 先补等长静音，让两路在同一时间轴上；
         * 早于起点 → 只裁掉超出的那一段，不把画面往后挪。
         */
        if (!firstBlockHandled) {
            firstBlockHandled = true
            val deltaUs = audioOriginUs - originUs
            if (deltaUs > 0) {
                /* 用静音占位，不丢内容 */
                val frames = (deltaUs * sampleRate / 1_000_000L).toInt()
                if (frames > 0) writeSilence(at, frames)
                Log.i(TAG, "音频首帧比画面晚 ${"%.0f".format(deltaUs / 1000.0)}ms → 前补 $frames 帧静音")
            } else if (deltaUs < -20_000L) {
                val skipFrames = (-deltaUs * sampleRate / 1_000_000L).toInt()
                pendingSkipBytes = skipFrames.toLong() * bytesPerFrame
                Log.i(TAG, "音频首帧比画面早 ${"%.0f".format(-deltaUs / 1000.0)}ms → 往后累计裁掉 $skipFrames 帧")
            }
        }

        val epochAtWrite = seekFlushCount
        while (bytes > 0 && running.get()) {
            /* 写到一半遇到新的跳转：这块是旧位置的数据，整块丢掉 */
            if (seekFlushCount != epochAtWrite) return
            if (pendingSkipBytes > 0) {
                val drop = minOf(pendingSkipBytes, bytes.toLong()).toInt()
                offsetBytes += drop
                bytes -= drop
                pendingSkipBytes -= drop
                if (bytes <= 0) return
            }
            buf.position(info.offset + offsetBytes)
            buf.limit(info.offset + offsetBytes + bytes)
            val n = at.write(buf, bytes, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) {
                if (zeroWrites++ < 3) {
                    Log.w(TAG, "写入设备返回 $n（设备未在放；只报前三次）")
                }
                return
            }
            offsetBytes += n
            bytes -= n
            writtenFrames += (n / bytesPerFrame).toLong()
            lastWriteMs = SystemClock.elapsedRealtime()
            if (stalled.compareAndSet(true, false)) {
                Log.i(TAG, "声音恢复写入 → 通知画面一起走")
                runCatching { onStall?.invoke(false) }
            }
            notePosition()
        }
        if (firstPcmAtMs == 0L) {
            firstPcmAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "AUDIO 首帧写入：${trackDesc} 起点=${"%.3f".format(originUs / US)}s" +
                if (autoPlayOn) "" else "（设备未起动，等放行）")
            /* 自检结论：库没链上 / 打不开 / 解不出来，三种情况在日志里必须能分开 */
            Log.i(TAG, "AUDIO 自检|后端=$decoderKind|轨=$trackMime|设备=${channelCount}ch|每帧=$bytesPerFrame B" +
                if (ffDec == null) "|解出=走系统解码器" else "|解出=${ffmpegBytes}B（首帧后继续累计）")
        }
    }

    /**
     * 补静音。
     *
     * `frames` 是**每声道帧数**，而 `AudioTrack.write(short[], off, size)` 的 size 是
     * **短路样本数**（= 帧数 × 声道数）。原来直接把 frames 当样本数写：
     * 双声道只补一半、5.1 只补六分之一，听感就是"声音比画面早到"（豆豆 2026-11-21 查实）。
     * 另外 `writtenFrames` 也要按帧累加，不能拿样本数当帧数。
     */
    private fun writeSilence(at: AudioTrack, frames: Int) {
        val outCh = (bytesPerFrame / 2).coerceAtLeast(1)
        /* AudioTrack 只接受 byte/short/float 三种数组，静音用 short 全零 */
        val chunk = ShortArray(minOf(frames, 4096 / outCh) * outCh)
        var leftSamples = frames.toLong() * outCh
        while (leftSamples > 0 && running.get()) {
            val n = minOf(leftSamples, chunk.size.toLong()).toInt()
            val wrote = at.write(chunk, 0, n, AudioTrack.WRITE_BLOCKING)
            if (wrote <= 0) {
                /* 设备没起动/写不进去：不能把没写进去的当成已写，否则位置会假前进 */
                if (zeroWrites++ < 3) Log.w(TAG, "补静音写入返回 $wrote（设备未在放）")
                break
            }
            leftSamples -= wrote
            writtenFrames += (wrote / outCh).toLong()
        }
        notePosition()
    }

    /**
     * 上报位置：优先用设备的硬件时间戳（"这一帧什么时候真正被放出来"），
     * 拿不到就退回"已写样本数 − 设备延迟"。
     */
    private fun notePosition() {
        val at = track ?: return
        val clk = clock ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastPosLogMs >= 2000L) {
            lastPosLogMs = nowMs
            val played = (at.playbackHeadPosition - frameBase).coerceAtLeast(0)
            /* 两者应当同步增长；已写远超已播 = 数据堆在缓冲里没放出来 */
            Log.i(
                TAG,
                "AUDIOPOS|设备已播=${played}帧|已写=${writtenFrames}帧|采样率=$sampleRate|时钟=${clk.positionUs() / 1000}ms" +
                    "|源=${clk.lastSource}|后端=$decoderKind|解出=${ffmpegBytes}B",
            )
        }
        val nowMs2 = SystemClock.elapsedRealtime()
        val posUs = if (startedAtMs > 0L) {
            /*
             * 真实时间推算：设备一旦起动就按实时速率出声，
             * 所以"放出来多少"= 现在离放行多久 − 还堆在设备缓冲里没放的那一段。
             * 在途量用"已写 − 已播"直接量，不依赖标称采样率。
             */
            val played = (at.playbackHeadPosition - frameBase).coerceAtLeast(0)
            val inFlightFrames = (writtenFrames - played).coerceAtLeast(0)
            val inFlightUs = inFlightFrames * 1_000_000L / sampleRate
            val elapsedUs = (nowMs2 - startedAtMs) * 1000L
            (originUs + elapsedUs - inFlightUs).coerceIn(originUs, originUs + elapsedUs)
        } else if (at.getTimestamp(ts)) {
            /*
             * 硬件时间戳直接给的是"这一帧什么时候真正被放出来"，加上到现在的流逝就是当前播放位置。
             * 这里**不能再减输出延迟**：减了等于把位置整体往前挪，画面就永远比声音早，
             * 主观是"声音一直滞后"（2026-11-21 实测：估算延迟 250ms 过大，差值被算进了时钟）。
             */
            val frames = ts.framePosition - frameBase
            val playedUs = frames * 1_000_000L / sampleRate
            val sinceTsUs = (System.nanoTime() - ts.nanoTime) / 1000L
            originUs + playedUs + sinceTsUs
        } else {
            /*
             * 拿不到硬件时间戳时用**设备已经播放的帧数**，不能用"已写进去的样本数"。
             *
             * 已写样本数包含还躺在缓冲里没放出来的那一截，它随缓冲涨落而变，
             * 用它算出来的位置会慢慢偏大 —— 画面按这个位置判自己迟到、一路丢帧，
             * 观感就是"播一会儿声音就跑前面去了"。已播帧数是设备实际播到哪，长期不漂。
             */
            /* 播放头位置不含设备缓冲里那一段，这里减一个保守的余量（缓冲四分之一） */
            val played = at.playbackHeadPosition - frameBase
            originUs + played.coerceAtLeast(0) * 1_000_000L / sampleRate - latencyUs
        }
        clk.noteAudioPosition(posUs.coerceAtLeast(originUs), clockToken)
    }
}
