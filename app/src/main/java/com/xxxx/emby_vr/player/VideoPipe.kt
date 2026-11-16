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

    /**
     * 杜比参数落盘路径（与内核那份同一个文件，渲染层读它做还原）。
     *
     * 自管解码之后内核不再解视频，也就没人写这份参数了 —— 这里补上：
     * 每帧从码流里解出 RPU，参数变了才落盘（渲染层按 mtime 判断要不要重读）。
     */
    @Volatile private var doviParamPath: String? = null

    /** 上一份写出去的参数，用来判断变没变 */
    private var lastDovi: FloatArray? = null

    /** 复用的取样缓冲：每帧临时 new 一个几 MB 的数组会把 GC 拖垮 */
    private val sampleBuf = ByteArray(8 * 1024 * 1024)

    /** 参数落盘的最小间隔（帧）：每帧都写会让渲染层每帧重读文件 */
    private var doviWriteSkip = 0

    /** 解不出参数的前几帧各打一条日志，别刷屏 */
    private var doviMissLog = 0

    /** 画面自己的时钟基准（墙钟连续走，播放器位置只用来纠偏） */
    private var basePtsSec = -1.0
    private var baseWallNs = 0L
    private var lastCorrectionNs = 0L

    /** 解码出来的实际宽高，起播后才有值 */
    @Volatile var videoWidth = 0
        private set
    @Volatile var videoHeight = 0
        private set

    /** 已解出的帧数，给日志与首帧判定用 */
    @Volatile var decodedFrames = 0L
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
    ): Boolean {
        stop()
        lastError = null
        clockProvider = clock
        clockValidProvider = clockValid
        doviParamPath = doviPath
        lastDovi = null
        doviWriteSkip = 0
        basePtsSec = -1.0
        baseWallNs = 0L
        lastCorrectionNs = 0L
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
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "起播失败：$lastError")
            stop()
            false
        }
    }

    /** 解码主循环：喂码流、取画面，按时间轴送出去，直到停止或码流结束。 */
    private fun pump() {
        val ex = extractor ?: return
        val codec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (running.get() && !outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val pts = ex.sampleTime
                        /*
                         * 趁这帧还在手里，先解出杜比参数。
                         *
                         * 必须在 queueInputBuffer 之前读：入队之后缓冲归解码器所有，
                         * 内容随时可能被改写。
                         */
                        harvestDovi(buf, size)
                        codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                        ex.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    /*
                     * 定时输出：把「这一帧该在哪一刻显示」交给系统排，而不是自己 sleep 等。
                     *
                     * 时间基准用**自己的墙钟连续走**，播放器的位置只用来偶尔纠偏
                     * （2026-11-16 父亲实测「速度一会快一会慢」）：
                     * 逐帧去读播放器的位置，而它缓冲时那个值本身在跳，画面就跟着跳。
                     * 这与弹幕时钟是同一条定式 —— 时钟自己走，播放器的值只做平滑纠偏。
                     */
                    val ptsSec = info.presentationTimeUs / 1_000_000.0
                    val nowNs = System.nanoTime()
                    if (basePtsSec < 0.0) {
                        basePtsSec = ptsSec
                        baseWallNs = nowNs
                    }
                    // 播放器给的秒数，每半秒拿来校一次基准
                    if (nowNs - lastCorrectionNs > 500_000_000L) {
                        lastCorrectionNs = nowNs
                        val clk = clockProvider?.invoke() ?: -1.0
                        if (clk > 0.0) {
                            val localNow = basePtsSec + (nowNs - baseWallNs) / 1_000_000_000.0
                            if (kotlin.math.abs(localNow - clk) > 0.8) {
                                // 偏离太多：直接把基准挪到播放器位置
                                basePtsSec = clk - (nowNs - baseWallNs) / 1_000_000_000.0
                            }
                        }
                    }
                    val dueNs = baseWallNs + ((ptsSec - basePtsSec) * 1_000_000_000.0).toLong()
                    // 最多等 80 毫秒：比一帧周期略长，够纠偏又不会拖住画面
                    val delayNs = (dueNs - nowNs).coerceIn(-2_000_000_000L, 80_000_000L)
                    codec.releaseOutputBuffer(outIdx, System.nanoTime() + delayNs)
                    decodedFrames++
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    videoWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "输出格式确定 ${videoWidth}x$videoHeight")
                }
                // INFO_TRY_AGAIN_LATER：这一轮没东西，继续
            }
        }
        Log.i(TAG, "解码循环结束，共 $decodedFrames 帧")
    }

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
                Log.i(TAG, "杜比参数已解出：分段数=${params[2].toInt()} 通道=${params[1].toInt()}")
            }
            lastDovi = params
            doviWriteSkip = 3
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
    fun stop() {
        running.set(false)
        thread?.let {
            try {
                it.join(600)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
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
        extractor = null
    }
}
