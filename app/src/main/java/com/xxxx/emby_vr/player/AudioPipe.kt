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

    private var extractor: MediaExtractor? = null

    /*
     * 读线程 + 包队列（与画面那套完全对称，2026-11-21 父亲定）。
     *
     * 为什么声音也要队列：之前声音是"边读边解"，网盘抖一下就断供，
     * 而画面有三十多兆存货顶着 —— 所以每次卡顿都只打中声音，断一段就变成永久落后。
     */
    private var feedThread: Thread? = null
    /* 音频码率低（约 176KB/s），16MB 约 90 秒，足够吸收抖动 */
    private val feedQueue = SampleQueue(16 * 1024 * 1024)
    @Volatile private var feedSeekSec = -1.0
    @Volatile private var feedJustSought = false
    private var prepareLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var preparedFormat: MediaFormat? = null
    @Volatile private var prepareError: String? = null
    @Volatile private var feedUrl = ""
    @Volatile private var feedHeaders: Map<String, String> = emptyMap()
    @Volatile private var feedStartSec = 0.0
    @Volatile private var feedTrackIndex = -1
    @Volatile private var feedMemWindowSec = 0
    private var codec: MediaCodec? = null
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
    ): Boolean {
        stop()
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
        feedQueue.reset()
        prepareLatch = java.util.concurrent.CountDownLatch(1)
        preparedFormat = null
        prepareError = null
        running.set(true)
        return try {
            feedThread = Thread({ audioFeedLoop() }, "AudioFeed").apply { isDaemon = true; start() }
            if (!prepareLatch.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                lastError = "音频解封装准备超时"
                Log.e(TAG, lastError!!)
                stop()
                return false
            }
            val fmt = preparedFormat
            val perr = prepareError
            if (fmt == null || perr != null) {
                lastError = perr ?: "音频解封装准备失败"
                Log.e(TAG, lastError!!)
                stop()
                return false
            }
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            if (channelCount !in 1..8) channelCount = 2
            trackDesc = "编码=$mime 采样率=$sampleRate 声道=$channelCount"
            Log.i(TAG, "音轨选择：$trackDesc（走队列，读线程已起）")

            val c = MediaCodec.createDecoderByType(mime)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c

            val at = buildTrack()
            if (at == null) {
                lastError = "音频设备建不起来"
                c.stop(); c.release(); codec = null
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
            extractor = null
            false
        }
    }

    /**
     * 放行：起动设备并开始写数据。
     *
     * 只有这一处会把设备从"准备好但没动"变成"正在放"，所以声音与画面必然同刻开始。
     */
    fun startNow() {
        if (started.get()) return
        started.set(true)
        startedAtMs = SystemClock.elapsedRealtime()
        paused.set(false)
        runCatching { track?.play() }
        Log.i(TAG, "音频放行：设备起动 + 开始写数据（位置中枢=${clock?.positionUs()?.div(1000L) ?: 0L}ms）")
    }

    fun pause() {
        paused.set(true)
        runCatching { track?.pause() }
        clock?.pause()
    }

    fun resume() {
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
        feedQueue.abort()
        /* 读线程持连接：它没退出就等于连接没归还，必须进返回值（豆豆 2026-11-21 审出） */
        val ft = feedThread
        ft?.let { runCatching { it.join(1500) } }
        feedThread = null
        val feedDead = ft == null || !ft.isAlive
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            runCatching { t.join(1500) }
        }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { track?.release() }
        /*
         * extractor 不在这里释放：它归读线程所有，由 audioFeedLoop 的 finally 释放。
         * 读线程还没退出就从这里拆，它下一次碰 extractor 必抛（豆豆 2026-11-21 第二遍审出）。
         */
        codec = null
        track = null
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
            val rank = MIME_PRIORITY.indexOf(mime)
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank
                best = i
            }
        }
        return best
    }

    private fun isDecodable(mime: String): Boolean =
        MIME_PRIORITY.contains(mime) && !mime.contains("dts", true) && !mime.contains("truehd", true)

    private fun buildTrack(): AudioTrack? {
        val chMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val outCh = if (channelCount == 1) 1 else 2
        bytesPerFrame = outCh * 2
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
    private fun audioFeedLoop() {
        val ex = MediaExtractor()
        try {
            val hdrs = feedHeaders
            if (feedMemWindowSec > 0 && hdrs.isEmpty()) {
                ex.setDataSource(MemWindowSource(feedUrl, 32 * 1024 * 1024))
            } else if (hdrs.isEmpty()) {
                ex.setDataSource(feedUrl)
            } else {
                ex.setDataSource(feedUrl, hdrs)
            }
            val pick = pickAudioTrack(ex, feedTrackIndex)
            if (pick < 0) {
                prepareError = "没有可解的音轨"
                prepareLatch.countDown()
                return
            }
            val fmt = ex.getTrackFormat(pick)
            ex.selectTrack(pick)
            if (feedStartSec > 0.5) {
                ex.seekTo((feedStartSec * US).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                feedJustSought = true
            }
            audioOriginUs = ex.sampleTime.coerceAtLeast(0L)
            preparedFormat = fmt
            extractor = ex
            prepareLatch.countDown()
            Log.i(TAG, "音频读线程就绪：轨道=$pick 起点=${"%.3f".format(audioOriginUs / US)}s")

            var arr = ByteArray(1024 * 1024)
            var bb = java.nio.ByteBuffer.wrap(arr)
            while (running.get()) {
                val target = feedSeekSec
                if (target >= 0.0) {
                    feedSeekSec = -1.0
                    runCatching { ex.seekTo((target * US).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                    feedJustSought = true
                    audioOriginUs = runCatching { ex.sampleTime }.getOrDefault(0L).coerceAtLeast(0L)
                    feedQueue.flush()
                    Log.i(TAG, "音频读线程跳转到 ${"%.2f".format(target)}s（首帧=${"%.3f".format(audioOriginUs / US)}s）")
                }
                bb.clear()
                var size = -1
                var retries = 0
                while (running.get()) {
                    size = try {
                        ex.readSampleData(bb, 0)
                    } catch (t: Throwable) {
                        -1
                    }
                    if (size >= 0) break
                    /* 装不下就按帧大小扩缓冲（音频帧通常很小，这里只是兜底） */
                    val need = runCatching { ex.sampleSize }.getOrDefault(0L)
                    if (need > bb.capacity().toLong()) {
                        arr = ByteArray((need + 1024L * 1024L).toInt())
                        bb = java.nio.ByteBuffer.wrap(arr)
                        bb.clear()
                        size = try {
                            ex.readSampleData(bb, 0)
                        } catch (t: Throwable) {
                            -1
                        }
                        if (size >= 0) break
                    }
                    if (!feedJustSought) break
                    if (retries++ >= 100) break
                    Thread.sleep(20)
                }
                if (size < 0) {
                    feedQueue.putEos()
                    Log.i(TAG, "音频读线程到结尾")
                    break
                }
                feedJustSought = false
                val pts = try {
                    ex.sampleTime
                } catch (t: Throwable) {
                    break
                }
                val data = ByteArray(size)
                bb.position(0)
                bb.get(data, 0, size)
                if (!feedQueue.put(SampleQueue.Packet(data, size, pts))) break
                try {
                    ex.advance()
                } catch (t: Throwable) {
                    break
                }
            }
        } catch (t: Throwable) {
            prepareError = "音频解封装异常：${t.javaClass.simpleName}: ${t.message}"
            prepareLatch.countDown()
            Log.e(TAG, prepareError!!)
        } finally {
            runCatching { ex.release() }
            if (extractor === ex) extractor = null
        }
    }

    private fun loop() {
        /* 读线程独占 extractor；这里只从队列取数据 */
        val c = codec ?: return
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var eos = false

        while (running.get()) {
            try {
                /* 跳转：顺序固定 —— 停设备 → 清解码器 → 定新起点 → 重新起播 */
                val seekTarget = pendingSeekSec
                if (seekTarget >= 0.0) {
                    pendingSeekSec = -1.0
                    at.pause()
                    at.flush()
                    c.flush()
                    /* 跳转交给读线程（extractor 归它）；这里只清队列与本地计数 */
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

                if (!inputDone) {
                    val ci = c.dequeueInputBuffer(5_000)
                    if (ci >= 0) {
                        /* 数据来自读线程攒好的队列（与画面同一套结构） */
                        val pkt = feedQueue.get()
                        if (pkt == null) {
                            c.queueInputBuffer(ci, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = c.getInputBuffer(ci)
                            if (buf != null) {
                                buf.clear()
                                buf.put(pkt.data, 0, pkt.size)
                                c.queueInputBuffer(ci, 0, pkt.size, pkt.ptsUs.coerceAtLeast(0L), 0)
                            } else {
                                c.queueInputBuffer(ci, 0, 0, 0, 0)
                            }
                        }
                    }
                }

                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    val out = c.getOutputBuffer(oi)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        writePcm(out, info)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    val r = runCatching { nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                    if (r != sampleRate) {
                        Log.w(TAG, "解码输出采样率与轨声明不一致：$sampleRate → $r（设备仍按 $sampleRate）")
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
        }
    }

    private fun writeSilence(at: AudioTrack, frames: Int) {
        /* AudioTrack 只接受 byte/short/float 三种数组，静音用 short 全零 */
        val chunk = ShortArray(minOf(frames, 4096))
        var left = frames
        while (left > 0 && running.get()) {
            val n = minOf(left, chunk.size)
            at.write(chunk, 0, n, AudioTrack.WRITE_BLOCKING)
            left -= n
            writtenFrames += n.toLong()
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
                    "|源=${clk.lastSource}",
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
            originUs + (nowMs2 - startedAtMs) * 1000L - inFlightUs
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
