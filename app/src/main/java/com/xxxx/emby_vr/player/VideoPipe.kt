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

    /**
     * 首帧之后的闸门：返回 true 时先把画面停住（声音还没开始，画面不能自己往前跑）。
     *
     * 自管解码起播时，第一帧解出来要立刻显示，但后面不能继续放 —— 声音那边还挂在
     * 起播位置等我们，画面先跑就变成有画无声的假播放，等声音放开再对齐会明显跳一下。
     */
    @Volatile private var holdGate: (() -> Boolean)? = null

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
    private var originPts = -1.0
    private var appliedOffset = Double.NaN
    /** 预卷帧（pts < audioStart，不许上屏）丢弃计数，写日志用 */
    private var prerollDropped = 0
    /** 连续丢帧累计毫秒（≥300ms 档用；累计 500ms 仍未追平 → 请求重启） */
    private var skippedMs = 0.0
    /** 时钟无效时的累计挂起毫秒（8s 判失败） */
    private var gateWaitMs = 0L
    /** 上一张真正上屏的帧 PTS（丢帧累计用） */
    private var lastRenderedPts = 0.0
    /** 首帧已解出（≠ 已上屏）：只回调一次 onFirstFrame 用 */
    @Volatile private var firstFrameReady = false

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
    companion object {
        /**
         * 落后多少就丢帧：图上定的是 P95+20ms（P95 = 4K 解码延迟基线）。
         * 初值 80ms，实测后按 P95 替换——**这不是可调参数，是同步上限**。
         */
        private const val DROP_BEHIND_SEC = 0.080
    }

    fun start(
        url: String,
        headers: Map<String, String>,
        surface: Surface,
        clock: (() -> Double)? = null,
        clockValid: (() -> Boolean)? = null,
        startSec: Double = 0.0,
        doviPath: String? = null,
        holdGate: (() -> Boolean)? = null,
        onFirstFrame: (() -> Unit)? = null,
        onDoviParams: (() -> Unit)? = null,
        onEos: (() -> Unit)? = null,
        onNeedRestart: ((Double) -> Unit)? = null,
    ): Boolean {
        stop()
        lastError = null
        clockProvider = clock
        clockValidProvider = clockValid
        doviParamPath = doviPath
        this.holdGate = holdGate
        this.onFirstFrame = onFirstFrame
        this.onDoviParams = onDoviParams
        this.onEos = onEos
        this.onNeedRestart = onNeedRestart
        firstFrameReady = false
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
        originPts = -1.0
        appliedOffset = Double.NaN
        prerollDropped = 0
        skippedMs = 0.0
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
                    /*
                     * 出帧节拍（2026-11-17 按施工图 §5 重写）。
                     *
                     * 三条判据，缺一不出帧：
                     *   ① 时钟"活着"（clockAdvancing：已加载 + 未暂停 + 位置最近 200ms 有推进）
                     *      —— 原来这里写死 `{ true }` 且用 `audioSec > 0` 当门，起播位置正好是 0 时
                     *      门恒不成立，画面全速出帧 = 父亲看到的"快速播放"
                     *   ② 零点已标定（appliedOffset 冻结后才有换算关系）
                     *   ③ 这一帧不早于音频起点（早于的是预卷帧，不许上屏）
                     * 时钟无效时的动作是**挂起不出帧**（图 §0 铁律 6），不是跳过检查继续出帧。
                     */
                    val ptsRaw = info.presentationTimeUs / 1_000_000.0
                    val audioSec = if (clockValidProvider?.invoke() == true) {
                        clockProvider?.invoke() ?: -1.0
                    } else -1.0

                    if (audioSec < 0.0) {
                        /* 时钟没在走：这一帧原地挂起，20ms 后重看，不推进也不丢弃 */
                        gateWaitMs += 20
                        if (gateWaitMs > 8000) {
                            lastError = "时钟 8 秒未推进（clockAdvancing=false）"
                            Log.e(TAG, "出帧中止：$lastError")
                            outputDone = false
                            break
                        }
                        try {
                            Thread.sleep(20)
                        } catch (t: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        continue
                    }
                    gateWaitMs = 0

                    /* 零点：audioStart 是 mpv 加载后的实际位置（外面按图 §4.2.1 第②步传进来） */
                    if (originPts < 0.0 && ptsRaw >= startSec) {
                        originPts = ptsRaw
                        appliedOffset = startSec - originPts
                        Log.i(TAG, "零点标定：start=$startSec origin=$originPts offset=$appliedOffset 预卷丢弃=$prerollDropped")
                    }
                    if (originPts < 0.0) {
                        /* 还在预卷区：丢弃但计数，不上屏（图 §5：预卷帧必须显式丢弃并计数） */
                        prerollDropped++
                        codec.releaseOutputBuffer(outIdx, false)
                        continue
                    }

                    val ptsRef = ptsRaw + appliedOffset   /* 换算到与主时钟同一时间轴 */
                    val ahead = ptsRef - audioSec

                    when {
                        /* 超前 40ms~300ms：按差值等待（分段可中断，每段检查 running） */
                        ahead > 0.040 && ahead < 0.300 -> {
                            var remainMs = ((ahead - 0.040) * 1000.0).toLong()
                            while (remainMs > 0 && running.get()) {
                                val step = remainMs.coerceAtMost(20L)
                                try {
                                    Thread.sleep(step)
                                } catch (t: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                remainMs -= step
                            }
                            if (!running.get()) break
                        }
                        /* 超前 ≥300ms：不等待，直接丢帧跳（图 §5 第二档；等待会饿死解码器） */
                        ahead >= 0.300 -> {
                            skippedMs += ((ptsRaw - lastRenderedPts).coerceAtLeast(0.0)) * 1000.0
                            codec.releaseOutputBuffer(outIdx, false)
                            decodedFrames++
                            /* 丢帧累计 500ms 仍未追平 → 请外面从主时钟位置重启（失步兜底） */
                            if (skippedMs >= 500.0) {
                                val target = audioSec
                                Log.w(TAG, "失步：连续丢帧 ${skippedMs.toInt()}ms 未追平 → 请求从 ${target}s 重启")
                                skippedMs = 0.0
                                runCatching { onNeedRestart?.invoke(target) }
                                break
                            }
                            continue
                        }
                        /* 落后超过阈值：丢帧追上（阈值 = P95+20ms，初值 80ms） */
                        ahead < -DROP_BEHIND_SEC -> {
                            codec.releaseOutputBuffer(outIdx, false)
                            decodedFrames++
                            continue
                        }
                    }

                    /*
                     * 首帧门（图 §4.2.1 ③④⑤）：卡在"出帧动作"上，不是卡在取输出缓冲上。
                     *
                     * 首帧解出来先报告外面（外面据此让主时钟开始走），但**先不上屏**——
                     * 门没放开就挂起，等外面确认时钟真的走起来再放行。
                     * 原来先 release 再回调，门永远看到"已放开"，等于没实现。
                     */
                    if (!firstFrameReady) {
                        firstFrameReady = true
                        Log.i(TAG, "首帧已就绪（未上屏）pts=$ptsRaw 预卷丢弃=$prerollDropped")
                        runCatching { onFirstFrame?.invoke() }
                    }
                    if (holdGate?.invoke() == true) {
                        /* 门还压着：这帧留在手里不上屏（不推进、不丢弃），稍后重看 */
                        try {
                            Thread.sleep(5)
                        } catch (t: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        continue
                    }
                    codec.releaseOutputBuffer(outIdx, true)
                    lastRenderedPts = ptsRaw
                    skippedMs = 0.0
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
        /*
         * 画面放完了：告诉外面一次（父新 2026-11-16 要求）。
         * 正常播完与被停止都会走到这里，外面按"是不是还在播"决定要不要当结束处理。
         */
        if (outputDone) runCatching { onEos?.invoke() }
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
    fun stop() {
        running.set(false)
        holdGate = null
        onNeedRestart = null
        firstFrameReady = false
        gateWaitMs = 0L
        onFirstFrame = null
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
