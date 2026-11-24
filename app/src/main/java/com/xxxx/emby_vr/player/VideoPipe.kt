package com.xxxx.emby_vr.player

import android.media.MediaCodec
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

    private var decoder: MediaCodec? = null
    private var thread: Thread? = null

    /*
     * 读线程 → 队列 → 解码线程（照 ffplay 的 packet queue，2026-11-21）。
     *
     * 为什么加这一层：网络一抖，原来解码线程直接卡在"读不到数据"上，画面就冻住；
     * 有了队列，读线程可以在网络好的时候多攒几秒，解码线程只从内存拿。
     * 队列按字节封顶（32MB，约两三秒 4K），满了读线程等 —— 与 ffplay 一样不丢帧。
     */
    private var feedThread: Thread? = null
    /*
     * 样本队列：按码率折算约 6~8 秒（4K 12MB/s → 96MB）。
     * 32MB 只有两秒多，网盘抖 5~10 秒就挡不住（豆豆 2026-11-21 核）。
     */
    private val feedQueue = SampleQueue(
        /* 上限保护：最多占进程可用内存的四分之一，避免 96MB 常驻把堆压爆 */
        minOf(96 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 4).toInt()),
    )

    /**
     * 全包档：连接与读线程归 [DemuxSession]，本类不建 feed 线程、不建解封装、不关连接。
     *
     * 队列也跟着换：off 档用本类自己攒的 [feedQueue]，全包档直接消费 Session 的队列。
     */
    @Volatile private var fullMode = false
    @Volatile private var sourceQueue: SampleQueue = feedQueue
    @Volatile private var session: DemuxSession? = null
    /** 全包档断流判定：低水位起点与当前是否已判停（<0.5s 连续 2.0s 判停，≥1.5s 恢复） */
    private var lowWaterSinceMs = 0L
    private var queueStalled = false
    /** 待执行的解码器清理（主线程只登记，由解码线程在两帧之间做） */
    @Volatile private var pendingSourceFlush = false
    /** 全包档：跳转后要等关键帧再喂包，否则硬解报错/花屏（豆豆 2026-11-22 P1-1） */
    @Volatile private var awaitingKeyframe = false
    private var skippedToKeyframe = 0
    /** 等关键帧的起点（elapsedRealtime）；0 = 没在等 */
    private var keyWaitSinceMs = 0L
    /* 排查用计数：前 20 个包、前 30 次取输出各打一遍（2026-11-22 全包档零输出） */
    private var pktProbe = 0
    private var outProbe = 0
    /** 取证落盘：把前 60 个视频包转 Annex-B 写成文件，拿去 .15 上让 ffmpeg 解 */
    private var dumpStream: java.io.FileOutputStream? = null
    private var dumpPath: String? = null
    private var dumpedPackets = 0
    private var dumpedBytes = 0L
    private var totalFed = 0L

    /**
     * 致命态：读线程卡在网络读里退不出来，连接也就还不回去。
     * 置位后上层不再尝试新建（网盘只给两路，强开只会被拒），只提示重启应用。
     */
    @Volatile
    var fatal = false
        private set

    /**
     * 连续"严重迟到"的帧数与起始时刻。
     *
     * 时钟一旦永久领先（多声道三倍速、令牌错走墙钟等），每一帧都会严重迟到、
     * 每一帧都立刻上屏 → 解码器全速跑 → 永久快进，而且这条路径原来没有任何恢复动作。
     * 连续 2 秒都处于严重迟到就报一次，供上层决定是否整条重建（这一代只允许一次）。
     */
    private var lateStreak = 0
    private var lateSinceMs = 0L
    private var lastLateLogMs = 0L

    /** 健康行用：最近一次连续严重迟到的时长（毫秒），0 表示正常 */
    @Volatile
    var lateStreakMs = 0L
        private set

    /** 待执行的跳转（解封装归读线程独占，跳转也交给它做） */
    @Volatile private var feedSeekSec = -1.0

    /** 解码线程首包只报一次 */
    private var pumpFirstLogged = false

    /** 读线程要用的起播参数（start() 的局部变量它看不到，必须落到字段） */
    @Volatile private var feedUrl: String = ""
    @Volatile private var feedHeaders: Map<String, String> = emptyMap()
    @Volatile private var feedStartSec: Double = 0.0
    @Volatile private var feedMemWindowSec: Int = 0

    /** 读线程准备完成会放行；主线程等它拿到轨道格式 */
    private var prepareLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var preparedFormat: MediaFormat? = null
    @Volatile private var prepareError: String? = null
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

    private var onOriginSec: ((Double) -> Unit)? = null

    /**
     * 片长（秒），打开容器时读一次。
     *
     * P5 这条路不加载内核之后，时长没有别处可问 —— 只能自己从容器头读。
     */
    @Volatile
    var durationSec: Double = 0.0
        private set

    /** 起点是否由画面首帧决定（P5 全自管路为 true） */
    private var originFromPicture = false

    /**
     * 跳转后**第一个真正上屏的帧**回调（参数 = 该帧的播放位置，秒）。
     *
     * 跳转放行靠它当"画面侧信号"（父亲 2026-11-18：跳转后等画面出新帧、内核报出位置，
     * 同一拍再放开声音）。原来跳转后立刻 setPaused(false)，声音先跑、画面还在跳，
     * 现场就是"跳完要手点一次播放键"。
     */
    @Volatile var onSeekRendered: ((Double) -> Unit)? = null
    /** 已在等跳转后的新帧（下一次上屏就回调一次） */
    @Volatile private var seekAwaitFrame = false

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

    /** 管线代次：推参数时带上，原生据此丢弃旧代晚到的推送 */
    @Volatile
    var pipeGen: Long = 0L

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

    /** 首个真实帧的起点只报一次（跳转会重新标定，但报给音频只在起播时有意义） */
    private var originReported = false
    private var originPts = -1.0
    private var appliedOffset = Double.NaN
    /** 预卷帧（pts < audioStart，不许上屏）丢弃计数，写日志用 */
    private var prerollDropped = 0

    /** 队列里现有数据的字节数（水位口径，供放行与健康行使用） */
    fun queuedBytes(): Int = sourceQueue.sizeBytes()

    /**
     * 队列里还有多少秒数据：用「队尾时间戳 − 当前播放位置」算。
     *
     * 不用字节除码率：低码率片（1080p 2~3MB/s）会被按 12MB/s 低估四五倍，
     * 于是"攒够 2 秒"实际要攒二十多兆，起播白等（豆豆 2026-11-21 第三遍审出）。
     */
    fun queuedSec(): Double {
        val tail = sourceQueue.lastPtsUs
        if (tail < 0L) return 0.0
        /*
         * 基准取"时钟；时钟没起来（起播时 running=false，读数恒为 0）就退回用起点。
         * 不退回的话，首次起播每次都会判成 0 秒存货 → 白等满 8 秒超时才放行。
         */
        val clk = clockNowOrWall() * 1_000_000.0
        val now = if (clk > 0.0) clk else audioStartSec * 1_000_000.0
        return ((tail - now) / 1_000_000.0).coerceAtLeast(0.0)
    }

    /** 码率估值（字节/秒）：从轨道格式的 bitrate 换算，拿不到就是 0 */
    @Volatile
    private var estimatedBps: Int = 0
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
        /** 内核是否处于暂停；暂停时画面原地握住当前帧、不上屏 */
        pausedNow: (() -> Boolean)? = null,
        /** 断流通知：true=读不到数据（请把声音也停住），false=恢复 */
        onStall: ((Boolean) -> Unit)? = null,
        onFirstFrame: (() -> Unit)? = null,
        /**
         * 首个真实视频帧的内容时间（秒）。
         *
         * 音频要按它对齐：音频首帧晚于这个时刻就补等长静音，早才裁 —— 时间轴只有这一个起点，
         * 所以必须先由画面探测出来再起音频（原型 `sim/p5render.c` 的「预探测首个视频帧」）。
         */
        onOriginSec: ((Double) -> Unit)? = null,
        /**
         * 时间轴起点由画面首帧决定（P5 全自管路）。
         *
         * 内核在场时起点取"内核报的起播位置"，画面靠 offset 去追它；声音也归我们自己之后，
         * 起点只能有一个来源 —— 画面首个真实帧。传 true 时 offset 恒为 0，帧 pts 直接与
         * 音频时钟比较，不再有两套基准（两套基准就是忽快忽慢的来源）。
         */
        originFromPicture: Boolean = false,
        /**
         * 内存缓冲要攒多少秒（0 = 不用）。
         *
         * 预算按"秒 × 片子自己的码率"折算成字节，所以 4K 与 1080p 自动适配；
         * 秒数取控制条的缓冲档位。做法照 mpv/ffplay：读线程只关心"播放点之后还剩几秒"。
         */
        memWindowSec: Int = 0,
        onDoviParams: (() -> Unit)? = null,
        onEos: (() -> Unit)? = null,
        onNeedRestart: ((Double) -> Unit)? = null,
        /** 预热帧数（图 §4.2.1 ③） */
        preheatFrames: Int = 3,
        /** 预热完成（三帧全部解出并归还）→ 报跨度与 RPU 状态，等外部放行 */
        onPreheatReady: ((Long, Boolean) -> Unit)? = null,
        /**
         * 全包档：把 Session 与画面格式直接交进来。
         *
         * 非 null 时本类**不建 feed 线程、不建解封装**，只配解码器并从 Session 的队列取包
         * （图 §3 铁律：多一条 feed 线程就是第二条连接，网盘立刻拒）。
         */
        demuxSession: DemuxSession? = null,
        formatOverride: MediaFormat? = null,
        /** 取证用：把前若干个视频包转成 Annex-B 落盘（null = 不落盘） */
        probeDumpPath: String? = null,
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
        fullMode = demuxSession != null
        /*
         * HDR10 渲染开关在每次起播/重建都重设一遍。
         *
         * 它原来只在起播分支里设一次，而换片/重建路径会把它清成 0（日志实证：
         * 12:54:10 "本次播放是 HDR10 → 0" 之后再无置 1）—— 于是画面退回"直接显示
         * PQ 原始码值"，看起来就是黑的（父亲 12:53 实测）。开关归渲染层，起播就该重设。
         */
        runCatching {
            val hdr10 = com.xxxx.emby_vr.player.PlaybackFlags.hdr10Source &&
                !com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource
            com.xxxx.emby_vr.vr.VrNative.setHdr10StreamActive(hdr10)
        }
        session = demuxSession
        sourceQueue = demuxSession?.videoQueue ?: feedQueue
        lowWaterSinceMs = 0L
        queueStalled = false
        clockProvider = clock
        clockValidProvider = clockValid
        doviParamPath = doviPath
        this.holdGate = holdGate
        this.clockProbe = clockProbe
        this.clockAgeProvider = clockAgeProvider
        this.clockRealProvider = clockReal
        this.onAudioAlign = onAudioAlign
        this.pausedNow = pausedNow
        this.onStall = onStall
        stopping = false
        this.onFirstFrame = onFirstFrame
        this.onOriginSec = onOriginSec
        this.originFromPicture = originFromPicture
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
        pausedHoldCount = 0
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
        originReported = false
        appliedOffset = Double.NaN
        prerollDropped = 0
        desyncSinceMs = 0L
        return try {
            /*
             * 解封装的打开、选轨、跳转、读，全部交给读线程（系统 MediaExtractor 要求同一线程）。
             * 主线程在这里只等它准备完，拿到轨道格式之后才能配解码器。
             */
            /* stop() 会把队列置成中止；复用一个实例起播前必须解开，否则第一帧就入队失败 */
            if (!fullMode) feedQueue.reset()
            feedUrl = url
            feedHeaders = headers
            feedStartSec = startSec
            feedMemWindowSec = memWindowSec
            prepareLatch = java.util.concurrent.CountDownLatch(1)
            prepareError = null
            running.set(true)
            if (fullMode) {
                /* 全包档：队列已由 Session 建好，本类不再起读线程 */
                preparedFormat = formatOverride
                prepareLatch.countDown()
                if (formatOverride == null) {
                    lastError = "全包档没有给出画面格式"
                    Log.e(TAG, lastError!!)
                    stop()
                    return false
                }
                /* 起播也走过同一条保险：万一首包不是关键帧，先等关键帧再喂（豆豆 2026-11-22） */
                awaitingKeyframe = true
                skippedToKeyframe = 0
                keyWaitSinceMs = 0L
                dumpPath = probeDumpPath
                dumpedPackets = 0
                dumpedBytes = 0L
                totalFed = 0L
                /* 片长由 Session 报（全包档没有 MediaExtractor 的 KEY_DURATION） */
                durationSec = (demuxSession?.durationMs ?: 0L) / 1000.0
                Log.i(
                    TAG,
                    "全包档起播：格式=${formatOverride.getString(MediaFormat.KEY_MIME)} " +
                        "片长=${"%.1f".format(durationSec)}s 队列=${sourceQueue.sizeBytes() / 1024}KB",
                )
            } else {
                preparedFormat = null
                feedThread = Thread({ feedLoop() }, "VideoFeed").apply { isDaemon = true; start() }
                if (!prepareLatch.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    lastError = "解封装准备超时（20s）"
                    Log.e(TAG, lastError!!)
                    stop()
                    return false
                }
            }
            val format = preparedFormat
            val perr = prepareError
            if (format == null || perr != null) {
                lastError = perr ?: "解封装准备失败"
                Log.e(TAG, lastError!!)
                stop()
                return false
            }
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            /*
             * 不传 Crypto，也不设 KEY_COLOR_* —— 杜比 P5 的颜色要由我们自己的着色器算，
             * 这里让它按普通 HEVC 解即可。硬件若擅自做色调映射，画面会显得亮度被压平，
             * 那属于要在实测里发现的问题，不在这里猜。
             */
            Log.i(TAG, "解码组件：${codec.name}（软解会是 c2.android.* 之类）")
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "起播 $mime ${videoWidth}x$videoHeight")
            /* 取证：两条路的格式头逐字节对比（全包档零输出时先看这里） */
            runCatching {
                val c0 = format.getByteBuffer("csd-0")
                val c1 = format.getByteBuffer("csd-1")
                val n0 = c0?.remaining() ?: 0
                /* 全量 hex（最多 256 字节）：两条路逐字节比 csd 是判定"参数集是否同源"的唯一硬证据 */
                val full = if (c0 != null) {
                    val d = ByteArray(minOf(256, n0))
                    c0.duplicate().get(d)
                    d.joinToString(" ") { "%02x".format(it) }
                } else ""
                Log.i(TAG, "格式取证：全包档=$fullMode csd0=${n0}B csd1=${c1?.remaining() ?: 0}B hex=$full")
            }

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
                    /*
                     * 断流判定：两拍之间样本数没涨，说明读不到数据了。
                     * 超过 1 拍（约 2 秒）就通知上层把声音一起停住；恢复了再通知放开。
                     */
                    /*
                     * 全包档读在 Session 里，本类的"样本数没涨"不再代表断流 —— 改看队列水位
                     * （图 §3 P1-3：<0.5 秒连续 2.0 秒判停，≥1.5 秒判恢复）。
                     */
                    val nowStalled = if (fullMode) queueWaterStalled() else {
                        val grew = statSamples != lastWatchSamples
                        !grew && statSamples > 0
                    }
                    if (nowStalled != stalled) {
                        stalled = nowStalled
                        Log.w(TAG, if (nowStalled) "断流：读不到数据 → 请上层暂停声音" else "断流恢复：数据来了 → 请上层继续")
                        runCatching { onStall?.invoke(nowStalled) }
                    }
                    /* 无进展秒数：数字冻结时靠它一眼看出"读不动"多久了（豆豆 2026-11-23） */
                    if (statSamples != lastWatchSamples) noProgressSince = System.currentTimeMillis()
                    val noProgressSec = if (statSamples == 0L) 0L
                    else (System.currentTimeMillis() - noProgressSince) / 1000
                    lastWatchSamples = statSamples
                    Log.i(
                        TAG,
                        "PIPEREAD|样本=$statSamples|字节=$statBytes|读返回=$statLastReadRet" +
                            "|要不到输入格=$statInMiss|取不到输出帧=$statOutMiss|held=$heldIdx" +
                            "|无进展=${noProgressSec}s",
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

    /**
     * 读线程：把解封装出来的每一包塞进队列（ffplay 的 packet_queue 那条读线程）。
     *
     * 解封装归 [PacketSource]：打开、选轨、跳转、推进都在这个线程里，解码线程只从队列取。
     * 换解封装做法（系统解封装 / 一次解封装全包）只换工厂，不动这个循环。
     */
    private fun feedLoop() {
        val src = newPacketSource()
        try {
            if (!src.open()) {
                prepareError = src.lastError ?: "解封装准备失败"
                Log.e(TAG, prepareError!!)
                prepareLatch.countDown()
                return
            }
        } catch (t: Throwable) {
            prepareError = "解封装准备异常：${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, prepareError!!)
            prepareLatch.countDown()
            return
        }
        src.configureBudget(budgetBytes(feedMemWindowSec, src.bitRate))
        estimatedBps = src.bitRate / 8
        durationSec = src.durationSec
        preparedFormat = src.format
        prepareLatch.countDown()
        Log.i(TAG, "读线程循环开始：缓冲=${src.bufferBytes / 1048576}MB")
        var readOk = 0
        var diedByError = false
        while (running.get()) {
            val target = feedSeekSec
            if (target >= 0.0) {
                feedSeekSec = -1.0
                src.seekTo(target)
                feedQueue.flush()
                Log.i(TAG, "读线程跳转到 ${"%.2f".format(target)}s（队列已清空）")
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
                if (!diedByError && !src.endedByError) feedQueue.putEos()
                Log.w(TAG, "读线程判结尾：已读 $readOk 帧 ${src.endDetail()}")
                break
            }
            readOk++
            if (readOk == 1) {
                val head = s.data.copyOf(minOf(12, s.data.size)).joinToString(" ") { "%02x".format(it) }
                Log.i(TAG, "包取证(extractor)#1 size=${s.data.size} pts=${s.ptsUs}us 头=$head")
            }
            if (readOk % 100 == 1) {
                Log.i(
                    TAG,
                    "读帧 #$readOk：本帧=${s.data.size} 字节 pts=${s.ptsUs}us " +
                        "缓冲=${src.bufferBytes / 1048576}MB 队列=${feedQueue.sizeBytes() / 1024}KB",
                )
            }
            if (!feedQueue.put(SampleQueue.Packet(s.data, s.data.size, s.ptsUs))) {
                Log.w(TAG, "入队失败（队列已中止），退出。已读 $readOk 帧")
                break
            }
            /* 解封装已经不能往下走（推进失败）：这一包照常解，然后收工 */
            if (src.endedByError) break
        }
        runCatching { src.close() }
        Log.i(TAG, "读线程退出：已读 $readOk 帧，队列剩 ${feedQueue.sizeBytes() / 1024}KB")
    }

    /** 画面这一路的解封装做法：系统解封装读视频轨（全包档下一步接 QueuePacketSource） */
    private fun newPacketSource(): PacketSource =
        ExtractorVideoSource(TAG, feedUrl, feedHeaders, feedMemWindowSec, feedStartSec)

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
        /* 解码线程只从队列取数据，不再直接碰解封装（它归读线程） */
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
            /* 全包档跳转：清解码器必须在这里做（两帧之间、手里没攥 buffer） */
            applyPendingFlush()
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
                    /* 数据来自读线程攒好的队列；队列给 null 表示读完了或已中止 */
                    val pkt = sourceQueue.get()
                    if (pkt != null && !pumpFirstLogged) {
                        pumpFirstLogged = true
                        Log.i(TAG, "解码线程取到首包：$pkt.size 字节 pts=${pkt.ptsUs}us")
                    }
                    statLastReadRet = (pkt?.size ?: -1).toLong()
                    /*
                     * 全包档跳转后：先等关键帧。落点不是关键帧时硬解会报错或花屏几秒，
                     * 丢掉非关键帧最多黑到下一个关键帧（豆豆 2026-11-22 P1-1）。
                     */
                    if (fullMode && awaitingKeyframe && pkt != null) {
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (keyWaitSinceMs == 0L) keyWaitSinceMs = nowMs
                        /*
                         * 丢包闸：坏源可能一直不出关键帧。
                         * 阈值取"约两倍最长 GOP"（实测这部片关键帧间隔 ~4 秒）：10 秒 / 600 包，
                         * 正常片永不误触发；坏源最多多黑 5 秒就报错（豆豆 2026-11-22）。
                         */
                        val waitedMs = nowMs - keyWaitSinceMs
                        val giveUp = skippedToKeyframe >= 600 || waitedMs >= 10_000L
                        if (!pkt.key && !giveUp) {
                            skippedToKeyframe++
                            if (skippedToKeyframe % 100 == 0) {
                                Log.w(TAG, "全包档等关键帧：已丢 $skippedToKeyframe 包（等了 ${waitedMs}ms）")
                            }
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            continue
                        }
                        awaitingKeyframe = false
                        keyWaitSinceMs = 0L
                        if (!pkt.key) {
                            Log.e(TAG, "全包档等关键帧超限（丢 $skippedToKeyframe 包，等 ${waitedMs}ms）→ 硬喂这一包")
                        } else {
                            Log.i(TAG, "全包档等到关键帧：pts=${pkt.ptsUs}us（丢弃 $skippedToKeyframe 包，等 ${waitedMs}ms）")
                        }
                    }
                    if (pkt == null) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        statSamples++
                        statBytes += pkt.size
                        buf.clear()
                        buf.put(pkt.data, 0, pkt.size)
                        val pts = if (pkt.ptsUs >= 0) pkt.ptsUs else 0L
                        /*
                         * 趁这帧还在手里，先解出杜比参数。
                         *
                         * 必须在 queueInputBuffer 之前读：入队之后缓冲归解码器所有，
                         * 内容随时可能被改写。**参数来自输入端**，所以"预热三帧全部归还、
                         * 不上屏"不影响颜色 —— 这是不留首帧的依据。
                         */
                        lastSeenInputPts = pts / 1_000_000.0
                        runCatching { harvestDovi(java.nio.ByteBuffer.wrap(pkt.data, 0, pkt.size), pkt.size) }
                        totalFed++
                        dumpPacketIfNeeded(pkt)
                        if (pktProbe < 20) {
                            pktProbe++
                            val head = pkt.data.copyOf(minOf(12, pkt.size)).joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "包取证#$pktProbe size=${pkt.size} pts=${pkt.ptsUs} key=${pkt.key} 头=$head")
                        }
                        codec.queueInputBuffer(inIdx, 0, pkt.size, pts, 0)
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
            if (outProbe < 30) {
                outProbe++
                /*
                 * 全量取证：这是判断"解码器到底有没有解出画面"的唯一硬证据。
                 * 标志位：1=KEY_FRAME、2=CODEC_CONFIG、4=END_OF_STREAM、8=PARTIAL_FRAME。
                 * 若每帧都是 size=8 且带 KEY_FRAME，说明它在回吐配置而不是画面（r1727 现象）。
                 */
                Log.i(
                    TAG,
                    "取输出#$outProbe 返回=$outIdx" +
                        (if (outIdx >= 0) {
                            " size=${info.size} 标志=${info.flags} 呈现=${info.presentationTimeUs}" +
                                " 表面=有"
                        } else "") +
                        " 已喂=$totalFed",
                )
            }
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
                    Log.i(TAG, "输出格式：$f")
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
        /* 解封装归读线程所有，这里不许关闭：关了读线程下一次取样本就抛异常（实测崩溃） */
        decoder = null
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
     *  ② 偏差超过 0.5 秒 → **在同一个解码器里跳过去**（解封装定位 + codec.flush），
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
                /* 与原生侧「显示帧对账」逐条比对：两边读到的片源时间应相差几十毫秒内
                 * （原生读 SurfaceTexture.getTimestamp，这里读出帧 pts）。 */
                if (decodedFrames % 60 == 0L) {
                    Log.i(
                        TAG,
                        "出帧对账|序号=$decodedFrames|pts=${"%.3f".format(ptsRaw)}|" +
                            "偏移=${"%.3f".format(appliedOffset)}",
                    )
                }
                /* 跳转后的第一个上屏帧：外面的"画面侧信号"（见 onSeekRendered） */
                if (seekAwaitFrame) {
                    seekAwaitFrame = false
                    val shown = lastShownPts
                    runCatching { onSeekRendered?.invoke(shown) }
                }
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
             * 画面首帧落地这一刻，把时钟拨到它的真实时间（父亲 2026-11-21 定的第二步）。
             *
             * 关键帧可能在目标左侧几百毫秒到几秒，时钟必须跟着它走，
             * 否则"目标时间"和"真实能动的位置"之间会一直差着一截，声音也没法对齐。
             */
            if (originFromPicture) {
                audioStartSec = ptsRaw
                /* 上报交给下面 originReported 那一处，这里不许再报一次（重复上报会起两个音频实例） */
            }
            if (!originReported) {
                originReported = true
                runCatching { onOriginSec?.invoke(ptsRaw) }
            }
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
                    /*
                     * 自管路上禁止画面单方面内跳（豆豆 2026-11-21 审出）。
                     *
                     * 内跳会把 originPts 清掉、下一帧重报原点，上层于是再起一个音频实例；
                     * 新音频一旦起不来（第三路被拒），就是"画面在新位置、声音在旧位置"。
                     * 要跳只能整条事务重建，两路一起动。
                     */
                    if (originFromPicture) {
                        Log.w(TAG, "画面落后 ${"%.2f".format(-gap)}s：自管路不内跳，保持原状等整条重建")
                    } else {
                        Log.w(TAG, "起播对齐：画面落后声音 ${"%.2f".format(-gap)}s → 画面跳到时钟处追")
                        pendingSeekSec = (clockAtFix - 0.15).coerceAtLeast(0.0)
                    }
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

        /*
         * 暂停时：握住这一帧原地等，**不上屏**。恢复后立刻接着放，
         * 画面不会趁机往前跑，恢复后也不需要再追同步。
         */
        if (pausedNow?.invoke() == true) {
            pausedHoldCount++
            while (running.get() && pausedNow?.invoke() == true) {
                try {
                    Thread.sleep(10)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!running.get()) return finish(false)
        }

        /*
         * 起播放行闸门：门关着就持这一帧等，门一开立刻上屏。
         *
         * **首帧不拦**（2026-11-21）：上层的放行现在要等"首帧真正上屏"这个信号，
         * 如果这里把首帧也拦住，信号永远等不到，就死锁了。所以首帧一律先上屏，闸门只拦后续帧。
         */
        if (firstFrameShown && holdGate?.invoke() == true) {
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
        val earlyUs = ((ptsRaw + appliedOffset + FRAME_DELAY_SEC) * 1_000_000.0).toLong() - clockUs
        val nowUs = android.os.SystemClock.elapsedRealtime() * 1000L
        val sinceLastRenderUs = if (lastShownAtMs > 0L) nowUs - lastShownAtMs * 1000L else Long.MAX_VALUE

        if (earlyUs < MIN_EARLY_US_VERY_LATE_THRESHOLD) {
            /*
             * 自管路上禁止内跳（豆豆 2026-11-21 第二遍审出：这一段上轮没改到）。
             *
             * 内跳会清原点 → 下一帧重报 → 上层再起一个音频实例；新音频一旦起不来，
             * 就是"画面在新位置、声音在旧位置"，而时钟单调只增会把偏差固化。
             * 自管路的失步只走整条重建（两路一起动）。
             */
            if (originFromPicture) {
                if (lateStreak == 0) lateSinceMs = android.os.SystemClock.elapsedRealtime()
                lateStreak++
                lateStreakMs = android.os.SystemClock.elapsedRealtime() - lateSinceMs
                if (lateStreakMs - lastLateLogMs >= 2000L) {
                    lastLateLogMs = lateStreakMs
                    Log.e(TAG, "连续严重迟到 ${lateStreakMs}ms（$lateStreak 帧）→ 时钟疑似领先，画面在快进")
                }
                return finish(true)
            }
            val target = (clockNowOrWall() - 0.15).coerceAtLeast(0.0)
            Log.w(TAG, "严重迟到 ${"%.2f".format(-earlyUs / 1e6)}s → 丢掉这一段，跳到 ${"%.2f".format(target)}s")
            pendingSeekSec = target
            return finish(false)
        }
        if (lateStreak > 0) {
            lateStreak = 0
            lateStreakMs = 0L
            lastLateLogMs = 0L
        }
        if (earlyUs < MIN_EARLY_US_LATE_THRESHOLD && sinceLastRenderUs > 100_000L) {
            forcedRender++
            return finish(true)
        }
        /*
         * 迟到但不严重：**不丢，直接上屏**。
         *
         * 这是 `.15` 原型验过的做法（`sim/p5decode_player.py`：382 帧、丢 0 帧）：
         * 落后就靠"这一帧立刻显示、下一帧马上接着来"把差距压回去，画面内容一帧不少。
         * 原来的判据是迟到 30 毫秒就丢 —— 解码抖动两三帧就把画面跳过一小段，
         * 观众看到的是"突然丢帧"，之后声音听起来就一直滞后（实测 2026-11-20）。
         * 只有迟到超过 0.5 秒（真追不回来，见上面的分支）才值得丢。
         */
        if (earlyUs < MIN_EARLY_US_LATE_THRESHOLD) {
            droppedStale++
            if (droppedStale % 50 == 1) {
                Log.i(TAG, "迟到 ${"%.0f".format(-earlyUs / 1000.0)}ms → 直接上屏追上（不丢帧，累计 $droppedStale 次）")
            }
            return finish(true)
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
        seekAwaitFrame = true
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
        val c = decoder ?: return false
        if (fullMode) {
            /*
             * 全包档跳转只有一个入口：Session。这里只清解码器，绝不写 feedSeekSec（没人消费它）、
             * 不重定 originPts/audioStartSec（会把上层诱导去再起一个音频实例）。
             */
            val s = session ?: return false
            runCatching { c.flush() }
            s.requestSeek((targetSec * 1000.0).toLong())
            Log.i(TAG, "全包档：跳转交给 Session → ${"%.2f".format(targetSec)}s（解码器已清）")
            return true
        }
        return try {
            /*
             * 解封装由读线程独占，所以这里只递话：读线程看到目标就定位并清队，
             * 解码线程只管把解码器 flush 掉。两个线程各碰各的对象，不会交叉。
             */
            feedSeekSec = targetSec
            feedQueue.flush()
            c.flush()
            /*
             * 重定基准：跳到新处后第一帧重新定 offset，预卷丢弃重新计。
             * **必须同时把 audioStartSec 换成新目标**：否则预卷判断仍拿老起点比，
             * 新位置解出来的每一帧都被当成"早于起点的预滚帧"丢掉 —— 现场表现是
             * 后退到片头后声音回来了、画面永远不来（2026-11-17 实测）。
             */
            audioStartSec = targetSec
            originPts = -1.0
            /* 让新位置的首帧重新上报一次，声音才能跟着换起点（否则热跳后声音不跟） */
            originReported = false
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
    /**
     * 画面相对时钟的显示偏移（秒）：正数 = 比时钟晚显示。
     *
     * 为什么取正：人耳对"声音略早"比对"声音略晚"宽容得多（行业容差是音频领先 45ms 到落后 125ms）。
     * 2026-11-21 实测这个偏移写成了负数（`pts - 0.05` 等于提前 50ms 上屏），叠加解码缓冲后
     * 画面净超前约 80ms，主观就是"声音一直滞后"。
     */
    /**
     * 内存预算（字节）= 秒数 × 码率 / 8，夹在 32MB ~ 256MB 之间。
     *
     * 上限 256MB 是给 PICO 8G 内存留的余量：长秒数档位配高码率片子算出来可能超过 1GB，
     * 照抄 mpv 的默认值不现实，必须给设备兜一个顶。
     */
    private fun budgetBytes(sec: Int, bitrate: Int): Int {
        /* 码率读不到就按 16Mbps 估（1080p 偏高、4K 偏低，都比"不缓冲"安全） */
        val bps = if (bitrate > 0) bitrate.toLong() else 16_000_000L
        val raw = sec.toLong() * bps / 8L
        /*
         * 上限 96MB：一次要的是连续内存，PICO 上更大很容易分配失败（实测 256MB 会失败）。
         * 按 4K 12MB/s 算，96MB 约 8 秒 —— 够挡住网盘抖动，又不至于把内存吃穿。
         */
        return raw.coerceIn(32L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    }

    private val FRAME_DELAY_SEC = 0.05
    private val MIN_EARLY_US_LATE_THRESHOLD = -30_000L
    private val MIN_EARLY_US_VERY_LATE_THRESHOLD = -500_000L

    /**
     * 暂停状态提供者：内核暂停时画面也要停。
     *
     * 2026-11-18 父亲实测：按暂停后声音停了、画面还在走，恢复后音画不同步一段时间。
     * 原因：暂停只作用于内核，画面这条链照常解码上屏（时钟被冻住，它只是每帧多等一会儿就照放）。
     */
    @Volatile private var pausedNow: (() -> Boolean)? = null

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
    /** 监视线程上一拍的样本数（判断流用） */
    @Volatile private var noProgressSince = System.currentTimeMillis()
            private var lastWatchSamples = 0L

    /** 需要"解码器内跳转"到的位置（秒，<0 表示没有待办）。由解码线程自己执行 */
    @Volatile private var pendingSeekSec = -1.0

    /** 丢掉迟到帧的累计数（观测量：它一直涨说明解码跟不上） */
    private var droppedStale = 0
    /**
     * 断流回调：画面这条链读不到数据时通知上层把声音也停住，数据恢复后再一起走。
     *
     * 父亲 2026-11-18 定：「如果画面卡住，声音也跟着暂停是最好的」——
     * 宁可短暂停顿，也不要声音独自跑掉、音画分家。
     */
    @Volatile private var onStall: ((Boolean) -> Unit)? = null
    @Volatile private var stalled = false

    /** 因暂停而原地握帧的次数（观测量） */
    private var pausedHoldCount = 0

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
            /*
             * 并行对账阶段（2026-11-21）：既推给原生、也照旧写盘，
             * 日志里比对"按帧取到的那套"与"文件里那套"是否同一套；确认一致后删掉写盘。
             */
            runCatching {
                /*
                 * 参数布局：版本 / 通道数 / 分段数 + 3×33 曲线 + 21 色块 = 122 个数，
                 * 渲染层 uniform 是 128 个 float，正好装得下。长度超了就不推（宁可偏一下也别越界）。
                 */
                if (params.size in 4..128) {
                    com.xxxx.emby_vr.vr.VrNative.pushDoviParams(
                        gen = pipeGen,
                        ptsNs = (lastSeenInputPts * 1_000_000_000.0).toLong(),
                        segments = params[2].toInt(),
                        channels = params[1].toInt(),
                        data = params,
                    )
                } else {
                    Log.e(TAG, "参数长度异常（${params.size}），跳过本次推送")
                }
            }
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

    /**
     * 全包档断流判定：队列里还剩几秒（队尾 pts 与当前播放位置换算，不用字节除码率）。
     *
     * 阈值按图 §3：低于 0.5 秒且连续 2.0 秒 → 判停；回到 1.5 秒以上 → 判恢复。
     * 滞后判据（2 秒）是为了不被一次正常出队打穿，恢复阈值（1.5 秒）留出缓冲回填的时间。
     */
    private fun queueWaterStalled(): Boolean {
        val tail = sourceQueue.lastPtsUs
        val posUs = (clockNowOrWall() * 1_000_000.0).toLong()
        val queuedSec = if (tail < 0L) 0.0 else (tail - posUs) / 1_000_000.0
        val now = android.os.SystemClock.elapsedRealtime()
        if (queuedSec < 0.5) {
            if (lowWaterSinceMs == 0L) lowWaterSinceMs = now
            if (!queueStalled && now - lowWaterSinceMs >= 2000L) {
                queueStalled = true
                Log.w(TAG, "队列水位 ${"%.2f".format(queuedSec)}s 连续 2 秒 → 判断流")
            }
        } else if (queuedSec >= 1.5) {
            lowWaterSinceMs = 0L
            queueStalled = false
        }
        return queueStalled
    }

    /**
     * 取证：前 60 个视频包转 Annex-B 落盘，拿去 .15 上让 `ffmpeg -f hevc` 试解。
     *
     * 从**第一个关键帧**开始计数：落点不在 IDR 时文件头没有 VPS/SPS/PPS，
     * 那样 dump 出来的东西解不出来，不是证据而是误导（豆豆 2026-11-22）。
     */
    private fun dumpPacketIfNeeded(pkt: SampleQueue.Packet) {
        val path = dumpPath ?: return
        if (dumpedPackets >= 60) return
        if (dumpStream == null) {
            if (dumpedPackets > 0) return          // 已落完并关掉，别重开覆盖
            if (!pkt.key) return                   // 等第一个关键帧再开始
        }
        try {
            val out = dumpStream ?: java.io.FileOutputStream(path).also {
                dumpStream = it
                Log.i(TAG, "取证落盘：$path（从关键帧起，最多 60 包）")
            }
            /*
             * 包体在 DemuxSession 里已经转成 Annex-B（起始码），这里原样写即可；
             * 再转一遍会写出一份垃圾文件（豆豆 2026-11-23 提醒）。
             */
            val d = pkt.data
            val size = pkt.size
            out.write(d, 0, size)
            dumpedPackets++
            dumpedBytes += size.toLong()
            if (dumpedPackets >= 60) {
                out.flush()
                out.close()
                dumpStream = null
                Log.i(TAG, "取证落盘完成：$path 包=$dumpedPackets 字节=$dumpedBytes")
            }
        } catch (t: Throwable) {
            /* 取证不许把解码线程带崩：磁盘满/权限失败都只记一行 */
            Log.w(TAG, "取证落盘失败：${t.javaClass.simpleName}: ${t.message}")
            runCatching { dumpStream?.close() }
            dumpStream = null
            dumpPath = null
        }
    }

    /**
     * 全包档：跳转由 Session 统一发起，这里只登记"要清解码器"。
     *
     * 不能在这里直接 flush：主线程调 flush 时解码线程可能正握着输出缓冲，
     * MediaCodec 规定持有 buffer 时不许 flush（轻则丢帧，重则把解码线程打死）。
     * 真正的 flush 由解码线程在两帧之间自己做（见 pump 顶部）。
     */
    fun flushForSessionSeek() {
        pendingSourceFlush = true
        Log.i(TAG, "全包档跳转：已登记清解码器，等解码线程在两帧之间执行")
    }

    /**
     * 全包档跳转后更新"预滚阈值"。
     *
     * `audioStartSec` 是"早于这个时刻的帧当作起播前的预滚、直接丢"的判据；
     * 跳转后不更新它，往回跳就会把所有新帧都当预滚丢掉（表现：声音走了、画面留在原地）。
     */
    fun setPrerollStart(sec: Double) {
        audioStartSec = sec
    }

    /** 由解码线程执行的清解码器（只在没握着输出缓冲时） */
    private fun applyPendingFlush() {
        if (!pendingSourceFlush) return
        if (heldIdx >= 0) return
        pendingSourceFlush = false
        awaitingKeyframe = true
        skippedToKeyframe = 0
        keyWaitSinceMs = 0L
        runCatching { decoder?.flush() }
        /*
         * 队列里还压着旧位置的数据包，一并清掉（2026-11-24 定案）：
         * 只清解码器不清队列，跳转后还会把旧位置的残缺包喂进去，
         * 表现是跳完解码计数不再前进、画面停在最后一帧。
         */
        runCatching { (session?.videoQueue ?: feedQueue).flush() }
        originPts = -1.0
        appliedOffset = Double.NaN
        firstFrameShown = false
        pumpFirstLogged = false
        lowWaterSinceMs = 0L
        queueStalled = false
        Log.i(TAG, "全包档跳转：解码器已清（解码线程内执行）")
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
        /*
         * 全包档的队列归 Session：这里不 abort 队列，但必须让 Session 中止阻塞读，
         * 否则解码线程会一直卡在 queue.get() 上、join 超时被判僵尸（豆豆 2026-11-22 P1-6）。
         */
        runCatching {
            dumpStream?.flush()
            dumpStream?.close()
            dumpStream = null
        }
        if (fullMode) {
            runCatching { session?.abort() }
        } else {
            feedQueue.abort()
        }
        /* 读线程才是**持有连接**的那一个：它没退出就等于连接没归还，必须进返回值 */
        val ft = feedThread
        /* 读线程可能卡在一次网络读里：给足 8 秒；仍不退就进致命态，不再尝试开新连接 */
        ft?.let { runCatching { it.join(8000) } }
        feedThread = null
        val feedDead = ft == null || !ft.isAlive
        holdGate = null
        onNeedRestart = null
        onAudioAlign = null
        pausedNow = null
        onStall = null
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
        /*
         * 两个线程都要确认退出，才算"资源已归还"。
         *
         * 只判解码线程是错的：真正持有网盘连接的是读线程。它超时仍在跑、
         * 这里却返回"已停"，上层就会立刻建新连接 —— 网盘只给两路，第三路被拒，
         * 现场表现就是"旧连接没关"的三种随机结果（2026-11-21 豆豆审出）。
         *
         * 释放权归线程自己：这里不代它们 release（那是拆正在用的实例）。
         */
        if (!feedDead) fatal = true
        if (t0?.isAlive == true || !feedDead) {
            Log.e(
                TAG,
                "ASSERT 旧管线未退出：解码线程=${t0?.isAlive == true} 读线程=${!feedDead}（禁止新建）",
            )
            return false
        }
        return true
    }
}
