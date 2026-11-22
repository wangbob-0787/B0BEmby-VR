package com.xxxx.emby_vr.player

import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次解封装 = 一条连接 = 全部轨。
 *
 * 为什么要有这个类：网盘对同一文件只给两路连接，而 Emby 转发时自己会占两条
 * （一条读正文、一条读文件尾索引）。以前画面用 MediaExtractor、声音再开一路，
 * 到网盘就是三四条，必然超；表现出来就是读几 KB 就断、跳转被拒。
 *
 * 这里只开一次，画面包、声音包、字幕都在同一个读线程里按顺序取出后分发：
 *   - 读线程只有一个，因为一条连接本身是顺序的，拿完一个包才能拿下个包；
 *   - 队列归本类持有，跳转时由本类一次性清空两个队列再定位，
 *     否则会出现"声音还在旧位置、画面已到新位置"。
 *
 * 硬约束：读线程被画面队列阻塞时声音也会停，所以画面队列要够大；
 * 渲染端卡顿必须丢帧，不能停读；反过来也绝不能为了声音去跳过画面包（跳了就花屏）。
 */
class DemuxSession(
    private val url: String,
    private val startMs: Long,
    private val discardOthers: Boolean = true,
) {
    companion object {
        private const val TAG = "DemuxSession"
        private const val VIDEO_QUEUE_BYTES = 96 * 1024 * 1024
        private const val AUDIO_QUEUE_BYTES = 8 * 1024 * 1024
    }

    /** 画面包（字节 + 时间戳微秒 + 是否关键帧） */
    class VideoPacket(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    /** 声音包 */
    class AudioPacket(val data: ByteArray, val ptsUs: Long)

    val videoQueue = SampleQueue(VIDEO_QUEUE_BYTES)
    val audioQueue = SampleQueue(AUDIO_QUEUE_BYTES)

    var onSubtitle: ((startMs: Long, endMs: Long, text: String, track: Int) -> Unit)? = null

    /** 这一路彻底没了（单向，不配对）：上层据此提示并决定是否自愈 */
    var onRouteFailed: ((reason: String) -> Unit)? = null

    /** 跳转完成：上层据此统一新起点（首帧基准、首块对齐、字幕表） */
    var onSeekDone: ((targetMs: Long) -> Unit)? = null

    /** 跳转失败：不重建、不静默，必须让上层知道 */
    var onSeekFailed: ((reason: String) -> Unit)? = null

    /**
     * 跳转落点（首包到手后回灌一次）：目标毫秒、首视频 pts、首音频 pts（未知为 -1）。
     *
     * 为什么不在 seek 返回那一刻就报：`avformat_seek_file` 只保证"定位完成"，
     * 真实落点在关键帧上（实测 1987.4 → 1986.120，早 1.28 秒）。上层若拿请求目标当起点，
     * 首块对齐会把这段当成"音频早到"而裁掉/补静音，每条跳都偏一截。
     */
    var onSeekOrigin: ((targetMs: Long, videoPtsUs: Long, audioPtsUs: Long) -> Unit)? = null

    /** 跳转后等首视频包回灌落点 */
    private var seekOriginPending = false
    private var seekOriginTargetMs = 0L

    @Volatile
    var sampleRate: Int = 48000
        private set

    @Volatile
    var channelCount: Int = 2
        private set

    @Volatile
    var audioCodecName: String = ""
        private set

    @Volatile
    var subTrackCount: Int = 0
        private set

    @Volatile
    var durationMs: Long = 0L
        private set

    /** 画面格式：设备硬解要的格式头（mkv 里是 hvcC，正是它要的 csd） */
    @Volatile
    var videoCsd: ByteArray? = null
        private set

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    /** 本次打开/跳转之后第一条音频包的时间（微秒），-1 = 还没读到。音频起点对齐要用它 */
    @Volatile
    var audioFirstPtsUs: Long = -1L
        private set

    /** 本次打开/跳转之后第一条视频包的时间（微秒），-1 = 还没读到。落点判据用它 */
    @Volatile
    var videoFirstPtsUs: Long = -1L
        private set

    /** 读取轨道参数后，把"首包时间"清回未知（跳转与重新打开都要清） */
    private fun resetFirstPts() {
        audioFirstPtsUs = -1L
        videoFirstPtsUs = -1L
    }

    /** 画面编码（FFmpeg 的 codec_id）：173=hevc、27=h264；其余按 hevc 处理 */
    @Volatile
    var videoCodecId: Int = 0
        private set

    private var handle = 0L
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val seekTargetMs = java.util.concurrent.atomic.AtomicLong(-1L)

    /** 跳转目标（毫秒）；-1 表示没有待处理的跳转 */
    fun requestSeek(ms: Long) {
        seekTargetMs.set(ms)
    }

    /** 打开并读取轨道参数；成功返回 true。此时还没开始读包。 */
    fun open(): Boolean {
        val h = AudioDemuxNative.nativeDemuxOpen(url, startMs, if (discardOthers) 1 else 0)
        if (h == 0L) {
            Log.e(TAG, "解封装打开失败：$url")
            return false
        }
        handle = h
        val info = IntArray(6)
        if (AudioDemuxNative.nativeDemuxInfo(h, info) != 0) {
            Log.e(TAG, "解封装读参数失败")
            AudioDemuxNative.nativeDemuxClose(h)
            handle = 0L
            return false
        }
        sampleRate = info[0].takeIf { it > 0 } ?: 48000
        channelCount = info[1].takeIf { it in 1..8 } ?: 2
        subTrackCount = info[3]
        durationMs = info[4].takeIf { it > 0 }?.toLong() ?: 0L
        audioCodecName = runCatching { AudioDemuxNative.nativeDemuxCodecName(h) }.getOrDefault("")

        val csdOut = IntArray(4)
        videoCsd = runCatching { AudioDemuxNative.nativeDemuxVideoCsd(h, csdOut) }.getOrNull()
        videoWidth = csdOut[0]
        videoHeight = csdOut[1]
        videoCodecId = csdOut[3]
        Log.i(
            TAG,
            "解封装就绪：采样率=$sampleRate 声道=$channelCount 解码器=$audioCodecName " +
                "字幕轨=$subTrackCount 时长=${durationMs / 1000}s " +
                "画面=${videoWidth}x$videoHeight csd=${videoCsd?.size ?: 0}B",
        )
        return true
    }

    /** 启动唯一的读线程（参数已就绪之后调用） */
    fun start() {
        if (handle == 0L) return
        running.set(true)
        thread = Thread({ loop() }, "demux-session").apply { start() }
    }

    private fun loop() {
        val h = handle
        /* 4K HEVC 的关键帧包常有 2~4MB：缓冲小于它会被 native 判成致命错，整片直接停 */
        val buf = ByteArray(8 * 1024 * 1024)
        val meta = IntArray(5)
        var waitMs = 0L
        var netRetry = 0
        while (running.get()) {
            val target = seekTargetMs.get()
            if (target >= 0) {
                seekTargetMs.set(-1L)
                /* 跳转只在这里做：先清两个队列，再定位，最后恢复读 */
                videoQueue.flush()
                audioQueue.flush()
                resetFirstPts()
                val r = runCatching { AudioDemuxNative.nativeDemuxSeek(h, target) }.getOrDefault(-1)
                Log.i(TAG, "跳转到 ${target}ms 结果=$r")
                if (r >= 0) {
                    seekOriginPending = true
                    seekOriginTargetMs = target
                }
                if (r >= 0) {
                    runCatching { onSeekDone?.invoke(target) }
                } else {
                    /* 跳转失败却当成成功，会让画面/声音停在两个不同的位置 */
                    runCatching { onSeekFailed?.invoke("跳转失败 rc=$r") }
                }
            }
            val n = try {
                AudioDemuxNative.nativeDemuxRead(h, buf, meta)
            } catch (t: Throwable) {
                Log.w(TAG, "读异常：${t.javaClass.simpleName}")
                -9
            }
            when {
                n < 0 -> {
                    when (n) {
                        -1 -> {
                            Log.i(TAG, "读到结尾（时长=${durationMs / 1000}s）")
                            videoQueue.putEos()
                            audioQueue.putEos()
                            return
                        }
                        -2 -> {
                            waitMs += 300L
                            if (waitMs > 8_000L) {
                                /* 等太久不是结尾：上报，不发 EOS */
                                Log.w(TAG, "连续 8 秒没有数据 → 上报网络错误")
                                runCatching { onRouteFailed?.invoke("等待超时") }
                                return
                            }
                            var slept = 0L
                            while (slept < 300L && running.get()) {
                                Thread.sleep(100)
                                slept += 100
                            }
                        }
                        -5 -> {
                            /* 被我们自己打断（停播/换片）：不是网络错，也不是结尾 */
                            Log.i(TAG, "解封装被主动打断，读线程退出")
                            return
                        }
                        -3 -> {
                            if (!running.get()) return
                            netRetry++
                            if (netRetry > 2) {
                                Log.w(TAG, "网络错误连续 $netRetry 次 → 上报")
                                runCatching { onRouteFailed?.invoke("网络错误") }
                                return
                            }
                            var slept = 0L
                            while (slept < 500L && running.get()) {
                                Thread.sleep(100)
                                slept += 100
                            }
                        }
                        else -> {
                            Log.e(TAG, "致命错误 rc=$n → 上报")
                            runCatching { onRouteFailed?.invoke("解封装致命错误") }
                            return
                        }
                    }
                }
                n == 0 -> Unit
                else -> {
                    waitMs = 0L
                    netRetry = 0
                    val ptsUs = if (meta[2] >= 0) meta[2] * 1000L else 0L
                    when (meta[0]) {
                        0 -> {
                            if (audioFirstPtsUs < 0L) audioFirstPtsUs = ptsUs
                            if (!audioQueue.put(SampleQueue.Packet(buf.copyOf(n), n, ptsUs))) break
                        }
                        1 -> {
                            if (videoFirstPtsUs < 0L) videoFirstPtsUs = ptsUs
                            if (seekOriginPending) {
                                seekOriginPending = false
                                runCatching {
                                    onSeekOrigin?.invoke(seekOriginTargetMs, videoFirstPtsUs, audioFirstPtsUs)
                                }
                            }
                            val key = meta[3] != 0
                            if (!videoQueue.put(SampleQueue.Packet(buf.copyOf(n), n, ptsUs))) break
                        }
                        2 -> {
                            val text = String(buf, 0, n, Charsets.UTF_8)
                            if (text.isNotBlank()) {
                                val endMs = if (meta[4] > meta[2]) meta[4].toLong() else 0L
                                runCatching { onSubtitle?.invoke(ptsUs / 1000, endMs, text, meta[3]) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 中止：停读线程并作废两个队列，把阻塞在 get() 上的消费端叫醒。
     *
     * 只能用在停止路径 —— 跳转清队列必须用 flush()：abort 之后 get() 永久返回 null，
     * 两条 Pipe 会同时走"本路结束"，现场就是跳一下整片当播完（图 §2-7）。
     */
    fun abort() {
        running.set(false)
        if (handle != 0L) runCatching { AudioDemuxNative.nativeDemuxAbort(handle) }
        videoQueue.abort()
        audioQueue.abort()
    }

    /** 画面解码器要的格式；没有 extradata 或宽高时返回 null（调用方按起播失败处理） */
    fun videoFormat(): MediaFormat? {
        val csd = videoCsd ?: return null
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val mime = when (videoCodecId) {
            27 -> "video/avc"
            else -> "video/hevc"
        }
        return MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
        }
    }

    /** 音频解码器要的格式（自带库按 mime + 采样率 + 声道打开） */
    fun audioFormat(): MediaFormat = MediaFormat.createAudioFormat(
        "audio/" + audioCodecName.ifBlank { "eac3" }, sampleRate, channelCount,
    )

    fun stop() {
        running.set(false)
        if (handle != 0L) runCatching { AudioDemuxNative.nativeDemuxAbort(handle) }
        thread?.let { runCatching { it.join(1500) } }
        thread = null
        videoQueue.abort()
        audioQueue.abort()
        if (handle != 0L) {
            runCatching { AudioDemuxNative.nativeDemuxClose(handle) }
            handle = 0L
        }
    }
}
