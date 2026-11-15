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
        startSec: Double = 0.0,
    ): Boolean {
        stop()
        lastError = null
        clockProvider = clock
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
                        codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                        ex.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    syncToClock(info.presentationTimeUs)
                    /*
                     * releaseOutputBuffer(true)：把这一帧真正送到 Surface。
                     * 这一步就是把画面交给 OES 纹理的时刻，之后渲染层 updateTexImage()
                     * 才拿得到新内容。
                     */
                    codec.releaseOutputBuffer(outIdx, true)
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
     * 让这一帧对上音频的位置。
     *
     * 音频在 mpv 那边播，它报的播放位置就是权威时间。视频这边每帧拿自己的时间戳跟它比：
     *   · 超前 → 等到该出现的那一刻（最多等 200 毫秒，防时间戳异常把线程挂死）
     *   · 落后 → 立刻放，不追不跳
     *
     * 为什么不用「自己第一帧 + 墙钟」那套：两边起点不同（mpv 可能已经起播一段、
     * 我们又要先打开流再初始化解码器），固定偏移会让音画差一截而且越跑越歪
     * （2026-11-15 父亲实测「画面声音不同步」）。
     */
    private fun syncToClock(ptsUs: Long) {
        val clk = clockProvider?.invoke() ?: return
        if (clk <= 0.0) return
        val ptsSec = ptsUs / 1_000_000.0
        val lead = ptsSec - clk          // >0 画面超前
        if (lead <= 0.02) return         // 已到点或落后：直接放
        val waitMs = (lead * 1000.0).toLong().coerceAtMost(200L)
        try {
            Thread.sleep(waitMs)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
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
