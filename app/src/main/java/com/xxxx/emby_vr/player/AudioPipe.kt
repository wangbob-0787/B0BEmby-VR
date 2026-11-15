package com.xxxx.emby_vr.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自管音频播放：MediaCodec 解音频，AudioTrack 直接输出。
 *
 * 为什么要有它：P5 片子的画面已经改成自己解了（[VideoPipe]），如果声音还留在 mpv 那边，
 * 就要为同一个文件开**第二条连接** —— 实测起播比单连接慢二十秒（2026-11-15 父亲实测：
 * 声音先出、画面等二十秒）。全部自己解之后只有一条连接，音画也来自同一个时间源，
 * 天然同步，不用再拿别处的播放位置去对齐。
 *
 * 时间基准出的就是这个类：它维护「已经播出去的音频到第几秒」，画面拿它当权威时间。
 */
class AudioPipe {

    companion object {
        private const val TAG = "B0BEmbyVR-AudioPipe"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 已播出去的音频位置（秒），画面靠它对齐 */
    @Volatile var clockSec: Double = 0.0
        private set

    /** 起播时的绝对位置（续播要从这里算起） */
    @Volatile private var baseSec: Double = 0.0

    @Volatile var sampleRate = 0
        private set
    @Volatile var channels = 2
        private set

    @Volatile var lastError: String? = null
        private set

    /**
     * 起播。
     *
     * @param url      与画面同一个地址（同一条连接就够，各开各的也无妨——同进程的两次请求
     *                 在服务端看来是两次，真要只开一条得共享解复用器，那是下一步的事）
     * @param startSec 起播位置
     * @return 成功与否（原因写 [lastError]）
     */
    fun start(url: String, headers: Map<String, String>, startSec: Double): Boolean {
        stop()
        lastError = null
        baseSec = startSec
        clockSec = startSec
        return try {
            val ex = MediaExtractor()
            if (headers.isEmpty()) ex.setDataSource(url) else ex.setDataSource(url, headers)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                ex.release()
                lastError = "容器里没有音频轨"
                Log.e(TAG, lastError!!)
                return false
            }
            ex.selectTrack(trackIndex)
            if (startSec > 0.5) {
                ex.seekTo((startSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            extractor = ex

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec

            val outChannels = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO
                              else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, outChannels, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outChannels)
                        .build()
                )
                .setBufferSizeInBytes(if (minBuf > 0) minBuf * 2 else sampleRate * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t

            Log.i(TAG, "起播 $mime ${sampleRate}Hz ${channels}声道")
            running.set(true)
            thread = Thread({ pump() }, "AudioPipe").apply {
                priority = Thread.NORM_PRIORITY + 3
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

    /**
     * 解码主循环。
     *
     * 用阻塞写（[AudioTrack.write] 第三参给 false 即阻塞）做流控：缓冲区满了自然就等，
     * 不需要自己算节拍。写出去多少样本就能反推播到第几秒，时钟就是这么来的。
     */
    private fun pump() {
        val ex = extractor ?: return
        val codec = decoder ?: return
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var writtenSamples = 0L

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
                        codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            when (val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                in 0..Int.MAX_VALUE -> {
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        out.get(pcm)
                        // 阻塞写：AudioTrack 缓冲满就在这里等，播放速度天然对
                        at.write(pcm, 0, pcm.size)
                        writtenSamples += pcm.size / (2 * channels)
                        clockSec = baseSec + writtenSamples.toDouble() / sampleRate
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "输出格式确定 ${codec.outputFormat}")
                }
            }
        }
        Log.i(TAG, "音频循环结束，已写 ${writtenSamples} 个样本")
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun resume() {
        runCatching { track?.play() }
    }

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
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        runCatching { extractor?.release() }
        extractor = null
    }
}
