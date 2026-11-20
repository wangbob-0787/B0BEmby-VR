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

    /** 跳转后重新对齐首块 */
    private var seekFlushCount = 0

    private val ts = AudioTimestamp()

    /** 起播是否允许直接出声（false = 等放行，见 startNow） */
    private var autoPlayOn = true

    /** 写入被设备拒收的次数（只用于限制日志量） */
    private var zeroWrites = 0

    /** 位置日志节流 */
    private var lastPosLogMs = 0L
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
        /** 内存窗口大小（MB，0 = 直读）。与画面用同一档位，两路各一个窗。 */
        memWindowMb: Int = 0,
    ): Boolean {
        stop()
        lastError = null
        firstPcmAtMs = 0L
        writtenFrames = 0L
        firstBlockHandled = false
        this.clock = clock
        originUs = (originSec * US).toLong()

        val ex = MediaExtractor()
        return try {
            if (memWindowMb > 0 && headers.isEmpty()) {
                Log.i(TAG, "音频数据源：内存窗 ${memWindowMb}MB")
                ex.setDataSource(MemWindowSource(url, memWindowMb * 1024 * 1024))
            } else if (headers.isEmpty()) {
                ex.setDataSource(url)
            } else {
                ex.setDataSource(url, headers)
            }
            val pick = pickAudioTrack(ex, trackIndex)
            if (pick < 0) {
                lastError = if (trackIndex >= 0) "指定音轨 $trackIndex 不可解" else "没有可解的音轨"
                Log.w(TAG, "音频起不来：$lastError")
                ex.release()
                return false
            }
            val fmt = ex.getTrackFormat(pick)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            if (channelCount !in 1..8) channelCount = 2
            trackDesc = "轨$pick 编码=$mime 采样率=$sampleRate 声道=$channelCount"
            Log.i(TAG, "音轨选择：$trackDesc")

            val c = MediaCodec.createDecoderByType(mime)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c

            ex.selectTrack(pick)
            ex.seekTo((startSec * US).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            extractor = ex
            /* seek 后第一帧的内容时间就是这一路的真实起点（跳转落点是关键帧，不等于请求位置） */
            audioOriginUs = ex.sampleTime.coerceAtLeast(0L)

            val at = buildTrack()
            if (at == null) {
                lastError = "音频设备建不起来"
                c.stop(); c.release(); codec = null
                ex.release(); extractor = null
                return false
            }
            track = at
            /*
             * 输出延迟：设备不提供可用的延迟读数时，按缓冲区一半保守估计。
             * 减多了会让位置略偏早（画面略微超前），减少了会让位置偏晚（画面略滞后），
             * 宁可偏早不可偏晚 —— 偏晚在主观上就是"声音比画面慢"。
             */
            latencyUs = runCatching {
                val frames = at.bufferSizeInFrames
                if (frames > 0) frames * 1_000_000L / sampleRate / 2 else 0L
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
            if (autoPlay) at.play()
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
            runCatching { ex.release() }
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
        val t = thread
        thread = null
        if (t != null && t.isAlive) {
            runCatching { t.join(1500) }
        }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { track?.release() }
        runCatching { extractor?.release() }
        codec = null
        track = null
        extractor = null
        paused.set(false)
        return t == null || !t.isAlive
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

    private fun loop() {
        val ex = extractor ?: return
        val c = codec ?: return
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        val src = ByteBuffer.allocateDirect(1 shl 18)
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
                    ex.seekTo((seekTarget * US).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    originUs = (seekTarget * US).toLong()
                    writtenFrames = 0L
                    firstBlockHandled = false
                    seekFlushCount++
                    audioOriginUs = ex.sampleTime.coerceAtLeast(0L)
                    /* flush 之后设备帧计数不一定归零（各机型行为不同），基准取当下值 */
                    frameBase = runCatching { at.playbackHeadPosition.toLong() }.getOrDefault(0L)
                    inputDone = false
                    eos = false
                    clock?.resetTo(originUs)
                    if (!paused.get()) at.play()
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
                        val n = ex.readSampleData(src, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ci, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = c.getInputBuffer(ci)
                            if (buf != null) {
                                buf.clear()
                                src.position(0)
                                src.limit(n)
                                buf.put(src)
                                c.queueInputBuffer(ci, 0, n, ex.sampleTime.coerceAtLeast(0L), 0)
                            } else {
                                c.queueInputBuffer(ci, 0, 0, 0, 0)
                            }
                            ex.advance()
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
                val skipBytes = (skipFrames * bytesPerFrame).coerceAtMost(bytes)
                offsetBytes = skipBytes
                bytes -= skipBytes
                Log.i(TAG, "音频首帧比画面早 ${"%.0f".format(-deltaUs / 1000.0)}ms → 裁掉开头 $skipFrames 帧")
            }
        }

        while (bytes > 0 && running.get()) {
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
        val posUs = if (at.getTimestamp(ts)) {
            val frames = ts.framePosition - frameBase
            val playedUs = frames * 1_000_000L / sampleRate
            val sinceTsUs = (System.nanoTime() - ts.nanoTime) / 1000L
            originUs + playedUs + sinceTsUs - latencyUs
        } else {
            /*
             * 拿不到硬件时间戳时用**设备已经播放的帧数**，不能用"已写进去的样本数"。
             *
             * 已写样本数包含还躺在缓冲里没放出来的那一截，它随缓冲涨落而变，
             * 用它算出来的位置会慢慢偏大 —— 画面按这个位置判自己迟到、一路丢帧，
             * 观感就是"播一会儿声音就跑前面去了"。已播帧数是设备实际播到哪，长期不漂。
             */
            val played = at.playbackHeadPosition - frameBase
            originUs + played.coerceAtLeast(0) * 1_000_000L / sampleRate - latencyUs
        }
        clk.noteAudioPosition(posUs.coerceAtLeast(originUs))
    }
}
