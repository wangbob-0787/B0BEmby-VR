package com.xxxx.emby_vr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xxxx.emby_vr.data.EmbyContent
import com.xxxx.emby_vr.data.remote.EmbyApi
import com.xxxx.emby_vr.panel.PanelApp
import com.xxxx.emby_vr.panel.PanelLayer
import com.xxxx.emby_vr.vr.InputRouter
import com.xxxx.emby_vr.vr.VrRenderer
import com.xxxx.emby_vr.vr.VrSession
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import com.xxxx.emby_vr.panel.isSubMenu
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * B0BEmby VR 版 主 Activity。
 *
 * ## 架构（2026-10-04 父亲定稿）
 *
 * - **面板层**：电视版 B0BEmby 的整套界面（登录 / 首页 / 片库 / 搜索 / 详情 /
 *   账号 / 设置）渲染进虚拟显示器，再贴到虚拟屏；手柄光标换算成面板像素后
 *   同进程派发（见 [PanelLayer]）。虚拟屏占满整个视口。
 * - **播放屏**：VR 原生（视频帧 → OES 纹理 → 虚拟屏，状态条见 VrRenderer.setHudText）。
 * - 渲染路线：GLSurfaceView + OpenGL ES 3.0；当前走 PICO 的 2D 面板模式
 *   （不声明 pvr.app.type），系统把画面贴成空间面板；做真双目立体渲染时再接 OpenXR。
 */
class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private val vrSession = VrSession()

    /**
     * 面板层（2026-10-04 新增）：承载复用的电视版 Compose 界面。
     *
     * 渲染：VirtualDisplay → SurfaceTexture → OES 纹理 → 贴到虚拟屏。
     * 输入：光标坐标换算成面板像素后同进程派发（不走 INJECT_EVENTS）。
     */
    private lateinit var panel: PanelLayer

    /**
     * 控制条（OSD，2026-10-05）：架在观影者身前近场的一块小面板（1920×270）。
     *
     * 与主面板同一套 VirtualDisplay + SurfaceTexture 机制，只是尺寸与位置不同；
     * 播放中扣扳机开关它，按钮语义沿用电视版播放页（另加「选片」「退出」）。
     */
    private lateinit var osd: PanelLayer
    private val osdState = com.xxxx.emby_vr.panel.OsdState()
    private var osdVisible = false
    private var osdJob: kotlinx.coroutines.Job? = null

    /**
     * 三个网络视频站点的名字（父亲 2026-10-13）。
     *
     * 下标与 `ui/WebLibraryScreen.kt` 里的站点顺序一致（0 = 哔哩哔哩）。
     * 银幕在放网页时，控制条标题行用它显示「正在播放：哔哩哔哩」。
     */
    private val WEB_SITE_NAMES = arrayOf("哔哩哔哩", "腾讯视频", "爱奇艺", "YouTube")

    // ── 片头跳过（父亲 2026-10-12）──────────────────────────────────────────
    /**
     * 片头范围，毫秒（与 `ctl.positionMs()` 同一口径）。
     *
     * 数据来源：Emby 章节里的 **IntroStart / IntroEnd 标记**（IntroSkipHelper 认这两个
     * markerType）。剧集要服务端刮削出这两条标记才有；没有标记时整条功能静默不出现
     * （控制条上不会多出那颗按钮），不影响播放。
     * 起播时立（playMedia 取回播放信息那一刻），停止播放 / 换片时作废。
     */
    private var introStartMs: Long? = null
    private var introEndMs: Long? = null

    /** 自动跳过这一部只做一次；手动点过「跳过片头」也算做过，起播 / 换片时复位 */
    private var hasAutoSkippedIntro = false

    /** 片头守望协程：250ms 看一眼播放位置，播放停下来自己退 */
    private var introJob: kotlinx.coroutines.Job? = null

    /**
     * 界面设置（「自动跳过片头」这类开关存在这里）。
     * 与电视版同一个 SharedPreferences（emby_tv），以后在设置页加开关也能共用。
     */
    private val uiPrefs by lazy { com.xxxx.emby_vr.data.local.PreferencesManager(this) }

    /**
     * 播放接口统一层（父亲 2026-10-08）。
     *
     * 普通片源与杜比视界 Profile 5 的内核共用这一套「读状态 / 下指令」，
     * 控制条、银幕进度条、快进快退、拖进度条、暂停、倍速都只认它。
     * 用 lazy：声明位置与 player / mpvBackend 的初始化顺序无关。
     */
    private val ctl by lazy {
        com.xxxx.emby_vr.player.PlaybackControl().apply {
            exoProvider = { player }
            mpvProvider = { mpvBackend }
        }
    }

    /**
     * 展开菜单面板（2026-10-06 父亲定）。
     *
     * 控制条本身不变（仍是那块矮条）；菜单是**另一块面板**，架在控制条正上方、
     * 与控制条等宽，窗口背景透明，只有菜单卡片有底色。
     */
    private lateinit var menu: PanelLayer
    private val menuState = com.xxxx.emby_vr.panel.MenuState()

    /**
     * 弹幕层（2026-10-06 父亲：弹幕要接进来）。
     *
     * 贴在银幕前面、比银幕小一圈的一块透明面板，内容是自写的 DanmakuView
     * （解析 ASS 的 \move 逐帧画，Media3 自带的字幕渲染做不了滚动弹幕）。
     */
    /*
     * 原生把弹幕层 / 片名 logo 的纹理回调推上来时，这两块面板可能还没建好
     * （它们的创建排在控制条、菜单之后，而原生是在渲染线程一启动就推的）。
     * 回调先到的纹理存在这里，面板建好后立刻补挂 —— 否则这两块画面永远不绘制，
     * 表现就是「弹幕和 logo 一直看不见」（父亲 2026-10-06 晚，图层体检里帧数恒为 0）。
     */
    private var pendingDanmakuSt: android.graphics.SurfaceTexture? = null
    private var pendingLogoSt: android.graphics.SurfaceTexture? = null

    /** 弹幕画笔（直接往纹理上画，不走虚拟显示器） */
    private var danmakuPainter: com.xxxx.emby_vr.danmaku.DanmakuSurfacePainter? = null

    /**
     * 弹幕画布高度（父亲 2026-10-07）：宽度固定 2560，高度随影片比例。
     * 起播拿到真实比例后由 [applyDanmakuCanvas] 改写。
     */
    private var danmakuCanvasH = 1080

    /** 已应用的弹幕画布宽度（0=还没应用过）；与开关值不同就重建画布 */
    private var danmakuCanvasW = 0

    /** 上次用的画面比例：运行时改画布宽度时按它重算高度 */
    private var lastDanmakuAspect = 16f / 9f
    private var danmakuView: com.xxxx.emby_vr.danmaku.DanmakuView? = null

    /** 当前这一集解析好的弹幕（菜单里把弹幕关掉时先留着，开回来直接用） */
    private var danmakuTrack: com.xxxx.emby_vr.danmaku.DanmakuTrack? = null

    /** 片名 logo 层（银幕左上角那块小透明面板） */
    private lateinit var logo: PanelLayer
    private val logoUrl = androidx.compose.runtime.mutableStateOf<String?>(null)

    /*
     * 网页面（父亲 2026-10-13 定）：网页整块放到大银幕上。
     *
     * 网页在**自己的一块离屏画面**上渲染（1920×1080，不在世界里任何位置），
     * 银幕取样它 —— 所以海报墙还能照常浏览别的页面，两者互不干扰。
     * 它是按需建、按需销毁的：点站点图标建起来，点一部 Emby 片子就整个销毁
     * （父亲定：不留后台，免得两个声音一起响）。
     */
    private var web: PanelLayer? = null
    private var pendingWebSt: android.graphics.SurfaceTexture? = null

    /** 网页面当前加载哪个站（null = 网页面空着）；改它会触发重画与重新加载 */
    private val webSiteState = androidx.compose.runtime.mutableStateOf<Int?>(null)
    /*
     * 【2026-10-17 修「按 B 回到 B站 首页」】
     * 接管那一刻把"当时那一页的地址"记下来（扩展每轮扫描都会报 location.href）。
     * 回网页时按它载入 —— 原来只能载入站点首页，因为网页面在接管时被整个拆掉、
     * 回来时只能按站点编号重建（头显日志：`网页开始载入：about:blank` → `bilibili.com/`）。
     */
    private var webReturnUrl: String? = null
    private val webStartUrlState = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * 这一次播放的流是从哪个站点抓来的（父亲 2026-10-16）。
     * 退出播放时用它回到那个网页（B站 / 腾讯 / 爱奇艺）接着选片；从 Emby 播的片子置空。
     */
    private var webStreamSite: Int? = null

    /**
     * 【2026-10-17 23:26 父亲实测：B站 → Emby 片 → B站 之后，控制条还挂着 Emby 片的信息，
     * 连片名 logo 都还留在大银幕上，画面比例也还是 Emby 那部片的】
     *
     * 一句话：从自己的片子切到"网页流"时，上一部片子的遗留物没人清。
     * 现在起播处明确记下"此刻放的是网页流"，凡是 Emby 专属的刷新（标题、logo）都让位。
     */
    private var webPlayingNow = false

    /** 网页流当前画布尺寸（第一帧出来后按内核报的真实尺寸重设一次，2026-10-22） */
    private var webCanvasW = 0
    private var webCanvasH = 0
    /** 网页流标题的"基底"（剧名 第X集 [集名]），分辨率后面再拼上去（父亲 2026-10-19 20:58 定） */
    private var webTitleBase: String = "" 

    /**
     * **当前实际生效的银幕比例**（宽 / 高）。
     *
     * 父亲 2026-10-18 00:08 实测：「切换的时候，有时候比例正确，有时候不正确」。
     *
     * 病根是比例有好几个地方在写（换源清场写 16:9、网页流写抓来的宽高、
     * 内核报出真实尺寸、网页面铺上银幕时写 16:9），谁最后写谁生效；
     * 而「内核报的那份」原来靠自己记的一个标记去重 —— 只要别的路径先把它写成别的值，
     * 内核那一路就再也不补，于是时对时错。
     * 现在所有写入都走 applyScreenAspect() 并记在这里，内核每拍拿它比对、不一致就纠回来。
     */
    private var appliedAspect = 0f

    /** 银幕上现在是不是在放网页（原生侧银幕取样网页面纹理） */
    private var webOnScreen = false
    /*
     * 网页被"我们自己的播放器"顶掉时置 true（父亲 2026-10-16）：
     * 这时网页**没有销毁**，只是从大银幕上收起来了，地址也没动过。
     * 按返回键就该把它显示回来 —— 回到起播前那一页，而不是 B站 首页。
     */
    private var webHiddenByStream = false
    private var webSiteBeforeStream: Int? = null
    /** 网页流自带的字幕地址（B站 的 subtitle_url，JSON 格式；父亲 2026-10-16） */
    private var webSubtitleUrl: String = ""
    /** 网页片子自带的"片子编号"，弹幕按它拉（父亲 2026-10-16） */
    private var webCid: Long = 0L
    /** 腾讯的片号（如 j410292e7ng），拉弹幕要用（2026-10-20） */
    private var webVid: String = ""
    /** 这一部网页片子的弹幕是否已经挂上（挂上后别再被对白字幕顶掉） */
    private var webDanmakuLoaded = false

    /*
     * 片名 logo 的位图（父亲 2026-10-06 晚：logo 一直没画出来）。
     *
     * 原来走 Coil 加载，日志里只停在 Loading、永远不结束，也拿不到失败原因。
     * 改成自己下载：8 秒连接、15 秒读超时，成功/失败都打日志，图拿到就交给面板画。
     */
    private val logoBitmap = androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)

    /**
     * 换片 / 首播等待期的提示文字（父亲 2026-10-07：「即将播放：片名」）。
     * 画在弹幕层画布的中部偏下；第一帧到了就清空。
     */
    private val danmakuHint = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var logoLoadJob: kotlinx.coroutines.Job? = null

    /** 已成功下载的 logo 地址（同址复用，不再重复下载） */
    private var logoLoadedUrl: String? = null

    /** 本次起播是不是「重起播」（切字幕 / 音轨 / 质量 / 缓冲 / 换集）：是就别收控制条与菜单 */
    private var replaying = false

    /** 换轨重播时往回退多少毫秒（父亲 2026-10-06 晚定） */
    private val kReplayBackMs = 10_000L

    /**
     * 等「播放器报出内封文字轨」最多试几次（每次 1.2 秒，共约 24 秒）。
     *
     * 2026-10-10：换画质重播后播放器要重新缓冲，3 秒内报不出轨道；
     * 原来两次就放弃 → 退到"服务端挖字幕"那条在网盘片源上走不通的路 → 字幕不显示。
     */
    /**
     * 等「播放器报出内封文字轨」最多试几次（每次 1.2 秒）。
     *
     * 2026-10-10 定稿：**只有直连才指望播放器**（转码的流里它永远报不出来，见 lastPlayMethod
     * 分路）。所以这里不用等太久 —— 等不到就换"服务端转 SRT 自绘"那条路兜底，
     * 两条互相兜底，不给用户"字幕没了"的空窗。
     */
    private val kSubtitleTrackWaitAttempts = 6

    /**
     * 本次起播的播放方式（DirectPlay / DirectStream / Transcode）。
     *
     * 用途：决定字幕走哪条路 —— **转码的流里播放器报不出内封文字轨**（实测），
     * 那就别等它，直接让服务端转 SRT 自绘；直连才用"播放器就地解析"那条快路。
     */
    private var lastPlayMethod: String = ""

    // ── 光柱交互状态（2026-10-06 下午）──
    /** 最近一次控制条 / 菜单指针到达的时间：用来判断「光柱指着画面还是指着面板」 */
    private var lastOsdPointerAt = 0L
    private var lastMenuPointerAt = 0L

    /** 主线程定时器（位置刷新等） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 摇杆入口日志的限流时间戳（诊断用） */
    private var stickEntryLogAt = 0L
    /** 光柱在海报墙上的最近位置（滚动时要带上去） */
    private var lastPanelPx = 0f
    private var lastPanelPy = 0f

    /** 面板最近一次吃下点击 / 滚动的时间：这一下扳机归面板，不能再当播放暂停 */
    private var lastPanelClickAt = 0L

    /** 海报墙摆放的本地存档（下次打开 APP 还原） */
    private val placePrefs by lazy { getSharedPreferences("b0bemby_vr", MODE_PRIVATE) }

    /** 影厅亮度记忆键（父亲 2026-10-10：默认 0 = 全黑，之后记住上次选的；2026-10-12 默认改 1%） */
    private val PREF_CINEMA_BRIGHT = "cinema_bright"
    /** 亮度默认值一次性迁移标记（父亲 2026-10-12）——见 restorePlacePrefs 里的用法 */
    private val PREF_BRIGHT_RESET_V2 = "cinema_bright_reset_v2"
    /**
     * 第二轮迁移标记（父亲 2026-10-14：影厅亮度 3%、座椅高 0.11、前后 -0.32）。
     * 与 V2 同一个道理 —— 老机器上旧存档会把新默认值顶掉，升级后第一次启动强写一遍。
     */
    private val PREF_CINEMA_RESET_V3 = "cinema_reset_v3"
    /** 座椅调节记忆键（米） */
    private val PREF_SEAT_UP = "seat_up"
    private val PREF_SEAT_FWD = "seat_fwd"
    /** 拖动目标 */
    private val DRAG_BRIGHT = 0
    private val DRAG_SEAT_UP = 1
    private val DRAG_SEAT_FWD = 2

    /** 启动后的海报墙摆放是否已还原过（onResume 只做一次） */
    private var panelPlaceRestored = false

    // ── 播放上下文（切字幕 / 音轨 / 质量 / 选集都要用它重新起播）──
    private var currentMediaId = ""

    /**
     * 换片后等「新片第一帧」（父亲 2026-10-07）：
     * 这期间弹幕层、字幕、片名 logo 全部藏着，等画面出来那一刻一起亮 ——
     * 不要在新片开始播放前就先弹出弹幕 / 字幕 / logo。
     */
    private var waitingFirstFrame = false
    private var currentSeriesId: String? = null
    private var currentSeasonId: String? = null
    private var currentItem: com.xxxx.emby_vr.data.model.BaseItemDto? = null
    private var currentStreams: List<com.xxxx.emby_vr.data.model.MediaStreamDto> = emptyList()

    /**
     * 当前这部片的**全部版本**（Emby 的 MediaSources，一部片可能有多份文件）。
     * 字幕、音轨菜单只跟「选中的那份」走；「更多 → 播放版本」用它列可选版本。
     */
    private var currentSources: List<com.xxxx.emby_vr.data.model.MediaSourceInfoDto> = emptyList()
    private var audioStreamIndices: List<Int> = emptyList()
    private var subtitleStreamIndices: List<Int> = emptyList()
    private var episodeIds: List<String> = emptyList()

    /** 每集上次看到的位置（tick）：选集换片也从那儿接着播（父亲 2026-10-06 晚定） */
    private var episodePositions: List<Long> = emptyList()

    /** 当前该显示的字幕文字（画笔后启动时补上） */
    private var subtitleNow = ""

    /*
     * 播放位置的「基准 + 时刻」。
     *
     * 弹幕画布在**自己的线程**上逐帧绘制，而播放器不允许跨线程访问
     * （实测报 IllegalStateException: Player is accessed on the wrong thread，
     * 弹幕因此一帧都画不出来）。所以主线程只负责定期取一次位置，画笔线程
     * 用「基准 + 已过去的时间」自己推算出当前进度，既线程安全又足够平滑。
     */
    @Volatile
    private var posBaseMs = 0L

    @Volatile
    private var posBaseAtMs = 0L

    /*
     * 弹幕时钟（父亲 2026-10-13 凌晨：内核路径弹幕「一跳一跳」）。
     *
     * 病根：播放器每秒只报几次位置（内核那条路报得更粗），而弹幕每秒画 90 帧 ——
     * 每报一次就把时钟"拽"到播放器的值，画面上就是每 250ms 抖一下。
     *
     * 改法：时钟自己按墙钟往前走，播放器的值只用来**慢慢纠偏**（slew）：
     *   · **绝不后退**：往回拽是最扎眼的抖动，宁可原地等；
     *   · 落后时每拍最多追 [kDanmakuSlewMs]，差得离谱（换集 / seek）才直接对齐。
     * 结果：位置连续、单调，抖动被摊平成看不出来的微调。
     */
    @Volatile
    private var smoothPosMs = 0.0

    @Volatile
    private var smoothAtMs = 0L

    /** 位置刷新定时器（独立于控制条：控制条收起时也要继续跑） */
    private val posTicker = object : Runnable {
        override fun run() {
            refreshPosBase()
            updateDrawnSubtitle()
            /*
             * 弹幕画布宽度是运行时开关（2026-10-14 父亲定）：文件里改了立刻重建画布，
             * 不用重起播。比的是"已应用值"，没变就什么都不做。
             */
            if (com.xxxx.emby_vr.vr.VrTuning.danmakuCanvasW != danmakuCanvasW) {
                applyDanmakuCanvas(lastDanmakuAspect)
            }
            handler.postDelayed(this, 250L)
        }
    }

    private fun startPosTicker() {
        handler.removeCallbacks(posTicker)
        handler.post(posTicker)
    }

    private fun stopPosTicker() {
        handler.removeCallbacks(posTicker)
    }

    /**
     * 主线程调：采一次播放器位置，并**平滑纠偏**弹幕时钟（每 250ms 一次）。
     *
     * 纠偏规则见 [smoothPosMs] 的注释：对齐大跳变、慢慢追落后、绝不后退。
     */
    private fun refreshPosBase() {
        val now = android.os.SystemClock.elapsedRealtime()
        val target = currentPositionMs()
        if (smoothAtMs == 0L) {
            smoothPosMs = target.toDouble()
            smoothAtMs = now
            posBaseMs = target
            posBaseAtMs = now
            return
        }
        var est = smoothPosMs + (now - smoothAtMs)
        val err = target - est
        est += when {
            // 换集 / 拖动进度 / 起播：差得离谱就认播放器的值
            kotlin.math.abs(err) > kDanmakuRealignMs -> err
            // 落后：每拍最多追这么多，几拍内悄悄跟上
            err > 0.0 -> kotlin.math.min(err, kDanmakuSlewMs)
            // 超前（缓冲卡住等）：原地等播放器追上来，绝不往回跳
            else -> 0.0
        }
        smoothPosMs = est
        smoothAtMs = now
        // 旧字段留着给"没有平滑时钟"的兜底路径读，保持单调
        posBaseMs = est.toLong()
        posBaseAtMs = now
    }

    /** 任何线程可调：按平滑时钟推算当前播放位置（连续、单调，不抖） */
    private fun playbackPosEstimate(): Long {
        val at = smoothAtMs
        if (at == 0L) return 0L
        return (smoothPosMs + (android.os.SystemClock.elapsedRealtime() - at))
            .toLong()
            .coerceAtLeast(0L)
    }

    /**
     * 快进 / 快退期间把**弹幕位置**冻住（父亲 2026-10-07 实测：
     * 快进时弹幕一跳一跳 —— 弹幕位置是按播放进度算的，进度一跳它就跳）。
     * 冻的只是"喂给弹幕层的进度"，画面与真实进度不受影响；
     * 1.5 秒后自动解冻，缓冲结束（STATE_READY）会提前解冻。
     */
    /**
     * 弹幕位置的两个冻结条件（父亲 2026-10-07）：
     *  · **暂停**：一直冻到恢复播放（暂停时弹幕必须停在原地，不能自己往前跑）；
     *  · **seek**：快进快退/拖进度时冻 1.5 秒（缓冲结束会提前解冻）。
     * 两者互不干扰；都成立时同样返回冻结值。
     */
    private var danmakuFreezePosMs = 0L
    private var danmakuSeekFreezeUntilMs = 0L
    private var danmakuPaused = false

    /** 弹幕层取位置：冻结中返回冻结值，否则按播放进度估计 */
    private fun danmakuPosForPainter(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        val frozen = danmakuPaused || now < danmakuSeekFreezeUntilMs
        if (frozen) return danmakuFreezePosMs
        // 弹幕时间偏移：只挪弹幕看的位置，画面与真实进度不受影响（父亲 2026-10-09）
        return (playbackPosEstimate() + danmakuOffsetMs).coerceAtLeast(0L)
    }

    /** seek 开始：冻住弹幕位置 */
    private fun freezeDanmakuForSeek() {
        danmakuFreezePosMs = playbackPosEstimate()
        danmakuSeekFreezeUntilMs = android.os.SystemClock.elapsedRealtime() + 1500L
    }

    /** 播放/暂停：暂停时把弹幕冻在原地，恢复播放即解冻 */
    private fun setDanmakuPaused(paused: Boolean) {
        if (paused) danmakuFreezePosMs = playbackPosEstimate()
        danmakuPaused = paused
    }

    private var selectedAudioIndex: Int? = null
    /**
     * 客户端自绘字幕（父亲 2026-10-07）。
     *
     * 直连播放时服务端不参与字幕，选哪条也不会发过来 —— 所以用户选了某条字幕之后，
     * 我们按那条的编号把整条字幕取回来，自己解析、自己按时间画在弹幕层的字幕位。
     * 没选任何一条时这里是空的，交给播放器按语言偏好自己挑（onCues 那条路）。
     */
    private var subtitleCues: List<SubtitleCue> = emptyList()

    /**
     * 当前这条片子是否走自管解码（P5）。
     *
     * 自管路没有播放器替我们解析内嵌字幕，所以「内嵌文字轨」在它上面等于不可见；
     * 字幕路由必须因此改走服务端转 SRT 再自绘（2026-11-22）。
     */
    @Volatile
    private var selfPipeActive = false

    /**
     * 全包档（demux_mode=full）的解封装会话：一条连接同时出画面/声音/字幕。
     * 归这条播放周期所有；停播、换集、换代都要收掉（连接只有一条，泄漏就是 403）。
     */
    @Volatile private var selfSession: com.xxxx.emby_vr.player.DemuxSession? = null

    /** 全包档跳转的代次：落点回灌与 3 秒兜底谁先到用谁，后到的作废 */
    private var seekOriginSeq = 0

    /** 解封装直供的字幕（边播边到）：按开始时间排序，只保留最近的若干条 */
    private val demuxSubCues = java.util.TreeMap<Long, SubtitleCue>()

    /** 收场判据：两路都到结尾才结束（任一路先完不许动另一路，豆豆 2026-11-22） */
    @Volatile private var videoEos = false
    /** 拿不到时长时，画面要连报两次结尾才认（防假 EOS） */
    private var videoEosCount = 0
    @Volatile private var audioEos = false

    /** 两路都到结尾才收场；一路坏掉不完成时，10 秒后按完成收场（兜底，豆豆 2026-11-22） */
    private var eosWatchdogPosted = false

    private fun maybeFinishPlayback() {
        if (videoEos && audioEos) {
            Log.i(TAG, "自管解码：两路都到结尾 → 收场")
            selfPaused = true
            playClock?.pause()
            runCatching { audioPipe?.pause() }
            return
        }
        if (eosWatchdogPosted) return
        eosWatchdogPosted = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (videoEos != audioEos) {
                Log.w(TAG, "收场兜底：一路结束 10 秒后另一路仍未完成 → 按完成收场")
                videoEos = true
                audioEos = true
            }
            maybeFinishPlayback()
        }, 10_000L)
    }

    /** 当前要让解封装交付第几条文字字幕轨（-1 = 不要） */
    @Volatile
    private var demuxSubTrackWanted = -1

    /** 解封装来了一条字幕：并入自绘字幕表（endMs 来自流里的真实时长，缺失才兜底） */
    /** 字幕诊断日志节流 */
    private var subDiagAtMs = 0L
    private var subtitleNowMissing = 0

    /** 字幕包到达计数（只用于诊断日志节流） */
    private var subRecv = 0L

    private fun appendDemuxCue(startMs: Long, endMs: Long, text: String, track: Int = 0) {
        if (startMs < 0L || text.isBlank()) return
        val end = if (endMs > startMs) endMs else startMs + 3000L
        /* 键里混入轨号：不同轨会有同一时刻的条目，只按时间当键会互相覆盖 */
        demuxSubCues[startMs * 8L + (track and 7)] = SubtitleCue(startMs, end, text, emptyList(), track)
        if (demuxSubCues.size > 4000) runCatching { demuxSubCues.pollFirstEntry() }
        refreshDemuxSubtitleCues()
    }

    /** 只把"当前选中轨"的条目交给显示层（切轨后旧轨条目留在表里但不上屏） */
    private fun refreshDemuxSubtitleCues() {
        val want = demuxSubTrackWanted
        subtitleCues = demuxSubCues.values.filter { it.track == want }.sortedBy { it.startMs }
    }

    /** Emby 字幕流序号 → 文件内第几条「文字」字幕（解封装按这个序号交付） */
    private fun demuxSubtitleOrdinal(index: Int): Int {
        /*
         * 只数**容器内的文字字幕**：外挂字幕（IsExternal）不在文件里，
         * 计数时算进来会让全包档的轨序号整体错位、选到别的内封轨（豆豆 2026-11-23）。
         */
        val textSubs = currentStreams.filter {
            it.type.equals("Subtitle", true) && !isDanmakuStream(it) && !isImageSubtitle(it) &&
                it.isExternal != true
        }
        return textSubs.indexOfFirst { it.index == index }
    }
    private var subtitleCueStream: Int? = null
    /**
     * 起播崩过一次就改用 H.264 重来（父亲 2026-10-07）。
     *
     * 《律界战争》这类片：服务端视频原样 copy 成 HEVC 装进 TS，Media3 的 H265Reader
     * 会在 SampleQueue.commitSample 抛 IllegalArgumentException，整条流报 Source error。
     * 与其为它把全局封装换掉（会把别的片的流畅度拖下水），不如只在崩过之后对
     * **这一部片**降级要 H.264。换片时自动复位。
     */
    private var forceH264 = false
    private var selectedSubtitleIndex: Int? = null

    /** 上一次起播用的原始地址（判断是否换了内容，决定要不要清空中转缓存） */
    private var lastPipeUrlForCache: String = ""
    private var qualityIndex = 0
    private var bufferPresetIndex = 0
    private var playModeIndex = 0
    private var danmakuOn = true
    private var danmakuScale = 1f

    /**
     * 弹幕时间偏移（毫秒，正 = 提前）（父亲 2026-10-09）。
     *
     * 用途：片源和弹幕源对不齐（弹幕快半拍/慢半拍）时手动修。
     * 只影响喂给弹幕层的播放位置，不动画面与真实进度。范围 ±10 秒，落盘记住。
     */
    /** 弹幕时间偏移上限：±2 分半（父亲 2026-10-09 定） */
    private val kDanmakuOffsetMaxMs = 150_000

    private var danmakuOffsetMs =
        runCatching { placePrefs.getInt("danmaku_offset_ms", 0) }
            .getOrDefault(0)
            .coerceIn(-kDanmakuOffsetMaxMs, kDanmakuOffsetMaxMs)


    /** 正在挑片（控制条上的「选片」打开的海报墙）：此时画面回到面板、控制条留着 */
    private var picking = false

    /** 光柱当前是否落在海报墙上（原生回推，播放期间决定面板接不接输入） */
    private var panelPointerOnPanel = false

    /** 当前倍速（控制条倍速按钮循环切换） */
    private var playSpeed = 1f

    /**
     * VR 模式下的输入源是否已经活了（2026-10-05）。
     *
     * VR 模式里没有系统合成的触摸流，光柱坐标/扳机/摇杆由原生层直接推上来。
     * 一旦收到第一条，就把老的触摸通道关掉，避免两套坐标同时喂面板
     * （否则又回到「光柱指这里、点到的却是旁边」）。
     */
    private var vrInputLive = false


    /** 光柱输入 → 面板（坐标已是面板像素，与 PanelLayer 的 1920×1080 同一套） */
    private val vrInput = object : com.xxxx.emby_vr.vr.VrNative.InputSink {
        override fun onPointer(px: Float, py: Float) {
            if (!vrInputLive) {
                vrInputLive = true
                Log.i(TAG, "VR 光柱输入已接管（老的触摸通道关闭）")
            }
            lastPanelPx = px
            lastPanelPy = py
            runOnUiThread { if (panelInputReady()) panel.vrPointer(px, py) }
        }

        override fun onPanelBackMask(mask: Int) {
            /*
             * 原生只在变化时推：哪些面板"人在背面"（父亲 2026-10-12）。
             * 置位的那层 → 内容左右镜像 + 盖半透明蒙层（见 PanelLayer.backView）。
             */
            runOnUiThread {
                if (::panel.isInitialized) panel.backView = (mask and 1) != 0
                if (::osd.isInitialized) osd.backView = (mask and 2) != 0
                if (::menu.isInitialized) menu.backView = (mask and 4) != 0
            }
        }

        override fun onPanelFocus(onPanel: Boolean) {
            // 原生只在变化时推：光柱是否落在海报墙上
            runOnUiThread {
                panelPointerOnPanel = onPanel
                /*
                 * 光柱离开面板 / 面板收起 → 把「扫到谁」清掉（父亲 2026-10-10）。
                 * 不清的话会留着一个已经不存在的控件钥匙，或者光柱早就不在墙上了、
                 * 某张海报还亮着。
                 */
                if (!onPanel) {
                    com.xxxx.emby_vr.panel.VrHover.clear()
                    com.xxxx.emby_vr.panel.VrPointer.clear()
                }
            }
        }

        /**
         * 新片画面**真的到了纹理层**（原生侧的判据）。
         */
        override fun onVideoFrameReady() {
            /*
             * 只留一条日志：收黑幕的时机已改由"播放器首帧 + 150ms"决定（见 onRenderedFirstFrame）。
             * 原生这个回调只会在**首次**取到帧时发一次，不能作为每次换片的判据
             * （父亲 2026-10-07：声音都出来了黑幕还挂着）。
             */
            if (playerFrameSeen) Log.i(TAG, "原生已取到这一部的帧（黑幕按播放器首帧收）")
            /*
             * 内核（Profile 5）这条路走**同一条收幕通道**（父亲 2026-10-09：
             * 「切到 Profile 5 片、Profile 5 之间、选集之间的切换逻辑与文案，
             *   要和非 Profile 5 之间切片做成一样」）。
             *
             * 也就是说：内核侧这个「画面已到纹理层」的回调当作播放器首帧用，
             * 立 playerFrameSeen、再走 tryRevealWaitingFrame（+150ms 纹理余量），
             * 与非 Profile 5 那条一模一样，不再另开一套。
             * 另外压一个最短停留：黑幕/转圈/「即将播放：片名」至少亮 700ms，
             * 否则内核起得快时会一闪而过，观感和普通片切换不一致。
             */
            /*
             * 内核这条路**不在这里收幕**（父亲 2026-10-09 实测：普通片切 Profile 5、
             * Profile 5 之间互切，都"没有先黑屏"）。
             *
             * 原因：换片瞬间这块纹理里还留着**上一部的旧帧**，这个回调照样会响 ——
             * 拿它当"新片出画了"，黑幕就被提前收掉，露出旧画面。
             * 现在内核出画改由 mpvSubtitleJob 轮询 `video-params/w` 判定
             * （第一个视频帧解出来才有值），另有 clearForNewMedia 的 8 秒兜底。
             */
            if (mpvBackend == null) Log.i(TAG, "原生首帧回调（非内核路径，按播放器首帧收幕）")
        }

        override fun onClick(px: Float, py: Float) {
            vrInputLive = true
            lastPanelClickAt = android.os.SystemClock.uptimeMillis()
            runOnUiThread {
                /*
                 * 这一路只服务海报墙（父亲 2026-10-13 修）：大银幕上的网页走
                 * onWebClick 那条独立通道 —— 两套坐标空间都是 1920×1080，
                 * 混在一起时点海报墙会被当成"点在网页上"吃掉，海报墙就点不动了。
                 */
                if (panelInputReady()) {
                    Log.i(TAG, "光柱点击 (${px.toInt()}, ${py.toInt()}) → 交给面板")
                    panel.vrClick(px, py)
                } else if (!renderer.videoActive) {
                    Log.w(TAG, "光柱点击被丢弃：面板未就绪（已激活=${com.xxxx.emby_vr.vr.VrNative.panelActive}）")
                }
            }
        }

        /** 大银幕上的网页：点击 / 滚动（坐标由原生按银幕落点换算好） */
        override fun onWebClick(px: Float, py: Float) {
            vrInputLive = true
            runOnUiThread {
                Log.i(TAG, "光柱点击 (${px.toInt()}, ${py.toInt()}) → 交给网页")
                com.xxxx.emby_vr.web.WebPageHub.tap(px, py)
            }
        }

        /** 大银幕上的网页：光柱移动 = 鼠标悬停（父亲 2026-10-13：手柄当鼠标） */
        override fun onWebHover(px: Float, py: Float) {
            vrInputLive = true
            runOnUiThread { com.xxxx.emby_vr.web.WebPageHub.hover(px, py) }
        }

        override fun onWebStick(px: Float, py: Float, sx: Float, sy: Float) {
            vrInputLive = true
            /*
             * 原生按"银幕上是不是网页"决定摇杆送哪条通道
             * （openxr_renderer.cpp:3562-3582，判据是 screenShowsWeb）。
             *
             * 父亲 2026-10-16 实测：接管播放之后，光点对着银幕推摇杆不能快进快退
             * （Emby 片子可以）。网页那一侧此时已经收掉，这一下却仍被送到网页通道，
             * 而通道那头除了滚网页没别的事可做 —— 事件就等于丢了。
             * 修法：网页不在就把它当普通摇杆用（光点在银幕上 = 快进快退）。
             */
            if (!webOnScreen && webSiteState.value == null) {
                Log.i(TAG, "网页摇杆通道收到，但网页不在 → 转普通摇杆（光点在银幕上=快进快退）")
                onStick(px, py, sx, sy)
                return
            }
            runOnUiThread { com.xxxx.emby_vr.web.WebPageHub.scroll(px, py, sx, sy) }
        }

        override fun onStick(px: Float, py: Float, sx: Float, sy: Float) {
            vrInputLive = true
            /*
             * 诊断（父亲 2026-10-06 晚）：选集菜单开着时推摇杆，日志里一条都没有，
             * 而字幕菜单开着时有 —— 需要区分是"摇杆根本没送到"还是"送到了但判断没进"。
             * 限流打印，避免刷屏。
             */
            val stickNow = android.os.SystemClock.uptimeMillis()
            // 菜单开着时全部打印（这是要查的场景），其余限流 150ms 免得刷屏
            val menuOpen = menuState.kind != null
            if (menuOpen || stickNow - stickEntryLogAt > 150L) {
                stickEntryLogAt = stickNow
                Log.i(TAG, "收到摇杆 px=$px py=$py sx=$sx sy=$sy 菜单=${menuState.kind}")
            }
            runOnUiThread {
                /*
                 * 这一路只服务海报墙/播放画面（父亲 2026-10-13 修）：
                 * 大银幕上的网页走 onWebStick 那条独立通道，否则菜单开着时
                 * 摇杆会被网页抢去滚，菜单自己滚不动。
                 */
                /*
                 * 光柱指着**画面**时，摇杆左右 = 快退 / 快进（父亲 2026-10-06）。
                 *
                 * 「指着画面」的判断：原生只在光柱真的落在控制条 / 菜单上时才推指针，
                 * 所以 400ms 内没有面板指针 = 光柱在画面这一侧。
                 */
                val nowMs = android.os.SystemClock.uptimeMillis()
                /*
                 * 「光柱在面板这一侧」的两种来源：
                 *  · 原生刚推过控制条 / 菜单的指针（400ms 时间窗）；
                 *  · 原生报「光柱落在海报墙上」。
                 * 父亲 2026-10-07：播放中光柱明明指着海报墙，摇杆却被当成快进快退，
                 * 海报墙滚不动 —— 原因是这里只算了控制条和菜单，漏了海报墙。
                 */
                val onPanelUi = panelPointerOnPanel ||
                    nowMs - maxOf(lastOsdPointerAt, lastMenuPointerAt) < 400L
                /*
                 * 菜单开着、光柱在菜单上：摇杆滚列表（父亲 2026-10-06 晚：
                 * 选集和演职人员滚不动）。取主方向 —— 竖直列表用上下推，
                 * 演职人员那一排是横向的，左右推也认。
                 */
                /*
                 * 影厅亮度面板（父亲 2026-10-10 19:05「影厅亮度摇杆左右操作不动」）：
                 * 这一页只有一条亮度条，摇杆左右就该推它 —— 别再往列表滚动通道送。
                 * 每次事件走 6%，推一下就到头也不至于失控。
                 */
                /*
                 * 座椅调节页（父亲 2026-10-10 22:56）：上下推 = 座椅高低、左右推 = 座椅前后。
                 * 两条都只有滑条没有列表，所以不往菜单滚动通道送。
                 */
                if (menuState.kind == com.xxxx.emby_vr.panel.MenuKind.SEAT_ADJUST) {
                    if (kotlin.math.abs(sy) > 0.15f) {
                        val v = (menuState.seatUp + sy * 0.05f)
                            .coerceIn(-com.xxxx.emby_vr.panel.SEAT_UP_RANGE, com.xxxx.emby_vr.panel.SEAT_UP_RANGE)
                        menuState.seatUp = v
                        com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_UP, v)
                        placePrefs.edit().putFloat(PREF_SEAT_UP, v).apply()
                    }
                    if (kotlin.math.abs(sx) > 0.15f) {
                        val v = (menuState.seatFwd + sx * 0.05f)
                            .coerceIn(-com.xxxx.emby_vr.panel.SEAT_FWD_RANGE, com.xxxx.emby_vr.panel.SEAT_FWD_RANGE)
                        menuState.seatFwd = v
                        com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_FWD, v)
                        placePrefs.edit().putFloat(PREF_SEAT_FWD, v).apply()
                    }
                    Log.i(TAG, "摇杆调座椅：sx=$sx sy=$sy → 高 ${"%.2f".format(menuState.seatUp)} 前后 ${"%.2f".format(menuState.seatFwd)}")
                    return@runOnUiThread
                }
                if (menuState.kind == com.xxxx.emby_vr.panel.MenuKind.CINEMA_LIGHT) {
                    if (kotlin.math.abs(sx) > 0.15f) {
                        val v = (menuState.cinemaBright + sx * 0.06f).coerceIn(0f, 1f)
                        menuState.cinemaBright = v
                        com.xxxx.emby_vr.vr.VrNative.setTuning(
                            com.xxxx.emby_vr.vr.VrTuning.KEY_CINEMA_BRIGHT, v,
                        )
                        placePrefs.edit().putFloat(PREF_CINEMA_BRIGHT, v).apply()
                        Log.i(TAG, "摇杆调影厅亮度：sx=$sx → ${(v * 100).toInt()}%")
                    }
                    return@runOnUiThread
                }
                if (menuState.kind != null && ::menu.isInitialized) {
                    /*
                     * 菜单开着：摇杆滚菜单。
                     *
                     * 2026-10-06 晚通读代码后改对了通道：菜单和海报墙一样是 PanelLayer，
                     * 它的滚动通道是 vrStick() → 往窗口里派发滚轮事件。菜单内容是
                     * Compose 的 LazyColumn，只有真的收到滚轮事件才会动 ——
                     * 之前在界面层自己攒偏移量那套（requestScroll）根本没接到列表上，
                     * 所以日志里"滚了"和"没滚"都看不出来。这次直接走面板的通道。
                     */
                    Log.i(TAG, "摇杆滚菜单：kind=${menuState.kind} sx=$sx sy=$sy → menu.vrStick")
                    /*
                     * 落点收进菜单自己的矩形（父亲 2026-10-12）。
                     *
                     * 滚轮是按坐标派发的，光柱指在海报墙上时落点落在菜单外面 ——
                     * 父亲实测：「海报墙和菜单选项会一起滚」，而且指在卡片中间时
                     * 演职人员那一排根本滚不动（只有指到最右边才行）。
                     * 演职人员面板收进那一排的矩形，其余菜单收进卡片矩形。
                     */
                    menu.scrollClampRect =
                        if (menuState.kind == com.xxxx.emby_vr.panel.MenuKind.CAST) {
                            menuState.castRowRect ?: menuState.cardRect
                        } else {
                            menuState.cardRect
                        }
                    menu.vrStick(px, py, sx, sy)
                    return@runOnUiThread
                }
                if (renderer.videoActive && !onPanelUi) {
                    stickSeek(sx)
                    return@runOnUiThread
                }
                if (panelInputReady()) {
                    panel.vrStick(px, py, sx, sy)
                } else if (!renderer.videoActive && (kotlin.math.abs(sx) > 0.2f || kotlin.math.abs(sy) > 0.2f)) {
                    Log.w(TAG, "光柱摇杆被丢弃：面板未就绪（已激活=${com.xxxx.emby_vr.vr.VrNative.panelActive}）")
                }
            }
        }


        override fun onOsdPointer(px: Float, py: Float, pressed: Boolean) {
            vrInputLive = true
            lastOsdPointerAt = android.os.SystemClock.uptimeMillis()
            // 归一化横向 + 纵向位置：界面拿它判断光柱停在哪儿（按钮悬停高亮）
            osdState.pointerNx = if (px < 0f) {
                -1f                                             // 光柱不在控制条上：清掉悬停高亮
            } else {
                (px / com.xxxx.emby_vr.panel.OSD_PANEL_W).coerceIn(0f, 1f)
            }
            osdState.pointerNy = if (py < 0f) {
                -1f
            } else {
                (py / com.xxxx.emby_vr.panel.OSD_PANEL_H).coerceIn(0f, 1f)
            }
            runOnUiThread {
                /*
                 * 丢弃时也要留痕（父亲 2026-10-11：内核模式控制条点不动，日志里
                 * 只有原生的"控制条扳机按下"，界面这一侧一条都没有 —— 查不下去）。
                 */
                if (osdReady()) {
                    osd.vrPointerPressed(px, py, pressed)
                } else {
                    Log.w(
                        TAG,
                        "控制条按压被丢弃：就绪=${::osd.isInitialized && osd.osdReady} " +
                            "可见=$osdVisible 按下=$pressed (${px.toInt()}, ${py.toInt()})",
                    )
                }
            }
        }

        override fun onOsdClick(px: Float, py: Float) {
            /*
             * 扣扳机不再走这里：控制条改成「按下 / 拖动 / 抬起」三态（见 onOsdPointer），
             * 这里再点一次会变成点两下。只留日志。
             */
            vrInputLive = true
            lastOsdPointerAt = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "控制条扳机按下 (${px.toInt()}, ${py.toInt()})")
        }

        /**
         * 菜单指针：按下 / 拖动 / 抬起三态（父亲 2026-10-10 调影厅亮度条）。
         *
         * 「影厅亮度条」是菜单里唯一要拖的控件：按下点在条上就进入拖动，横坐标换算成
         * 0~1 的亮度值；按下没怎么动就抬起，仍旧当成一次普通点击（原本的行点击不能丢）。
         */
        private var menuPressX = 0f
        private var menuPressY = 0f
        private var menuDraggingBar = false
        /** 正在拖哪条：0 亮度 · 1 座椅高低 · 2 座椅前后 */
        private var menuDragTarget = 0
        /** 上一帧扳机是否按着：只有"按下 → 抬起"的跳变才算一次点击 */
        private var menuWasPressed = false

        override fun onMenuPointer(px: Float, py: Float, pressed: Boolean) {
            vrInputLive = true
            lastMenuPointerAt = android.os.SystemClock.uptimeMillis()
            val bar = menuState.brightBar
            val upBar = menuState.seatUpBar
            val fwdBar = menuState.seatFwdBar
            if (pressed) {
                if (!menuDraggingBar) {
                    /* 每次按下都记下起点：抬起时用它判断"这是点击还是拖动" */
                    menuPressX = px
                    menuPressY = py
                }
                val at = androidx.compose.ui.geometry.Offset(px, py)
                if (!menuDraggingBar && bar != null && bar.contains(at)) {
                    /* 按在亮度条上 = 开始拖，不算点击 */
                    menuDraggingBar = true
                    menuDragTarget = DRAG_BRIGHT
                    applyCinemaBright(bar, px)
                } else if (!menuDraggingBar && upBar != null && upBar.contains(at)) {
                    menuDraggingBar = true
                    menuDragTarget = DRAG_SEAT_UP
                    applySeatAdjust(DRAG_SEAT_UP, upBar, px)
                } else if (!menuDraggingBar && fwdBar != null && fwdBar.contains(at)) {
                    menuDraggingBar = true
                    menuDragTarget = DRAG_SEAT_FWD
                    applySeatAdjust(DRAG_SEAT_FWD, fwdBar, px)
                } else if (menuDraggingBar) {
                    // 拖出条外也继续跟随，手感更顺
                    when (menuDragTarget) {
                        DRAG_SEAT_UP -> applySeatAdjust(DRAG_SEAT_UP, upBar, px)
                        DRAG_SEAT_FWD -> applySeatAdjust(DRAG_SEAT_FWD, fwdBar, px)
                        else -> applyCinemaBright(bar, px)
                    }
                } else if (!menuWasPressed) {
                    /*
                     * 按下即点（跟改造前一模一样：原来原生是在扳机按下的那一刻发点击）。
                     * 这样手抖不影响命中 —— 改成"抬起才算"的话，按住时手一晃超过阈值，
                     * 这一下点击就丢了。
                     */
                    runOnUiThread { if (menuReady()) menu.vrClick(px, py) }
                }
            } else {
                /* 松手：结束拖动。点击已经在按下那一刻发过了（不在这里补发，否则会连点） */
                menuDraggingBar = false
            }
            menuWasPressed = pressed
            runOnUiThread { if (menuReady()) menu.vrPointer(px, py) }
        }

        /** 座椅调节两条滑条：位置 → 米（范围与 PlayerMenuPanel 的常量一致） */
        private fun applySeatAdjust(target: Int, bar: androidx.compose.ui.geometry.Rect?, px: Float) {
            val r = bar ?: return
            val f = ((px - r.left) / r.width).coerceIn(0f, 1f)
            if (target == DRAG_SEAT_UP) {
                val v = (f * 2f - 1f) * com.xxxx.emby_vr.panel.SEAT_UP_RANGE
                menuState.seatUp = v
                com.xxxx.emby_vr.vr.VrNative.setTuning(
                    com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_UP, v,
                )
                placePrefs.edit().putFloat(PREF_SEAT_UP, v).apply()
            } else {
                val v = (f * 2f - 1f) * com.xxxx.emby_vr.panel.SEAT_FWD_RANGE
                menuState.seatFwd = v
                com.xxxx.emby_vr.vr.VrNative.setTuning(
                    com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_FWD, v,
                )
                placePrefs.edit().putFloat(PREF_SEAT_FWD, v).apply()
            }
        }

        /** 亮度条位置 → 0~1 的影厅环境亮度（原生的 key 24 负责换算环境光与底光） */
        private fun applyCinemaBright(bar: androidx.compose.ui.geometry.Rect?, px: Float) {
            val r = bar ?: return
            val v = ((px - r.left) / r.width).coerceIn(0f, 1f)
            if (kotlin.math.abs(v - menuState.cinemaBright) < 0.01f) return
            menuState.cinemaBright = v
            com.xxxx.emby_vr.vr.VrNative.setTuning(
                com.xxxx.emby_vr.vr.VrTuning.KEY_CINEMA_BRIGHT, v,
            )
        }

        override fun onMenuClick(px: Float, py: Float) {
            vrInputLive = true
            runOnUiThread { Log.i(TAG, "菜单点击 (${px.toInt()}, ${py.toInt()})") }
        }

        override fun onToggleOsd() {
            vrInputLive = true
            runOnUiThread { setOsdVisible(!osdVisible) }
        }

        /**
         * 扳机松开 → 停掉连发（父亲 2026-10-09）。
         *
         * 弹幕时间偏移那几行是"点一下走一档"，按住不放要一直走、越走越快；
         * 这个回调就是那个"松开"的信号。
         */
        override fun onTriggerState(pressed: Boolean) {
            if (!pressed) runOnUiThread { stopOffsetRepeat() }
        }

        override fun onBack(onScreen: Boolean) {
            vrInputLive = true
            runOnUiThread {
                /*
                 * 【2026-10-17 00:12 观测：按 B 那一刻的现场】
                 * 父亲报"光标对着大银幕按 B 还是回 B站 首页"。这一行把决定分支的四个状态
                 * 一次打全 —— 之后无论走哪一支，都能对上是从哪个状态落下去的，不用再猜。
                 */
                Log.i(
                    TAG,
                    "光柱 B 键现场：网页在屏=$webOnScreen 被播放器顶掉=$webHiddenByStream " +
                        "光标在面板=$panelPointerOnPanel 光柱在银幕=$onScreen " +
                        "网页页=${webSiteState.value} 记下的站=${webStreamSite}",
                )
                /*
                 * 父亲 2026-10-07 重定 B 键语义：
                 *  · 菜单开着 → 关菜单（回上一级）；
                 *  · 光点在播放画面上 → **不响应**。播放页的退出、快进快退、选集
                 *    都在控制条上，B 键不必再插一脚"停止播放"；
                 *  · 光点在海报墙上 → 面板返回上一级。
                 */
                if (menuState.kind != null) {
                    Log.i(TAG, "光柱 B 键 → 菜单返回上一级")
                    menuBack()
                } else if (panelPointerOnPanel) {
                    /*
                     * 【2026-10-16 父亲定的总语义：B 键必须"光标指着谁才管谁"】
                     *
                     * 光标在海报墙上 / 面板上 → B 只归面板（返回上一级），
                     * 绝不能顺手把大银幕上的播放顶回网页 —— 原来就是因为在哪儿按都一样，
                     * 随手一按大银幕就回网页了（父亲实测反馈）。
                     */
                    if (panelInputReady()) {
                        Log.i(TAG, "光柱 B 键 → 面板返回（光标在面板上）")
                        panel.back()
                    } else {
                        Log.i(TAG, "光柱 B 键忽略：光标在面板区但面板没就绪")
                    }
                } else if (webOnScreen) {
                    /*
                     * 父亲 2026-10-13 实测：光柱指着大银幕按 B，海报墙反而退回上一屏。
                     * 手柄 B 键走的是这条路（不是 onKeyDown），这里原来只挡了"播放画面"，
                     * 没挡"银幕上是网页" —— 补上，语义与 onKeyDown 那条一致。
                     */
                    /*
                     * 2026-10-14：再往前一步 —— 光柱指着网页时，B 键先交给网页自己
                     * （退回网页的上一页）。WebPageHub.back() 早就定义好了却**没有任何地方
                     * 调用它**，网页内的返回因此从来没生效过；已经在首页就保持原样，
                     * 不退出网页页（语义按 2026-10-13 定的）。
                     */
                    val wentBack = com.xxxx.emby_vr.web.WebPageHub.back()
                    Log.i(
                        TAG,
                        if (wentBack) "光柱 B 键 → 网页退回上一页" else "光柱 B 键忽略：网页已无上一页",
                    )
                } else if (webHiddenByStream) {
                    /*
                     * 【2026-10-16 头显实测定案：这就是"按 B 回首页"的真凶】
                     *
                     * 光柱（射线）这条 B 键路径下面原来分支是「按记下的站点重新 openWeb」——
                     * 而 openWeb 只能打开**站点首页**，于是从 B站 电影/剧集接管过来之后
                     * 一按 B 就落回 B站 首页，而不是刚才那一页（父亲两次实测反馈）。
                     *
                     * 网页其实一直活着（接管只是把它从大银幕上收起来，没有销毁、地址没动），
                     * 所以这里跟系统返回键走同一条路：停下播放器、把大银幕交回网页。
                     * 这一支必须排在下面 openWeb 那一支**之前**，否则永远轮不到。
                     *
                     * 【2026-10-17 19:26 父亲立规：只有光柱指着大银幕那一按才回网页】
                     * 原来这里不看光柱落在哪 —— 对着天上、地板按 B 也把银幕顶回网页
                     * （日志现场：`被播放器顶掉=true 光标在面板=false`，光柱其实不在银幕上）。
                     * 现在这一支加一道闸：`onScreen` 为假就什么都不做。
                     * 语义与 2026-10-16 定的总规矩一致 —— B 键"光标指着谁才管谁"。
                     */
                    /*
                     * 【2026-10-17 22:53 父亲实测：播自己的片子时对着银幕按 B 跳回了 B站 网页】
                     *
                     * `webHiddenByStream` 的语义是"**这一部网页流**被我们的播放器顶掉了"。
                     * 播 Emby 片子时它还留着上一部 B站 的 true（现场日志：
                     * `被播放器顶掉=true 记下的站=null`），于是这一支把银幕交回网页 —— 张冠李戴。
                     * 所以再上一道闸：此刻必须真的在放网页流（`webStreamSite` 非空）
                     * 才允许"让位回网页"。播自己的片子时 webStreamSite 已在起播处清成 null。
                     */
                    if (onScreen && webStreamSite != null) {
                        Log.i(TAG, "光柱 B 键 → 播放器让位，银幕交回网页（光柱指着银幕）")
                        chainLog("⑨按B回网页", "站=" + webStreamSite + "（接下来网页应真退出全屏、接收槽清空）")
                        returnFromStreamToWeb()
                    } else if (!onScreen) {
                        Log.i(TAG, "光柱 B 键忽略：光柱没指在银幕上（接管后只认指着银幕那一按）")
                    } else {
                        Log.i(TAG, "光柱 B 键忽略：现在放的不是网页流（记下的站=null）")
                    }
                } else if (renderer.videoActive) {
                    Log.i(TAG, "光柱 B 键忽略：光标对着播放画面（播放页操作走控制条）")
                } else if (panelInputReady()) {
                    Log.i(TAG, "光柱 B 键 → 面板返回")
                    panel.back()
                }
            }
        }
    }

    /** 控制条能不能接输入 */
    private fun osdReady(): Boolean = ::osd.isInitialized && osd.osdReady && osdVisible

    /** 菜单面板能不能接输入 */
    private fun menuReady(): Boolean =
        ::menu.isInitialized && menu.osdReady && menuState.kind != null

    /** 打开某个菜单（对齐到触发它的那颗按钮正上方） */
    private fun openMenu(kind: com.xxxx.emby_vr.panel.MenuKind, anchor: com.xxxx.emby_vr.panel.OsdButton) {
        /*
         * 打开字幕菜单前，先按"播放器实际在用的那条轨"回填勾选。
         * 父亲 2026-11-18：起播时内嵌字幕已经上屏，但菜单里一项都没勾 ——
         * 菜单显示的是我们记的选择，不是实际在用的轨，两边必须对齐。
         */
        if (kind == com.xxxx.emby_vr.panel.MenuKind.SUBTITLE) {
            runCatching { syncSelectedSubtitleFromPlayer() }
        }
        fillMenuData(kind)
        menuState.anchor = anchor
        menuState.kind = kind
        osdState.activeMenuButton = anchor
        if (::menu.isInitialized) com.xxxx.emby_vr.vr.VrNative.setMenuVisible(true)
        Log.i(TAG, "菜单打开：${kind.title}（对齐 ${anchor.label}）")
    }

    /**
     * 菜单返回上一级（父亲 2026-10-06 晚定的操作逻辑）。
     *
     * 二级（从「更多」点进去的音频 / 质量 / 模式 / 缓冲）→ 退回「更多」那一级；
     * 一级（从控制条按钮直接展开的）→ 关掉菜单。B 键与卡片上的「返回」都走这里。
     */
    private fun menuBack() {
        val kind = menuState.kind ?: return
        if (kind.isSubMenu) {
            Log.i(TAG, "菜单返回上级：${kind.title} → 更多")
            openMenu(com.xxxx.emby_vr.panel.MenuKind.MORE, com.xxxx.emby_vr.panel.OsdButton.MORE)
        } else {
            Log.i(TAG, "菜单返回：${kind.title} → 关闭")
            closeMenu()
        }
    }

    /**
     * 信息面板的技术行（父亲 2026-10-06 晚：元数据不够，照电视版补）。
     * 例：`1080p HEVC · 1920×1080 · AAC 立体声`。
     */
    private fun techLineOf(): String {
        val v = currentStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
        val a = currentStreams.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
        /*
         * 显示"大屏幕实际按多大上屏"，不是片源写着多少 —— 两者在 4K 片源 + 宽度上限
         * 之下经常不一致，父亲要一眼看得出实际跑的是哪一档。
         * 算法与原生侧同源：P5 走 video_surface_w，其余走 raw_max_w，3D 再叠每眼上限。
         */
        val size = screenSizeOf(v?.width, v?.height)
        return listOfNotNull(
            v?.displayTitle?.takeIf { it.isNotBlank() },
            size,
            a?.displayTitle?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    /** 大屏幕实际显示的像素尺寸（受当前宽度上限约束，见 techLineOf 的说明） */
    private fun screenSizeOf(srcW: Int?, srcH: Int?): String? {
        if (srcW == null || srcH == null || srcW <= 0 || srcH <= 0) return null
        val vr = com.xxxx.emby_vr.vr.VrTuning
        /* 实际宽度取"当前选中那一档"与上限的较小者：切了 2560 就该显示 2560 */
        var cap = if (com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource) {
            vr.videoSurfaceMaxW.let { if (it > 0) it else 1920 }
        } else {
            vr.rawMaxW.let { if (it > 0) it else vr.VIDEO_W_HARD_CAP }
        }
        val p5 = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource
        val steps = com.xxxx.emby_vr.panel.qualityStepsFor(
            kernelActive = ctl.kernelActive,
            isWeb = webStreamSite != null,
            isP5 = p5,
            stereo3d = vr.stereo3dOn,
        )
        val picked = steps.entries.getOrNull(qualityIndex)?.first ?: 0
        if (picked > 0 && cap > picked) cap = picked
        if (vr.stereo3dOn && vr.stereoEyeW > 0 && cap > vr.stereoEyeW) cap = vr.stereoEyeW
        val w = if (srcW > cap) cap else srcW
        val h = (srcH.toLong() * w / srcW).toInt().coerceAtLeast(1)
        val shown = "${w}×$h"
        /* 被压过就一并标出片源尺寸，免得看不出差别来 */
        return if (w != srcW) "$shown（片源 ${srcW}×$srcH）" else shown
    }

    /**
     * 启动弹幕画笔：把原生给的那张纹理包成画布，逐帧画弹幕。
     *
     * 尺寸用 2560×1440（16:9，够弹幕文字清晰），与原生弹幕层的缓冲尺寸一致。
     */
    /**
     * 弹幕画布比例跟影片走（父亲 2026-10-07 定的第二条方案）。
     *
     * 宽度取运行时开关 [com.xxxx.emby_vr.vr.VrTuning.danmakuCanvasW]：默认 **1920**，
     * 与银幕本身的 1920 宽对齐（2026-10-14 父亲定：原来钉死 2560，比银幕还大三分之一，
     * 多出来的像素在 VR 里看不见，却每帧都要整张拷进图层）。
     * 高度 = 宽度 ÷ 画面比例：画布比例与银幕完全一致，弹幕、片名 logo、字幕、
     * 快进快退进度条都不再被拉伸，也不会跑到画面外。
     */
    private fun applyDanmakuCanvas(aspect: Float) {
        if (aspect <= 0.2f || aspect > 6f) return
        lastDanmakuAspect = aspect
        val w = com.xxxx.emby_vr.vr.VrTuning.danmakuCanvasW
        val h = Math.round(w.toFloat() / aspect).coerceIn(600, 3200)
        if (w == danmakuCanvasW && h == danmakuCanvasH) return
        danmakuCanvasW = w
        danmakuCanvasH = h
        com.xxxx.emby_vr.vr.VrNative.setDanmakuCanvas(w, h)
        danmakuPainter?.resizeTo(w, h)
        Log.i(TAG, "弹幕画布随影片比例 → ${w}x$h（比例 $aspect）")
    }

    /**
     * 从源里挑一条头显能解的音轨（父亲 2026-10-07：音画不同步）。
     *
     * 头显只给第三方应用开放 AAC/MP3 这类音轨，EAC3/DTS/AC3 都不在名单里。
     * 片子默认音轨是 DTS、同时又带一条 AAC 时，让服务端直接发原文件里那条 AAC ——
     * 比「画面直通 + 音频转码再拼回」音画同步得多，也不用实时从网盘拉源。
     * 返回 null：默认那条就能放，或源里没有能放的音轨（交给服务端转码）。
     */
    private fun pickPlayableAudio(
        streams: List<com.xxxx.emby_vr.data.model.MediaStreamDto>?,
    ): Int? {
        val audios = streams.orEmpty().filter { it.type.equals("Audio", ignoreCase = true) }
        if (audios.isEmpty()) return null
        fun playable(codec: String?) = codec?.lowercase() in setOf("aac", "mp3")
        val default = audios.firstOrNull { it.isDefault == true } ?: audios.first()
        if (playable(default.codec)) return null
        return audios.firstOrNull { playable(it.codec) }?.index
    }

    /**
     * 把用户选的那条字幕整条取回来（父亲 2026-10-07）。
     *
     * 服务端能把内封/外挂字幕转成 srt 文本，所以不管片源里是 srt 还是 ass 都拿得到。
     * 拿到后自己解析、按播放位置显示 —— 不依赖服务端出流，也不受直连/转码影响。
     */
    private fun loadSubtitleTrack(index: Int) {
        val mediaId = currentMediaId
        /*
         * 片源编号必须属于**当前这一集**（2026-10-09 服务端日志实证）。
         *
         * 日志里出现过 `/Videos/3930727/mediasource_3930720/Subtitles/3/Stream.srt`
         * —— 集号是新的、片源编号还是上一集的，服务端直接
         * `Sequence contains no matching element` → 返回 0 字节 → 字幕空。
         * 所以先按集号核对，对不上就退回标准命名（单版本片源就是 mediasource_<集号>）。
         */
        val sourceId = currentSourceId() ?: "mediasource_$mediaId"
        if (mediaId.isBlank()) return
        /*
         * 弹幕轨永远不当普通字幕画（2026-10-10 父亲明确：弹幕的 ASS 文件名里带「弹幕」，
         * 用文件名就能把弹幕和普通字幕分开）。
         *
         * 走到这里说明有条弹幕轨的序号被交给了字幕通道 —— 可能是清单还没更新时的旧序号，
         * 也可能是播放器自己挑轨挑中了它。结果就是同一份弹幕在画面下方又当字幕画了一遍，
         * 看起来「上下都有弹幕」。这里按文件名直接拒掉，弹幕只归弹幕层。
         */
        currentStreams.firstOrNull { it.index == index }?.let { meta ->
            if (isDanmakuStream(meta)) {
                Log.w(TAG, "拒画：流 $index 是弹幕轨（${meta.displayTitle ?: meta.path ?: "?"}）→ 交给弹幕层")
                subtitleCues = emptyList()
                subtitleCueStream = null
                subtitleNow = ""
                danmakuView?.setSubtitle("")
                return
            }
        }
        subtitleCueStream = index
        /*
         * 自绘字幕接管时，必须把播放器自己那条文字轨关掉（2026-10-11 修）。
         *
         * 症状（父亲实测《初潮》）：切到低画质触发转码后，字幕菜单里换字幕「没反应」，
         * 而且屏上字幕与画面进度对不上；直推时正常。
         *
         * 根因：转码时服务端会顺带出一条 HLS 字幕轨，播放器按语言偏好自己把它选上
         * （日志实证：`播放器自动选中的字幕 = 流 2`），而我们另有一条自绘的 ——
         * 两路字幕写同一个字幕层，且播放器那路按**转码流的分片时间轴**走，与画面
         * 进度不一致。切字幕只换了我们自绘这条，播放器那条没跟着换，于是
         * 「切了没反应」「字幕与进度对不上」。
         *
         * 这里统一断掉播放器文字轨：凡走自绘（转码 / 外挂字幕）的场合都只剩我们这一路。
         * 直推/直连不受影响 —— 那条路走 selectEmbeddedTextTrack，不经过这里。
         */
        player?.let { pl ->
            pl.trackSelectionParameters = pl.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(
                    androidx.media3.common.C.TRACK_TYPE_TEXT, true,
                )
                .build()
        }
        val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
            "/Stream.srt?api_key=${userToken()}"
        scope.launch {
            /*
             * 取字幕整条：**失败重试 3 次**（2026-10-10 系统排查）。
             * 网盘片源第一次要服务端现挖，偶尔连接被重置（实测 响应码=-1），
             * 重试通常就成；三次都不成才放弃，并在屏幕上说一句（不再"字幕凭空没了"）。
             */
            var raw: String? = null
            for (attempt in 0 until 3) {
                if (mediaId != currentMediaId || subtitleCueStream != index) return@launch
                raw = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.URL(url).openConnection().let { conn ->
                        conn.connectTimeout = 10000
                        /*
                         * 读超时放宽到 30 秒（2026-10-09）：Emby 对某些字幕轨（图片型/
                         * 需要 OCR 的）转 SRT 会非常慢甚至长时间不回，20 秒不够，
                         * 实测同一部剧有的轨秒回、有的直接挂住。
                         */
                        /*
                         * 读超时从 30 秒放宽到 150 秒（2026-10-09 父亲报「切到别的字幕项都没字幕」）。
                         *
                         * 服务端日志实证：内封字幕要现挖 —— 服务端要拿 ffmpeg 从云端那个 5 GB 的
                         * 原文件里逐段读取字幕包，等于把整片读一遍，30 秒根本读不完；
                         * 我们一断，服务端就把任务取消（TaskCanceledException: A task was canceled，
                         * 耗时正好 30007ms）。外挂字幕（我们自己的 .ass）是现成文件，28 毫秒就回来，
                         * 所以只有那一项有字幕。
                         * 放宽之后第一次选要等（服务端挖完会缓存，之后同一轨是秒回）。
                         */
                        conn.readTimeout = 150000
                        /*
                         * 不能无条件按 UTF-8 读（2026-10-09 修，父亲实测「选了中文字幕是乱字符」）。
                         *
                         * Emby 的 Subtitles 接口把内封/外挂字幕转成 SRT 文本，但**可能保留
                         * 原字幕的编码**（中文片常见 GBK/GB18030）。原来用 bufferedReader()
                         * 默认 UTF-8 硬读 → 乱字符；同一部片 4XVR 字幕正常，因为它认编码。
                         *
                         * 策略：① 优先用响应头声明的 charset；
                         *       ② 没声明就先按 UTF-8 **严格**解码，遇非法字节回退 GB18030。
                         */
                        /*
                         * 把请求细节打进日志（2026-10-09）：原来只看到"取到 0 条"，
                         * 分不清是 URL 不对、权限不对，还是服务端真的没内容。
                         * token 不落盘 —— 只打 URL 的问号之前部分 + 响应码。
                         */
                        /* responseCode 只有 HTTP(S) 连接才有，普通 URLConnection 没有 */
                        val code = runCatching {
                            (conn as? java.net.HttpURLConnection)?.responseCode ?: -1
                        }.getOrDefault(-1)
                        val declared = conn.contentType
                        Log.i(TAG, "自绘字幕：流 $index 响应码=$code 类型=$declared " +
                            "地址=${url.substringBefore("?")}")
                        val bytes = conn.getInputStream().use { it.readBytes() }
                        decodeSubtitleText(bytes, declared)
                    }
                    }.getOrNull()
                }
                if (!raw.isNullOrBlank()) break
                Log.w(TAG, "自绘字幕：流 $index 第 ${attempt + 1} 次没取到")
                if (attempt < 2) kotlinx.coroutines.delay(1200L)
            }
            // 中途换片 / 换字幕轨 → 结果丢掉
            if (mediaId != currentMediaId || subtitleCueStream != index) return@launch
            val cues = parseSrt(raw)
            subtitleCues = cues
            Log.i(TAG, "自绘字幕：流 $index 取到 ${cues.size} 条")
            /*
             * 菜单勾选与屏幕内容必须同源（父亲 2026-11-22）：
             * 取到了就按"这条轨正在显示"刷新一次，避免菜单还停在上一次的勾选状态。
             */
            if (cues.isNotEmpty()) {
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
            }
            /*
             * 取不到就**清屏**（2026-10-09 父亲要求）。
             *
             * 原来失败了什么都不做，屏幕上继续显示上一条字幕的残留 ——
             * 切到别的语言时用户看到的就是"上一次的内容"，误以为是乱字符。
             * 现在明确清空：宁可不显示，也不显示错的。
             */
            if (cues.isEmpty()) {
                Log.w(TAG, "自绘字幕：流 $index 三次都没取到内容（${raw?.length ?: 0} 字节）→ 清屏")
                subtitleNow = ""
                if (!waitingFirstFrame) danmakuView?.setSubtitle("")
                /*
                 * 屏幕上说一句（父亲 2026-10-10：别再"字幕凭空没了"）。
                 * 网盘片源第一次要服务端现挖整片，可能真的取不到；再点一次字幕菜单
                 * 通常就成了（服务端挖完会缓存）。
                 */
                /*
                 * 取不到就**取消勾选**（父亲 2026-11-22：显示与勾选同步）。
                 * 菜单上继续勾着一条屏幕上根本没有的字幕，就是在骗人；
                 * 清掉之后用户再点同一条即可重试（服务端挖完会缓存）。
                 */
                selectedSubtitleIndex = null
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
                hud("这条字幕服务端没取到，可在字幕菜单里再点一次")
            }
        }
    }

    /**
     * 字幕文本解码（2026-10-09）：优先响应头 charset，否则 UTF-8 严格解码失败回退 GB18030。
     *
     * 背景见 loadSubtitleTrack 里的注释：Emby 转出的 SRT 可能是 GBK 系编码，
     * 按 UTF-8 硬读会得到乱字符。
     */
    private fun decodeSubtitleText(bytes: ByteArray, contentType: String?): String {
        val declared = Regex("charset\\s*=\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.getOrNull(1)
        if (!declared.isNullOrBlank()) {
            runCatching { return String(bytes, charset(declared)) }
        }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            Log.w(TAG, "自绘字幕：不是 UTF-8，回退 GB18030 解码")
            String(bytes, charset("GB18030"))
        }
    }

    /** SRT 解析：只要时间和文本，够弹幕层的字幕位用 */
    /**
     * 字幕文本清洗（2026-10-09 修，父亲实测「中英文字幕都是乱七八糟的字母」）。
     *
     * 根因：片源内封的字幕是从 ASS 转出来的 SRT，**每句都带着 ASS 排版标记**。
     * 从 Emby 拉下来的原始字节实测：
     *
     *     1
     *     00:00:06,800 --> 00:00:08,359
     *     {\an8}Please put your hands together
     *
     * `{\an8}` 是「把这句显示在画面上方」的排版指令。我们把这行当纯文本画出去，
     * 于是**每句话前面都多出一段 `{\an8}` 之类的符号** —— 中英文都一样，
     * 看起来就是"乱七八糟的字母"。4XVR 认这些标记（它按 ASS 规则排版），所以正常。
     *
     * 处理：滤掉 `{...}` 排版块与残留的 `<...>` 标签，并压掉多余空白。
     */
    /**
     * 字幕文字清理（2026-10-09 父亲实测：SubRip 里的换行转义被原样画了出来）。
     *
     * 要处理的东西：
     *   · `{\an8}` 这类 ASS 覆盖标记 → 去掉；
     *   · `<i>` 这类 HTML 风格标记 → 去掉；
     *   · `\N`（硬换行）`\n`（软换行）→ **真的换行**（字幕层按行绘制，能画多行）；
     *   · `\h`（硬空格）→ 空格；其余反斜杠转义一律去掉。
     */
    private fun cleanSubtitleText(s: String): String {
        val t = s
            .replace(Regex("""\{[^}]*\}"""), "")
            // 标签不再限 40 字符：`<font face="微软雅黑" size="72" color="#ffe680">`
            // 这种长标签原来删不掉，会被当正文画出来（父亲 2026-10-09 实测）
            .replace(Regex("""<[^<>]{0,300}>"""), "")
            .replace(Regex("""\\[Nn]"""), "\n")
            .replace(Regex("""\\h"""), " ")
            .replace("\\", "")
        return t.split("\n")
            .joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .trim('\n', ' ', '\t')
    }

    private fun parseSrt(raw: String?): List<SubtitleCue> {
        if (raw.isNullOrBlank()) return emptyList()
        val timeRe = Regex(
            """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
        )
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val rawCues = ArrayList<RawCue>()
        var i = 0
        fun toMs(g: Int, m: MatchResult): Long =
            (m.groupValues[g].toLong() * 3600 + m.groupValues[g + 1].toLong() * 60 +
                m.groupValues[g + 2].toLong()) * 1000 +
                m.groupValues[g + 3].padEnd(3, '0').toLong()

        while (i < lines.size) {
            val m = timeRe.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = toMs(1, m)
            val end = toMs(5, m)
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                body.add(lines[i])
                i++
            }
            if (body.isNotEmpty()) rawCues.add(RawCue(start, end, body))
        }
        /*
         * 字号基准：取这一集**出现最多的那一档**当 1.0（2026-10-09）。
         *
         * 服务端把 ASS 转成 SRT 时，字号写进了 font 标签（如中文 size="72"、
         * 英文 size="48"）。直接拿绝对值当像素画会大小失控，所以只取**相对比例**：
         * 众数那档 = 1.0，其余按比例缩放 —— 中文字大、英文字小，与原片一致，
         * 而整体大小仍由我们按银幕宽度定的基准决定。
         */
        val sizeCount = HashMap<Int, Int>()
        for (c in rawCues) {
            for (l in c.lines) {
                parseStyle(l).size?.let { sizeCount[it] = (sizeCount[it] ?: 0) + 1 }
            }
        }
        val refSize = sizeCount.maxByOrNull { it.value }?.key ?: 0
        return rawCues.mapNotNull { c ->
            val ls = c.lines.mapNotNull { rawLine ->
                val st = parseStyle(rawLine)
                val text = cleanSubtitleText(st.text)
                if (text.isEmpty()) {
                    null
                } else {
                    com.xxxx.emby_vr.danmaku.SubtitleLine(
                        text = text,
                        sizeFactor = if (refSize > 0 && st.size != null) {
                            st.size.toFloat() / refSize.toFloat()
                        } else {
                            1f
                        },
                        color = st.color,
                    )
                }
            }
            if (ls.isEmpty()) null else SubtitleCue(c.startMs, c.endMs, ls.joinToString("\n") { it.text }, ls)
        }
    }

    /** size="72" / color="#ffe680" 这些属性 */
    private val kFontSizeRe = Regex("""size\s*=\s*"?(\d+)""", RegexOption.IGNORE_CASE)
    private val kFontColorRe = Regex(
        """color\s*=\s*"?(#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|[a-zA-Z]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 把一行带样式标签的文本拆成「纯文字 + 字号 + 颜色」（父亲 2026-10-09）。
     *
     * 服务端转换后的样子：`<font face="微软雅黑" size="72" color="#ffe680"><b>中文</b></font>`
     * 原来我们只删标签，且正则限了 40 字符长度 —— 长标签删不掉，于是
     * 「微软雅黑 / size / color」被当正文画了出来。现在按标签解析，标签一律不画。
     */
    private fun parseStyle(rawLine: String): StyledRaw {
        val sb = StringBuilder()
        var size: Int? = null
        var color: Int? = null
        var i = 0
        while (i < rawLine.length) {
            val lt = rawLine.indexOf('<', i)
            if (lt < 0) {
                sb.append(rawLine, i, rawLine.length)
                break
            }
            if (lt > i) sb.append(rawLine, i, lt)
            val gt = rawLine.indexOf('>', lt)
            if (gt < 0) break                       // 不闭合的标签：后面是垃圾，丢掉
            val tag = rawLine.substring(lt, gt + 1).lowercase()
            when {
                tag.startsWith("<font") -> {
                    kFontSizeRe.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { size = it }
                    kFontColorRe.find(tag)?.groupValues?.getOrNull(1)?.let { v ->
                        runCatching { android.graphics.Color.parseColor(v) }.getOrNull()
                            ?.let { color = it }
                    }
                }
                tag.startsWith("</font") -> {
                    size = null
                    color = null
                }
            }
            i = gt + 1
        }
        return StyledRaw(sb.toString(), size, color)
    }

    /**
     * 自绘字幕跟片走（父亲 2026-10-07）：按当前播放位置找命中的那一条。
     */
    private fun updateDrawnSubtitle() {
        /*
         * 内核（Profile 5）这条路的字幕由 mpvSubtitleJob 从内嵌轨道取文，别两边抢着写。
         * 但全包档（selfSession != null）画面与字幕都归我们自己：mpv 对象可能仍存在，
         * 这里必须放行，否则下面全部逻辑（含诊断日志）一行都不会执行 —— 字幕永远不上屏
         * （豆豆 2026-11-23 定位）。
         */
        if (mpvBackend != null && selfSession == null) return
        if (subtitleCues.isEmpty()) return
        val pos = currentPositionMs()
        val cue = subtitleCues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
        /* 诊断：字幕表有内容却不上屏时，对照"当前进度"与"表里时刻"（每 2 秒一行） */
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (subtitleCues.isNotEmpty() && nowMs - subDiagAtMs > 2000L) {
            subDiagAtMs = nowMs
            Log.i(
                TAG,
                "字幕诊断|进度=${pos}ms|表=${subtitleCues.size}条|首条=${subtitleCues.first().startMs}ms|" +
                    "命中=${cue?.text?.length ?: -1}|waitingFirstFrame=$waitingFirstFrame|" +
                    "队列尾=${(selfSession?.videoQueueTailUs ?: -1L) / 1000}ms",
            )
        }
        val text = cue?.text.orEmpty()
        subtitleNow = text
        if (!waitingFirstFrame) {
            // 有样式信息就按样式画（字号/颜色），没有就走纯文本那条老路
            if (cue != null && cue.lines.isNotEmpty()) {
                danmakuView?.setSubtitleRich(cue.lines)
            } else {
                danmakuView?.setSubtitle(text)
            }
        }
    }

    private fun attachDanmakuSurface(st: android.graphics.SurfaceTexture) {
        runOnUiThread {
            runCatching {
                danmakuPainter?.stop()
                /*
                 * 画布尺寸以运行时开关为准（2026-10-14）。
                 *
                 * 这里必须同时把尺寸推给原生：弹幕层的交换链是按原生那个值建的，
                 * 画笔与它不一致就会被拉伸。原来画笔写死 2560、原生层也 2560，
                 * 但银幕本身才 1920 —— 白多三分之一像素。现在两边都用开关值。
                 */
                val wantW = com.xxxx.emby_vr.vr.VrTuning.danmakuCanvasW
                val wantH = Math.round(wantW.toFloat() / lastDanmakuAspect).coerceIn(600, 3200)
                if (wantW != danmakuCanvasW || wantH != danmakuCanvasH) {
                    danmakuCanvasW = wantW
                    danmakuCanvasH = wantH
                    com.xxxx.emby_vr.vr.VrNative.setDanmakuCanvas(wantW, wantH)
                    Log.i(TAG, "弹幕画布尺寸校准 → ${wantW}x$wantH（开关 $wantW）")
                }
                val painter = com.xxxx.emby_vr.danmaku.DanmakuSurfacePainter(
                    context = this,
                    surfaceTexture = st,
                    widthPx = danmakuCanvasW,
                    heightPx = danmakuCanvasH,
                    positionProvider = { danmakuPosForPainter() },
                    scale = danmakuScale,
                )
                danmakuView = painter.view
                // 等第一帧期间不画片名 logo（父亲 2026-10-07：logo 要跟画面一起出现）
                painter.logoBitmapProvider = { if (waitingFirstFrame) null else logoBitmap.value }
                /*
                 * 等待期（换片 / 首播）：黑幕 + 转圈 + 「即将播放：片名」全部画在这一层。
                 * 平时返回 null，这一层恢复"透明底 + 弹幕 + 字幕 + logo"。
                 */
                painter.loadingProvider = {
                    if (waitingFirstFrame) (danmakuHint.value ?: "即将播放…") else null
                }
                painter.hintProvider = { if (waitingFirstFrame) null else danmakuHint.value }
                painter.view.setTrack(if (danmakuOn) danmakuTrack else null)
                painter.view.setSubtitle(subtitleNow)
                painter.start()
                danmakuPainter = painter
                Log.i(TAG, "弹幕画笔已启动（${danmakuCanvasW}x$danmakuCanvasH，开关=${if (danmakuOn) "开" else "关"}）")
            }.onFailure { t ->
                Log.e(TAG, "弹幕画笔启动失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** 下载片名 logo 位图（见字段注释：绕开 Coil，成功失败都有日志） */
    private fun loadLogoBitmap(url: String?) {
        /*
         * 同一地址已下载成功就不再动（父亲 2026-10-07 00:55 实测「logo 时隐时现」）：
         * loadItemDetail 在起播和每次开菜单时都会调，原来每次都清空旧图重新下载，
         * 下载的那几秒画面上就没有 logo —— 看起来就是「一会儿有一会儿没有」。
         */
        if (url == logoLoadedUrl && logoBitmap.value != null) {
            // 位图早就下好了，但要不要显示仍受「等第一帧」门控（父亲 2026-10-07）
            com.xxxx.emby_vr.vr.VrNative.setLogoVisible(!waitingFirstFrame)
            return
        }
        logoLoadedUrl = url
        logoLoadJob?.cancel()
        logoBitmap.value = null
        if (url.isNullOrBlank()) {
            com.xxxx.emby_vr.vr.VrNative.setLogoVisible(false)
            return
        }
        logoLoadJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection()
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp == null) {
                Log.w(TAG, "片名 logo 下载失败（地址打不开或不是图片）")
                logoLoadedUrl = null   // 失败了允许下次重试
            } else {
                Log.i(TAG, "片名 logo 下载 → ${bmp.width}x${bmp.height}")
            }
            logoBitmap.value = bmp
            // 等第一帧期间先藏着（父亲 2026-10-07），第一帧到了由 reveal 一起放出来
            com.xxxx.emby_vr.vr.VrNative.setLogoVisible(bmp != null && !waitingFirstFrame)
        }
    }

    /** 控制条第一行右侧的时间格式 */
    private val osdClockFormat =
        java.text.SimpleDateFormat("yyyy年MM月dd HH:mm:ss", java.util.Locale.getDefault())

    /**
     * 控制条第一行的片名（父亲 2026-10-06 晚定稿）：
     *  · 电影：`正在播放：片名`
     *  · 剧集：`正在播放：剧名 第X集 集名`
     *  · 有剧名没集号：`正在播放：剧名`
     */
    /**
     * 控制条的片名文案（父亲 2026-10-07：「正在播放」与「即将播放」用同一套）。
     *
     * 剧集 = 剧名 + 第X集 + 集名；电影 = 片名。前缀由调用方给。
     */
    /**
     * 【2026-10-23 00:48 父亲纠正"按位数猜集号也不对"】
     *
     * 剧集本来就有几十上百集，靠"数字位数"分辨"内部编号 vs 集序"根本不成立。
     * 正解：网页载荷里 **身份 ep 一律不当集号显示**；显示用的集序单独走 `epNo`
     * （页面元数据里的"第几集"）。epNo 取不到就只显示片名 —— 宁可不显示集号，
     * 也不能把内部编号当集号摆给父亲看。
     */
    private fun webEpisodeNumber(obj: org.json.JSONObject): Int {
        val n = obj.optInt("epNo", 0)
        return if (n in 1..2000) n else 0
    }

    private fun itemTitleOf(item: com.xxxx.emby_vr.data.model.BaseItemDto?): String {
        val series = item?.seriesName
        /*
         * 集号前面要带**季号**（父亲 2026-10-10）：
         * 只写"第 3 集"在长剧里分不清是哪一季的，控制条的"正在播放 / 即将播放"都要带上。
         */
        val season = item?.parentIndexNumber
        val ep = item?.indexNumber
        val name = item?.name
        val seasonPart = if (season != null) "第${season}季 " else ""
        return when {
            !series.isNullOrBlank() && ep != null ->
                /*
                 * 注意 `${seasonPart}` 必须带花括号：中文字符在 Kotlin 里算合法标识符字符，
                 * 写成 `$seasonPart第` 会被当成变量名 `seasonPart第` → 编译不过
                 * （2026-10-10 实测又踩一次，笔记里记过这个坑）。
                 */
                if (!name.isNullOrBlank() && name != series) "$series ${seasonPart}第${ep}集 $name"
                else "$series ${seasonPart}第${ep}集"
            !series.isNullOrBlank() -> series
            else -> name ?: ""
        }
    }

    private fun osdTitleText(prefix: String = "正在播放："): String =
        prefix + itemTitleOf(currentItem)

    /**
     * 【2026-10-17 修「控制条写着『正在播放： 正在播放：』且片名空」】
     *
     * `osdState.title` 现在**只放片名本身**，前缀由控制条那一行加一次（原来这里放的
     * 是带前缀的整句，而控制条又加一遍 → 前缀画两次）。
     * 另外"每秒刷新"这一路原来无条件写入 —— 放网页流时 `currentItem` 是空的，
     * 于是每秒把网页流的片名覆盖成空串（这就是片名那格空着的原因）。
     * 规矩：**取不到片名就不写**，别用空值覆盖别人写好的标题。
     */
    /**
     * 银幕比例的**唯一入口**（父亲 2026-10-18 00:08 实测「切换时比例时对时错」立的）。
     *
     * 银幕高度 = 宽度 / 比例，所以这个值错了画面就被压扁或拉长。写它的地方有四处：
     * 换源清场（回 16:9）、网页流（抓来的宽高）、内核（报出的真实尺寸）、
     * 网页面铺上银幕（16:9）。谁都直接调原生，就必然出现「谁最后写谁生效」，
     * 而内核那一路原本还带一个自己记的去重标记 —— 一旦被别的路径覆盖就再也不纠。
     * 现在统一走这里：记下当前生效的值，任何一路发现与自己不一致都能纠回来。
     */
    private fun applyScreenAspect(a: Float, why: String) {
        if (a <= 0f || !a.isFinite()) return
        if (kotlin.math.abs(a - appliedAspect) < 0.0005f) return
        appliedAspect = a
        com.xxxx.emby_vr.vr.VrNative.setVideoAspect(a)
        applyDanmakuCanvas(a)
        Log.i(TAG, "银幕比例 → $a（$why）")
    }

    private fun refreshOsdTitleFromItem() {
        /*
         * 【2026-10-17 23:26 父亲实测：从 Emby 片子切到 B站 视频后，控制条标题还是 Emby 片名】
         *
         * 这一拍每秒跑一次（startOsdTicker），而 currentItem 还停在上一部 Emby 片子上，
         * 于是刚写好的「站点-片名」下一秒就被它按回去了。
         * 正在放网页流时不许它插手 —— 标题由 applyWebStreamTitle 一个人负责。
         */
        if (webPlayingNow) {
            /*
             * 画布按内核真实尺寸重设只有一处（播放侧每秒一次的循环），这里不再重复做：
             * 两处同时按不同上限重设，切档结果会被另一处顶回去（切 2560 而画布仍回 4096）。
             */
            /* 尺寸要等第一帧才准，所以每秒复核一次，把分辨率补进标题 */
            val base = webTitleBase
            if (base.isNotEmpty()) {
                val nt = base + resSuffix()
                if (nt != osdState.title) {
                    osdState.title = nt
                    Log.i(TAG, "控制条标题（网页流，补分辨率）→ $nt")
                }
            }
            return
        }
        val t = itemTitleOf(currentItem)
        /* 与网络视频同一口径：片名后面跟「实际 W×H（原 片源 W×H）」 */
        if (t.isNotEmpty()) osdState.title = t + resSuffix()
    }

    /** 最近 300ms 面板刚吃过指针 / 点击 / 滚动：这一下扳机归面板 */
    private fun panelTouchedRecently(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastPanelClickAt < 300L

    /**
     * 上次退出前海报墙摆在哪儿：读回来（父亲 2026-10-06 晚）。
     *
     * 位置 / 朝向 / 宽度一起存、一起还 —— 他调好一次，下次打开就还在那儿。
     */
    private fun restorePanelPlace() {
        val raw = placePrefs.getString("panel_place", null) ?: return
        val v = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
        if (v.size != 6) return
        com.xxxx.emby_vr.vr.VrNative.setPanelPlace(v)
        Log.i(TAG, "海报墙摆放已还原到上次退出前的样子")
    }

    /** 把海报墙当前摆放存下来（下次打开还原） */
    private fun savePanelPlace() {
        val v = com.xxxx.emby_vr.vr.VrNative.getPanelPlace() ?: return
        /*
         * 这里必须 commit()（同步写），不能 apply()（异步）：
         * 退出走的是 exitApp() 直接杀进程，进程被杀的那一刻 apply() 排队的写
         * 可能还没落盘，下次打开读到的仍是旧值（2026-10-30 父亲实测：
         * 1113 版「海报墙摆放已记住」日志打了、位置却没存上，就是这个原因）。
         */
        val ok = placePrefs.edit().putString("panel_place", v.joinToString(",")).commit()
        Log.i(TAG, if (ok) "海报墙摆放已记住（同步落盘）" else "海报墙摆放保存失败（commit 返回 false）")
    }

    /** 关掉菜单（控制条按钮高亮一并清掉） */
    private fun closeMenu() {
        if (menuState.kind == null) return
        menuState.kind = null
        osdState.activeMenuButton = null
        // 卡片矩形清零：菜单都不在了，射线不该再被它拦住
        com.xxxx.emby_vr.vr.VrNative.setMenuHitRect(0f, 0f, 0f, 0f)
        com.xxxx.emby_vr.vr.VrNative.setMenuVisible(false)
        Log.i(TAG, "菜单关闭")
    }

    /*
     * 摇杆左右快进快退（2026-10-07 父亲：光柱指着屏幕时，逻辑复刻电视版）。
     *
     * 电视版（`PlayerScreen.kt` 行 1670–1711 / 754–782）：
     *   · 按下后 500ms 内抬起 → ±10 秒（播放器的 seekBack/Forward 增量就是 10 秒）
     *   · 按住 ≥500ms        → 每 200ms 跳 30 秒；跳的时候若已暂停，自动续播
     *   · 每次按下（含长按连发）都唤出画面底部的进度条，最后一次操作后 5 秒收起
     *   · 控制条展开时左右键不快进快退（交给焦点移动）
     *
     * PICO 上没有"按下 / 抬起"，只有摇杆量（原生每 33ms 推一帧、回中推一帧零值），
     * 所以等价换算：推过 0.7 当作按下；回到 0.35 以下当作抬起；
     * 中间保持的时长对上电视版那个 500ms 判定。
     */
    private var stickSeekDir = 0              // 0 = 没推 / +1 快进 / -1 快退
    private var stickSeekAccel = false        // 是否已进入"按住加速"那一档
    private var stickSeekHoldJob: kotlinx.coroutines.Job? = null

    /** 画面上那条快进快退进度条：正在显示 / 刷新任务 / 隐退任务 */
    private var seekHudShown = false
    private var seekHudTickJob: kotlinx.coroutines.Job? = null
    private var seekHudHideJob: kotlinx.coroutines.Job? = null

    private fun stickSeek(sx: Float) {
        /*
         * 用公共播放控制层判断，**不能看 ExoPlayer 对象**（2026-10-09 修）。
         *
         * 原来这一行是 `if (player == null) return`：内核（杜比视界 Profile 5）这条路
         * 压根没有 ExoPlayer 实例，于是整个快进快退**第一步就返回** —— 连日志都不打。
         * 父亲实测：光柱指着银幕、菜单关着、推了 6 次左右，日志里一条"摇杆短推"都没有。
         * 其他片源走 ExoPlayer，player 不为空，所以**这个毛病只在 Profile 5 上出现**。
         * ctl（PlaybackControl）本来就同时支持两套引擎，用它即可。
         */
        if (!ctl.hasEngine()) return
        val mag = kotlin.math.abs(sx)
        if (mag < 0.35f) {                                   // 回中 = 抬起
            if (stickSeekDir == 0) return
            stickSeekHoldJob?.cancel()
            stickSeekHoldJob = null
            // 电视版：按下到抬起不到 500ms → 只跳一次 10 秒
            //（超过 500ms 的那档已经在加速循环里按 30 秒连跳过了）
            if (!stickSeekAccel) {
                val dir = stickSeekDir
                seekBy(if (dir > 0) 10_000L else -10_000L)
                Log.i(TAG, "摇杆短推 → ${if (dir > 0) "快进" else "快退"} 10 秒")
            }
            stickSeekDir = 0
            stickSeekAccel = false
            armSeekHudDismiss()
            return
        }
        if (stickSeekDir != 0 || mag < 0.7f) return           // 这一次推杆已经在处理
        val dir = if (sx > 0f) 1 else -1
        stickSeekDir = dir
        stickSeekAccel = false
        showSeekHud()
        stickSeekHoldJob = scope.launch {
            kotlinx.coroutines.delay(500L)                    // 电视版：500ms 内抬手就只跳一次
            stickSeekAccel = true
            while (stickSeekDir == dir) {   // job 被 cancel 时 delay 会抛出并结束循环
                seekBy(dir * 30_000L)                         // 电视版长按：每 200ms 跳 30 秒
                if (!ctl.hasEngine()) break
                // 电视版：长按时暂停会自动续播（同样走公共控制层，别碰 ExoPlayer 对象）
                if (!ctl.isPlaying()) ctl.togglePlayPause()
                armSeekHudDismiss()
                kotlinx.coroutines.delay(200L)
            }
        }
    }

    /** 亮出快进快退进度条（控制条展开时不亮，与电视版一致），并重置 5 秒隐退计时 */
    private fun showSeekHud() {
        /*
         * 控制条开着时也要显示（父亲 2026-10-07）。
         * 电视版是控制条展开就收起来，但 VR 里光柱不在控制条上时摇杆照样快进快退，
         * 这时没有进度条就没有反馈 —— 所以不跟电视版这一条，照常显示。
         */
        val v = danmakuView
        if (v == null) {
            /* 父亲 2026-10-16：网页流快进快退时银幕上没有进度条 —— 这条日志用来判断
             * 是"承载进度条的层没建起来"还是"建了但没画上去" */
            Log.w(TAG, "银幕进度条没显示：承载层未建立（danmakuView 为空）")
            return
        }
        seekHudShown = true
        /*
         * 进度条画在弹幕层上，而"收起 UI"会把那一层一起收掉 ——
         * 于是光柱对着银幕推摇杆快进快退时，银幕上什么反馈都没有
         * （父亲 2026-10-16）。显示期间临时把这一层放出来。
         */
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)
        /*
         * 数据走播放接口层（父亲 2026-10-08）。
         * 原来这里直接读系统播放器对象，内核模式下它是空的 —— 银幕上那条进度条
         * 就是「屏幕上的进度条也有问题」那条反馈的根子。
         */
        v.setSeekHud(true, ctl.positionMs(), ctl.durationMs(), ctl.bufferedMs())
        if (seekHudTickJob?.isActive != true) {
            seekHudTickJob = scope.launch {
                while (true) {
                    danmakuView?.setSeekHud(
                        true,
                        ctl.positionMs(),
                        ctl.durationMs(),
                        ctl.bufferedMs(),
                    )
                    /*
                     * 刷新节奏与控制条上的进度条对齐（父亲 2026-10-09：
                     * "跳动的距离和频率要和控制条上的进度条同步"）。
                     *
                     * 两条读的是同一个位置数据，差别在频率：
                     *   控制条那条由 startOsdTicker 每 **1000ms** 刷一次；
                     *   银幕这条原来每 **200ms** 刷一次 —— 于是银幕上的光标跳得密、
                     *   控制条上的稳，看上去就是"不同步"。
                     * 改成同一节奏；每次快进快退那一刻仍会立刻把目标位置写上去
                     * （见 seekBy 里的 setSeekHud(target)），所以响应不打折。
                     */
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }
        armSeekHudDismiss()
    }

    /** 每次操作（含长按连发）都重置隐退计时：最后一次操作后 5 秒收起（电视版同款） */
    private fun armSeekHudDismiss() {
        seekHudHideJob?.cancel()
        seekHudHideJob = scope.launch {
            kotlinx.coroutines.delay(5000L)
            hideSeekHud()
        }
    }

    private fun hideSeekHud() {
        seekHudHideJob?.cancel()
        seekHudHideJob = null
        seekHudTickJob?.cancel()
        seekHudTickJob = null
        if (!seekHudShown) return
        seekHudShown = false
        danmakuView?.setSeekHud(false, 0L, 0L, 0L)
        /* 进度条撤了就把层还回去：控制条收着的时候弹幕层也该收着，否则弹幕自己冒出来 */
        if (!osdVisible) com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(false)
    }

    /** 控制条显隐（VR 侧画不画那块面板） */
    private fun setOsdVisible(visible: Boolean) {
        /*
         * 控制条不禁用（父亲 2026-10-13 定）：银幕上放网页时控制条照样能出来 ——
         * 开关的手势改成了「光柱指大银幕 + 按住侧握键 + 扣扳机」（原生侧判定），
         * 所以这里不再按"网页模式"把控制条挡掉。
         */
        osdVisible = visible
        com.xxxx.emby_vr.vr.VrNative.setOsdVisible(visible)
        // 控制条收起来时菜单一起收（父亲 2026-10-06：菜单挂在控制条上）
        if (!visible) closeMenu()
        Log.i(TAG, if (visible) "控制条显示" else "控制条隐藏")
    }

    /**
     * 点网络视频的站点图标 = 把那个网站的网页整块放到大银幕上（父亲 2026-10-13 定）。
     *
     * 海报墙不动、留在网络视频详情页；网页在"网页面"那块离屏画面上渲染，银幕取样它。
     * 这里**不**把应用切进播放态 —— 切进去的话点击和摇杆会被播放器语义吃掉，
     * 网页在大银幕上就操作不了了（父亲报的"投射后手柄无法操作网页"）。
     */
    /**
     * 清掉控制条上属于「上一部影片」的信息（父亲 2026-10-13 报的"该清掉的没清掉"）。
     *
     * 为什么必须单独清一次：`stopPlaybackInternal` 有意**不**动控制条数据
     * （切集 / 换音轨都要靠它保持按钮可用，2026-10-07 定的），只有 `clearForNewMedia`
     * （换一部新片起播）才清。打开网页这条路既不换片、也不再起播，于是控制条留着
     * 上一部片的已播时间、总时长、进度条位置、按钮亮灭 —— 片名虽然被
     * 「正在播放：哔哩哔哩」顶掉了，可那一行之外的东西全是上一部片的。
     * 音轨 / 字幕清单同理（那是上一部片的轨，网页模式下点了也没用）。
     */
    /**
     * 网页流接管后往控制条写「站点-片名」（父亲 2026-10-16）。
     * 片名由扩展从页面标题带过来；站点名按接管来源取（B站 / 腾讯 / 爱奇艺…）。
     */
    /**
     * 控制条标题里的分辨率尾巴。
     *
     * 内核这条链（网络视频 / 杜比 P5 / 图形字幕）只认内核报出的片源尺寸，
     * 实际显示宽度按画质档位算：写成「实际 W×H（原 片源 W×H）」，
     * 一眼能看出切档生效了没有、有没有被降分辨率。
     */
    private fun resSuffix(): String {
        val stereo = com.xxxx.emby_vr.vr.VrTuning.stereo3dOn
        val isWeb = webPlayingNow || webStreamSite != null
        val ks = try { mpvBackend?.videoSize() } catch (_: Throwable) { null }
        val kernelW = ks?.first ?: 0
        val kernelH = ks?.second ?: 0
        val kernelNow = kernelW >= 64 && kernelH >= 64
        /*
         * 源与「当前实际」分开取：
         *  · 内核在跑（网络视频 / 杜比 P5 / 图形字幕）→ 片源与当前都以内核报的为准；
         *  · 系统播放器（Emby 普通片、3D 播普通片）→ 当前取播放器正在解的那一路，
         *    片源取探片源时的记录（服务端转码时两者会不同）。
         */
        val srcW: Int
        val srcH: Int
        var curW: Int
        var curH: Int
        if (kernelNow) {
            srcW = kernelW
            srcH = kernelH
            curW = kernelW
            curH = kernelH
        } else {
            srcW = com.xxxx.emby_vr.player.PlaybackFlags.videoWidth
            srcH = com.xxxx.emby_vr.player.PlaybackFlags.videoHeight
            val vf = try { player?.videoFormat } catch (_: Throwable) { null }
            curW = vf?.width?.takeIf { it > 0 } ?: srcW
            curH = vf?.height?.takeIf { it > 0 } ?: srcH
        }
        if (curW <= 0 || curH <= 0) return ""
        /*
         * 实际宽度要按档位收一次 —— 只有两种情况档位的值表示宽度：
         * 内核这条路，以及 3D（每眼宽度）。Emby 普通片非 3D 时档位是码率，不能往里套。
         * 3D 走系统播放器时视频层也会被压到每眼宽度，原来这里没算，控制条就把片源
         * 3840×1608 当成实际值显示。
         */
        if (kernelNow || stereo) {
            val tier = com.xxxx.emby_vr.panel.qualityStepsFor(
                kernelActive = kernelNow,
                isWeb = isWeb,
                isP5 = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource,
                stereo3d = stereo,
            ).entries.getOrNull(qualityIndex)?.first ?: 0
            if (tier >= 64 && tier < curW) {
                curH = (curH.toLong() * tier / curW).toInt().coerceAtLeast(64)
                curW = tier
            }
        }
        /*
         * 显示口径（父亲 2026-11-05 定）：始终写成「实际 W×H（原 片源 W×H）」。
         * 片源只有 720 / 1080 时三个档位实际上限都一样，括号两边相同 ——
         * 相同也照写，看得出来"切了没变化"是因为片源就这么多，不是切档没生效。
         */
        return if (srcW >= 64 && srcH >= 64) " ${curW}×${curH}（原 ${srcW}×${srcH}）"
        else " ${curW}×${curH}"
    }

    private fun applyWebStreamTitle(obj: org.json.JSONObject?) {
        if (obj == null) return
        val siteName = WEB_SITE_NAMES.getOrElse(webStreamSite ?: -1) { "网页" }
        val epName = obj.optString("title", "").trim()
        /*
         * 【2026-10-21 16:25 父亲定：控制条只显示片名，不再挂站点前缀】
         *
         * 原来无论有没有片名都拼成「爱奇艺-蒸蒸日上…」，页面标题里的营销词
         * （高清完整版 / 视频在线观看 / 电视剧全集）也一起显示出来。
         * 现在片名由页面侧清洗并拼好（剧名 第X季 第X集 集名），这里原样用；
         * 只有实在没有片名时才退回站点名，避免控制条空着。
         */
        val t = if (epName.isEmpty()) siteName else epName
        /*
         * 【2026-10-16 父亲定的口径：不分优先级，正在播什么就显示什么】
         *
         * 原来网页流走 webSiteName 这个独立字段，而控制条标题里它优先级最高 ——
         * 于是"在放自己的片子、标题还挂着 B站 名字"这种错位必然会发生。
         * 现在只有**一个**标题字段：谁在播就写谁，控制条直接显示它。
         * webSiteName 保留只为判断"银幕上是不是网页"，不再参与标题。
         */
        webTitleBase = t
        osdState.title = t + resSuffix()
        osdState.webSiteName = t
        Log.i(TAG, "控制条标题（网页流）→ $t" + resSuffix())
    }

    private fun clearFilmInfoFromOsd() {
        osdState.hasPlayback = false
        osdState.title = ""
        osdState.webSiteName = ""
        osdState.durationMs = 0L
        osdState.positionMs = 0L
        osdState.bufferedMs = 0L
        osdState.showSkipIntroButton = false
        currentStreams = emptyList()
        audioStreamIndices = emptyList()
        subtitleStreamIndices = emptyList()
        introStartMs = null
        introEndMs = null
        hasAutoSkippedIntro = false
    }

    /**
     * 【2026-10-22 修 P0：网页流的状态没有"统一复位"这一处，于是到处串】
     *
     * 病根：`webPlayingNow` 只有起播处赋值（startPlayer），`webTitleBase` 只有"让位回网页"
     * 才清。于是"播着 B站 去点腾讯图标"、"播着网页流按停止"这两种操作之后：
     *   · 每秒的标题刷新还认为在放网页流 → 用旧片名把控制条写回去（串片文案）；
     *   · 选集/信息菜单还走"网站视频"分支被清空（以为还在播）；
     *   · `webPreview` 还是上一站那一集 → 控制条播放键起播上一站的内容（串片）；
     *   · `webHiddenByStream` + `webStreamSite` 还留着 → 之后按返回键被"让位回网页"带走。
     * 这里一次全清，凡是"这一轮网页播放结束"的地方都调它。
     */
    private fun resetWebStreamState(reason: String) {
        val had = webPlayingNow || webTitleBase.isNotEmpty() || webPreview != null ||
            webHiddenByStream || webStreamSite != null
        webPlayingNow = false
        webTitleBase = ""
        webHiddenByStream = false
        webStreamSite = null
        webSiteBeforeStream = null
        webReturnUrl = null
        webPreview = null
        webAutoPlayPending = false
        webAutoPlayAt = 0L
        webVid = ""
        webCid = 0L
        webSubtitleUrl = ""
        webDanmakuLoaded = false
        webCandSrc = null
        webCandIdx = -1
        osdState.upcoming = false
        if (had) Log.i(TAG, "网页流状态已复位（$reason）")
    }

    private fun openWeb(site: Int) {
        runOnUiThread {
            /*
             * 大银幕这一路同时只能有一个来源（父亲 2026-10-13 实测报的）：
             * 先在放 Emby 片子、再点站点图标时，片子必须**停掉** —— 否则它还在后台
             * 出声，网页和片子两个声音一起响。停之前照常上报进度（服务端记住看到哪儿）。
             */
            if (renderer.videoActive) {
                Log.i(TAG, "开网页：先把正在播的片子停掉")
                stopPlayback()
            }
            /*
             * 【2026-10-22 修 P0】切站/开网页一律先把"上一轮网页播放"的状态清干净：
             * 否则控制条会带着上一部片名、返回键会把人带回上一站那一集。
             */
            resetWebStreamState("点站点图标：${WEB_SITE_NAMES.getOrElse(site) { site.toString() }}")
            /* 控制条上属于影片的信息全部清掉（父亲 2026-10-13）——见上面的函数注释 */
            clearFilmInfoFromOsd()
            /*
             * 银幕比例复位成它自己的形状 16:9（父亲 2026-10-13 定：
             * 网页投上大银幕要按大银幕的高宽比，不跟上一部影片走）。
             *
             * 银幕的高度是按 videoAspect 算的（宽 / 比例），播片时会被改成片子真实比例
             * （2.39:1 宽银幕、4:3 老片）。复位这一步原来只在"换片"那条路
             * （clearForNewMedia）里有，打开网页这条路漏了 —— 于是先看了一部宽银幕的片子
             * 再开 B 站，银幕还是 2.39:1 的形状，16:9 的网页画面贴上去被横向拉扁。
             * 网页面画布固定 1920×1080，银幕固有形状也是 16:9，两边一致。
             */
            applyScreenAspect(16f / 9f, "网页面铺上银幕")
            webStartUrlState.value = null      /* 从图标进站点 = 站点首页 */
            webSiteState.value = site
            webOnScreen = true
            /* 网页在屏 = 「更多」里的 3D 设置由灰变实（父亲 2026-11-02） */
            menuState.webPlaying = true
            /*
             * 控制条标题行显示「正在播放：哔哩哔哩」（父亲 2026-10-13）。
             *
             * 网页不是 Emby 片源，hasPlayback 是 false，所以以前控制条在这时候写的是空的，
             * 看着像"什么都没在播"，而声音明明是网页在放。
             */
            osdState.webSiteName = WEB_SITE_NAMES.getOrElse(site) { "网页" }
            com.xxxx.emby_vr.vr.VrNative.setScreenWeb(true)
            Log.i(TAG, "网页面 → 大银幕（站点 $site）")
        }
    }

    /**
     * 网页面收摊：被一部 Emby 片子顶掉时整个销毁。
     *
     * 父亲 2026-10-13 定：不留后台、也不暂停在后台，直接退出 —— 免得两个声音一起响。
     */
    private fun closeWeb() {
        /*
         * 【2026-10-16 父亲实测：播 Emby 片子时控制条还写着"正在播放：哔哩哔哩"】
         *
         * 控制条标题网页那一路优先级最高（PlayerOsd：webSiteName 非空就盖过片名），
         * 而这里原来是"网页没开着就直接返回"，返回时**没清标题** ——
         * 于是上一部网页片子留下的站名一直挂在控制条上，明明在放自己的片子。
         * 清标题这一步必须在提前返回之前做。
         */
        osdState.webSiteName = ""
        if (!webOnScreen && webSiteState.value == null) return
        runOnUiThread {
            webSiteState.value = null
            webOnScreen = false
            menuState.webPlaying = false
            /*
             * 【2026-10-16 头显实测定案：这里原来有一句 webHiddenByStream = false，是真凶】
             *
             * 接管流程是「先置 webHiddenByStream = true（记下网页是被我们顶掉的），
             * 紧接着调 closeWeb()」—— 于是这一句立刻把刚置好的标记清成 false，
             * 按 B 的判断永远为假，一路落到下面 openWeb(站点) 那一支，
             * 打开的是站点首页。表现出来就是"按 B 回到 B站 首页"（父亲两次实测）。
             *
             * 这个标记的生命周期只该由两件事管：接管时置真、回网页时（returnFromStreamToWeb）
             * 置假。关网页本身不代表用户已经回过网页了。
             */
            osdState.webSiteName = ""
            com.xxxx.emby_vr.vr.VrNative.setScreenWeb(false)
            com.xxxx.emby_vr.vr.VrNative.setWebKeepSampling(false)
            Log.i(TAG, "网页面已关闭（大银幕交回影片）")
        }
    }

    /**
     * 控制条第 1 行右侧的年月日时分秒：**只跟系统时钟有关**（父亲 2026-10-07）。
     *
     * 它不该和播放建立关系，也不该和控制条显隐建立关系 —— 应用一起来就走，
     * 每次对齐到整秒再更新，秒数跳变是准的，不随暂停 / 切片 / 停止而停。
     */
    private var clockJob: kotlinx.coroutines.Job? = null

    private fun startClockTicker() {
        if (clockJob?.isActive == true) return
        clockJob = scope.launch {
            while (true) {
                osdState.nowClock = osdClockFormat.format(java.util.Date())
                kotlinx.coroutines.delay(1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }

    /** 播放进度 → 控制条（每秒刷一次，进度条才走得动） */
    private fun startOsdTicker() {
        osdJob?.cancel()
        osdJob = scope.launch {
            while (renderer.videoActive || picking) {
                /*
                 * 控制条取数统一走播放接口层（父亲 2026-10-08）。
                 *
                 * 原来这里是「系统播放器有值就取、否则取内核」两段分支：每加一处取数要写
                 * 两遍，还漏（银幕进度条就漏了，内核模式下是空的）。现在只有一个来源，
                 * 谁在解码都一样。
                 */
                osdState.playing = ctl.isPlaying()
                osdState.positionMs = ctl.positionMs()
                osdState.bufferedMs = ctl.bufferedMs()
                val d = ctl.durationMs()
                if (d > 0L) osdState.durationMs = d
                // 控制条第一行左侧：正在播放什么（右侧的时间由独立时钟负责）
                refreshOsdTitleFromItem()
                // 弹幕画布要的播放位置：主线程取，画笔线程只读缓存
                refreshPosBase()
                /*
                 * 银幕比例每部片子重算（父亲 2026-10-17 立的规矩）。
                 * 内核拿到第一帧后才会报出真实尺寸；报出来、且与当前用的比例不一样就改。
                 * 这条同时管两件事：网页流那一份地址不带宽高时不再沿用上一部的比例；
                 * Emby 片子自己报的尺寸也和探测值不一致时以真实帧为准。
                 */
                mpvBackend?.videoSize()?.let { (vw, vh) ->
                    /*
                     * 大屏纹理尺寸必须按「切完档的目标宽度」下给 native：每秒再按片源原尺寸
                     * 灌一次，菜单切到 2560 也会被这条立刻顶回 4096。
                     * 非网页路径没有调宽度的余地，按片源真实尺寸走。
                     */
                    var blitW = vw
                    var blitH = vh
                    if (webPlayingNow || webStreamSite != null) run {
                        val cap = webLayerWidthCap()
                        val bw = if (vw > cap) cap else vw
                        val bh = (vh.toLong() * bw / vw).toInt().coerceAtLeast(64)
                        blitW = bw
                        blitH = bh
                        if (bw != webCanvasW || bh != webCanvasH) {
                            webCanvasW = bw
                            webCanvasH = bh
                            runCatching { renderer.setVideoBufferSize(bw, bh) }
                            runCatching { mpvBackend?.setSurfaceSize(bw, bh) }
                            Log.i(TAG, "内核画布按真实尺寸重设 → ${bw}x$bh（内核报 ${vw}x$vh）")
                        }
                    }
                    runCatching { com.xxxx.emby_vr.vr.VrNative.setVideoSize(blitW, blitH) }
                    /*
                     * 尺寸到手就标记"已知"：揭幕要等这个标记（2026-10-20，
                     * 免得第一次接管时把"先按 16:9 建的画面"揭给人看，看着是拉扁的）。
                     */
                    webSizeKnown = true
                    applyScreenAspect(vw.toFloat() / vh.toFloat(), "内核报出画面尺寸 ${vw}x${vh}")
                    /*
                     * 【2026-10-23 00:50 父亲定：播出去也要有结论】
                     * 之前的日志只到"交出去"，看不到"播起来没有、多久出画、真实分辨率多少"。
                     * 网页流第一次报出真实尺寸就是"真的出画面了"，把耗时和比例一起记下来。
                     */
                    val costMs = if (lastWebDeliverAt > 0) System.currentTimeMillis() - lastWebDeliverAt else -1
                    /* 只在分辨率/比例变化时打一行；这个循环每秒跑一次，否则日志被刷满 */
                    if (vw != lastWebSizeW || vh != lastWebSizeH) {
                        lastWebSizeW = vw
                        lastWebSizeH = vh
                        android.util.Log.i(
                            "B0BEmbyVR",
                            "◆起播 站=网页流 分辨率=${vw}x${vh}" +
                                " 比例=" + String.format("%.3f", vw.toFloat() / vh.toFloat()) +
                                " 交付到出画=" + (if (costMs >= 0) costMs.toString() + "ms" else "未记录"),
                        )
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
            osdState.playing = false
        }
        // 片头守望跟控制条一起起步（它只在有片头标记时干活）
        startIntroWatch()
    }

    // ── 片头跳过（父亲 2026-10-12）──────────────────────────────────────────
    /**
     * 片头守望：250ms 看一眼播放位置，决定「跳过片头」按钮显不显示、要不要自动跳。
     *
     * 为什么单独一条协程、而不是搭在控制条那一拍（1 秒）上：
     * 控制条每秒刷一次就够（进度条走得动），但按钮**出现 / 收起**的时机是这个功能的
     * 全部体验 —— 1 秒一拍意味着片头已经播过去了、按钮还挂在条上最多 1 秒，
     * 看着像"按钮坏了"。250ms 与银幕字幕那一拍同频（startPosTicker），
     * 开销可以忽略（只读一次 SharedPreferences 内存值 + 一次位置读取）。
     *
     * 生命周期的判据跟 startOsdTicker 完全一致（renderer.videoActive || picking），
     * 播放停下来自己退，退出前把按钮收掉 —— 不留一颗点到没反应的按钮在条上。
     */
    private fun startIntroWatch() {
        introJob?.cancel()
        introJob = scope.launch {
            while (renderer.videoActive || picking) {
                refreshSkipIntro()
                kotlinx.coroutines.delay(250L)
            }
            osdState.showSkipIntroButton = false
        }
    }

    /** 片头守望的每一拍：落进片头范围就亮按钮（开了自动跳过则直接跳过去） */
    private fun refreshSkipIntro() {
        val start = introStartMs
        val end = introEndMs
        if (start == null || end == null) {
            if (osdState.showSkipIntroButton) osdState.showSkipIntroButton = false
            return
        }
        /*
         * 暂停时**不改显示**（父亲 2026-10-12）：在片头里暂停，那颗按钮照旧留着 ——
         * 一暂停按钮就消失，用户回来还得等它再出现一次。自动跳过同样等恢复播放再做。
         */
        if (!ctl.isPlaying()) return
        /*
         * 画面还没出来（起播等待期，网盘冷启动最长能等十几秒）也**不改显示**：
         * 那时候银幕上是黑幕 /「即将播放」，控制条上多出一颗灰按钮 + 整行重排
         * 只会看着像界面出毛病；等第一帧到了再亮（hasPlayback 就是那个信号）。
         * 自动跳过也一起等 —— 内核（mpv）在文件还没打开时下 seek 可能被丢掉。
         */
        if (!osdState.hasPlayback) return
        val pos = ctl.positionMs()
        when {
            pos < start -> if (osdState.showSkipIntroButton) osdState.showSkipIntroButton = false
            pos < end -> {
                if (uiPrefs.autoSkipIntro) {
                    // 自动跳过：一部片只做一次（手动点过也算做过）
                    if (!hasAutoSkippedIntro) {
                        hasAutoSkippedIntro = true
                        osdState.showSkipIntroButton = false
                        skipIntroTo(end, "片头自动跳过（起点 ${start / 1000}s，位置 ${pos / 1000}s）")
                    }
                } else {
                    osdState.showSkipIntroButton = true
                }
            }
            // 片头结束（含用户手动拖过去）：按钮收起
            else -> if (osdState.showSkipIntroButton) osdState.showSkipIntroButton = false
        }
    }

    /**
     * 跳到片头结束位置 —— 自动跳过与按钮**共用**这一条。
     *
     * 冻结弹幕位置、立刻写控制条位置，都与手工快进（seekBy）同一套：
     * 不冻结的话弹幕会按跳变后的进度自己往前窜一下；不写位置的话
     * 进度条要等下一拍（最多 1 秒）才跟过去。
     */
    private fun skipIntroTo(endMs: Long, why: String) {
        if (!ctl.hasEngine()) return
        freezeDanmakuForSeek()
        ctl.seekTo(endMs)
        osdState.positionMs = endMs
        if (seekHudShown) danmakuView?.setSeekHud(true, endMs, ctl.durationMs(), ctl.bufferedMs())
        Log.i(TAG, "$why → ${endMs / 1000} 秒")
    }

    /** 点「跳过片头」：跳到片头结束，并记成"已跳过"（自动那条不再触发） */
    private fun skipIntroManual() {
        val end = introEndMs ?: return
        if (!ctl.hasEngine()) return
        hasAutoSkippedIntro = true
        osdState.showSkipIntroButton = false
        skipIntroTo(end, "手动跳过片头")
    }

    /** 面板此刻能不能接输入：界面就绪、没在播放 */
    /**
     * 面板能不能接光柱输入。
     *
     * 2026-10-06 父亲要求：播放期间海报墙照样能操作（点、滚、拖、B 返回）。
     * 所以不再因为"在播放"就整块关掉 —— 改由光柱位置决定：光柱指在海报墙上
     * （原生回推 onPanelFocus）时就接，指别处时不接。
     */
    /**
     * 面板能不能接这一下点击（父亲 2026-10-11 修）。
     *
     * 原条件还多一条「视频活跃时必须 `panelPointerOnPanel`」—— 那是 2026-10-04 防误触加的。
     * 实测（父亲 2026-10-11）：**内核播放时海报墙点不动**，日志里连"光柱点击被丢弃"都没有，
     * 说明被这条静默挡住了：原生在内核这条路上不推那个指针标志。
     * 而原生推 onClick 的前提本来就是"光柱落在面板 / 控制条上"（指着银幕时它走的是
     * "控制条开关"那条动作），所以这里只留「面板已就绪且在交互态」就够。
     */
    private fun panelInputReady(): Boolean =
        ::panel.isInitialized && panel.ready &&
            com.xxxx.emby_vr.vr.VrNative.panelActive

    /**
     * 手柄/按键输入 → UI 动作。
     *
     * ## 与 TV 版 B0BEmby 的交互模型对齐 + VR 手柄适配
     *
     * | 来源 | 语义 |
     * |---|---|
     * | 方向键 / 摇杆 | 移动焦点（同 TV 版） |
     * | 手柄指向（PICO 转成虚拟指针） | 焦点跟随指针（VR 特有） |
     * | 扳机（PICO 发 BTN_TOOL_FINGER + 指针坐标） | 确认 |
     * | 返回键 / 手柄 B | 返回上一层 |
     *
     * 实机结论（2026-10-03 抓包）：PICO 手柄走的是**虚拟指针**通道，
     * 不是 BUTTON_A，详见 [InputRouter.onTouchEvent]。
     */
    private val input = InputRouter { action ->
        when (action) {
            /*
             * 方向类动作有两条来源，都归一到面板的方向键（父亲 2026-10-04 定）：
             *   1) 摇杆真走 joystick 轴事件（InputRouter.onGenericMotion，带死区+重复）
             *   2) 摇杆/手柄被系统合成成「按下+拖动+抬起」（走 PanelLayer.dispatch）
             * 播放中方向键仍然是快进快退，不喂给面板。
             */
            InputRouter.Action.LEFT -> {
                if (renderer.videoActive) seekBy(-10_000) else panelKey(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            InputRouter.Action.RIGHT -> {
                if (renderer.videoActive) seekBy(+10_000) else panelKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            InputRouter.Action.UP -> {
                if (!renderer.videoActive) panelKey(KeyEvent.KEYCODE_DPAD_UP)
            }
            InputRouter.Action.DOWN -> {
                if (!renderer.videoActive) panelKey(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            InputRouter.Action.CONFIRM -> {
                /*
                 * 只在播放中有效（扳机 = 暂停/继续）。
                 *
                 * 非播放时**不给面板发 OK**：新语义里扳机是「鼠标左键」——
                 * 指着哪里就激活哪里（由 PanelLayer 用光标位置发鼠标点击实现）。
                 * 若在这里再补一个 OK，就会出现「指着空白处扣扳机却激活了当前焦点元素」，
                 * 与鼠标语义冲突（父亲 2026-10-04 定）。
                 *
                 * 2026-10-06 补：播放中光柱指在海报墙上时，这一下是"点海报墙"，
                 * 不能再当成播放/暂停（否则点海报把片子按停了）。
                 */
                /*
                 * 2026-10-06 晚再收紧（父亲实测：详情页、媒体库页扣扳机会把片子按停）：
                 * 只要这一下刚刚被海报墙吃下（点了 / 滚了），就绝不再触发播放暂停 ——
                 * 不靠"光柱在不在墙上"这个可能滞后的判断兜底。
                 */
                if (renderer.videoActive && !panelPointerOnPanel && !panelTouchedRecently()) {
                    togglePlayPause()
                }
            }
            InputRouter.Action.BACK -> {
                /*
                 * 播放中：光柱指在海报墙上时，B 给面板导航栈（父亲 2026-10-06：
                 * 播放期间海报墙也要能操作）；指别处时 B = 退出播放。
                 */
                /*
                 * 父亲 2026-10-06：B 键只管面板返回，不再顺手把片子退掉 ——
                 * 播放中要退出，用控制条上的「退出」按钮。
                 */
                /*
                 * 【2026-10-16 头显实测定案】手柄 B 键走的是**这一路**，不是系统返回键
                 * （mapKey 把 KEYCODE_BUTTON_B 也映射成 BACK）—— 所以只在系统返回键上加
                 * 「回网页」是永远不生效的，实测按了还是落回网页首面。
                 *
                 * 网页被我们自己的播放器顶掉时：B 的含义是「回网页」，不是「面板返回」
                 * （panel.back() 会把面板导航栈退回根页面，表现出来就是回到 B站 首页）。
                 */
                /*
                 * 【2026-10-17 22:53 父亲实测：播自己的片子时对着银幕按 B 跳回了 B站 网页】
                 * 与光柱那条 B 键同一个病根：`webHiddenByStream` 是"网页流被顶掉"的标记，
                 * 播 Emby 片子时它还留着上一部 B站 的 true。两道闸一起上：既要在放网页流
                 * （`webStreamSite` 非空），也要网页真的被顶掉。
                 */
                if (webHiddenByStream && webStreamSite != null) {
                    /*
                     * 大银幕上是我们自己的播放器 → B = 回网页（回到起播前那一页）。
                     */
                    returnFromStreamToWeb()
                } else if (webOnScreen && !panelPointerOnPanel) {
                    /*
                     * 大银幕上是网页 → B = 交给网页自己的返回（有上一页就退一层，
                     * 已经在首页就停住）。注意**不能走 panel.back()**：那是面板的
                     * 导航栈，它的"上一页"是面板根页面（B站 首页），不是网页的上一页。
                     * 父亲 2026-10-16 明确要按这两种情况分开。
                     */
                    val wentBack = com.xxxx.emby_vr.web.WebPageHub.back()
                    Log.i(TAG, if (wentBack) "B 键交给网页：退回上一页" else "B 键忽略：网页已无上一页")
                } else if (::panel.isInitialized && panel.ready &&
                    com.xxxx.emby_vr.vr.VrNative.panelActive) {
                    panel.back()
                }
            }
            InputRouter.Action.PLAY_PAUSE ->
                if (renderer.videoActive && !panelTouchedRecently()) togglePlayPause()
            InputRouter.Action.SEEK_BACK -> if (renderer.videoActive) seekBy(-10_000)
            InputRouter.Action.SEEK_FORWARD -> if (renderer.videoActive) seekBy(+10_000)
            else -> { /* 余下动作后续接 */ }
        }
    }

    /**
     * 把网页片子自带的字幕拉下来，挂到自绘弹幕层（父亲 2026-10-16）。
     *
     * 为什么走弹幕层而不是当普通字幕：B站 的字幕是"每句时间 + 文字"的 JSON，
     * 而弹幕层本来就吃"时间轴 + 文字"这种结构，挂上去之后控制条上的
     * **字号、提前/延后**这两个开关天然生效（它们作用在弹幕层上），
     * 字幕菜单里也会多出这一条。
     */
    /**
     * 拉 B站 的滚动评论（弹幕），挂到自绘弹幕层（父亲 2026-10-16）。
     *
     * 接口：api.bilibili.com/x/v1/dm/list.so?oid=<片子编号>，返回 XML，
     * 每条形如 <d p="出现秒,模式,字号,颜色,…">文字</d>；
     * 模式 1/2/3 是从右往左滚，4 是底部固定，5 是顶部固定。
     *
     * 走弹幕层而不是播放器字幕：控制条上的字号、提前/延后本来就作用在弹幕层上，
     * 挂上去这三样天然生效。
     */
    /*
     * 【2026-10-20 19:36 腾讯弹幕接入（父亲要求）】
     *
     * 两个站的入口不一样：
     *   B站  → 一个公开接口按片号（cid）拉一个 XML；
     *   腾讯 → 先拉一份分段目录（每 30 秒一段），再逐段拉那一小段里的弹幕（实测 .18 上直接可取，
     *          只需带 Referer，不需要登录）。
     * 后半段"怎么分行、怎么丢溢出"完全共用，跟 Emby 那条路的口径一致。
     */
    private fun loadWebDanmaku(cid: Long) {
        val vid = webVid
        /*
         * 爱奇艺这条路不经过 cid/vid：片号 tvid 是从取流接口查询串里记下来的
         * （2026-10-21 13:30 实测：不改这里的话，爱奇艺接管后弹幕一条都不会去拉）。
         */
        val iqTvid = com.xxxx.emby_vr.web.IqiyiDash.tvid
        if (cid <= 0L && vid.isBlank() && !(webStreamSite == 2 && iqTvid.isNotBlank())) return
        scope.launch {
            val track = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    /*
                     * 【2026-10-21 13:25 爱奇艺弹幕】
                     *
                     * 接口（我们服务端那套一直在用，见 scripts/danmaku/iqiyi_fetch.py）：
                     *   https://cmts.iqiyi.com/bullet/<tvid[-4:-2]>/<tvid[-2:]>/<tvid>_300_<n>.z
                     * 每片 300 秒、zlib 压缩 XML、n 从 1 开始，超出片长返回 404。
                     * 片号 tvid 从取流接口查询串里来（IqiyiDash.noteTvid）。
                     * `<showTime>` 是毫秒时间点，直接用。
                     */
                    if (webStreamSite == 2 && com.xxxx.emby_vr.web.IqiyiDash.tvid.isNotBlank()) {
                        val tv = com.xxxx.emby_vr.web.IqiyiDash.tvid
                        val parsedIq = ArrayList<Triple<Long, String, Long>>()
                        var segsOk = 0
                        for (n in 1..40) {
                            val bytes = try {
                                val conn = java.net.URL(
                                    "https://cmts.iqiyi.com/bullet/" + tv.takeLast(4).take(2) + "/" +
                                        tv.takeLast(2) + "/" + tv + "_300_" + n + ".z",
                                ).openConnection()
                                conn.setRequestProperty("Referer", "https://www.iqiyi.com/")
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                conn.connectTimeout = 8000
                                conn.readTimeout = 8000
                                conn.inputStream.use { it.readBytes() }
                            } catch (t: Throwable) {
                                break
                            }
                            val xml = try {
                                val inf = java.util.zip.Inflater()
                                inf.setInput(bytes)
                                val out = java.io.ByteArrayOutputStream(bytes.size * 4)
                                val buf = ByteArray(8192)
                                while (!inf.finished()) {
                                    val k = inf.inflate(buf)
                                    if (k == 0) break
                                    out.write(buf, 0, k)
                                }
                                inf.end()
                                String(out.toByteArray(), Charsets.UTF_8)
                            } catch (t: Throwable) {
                                break
                            }
                            val blocks = Regex("<bulletInfo>(.*?)</bulletInfo>", RegexOption.DOT_MATCHES_ALL)
                                .findAll(xml)
                            var got = 0
                            for (b in blocks) {
                                val blk = b.groupValues[1]
                                val mc = Regex("<content>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                                    .find(blk)?.groupValues?.get(1) ?: continue
                                val mt = Regex("<showTime>(\\d+)</showTime>")
                                    .find(blk)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                                val txt = mc.trim()
                                if (txt.isEmpty()) continue
                                /*
                                 * 爱奇艺的 <showTime> 是**秒**，不是毫秒（2026-10-26 实测：
                                 * 第1段 7~297、第2段 302~600、第5段 1202~1498，是整片的绝对秒数）。
                                 * 按毫秒用的话全片弹幕会被挤在前 0.3 秒内，两行只排进 6 条、
                                 * 其余 1854 条被当"行满"丢掉 —— 这就是爱奇艺看不见弹幕的原因。
                                 */
                                val ms = if (mt < 10000L) mt * 1000L else mt
                                parsedIq.add(Triple(ms, txt, 16777215L))
                                got++
                            }
                            if (got > 0) segsOk++
                        }
                        parsedIq.sortBy { it.first }
                        android.util.Log.i(
                            TAG,
                            "⑦ 爱奇艺弹幕已拉到 ${parsedIq.size} 条（片号 $tv，分片 $segsOk）",
                        )
                        return@withContext buildDanmakuTrack(parsedIq)
                    }
                    if (vid.isNotBlank()) {
                        /* —— 腾讯这一支：分段目录 → 逐段拉 —— */
                        val parsed = ArrayList<Triple<Long, String, Long>>()
                        val base = org.json.JSONObject(
                            httpGetText("https://dm.video.qq.com/barrage/base/" + vid),
                        )
                        val segIdx = base.optJSONObject("segment_index")
                        /* 段按起点排好再取：JSON 的键序不保证，先 take 会漏掉靠前的段 */
                        val segs = ArrayList<Pair<Long, String>>()
                        if (segIdx != null) {
                            val keys = segIdx.keys()
                            while (keys.hasNext()) {
                                val seg = segIdx.optJSONObject(keys.next()) ?: continue
                                val nm = seg.optString("segment_name")
                                if (nm.isBlank()) continue
                                val start = seg.optString("segment_start").toLongOrNull()
                                    ?: nm.split("/").getOrNull(2)?.toLongOrNull() ?: 0L
                                segs.add(start to nm)
                            }
                        }
                        segs.sortBy { it.first }
                        /* 只拉前 2 小时的分段：一集电视剧够了，避免一上来几百个请求 */
                        for ((segStart, nm) in segs.take(240)) {
                            val obj = runCatching {
                                org.json.JSONObject(
                                    httpGetText("https://dm.video.qq.com/barrage/segment/" + vid + "/" + nm),
                                )
                            }.getOrNull() ?: continue
                            val list = obj.optJSONArray("barrage_list") ?: continue
                            for (i in 0 until list.length()) {
                                val it0 = list.optJSONObject(i) ?: continue
                                val txt = it0.optString("content").trim()
                                if (txt.isEmpty()) continue
                                val offMs = it0.optString("time_offset").toLongOrNull() ?: 0L
                                /*
                                 * 【2026-10-20 22:10 父亲反馈"腾讯弹幕时间线对不上"，实测定案】
                                 * time_offset 是**整集绝对毫秒**，不是段内偏移：实测片号
                                 * z4102y0l2x7 的 t/v1/60000/90000 段，offset 全落在 60000~89000。
                                 * 原来又加了一遍段起点 → 第 N 段的弹幕被推到第 2N 段的位置，越往后越晚。
                                 * 防老接口按段内给：offset 比段起点还小才当段内偏移补起点。
                                 */
                                val at = if (segStart > 0L && offMs < segStart) segStart + offMs else offMs
                                parsed.add(Triple(at, txt, 16777215L))
                            }
                        }
                        parsed.sortBy { it.first }
                        android.util.Log.i(TAG, "⑦ 腾讯弹幕已拉到 ${parsed.size} 条（片号 $vid，分段 ${segs.size}）")
                        return@withContext buildDanmakuTrack(parsed)
                    }
                    val conn = java.net.URL(
                        "https://api.bilibili.com/x/v1/dm/list.so?oid=" + cid,
                    ).openConnection()
                    conn.setRequestProperty("Referer", "https://www.bilibili.com")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    /*
                     * 【2026-10-17 17:26 实测根因】B站 的 dm/list.so 回的是 **raw deflate**
                     * （.18 上直接 curl：16030 字节二进制，zlib -15 解出 37444 字节 XML）。
                     * 原来按文本读 → 全是乱码 → 正则一条都匹配不到 → 日志"弹幕是空的"。
                     * 现在先按 deflate 解，解不开再当明文（防它哪天改回明文）。
                     */
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val raw = try {
                        val inf = java.util.zip.Inflater(true)
                        inf.setInput(bytes)
                        val out = java.io.ByteArrayOutputStream(bytes.size * 4)
                        val buf = ByteArray(8192)
                        var guard = 0
                        while (!inf.finished() && guard++ < 4096) {
                            val n = inf.inflate(buf)
                            if (n == 0) {
                                if (inf.needsInput() || inf.needsDictionary()) break
                            } else out.write(buf, 0, n)
                        }
                        inf.end()
                        String(out.toByteArray(), Charsets.UTF_8)
                    } catch (t: Throwable) {
                        String(bytes, Charsets.UTF_8)
                    }
                    if (raw.isEmpty()) return@withContext null
                    val re = Regex("<d p=\"([^\"]+)\">([^<]*)</d>")
                    /*
                     * 【2026-10-17 18:10 现场定案：B站 的 XML 不按时间排】
                     *
                     * 在播编号 41999863759 实测：3600 条，前 8 条时间是
                     * 484 / 226 / 471 / 267 / 1164 / 1065 / 833 / 839 秒 —— 乱的。
                     * 而画笔按开始时间顺序走、遇到"还没到点"的那条就 `break`
                     * （DanmakuView.onDraw 里那句 `if (item.startMs > nowMs) break`），
                     * 于是第一条（484 秒）就把循环停住 → 屏幕上一条都没有，
                     * 日志原文：`弹幕画笔：… 轨道=3600 本帧画了=0 条`。
                     *
                     * Emby 那条路一直正常，正是因为 AssDanmakuParser 里有
                     * `items.sortBy { it.startMs }`，网页这条路漏了这一步。
                     * 下面先攒成 (时间, 文字, 颜色) 再排序 —— 排完才轮到分行。
                     */
                    val parsed = ArrayList<Triple<Long, String, Long>>()
                    for (m in re.findAll(raw)) {
                        val f = m.groupValues[1].split(',')
                        if (f.size < 4) continue
                        val sec = f[0].toDoubleOrNull() ?: continue
                        val color = f[3].trim().toLongOrNull() ?: 16777215L
                        val text = m.groupValues[2].trim()
                        if (text.isEmpty()) continue
                        parsed.add(Triple((sec * 1000).toLong(), text, color))
                    }
                    parsed.sortBy { it.first }
                    /*
                     * 【2026-10-17 18:30 父亲定：行数照 Emby 那条路来，多出来的丢掉】
                     *
                     * Emby 的弹幕是我们自己生成的（`scripts/danmaku/make_ass.py`），
                     * 生成时写死 **2 行**：`fetch_episode.py` 传 `--lanes 2 --top-margin 15`，
                     * `line_h = 字号 × 1.35`，轨道满了就丢弃多余弹幕
                     * （make_ass.py 原话：`固定行数模式:轨道满则丢弃,保证同屏只有 N 行`），
                     * 同一条轨道上的下一条要等前一条时长的 0.35 倍（`--density-gap 0.35`）。
                     *
                     * 网页这条路原来是"每一条都写在 y=60 那一行"，全挤成一坨。
                     * 这里照 Emby 的规则重排：同样 2 行、同样丢溢出，字号 52 也对齐
                     * （Emby 1080 画布下 max(40, 1080×0.05) = 54，同一档）。
                     * B站 的固定弹幕（模式 4/5）也一并按滚动处理 —— Emby 那条路
                     * 生成的弹幕**全部**是滚动（make_ass.py 只写 \move），口径一致。
                     */
                    return@withContext buildDanmakuTrack(parsed)
                } catch (t: Throwable) {
                    Log.w(TAG, "⑦ 弹幕没拉下来：" + t.message)
                    null
                }
            }
            if (track == null) {
                chainLog("⑧弹幕", "空（这部片子可能没开弹幕，或编号不对）")
                Log.w(TAG, "⑦ 弹幕是空的（这部片子可能没开弹幕，或编号不对）")
                return@launch
            }
            /*
             * 不许"小的顶掉大的"：爱奇艺按片号能拉到上千条，网页那一路按标题查常常只回几条。
             * 两条路都在跑，后到的小轨会把已经挂好的大轨换掉 ——
             * 表现就是"弹幕明明拉到了却不显示"（父亲 2026-10-26 01:07：1860 条被 6 条顶掉）。
             */
            if (danmakuTrack != null && danmakuTrack!!.items.size >= 100 && track.items.size <= 30) {
                Log.i(TAG, "⑦ 小轨跳过：已有 " + danmakuTrack!!.items.size + " 条，这次只有 " + track.items.size + " 条")
                return@launch
            }
            danmakuTrack = track
            webDanmakuLoaded = true
            applyDanmakuSetting()
            Log.i(TAG, "⑦ 弹幕已挂到弹幕层（" + track.items.size + " 条），字号与提前延后跟着生效")
        }
    }


    /**
     * 把 (时间, 文字, 颜色) 列表排成弹幕轨道（2026-10-20 从 B站 那段抽出来，腾讯共用）。
     * 规则：**固定两行**；整部片的弹幕按"两行总容量"均匀抽稀，超过容量就按比例取样。
     * 每条 8 秒、同轨下一条间隔取调参文件里的 `danmaku_gap`（默认 2.0 秒）；
     * 该出现时两行都占着，这一条直接丢掉，不排队（父亲 2026-10-26 09:44 定）。
     */
    private fun buildDanmakuTrack(parsed: List<Triple<Long, String, Long>>): com.xxxx.emby_vr.danmaku.DanmakuTrack? {
        val fs = 52f
        val lineH = fs * 1.35f
        val topMargin = 15f
        val spanMs = 8000L
        /*
         * 父亲 2026-10-26 定：只留两行；要"从全部弹幕里挑"，不是从头部截。
         * 做法：先算这两行在整个时长里最多放得下多少条（capacity），
         * 超过就按比例均匀抽稀 —— 整部片从头到尾都有弹幕，不再出现"前几分钟几十条、后面全丢"。
         */
        /* 间隔走调参文件（vr-tuning.txt 的 danmaku_gap，单位秒）：现场改、不用重装。
           默认 2.0 秒 —— 1.2 秒时同屏两条会挤在一起（父亲 2026-10-26 09:44 定）。 */
        val gapMs = (com.xxxx.emby_vr.vr.VrTuning.danmakuGapSec * 1000f).toLong().coerceIn(600L, 6000L)
        val laneY = floatArrayOf(topMargin, topMargin + lineH)
        /*
         * 一条弹幕从右边缘外飞到左边缘外共用 spanMs，横跨 1920+400 像素，速度固定。
         * 同一条轨道上，后一条要等前一条的**整个身子**走出右边缘才能进，否则就是贴上去（追尾重叠）。
         * 所以真正要等的时长由"前一条有多宽"决定：长弹幕后面天然多等，短弹幕后面少等。
         * 只按固定间隔排（原来 2.5 秒）时，超过约 13 个字的弹幕一定会和后面那条叠在一起
         * （父亲 2026-10-27 报「还是有很多重叠」）。
         */
        val speedPx = (1920f + 400f) / (spanMs / 1000f)          // 像素/秒
        fun textW(t: String): Float {
            var w = 0f
            for (ch in t) w += if (ch.code < 128) fs * 0.45f else fs
            return w
        }
        fun needMs(t: String): Long = maxOf(gapMs, (textW(t) / speedPx * 1000f).toLong())
        val spanMsAll = (parsed.maxOfOrNull { it.first } ?: 0L).coerceAtLeast(1L)
        val avgNeed = if (parsed.isEmpty()) gapMs
                      else parsed.take(3000).map { needMs(it.second) }.average().toLong()
        val capacity = ((spanMsAll / avgNeed) * laneY.size).toInt().coerceAtLeast(1)
        val picked: List<Triple<Long, String, Long>> =
            if (parsed.size <= capacity) parsed
            else {
                val step = parsed.size.toDouble() / capacity
                (0 until capacity).mapNotNull { i -> parsed.getOrNull((i * step).toInt()) }
            }
        val laneFree = LongArray(laneY.size)
        var dropped = 0
        val items = ArrayList<com.xxxx.emby_vr.danmaku.DanmakuItem>()
        for (p in picked) {
            /* 选一条空着的行 */
            var lane = -1
            for (i in laneY.indices) {
                if (laneFree[i] <= p.first) {
                    lane = i
                    break
                }
            }
            /*
             * 行满就丢，不排队（父亲 2026-10-26 09:44 定："丢几条也没关系"）。
             * 挑一行硬接会让后面的弹幕整体往后拖，比丢几条更难接受；
             * 容量那一步（capacity）已经把整片摊平，丢的是局部密集段。
             */
            if (lane < 0) {
                dropped++
                continue
            }
            laneFree[lane] = p.first + needMs(p.second)
            val y = laneY[lane]
            items.add(
                com.xxxx.emby_vr.danmaku.DanmakuItem(
                    startMs = p.first,
                    endMs = p.first + spanMs,
                    text = p.second,
                    isMove = true,
                    x1 = 1920f, y1 = y, x2 = -400f, y2 = y,
                    posX = 960f, posY = y,
                    style = com.xxxx.emby_vr.danmaku.DanmakuStyle(
                        fontSize = fs,
                        primaryColor = (0xFF000000L or (p.third and 0xFFFFFFL)).toInt(),
                        outlineColor = android.graphics.Color.BLACK,
                        outline = 3f,
                        alignment = 2,
                    ),
                ),
            )
        }
        if (items.isEmpty()) return null
        Log.i(
            TAG,
            "⑦ 弹幕按 Emby 那套排成 " + laneY.size + " 行：保留 " + items.size +
                " 条（两行容量 " + capacity + "），行满丢弃 " + dropped +
                " 条（共解析 " + parsed.size + " 条，间隔 " + (gapMs / 1000f) + "s）",
        )
        return com.xxxx.emby_vr.danmaku.DanmakuTrack(1920f, 1080f, items)
    }

    /** 取一段文本（腾讯弹幕那几个接口只要带上 Referer 就能拿） */
    /*
     * 【2026-10-20 22:21 父亲报"腾讯播一会儿就闪退"→ 根因已定：句柄被耗尽】
     *
     * 头显日志（22:12:06）：进程句柄表被撑到上限 32768，Adreno 报
     * `gsl_syncobj_dup_fd ... Too many open files`、`GraphicBuffer failed (Out of memory)`，
     * 紧接着画面传不上显卡、SIGSEGV 闪退。22:01:36 起来、22:12:06 崩，10 分半；
     * 句柄表 4096 → 8192 只用了几分钟 —— 播放时有东西**每帧领一个号不还**（约 50~60 个/秒）。
     *
     * 但 release 包 + 非 root，adb shell 读不到 /proc/<pid>/fd（Permission denied），
     * 只能让应用自己数自己。这里每 10 秒把句柄按目标粗分一次记进日志，
     * 看是哪一类在涨（网络 socket / 显存 dmabuf / 内核 anon_inode / 显卡 kgsl …），
     * 一轮十分钟的播放就能指出漏在哪一层。
     */
    private fun startFdCensus() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(5_000L)
                try {
                    val names = java.io.File("/proc/self/fd").list() ?: continue
                    val buckets = HashMap<String, Int>()
                    for (n in names) {
                        val t = runCatching {
                            android.system.Os.readlink("/proc/self/fd/" + n)
                        }.getOrNull() ?: continue
                        /*
                         * 【2026-10-20 23:16 第一轮普查结论】涨的全是 anon_inode 一类，
                         * 每秒约 52 个（和 4K60 帧率同频），其余类别一动不动。
                         * anon_inode 底下混着 显卡缓冲(dmabuf) / 帧同步(sync_file) / 事件fd 等，
                         * 必须按**原文**细分才能点名，所以这里直接拿 target 原文当桶名。
                         */
                        val k = when {
                            t.startsWith("socket:") -> "网络"
                            t.startsWith("pipe:") -> "管道"
                            t.startsWith("anon_inode:") -> t.take(34)
                            t.contains("kgsl") -> "显卡"
                            t.contains("dmabuf") || t.contains("dma_heap") ||
                                t.contains("/dev/dri") || t.contains("ion") -> "显存"
                            t.contains("ashmem") || t.contains("memfd") -> "共享内存"
                            t.contains("binder") -> "系统调用"
                            else -> "文件/其它"
                        }
                        buckets[k] = (buckets[k] ?: 0) + 1
                    }
                    val top = buckets.entries.sortedByDescending { it.value }.take(6)
                        .joinToString("，") { it.key + " " + it.value }
                    Log.i(TAG, "⑧ 句柄 " + names.size + " 个：" + top)
                } catch (e: Throwable) {
                    Log.w(TAG, "⑧ 句柄普查失败：" + e.message)
                }
            }
        }
    }

    /* 超时可调：弹幕现拉接口第一次可能要拉几十秒（优酷逐段拉），那边给 40 秒；其余调用仍用 10 秒 */
    private fun httpGetText(url: String, timeoutMs: Int = 10000): String {
        val conn = java.net.URL(url).openConnection()
        conn.setRequestProperty("Referer", "https://v.qq.com/")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun loadWebSubtitle(url: String) {
        if (url.isBlank()) return
        /*
         * 弹幕已经挂上了就别再换成对白字幕（父亲 2026-10-16 要的是弹幕）。
         * 两个都来自网页，但弹幕才是他要看的那一层。
         */
        if (webDanmakuLoaded) return
        val want = url
        scope.launch {
            val track = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val conn = java.net.URL(want).openConnection()
                    conn.setRequestProperty("Referer", "https://www.bilibili.com")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val body = org.json.JSONObject(txt).optJSONArray("body")
                        ?: return@withContext null
                    val items = ArrayList<com.xxxx.emby_vr.danmaku.DanmakuItem>()
                    for (i in 0 until body.length()) {
                        val it = body.optJSONObject(i) ?: continue
                        val from = it.optDouble("from", -1.0)
                        val to = it.optDouble("to", -1.0)
                        val c = it.optString("content").replace("\n", " ").trim()
                        if (from < 0 || c.isEmpty()) continue
                        val a = (from * 1000).toLong()
                        val b = if (to > from) (to * 1000).toLong() else a + 4000
                        items.add(
                            com.xxxx.emby_vr.danmaku.DanmakuItem(
                                startMs = a, endMs = b, text = c,
                                isMove = false,
                                x1 = 0f, y1 = 0f, x2 = 0f, y2 = 0f,
                                posX = 960f, posY = 1000f,
                                style = com.xxxx.emby_vr.danmaku.DanmakuStyle(
                                    fontSize = 66f,
                                    primaryColor = android.graphics.Color.WHITE,
                                    outlineColor = android.graphics.Color.BLACK,
                                    outline = 3f,
                                    alignment = 2,
                                ),
                            ),
                        )
                    }
                    if (items.isEmpty()) null
                    else com.xxxx.emby_vr.danmaku.DanmakuTrack(1920f, 1080f, items)
                } catch (t: Throwable) {
                    Log.w(TAG, "⑥ 网页字幕没拉下来：${t.message}")
                    null
                }
            }
            if (track == null) return@launch
            danmakuTrack = track
            applyDanmakuSetting()
            Log.i(TAG, "⑥ 网页字幕已挂到弹幕层（${track.items.size} 句），字号与提前延后跟着生效")
        }
    }

    /**
     * 从"我们自己的播放器"回到网页（父亲 2026-10-16 定的语义）。
     *
     * 网页在接管期间**没有销毁、地址也没动过**，只是从大银幕上收起来了；
     * 所以这里只做三件事：停下播放器、把大银幕交回网页、恢复站点状态。
     * 落点就是起播前那一页 —— 关键是**不能走 panel.back()**，
     * 那会把面板导航栈退回根页面，那才是"回到 B站 首页"的真凶。
     */
    private fun returnFromStreamToWeb() {
        if (!webHiddenByStream) return
        webHiddenByStream = false
        /*
         * 【2026-10-20 18:28 父亲实测：接管后按 B 回网页，网页被压成片子的长宽比】
         *
         * B站 那条路（走 openWeb）一直有下面这行复位，而这条"让位回网页"的路漏了：
         * 银幕还留着刚才那部片子的比例（2.39:1 之类），16:9 的网页画面贴上去就被拉扁。
         * 这里补同一步 —— 网页面固定 16:9，与 openWeb 保持同一个基准。
         */
        applyScreenAspect(16f / 9f, "回网页：银幕复位 16:9")
        /* 三槽一起清掉（2026-10-20 父亲定）：换片时不留上一部的地址，免得串台 */
        runCatching { com.xxxx.emby_vr.web.WebPageHub.clearSlotsHandler?.invoke() }
        /*
         * 攒分片模式退场：把攒段状态清干净（2026-10-18）。
         * 网页面本身**没有被销毁**，所以这里是"接着原来那一页继续选片"，
         * 下一部片子从头攒段，不会把上一部的段接在后面。
         */
        if (webFragMode) {
            Log.i(TAG, "回网页：攒分片模式结束（这一部共攒过 ${webFragSeen.size} 段）")
            webFragMode = false
            webFragSeen.clear()
            webFragPending.clear()
            webFragMinIdx = Int.MAX_VALUE
            webFragMaxIdx = -1
            stopWebAlive()
            /* 回网页 = 这一轮"留着网页"的任务结束，标记复位（2026-10-19） */
            webKeepPageAlive = false
            /* 网页又上银幕了，后台取帧这个开关收掉（银幕自己会取） */
            com.xxxx.emby_vr.vr.VrNative.setWebKeepSampling(false)
        }
        /*
         * 停播放器但**保留控制条**（父亲 2026-10-16 明确：光标对着银幕按 B 从播放状态
         * 回网页时，控制条不用收起来）。
         * 走 keepUi = true 这条：进度上报照旧，只是不收起界面。
         */
        reportPlaybackStopped(ctl.positionMs() * 10_000)
        stopPlaybackInternal(keepUi = true)
        renderer.videoActive = false
        renderer.setHudText("")
        /*
         * 【2026-10-17 真因修复】这里原来只设了站点编号 —— 而网页面在接管时已经被拆掉，
         * 重新建起来时只能载入站点首页（日志：`网页开始载入：about:blank` → `bilibili.com/`），
         * 于是"按 B 回网页"实际是"回到 B站 首页"。现在把接管时记下的那一页交给网页屏。
         */
        /*
         * 【2026-10-17 15:11 父亲定稿：退出接管三清 —— 清成对地址、清控制条显示信息】
         * 成对地址在网页面重建时由扩展天生清空；这里清客户端侧的网页片信息，
         * 控制条回到"未在播放"，下一轮接管从头来。
         */
        webCid = 0L
        webSubtitleUrl = ""
        webDanmakuLoaded = false
        if (danmakuTrack != null) {          /* 网页弹幕轨清掉，回网页选下一部不残留 */
            danmakuTrack = null
            applyDanmakuSetting()
        }
        osdState.title = ""
        osdState.hasPlayback = false
        webTitleBase = ""
        /* 回网页：把"等地址自动起播"的等待一并作废，避免几秒后自己又接管（父亲 2026-10-19 21:15 第 1 条） */
        webAutoPlayPending = false
        webAutoPlayAt = 0L
        webPreview = null
        val back = webReturnUrl
        webStartUrlState.value = back
        webSiteState.value = webSiteBeforeStream
        webOnScreen = true
        com.xxxx.emby_vr.vr.VrNative.setScreenWeb(true)
        Log.i(
            TAG,
            "回网页：从自己的播放器交回网页（按接管时那一页重建：" +
                (back?.takeLast(60) ?: "站点首页") + "）",
        )
    }

    /** 面板按键入口：面板没就绪（还在启动/播放中）时静默忽略 */
    private fun panelKey(keyCode: Int) {
        if (::panel.isInitialized && panel.ready && com.xxxx.emby_vr.vr.VrNative.panelActive) panel.key(keyCode)
    }

    /** 播放期状态反馈：写到视频画面上的状态条（屏幕大字被视频盖住，看不见） */
    private fun hud(text: String) = renderer.setHudText(text)

    /**
     * 指针位置变化（触摸通道 `applyRay`）。正式版里指针只驱动面板光标
     * （dispatchTouchEvent 的面板分支直接用事件坐标），不再喂给渲染器。
     */

    /*
     * 旧的两条摇杆通道（拨动累计位移 onStickMotion、按住扳机拖动 onTriggerDrag）
     * 已在 2026-10-07 删除：播放页快进快退统一走 stickSeek（照电视版复刻）。
     */


    /**
     * 起播指定媒体（面板层点播放时调用）。
     *
     * 链路（与 TV 版一致）：`getPlaybackInfo` → `mediaSources.first()`
     * 的 `directStreamUrl`（没有就用 `transcodingUrl`）→ `${server}/emby<path}` →
     * ExoPlayer 解码 → Surface → 渲染器 OES 纹理贴到虚拟屏。
     */
    private fun playMedia(
        mediaId: String,
        startTicks: Long,
        keepPosition: Boolean = false,
    ) {
        /*
         * 切字幕 / 音轨 / 质量 / 缓冲：从当前位置**回退 10 秒**重新起播
         * （父亲 2026-10-06 晚定：不要从头，也不要正好卡在刚才那一句上）。
         * 下限 0，避免刚开头就倒退成负数。
         */
        val effectiveStart =
            if (keepPosition) {
                /*
                 * 位置必须从**公共控制层**取（2026-10-09 修）。
                 * 原来写的是 `player?.currentPosition ?: 0L` —— 内核（Profile 5）下
                 * player 恒为空 → 取到 0 → **任何重播都从片头开始**（父亲实测）。
                 * ctl 同时支持 mpv 与 ExoPlayer，用它。
                 */
                val from = ((ctl.positionMs() - kReplayBackMs).coerceAtLeast(0L))
                Log.i(TAG, "重播起始位置 ${from / 1000} 秒（当前位置回退 ${kReplayBackMs / 1000} 秒）")
                from * 10_000L
            } else {
                startTicks
            }
        // 换片（不是切字幕 / 音轨那种重播）：先把旧片整个收掉（父亲 2026-10-07）
        if (mediaId != currentMediaId) {
            forceH264 = false
            clearForNewMedia()
        }
        currentMediaId = mediaId
        /*
         * 应用「记住的上次字幕」（父亲 2026-10-10 定）。
         *
         * 必须在这里做、**赶在取播放信息之前**：取播放信息那一步会读
         * forceKernelForImageSubs 来决定用系统播放器还是内核 —— 上次选的是图片字幕，
         * 这一部就得直接走内核，否则又要手动去字幕菜单点一次才触发。
         */
        /*
         * 详情页选过的音轨（父亲 2026-10-28）：key 里带版本 id，
         * 不同版本文件音轨不同，不能互相串。
         */
        runCatching {
            val srcKey = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId.orEmpty()
            val savedAudio = placePrefs.getInt("aud_choice_${mediaId}_$srcKey", -1)
            if (savedAudio >= 0) {
                selectedAudioIndex = savedAudio
                Log.i(TAG, "起播应用记住的音轨：流 $savedAudio（版本 $srcKey）")
            }
        }
        runCatching {
            val mem = restoreSubtitleChoice(mediaId)
            if (mem != null) {
                selectedSubtitleIndex = mem.first
                com.xxxx.emby_vr.player.PlaybackFlags.forceKernelForImageSubs = mem.second
                Log.i(
                    TAG,
                    "起播应用记住的字幕：流 ${mem.first ?: "关闭"}（图片字幕=${mem.second}）",
                )
            }
        }
        /*
         * 父亲 2026-10-06：播放中点海报墙的片子起不来、反而把正在播的暂停了。
         * 原因是这里原来有一句"正在播就切播放/暂停"的老逻辑 —— 那是给面板上的
         * 播放键用的，不该拦起播。现在点谁就播谁，旧片由 startPlayer 里的
         * stopPlaybackInternal 收掉。
         */
        scope.launch {
            try {
                /*
                 * 版本记忆（父亲 2026-10-28）：同一部片在库里有多个版本文件，
                 * 每个版本自带一份字幕/音轨清单（实测《蜘蛛侠》12 个版本，字幕 0～11 条）。
                 * 取流时带上 MediaSourceId，服务端按这个版本出流，客户端拿到的清单也就是这一份 ——
                 * 否则菜单按默认源排，播的却是别的文件，序号必然错位。
                 */
                com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId = versionChoice(mediaId)
                var media = EmbyApi.getPlaybackInfo(
                    context = this@MainActivity,
                    serverUrl = userServer(),
                    apiKey = userToken(),
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    mediaId = mediaId,
                    startTimeTicks = effectiveStart,
                    selectedAudioIndex = selectedAudioIndex,
                    selectedSubtitleIndex = selectedSubtitleIndex,
                    maxStreamingBitrate = bitrateForQuality(),
                    maxWidth = embyTier().maxWidth,
                    maxHeight = embyTier().maxHeight,
                    forceTranscode = embyTier().forceTranscode,
                    disableHevc = forceH264,
                    mediaSourceId = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId,
                )
                currentSources = media.mediaSources.orEmpty()
                var source = pickMediaSource(currentSources)
                /*
                 * 自动挑一条头显能放的原声音轨（父亲 2026-10-07：音画不同步）。
                 *
                 * 《无可替代》这类片子带两条音轨：DTS 6ch（默认）+ AAC 2ch。头显解不了
                 * DTS，服务端就把音频转成 AAC 再和直通的画面拼起来 —— 拼接会让声音和
                 * 画面对不齐，还要实时从网盘拉源，慢起来就超时。
                 * 发现默认那条解不了、源里又带能解的那条时，带索引重新要一次地址：
                 * 服务端直接把原文件发过来，不转码、不卡、音画本来就是对好的。
                 * 用户自己选过音轨就不插手（selectedAudioIndex != null）。
                 */
                if (selectedAudioIndex == null) {
                    pickPlayableAudio(source?.mediaStreams)?.let { alt ->
                        Log.i(TAG, "默认音轨头显解不了 → 改用流 $alt（不转码，音画同步）")
                        selectedAudioIndex = alt
                        media = EmbyApi.getPlaybackInfo(
                            context = this@MainActivity,
                            serverUrl = userServer(),
                            apiKey = userToken(),
                            deviceId = EmbyContent.DEVICE_ID,
                            userId = BuildConfig.EMBY_USER_ID,
                            mediaId = mediaId,
                            startTimeTicks = effectiveStart,
                            selectedAudioIndex = alt,
                            selectedSubtitleIndex = selectedSubtitleIndex,
                            maxStreamingBitrate = bitrateForQuality(),
                            maxWidth = embyTier().maxWidth,
                            maxHeight = embyTier().maxHeight,
                            forceTranscode = embyTier().forceTranscode,
                            disableHevc = forceH264,
                            mediaSourceId = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId,
                        )
                        currentSources = media.mediaSources.orEmpty()
                        source = pickMediaSource(currentSources)
                    }
                }
                // 记下这次播放的身份，供服务端上报（播放历史/继续观看靠它）
                pendingItemId = mediaId
                pendingPlaySessionId = media.playSessionId
                pendingMediaSourceId = source?.id
                pendingSourcePath = source?.path
                pendingRunTimeTicks = source?.runTimeTicks ?: 0L
                /*
                 * 片头范围（父亲 2026-10-12）：从章节标记里认 IntroStart / IntroEnd。
                 *
                 * 在这一步立，是因为**这里是 source 唯一的出处** —— 后面 startPlayer 只管
                 * 起播、拿不到章节；而且这条路（含"默认音轨解不了再要一次地址"那次重取）
                 * 跑完 source 才是最终那份。
                 * 换片 / 换轨都会重新走这里，范围永远跟当前这部片一致。
                 */
                val intro = com.xxxx.emby_vr.util.IntroSkipHelper.detectIntroRange(source?.chapters)
                introStartMs = intro?.first
                introEndMs = intro?.second
                hasAutoSkippedIntro = false
                osdState.showSkipIntroButton = false
                if (intro != null) {
                    Log.i(
                        TAG,
                        "片头标记：${intro.first / 1000}s → ${intro.second / 1000}s" +
                            "（自动跳过=${uiPrefs.autoSkipIntro}）",
                    )
                } else {
                    Log.i(TAG, "这一部没有片头标记（章节里没有 IntroStart / IntroEnd）")
                }
                // 字幕 / 音轨菜单要用它列选项（切轨靠 Emby 重新出流）
                currentStreams = source?.mediaStreams ?: emptyList()
                /*
                 * 清单到手的这一刻立刻重算一遍（2026-10-10 修「字幕菜单有时空 / 有时列的是
                 * 别的片子的项 / 点了不生效，过一会又对了」）。
                 *
                 * 根因：currentStreams 要等这条网络请求回来才有值，而列表是**打开菜单那一刻**
                 * 才生成的 —— 在请求回来之前打开：首次起播拿到空表（菜单空），换片拿到上一部片
                 * 的表（项是别的片子的，序号也对不上，点了自然不生效）；等请求回来之后再打开
                 * 就正常了。所以这里在数据到手时主动重建，并把正开着的菜单一起刷新。
                 */
                rebuildTrackMenus()
                /*
                 * 按服务端给的判定挑地址（2026-10-06 父亲报「长宽比不对 / 没声音 / 灰蒙蒙」）。
                 * 原来无脑优先 directStreamUrl，等于把服务端的转码决定（音频转 AAC、
                 * HDR 转 SDR、换封装）全绕过去了。现在照 TV 版那套判定走。
                 */
                val method = Utils.determinePlayMethod(media)
                lastPlayMethod = method
                /*
                 * 杜比视界片不走服务端转码（父亲 2026-10-11）。
                 *
                 * 服务端硬解杜比会把色彩解丢 → 推过来的流是**偏色**的，而客户端看不出来
                 * （画面有、声音也有，只是颜色不对 —— 机器没法判断"正确颜色"长什么样）。
                 * 所以从源头堵。
                 *
                 * 但不是无脑换直连 —— 先看服务端**为什么**转（父亲 2026-10-11 问
                 * 「你会去看为什么转码吗」）。转码地址自己就写着视频要不要重编码：
                 * `VideoCodec=copy` = 视频原样、只转音频或换封装 → 无害，保持转码；
                 * 视频要重编码（URL 里是 h264/hevc 之类）→ 有偏色风险，换直连。
                 * 真需要转码才播得了的片，由起播看门狗改走自带内核（内核拉原文件）。
                 */
                val dvSource = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource
                /*
                 * 内核直接拉 strm 里写的原始地址（父亲 2026-11-13 定）：
                 * 杜比 P5 必须有每帧元数据，服务端转封装会把元数据段落重排；
                 * 带图形字幕（PGS/VobSub）的片，服务端要烧字幕就得转码，画面还被降分辨率。
                 * 这两种都让内核直连源地址，其余照旧走服务端。
                 */
                val graphicSub = source?.mediaStreams?.any { st ->
                    st.type.equals("Subtitle", true) && (st.codec ?: "").lowercase().let {
                        it.contains("pgs") || it.contains("dvdsub") || it.contains("dvbsub")
                    }
                } ?: false
                val strmPath = source?.path
                val strmDirectUrl = if (
                    (dvSource && com.xxxx.emby_vr.player.PlaybackFlags.useKernelDecoder || graphicSub) &&
                    strmPath?.endsWith(".strm") == true
                ) fetchStrmUrl(strmPath) else null
                val path0 = when {
                    strmDirectUrl != null -> {
                        Log.i(TAG, "走 strm 原始地址：$strmDirectUrl")
                        lastPlayMethod = "DirectPlay"
                        strmDirectUrl
                    }
                    /*
                     * 只有 Profile 5 才换直连：那一版系统解不出画面。
                     * 8.1 / 7 的基础层就是普通 HDR10，照常交给系统播放器，
                     * 强换直连反而让系统解不了、只剩声音（父亲 2026-11-01 实测）。
                     */
                    dvSource && com.xxxx.emby_vr.player.PlaybackFlags.useKernelDecoder &&
                        method != "DirectPlay" -> {
                        /*
                         * 杜比 P5 一律直连原文件（2026-11-13）。
                         *
                         * 原来按 VideoCodec=copy 判断"只换封装就保持转码"，实测这条路
                         * 给的是 1920 宽的 HLS 分片，每帧杜比元数据被重新封装后长度与
                         * 源文件对不上（头显日志里切出来的元数据比源文件多几字节、结尾
                         * 标记不是 0x80），还原按错误场景的曲线跑，颜色随场景漂移。
                         * 直连原文件后元数据与画面对得上，还原链才成立。
                         */
                        Log.i(TAG, "杜比 P5：直连原文件（转封装会丢每帧元数据）")
                        lastPlayMethod = "DirectPlay"
                        "/videos/$mediaId/stream?Static=true&MediaSourceId=${source?.id.orEmpty()}"
                    }
                    method == "DirectPlay" -> source?.directStreamUrl
                    else -> source?.transcodingUrl ?: source?.directStreamUrl
                }
                Log.i(TAG, "播放方式=$method")
                if (path0 == null) {
                    hud("取不到播放地址（服务器未返回直链）")
                    return@launch
                }
                var path = path0
                val url = if (path.startsWith("http")) {
                    path
                } else {
                    // 直链缺 api_key 时补上（Emby 视频直链默认不带 token）
                    if (!path.contains("api_key=")) {
                        path += (if (path.contains("?")) "&" else "?") + "api_key=${userToken()}"
                    }
                    "${userServer()}/emby$path"
                }
                withContext(Dispatchers.Main) {
                    startPlayer(url, mediaId, effectiveStart / 10_000L)
                }
                // 取这一集的详情（剧集 id / 季 id / 简介 / 演员），给选集与信息菜单用
                loadItemDetail()
            } catch (e: Exception) {
                Log.e(TAG, "取播放地址失败", e)
                hud("取播放地址失败：${friendlyError(e)}")
            }
        }
    }

    /** 起播 */
    /**
     * 用我们自己的播放器播"网页抓来的流"（2026-10-16，父亲定的方向：网页只当选片器）。
     *
     * 网页此时已经在播同一个片子 —— 直接切过去会两个声音一起响，所以先 closeWeb()
     * 把网页面收摊（与"Emby 片子顶掉网页"同一套处理）。
     *
     * 凭证：请求头带 Referer（页面地址，站点用它校验来路）+ 浏览器的 User-Agent；
     * 会员片再加网页里取到的 Cookie（父亲的会员身份就在这一条里）。
     *
     * 兜底：8 秒后内核还没出画面，说明这条流播不动（地址过期 / 缺凭证 / 编码不支持），
     * 打一条日志并提示，父亲不会停在黑屏上猜。
     */
    /* 网页流候选：一条播不动就换下一条（父亲 2026-10-16 定） */
    private var webCandSrc: org.json.JSONObject? = null
    private var webCandIdx = -1
    private val webCandHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /*
     * 攒分片模式（2026-10-18，父亲口径：先把 1080P 搞定）。
     *
     * 腾讯这种站没有整片地址也没有播放列表，网页是一小段一小段取的。这条路：
     * 第一段起播我们自己的播放器，后面每来一段接一段，**网页面留在后台继续取新段**
     * （签名跟着段走，页面不停才有新段）。
     */
    private var webFragMode = false
    private val webFragSeen = java.util.HashSet<Int>()
    private var webFragReturnUrl: String? = null
    /** 起播前先到达、还没能接上去的分片（播放器一起来就补接） */
    private val webFragPending = java.util.ArrayDeque<String>()
    /** 这一部片子见过的分片编号范围（用来判"换片子"） */
    /** 最近一次接上分片的时刻 / 播到段尾后等下一段时的日志节流 */
    @Volatile private var webFragLastAt = 0L
    private var webFragEofLogged = 0L
    private var webFragMinIdx = Int.MAX_VALUE
    private var webFragMaxIdx = -1
    /** 起播时告诉"这一段别销毁网页"（攒分片模式专用） */
    private var webKeepPageAlive = false

    /*
     * 正在播"整集地址"的那一集的片号（2026-10-18 晚）。
     * 起因：父亲看到"总时长 45 分钟闪一下变成 3:25"——整集地址已经进播放器了，
     * 紧接着分片路又把同一集顶掉（分片只能顺播、拖不动）。同一集既已整集在播，就不许分片再接管。
     */
    private var wholePlayingVid: String? = null

    /** 扩展攒到的一批分片（跑在火狐内核的线程上，先切回主线程） */
    private fun onBridgeFrags(obj: org.json.JSONObject) {
        runOnUiThread { handleBridgeFrags(obj) }
    }

    private fun handleBridgeFrags(obj: org.json.JSONObject) {
        val arr = obj.optJSONArray("list") ?: return
        val fresh = ArrayList<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("i", -1)
            val u = o.optString("u")
            if (idx < 0 || u.isBlank()) continue
            if (webFragSeen.add(idx)) fresh.add(idx to u)
        }
        if (fresh.isEmpty()) return
        /*
         * 换片子的判据用**编号往回跳**（2026-10-18 头显实测改的）：
         * 原来按页面编号判，可这个编号是各层各算的 —— 报上来的层一变编号就变，
         * 于是刚起播就被判成"换片子"、从头重来（父亲看到的就是又只播一小段）。
         * 分片编号在一部片子里是递增的，真换片子才会从 0 重来，所以看编号够不够稳。
         */
        val minIdx = fresh.minOf { it.first }
        if (webFragMaxIdx >= 0 && minIdx < webFragMaxIdx - 30) {
            Log.i(TAG, "攒分片：编号从 $webFragMaxIdx 跳回 $minIdx，判为换片子，从头重新攒")
            webFragSeen.clear()
            webFragSeen.addAll(fresh.map { it.first })
            webFragMode = false
            webFragPending.clear()
        }
        for (p in fresh) {
            if (p.first > webFragMaxIdx) webFragMaxIdx = p.first
            if (p.first < webFragMinIdx) webFragMinIdx = p.first
        }
        val frame = obj.optString("frame")
        val cookie = obj.optString("cookie")
        /*
         * 腾讯页面**不走分片路**（2026-10-18 22:11 父亲定）。
         *
         * 理由：腾讯的分片是"一段一签"的试看片段，头显实测内核接上后一条轨道都没有
         * （日志 `内核轨道清单：[]`、`画面参数：格式=-`），大屏全空；而整集地址由扩展在
         * "按下网页全屏"那一刻单独送来（带 `whole=1`）。
         * 这条只对腾讯生效：B站 / 爱奇艺 的分片接力照旧，一行未改。
         */
        /*
         * 腾讯页面**不再走分片路**（2026-10-19 12:05 删净，父亲定"老逻辑全删"）。
         *
         * 分片那一套（记账 + 本机分片播放列表 + 攒够 3 段起播 + 等 12 秒等待）是 10-19 凌晨的
         * 过渡做法 —— 那时手里没有整集清单，只能靠页面一段段喂。现在整集清单（4K，284 段全在）
         * 由页面在播放信息响应里直接给到，整集交给内核即可，分片那套一行都不留。
         * 这条只对腾讯生效：B站 / 爱奇艺 的分片接力照旧，一行未改。
         */
        if (com.xxxx.emby_vr.web.TencentWholeUrl.vidFromFrame(frame) != null) {
            Log.i(TAG, "腾讯页面：分片路已删除，只认整集清单（见 playWebStream）")
            return
        }
        /*
         * 这里原来会"一进腾讯播放页就自己去要整集地址"（2026-10-18 那条路），
         * 2026-10-19 02:24 父亲报「我现在没有点网页全屏，它就接管了」—— 就是它干的：
         * 触发点必须只有"父亲按下网页全屏"这一个（扩展侧那条 whole=1 消息）。
         * 现在整段删除；腾讯只做"分片记账"，起播一律等全屏那一趟。
         */
        /*
         * 同一集已经在播整集地址了，分片（只能顺播、拖不动）不许再顶掉它。
         * 换集时片号不同，自然不会拦，分片路照旧可用。
         */
        val fragVidNow = com.xxxx.emby_vr.web.TencentWholeUrl.vidFromFrame(frame)
        if (fragVidNow != null && fragVidNow == wholePlayingVid) {
            Log.i(TAG, "整集已在播（$fragVidNow），这一站的分片不接管")
            return
        }
        if (!webFragMode) {
            webFragMode = true
            webFragReturnUrl = frame.takeIf { it.isNotBlank() }
            Log.i(
                TAG,
                "攒分片起播：这一站没有整片地址，第 1 段起播（第 ${fresh[0].first} 段），" +
                    "网页留在后台继续取新段，登录票=" + (if (cookie.isNotBlank()) "有" else "无"),
            )
            val headers = org.json.JSONObject()
            try {
                headers.put("Referer", "https://v.qq.com/")
                headers.put("User-Agent", WEB_DESKTOP_UA)
                if (cookie.isNotBlank()) headers.put("Cookie", cookie)
            } catch (e: Exception) {
            }
            val one = org.json.JSONObject()
            val streams = org.json.JSONArray()
            val s0 = org.json.JSONObject()
            s0.put("video", fresh[0].second)
            s0.put("audio", "")
            s0.put("height", 1080)
            s0.put("bw", 0)
            s0.put("headers", headers)
            streams.put(s0)
            one.put("streams", streams)
            one.put("kind", "play")
            one.put("durSec", 0)
            one.put("frame", frame)
            one.put("title", obj.optString("title"))
            one.put("cookie", cookie)
            webKeepPageAlive = true
            /* 记下站点：按 B 要能回到这张网页继续选片（与网页流那条路同一套判据） */
            webStreamSite = webSiteState.value
            /* 起播同时开始按着网页（页面停手就没新段了） */
            startWebAlive()
            try {
                playWebStream(one)
            } finally {
                webKeepPageAlive = false
            }
            for (k in 1 until fresh.size) appendFrag(fresh[k].second)
        } else {
            for (k in 0 until fresh.size) appendFrag(fresh[k].second)
            Log.i(TAG, "接上第 ${fresh[0].first}～${fresh[fresh.size - 1].first} 段（共接 ${fresh.size} 段）")
        }
    }

    /**
     * 把一段接在后面。
     *
     * 起播那几拍播放器还在建（`mpvBackend` 还是空的），这一段先存起来，
     * 等播放器起来再补接 —— 不然父亲按全屏那一刻交上来的那几段会全被丢掉，
     * 表现就是"只播了十几秒"（2026-10-18 头显实测）。
     */
    /**
     * 攒分片时把网页面按在"活跃"上（2026-10-18 头显实测）。
     *
     * 接管播放后网页屏被收起来，火狐内核把会话判成不活跃 —— 页面里的心跳整段消失、
     * 分片不再来，手里那一段播完就回到网页，看着就是"反复播一小段"。
     * 这里每隔几秒按一次，让页面照常往下取。
     */
    private val webAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val webAliveTick = object : Runnable {
        override fun run() {
            if (!webFragMode) return
            try {
                com.xxxx.emby_vr.ui.keepWebPageActive()
            } catch (e: Throwable) {
            }
            webAliveHandler.postDelayed(this, 3000L)
        }
    }

    private fun startWebAlive() {
        webAliveHandler.removeCallbacks(webAliveTick)
        webAliveHandler.post(webAliveTick)
    }

    private fun stopWebAlive() {
        webAliveHandler.removeCallbacks(webAliveTick)
    }

    private fun appendFrag(url: String) {
        webFragLastAt = System.currentTimeMillis()
        val m = mpvBackend
        if (m == null) {
            webFragPending.addLast(url)
            return
        }
        m.appendFile(url)
    }

    private fun flushPendingFrags() {
        val m = mpvBackend ?: return
        if (webFragPending.isEmpty()) return
        var n = 0
        while (webFragPending.isNotEmpty()) {
            m.appendFile(webFragPending.removeFirst())
            n++
        }
        webFragLastAt = System.currentTimeMillis()
        if (n > 0) Log.i(TAG, "补接起播前到达的 $n 段（播放器起来之前先存着的）")
    }

    /*
     * 腾讯分片那一整套（记账表 tencentSegs、本机分片播放列表服务 segHls、换集清场
     * clearSegsIfNewEpisode / keepOnlyCurrentPlayback / prefixOf、起播 playPlaylistForSegs /
     * handPlaylistToPlayer）**全部删除**（2026-10-19 12:05，父亲定"老逻辑全删"）。
     *
     * 那套是 10-19 凌晨的过渡做法：一段一签、只能趁页面在放时一段段捡，捡够三段起播。
     * 现在页面在播放信息响应里直接给出**整集清单**（4K，284 段全在，可拖动），
     * 交一次给内核就够了 —— 分片那套一行不留，免得它再抢在清单前面。
     */

    /*
     * 网页那边报来的"这一集可以播了"（2026-10-19 父亲定）。
     *
     * 父亲的口径：他在网页上选好集，地址其实早就到手，不必等他去点网页全屏。
     * 所以这里**只填信息、不起播** —— 控制条上先出现这一集的时长与集号，
     * 他按控制条上的播放键（PLAY_PAUSE）才真正接管，那一份地址此时才交出去。
     */
    private var webPreview: org.json.JSONObject? = null

    /**
     * 父亲已经按了播放键、正在等"这一集的地址"（2026-10-19 14:07 父亲定的连贯动作）。
     *
     * 一点网页全屏或控制条播放键 → 先清旧的 → 大屏幕写「即将播放」→ 地址一到就起播。
     * 这个标记就是"他按了、我在等"：地址（preview 或整集清单）进来的那一刻自动起播。
     */
    private var webAutoPlayPending = false
    /** 按下控制条播放键的时刻：只在这个时刻之后的 60 秒内，地址到了才允许自动起播（父亲 2026-10-19 21:16） */
    private var webAutoPlayAt = 0L

    /**
     * 按下播放键但手里还没有地址：只提示，不挂"等地址自动起播"的标记。
     *
     * 【2026-10-19 21:18 父亲定：绝不自动起播】
     * 他的流程是"先看到控制条写「即将播放：剧名 第X集」，再自己按播放键"。
     * 所以这里只提示一句；地址到达时只填控制条，一定等他再按一次才接管。
     */
    private fun startWebWaitForAddress() {
        webPreview = null
        webAutoPlayPending = false
        webAutoPlayAt = 0L
        osdState.upcoming = false
        /*
         * 没有合格地址：播放键置灰点不动（父亲 2026-10-19 21:20）。
         * 只在**屏幕上是网页**时这么做 —— Emby 片子那条路的置灰/等待有自己的状态，
         * 这里不许碰它（父亲 2026-10-19 21:20 提醒：别和 Emby 的规则冲突）。
         */
        if (webOnScreen) osdState.hasPlayback = false
        osdState.positionMs = 0L
        hud("这一集的地址还没到，到了控制条会写「即将播放」")
        Log.i(TAG, "控制条播放键：手里还没有这一集的地址（不会自动起播，等地址到了再按一次）")
    }

    private fun onWebPreviewMsg(obj: org.json.JSONObject) {
        /*
         * 换集了就先把旧的作废（父亲 2026-10-19 14:07 定：切集先清旧的，
         * 收到新这一集的地址才接管）。这里不清就会拿上一集的地址起播。
         */
        if (obj.optInt("reset", 0) == 1) {
            webPreview = null
            /*
             * 切集先清空：控制条不写「即将播放」、播放键置灰（父亲 2026-10-19 21:20）。
             * 仍只在网页在屏时动 hasPlayback，Emby 片子的状态不归这里管。
             */
            osdState.upcoming = false
            if (webOnScreen) osdState.hasPlayback = false
            osdState.durationMs = 0L
            osdState.positionMs = 0L
            osdState.title = ""
            Log.i(TAG, "网页通报：换集了，等这一集的新地址（${obj.optString("frame").take(50)}）")
            hud("换集了，等这一集的地址")
            return
        }
        val dur = obj.optInt("durSec", 0)
        val ep = obj.optString("ep")
        val title = obj.optString("title")
        webPreview = obj
        if (dur > 0) osdState.durationMs = dur * 1000L
        osdState.positionMs = 0L
        /*
         * 标题与集号：网页报了片名就用片名，否则用"第 N 集"。
         * hasPlayback 置上，控制条上的播放按钮才是可用的 —— 父亲要按的就是它。
         */
        val epNo = webEpisodeNumber(obj)
        osdState.title = when {
            title.isNotBlank() -> title
            epNo > 0 -> "第 $epNo 集"
            else -> osdState.title
        }
        if (epNo <= 0) {
            android.util.Log.i(
                "B0BEmbyVR",
                "◆集号 网页没给集序（身份 ep=$ep 只用于判断是不是同一集）→ 标题只用片名/页面文字，不把内部编号当集号",
            )
        }
        osdState.hasPlayback = true
        /*
         * 标题行写「即将播放：…」（父亲 2026-10-19 定）：地址已就绪、还没交出去，
         * 他按控制条上的播放键之后才转成「正在播放」（见 playWebStream 开头）。
         */
        osdState.upcoming = true
        Log.i(
            TAG,
            "网页通报：这一集已就绪（时长 ${dur}s，集号 $ep，页面 ${obj.optString("frame").take(50)}）" +
                "，等父亲按控制条播放键",
        )
        hud(if (dur > 0) "这一集可以播了，按播放键开始（约 ${dur / 60} 分钟）" else "这一集可以播了，按播放键开始")
        /* 【2026-10-19 21:18 父亲定：绝不自动起播】
           地址到了只填控制条、写「即将播放」，一定等他看到并再按一次播放键才接管。 */
        Log.i(TAG, "网页通报：地址已就绪，只填控制条（不自动起播）")
    }

    /**
     * 控制条播放键按下时，把网页手里那份地址交出去（2026-10-19 父亲定）。
     *
     * 走的是与"网页按全屏起播"完全相同的那条路（playWebStream），
     * 只是触发点从网页按钮搬到了我们的控制条上。
     */
    private fun playWebPreview() {
        val obj = webPreview ?: return
        /*
         * **不清掉 webPreview**（2026-10-19 11:58 自查）：
         * 起播之后 hasEngine() 为真，控制条上这一下按的就是暂停/继续了；
         * 万一这一下没起来（页面没在取片等），手里那份还得留着让他再按一次。
         */
        Log.i(TAG, "控制条播放键：把网页这一集交给自己的播放器（时长 ${obj.optInt("durSec", 0)}s）")
        playWebStream(obj)
    }

    /** 最近一次把网页流交给内核的时刻（用来算"交出去多久才出画面"） */
    private var lastWebDeliverAt = 0L
    /** 上一次记过的网页流分辨率（◆起播只在变化时打一行） */
    private var lastWebSizeW = 0
    private var lastWebSizeH = 0

    private fun chainLog(step: String, kv: String) {
        Log.i("B0B链", step + " " + kv)
    }

    /**
     * 起播日志里的站点标识兜底（2026-10-26 父亲报"站= 是空的"）。
     * 这个字段本该由网页交付带过来，但原生自己重新拼播放参数的两条路（腾讯攒分片、
     * 爱奇艺本机转发）没把它带上；用交付里的页面地址反推，日志才看得懂是哪一站。
     */
    private fun siteTagOf(obj: org.json.JSONObject): String {
        val direct = obj.optString("site")
        if (direct.isNotBlank()) return direct
        val u = obj.optString("frame")
        return when {
            u.contains("youtube") || u.contains("youtu.be") -> "yt"
            u.contains("iqiyi") -> "iq"
            u.contains("qq.com") -> "qq"
            u.contains("bilibili") -> "bi"
            else -> "-"
        }
    }

    /**
     * 换片清场 —— **只在收场口调**（施工图 §4.2.3.1）。
     *
     * 除了杜比痕迹，还必须把**内核视频轨开回来**：P5 那条路为了让自管解码出画面，
     * 会设 `vid=no` 把内核视频轨关掉；这个属性是进程级的，不恢复的话，
     * 之后每一部走内核的片子都会"只有声音没有画面"
     * （父亲 2026-11-17 实测：先播 P5，再播任何片都黑屏；先播普通片则正常）。
     */
    private fun clearDoviState() {
        com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(false)
        /*
         * 内核还没建起来（本进程还没播过片）时没有残留可清，整段跳过：
         * 否则会连打十几行 SKIP，把真正的故障淹掉。渲染层标志不属于内核，照常归零。
         */
        if (!com.xxxx.emby_vr.player.MpvBackend.isReady()) {
            Log.w(TAG, "换片清场：内核未就绪，跳过属性恢复（本进程没有残留）")
            return
        }
        /*
         * 逐项恢复"为某类片源临时改过"的开关（施工图 §4.2.3 A 类）。
         *
         * 杜比参数文件**不在这里删**（图 §4.4 ⑧）：渲染层按 mtime 读它，删了会让正在显示的那帧跳色；
         * 删文件的动作留在 VideoPipe.start 里做。
         *
         * 这些开关是**进程级**的，不恢复就会带着上一部片的状态跑下一部：
         *   vid               —— P5 关掉内核视频轨，不恢复则后续片全黑（已实测）
         *   loop-file         —— P5 期间强制关，不恢复则普通片播完不循环
         *   色彩五件套         —— 不按本片重设则沿用上一部的（发灰/偏绿）
         *   android-surface-size —— 不按本片重设则画面压扁/拉伸
         *   aid / sid         —— 不恢复则音轨、字幕串到上一部
         *   sub-auto / sub-visibility —— 字幕串台（历史问题）
         *   外挂字幕 / 外部音轨 —— 不摘则下一部挂着上一部的轨
         *   mute              —— 起播静音后中途失败会一直没声音
         *   网页流请求头        —— 带到后续 Emby 片请求上（可能被拒）
         *
         * 用户设置（缓冲档 / 倍速）不在这里清，按用户当前选择重设。
         */
        restoreOption("vid", "auto")
        restoreOption("audio-display", "no")
        restoreOption("loop-file", if (playModeIndex != 2) "inf" else "no")
        /* 色彩是一对：内核管线 + 渲染层标志，必须同时归零，只归一半就偏色 */
        setColorPair(false)
        /*
         * 画面尺寸不在这里恢复：每条起播路径都会按本部片的实测宽高重设一次
         * （网页流按目标宽度、Emby 片按 videoSize()），清场时还没拿到新尺寸，
         * 在这里设一个猜测值反而会被起播时覆盖、白改一次。它属于"起播必设"，不是残留。
         */
        /* 音轨/字幕回到自动，并摘掉上一部挂上去的轨 */
        restoreOption("aid", "auto")
        restoreOption("sid", "auto")
        restoreOption("sub-auto", "no")
        restoreOption("audio-file", "")
        restoreOption("sub-file", "")
        /* 静音与网页流请求头：这两个不恢复最容易变成"没声音"和"被服务端拒" */
        restoreOption("mute", "no")
        restoreOption("http-header-fields", "")
        restoreOption("referrer", "")
        restoreOption("user-agent", "")
        Log.i(TAG, "换片清场：已恢复 [vid,loop,色彩,aid,sid,sub-auto,外挂轨,mute,请求头] 并清杜比状态")
    }

    /**
     * 恢复一个"按片源临时改过"的内核属性（施工图 §4.2.3 落地要求 2）。
     *
     * ① 走**静态**属性接口：清场可能发生在 `MpvBackend(this).also{...}` 内部，
     *    那时实例还是 null，用 `mpvBackend?.` 会静默跳过。
     * ② 留痕由 setOptionRuntime 内层负责；少恢复一项的后果是下一部片黑屏 / 无声 / 偏色，
     *    所以这里宁可多写一行显式调用，也不用 `?.` 让它可能被静默跳过。
     */
    private fun restoreOption(name: String, value: String) {
        /*
         * 不在这里 catch：setOptionRuntime 内部对每个属性自己 try+catch 并 Log.w
         * （"内核属性设置失败 …"），异常不会抛到这里，写 onFailure 是永不执行的死分支。
         * 封装的价值是"改一处、12 处生效"，以及让读者一眼看出这些值是清场恢复、不是起播设置。
         */
        com.xxxx.emby_vr.player.MpvBackend.setOptionRuntime(name, value)
    }

    /**
     * 起播自愈校验（施工图 §4.2.3.1 硬前提 2）。
     *
     * 只查三个**致命残留**：`vid` / `loop-file` / `mute`。清场已经挪到收场口，
     * 这里存在的理由只有一个 —— 进程被系统杀掉时收场口没走到，残留会被下一部片继承：
     * `vid=no` 让之后每一部走内核的片子都**只有声音没有画面**（已实测）。
     *
     * 只读三项、错了才写，起播瞬间的原生写压到个位数。
     */
    private fun startupSelfHeal() {
        val vid = com.xxxx.emby_vr.player.MpvBackend.getPropRuntime("vid")
        val loop = com.xxxx.emby_vr.player.MpvBackend.getPropRuntime("loop-file")
        val mute = com.xxxx.emby_vr.player.MpvBackend.getPropRuntime("mute")
        if (vid == null && loop == null && mute == null) {
            /* 新进程第一次起播时内核还没建：全新实例没有残留，不必校验 */
            Log.i(TAG, "起播校验：内核尚未就绪，本次跳过（新进程无残留）")
            return
        }
        val wantLoop = if (playModeIndex != 2) "inf" else "no"
        val fixed = ArrayList<String>(3)
        if (vid != null && vid != "auto") {
            restoreOption("vid", "auto")
            fixed.add("vid=$vid→auto")
            Log.e(TAG, "起播自愈：内核视频轨被上一次播放关掉了（vid=$vid），已恢复 auto")
        }
        if (loop != null && loop != wantLoop) {
            restoreOption("loop-file", wantLoop)
            fixed.add("loop=$loop→$wantLoop")
            Log.e(TAG, "起播自愈：循环开关有残留（loop-file=$loop），已按播放模式恢复 $wantLoop")
        }
        if (mute != null && mute != "no") {
            restoreOption("mute", "no")
            fixed.add("mute=$mute→no")
            Log.e(TAG, "起播自愈：内核还停在静音（mute=$mute），已恢复 no")
        }
        Log.i(TAG, "起播校验：vid=$vid loop=$loop mute=$mute 修正=${if (fixed.isEmpty()) "无" else fixed.joinToString(",")}")
    }

    /**
     * 本次片源的色彩二元组（施工图 §1.5）。
     *
     * 颜色由 $(applyDoviPipeline, setDoviStreamActive) 这一对共同决定，**必须成对设置**：
     *  · true/true   —— 自管解码的杜比 P5，颜色由渲染层还原；
     *  · false/false  —— 其余一切（MPV 内核、网页流、系统播放器）。
     * 只改一半就是最典型的偏绿/发灰（P5 的 IPT 数据被当普通视频解释）。
     */
    private fun setColorPair(doviRenderer: Boolean) {
        com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(doviRenderer)
        runCatching { com.xxxx.emby_vr.player.MpvBackend.applyDoviPipeline(doviRenderer) }
        Log.i(TAG, "色彩二元组 → 内核DOVI管线=$doviRenderer 渲染层DOVI标志=$doviRenderer")
    }

    private fun playWebStream(obj: org.json.JSONObject) {
        /* 网页流标记（2026-11-16）：供内核判定"不要按 HDR10 推断" */
        com.xxxx.emby_vr.player.PlaybackFlags.webSource = true
        /*
         * 清场已挪到收场口（施工图 §4.2.3.1），起播口不再全量清：
         * 这里只交代"本次不是杜比片源"，色彩二元组到 startPlayer 里按 plan 设。
         */
        lastWebDeliverAt = System.currentTimeMillis()
        /*
         * 网页流起播也走同一套换片清场（父亲 2026-11-07）。
         *
         * 以前这里只复位银幕比例和画布记账，其余"跟着上一部片子走"的东西全留着：
         * 流清单、字幕表、片名 logo、菜单里的信息/演职人员、片头时间轴、杜比格式标记。
         * 结果是网页里换片后，菜单显示上一部那套（P5 的 1920/1280）、字幕飘着上一部的台词。
         * 清场统一在 clearForNewMedia 一处做，Emby 片与网页片不再各清各的。
         */
        clearForNewMedia()
        /*
         * 清掉上一部 Emby 片子的编号（父亲 2026-11-11）。
         *
         * clearForNewMedia 只清流清单与菜单数据，不动这个编号；网页流起播不经过
         * playMedia，于是它一直留着上一部片子的值。凡是「拿 currentMediaId 去 Emby
         * 查东西」的逻辑（片详情、字幕轨、弹幕回退）都会查到旧片，表现就是播腾讯时
         * 飘出上一部剧的弹幕/字幕。
         */
        currentMediaId = ""
        chainLog(
            "⑧起播",
            "站=" + siteTagOf(obj) + " 集=" + obj.optString("ep").ifBlank { obj.optString("vid") } +
                " 时长=" + obj.optInt("durSec", 0) + "s at=" + obj.optLong("at", 0L) +
                " needProxy=" + obj.optInt("needProxy", 0) +
                " 标题「" + obj.optString("title").take(24) + "」",
        )
        /*
         * 【2026-10-23 08:52 父亲报"爱奇艺比例很多时候不对"】
         * 上一部是 2.4:1 宽银幕、这一部是 16:9（或反过来）时，银幕会带着上一部的比例起播，
         * 要等内核报出真实尺寸才纠正 —— 这一两秒就是"看着比例不对"。
         * 交付新片时先把银幕复位成 16:9（中性值），再等内核按真实画面覆盖。
         */
        /* 上一部已经报过真实尺寸 → 先把银幕复位成中性比例，避免带着上一部比例起播 */
        if (webSizeKnown) {
            applyScreenAspect(16f / 9f, "新片起播：银幕先复位 16:9，等内核报真实尺寸")
        }
        webSizeKnown = false   /* 新一集：尺寸重新等（2026-10-20） */
        /*
         * 画布记账也要清零：起播时内核窗口会先按 16:9 重建，
         * 若这里的记账还留着上一集的 1920x800，下面"画布跟真实尺寸重设"的判据
         * 会以为两边一样、跳过重设 —— 结果窗口停在 1080 高、画面被拉扁
         * （父亲 2026-10-26 00:30 现场：比例对了、画布没改）。
         */
        webCanvasW = 0
        webCanvasH = 0
        /*
         * 网页交来的画幅比：起播那一刻就有，不用等内核解码出第一帧。
         * 上一部是宽银幕、这一部是 16:9 时，复位 16:9 到内核报尺寸之间那两三秒
         * （父亲 2026-10-23 报「爱奇艺比例很多时候不对」）就靠它填。
         */
        val ar = obj.optDouble("ar", 0.0).toFloat()
        if (ar > 0f) applyScreenAspect(ar, "网页交来的画幅比 $ar")
        /*
         * 兜底纠正（父亲 2026-10-25 要）：起播后让内核再报一次真实尺寸。
         * 换源清场会把银幕复位成 16:9，若内核没在下一拍之前报出真实尺寸，
         * 宽银幕的片子就一直按 16:9 挂着 = 画面被压扁（第二次接管时出现过）。
         * 这里在起播后几个时间点各纠正一次，内核还没出帧就等下一个点。
         */
        /*
         * 起播后每 0.5 秒问一次内核"这一部到底多大"，报出来就把银幕比例改成它，连问 30 秒。
         *
         * 不能只在几个固定时间点各做一次：切集/换档时内核要重建输出窗口，报尺寸的时刻不定。
         * 实测出现过"内核 23:59:01 就报了 1920x800，银幕到 23:59:17 才改"，
         * 中间十几秒画面就是被 16:9 拉扁的样子（父亲 2026-10-26 00:01 报）。
         */
        run {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            var left = 60
            val tick = object : Runnable {
                override fun run() {
                    runCatching {
                        mpvBackend?.videoSize()?.let { (vw, vh) ->
                            if (vw > 0 && vh > 0) {
                                /*
                                 * 画布必须跟着真实尺寸一起改。
                                 * 只改银幕比例、画布还停在 1920x1080 时，mpv 会在 16:9 的画布
                                 * 里给 1920x800 的画面上下补黑边，再整块贴到 2.4:1 的银幕上 ——
                                 * 看着就是被拉宽/拉扁（父亲 2026-10-26 00:15 现场：比例改了、画布没改）。
                                 *
                                 * 宽度上限与切档用同一个来源（webLayerWidthCap）：这里按全局上限算，
                                 * 会把用户刚切好的 2560 每半秒顶回 4096。
                                 */
                                val cap = webLayerWidthCap()
                                val bw = if (vw > cap) cap else vw
                                val bh = (vh.toLong() * bw / vw).toInt().coerceAtLeast(64)
                                if (bw != webCanvasW || bh != webCanvasH) {
                                    webCanvasW = bw
                                    webCanvasH = bh
                                    runCatching { renderer.setVideoBufferSize(bw, bh) }
                                    runCatching { mpvBackend?.setSurfaceSize(bw, bh) }
                                    android.util.Log.i(
                                        TAG,
                                        "起播兜底纠正：画布跟着真实尺寸重设 → ${bw}x$bh（内核报 ${vw}x$vh）",
                                    )
                                }
                                applyScreenAspect(
                                    vw.toFloat() / vh.toFloat(),
                                    "起播兜底纠正（内核报 ${vw}x$vh）",
                                )
                            }
                        }
                    }
                    left--
                    if (left > 0) h.postDelayed(this, 500L)
                }
            }
            h.postDelayed(tick, 500L)
        }
        /* 真的开始接管了：标题行从「即将播放」转成「正在播放」（父亲 2026-10-19 定） */
        osdState.upcoming = false
        /*
         * 网页流也接弹幕：用交付里带的标题/集号去要一份。
         * 电影没有集号（ep<=0）按单片查，剧集带上集号。
         */
        runCatching {
            val t = obj.optString("title", "")
            val e = obj.optInt("ep", 0)
            loadDanmakuForWeb(t, if (e > 0) e else 0, e <= 0)
        }
        /*
         * 这条地址是**网页用浏览器自己的身份**要来的（2026-10-18 晚，扩展加了这条路）。
         * 浏览器带票，服务器才给整集；我们自己发的那条请求没身份、只给试看，
         * 所以这里记下来，分片路那边就不要再问一遍、把好地址顶掉了。
         */
        if (obj.optInt("whole", 0) == 1) {
            /*
             * 分片那套清场（clearSegsIfNewEpisode / keepOnlyCurrentPlayback）随老路一起删掉了
             * （2026-10-19 12:05）：整集清单自带集号与会话，不存在"新旧分片串剧"这回事。
             */
            /*
             * 【2026-10-19 11:58 父亲提醒：别让老逻辑挡住新路】
             *
             * 下面那两段是 **10-19 凌晨**的做法：那时手里没有整集清单，只能靠页面
             * 一段段喂分片，于是"攒到 3 段就改走分片播放列表"。
             * 现在手里拿的是**整集清单**（4K 的 m3u8，284 段全在，父亲 11:13 用 IINA 验过），
             * 它比"攒到的几段"完整得多 —— 再让老路抢先，就等于把整集换成几段。
             * 所以先认清单：给的是清单就直接往下面走（整集交给内核）；
             * 只有拿不到清单（老式整集文件 / 只有零散分片）才回退到那条老路。
             */
            val firstUrl = obj.optJSONArray("streams")?.optJSONObject(0)?.optString("video") ?: ""
            val isPlaylist = firstUrl.contains(".m3u8") || firstUrl.contains("#EXTM3U")
            if (isPlaylist) {
                Log.i(
                    TAG,
                    "整集清单在手（尾 ${firstUrl.takeLast(46)}），直接交给内核，" +
                        "不走分片记账那条老路",
                )
            }
            /*
             * 【2026-10-19 12:00 重写，父亲定："老逻辑全删，只留拿最高清那条路"】
             *
             * 这里原来有两段老路：①攒到 3 段分片就改走"分片播放列表"；
             * ②分片不够就等页面多取几段（最多 12 秒），等不到就不接管。
             * 它们都是 **10-19 凌晨** 的做法 —— 那时手里没有整集清单，只能靠页面
             * 一段段喂分片。现在手里拿到的是**整集清单**（4K 的 m3u8，284 段全在，
             * 父亲 11:13 用 IINA 验过整集可播），完整、可直接拖动，比"攒到的几段"好得多。
             * 两段老路全部删掉，这里只剩一条路：把整集清单交给内核。
             */
            val v = com.xxxx.emby_vr.web.TencentWholeUrl.vidFromFrame(obj.optString("frame"))
            if (v != null) {
                wholePlayingVid = v
                Log.i(TAG, "整片地址来自网页身份（浏览器带票），不再重复发我们那条无身份请求")
            }
        }
        /*
         * 【2026-10-17 23:28 父亲立的机制：不管从哪儿切到哪儿，控制条与弹幕屏的信息都要跟着换】
         *
         * 三种切换都得走同一套清场：Emby片→Emby片、Emby片→网站视频、网站视频→网站视频。
         * 前一种本来就在 playMedia 开头走 clearForNewMedia；网站这条路原来**什么都没清** ——
         * 于是父亲看到「B站→Emby片→B站」之后，控制条还挂着 Emby 片的信息、
         * 片名 logo 还留在大银幕上、画面还是 Emby 那部片的比例（现场日志 23:21:15：
         * `起播走 mpv 内核（…片源 hevc SDR 3840x1604…）` —— 那是上一部 Emby 片的尺寸）。
         *
         * 这里补上同一次清场（标题/时长/进度、银幕比例回 16:9、字幕表清空、片名 logo 收掉），
         * 清完由网页流自己填：标题与时长来自抓来的那一份，比例来自网页报的宽高。
         */
        Log.i(TAG, "换源：网站视频起播，先按新来源清一遍控制条与弹幕屏")
        clearForNewMedia()
        /*
         * 【2026-10-17】记下接管那一刻页面在哪 —— 按 B 回网页就落回这一页。
         * 以此刻页面为准（WebLibraryScreen 已用最新一跳覆盖过 frame）。
         */
        obj.optString("frame").takeIf { it.isNotBlank() && it != "about:blank" }?.let {
            webReturnUrl = it
            Log.i(TAG, "记下要回去的那一页：" + it.takeLast(70))
        }
        val streams = obj.optJSONArray("streams") ?: return
        if (streams.length() == 0) return
        webCandSrc = obj
        webCandIdx = -1
        Log.i(TAG, "④ 收到网页地址：${streams.length()} 条候选")
        tryWebStreamAt(0)
    }

    /*
     * 起播第 idx 条候选；8 秒内内核还没出画面就换下一条（父亲 2026-10-16：
     * 用我们自己播放器起播这件事要稳，一条播不动不能就停在黑屏上）。
     *
     * 判据用内核的 `video-params/w`（拿到第一个视频帧才有值），比"纹理来了一帧"
     * 可靠 —— 换片瞬间纹理层里还留着上一部的旧帧。
     * 候选由扩展按优先级排好：整片文件 → 画面+声音配对 → 其它画面分片。
     */
    private fun tryWebStreamAt(idx: Int) {
        val src = webCandSrc ?: return
        val streams = src.optJSONArray("streams") ?: return
        if (idx >= streams.length()) {
            Log.w(TAG, "⑤ ${streams.length()} 条候选都没能出画面")
            hud("抓到的地址都没能播起来（可能是会员票或地址过期）")
            return
        }
        val s = streams.optJSONObject(idx) ?: return
        if (s.optString("video").isBlank()) return
        /*
         * 整集文件走多路代理（2026-10-18 晚）。
         *
         * 实测：腾讯整集地址单连接被限速到约 1KB/s（1 连接 15KB / 2 连接 168MB / 4 连接 253MB，
         * 14 秒采样）。播放器只开一条连接 → 文件头都读不下来 → 大屏黑屏、总时长显示 0。
         * 所以整集这条路不给内核远端地址，给**本机代理**地址：代理自己开 4 条 Range 连接
         * 分块拉、写临时文件，内核要哪段喂哪段，拖进度也补得上。
         * 分片那条路（B站 / 腾讯试看）不动，仍直接给远端地址。
         */
        /*
         * 整集 mp4 分支已删除（2026-10-19 父亲定：那条播不起来）。
         *
         * 原委：腾讯整集文件单连接被限速到约 1KB/s（14 秒采样：1 连接 15KB、4 连接 253MB），
         * 内核连文件头都读不下来（日志「内核轨道清单：[]」「画面参数：格式=-」），大屏全黑。
         * 现在腾讯一律走分片播放列表（明文 MPEG-TS、33–44MB/s）；这条路若还有消息进来，
         * 只记一条日志、不接管。
         */
        if (src.optInt("whole", 0) == 1) {
            Log.w(TAG, "收到整集文件播放请求，已废弃（被限速），不接管")
            return
        }
        val playStream = s
        webCandIdx = idx
        webSubtitleUrl = src.optString("subtitle")
        webCid = src.optLong("cid", 0L)
        /*
         * 腾讯的片号从页面地址里取（形如 /x/cover/<cid>/j410292e7ng.html）——
         * 拉弹幕要它（2026-10-20）。非腾讯站留空，走 B站 那条。
         */
        /*
         * 【2026-10-22 修 P1】原来只从页面地址里抠片号：腾讯的**剧集页**
         * `/x/cover/<cid>.html` 里没有 vid，抠出来的是 cid，弹幕按它去拉就是空的/错的。
         * 现在页面交来的载荷里带 vid（播放器自己的片号），优先用它，地址正则只作兜底。
         */
        webVid = src.optString("vid").ifBlank {
            runCatching {
                val u = src.optString("frame")
                Regex("/([a-z][0-9a-z]{9,15})\\.html").find(u)?.groupValues?.get(1).orEmpty()
            }.getOrDefault("")
        }
        webDanmakuLoaded = false
        val site = webSiteState.value
        val hasCookie = src.optString("cookie").isNotBlank()
        Log.i(
            TAG,
            "④ 起播第 ${idx + 1}/${streams.length()} 条：" +
                "音轨=${s.optString("audio").isNotBlank()}，会员票=$hasCookie，站点 $site",
        )
        /* 只把这一条递给内核（内核那侧本来就只认 streams[0]），换条时重建一份 */
        val one = org.json.JSONObject(src.toString())
        val arr = org.json.JSONArray()
        arr.put(playStream)
        try {
            one.put("streams", arr)
        } catch (_: Throwable) {
        }
        webCandHandler.removeCallbacksAndMessages(null)
        /* 记下"网页是被我们顶掉的"，返回时好还原（父亲 2026-10-16） */
        if (webOnScreen) {
            webHiddenByStream = true
            webSiteBeforeStream = webSiteState.value
        }
        /*
         * 攒分片模式**不销毁网页**（2026-10-18）：腾讯的分片签名是"一段一签"，
         * 网页必须在后台继续放着才有新段。只把网页屏收起来（`setScreenWeb(false)`），
         * 页面本身照旧活着 —— 按 B 回来时也就不用重建，直接就是原来那一页。
         * 其余情况保持原样：整个销毁（父亲 2026-10-13 定"不留后台"）。
         */
        if (webKeepPageAlive) {
            osdState.webSiteName = ""
            com.xxxx.emby_vr.vr.VrNative.setScreenWeb(false)
            /*
             * 网页屏收起来了，但它的纹理还得继续取帧（2026-10-18 头显定案）：
             * 不取帧，生产端（火狐内核）的缓冲队列会堵死 —— 页面整个冻住、
             * 心跳整段消失、分片不再来，"只播一小段"就是这个原因。
             */
            com.xxxx.emby_vr.vr.VrNative.setWebKeepSampling(true)
            Log.i(TAG, "攒分片模式：网页面留在后台继续取新段（不销毁）")
        } else {
            closeWeb()
        }
        startPlayer(url = "", title = "网页", startMs = 0L, webStream = one)
        /*
         * 接管之后控制条保持展开（父亲 2026-10-16）：
         * 抓来的地址播起来时，条上要能看到片名与进度，别一接管就收起来。
         */
        setOsdVisible(true)
        webCandHandler.postDelayed({
            if (webCandIdx != idx) return@postDelayed
            val m = mpvBackend
            /*
             * 攒分片模式不做"换下一条"也不报错（2026-10-18）：这里的每一段只有十几秒，
             * 8 秒这个判据本来就是给整片/播放列表用的；段是连着接上去的，
             * 真播不动下一段会自己接上来。
             */
            if (webFragMode) {
                Log.i(TAG, "⑤ 攒分片模式：第 ${idx + 1} 条起播 8 秒检查（不再按整片那套换条）")
                return@postDelayed
            }
            if (m != null && !m.videoReady()) {
                Log.w(TAG, "⑤ 第 ${idx + 1} 条 8 秒没出画面，换下一条（站点 $site）")
                tryWebStreamAt(idx + 1)
            } else {
                Log.i(TAG, "⑤ 第 ${idx + 1} 条已出画面，接管成功")
                /* 画面稳了再去拉字幕，不跟起播抢带宽（父亲 2026-10-16） */
                loadWebDanmaku(webCid)
                loadWebSubtitle(webSubtitleUrl)
            }
        }, 8000L)
    }

    private fun startPlayer(
        url: String,
        title: String,
        startMs: Long,
        /**
         * 网页抓来的可播地址（2026-10-16）：非空时绕过 Emby 走内核直连 ——
         * 视频流、外部音轨、请求头都从这一份里取。为空时行为与以前完全一致。
         */
        webStream: org.json.JSONObject? = null,
    ) {
        /*
         * 播放画面走哪条线（2026-10-05）：
         *   VR 模式 → VR 上下文里建的那张纹理（原生播放屏，贴到 VR 里那块平面上）
         *   非 VR   → 老的 2D 画面管线（renderer.videoSurface）
         */
        val vrSurface = if (com.xxxx.emby_vr.vr.VrNative.vrRunning) renderer.vrVideoSurface else null
        val surface = vrSurface ?: renderer.videoSurface
        if (surface == null) {
            hud("视频纹理未就绪（GL 还没建好）")
            return
        }
        val useVrScreen = vrSurface != null
        try {
            stopPlaybackInternal(keepUi = replaying)
            /* 起播自愈校验：必须排在本片任何 vid/loop/mute 设置之前（施工图 §4.2.3.1） */
            startupSelfHeal()
            /*
             * 杜比视界片源：改走 mpv 解码内核（父亲 2026-10-08）。
             *
             * 系统解码器解不了杜比视界的 H.265 流，表现是「只有声音没有画面」；
             * 让服务端转码也不行（Emby 那条路走 QSV 硬解，日志里 hevc_qsv 报
             * unknown error (-21) 一万条后直接失败）。所以自己解 —— 和 PICO 上
             * 能正常播的 4XVR 一个路子。标记由取播放信息那一步立起来。
             */
            val isWeb = webStream != null
            /*
             * 网页流：从这一刻起，Emby 那套"上一部片子的信息"全部让位
             * （标题、片名 logo、银幕比例 —— 父亲 2026-10-17 实测的三处残留）。
             */
            webPlayingNow = isWeb
            if (isWeb) {
                com.xxxx.emby_vr.vr.VrNative.setLogoVisible(false)
                Log.i(TAG, "网页流起播：片名 logo 收掉（那是上一部 Emby 片子的）")
            }
            /* 从 Emby 播的片子：清掉"这一部是从网页抓来的"标记，免得按 B 跳到网页去 */
            if (!isWeb) {
                webStreamSite = null
                /*
                 * 【2026-10-17 22:53 父亲实测：播 Emby 片子时对着大银幕按 B 跳回 B站 网页】
                 *
                 * `webHiddenByStream` 记的是"**这一部网页流**被我们自己的播放器顶掉、B 键该回网页"。
                 * 换去放 Emby 片子时它还留着上一部 B站 的 true —— 病根在这里。
                 * 播非网页片子时一并清掉：B 键那一支的语义只属于网页接管，
                 * 播自己的片子时按 B 就该什么也不做（父亲 22:55 明确）。
                 * 注意：这只在"起播非网页片子"时清，不动 closeWeb() 里那条
                 * （那一条清掉会把接管流程刚置好的标记抹掉，2026-10-16 踩过）。
                 */
                webHiddenByStream = false
            }
            /*
             * 网页流：控制条上要写「正在播放 哔哩哔哩-片名」（父亲 2026-10-16）。
             * 原来只在网页还挂在大银幕上时才写站点名，接管播放时网页面被收掉、
             * 标题跟着被清空 —— 于是控制条显示"什么都没在播"，而画面上明明在放。
             */

            if (isWeb || com.xxxx.emby_vr.player.PlaybackFlags.useKernelDecoder) {
                /*
                 * 起播时画质档位归位到第一档：上一条片子选的档位（比如 Emby 片的「1 兆」= 第 3 档）
                 * 带到 P5 这条只有两档的路上就没有对应项，菜单里一个都不亮。
                 */
                qualityIndex = 0
                Log.i(TAG, "起播走 mpv 内核（杜比视界 Profile 5 或选了图形字幕，片源 ${com.xxxx.emby_vr.player.PlaybackFlags.videoDescriptor}，" +
                    "版本 ${com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionProfile ?: "未探到"}）")
                mpvBackend?.stop()
                /*
                 * 【2026-10-17 19:47 父亲问"为什么有两个播放器在放" → 是缺陷，这里收掉】
                 *
                 * 内核接手时只收掉了上一个内核，**没收系统播放器**：它交班之后对象还活着，
                 * 于是干出两件坏事 ——
                 *  · 它的状态回调继续左右弹幕层（`onIsPlayingChanged` → 弹幕"暂停"），
                 *    弹幕就被冻在那一刻、整场不滚动，重启 app 才好（父亲当晚实测）；
                 *  · 取播放位置时它排在前面，弹幕时钟被它那个不动的旧值反复拽回去
                 *    （日志 `弹幕画笔：… 进度=3秒` 几百帧不变）。
                 * 只放声音的是内核，它并没有真的在放 —— 但必须让它彻底退场。
                 */
                player?.let {
                    Log.i(TAG, "内核接手：系统播放器退场（收掉，交班对象不再影响弹幕与位置）")
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                player = null
                setDanmakuPaused(false)
                /*
                 * 地址要换成原文件直连（父亲 2026-10-08 实测）。
                 *
                 * 第一次跑 mpv 时用的是传进来的那个地址，日志里看到它拉的是
                 * `…/videos/3930933/hls1/main/2.ts` 这样的切片 —— 服务端转码出来的，而
                 * 那路转码本身是坏的（服务端硬解解不了杜比视界，切出来就是空的），
                 * 内核解了个寂寞，声音有、画面没有。
                 *
                 * 内核自带 FFmpeg，杜比视界它自己就能解，所以直接给它原文件
                 * （Static=true 就是原样直出，不转码不换封装）。
                 */
                val srcId = pendingMediaSourceId
                /* 网页流：地址直接用抓来的那一路（其余流程与内核播 Emby 片源一致） */
                val webFirst = webStream?.optJSONArray("streams")?.optJSONObject(0)
                val directUrl = if (webFirst != null) {
                    webFirst.optString("video")
                } else buildString {
                    append(userServer())
                    append("/emby/videos/")
                    append(pendingItemId ?: "")
                    append("/stream?Static=true")
                    if (!srcId.isNullOrEmpty()) {
                        append("&MediaSourceId=")
                        append(srcId)
                    }
                    append("&api_key=")
                    append(userToken())
                }
                Log.i(TAG, "mpv 直连原文件：…/videos/${pendingItemId}/stream?Static=true")
                /*
                 * 中转上游登记（2026-11-18 父亲定：直连网盘做成开关）。
                 *
                 * 开关打开时，中转拿这条小雅 /d/ 地址去要一次签名，之后直接向 CDN 取数据，
                 * 不再经 Emby 逐字节转发（每一块省掉约 1 秒的转发开销；头显不在家庭网段时
                 * 还能用上头显当地带宽）。取不到小雅地址就登记 null，这一部片照旧走 Emby。
                 */
                if (com.xxxx.emby_vr.player.SharedReadCache.enabled()) {
                    com.xxxx.emby_vr.player.SharedReadCache.setPlaybackUrls(
                        directUrl,
                        if (webFirst == null) alistUrlOf(pendingSourcePath) else null,
                    )
                }
                /*
                 * 画布尺寸（父亲 2026-10-08）。
                 *
                 * 一是必须设：我们的画面纹理从来没有缓冲尺寸，硬解时解码器会自己设，
                 * 内核自己渲染时不设就按默认尺寸画，屏幕上是整屏拉伸的色块。
                 *
                 * 二是别设成 4K：实测画布给到 3840x2160 时，内核每帧要处理的像素太多，
                 * 帧率在 12~24 之间跳、整个 VR 场景跟着抖。银幕在头显里根本用不到 4K，
                 * 宽度上限压到 1920（高度按片源比例算），像素量降到四分之一。
                 */
                /* 网页流：画布按抓到的这一档分辨率算；Emby 片源仍按播放信息里的值（父亲 2026-10-16） */
                val srcW = webFirst?.optInt("width", 0)?.takeIf { it > 0 }
                    ?: com.xxxx.emby_vr.player.PlaybackFlags.videoWidth.takeIf { it > 0 } ?: 1920
                val srcH = webFirst?.optInt("height", 0)?.takeIf { it > 0 }
                    ?: com.xxxx.emby_vr.player.PlaybackFlags.videoHeight.takeIf { it > 0 } ?: 1080
                /*
                 * 2026-10-09：上限可运行时调（vr-tuning 的 `video_surface_w`）。
                 * 实测杜比还原每帧吃约 15ms GPU，开销与像素量成正比，
                 * 把内核处理宽度压下来是当前最直接的省 GPU 手段。
                 */
                /*
                 * 【2026-10-20 18:44 父亲定：网页流不设限，片源多大就按多大解】
                 *
                 * 原话：「这几个网站的视频，把那个限制改成或者不限制，原生分辨率是多少就给 MPV 多少。」
                 * 那个上限本来是给 Emby 的杜比片省 GPU 用的（杜比还原每帧十几毫秒）；
                 * 网页流没有杜比要还原，卡在 1280 只会让 4K 片源看起来糊。
                 * 所以：网页流按片源真实宽度来（最高 3840），Emby 片源仍走配置里的上限。
                 */
                /*
                 * 【2026-10-20 21:36 父亲定：按大银幕自己的分辨率来，别冲 4K】
                 *
                 * 白天为了"看着更清晰"把网页流放到片源原始 3840 宽，结果播一会儿就闪退：
                 * 崩溃前 mpv 连续报 `Failed uploading frame!`（OpenGL GL_INVALID_OPERATION），
                 * 随后进程收到段错误 —— 4K 帧往显存里传，这块 GPU 扛不住。
                 * 银幕画布本身只有 1920 宽，按 1920 解出来的画面就是"银幕用够"的程度，
                 * 再高的分辨率肉眼看不到，只会把显存吃光。
                 */
                /*
                 * 两条链的上限各自可调：
                 *   网页流 / 原画档 → raw_max_w（0 = 按片源，最高 3840）
                 *   Emby 片源（含杜比视界 P5）→ video_surface_w（那条链要单独压低保黑纹）
                 */
                val cap = if (webFirst != null) {
                    com.xxxx.emby_vr.vr.VrTuning.rawMaxW.let {
                        if (it > 0) it.coerceAtMost(com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP) else com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP
                    }
                } else {
                    com.xxxx.emby_vr.vr.VrTuning.videoSurfaceMaxW.let {
                        if (it > 0) it else 1920
                    }
                }
                val vw = if (srcW > cap) cap else srcW
                val vh = (srcH.toLong() * vw / srcW).toInt().coerceAtLeast(64)
                renderer.setVideoBufferSize(vw, vh)
                mpvBackend = com.xxxx.emby_vr.player.MpvBackend(this).also { m ->
                    m.attachSurface(surface)
                    m.setSurfaceSize(vw, vh)
                    /*
                     * 【2026-10-20 23:40 闪退根因：句柄泄漏】网页流改零拷贝硬解。
                     *
                     * 复制模式（mediacodec-copy）为了消黑纹多拷一份，但实测每帧漏一个句柄，
                     * 4K60 每秒约 52 个，十分钟耗尽 32768 个 → 黑屏式闪退。
                     * 网页流都是 SDR、不需要杜比还原，零拷贝既省 GPU 又不漏；Emby 保持复制模式。
                     * 想退回：vr-tuning.txt 写 `web_zerocopy=0`（不用重装）。
                     */
                    /*
                     * 【2026-10-21 00:04 实测：Emby 也在漏】父亲播 Emby 片子时句柄每秒 +24
                     * （正好这片 24 帧）—— 复制模式漏的速率与帧率一一对应，跟片源无关。
                     * 所以普通片子（含 4K SDR）一并改零拷贝；只有**杜比视界**片保留复制模式，
                     * 那条是历史上横向黑纹的来源（10-08 台账：零拷贝与 GL 渲染共用同一帧缓冲，读写不同步）。
                     */
                    val dvSource = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource
                    val dvZero = com.xxxx.emby_vr.vr.VrTuning.dvZeroCopy != 0
                    val zeroCopy = com.xxxx.emby_vr.vr.VrTuning.webZeroCopy != 0 &&
                        (webFirst != null || !dvSource || dvZero)
                    m.setHwdecMode(zeroCopy)
                    Log.i(TAG, "解码方式：" + (if (zeroCopy) "零拷贝 mediacodec" else "复制 mediacodec-copy（杜比视界片）"))
                    if (webFirst != null) {
                        /*
                         * 网页流：请求头 = 页面地址（Referer）+ 浏览器身份（UA），
                         * 会员片再加 Cookie（父亲的会员身份）。音频是分开的一路，用 mpv 的外部音轨挂上。
                         */
                        val hd = mutableMapOf(
                            "Referer" to webStream?.optString("frame").orEmpty(),
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        )
                        val ck = webStream?.optString("cookie").orEmpty()
                        if (ck.isNotBlank()) hd["Cookie"] = ck
                        /*
                         * 【2026-10-21 13:50 失败不要无限重试】默认播放模式是"列表循环"，
                         * mpv 的 loop-file=inf 会让一条打不开的网页流一直重试
                         * （实测 13:43–13:45 每 300ms 一条 405，把播放器卡死，连带别家也接管不了）。
                         * 网页流起播时先关掉循环，由播放模式菜单显式打开才循环。
                         */
                        m.setLoopFile(playModeIndex == 1)
                        m.playStream(
                            directUrl,
                            webFirst.optString("audio").takeIf { it.isNotBlank() },
                            hd,
                            startMs / 1000.0,
                        )
                        /*
                         * 交接追踪（父亲 2026-10-18 02:10 要求"日志弄详细些，看看到底卡在哪"）。
                         * 这一行把内核真正要打开的东西交代清楚：哪个主机、地址多长、
                         * 有没有登录票、Referer 是哪一页 —— 黑屏/403 这类问题一眼能对号。
                         * 令牌串很长，只留主机与末 12 个字符（够认，不淹日志）。
                         */
                        val uHost = try { java.net.URI(directUrl).host ?: "?" } catch (t: Throwable) { "?" }
                        Log.i(
                            TAG,
                            "网页流送进内核：${webFirst.optInt("height", 0)}p，外部音轨=" +
                                webFirst.optString("audio").isNotBlank() + "，会员票=" + ck.isNotBlank(),
                        )
                        Log.i(
                            TAG,
                            "交接追踪：主机=$uHost 地址长=${directUrl.length} 末12=${directUrl.takeLast(12)} " +
                                "登录票=${if (ck.isNotBlank()) "有(${ck.length}字符)" else "无"} " +
                                "Referer=${hd["Referer"]?.take(60)} 站点=${webStreamSite ?: -1}",
                        )
                        startWebStreamWatchdog(uHost, ck.isNotBlank(), ck.length)
                        /* 起播前先到的分片，这会儿播放器有了，补接上去（2026-10-18） */
                        if (webFragMode) flushPendingFrags()
                    } else {
                        /*
                         * 自管解码（`videopipe=1`，默认关）。
                         *
                         * 打开后：视频交给 VideoPipe 用 MediaCodec 直接解到同一张画面纹理，
                         * mpv 只管音频与播放控制（`vid=no`）。绕开 mpv 的输出层是追平 4XVR
                         * 的唯一办法 —— 那一层在 4K 上跑整套色彩管理、每帧还多搬一趟。
                         *
                         * 两条路都要发起播：音频还得靠 mpv 走，位置也由它对齐；
                         * 画面这一段改由 VideoPipe 供。
                         */
                        /*
                         * 起播这一刻直接读文件判断，不依赖调参轮询的当前状态。
                         *
                         * 轮询是周期性的，起播落在两次轮询之间时会读到旧值，
                         * 表现就是「文件里明明写了 videopipe=1，跑的却是老链路」。
                         */
                        val pipeOn = runCatching {
                            java.io.File(
                                getExternalFilesDir(null) ?: filesDir,
                                "vr-tuning.txt",
                            ).readLines().firstOrNull { it.startsWith("videopipe=") }
                                ?.substringAfter("=")?.trim()?.toIntOrNull() == 1
                        }.getOrDefault(false) || com.xxxx.emby_vr.vr.VrTuning.videoPipeOn
                        /*
                         * 新路径只给杜比 P5（2026-11-16 父亲定）。
                         *
                         * 自管解码那条路目前只对 P5 验证过颜色正确；普通片子走它会偏色
                         * （与杜比无关，是这条路自己的色彩处理还没做对）。所以这里再加一道
                         * 按片源的门：不是 P5 就一律交给内核，与 videopipe 开关无关。
                         */
                        val isP5Source = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource &&
                            com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionProfile?.contains("50") == true
                        val useSelfPipe = pipeOn && isP5Source
                        Log.i(TAG, "起播分支判定：开关=$pipeOn 本片P5=$isP5Source 自管解码=$useSelfPipe")
                        /*
                         * 只有一条路（父亲 2026-11-20 定）：音视频全部客户端自管。
                         * 服务端转码那条路不再保留 —— 两套解码器同时活着会互相抢资源，
                         * 而且它把音画同步交给外部服务，客户端这边就再也量不准。
                         */
                        selfPipeActive = useSelfPipe
                        if (useSelfPipe) {
                            startSelfVideoPipe(directUrl, startMs / 1000.0, surface, m)
                        } else {
                            m.play(directUrl, startMs / 1000.0, source = "内核起播")
                        }
                    }
                    /*
                     * 起播时把用户选的缓冲档位下给 mpv（父亲 2026-10-11 问内核这边缓冲起没起作用）。
                     *
                     * 原来只有手动改档位那一刻才下发（applyBuffer），起播时用的是 mpv 默认缓存 ——
                     * 之前设过的大缓存 / 小缓存，新起播都不生效。这里补一次。
                     * 用 m 而不是 mpvBackend：外层那次赋值要等 also 跑完才发生。
                     */
                    val bp = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex]
                    m.applyBufferPreset(
                        seconds = (bp.third / 1000).coerceAtLeast(15),
                        maxBytesMb = when (bufferPresetIndex) {
                            0 -> 256
                            1 -> 512
                            else -> 1024
                        },
                    )
                    /*
                     * 起播后把内核侧轨道清单打进日志（父亲 2026-10-08）。
                     * 切音轨是按顺序映射的，清单里能看到内核认出的音轨 id 与语言，
                     * 万一映射对不上，一眼就能看出来。
                     */
                    scope.launch {
                        kotlinx.coroutines.delay(4000)
                        /*
                         * 轨道清单与画面参数在**后台线程**上打（父亲 2026-10-11）。
                         * mpv 的属性读取是同步的，核心线程在做网络 I/O 时会等很久；
                         * 这两条 dump 读的属性多，放界面线程上就是一次几十秒的假死。
                         */
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            m.dumpTracks()
                            m.dumpVideoParams()
                        }
                        /*
                         * 上次选过的字幕在内核路径要重新选上（2026-10-09）。
                         *
                         * **必须等内核真的报出字幕轨再选**（2026-10-10 修）。
                         * 原来这里固定等 4 秒、只试一次：网盘上那个几 GB 的原文件打开慢的时候，
                         * 4 秒时内核还没报出轨道 → 选不上，而且不再重试 →
                         * 表现就是父亲报的「图片字幕勾了有时不出来，取消再勾一次又出来了」
                         * （第二次文件已就绪，4 秒足够了）。
                         * 现在改成轮询等轨道就绪（最多 20 秒），等到了再选，并打日志。
                         */
                        val sel = selectedSubtitleIndex
                        if (sel != null) {
                            var waited = 0
                            /*
                             * 等内核把文件打开、报出轨道之后再选（按地址加载也要文件已就绪）。
                             * 等待期间用户又改了选择（或停了播放）→ 放弃这次恢复。
                             */
                            while (waited < 20000 && m.subtitleTrackIds().isEmpty()) {
                                kotlinx.coroutines.delay(1000)
                                waited += 1000
                                if (selectedSubtitleIndex != sel) return@launch
                            }
                            Log.i(
                                TAG,
                                "内核模式：恢复字幕 $sel（等了 ${waited}ms，内核共 " +
                                    "${m.subtitleTrackIds().size} 条）",
                            )
                            applyKernelSubtitle(sel)
                        }
                    }
                }
                /*
                 * 把画面切到 VR 银幕（父亲 2026-10-08）。
                 *
                 * 这一条原来在 ExoPlayer 路径的后半段，mpv 分支提前 return 就跳过了 ——
                 * 结果内核明明解出了画面（日志里 `first video frame after restart shown`、
                 * 硬解器 OMX.qcom.video.decoder.hevc 启动成功、原文件也拿到了），
                 * 银幕上却什么都没有：视频层根本没被激活。
                 */
                if (useVrScreen) {
                    com.xxxx.emby_vr.vr.VrNative.setVideoActive(true)
                    Log.i(TAG, "mpv 播放画面已切到 VR 原生（面板收起）")
                }
                /*
                 * 内核路径也要更新画布比例（父亲 2026-10-10 实测《骄阳似我》）：
                 * 那部片是 2.39:1 的宽银幕、走内核播放，而画布比例原来只在系统播放器的
                 * onVideoSizeChanged 里更新 —— 内核路径没更新，画布还是 16:9，
                 * 却被按 2.39 的形状贴出去，于是转圈、片名这些字被压扁（父亲看到的"字体变形"）。
                 */
                /*
                 * 影片自己的形状（父亲 2026-10-16：接管后宽银幕片被拉长，例如《功夫女足》）。
                 *
                 * 这里原来一律用"上一部片子的比例"（PlaybackFlags.videoAspect）——
                 * 网页流接管时那个值还是上一部影片留下的，宽银幕内容落到 16:9 的银幕上
                 * 就被纵向拉长（人变高变瘦）。网页流要按它自己那一档的真实宽高铺。
                 */
                /*
                 * 【2026-10-16 父亲实测定案：宽银幕片"宽撑满了、高度算矮了"的真 bug】
                 *
                 * 原来这里是无条件两句：先按网页流自己的档位设一次比例，紧接着
                 * **又无条件用 kernelAspect（上一部片子留下的值，兜底 16:9）覆盖掉**。
                 * 于是网页接管一部 2.39:1 的宽银幕片、而上一部是 16:9 时，
                 * 银幕最终按 1.778 算高度 = 宽度 / 1.778 → **高度算矮、画面压扁**。
                 * 只在"上一部刚好同比例"时看不出问题，所以表现为"偶尔算错"。
                 *
                 * 现在两条路互斥：网页流用网页流自己的宽高，内核片用内核报的值。
                 */
                if (isWeb) {
                    val ww = webStream?.optInt("width", 0) ?: 0
                    val hh = webStream?.optInt("height", 0) ?: 0
                    if (ww > 0 && hh > 0) {
                        applyScreenAspect(ww.toFloat() / hh.toFloat(), "网页流宽高 $ww x $hh")
                        com.xxxx.emby_vr.vr.VrNative.setVideoSize(ww, hh)
                    } else {
                        /* 【2026-10-23 00:45】网页流没给宽高时不在这里猜，等内核报真实画面尺寸纠偏 */
                        android.util.Log.i(
                            "B0BEmbyVR",
                            "◆比例 网页流没声明宽高（$ww x $hh）→ 不在这里定，等内核报真实画面",
                        )
                    }
                } else {
                    val kernelAspect =
                        com.xxxx.emby_vr.player.PlaybackFlags.videoAspect ?: (16f / 9f)
                    applyScreenAspect(kernelAspect, "内核片的探测比例")
                }
                /*
                 * 等待态**不再直接放行**（2026-10-09 父亲实测）。
                 *
                 * 原来这里写 waitingFirstFrame = false，等于把 clearForNewMedia 刚拉起的
                 * 黑幕 / 转圈 /「即将播放：片名」立刻收掉 —— 表现就是 Profile 5 换片时
                 * 「上一部的画面还在、也不显示即将播放的片名」。
                 * 内核这条路没有 ExoPlayer 的首帧回调，改用原生的「画面已到纹理层」信号收幕
                 * （见 onVideoFrameReady），另有 clearForNewMedia 的 8 秒兜底。
                 */
                osdState.hasPlayback = true

                /*
                 * 起播收尾（父亲 2026-10-08）。
                 *
                 * 这一段原来在 ExoPlayer 路径的后半段，mpv 分支提前 return 就整段跳过了，
                 * 于是：控制条上的片名/进度/时长全空（定时刷新没启动），
                 * 弹幕与片名 logo 也没去拉。
                 * 注意**不要**在这里写 osdState.title —— 那是编号不是片名，
                 * 片名由定时刷新里的 osdTitleText() 取。
                 */
                renderer.videoActive = true
                /*
                 * 内核（mpv）这条路也要把网页面收掉（父亲 2026-10-13 实测：
                 * 银幕上放着网页 / 网页视频时，切一部走内核解码的片子，网页退不出去）。
                 * 系统播放器那条路早就有这一句，mpv 分支提前 return，整段被跳过。
                 */
                /*
                 * 攒分片模式例外（2026-10-19 08:30 修，r713 头显实测）：
                 * 这个模式下网页必须活着才有新段 —— 我们把 setWebKeepSampling(true) 刚设好，
                 * closeWeb() 立刻又把它 setWebKeepSampling(false) + setScreenWeb(false) 推翻
                 * （日志里两句只差 36ms），页面当场停手，手上永远只有接管前那 3 段，
                 * 内核播到 20 秒就连续 404。这里按标记放行，标题照旧由下面 applyWebStreamTitle 写。
                 * 判据带上 isWeb：标记万一残留，也只对网页流生效，播 Emby 片子照旧关网页（不留后台）。
                 */
                if (!(isWeb && webKeepPageAlive)) {
                    closeWeb()
                } else {
                    Log.i(TAG, "攒分片模式：起播不关网页（页面继续攒段，取帧保持开启）")
                }
                /*
                 * closeWeb() 顺手把控制条上的影片信息清了（那是给 Emby 片子用的），
                 * 网页流的标题刚写就被抹掉 —— 父亲 2026-10-16 只看到「哔哩哔哩」。
                 * 所以标题必须写在这一句**之后**。
                 */
                if (isWeb) applyWebStreamTitle(webStream)
                picking = false
                reportedItemId = pendingItemId
                reportedPlaySessionId = pendingPlaySessionId
                reportedMediaSourceId = pendingMediaSourceId
                reportedRunTimeTicks = pendingRunTimeTicks
                /* 网页流：时长用抓来的那一份（Emby 那套身份是上一部片子的，不能拿来算） */
                osdState.durationMs = if (isWeb) {
                    webStream?.optInt("durSec", 0)?.toLong()?.times(1000L) ?: 0L
                } else {
                    pendingRunTimeTicks / 10_000
                }
                osdState.positionMs = 0L
                osdState.speed = playSpeed
                applyPlayMode(playModeIndex)
                startOsdTicker()
                startPosTicker()
                // 弹幕与片名 logo：起播后就去拉，任何一步失败都不影响播放
                // 网页流不是 Emby 片源，这两样没有可拉的（弹幕将来从网页那一路单独抓）
                if (!isWeb) {
                    loadDanmaku()
                    loadItemDetail()
                }
                /*
                 * 内嵌字幕由我们自己画（2026-10-09 父亲实测「字幕还是出不来」）。
                 *
                 * 内核自己那套 libass 在这台设备上找不到字体：日志里明确写着
                 *   sub/assfontselect: failed to find any fallback with glyph 0x0
                 * 截图核对过银幕底部确实一个字都没有。所以改为**取文字、自己画**：
                 * 内核握着文件与时间轴，我们用 `sub-text` 拿到当前这句的纯文本，
                 * 交给已经调好的弹幕层去排版（它画中文/韩文一直是好的）。
                 * 内核自己的那层同时关掉（sub-visibility=no），避免两边都画出现重影。
                 */
                startMpvSubtitlePoll()
                /*
                 * 公共收尾必须在**内核这条路也做**（2026-10-09 查 Profile 5 问题时发现）。
                 *
                 * 这一段原来写在下面 ExoPlayer 那条路的尾部，而内核分支在上面就 return 了
                 * —— 于是 Profile 5 这条路：
                 *   ① **进度一条都不上报**（父亲实测：日志覆盖 10 分钟、零条"进度上报"；
                 *      服务端因此记不住看到哪儿，继续观看也不更新）；
                 *   ② 摇杆快进快退的**状态复位整段被跳过**（上一部的 stickSeekHoldJob /
                 *      stickSeekDir 残留 → 表现为"光柱指着银幕摇杆也不快进快退"）；
                 *   ③ 起播日志也不打。
                 * 这些都是"提前 return 漏掉公共代码"的老毛病，与 10-08 修过的那两次同源。
                 */
                stickSeekHoldJob?.cancel()
                stickSeekHoldJob = null
                stickSeekDir = 0
                stickSeekAccel = false
                hideSeekHud()
                // 外挂 ASS 到底是我们下载的弹幕还是片子自带字幕 —— 后台看内容定（父亲 2026-10-09）
                probeExternalAssForDanmaku()
                /*
                 * 视频层宽度按路径区分（2026-10-09 父亲要求）：
                 * 内核这条链（杜比视界 Profile 5）贵，1664 实测 GPU 15ms 超预算 → 1280。
                 */
                /*
                 * 内核这条链的视频层宽度：杜比视界（P5）那条每帧吃 10~19ms GPU，
                 * 压在 1280 换稳定；只是"因为选了图片字幕"而走内核的普通片没有这笔开销，
                 * 给它 1920（父亲 2026-10-09）。
                 */
                com.xxxx.emby_vr.vr.VrNative.setVideoLayerMaxW(
                    if (com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource) {
                        /* P5 那条链单独压：上限走 video_surface_w（试黑纹就是试这个数） */
                        com.xxxx.emby_vr.vr.VrTuning.videoSurfaceMaxW.let {
                            if (it > 0) it else 1280
                        }
                    } else {
                        com.xxxx.emby_vr.vr.VrTuning.rawMaxW.let {
                            if (it > 0) it.coerceAtMost(com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP) else com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP
                        }
                    },
                )
                /* 网页流不向 Emby 报进度（它不属于 Emby 库；报了会把上一部片子的身份写进去） */
                if (!isWeb) startPlaybackReporting()
                /* Emby 片走内核：清掉网页标记与杜比状态（2026-11-16） */
                com.xxxx.emby_vr.player.PlaybackFlags.webSource = false
                /*
                 * 本次是 P5 且自管解码开着 → 内核这条路**只负责别的事**，不许改色彩目标、
                 * 不许再 play 一次（2026-11-17 父亲实测「偏绿、没声音、进度不走」）：
                 * 两条路同时跑，后跑的这条把色彩目标切回了 SDR，P5 的 IPT 数据被当普通视频
                 * 解释 → 整幅偏绿；它又用 vid=no 重新加载一次，内核直接回 idle → 没声音。
                 */
                val selfPipeOwnsVideo = com.xxxx.emby_vr.vr.VrTuning.videoPipeOn &&
                    com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource &&
                    com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionProfile?.contains("50") == true
                if (selfPipeOwnsVideo) {
                    Log.i(TAG, "P5 + 自管解码：内核这条路跳过起播（画面与颜色归渲染层）")
                    if (!isWeb) startPlaybackReporting()
                    return
                }
                setColorPair(false)
                Log.i(TAG, "开始播放（内核）: $title url=${url.take(160)}")
                return
            }
            // 缓冲档位（更多 → 缓冲设置）：起播缓冲与上限按菜单选的那一档
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    bufferMinMs(),
                    bufferMaxMs(),
                    androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    androidx.media3.exoplayer.DefaultLoadControl
                        .DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()
            /*
             * 音频软解器（父亲 2026-10-07）：头显系统不给第三方应用 AC3/EAC3/DTS 的
             * 解码器，这些音轨以前只能求服务端转码，一转码就卡、音画还会错位。
             * 现在由打包进来的 FFmpeg 扩展自己解。
             * MODE_ON = 系统解码器优先、扩展兜底：AAC 这类仍走系统（省电），
             * 只有系统解不了的才落到软解。
             */
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                )
            player = ExoPlayer.Builder(this, renderersFactory).setLoadControl(loadControl).build().also { p ->
                /*
                 * 字幕语言偏好（父亲 2026-10-07：直连之后字幕不显示了）。
                 *
                 * 直连播放时字幕由客户端自己挑 —— 服务端不参与，而片子里的字幕轨
                 * 一条都没标"默认"（实测《律界战争》内封八条，全 IsDefault=false），
                 * 不主动挑就一条都不显示。这里按中文优先自动选中。
                 * 选中后 Media3 解析内封字幕（子午线文本类），onCues 回调把文字交给
                 * 弹幕层绘制 —— 弹幕层本来就带字幕位。
                 */
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("chi,zh,zho")
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                /*
                 * 起始位置一并交给播放器：服务端虽然按 startTimeTicks 从该位置出流，
                 * 播放器自己仍会从流的第 0 秒开始放 —— 换轨重播「从头开始」就是这个。
                 */
                p.setMediaItem(MediaItem.fromUri(url), startMs.coerceAtLeast(0L))
                p.setVideoSurface(surface)
                p.prepare()
                p.playWhenReady = true
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        /*
                         * 片子实际比例 → 银幕高度（父亲 2026-10-06：有些片子长宽比不对）。
                         * pixelWidthHeightRatio 是像素长宽比，非方形像素的片子要靠它纠正。
                         */
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val a = videoSize.width * videoSize.pixelWidthHeightRatio /
                                videoSize.height
                            applyScreenAspect(a, "解码器报出 ${videoSize.width}x${videoSize.height}")
                            // 纹理尺寸也要给（锐化的邻域步长按真实像素算）
                            com.xxxx.emby_vr.vr.VrNative.setVideoSize(
                                videoSize.width,
                                videoSize.height,
                            )
                            Log.i(TAG, "视频尺寸 ${videoSize.width}x${videoSize.height} 比例 $a")
                        }
                    }

                    /*
                     * 轨清单/选中态变了 → 把"播放器实际选中的字幕"回填到菜单勾选（父亲 2026-10-10）。
                     *
                     * 起因：起播时是**播放器按语言偏好自己挑**的一条（我们只给了
                     * setPreferredTextLanguage("chi,zh,zho")，没设 selectedSubtitleIndex），
                     * 字幕正常显示，但打开字幕菜单一看**一项都没勾**。
                     * 这里把播放器真正选中的那条映射回 Emby 的流序号，回填并刷新菜单。
                     */
                    override fun onTracksChanged(
                        tracks: androidx.media3.common.Tracks,
                    ) {
                        syncSelectedSubtitleFromPlayer()
                        checkRealTrackColor(tracks)
                    }

                    override fun onCues(
                        cueGroup: androidx.media3.common.text.CueGroup,
                    ) {
                        /*
                         * 视频字幕（父亲 2026-10-06 晚）：ExoPlayer 自己不出字幕画面
                         * （视频画面直接走纹理），得把文字接过来自己画。画在弹幕层
                         * 最下方居中，弹幕在上面滚，互不干扰。
                         */
                        /*
                         * 每句也要清洗：Media3 的 SubRip 解析器不认 ASS 排版标记，
                         * 会把 `{\an8}` 原样留在文本里（2026-10-09 父亲实测到乱字符）。
                         */
                        val rawCues = cueGroup.cues.joinToString("\n") { cue ->
                            cue.text?.toString().orEmpty()
                        }
                        /*
                         * 弹幕被播放器当普通字幕选中 → 会从这条回调回来（2026-10-10 修
                         * 「有时候弹幕画在影片下方，上下都有弹幕」）。
                         *
                         * 两条路都要挡：
                         *   ① 文本里带 `\move(` —— 那是滚动弹幕的 ASS 位移标记，普通字幕不会有；
                         *   ② 播放器当前选中的文字轨，标题里带「弹幕 / danmaku」。
                         * 弹幕只由我们自己的弹幕层画（画面上方），不能在底部再当字幕画一遍。
                         */
                        val selectedIsDanmaku = player?.currentTracks?.groups?.any { g ->
                            g.type == androidx.media3.common.C.TRACK_TYPE_TEXT && g.isSelected &&
                                (0 until g.length).any { ti ->
                                    val lbl = g.mediaTrackGroup.getFormat(ti).label.orEmpty().lowercase()
                                    kDanmakuNameHints.any { lbl.contains(it) }
                                }
                        } == true
                        if (selectedIsDanmaku || rawCues.contains("\\move(")) {
                            Log.i(TAG, "字幕回调是弹幕轨（轨命中=$selectedIsDanmaku，" +
                                "位移标记=${rawCues.contains("\\move(")}）→ 丢弃，交给弹幕层画")
                            return
                        }
                        val text = cleanSubtitleText(rawCues)
                        // 用户自己选了字幕时以自绘的那条为准，别被播放器挑的轨盖掉
                        if (subtitleCues.isNotEmpty()) return
                        subtitleNow = text
                        // 等第一帧期间只记内容、不显示（父亲 2026-10-07）
                        if (!waitingFirstFrame) danmakuView?.setSubtitle(text)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // 缓冲/跳转结束 → 提前解冻弹幕位置（父亲 2026-10-07）
                        if (state == Player.STATE_READY) {
                            danmakuSeekFreezeUntilMs = 0L
                            /*
                             * 起播成功 → 控制条的「正在播放」与按钮恢复（父亲 2026-10-07）。
                             * 这条与"收黑幕"分开：收黑幕要等画面真的到（与门），
                             * 而按钮可用只要片子确实开始播了就恢复，不必等第一帧 ——
                             * 否则换轨那类路径会在灰态里停留很久。
                             */
                            if (waitingFirstFrame) {
                                osdState.hasPlayback = true
                                refreshOsdTitleFromItem()
                            }
                        }
                        // 自然播完 → 上报停止（服务端据此记"已看"与进度）
                        if (state == Player.STATE_ENDED) {
                            Log.i(TAG, "播放结束 → 上报停止")
                            reportPlaybackStopped(ctl.durationMs())
                        }
                    }

                    /*
                     * 暂停 / 恢复：暂停时弹幕停在原地（父亲 2026-10-07）。
                     * 用 isPlaying 而不是按钮状态 —— 缓冲卡住、外部暂停也一并覆盖。
                     */
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        /*
                         * 内核在放时，这个回调属于**已经交班的系统播放器**，不许它再动弹幕
                         * （2026-10-17：它一报"没在放"，弹幕就被冻住，整场不滚动）。
                         * 内核那条路的暂停/继续由 `togglePlayPause()` 自己管。
                         */
                        if (mpvBackend != null) return
                        setDanmakuPaused(!isPlaying)
                    }

                    /*
                     * 新片第一帧（ExoPlayer 侧）。见 tryRevealWaitingFrame：
                     * 收黑幕要它和原生"取到帧"**两个条件同时成立**。
                     */
                    override fun onRenderedFirstFrame() {
                        runOnUiThread {
                            playerFrameSeen = true
                            /*
                             * 画面确实渲染出来了 → 再等约 10 帧让原生把它取进纹理，然后收黑幕。
                             *
                             * 父亲 2026-10-07 实测：原来要求"原生也报一次取到帧"，
                             * 而那个回调只在**首次**取到帧时发一次 —— 被我丢弃之后就不再来了，
                             * 与门永远凑不齐，于是声音都出来了黑幕还挂着（最后靠 8 秒兜底收）。
                             * 一帧 11~16ms，150ms 足够盖上纹理延迟，也不会再露出旧画面。
                             */
                            handler.postDelayed({ tryRevealWaitingFrame() }, 150L)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 先停 videoActive（ticker 下一圈自行退出），再写错误提示，
                        // 否则每秒刷新的绿字会把错误盖掉
                        renderer.videoActive = false
                        /*
                         * 出错先试自带内核（父亲 2026-10-11）。
                         *
                         * 父亲点的关键问题：「本该走内核、却交给了服务端转码」的片，
                         * 出错方式不止解码器报错一种 —— 服务端转码**推不出流**（源错误 /
                         * 超时）、流本身解析不了，都算。所以除"网络断了"之外一律先试内核：
                         * 内核直接拉原文件，根本不走服务端转码这条路。
                         * 内核也救不了（已经是内核了）才退回下面换 H.264 重来。
                         */
                        val code = error.errorCode
                        val networkGone =
                            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        if (!networkGone && switchToKernelForFallback("播放出错($code)")) return
                        /*
                         * 崩一次就换 H.264 重来（父亲 2026-10-07：《律界战争》卡在开头）。
                         * 只重试一次，且只影响这一部片 —— 换片时 forceH264 复位。
                         * 位置按崩住的地方接着播，不从头。
                         */
                        if (!forceH264 && currentMediaId.isNotBlank()) {
                            forceH264 = true
                            val at = player?.currentPosition ?: 0L
                            Log.i(TAG, "播放出错 → 改用 H.264 重新起播（位置 ${at / 1000} 秒）")
                            hud("这条流解不了，改用兼容画质重来")
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                playMedia(currentMediaId, at * 10_000L)
                            }
                            return
                        }
                        hud("播放出错：${friendlyError(error)}")
                    }
                })
                /*
                 * 起播看门狗（父亲 2026-10-11）：起播前的线索（元数据 / 文件名）都可能判错，
                 * 所以起播后再用**事实**兜一次 —— 系统播放器若干秒仍出不了画面，
                 * 就改走自带内核重播（位置接着走）。真杜比片在系统解码器上就是这个症状。
                 */
                startKernelFallbackWatchdog()
            }
            renderer.videoActive = true
            /*
             * 大银幕这一路同时只能有一个来源（父亲 2026-10-13 定）：
             * 起播一部 Emby 片子 = 网页面直接退出（不留后台），银幕换成影片。
             * 攒分片模式例外（2026-10-19 修）：这个模式下页面必须活着才有新段，
             * 关掉它等于掐断分片来源 —— 见内核分支同款判断的说明。
             */
            if (!(isWeb && webKeepPageAlive)) closeWeb()
            /*
             * 系统播放器路径 GPU 只要 1.8~2.4ms，视频层可以放开到 1920（2026-10-09）：
             * 只有内核那条（杜比视界 Profile 5）才需要压到 1280 换稳定。
             */
            com.xxxx.emby_vr.vr.VrNative.setVideoLayerMaxW(
                com.xxxx.emby_vr.vr.VrTuning.rawMaxW.let {
                    if (it > 0) it.coerceAtMost(com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP) else com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP
                },
            )
            // 外挂 ASS 是弹幕还是片子自带字幕 —— 后台看内容定（父亲 2026-10-09）
            probeExternalAssForDanmaku()
            picking = false
            /*
             * 大银幕换成影片 = 网页那一路彻底让位，控制条标题也一并交还给片名
             * （父亲 2026-10-16：不然放着自己的片子，控制条上还写着 B站 的名字）。
             */
            osdState.webSiteName = ""
            osdState.title = title
            // 旧片已经停过（停止上报用的是旧身份），新片身份从现在起正式生效 —— 后面的字幕、弹幕、上报都要用它
            /*
             * 用户选过字幕 → 把那一条整条取回来自己画（父亲 2026-10-07）。
             * 没选 → 清掉自绘，交给播放器按语言偏好自动挑。
             */
            selectedSubtitleIndex?.let { idx ->
                /*
                 * 起播恢复上次选的字幕（2026-10-10 再修：**按播放方式分路**）。
                 *
                 * 实测（父亲切画质那条日志）：**服务端转码的流里播放器永远报不出文字轨**，
                 * 于是"等播放器就绪"白等 24 秒，最后还是要退回服务端转 SRT —— 用户看到的就是
                 * "切到 1080p 后中文字幕没了"（其实 25 秒后才出来）。
                 *
                 * 所以：
                 *   · 转码 → **直接走服务端转 SRT 自绘**（服务端这时正在推流、文件是热的，
                 *     实测 0.45 秒返回 1064 条）；
                 *   · 直连（DirectPlay/DirectStream）→ 播放器就地解析（毫秒级，官方也是这套）。
                 */
                val st = currentStreams.firstOrNull { it.index == idx }
                val embeddedText = st != null && st.isExternal != true && !isImageSubtitle(st)
                if (lastPlayMethod == "Transcode") {
                    /*
                     * 转码：**让服务端推字幕**（父亲 2026-10-11 定）。
                     *
                     * 转码时画面本身与进度条有几秒的、忽早忽晚的偏差（服务端切片所致），
                     * 而自绘字幕是跟着进度条走的 → 必然与画面错开。服务端推的字幕与画面
                     * 是同一路流出来的，天然同步。
                     */
                    subtitleCues = emptyList()
                    subtitleCueStream = null
                    selectServerSubtitleTrack(idx)
                } else if (embeddedText && !selfPipeActive) {
                    selectEmbeddedTextTrack(idx)
                } else {
                    Log.i(TAG, "字幕 $idx → 走服务端转 SRT 自绘（播放方式=$lastPlayMethod）")
                    loadSubtitleTrack(idx)
                }
            }
                ?: run {
                    subtitleCues = emptyList()
                    subtitleCueStream = null
                }
            reportedItemId = pendingItemId
            reportedPlaySessionId = pendingPlaySessionId
            reportedMediaSourceId = pendingMediaSourceId
            reportedRunTimeTicks = pendingRunTimeTicks
            osdState.durationMs = pendingRunTimeTicks / 10_000
            osdState.positionMs = 0L
            osdState.speed = playSpeed
            applyPlayMode(playModeIndex)   // 播放模式在起播时也应用一次
            if (com.xxxx.emby_vr.vr.VrNative.vrRunning) {
                /*
                 * 起播不自动亮控制条（父亲 2026-10-06）：要看控制条，指着银幕扣扳机。
                 * 但**重起播**（切字幕 / 音轨 / 质量 / 缓冲、选集换片）时控制条和已经
                 * 展开的菜单都要留着 —— 父亲 2026-10-06 晚明确：不要收起、不要关闭。
                 */
                /*
                 * 起播**不动**控制条（父亲 2026-10-07 18:16）：
                 *   · 关着的保持关 —— 2026-10-06 定的「起播不自动亮控制条」仍然成立；
                 *   · 开着的保持开 —— 从海报墙点一部片起播时，控制条不再被收起。
                 * 收起控制条只由「退出播放」（stopPlaybackInternal）负责。
                 */
                replaying = false
                startOsdTicker()
                startPosTicker()
                /*
                 * 弹幕与片名 logo：起播后就去拉，任何一步失败都不影响播放。
                 *
                 * 网页流不拉（父亲 2026-11-11）：那时 currentMediaId 还是上一部 Emby 片子的
                 * 编号，拉回来的是**旧片**的详情与字幕清单 —— 旧片那份 `.弹幕.ass` 就作为
                 * 字幕轨被内核加载，播腾讯时画面上飘着上一部剧的字幕。网页流没有 Emby
                 * 条目可拉，弹幕走弹幕服务、字幕走网页自己那一路。
                 */
                if (!isWeb) {
                    loadDanmaku()
                    loadItemDetail()
                }
            }
            if (useVrScreen) {
                // 播放时贴视频画面、收起面板（VR 原生播放屏）
                com.xxxx.emby_vr.vr.VrNative.setVideoActive(true)
                Log.i(TAG, "播放画面已切到 VR 原生（面板收起）")
            }
            stickSeekHoldJob?.cancel()
            stickSeekHoldJob = null
            stickSeekDir = 0
            stickSeekAccel = false
            hideSeekHud()
            startPlaybackReporting()
            /*
             * 播放期绿色状态字取消（父亲 2026-10-05：「播放界面的绿色快进快退字体取消」）。
             * 报错提示（起播失败 / 播放出错 / 取不到播放地址）仍保留 —— 那是故障信息。
             */
            /*
             * 系统播放器这条路也要把色彩二元组归零（2026-11-16 父亲实测：
             * 播过 P5 再播别的片，颜色被带偏）。它不走内核，但渲染层仍在看
             * 「本次是不是 P5」这个标志，不清就会把杜比那套颜色公式套到普通画面上。
             */
            setColorPair(false)
            Log.i(TAG, "开始播放: $title url=${url.take(160)}")
        } catch (e: Exception) {
            Log.e(TAG, "起播失败", e)
            hud("起播失败：${friendlyError(e)}")
            renderer.videoActive = false
        }
    }

    // ---- 播放期状态字：已取消（父亲 2026-10-05）----
    //
    // 原来播放中每秒刷一行绿字（播放中 / 快进 10 秒 / 已暂停 → 时间），父亲要求取消；
    // 只有报错提示还走 hud()。

    /**
     * 把技术错误翻成一句人话（给父亲看的屏上提示，不出现英文异常名）。
     * 只翻译能判断的常见情况，翻不出就给「播放失败，原因未知」并把详情留给日志。
     */
    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            t is java.net.UnknownHostException -> "连不上服务器"
            t is java.net.SocketTimeoutException || msg.contains("timeout", true) -> "连接超时"
            t is java.net.ConnectException -> "连不上服务器"
            msg.contains("404", false) -> "地址不存在"
            msg.contains("401", false) || msg.contains("403", false) -> "认证失败"
            msg.contains("HTTP 5", false) -> "服务器内部错误"
            msg.contains("解码", false) || msg.contains("图片", false) -> "图片解码失败"
            msg.contains("HTTP", false) -> "请求失败"
            else -> "播放失败（详情见日志）"
        }
    }

    /** 控制条上的一颗按钮被点了 */
    private fun onOsdButton(button: com.xxxx.emby_vr.panel.OsdButton) {
        Log.i(TAG, "控制条按钮：${button.label}")
        when (button) {
            com.xxxx.emby_vr.panel.OsdButton.SUBTITLE ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE, button)
            com.xxxx.emby_vr.panel.OsdButton.DANMAKU ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.DANMAKU, button)
            // 内核（mpv）这条路上 player 恒为空 —— 判据要用播放接口层，
            // 否则"因图片字幕走内核"的片子上，控制条的快退/快进按钮点了没反应（父亲 2026-10-09）
            com.xxxx.emby_vr.panel.OsdButton.SEEK_BACK -> if (ctl.hasEngine()) seekBy(-10_000)
            com.xxxx.emby_vr.panel.OsdButton.PLAY_PAUSE ->
                /*
                 * 网页还没接管、但手里已经有这一集的地址（2026-10-19 父亲定）：
                 * 这一下按的是"开始播"，不是暂停。
                 */
                if (!ctl.hasEngine() && webPreview != null) playWebPreview()
                else if (!ctl.hasEngine()) startWebWaitForAddress()
                else togglePlayPause()
            com.xxxx.emby_vr.panel.OsdButton.SEEK_FWD -> if (ctl.hasEngine()) seekBy(+10_000)
            // 片头跳过（父亲 2026-10-12）：这颗按钮只在片头范围内存在
            com.xxxx.emby_vr.panel.OsdButton.SKIP_INTRO -> skipIntroManual()
            com.xxxx.emby_vr.panel.OsdButton.SPEED ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.SPEED, button)
            com.xxxx.emby_vr.panel.OsdButton.EPISODES ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.EPISODES, button)
            com.xxxx.emby_vr.panel.OsdButton.PICK -> togglePicking()
            com.xxxx.emby_vr.panel.OsdButton.INFO ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.INFO, button)
            com.xxxx.emby_vr.panel.OsdButton.CAST ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.CAST, button)
            com.xxxx.emby_vr.panel.OsdButton.MORE ->
                toggleMenu(com.xxxx.emby_vr.panel.MenuKind.MORE, button)
            com.xxxx.emby_vr.panel.OsdButton.EXIT -> exitApp()
        }
    }

    /** 再点同一颗按钮 = 收起菜单（父亲 2026-10-06 定） */
    private fun toggleMenu(
        kind: com.xxxx.emby_vr.panel.MenuKind,
        anchor: com.xxxx.emby_vr.panel.OsdButton,
    ) {
        if (menuState.kind == kind) closeMenu() else openMenu(kind, anchor)
    }

    /**
     * 把 assets 里的文件拷到应用目录，返回绝对路径（2026-10-10）。
     *
     * 为什么要拷：原生渲染线程读不到 APK 里的 assets，只认文件路径。
     * 已经拷过且大小一致就跳过 —— 影厅几何 8.5 MB，不必每次启动都重写。
     */
    private fun copyAssetIfNeeded(name: String): String? {
        return runCatching {
            val dir = java.io.File(filesDir, "vr-assets").apply { mkdirs() }
            val out = java.io.File(dir, name)
            /*
             * 不用 assets.openFd()：文件被压缩时它会直接抛异常（2026-10-10 装机踩坑，
             * 影厅几何就是这么"丢"的）。open() + available() 压缩与否都能量出大小。
             */
            val input = assets.open(name)
            val assetLen = input.available().toLong()
            if (out.exists() && out.length() == assetLen) {
                input.close()
                return out.absolutePath
            }
            input.use { ins ->
                java.io.FileOutputStream(out).use { output -> ins.copyTo(output, 64 * 1024) }
            }
            Log.i(TAG, "影厅资源已拷贝：$name ${out.length() / 1024} KB")
            out.absolutePath
        }.getOrNull()
    }

    /** 退出 = 结束进程（父亲 2026-10-06 定：点退出就是退出 B0BEmby VR） */
    private fun exitApp() {
        Log.i(TAG, "控制条：退出应用（结束进程）")
        /*
         * 结束进程前必须先存海报墙摆放：这条退出路径直接 kill 进程，
         * onPause 根本不会执行，之前存的就还是上一次的值（父亲 2026-10-30 实测
         * 「摆好、点退出、再进来位置没变回去」）。
         */
        runCatching { savePanelPlace() }
        runCatching { stopPlaybackInternal() }
        runCatching { com.xxxx.emby_vr.vr.VrNative.stopVr() }
        runCatching { finishAndRemoveTask() }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** 菜单里某一项被点了 */
    private fun onMenuSelect(kind: com.xxxx.emby_vr.panel.MenuKind, index: Int) {
        Log.i(TAG, "菜单选择：${kind.title} #$index")
        when (kind) {
            com.xxxx.emby_vr.panel.MenuKind.MORE -> {
                /*
                 * 第 5 行「选座」（父亲 2026-10-10 17:35「我要坐着看电影」）：
                 * 点一下换一排：近（第 4 排，85°）→ 中（第 6 排，72°）→ 远（第 9 排，60°）→ 近……
                 * 原生侧把整间影厅挪一挪，银幕距离跟着变；视频层、弹幕、光柱、进度环
                 * 都按新距离当场重算，不用重起播、不用重装。
                 */
                if (index == 4) {
                    val next = (menuState.screenPreset + 1) % 3
                    com.xxxx.emby_vr.vr.VrNative.setTuning(
                        com.xxxx.emby_vr.vr.VrTuning.KEY_SCREEN_PRESET,
                        next.toFloat(),
                    )
                    menuState.screenPreset = next
                    Log.i(
                        TAG,
                        "选座 → " + when (next) {
                            0 -> "近排（第 4 排，离幕 9.56 米，水平视野 85°）"
                            1 -> "中排（第 6 排，离幕 12.18 米，水平视野 72°）"
                            else -> "远排（第 9 排，离幕 15.25 米，水平视野 60°）"
                        },
                    )
                    refreshMenuRows(kind)
                    return
                }
                /*
                 * 「座椅调节」（父亲 2026-10-10 22:56）：二级菜单，进去是两条滑条。
                 */
                if (index == 6) {
                    openMenu(com.xxxx.emby_vr.panel.MenuKind.SEAT_ADJUST, com.xxxx.emby_vr.panel.OsdButton.MORE)
                    return
                }
                /*
                 * 3D 设置（父亲 2026-11-02）：点这一行进二级菜单，
                 * 里面四选一：关 / 标准 / 舒适 / 远程。
                 */
                if (index == 7) {
                    openMenu(com.xxxx.emby_vr.panel.MenuKind.STEREO, com.xxxx.emby_vr.panel.OsdButton.MORE)
                    return
                }
                /*
                 * 「影厅亮度」（父亲 2026-10-10）：跟上面四条一样是二级菜单 ——
                 * 点这一行才展开亮度条，条子本身由菜单指针路径拖动（见 onMenuPointer）。
                 */
                if (index == 5) {
                    openMenu(com.xxxx.emby_vr.panel.MenuKind.CINEMA_LIGHT, com.xxxx.emby_vr.panel.OsdButton.MORE)
                    return
                }
                val target = when (index) {
                    0 -> com.xxxx.emby_vr.panel.MenuKind.AUDIO
                    1 -> com.xxxx.emby_vr.panel.MenuKind.QUALITY
                    2 -> com.xxxx.emby_vr.panel.MenuKind.MODE
                    else -> com.xxxx.emby_vr.panel.MenuKind.BUFFER
                }
                // 二级菜单仍停在「更多」按钮正上方
                openMenu(target, com.xxxx.emby_vr.panel.OsdButton.MORE)
            }
            /*
             * 以下四项选中后**菜单不关**（父亲 2026-10-06 晚）：
             * 选完就地更新勾选态，要退回上一层按 B 键。
             */
            com.xxxx.emby_vr.panel.MenuKind.SPEED -> {
                com.xxxx.emby_vr.panel.SPEED_STEPS.getOrNull(index)?.let { applySpeed(it) }
                refreshMenuRows(kind)
            }
            com.xxxx.emby_vr.panel.MenuKind.QUALITY -> {
                applyQuality(index)
                refreshMenuRows(kind)
            }
            com.xxxx.emby_vr.panel.MenuKind.MODE -> {
                applyPlayMode(index)
                refreshMenuRows(kind)
            }
            com.xxxx.emby_vr.panel.MenuKind.BUFFER -> {
                applyBuffer(index)
                refreshMenuRows(kind)
            }
            com.xxxx.emby_vr.panel.MenuKind.DANMAKU -> {
                val n = com.xxxx.emby_vr.panel.DANMAKU_SCALES.size
                var stepPerformed = false
                when {
                    index == 0 -> danmakuOn = !danmakuOn
                    index in 1..n -> com.xxxx.emby_vr.panel.DANMAKU_SCALES
                        .getOrNull(index - 1)?.let { danmakuScale = it.first }
                    index in (n + 1)..(n + 4) -> {
                        // 偏移步进抽成函数：按住连发时只重复这一步，不再重启连发
                        stepDanmakuOffset(index, n)
                        stepPerformed = true
                    }
                    index == n + 5 -> {
                        danmakuOffsetMs = 0
                        Log.i(TAG, "弹幕偏移归零")
                    }
                }
                if (stepPerformed) {
                    placePrefs.edit().putInt("danmaku_offset_ms", danmakuOffsetMs).apply()
                    menuState.danmakuOffsetMs = danmakuOffsetMs
                    startOffsetRepeat { stepDanmakuOffset(index, n) }
                } else {
                    stopOffsetRepeat()
                }
                applyDanmakuSetting()
                Log.i(TAG, "弹幕设置 → ${if (danmakuOn) "开" else "关"}，字号 ${danmakuScale}")
            }
            com.xxxx.emby_vr.panel.MenuKind.SUBTITLE -> {
                val i = index
                /*
                 * 点已勾上的那一条 = 取消勾选（父亲 2026-10-06 晚：不再单列「关闭字幕」，
                 * 取消勾选就起到关闭字幕的作用）。
                 */
                val picked = subtitleStreamIndices.getOrNull(i)
                selectedSubtitleIndex =
                    if (picked != null && picked == selectedSubtitleIndex) null else picked
                Log.i(TAG, "字幕 → ${selectedSubtitleIndex ?: "关闭"}（菜单保持打开）")
                /*
                 * 取消勾选必须**立刻**把屏幕上的字幕清掉（父亲 2026-11-18：
                 * 取消后字幕停在画面上不消失）。
                 * 两条渲染路都要处理：内核自己渲染的（sid=no）与我们自绘的那层（清 cue）。
                 * 只在某一条分支里清，另一条就会留下残影。
                 */
                if (selectedSubtitleIndex == null) {
                    runCatching { applyKernelSubtitle(null) }
                    subtitleCues = emptyList()
                    subtitleCueStream = null
                    subtitleNow = ""
                    runCatching { danmakuView?.setSubtitle("") }
                    Log.i(TAG, "字幕关闭：已清屏上文本并让内核 sid=no")
                }
                /*
                 * 记住这次选择（父亲 2026-10-10 定）：下次从海报墙起播这部片/这一集，
                 * 直接按它来决定"要不要走内核"，不用再点一次字幕菜单。
                 */
                runCatching {
                    /*
                     * 网页流没有 Emby 条目（currentMediaId 已清空）——不写这次的选择，
                     * 否则会用空编号覆盖掉真实片子的字幕记忆。
                     */
                    if (currentMediaId.isNotBlank()) {
                        val st = currentStreams.firstOrNull { it.index == selectedSubtitleIndex }
                        rememberSubtitleChoice(
                            mediaId = currentMediaId,
                            index = selectedSubtitleIndex,
                            isImage = st != null && isImageSubtitle(st),
                        )
                    }
                }
                /*
                 * 勾选要**立刻就位**（2026-10-09 父亲实测：原来不打钩，得关掉菜单再打开才看见）。
                 * 音轨那条分支一直有这句（父亲 2026-10-07 要求"菜单原地更新"），
                 * 字幕这条漏了 —— 同一个毛病，同一个修法。
                 */
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
                if (ctl.kernelActive) {
                    /*
                     * 内核模式（Profile 5）：字幕交给**内核自己渲染**（2026-10-09 父亲拍板）。
                     *
                     * 为什么换掉原来那套"从 Emby 取字幕再自绘"：
                     * P5 是直链播放、Emby 不推流 → 服务端**根本没打开过那个文件**，
                     * 它的字幕接口对这些片子完全不响应。实测（curl，同一台 .15）：
                     *   · P5 那片（item 3930930）字幕编号 2/3/5 → 全部 25 秒超时、零字节
                     *   · 8.1 那片（item 3961760，走 Emby 取流）→ 0.008 秒返回 200
                     * 而内核手里就握着文件，内封字幕轨它自己读得到，还自带 libass 排版
                     * （`{\an8}` 这类 ASS 标记它认识，不会画成乱字符）。
                     *
                     * 选轨方式见 applyKernelSubtitle：文字字幕按**地址**加载 Emby 那一条，
                     * 不再按序号挑（两边清单对不上号，见那个函数的注释）。
                     */
                    applyKernelSubtitle(selectedSubtitleIndex)
                    /* 自绘那套在内核路径不再使用，清掉免得留旧文本 */
                    subtitleCues = emptyList()
                    subtitleCueStream = null
                    subtitleNow = ""
                    danmakuView?.setSubtitle("")
                } else {
                    /*
                     * 普通路径（非 Profile 5）分两种情况（父亲 2026-10-09）。
                     *
                     * ① 内封文字字幕 → **让播放器自己从原片里解析**（秒出）。
                     *    官方客户端就是这个机制：直连播放时字幕是客户端从同一路流里
                     *    就地解出来的，服务端不参与，所以一点就有。我们原来一律走
                     *    "请服务端把内封轨挖成 SRT"，而片源在网盘上，服务端要读完整片
                     *    才挖得出来（实测 30 秒都挖不完，我们一断服务端就取消任务）——
                     *    这就是"切到别的字幕项都没有字幕"的原因。
                     * ② 图形字幕（PGS）→ 只能请服务端烧进画面（必然转码），
                     *    选中时立标记、取消或换文字字幕时清掉 → 回直送。
                     */
                    val stream = currentStreams.firstOrNull { it.index == selectedSubtitleIndex }
                    val image = stream != null && isImageSubtitle(stream)
                    val embeddedText = stream != null && stream.isExternal != true && !image
                    val wasKernelForSubs =
                        com.xxxx.emby_vr.player.PlaybackFlags.forceKernelForImageSubs
                    com.xxxx.emby_vr.player.PlaybackFlags.forceKernelForImageSubs = image
                    when {
                        image -> {
                            /*
                             * 图形字幕（PGS/VobSub）→ **这部片改走自带内核**（父亲 2026-10-09 定）。
                             *
                             * 内核（mpv）自己会画图片字幕，不用转码、不掉画质。
                             * 之前试过"请服务端烧字幕"：服务端确实重编码了，
                             * 但命令里没有任何叠加字幕的滤镜，画面上什么都没有。
                             */
                            Log.i(TAG, "图形字幕 ${selectedSubtitleIndex} → 改走内核播放（内核自己画图片字幕）")
                            if (selfPipeActive) {
                                hud("图形字幕需要内核播放，正在切换（P5 自管路不支持图形字幕）")
                            }
                            replayKeepingPosition()
                        }
                        wasKernelForSubs -> {
                            Log.i(TAG, "取消图形字幕 → 回到系统播放器")
                            replayKeepingPosition()
                        }
                        embeddedText && !selfPipeActive -> {
                            subtitleCues = emptyList()
                            subtitleCueStream = null
                            if (lastPlayMethod == "Transcode") {
                                /*
                                 * 转码：换字幕要**重新出流**（父亲 2026-10-11 定）。
                                 *
                                 * 服务端一次只推请求里那一条字幕轨，所以换轨必须重新请求播放地址；
                                 * 重起播位置接着走，出流后由起播路径选中新那条服务端字幕轨。
                                 */
                                Log.i(TAG, "转码流：换服务端字幕轨 ${selectedSubtitleIndex} → 重新出流")
                                replayKeepingPosition()
                            } else {
                                selectEmbeddedTextTrack(selectedSubtitleIndex!!)
                            }
                        }
                        selectedSubtitleIndex == null -> {
                            // 关字幕：把播放器的文字轨一起关掉，并清掉屏上的字
                            player?.trackSelectionParameters = player!!.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(
                                    androidx.media3.common.C.TRACK_TYPE_TEXT, true,
                                )
                                .build()
                            subtitleNow = ""
                            danmakuView?.setSubtitle("")
                            Log.i(TAG, "字幕关闭：播放器文字轨已关")
                        }
                        else -> {
                            /*
                             * 外挂字幕（我们取服务端转出的文字自绘）：把播放器自己的
                             * 文字轨关掉，免得它在我们的字出来之前又画一份（2026-10-09 复查）。
                             */
                            player?.let { pl ->
                                pl.trackSelectionParameters = pl.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(
                                        androidx.media3.common.C.TRACK_TYPE_TEXT, true,
                                    )
                                    .build()
                            }
                            loadSubtitleTrack(selectedSubtitleIndex!!)
                        }
                    }
                }
            }
            com.xxxx.emby_vr.panel.MenuKind.STEREO -> {
                applyStereoChoice(index)
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.STEREO)
                return
            }
            com.xxxx.emby_vr.panel.MenuKind.AUDIO -> {
                selectedAudioIndex = audioStreamIndices.getOrNull(index)
                Log.i(TAG, "音轨 → 流 ${selectedAudioIndex ?: "默认"}（菜单保持打开）")
                // 勾选立刻移到新音轨（父亲 2026-10-07：菜单要原地更新到新选项）
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.AUDIO)
                if (ctl.kernelActive) {
                    /*
                     * 内核模式（父亲 2026-10-08）：音轨交给内核自己切，不重起播
                     * （重起播会踩同一个 stale Global 崩溃）。
                     * 内核侧的音轨 id 与 Emby 的流序号不是一回事，所以按顺序映射：
                     * Emby 音频清单里的第几个，就取内核侧第几条。
                     */
                    val ordinal = ordinalOfAudioStream(selectedAudioIndex) ?: 0
                    Log.i(TAG, "内核模式：切音轨 流=${selectedAudioIndex ?: "默认"} 序号=$ordinal（失败会重试）")
                    retryKernelTrackSwitch("音轨", selectedAudioIndex) {
                        val m = mpvBackend
                        if (m == null) false
                        else m.setAudioTrackByOrdinal(ordinalOfAudioStream(selectedAudioIndex) ?: 0)
                    }
                } else {
                    replayKeepingPosition()
                }
            }
            com.xxxx.emby_vr.panel.MenuKind.EPISODES -> {
                val id = episodeIds.getOrNull(index)
                val resume = episodePositions.getOrNull(index) ?: 0L
                if (!id.isNullOrBlank()) {
                    Log.i(TAG, "选集 → $id（续播 ${resume / 10_000_000} 秒，控制条与菜单保持打开）")
                    // 换集同样保持界面（父亲 2026-10-07 00:39）：控制条与已展开的菜单不收
                    replaying = true
                    playMedia(id, resume)
                }
            }
            // 信息 / 演职人员：没有可选项，点空白不该把菜单收掉
            else -> Unit
        }
    }

    /** 倍速（菜单里选档） */
    private fun applySpeed(speed: Float) {
        playSpeed = speed
        // 两条内核共用一个入口（父亲 2026-10-08：走播放接口层）
        ctl.setSpeed(speed)
        osdState.speed = speed
        Log.i(TAG, "倍速 → ${speed}x")
    }

    /** 视频质量：换码率上限重起播（服务端据此决定转码档位） */
    private fun applyQuality(index: Int) {
        /*
         * 上限必须按**当前这一条播放路径**的档位表来夹，不能用固定的四档表：
         * 各路径档位数不同（Emby 五档、网页三档、P5 两档、3D 三档），用错表的结果是
         * 多出来的档位点不上 —— 2026-11-08 父亲实测「点 1 兆跳到 5 兆」，就是这里
         * 按四档夹、而 Emby 已改成五档。
         */
        val table = com.xxxx.emby_vr.panel.qualityStepsFor(
            kernelActive = ctl.kernelActive,
            isWeb = webStreamSite != null,
            isP5 = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource,
            stereo3d = com.xxxx.emby_vr.vr.VrTuning.stereo3dOn,
        )
        qualityIndex = index.coerceIn(0, (table.entries.size - 1).coerceAtLeast(0))
        Log.i(TAG, "视频质量 → ${table.entries.getOrNull(qualityIndex)?.second ?: qualityIndex}")
        /*
         * 内核（Profile 5）路径**不重起播**（2026-10-09 父亲要求）。
         * 这条菜单改的是「让 Emby 转码到什么码率」，而 P5 走直链、服务端不推流，
         * 档位对它没有任何作用 —— 重起播只是白等一次，还有踩崩溃的风险。
         * 仅记录选择（下次走 Emby 取流的片源会用到），当前播放保持不动。
         */
        if (ctl.kernelActive) {
            /*
             * 内核这条路上「画质」= **调内核处理分辨率**（父亲 2026-10-10 定）。
             *
             * 为什么不是码率：内核放的是**原始文件直连**（Static=true），服务端不推流，
             * 码率档位对它完全无效。内核每帧开销与像素量成正比，所以这里改成
             * 「内核跑多大」：原画 / 10 兆 → 1280，5 兆 → 1024，1 兆 → 768 ——
             * 当场生效、不重起播。
             *
             * 两种进内核的情形（杜比 P5 / 因为选了图片字幕）**一样处理：都留在内核里**。
             * 尤其图片字幕那类，一旦退回系统播放器字幕就没了（英文片没字幕没法看）。
             */
            /* 内核路径：档位的值就是"内核跑多宽"，按当前是 P5 还是网页流取不同档位表 */
            val steps = com.xxxx.emby_vr.panel.qualityStepsFor(
                kernelActive = true,
                isWeb = webStreamSite != null,
                isP5 = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource,
                stereo3d = com.xxxx.emby_vr.vr.VrTuning.stereo3dOn,
            )
            val w = steps.entries.getOrNull(qualityIndex)?.first
                ?: kernelWidthForQuality(qualityIndex)
            val ok = applyKernelWidth(w)
            Log.i(
                TAG,
                "内核路径：画质档位 → 内核处理分辨率 宽 $w（结果=$ok，" +
                    "杜比=${com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource}）",
            )
            hud(if (ok) "内核处理分辨率 → 宽 $w（码率由片源定）" else "画质由片源决定")
            return
        }
        replayKeepingPosition()
    }

    /**
     * 内核处理分辨率的**硬上限：1280 宽**（父亲 2026-10-10 明确：超过就出黑纹）。
     *
     * 这一条原来写死 1280（治黑纹时代留下的），结果网页 4K 也被一并压在这个宽度上。
     * 现在改成运行时可调：`raw_max_w`（0 = 按片源原分辨率，最高 3840），
     * 调完动一下画质菜单就按新值重建，不用重装 —— 试到哪一档不出黑纹为止。
     */
    private val kKernelWidthCap: Int
        get() = com.xxxx.emby_vr.vr.VrTuning.rawMaxW.let {
            if (it > 0) it.coerceAtMost(com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP) else com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP
        }

    /**
     * 画质档位 → **内核（mpv）处理分辨率的上限宽度**（父亲 2026-10-10 定）。
     *
     * 内核那条链每帧开销与像素量成正比，所以档位在这里的语义是"内核跑多大"：
     *   原画          → raw_max_w 当前值（可调到片源原分辨率，最高 3840）
     *   1080p 10 兆   → 1080
     *   1080p 5 兆    → 1024
     *   1080p 1 兆    → 768
     */
    private fun kernelWidthForQuality(index: Int): Int =
        (when (index) {
            0 -> kKernelWidthCap      // 原画 = 当前上限（运行时可调）
            1 -> 1080
            2 -> 1024
            else -> 768
        }).coerceAtMost(kKernelWidthCap)

    /** 立刻把内核的处理分辨率改成「宽 w」（高度按片源比例算），不重起播 */
    private fun applyKernelWidth(requested: Int): Boolean {
        val m = mpvBackend ?: return false
        val w = requested.coerceAtMost(kKernelWidthCap)   // 硬卡上限
        /*
         * 片源真实尺寸只有一个来源：内核报出的画面尺寸（video-params）。
         * 网页画布与 Emby 播放信息都可能缺、也可能过期，拿它们算会把 4096 的片子算成 1920
         * （表现为切 2560 实际给 1920、切回原画回不去、比例被压扁）。
         * 内核还没报出来就不动，等下一拍再切。
         */
        val real = m.videoSize()
        if (real == null || real.first < 64 || real.second < 64) {
            Log.w(TAG, "内核还没报出画面尺寸，画质切档推迟")
            return false
        }
        val srcW = real.first
        val srcH = real.second
        val vw = if (srcW > w) w else srcW
        val vh = (srcH.toLong() * vw / srcW).toInt().coerceAtLeast(64)
        renderer.setVideoBufferSize(vw, vh)
        m.setSurfaceSize(vw, vh)
        /*
         * 大屏纹理的像素尺寸只在起播时下过 native（VrNative.setVideoSize），切档不下就等于
         * 没切 —— 诊断里「每眼=4096×1716」一直不变，屏幕自然不跟着动。
         */
        runCatching { com.xxxx.emby_vr.vr.VrNative.setVideoSize(vw, vh) }
        Log.i(TAG, "内核处理分辨率 → ${vw}x${vh}（档位上限宽 $w，片源 ${srcW}x$srcH）")
        return true
    }

    /**
     * 网页流这一拍视频层能跑多宽 = 画质菜单当前那一档，再受全局上限约束。
     *
     * 每秒循环与起播后 30 秒的纠正都调这里。两处各自按自己的算法改画布时，
     * 一处按档位、一处按全局上限，结果就是每半秒互相顶回去：菜单停在 2560 而大屏
     * 反复在 4096 与 2560 之间重建。
     */
    private fun webLayerWidthCap(): Int {
        val tier = com.xxxx.emby_vr.panel.qualityStepsFor(
            kernelActive = true,
            isWeb = true,
            isP5 = false,
            stereo3d = com.xxxx.emby_vr.vr.VrTuning.stereo3dOn,
        ).entries.getOrNull(qualityIndex)?.first ?: com.xxxx.emby_vr.vr.VrTuning.VIDEO_W_HARD_CAP
        return tier.coerceAtMost(kKernelWidthCap)
    }

    private fun bitrateForQuality(): Int {
        /* 这里的码率上限只对 Emby 取流有意义，所以取 Emby 那条表（不是固定的四档表） */
        val v = com.xxxx.emby_vr.panel.EMBY_QUALITY_STEPS.entries.getOrNull(qualityIndex)?.first ?: 0
        return if (v <= 0) 200_000_000 else v
    }

    /** 当前画质档的完整定义（码率之外还有分辨率上限与是否强制转码，见 EMBY_QUALITY_TIERS） */
    private fun embyTier(): com.xxxx.emby_vr.panel.EmbyQualityTier =
        com.xxxx.emby_vr.panel.EMBY_QUALITY_TIERS.getOrNull(qualityIndex)
            ?: com.xxxx.emby_vr.panel.EMBY_QUALITY_TIERS.first()

    /** 播放模式：列表循环 / 单集循环 / 播完停止 */
    private fun applyPlayMode(index: Int) {
        playModeIndex = index.coerceIn(0, 2)
        player?.repeatMode = when (playModeIndex) {
            0 -> androidx.media3.common.Player.REPEAT_MODE_ALL
            1 -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        /*
         * 内核这条路同样要跟上（父亲 2026-10-09）：mpv 的循环是与 ExoPlayer 不同的属性，
         * 只设 player?.repeatMode 在内核下等于没设。
         * 播完停止（2）不循环，由下面轮询里的 eof-reached 收场。
         */
        /*
         * 自管解码在播时禁止给内核开循环（2026-11-17）：内核的视频轨是关的，它很快判定
         * `video=eof`，一开循环就从头重放 —— 表现是控制条走到 50 秒左右跳回 0、声音重来。
         */
        if (videoPipe == null) {
            mpvBackend?.setLoopFile(playModeIndex != 2)
        } else {
            mpvBackend?.setLoopFile(false)
        }
        Log.i(TAG, "播放模式 → ${com.xxxx.emby_vr.panel.PLAY_MODE_STEPS.getOrNull(playModeIndex)}")
    }

    /** 缓冲档位：参数在起播时生效，选完重起播一次 */
    private fun applyBuffer(index: Int) {
        bufferPresetIndex = index.coerceIn(0, com.xxxx.emby_vr.panel.BUFFER_PRESETS.size - 1)
        Log.i(TAG, "缓冲档位 → ${com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex].first}")
        /*
         * 内核（Profile 5）路径**不重起播**（2026-10-09 父亲要求）。
         * 这几个档位配的是 ExoPlayer 的 LoadControl，内核用的是 mpv 自己的缓存策略，
         * 重起播并不会让它生效，只是白等一次。仅记录档位，当前播放保持不动。
         * 内核的缓存档位改用 mpv 的 `cache-secs`（若要接，走 mpv_ 透传即可）。
         */
        if (ctl.kernelActive) {
            /*
             * 内核缓冲档位**真的下给 mpv**（父亲 2026-10-09）。
             *
             * 原来只是提示"无效"。现在翻译成 mpv 的 cache-secs / demuxer-max-bytes，
             * 运行中改立刻生效，不用重起播。
             */
            val preset = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex]
            mpvBackend?.applyBufferPreset(
                seconds = (preset.third / 1000).coerceAtLeast(15),
                maxBytesMb = when (bufferPresetIndex) {
                    0 -> 256
                    1 -> 512
                    else -> 1024
                },
            )
            hud("内核缓冲已按「${preset.first}」调整")
            return
        }
        replayKeepingPosition()
    }

    private fun bufferMinMs(): Int = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex].second

    /**
     * 内存缓冲要攒多少秒，直接取控制条缓冲档位的"最多攒多久"。
     *
     * 标准 50s / 较大 90s / 最大 180s；实际字节数由解码器按片源码率折算并封顶 256MB。
     */
    private fun pipeMemWindowSec(): Int {
        /*
         * 关闭（2026-11-21 两次实测都不成立）。
         *
         * 系统解封装（MediaExtractor）只接受一个"能任意位置随机读"的数据源；
         * 我们试过两种：从头顺序滑窗、按请求取块。两次都在起播时判定"容器里没有视频轨"，
         * 表现都是转圈结束后黑屏、无声（日志：`PIPE 起不来：容器里没有视频轨`）。
         *
         * 缓冲改到解封装之后做：解出来的数据包先排队（mpv/ffplay 的结构），
         * 那一层不依赖随机访问，也不再受这个接口的限制。这里先返回 0，等于直读 —— 直读是能播的。
         */
        return 0
    }

    private fun bufferMaxMs(): Int = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex].third

    /** 保持当前位置重新起播（切字幕 / 音轨 / 质量 / 缓冲用） */
    /**
     * 兜底：改走自带内核重播（父亲 2026-10-11 定）。
     *
     * 起因：起播前的两条线索都可能判错 —— 元数据把这部 4K 杜比视界报成 1080p H264，
     * 而文件名里的 DV 也可能只是资源组的习惯写法。光靠线索选解码器，总会碰上选错的片。
     * 所以补运行时的兜底：① 起播若干秒仍出不了画面；② 解码器直接报错。
     * 命中任一条就改走自带内核重播一次，位置接着走。
     * 每部片只自动切一次（kernelFallbackDone），防两边来回切。
     */
    private fun switchToKernelForFallback(reason: String): Boolean {
        if (mpvBackend != null || kernelFallbackDone) return false
        kernelFallbackDone = true
        Log.w(TAG, "兜底：$reason → 改走自带内核重播（位置接着走）")
        hud("这条流系统解不了，改用内核解码重来")
        com.xxxx.emby_vr.player.PlaybackFlags.useKernelDecoder = true
        replayKeepingPosition()
        return true
    }

    /**
     * 从播放器解析出的**真实轨道**核对一次片源色彩（父亲 2026-10-11）。
     *
     * 起播前的线索（元数据 / 文件名）都可能判错，播放器手里的轨道信息才是事实 ——
     * 而且**直推（不转码）时流是原样的，色彩标记齐全**：
     *   · `codecs` 带 `dvhe` / `dvh1` 就是杜比视界，其中 `.05.` 那档（Profile 5）
     *     系统解码器解不出画面、也还原不了颜色；
     *   · `colorInfo.colorTransfer` 是 PQ / HLG 说明是 HDR 流。
     * 命中杜比 5 就切内核；HDR10 只记日志（系统这条路已有颜色映射，不必白软解）。
     *
     * 注意：**转码流判不了** —— 服务端转完码，原始色彩标记已经没了。那条只能靠
     * "不让它重编码"（取播放地址那段的 VideoCodec 判定）加人眼兜底。
     */
    private fun checkRealTrackColor(tracks: androidx.media3.common.Tracks) {
        var codecs: String? = null
        var transfer = -1
        for (group in tracks.groups) {
            if (group.type != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                codecs = f.codecs ?: codecs
                transfer = f.colorInfo?.colorTransfer ?: transfer
            }
        }
        if (codecs == null && transfer < 0) return
        val c = codecs.orEmpty().lowercase()
        val isDv = c.contains("dvh1") || c.contains("dvhe") || c.contains("dovi")
        val dv5 = isDv && (c.contains(".05.") || c.contains("05.0"))
        val hdr = transfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 ||
            transfer == androidx.media3.common.C.COLOR_TRANSFER_HLG
        Log.i(TAG, "真实轨道：codecs=$codecs 色彩传输=$transfer 杜比=$isDv 杜比5=$dv5 HDR=$hdr")
        if (dv5 && mpvBackend == null) {
            switchToKernelForFallback("真实轨道是杜比视界 Profile 5（系统解不出颜色）")
        }
    }

    /**
     * 网页流看门狗（父亲 2026-10-18 02:10 要求"日志详细些，看看到底卡在哪"）。
     *
     * 交接成功 ≠ 有画面。腾讯那次就是：地址交出去了、内核也起来了、请求头也设了，
     * 但服务器回 403（缺登录票），内核一直"等第一帧"，父亲看到的是黑屏，
     * 而现场只有 mpv 内部的英文行。这里每 2 秒记一次进展，12 秒还没画面就写清卡在哪。
     */
    private var webStreamJob: kotlinx.coroutines.Job? = null

    private fun startWebStreamWatchdog(host: String, hasTicket: Boolean, cookieLen: Int) {
        webStreamJob?.cancel()
        webStreamJob = scope.launch {
            var waited = 0L
            while (waited < 12_000L) {
                kotlinx.coroutines.delay(2_000L)
                waited += 2_000L
                if (renderer.videoActive) {
                    Log.i(TAG, "网页流看门狗：${waited / 1000} 秒内出画面了（主机 $host）")
                    return@launch
                }
                Log.w(
                    TAG,
                    "网页流看门狗：${waited / 1000} 秒仍无画面（主机 $host 登录票=" +
                        (if (hasTicket) "有(${cookieLen}字符)" else "无") + "）",
                )
            }
            Log.w(
                TAG,
                "网页流看门狗：12 秒没出画面 → 判定交接后播放失败（主机 $host，" +
                    "登录票=" + (if (hasTicket) "有" else "无") +
                    "）。常见原因：地址过期、缺登录票被服务器拒（403）、或该地址只给网页播放器用。",
            )
        }
    }

    /**
     * 起播看门狗（父亲 2026-10-11）。
     *
     * 「本该走内核、却交给了服务端转码」的片有两种下场，都要兜住：
     *   ① 偏色 —— 机器判断不了，只能从源头堵（见取播放地址那段的 VideoCodec 判定）；
     *   ② 有声音没画面、甚至连声音也没有 —— 服务端转码转不出来 / 推不出流。
     * 这里管第 ② 种，分两档等：已经出声了再等 8 秒还没画面就切；什么都没出来等到 15 秒。
     * 切过去由 switchToKernelForFallback 让内核直接拉原文件，绕开服务端转码这条路。
     */
    private fun startKernelFallbackWatchdog() {
        kernelFallbackJob?.cancel()
        kernelFallbackJob = scope.launch {
            var waited = 0L
            while (waited < 15_000L) {
                kotlinx.coroutines.delay(1_000L)
                waited += 1_000L
                if (mpvBackend != null || playerFrameSeen) return@launch
                val sounding = runCatching { player?.isPlaying == true }.getOrDefault(false)
                if (sounding && waited >= 8_000L) {
                    switchToKernelForFallback("有声无画 ${waited / 1000} 秒")
                    return@launch
                }
            }
            switchToKernelForFallback("起播 15 秒既没画面也没声音")
        }
    }

    private fun replayKeepingPosition() {
        if (currentMediaId.isBlank()) return
        replaying = true
        playMedia(currentMediaId, 0L, keepPosition = true)
    }

    /** 往菜单里填数据（打开菜单时调） */
    private fun fillMenuData(kind: com.xxxx.emby_vr.panel.MenuKind) {
        menuState.serverUrl = userServer()
        // 内核片「视频质量」= 内核跑多大；普通片才是转码档位（父亲 2026-10-10）。
        // 放在这里：无论打开哪个菜单（含"更多"那条当前值）都能拿到正确的说明。
        menuState.kernelActive = ctl.kernelActive
        /* 档位按路径分表：网页流与杜比 P5 各自不同，见 qualityStepsFor 的说明 */
        menuState.isWeb = webStreamSite != null || webPlayingNow
        /*
         * 网页流一律不算杜比 P5（父亲 2026-11-07）：那条路根本没有杜比视界，
         * 标记只可能来自上一部 Emby 片。这里再挡一道，清场漏了也不会显示错档位。
         */
        menuState.isP5 = com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionSource &&
            !menuState.isWeb
        menuState.stereo3d = com.xxxx.emby_vr.vr.VrTuning.stereo3dOn
        when (kind) {
            com.xxxx.emby_vr.panel.MenuKind.SPEED -> menuState.speed = playSpeed
            /* 影厅亮度面板：数值由菜单状态自己带着（menuState.cinemaBright），这里不用填 */
            com.xxxx.emby_vr.panel.MenuKind.CINEMA_LIGHT -> Unit
            /* 座椅调节面板：同样自己带着（seatUp / seatFwd） */
            com.xxxx.emby_vr.panel.MenuKind.SEAT_ADJUST -> Unit
            com.xxxx.emby_vr.panel.MenuKind.QUALITY -> menuState.quality = qualityIndex
            com.xxxx.emby_vr.panel.MenuKind.MODE -> menuState.playMode = playModeIndex
            com.xxxx.emby_vr.panel.MenuKind.BUFFER -> menuState.buffer =
                com.xxxx.emby_vr.panel.BufferView(
                    presetIndex = bufferPresetIndex,
                    minBufferMs = bufferMinMs(),
                    maxBufferMs = bufferMaxMs(),
                    playbackBufferMs = 0,
                    rebufferMs = 0,
                )
            com.xxxx.emby_vr.panel.MenuKind.DANMAKU -> {
                menuState.danmakuOn = danmakuOn
                menuState.danmakuScale = danmakuScale
                menuState.danmakuOffsetMs = danmakuOffsetMs
            }
            com.xxxx.emby_vr.panel.MenuKind.AUDIO -> menuState.audioTracks = buildAudioRows()
            com.xxxx.emby_vr.panel.MenuKind.STEREO -> menuState.stereoRows = buildStereoRows()
            com.xxxx.emby_vr.panel.MenuKind.SUBTITLE -> menuState.subtitleTracks = buildSubtitleRows()
            com.xxxx.emby_vr.panel.MenuKind.EPISODES -> loadEpisodes()
            com.xxxx.emby_vr.panel.MenuKind.INFO, com.xxxx.emby_vr.panel.MenuKind.CAST ->
                loadItemDetail()
            /*
             * 「更多」一级菜单的行尾自带当前值（音频选择/视频质量/播放模式/缓冲设置
             * 四行），但它以前是空实现 —— 只有点进对应子菜单才会把那项数据填进
             * menuState，所以第一次打开「更多」时缓冲那行是空白，点进缓冲设置再返回
             * 才显示出当前值（父亲 2026-10-12 实测反馈）。
             *
             * 改法：把这四行行尾要用到的数据在这里一并填好，用的跟子菜单是同一份来源
             * （bufferPresetIndex / qualityIndex / playModeIndex / buildAudioRows()），
             * 所以不会出现"一级菜单显示的值和子菜单勾的不是一回事"。
             */
            com.xxxx.emby_vr.panel.MenuKind.MORE -> {
                menuState.quality = qualityIndex
                menuState.playMode = playModeIndex
                menuState.audioTracks = buildAudioRows()
                menuState.buffer = com.xxxx.emby_vr.panel.BufferView(
                    presetIndex = bufferPresetIndex,
                    minBufferMs = bufferMinMs(),
                    maxBufferMs = bufferMaxMs(),
                    playbackBufferMs = 0,
                    rebufferMs = 0,
                )
            }
        }
    }

    /** 音轨列表（来自 Emby 的媒体流；选中后由服务端重新出流） */
    private fun buildAudioRows(): List<com.xxxx.emby_vr.panel.MenuRowItem> {
        val audios = currentStreams.filter { it.type == "Audio" }
        audioStreamIndices = audios.mapNotNull { it.index }
        return audios.mapIndexed { i, s ->
            val label = s.displayTitle?.takeIf { it.isNotBlank() }
                ?: s.language?.takeIf { it.isNotBlank() }
                ?: s.codec?.uppercase()
                ?: "音轨 ${i + 1}"
            val extra = listOfNotNull(
                s.codec?.uppercase(),
                s.channels?.let { "$it 声道" },
            ).joinToString(" · ")
            val selected = if (selectedAudioIndex != null) {
                s.index == selectedAudioIndex
            } else {
                s.isDefault == true
            }
            com.xxxx.emby_vr.panel.MenuRowItem(
                label = if (extra.isBlank()) label else "$label（$extra）",
                selected = selected,
            )
        }
    }

    /** 字幕列表：第 0 项固定是「关闭字幕」 */
    private fun buildSubtitleRows(): List<com.xxxx.emby_vr.panel.MenuRowItem> {
        /*
         * 字幕菜单只列字幕，**不出现弹幕**（父亲 2026-10-28 定）。
         *
         * 弹幕不走字幕这条通道：它由我们现拉、自绘在独立弹幕层，开关与字号在「弹幕设置」菜单里
         * （panel/PlayerMenuPanel.kt 的 DanmakuMenu 第一行就是「显示弹幕：开/关」）。
         * 以前为了能在字幕菜单里开关弹幕，把「弹幕」塞成第 0 行，结果两处受伤：
         *   1) 菜单下标整体右移一位，`i - 1` 一旦对不上就落到别的字幕轨上；
         *   2) 弹幕本来就是独立层，混进字幕清单语义上也错。
         *
         * 顺序照官方 Emby 客户端（父亲 2026-10-28 明确）：**内嵌轨在前，Emby 下载的外挂字幕依次列在后**，
         * 组内按 Emby 流序号升序。菜单行与 subtitleStreamIndices 由同一个列表生成，
         * 保证「菜单第几条」与「Emby 流 index」一一对应。
         *
         * 图形字幕（PGS 等）不过滤（父亲 2026-10-09 定）：内核路径能直接画，普通路径选它会转走内核；
         * 这里只留一行日志说明哪几条是图形字幕。
         */
        val subs = currentStreams
            .filter { it.type == "Subtitle" && !isDanmakuStream(it) }
            .sortedWith(compareBy({ it.isExternal == true }, { it.index ?: Int.MAX_VALUE }))
        val images = subs.filter { isImageSubtitle(it) }
        if (images.isNotEmpty()) {
            Log.i(TAG, "字幕菜单：图形字幕 ${images.size} 条 → " +
                images.joinToString(" | ") { "${it.index}:${it.codec}" } +
                "（内核=" + (mpvBackend != null) + "）")
        }
        subtitleStreamIndices = subs.mapNotNull { it.index }
        val rows = mutableListOf<com.xxxx.emby_vr.panel.MenuRowItem>()
        /*
         * 一条字幕都没有时给一行占位，免得菜单整块空白让人以为菜单坏了。
         * 点它等于「不开字幕」（subtitleStreamIndices 为空 → getOrNull 拿不到 → 取消选中），无害。
         */
        if (subs.isEmpty()) {
            rows += com.xxxx.emby_vr.panel.MenuRowItem("（这部片没有字幕）", false)
            return rows
        }
        subs.forEachIndexed { i, s ->
            val label = s.displayTitle?.takeIf { it.isNotBlank() }
                ?: s.language?.takeIf { it.isNotBlank() }
                ?: s.codec?.uppercase()
                ?: "字幕 ${i + 1}"
            /*
             * 两行显示（父亲 2026-10-28，与官方客户端一致）：
             * 主行 = Emby 的 DisplayTitle（如 "Chinese Simplified (默认 SUBRIP)"），
             * 副行 = Emby 的 Title（母语名，如 "中文（简体）"）。
             * 只有一行时中文轨认不出来，看着像全是英文字幕。
             */
            rows += com.xxxx.emby_vr.panel.MenuRowItem(
                label = label,
                selected = s.index == selectedSubtitleIndex,
                sub = s.title?.takeIf { it.isNotBlank() && it != label }.orEmpty(),
            )
        }
        return rows
    }

    /**
     * 当前这次播放**真正在用的**媒体源 id（Emby 的 MediaSourceId）。
     *
     * 字幕、弹幕这些按流序号取内容的地址都要带它 —— 带错就是「HTTP 200 但 0 字节」，
     * 表现为"选了字幕不出字"（2026-10-28 实测《蜘蛛侠》流 22：地址里写的是
     * mediasource_1841404，实际源是 mediasource_2193097，服务端返回空）。
     *
     * 取法按可靠性排序：本次播放缓存的源清单里选中的那个 → 服务端回报的源 →
     * 历史字段（仅当它属于当前这部片）。都不成立才退回标准命名。
     */
    private fun currentSourceId(): String? {
        pickMediaSource(currentSources)?.id?.takeIf { it.isNotBlank() }?.let { return it }
        val sameItem = pendingItemId == currentMediaId
        if (sameItem) {
            pendingMediaSourceId?.takeIf { it.isNotBlank() }?.let { return it }
            reportedMediaSourceId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * 从服务端返回的媒体源清单里挑出"这次要播的那个版本"。
     *
     * 用户选过就用选过的（按 id 精确匹配）；没选过、或那个版本已经不在库里，
     * 退回第一个源（Emby 的默认排序）。
     */
    private fun pickMediaSource(
        sources: List<com.xxxx.emby_vr.data.model.MediaSourceInfoDto>?
    ): com.xxxx.emby_vr.data.model.MediaSourceInfoDto? {
        val list = sources.orEmpty()
        if (list.isEmpty()) return null
        val wanted = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId
        return wanted?.let { w -> list.firstOrNull { it.id == w } } ?: list.firstOrNull()
    }

    /** 这部片上次选的版本（媒体源 id）；没选过返回 null */
    private fun versionChoice(mediaId: String): String? =
        if (mediaId.isBlank()) null else placePrefs.getString("ver_choice_$mediaId", null)

    /** 记住这部片选的版本；sourceId = null 表示恢复默认 */
    private fun rememberVersionChoice(mediaId: String, sourceId: String?) {
        if (mediaId.isBlank()) return
        placePrefs.edit().apply {
            if (sourceId.isNullOrBlank()) remove("ver_choice_$mediaId")
            else putString("ver_choice_$mediaId", sourceId)
        }.apply()
    }

    /**
     * 记住这部片 / 这一集选过的字幕（父亲 2026-10-10 定）。
     *
     * index = null 表示"把字幕关掉"，也照样记住 —— 下次起播不该又自己冒出来。
     * isImage = 是不是图片字幕（PGS/VobSub）：起播时靠它决定要不要**直接走内核**。
     */
    private fun rememberSubtitleChoice(mediaId: String, index: Int?, isImage: Boolean) {
        if (mediaId.isBlank()) return
        val srcKey = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId.orEmpty()
        placePrefs.edit()
            .putString("sub_choice_${mediaId}_$srcKey", "${index ?: -1}|$isImage")
            .apply()
    }

    /**
     * 取这部片 / 这一集记住的字幕；没记过返回 null。
     * 返回的 index 为 null 表示"上次是关字幕"。
     */
    private fun restoreSubtitleChoice(mediaId: String): Pair<Int?, Boolean>? {
        if (mediaId.isBlank()) return null
        /*
         * key 带版本 id（详情页写入的就是这个）：不同版本文件字幕条数不同，
         * 同一个流序号在不同版本里指向的字幕完全不一样，不能串。
         * 读不到再退回旧的、不带版本的 key（老记忆继续有效）。
         */
        val srcKey = com.xxxx.emby_vr.player.PlaybackFlags.mediaSourceId.orEmpty()
        val raw = placePrefs.getString("sub_choice_${mediaId}_$srcKey", null)
            ?: placePrefs.getString("sub_choice_$mediaId", null)
            ?: return null
        val parts = raw.split("|")
        val idx = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val isImage = parts.getOrNull(1)?.equals("true", ignoreCase = true) == true
        return (if (idx < 0) null else idx) to isImage
    }

    /**
     * Emby 的字幕流序号 → **内核侧第几条字幕**（2026-10-10）。
     *
     * 口径与字幕菜单一致：Emby 字幕清单里排除弹幕轨之后的顺序（两边都按文件内顺序）。
     * 为什么不直接用 `subtitleStreamIndices`：那张表是**打开菜单时**重算的，
     * 菜单没开过就可能还是上一部片或空的 —— 拿它去映射会得到错的序号（甚至 -1）。
     * 这里每次从 currentStreams 现算，是权威值。
     */
    /**
     * 内核路径选字幕（2026-10-11 重写）。
     *
     * 旧做法把 Emby 菜单里的"第几条"直接当成内核侧"第几条" —— 错。
     * 内核手里是原文件，它的字幕轨比 Emby 索引出来的多得多（实测《蜘蛛侠：崭新之日》
     * 文件里 38 条内封、Emby 只报 1 条），序号必然错位：父亲选第一条外挂 SRT 出来英文、
     * 选另外两条出来"乱字符"（其实是保加利亚语、捷克语）。
     *
     * 新做法：文字字幕一律让内核按**地址**加载 Emby 那一条（`sub-add`），内容与菜单
     * 一一对应；地址加载不出去时才退回按序号选（序号只数内封轨）。
     * 图形字幕（PGS/VobSub）仍走序号 —— 那是图片，Emby 转不成文字，内核自己读文件才对。
     */
    private fun applyKernelSubtitle(index: Int?) {
        /*
         * 自管解码这条路**没有内核**：mpv 不加载文件，选它的字幕轨必然失败
         * （实测日志："内核没有报出字幕轨，选字幕失败"正是这条）。
         * 自管时文字字幕一律交给服务端转 SRT 自绘；关闭字幕就清屏（2026-11-22）。
         */
        if (selfPipeActive) {
            if (index == null) {
                demuxSubTrackWanted = -1
                demuxSubCues.clear()
                subtitleCues = emptyList()
                subtitleCueStream = null
                subtitleNow = ""
                if (!waitingFirstFrame) danmakuView?.setSubtitle("")
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
                Log.i(TAG, "自管解码：关闭字幕 → 清屏")
            } else {
                /*
                 * 自管解码时字幕由**解封装随播放直接交付**（avformat 一条连接同时读音频与字幕包），
                 * 不再走服务端转 SRT —— 那条路在播放期必然拿不到（网盘两路已被占满，实测 90 秒零字节）。
                 */
                /*
                 * 全包档（demux_mode=full）音频与字幕都走同一条连接，选中的轨会随播放直接交付；
                 * 只有旧路径（off 档，音频走系统解封装）才拿不到字幕包 —— 那时才强制不要字幕。
                 */
                val sess = selfSession
                if (sess == null) {
                    Log.w(TAG, "P5 文字字幕暂不可用（off 档音频走系统解封装，拿不到字幕包）")
                    demuxSubTrackWanted = -1
                } else {
                    val o = demuxSubtitleOrdinal(index)
                    demuxSubTrackWanted = if (o >= 0 && o < sess.subTrackCount) o else -1
                    if (demuxSubTrackWanted < 0) {
                        Log.w(
                            TAG,
                            "自管解码：字幕 $index 不在本文件内（序号=$o 容器文字轨=${sess.subTrackCount}）" +
                                "→ 全包档只出内封文字字幕，外挂/位图轨仍走原路",
                        )
                    }
                }
                /*
                 * **不要清空已攒好的字幕表**：读线程比播放位置早几十秒，选轨那一刻表里已经有
                 * "未来"的条目；清掉以后那段已读过的字幕不会重发，要等播放追上读线程才有新包，
                 * 表现为"选了字幕却长时间不出字"（2026-11-23 真机：表=6 条、首条 452.999s、进度 418s）。
                 */
                refreshDemuxSubtitleCues()
                subtitleNow = ""
                if (!waitingFirstFrame) danmakuView?.setSubtitle("")
                refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
                Log.i(TAG, "自管解码：字幕 $index → 文件内第 $demuxSubTrackWanted 条文字字幕（解封装直供，本轨已攒 ${subtitleCues.size} 条）")
            }
            return
        }
        val m = mpvBackend ?: return
        if (index == null) {
            m.setSubtitleByOrdinal(null)
            return
        }
        val stream = currentStreams.firstOrNull { it.index == index }
        val image = stream != null && isImageSubtitle(stream)
        val mediaId = currentMediaId
        /*
         * 能不能"按序号选"，先看两边清单对不对得上（2026-10-11 父亲实测后定）。
         *
         * 背景：按序号选踩过坑（《蜘蛛侠》Emby 只索引 1 条、文件里 38 条 → 选出来是
         * 英文/保加利亚语），所以改成了按地址加载。但**地址加载要服务器去取字幕**，
         * 对直连播放的片子服务器取不出来 —— 实测 logcat：mpv 等满 60 秒才报
         * `Failed to open .../Subtitles/4/Stream.srt`，这 60 秒里界面线程被按死，
         * 表现就是父亲报的"点一下字幕，控制条和海报墙卡好一阵"，而且字幕压根没出来。
         *
         * 现在两条路各归各位：
         *  · 两边内封轨条数一致 → 序号一定对得上，直接按序号从**文件里**选（秒出、不联网）；
         *  · 对不上（Emby 索引不全）→ 才走地址加载，且放后台执行 + 事后核对。
         */
        if (!image) {
            val embyEmbedded = currentStreams.count {
                it.type.equals("Subtitle", ignoreCase = true) &&
                    !isDanmakuStream(it) && it.isExternal != true
            }
            val kernelEmbedded = m.embeddedSubtitleCount()
            if (kernelEmbedded > 0 && kernelEmbedded == embyEmbedded) {
                Log.i(
                    TAG,
                    "内核模式：字幕 $index 两边内封轨一致（$kernelEmbedded 条）→ 按序号选，不联网",
                )
                retryKernelTrackSwitch("字幕", index) {
                    mpvBackend?.setSubtitleByOrdinal(pickKernelSubtitleOrdinal(index)) ?: false
                }
                return
            }
            if (mediaId.isNotBlank()) {
                val sourceId = currentSourceId() ?: "mediasource_$mediaId"
                val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
                    "/Stream.srt?api_key=${userToken()}"
                m.addSubtitleUrl(url)
                Log.i(TAG, "内核模式：字幕 $index 按地址加载（后台执行，界面不等它）")
                /*
                 * 事后核对：地址加载成功的话内核会多出一条**外挂**字幕轨并选中它。
                 * 等几秒还没有，就退回按序号选 —— 至少字幕要出得来。
                 */
                scope.launch {
                    kotlinx.coroutines.delay(4000L)
                    if (mpvBackend !== m || selectedSubtitleIndex != index) return@launch
                    if (m.externalSubtitleSelected()) {
                        Log.i(TAG, "内核模式：字幕 $index 地址加载已生效")
                        return@launch
                    }
                    Log.w(TAG, "内核模式：字幕 $index 地址加载没生效 → 退回按序号选")
                    retryKernelTrackSwitch("字幕", index) {
                        mpvBackend?.setSubtitleByOrdinal(pickKernelSubtitleOrdinal(index)) ?: false
                    }
                }
                return
            }
        }
        val ord = pickKernelSubtitleOrdinal(index)
        Log.i(TAG, "内核模式：字幕 $index → 内核第 $ord 条（失败会重试）")
        retryKernelTrackSwitch("字幕", index) {
            mpvBackend?.setSubtitleByOrdinal(pickKernelSubtitleOrdinal(index)) ?: false
        }
    }

    /**
     * 内核侧要选"第几条"字幕 —— 先按语言核对，序号只当兜底。
     *
     * 为什么不能只靠序号：Emby 索引出来的内封轨条数可能和文件里的对不上
     * （实测《蜘蛛侠》Emby 报 1 条、文件里 38 条），这时"菜单第几条 = 内核第几条"
     * 必然错位，表现就是父亲报的"选中文出英文"。语言是文件自带的元信息，
     * 与索引无关，所以拿它核对：语言一致的那条才是要的那条。
     *
     * 同名语言有多条时（简繁、粤语）优先按标题关键字对齐；仍分不清就用序号。
     */
    private fun pickKernelSubtitleOrdinal(streamIndex: Int): Int? {
        val base = ordinalOfSubtitleStream(streamIndex)
        val m = mpvBackend ?: return base
        val stream = currentStreams.firstOrNull { it.index == streamIndex } ?: return base
        val infos = m.subtitleTrackInfos().filter { !it.external }
        if (infos.isEmpty()) return base
        val want = normSubLang(stream.language)
        var hit = if (want == null) -1 else infos.indexOfFirst { normSubLang(it.lang) == want }
        if (hit >= 0 && infos.count { normSubLang(it.lang) == want } > 1) {
            // 同一语言多条（简繁 / 粤语）：用标题里的关键汉字再分一次
            val key = listOf("繁", "粵", "粤", "简", "簡", "简体", "繁體")
                .firstOrNull { k -> stream.title?.contains(k) == true }
            if (key != null) {
                val narrowed = infos.indexOfFirst {
                    normSubLang(it.lang) == want && it.title.contains(key)
                }
                if (narrowed >= 0) hit = narrowed
            }
        }
        if (hit >= 0 && hit != base) {
            Log.i(
                TAG,
                "字幕语言核对：流 $streamIndex（${stream.language}）→ 内核第 $hit 条" +
                    "（按序号是 $base，已纠正）",
            )
        }
        return if (hit >= 0) hit else base
    }

    /** 语言标签归一（Emby 用 639-2 如 chi/eng，内核混用 639-1/2） */
    private fun normSubLang(raw: String?): String? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isBlank() || s == "und" || s == "unknown") return null
        return when (s) {
            "chi", "zho", "zh", "zh-cn", "zh-hans", "zh-tw", "zh-hk", "zh-hant" -> "zh"
            "eng", "en" -> "en"
            "jpn", "ja" -> "ja"
            "kor", "ko" -> "ko"
            "fre", "fra", "fr" -> "fr"
            "ger", "deu", "de" -> "de"
            "spa", "es" -> "es"
            "rus", "ru" -> "ru"
            "por", "pt" -> "pt"
            "ita", "it" -> "it"
            "ara", "ar" -> "ar"
            else -> s.take(2)
        }
    }

    private fun ordinalOfSubtitleStream(streamIndex: Int): Int? {
        /*
         * 只数**内封**轨（2026-10-11）：外挂字幕不在内核手里的那个文件里，
         * 把它算进序号会让后面每一条都错位（详见 applyKernelSubtitle 的注释）。
         */
        val subs = currentStreams
            .filter {
                it.type.equals("Subtitle", ignoreCase = true) &&
                    !isDanmakuStream(it) && it.isExternal != true
            }
            .sortedBy { it.index ?: 0 }
        val ord = subs.indexOfFirst { it.index == streamIndex }
        return if (ord >= 0) ord else null
    }

    /**
     * Emby 的音频流序号 → 内核侧第几条音轨（从 currentStreams 现算）。
     *
     * 与字幕同理：菜单那张表是打开菜单时建的，可能过期或为空，拿它映射会得到错的序号。
     */
    private fun ordinalOfAudioStream(streamIndex: Int?): Int? {
        if (streamIndex == null) return null
        val audios = currentStreams
            .filter { it.type.equals("Audio", ignoreCase = true) }
            .sortedBy { it.index ?: 0 }
        val ord = audios.indexOfFirst { it.index == streamIndex }
        return if (ord >= 0) ord else null
    }

    /**
     * 内核侧切音轨 / 字幕：**失败就轮询重试**（2026-10-10 系统排查后统一）。
     *
     * 文件刚打开时内核还没报出轨道，这时候切轨会返回 false —— 原来只打一行日志、
     * 不再重试，用户看起来就是"点了没反应"，得再点一次才成。
     * 现在最多试 10 次、每次隔 800 毫秒；期间用户改了选择就自动放弃（闭包里读的是最新值）。
     */
    private fun retryKernelTrackSwitch(
        label: String,
        streamIndex: Int?,
        attempts: Int = 10,
        apply: () -> Boolean,
    ) {
        scope.launch {
            repeat(attempts) { i ->
                val ok = runCatching { apply() }.getOrDefault(false)
                if (ok) {
                    Log.i(TAG, "内核切$label 成功（第 ${i + 1} 次，流 ${streamIndex ?: "关闭"}）")
                    return@launch
                }
                kotlinx.coroutines.delay(800L)
            }
            Log.w(TAG, "内核切$label 试了 $attempts 次都没成（流 ${streamIndex ?: "关闭"}）")
        }
    }

    /** 把弹幕开关与字号立刻作用到弹幕层（弹幕菜单与字幕菜单里的弹幕行共用） */
    private fun applyDanmakuSetting() {
        menuState.danmakuOn = danmakuOn
        menuState.danmakuScale = danmakuScale
        menuState.danmakuOffsetMs = danmakuOffsetMs
        // 开关与字号立刻作用到弹幕内容：整层常开（它还承载片名 logo），只切轨道
        danmakuView?.userScale = danmakuScale
        danmakuView?.setTrack(if (danmakuOn) danmakuTrack else null)
        /*
         * 这一层常开（等待期要显示「即将播放：片名」，弹幕内容与 logo 仍等第一帧）。
         */
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)
    }

    /** 这条字幕流是不是弹幕轨（ASS / SSA，由自绘弹幕层负责，不当普通字幕选） */
    /** 弹幕文件名/标题里的硬标志（父亲 2026-10-09 定：带「弹幕」两个字才是弹幕） */
    private val kDanmakuNameHints = listOf("弹幕", "danmaku", "danmu")

    /** 弹幕常见的文件后缀：ASS/SSA（ASS 式弹幕）、XML（B 站/弹弹play 导出）、JSON（弹弹play） */
    private val kDanmakuExts = listOf(".ass", ".ssa", ".xml", ".json")

    /**
     * 这一条字幕轨是不是弹幕（父亲 2026-10-09 定的判据）。
     *
     * 起因：原来"格式是 ass/ssa 就算弹幕"，于是《黑帮领地》里内嵌的中文 ASS 字幕被当成
     * 弹幕塞进「弹幕」那一行；后来又出现反例 —— **SubRip 字幕被当成弹幕画到屏幕上方**，
     * 那些`{\an8}`/`\N` 之类的换行转义还照原样画了出来。
     *
     * 现在以**文件名**为准：我们自己和弹幕站生成的弹幕都是 `<片名>.弹幕.ass` 这种，
     * 文件名/标题里带「弹幕」（或 danmaku/danmu）才算弹幕。除此之外只有弹幕专用的
     * XML / JSON 容器（普通字幕基本不用这两种）也归弹幕 —— 免得当字幕画成乱码。
     */
    private fun isDanmakuStream(s: com.xxxx.emby_vr.data.model.MediaStreamDto): Boolean {
        val path = s.path ?: ""
        val pathLower = path.lowercase()
        val title = ((s.displayTitle ?: "") + " " + (s.title ?: "")).lowercase()
        if (kDanmakuNameHints.any { title.contains(it) || pathLower.contains(it) }) return true
        val codec = (s.codec ?: "").lowercase()
        if (s.isExternal == true && (codec == "xml" || codec == "json") &&
            kDanmakuExts.any { pathLower.endsWith(it) }) {
            return true
        }
        /*
         * ③ 名字认不出时看内容（起播后台探一次，见 probeExternalAssForDanmaku）。
         *
         * 我们的弹幕是**从各大视频网站下载的弹幕**再落盘成 ASS，历史上两种命名都用过：
         * 带「弹幕」的（<片名>.弹幕.ass）和跟视频同名的（早期自动改名对齐那种）。
         * 只按文件名判会漏掉后者 —— 那会被当普通字幕画在画面下方、一动不动，
         * 而它其实该在画面**上方滚动**。所以名字认不出时看内容：
         * 里面有大量位移标记的就是弹幕；片子自带的字幕基本不用位移。
         */
        return danmakuByContent[s.index] == true
    }

    /** 内容判定结果：流号 → 是不是弹幕（起播后台探测后填） */
    private val danmakuByContent = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /**
     * 外挂 ASS/SSA 到底是「我们下载的弹幕」还是「片子自带字幕」—— 按内容认一遍
     * （父亲 2026-10-09：弹幕文件放画面**上方**，字幕文件放画面**下方**）。
     *
     * 只探外挂的 ass/ssa（内封的不可能是我们下载的弹幕），拉下文本数位移标记：
     * 够多就是弹幕。判定结果变了才刷新弹幕轨与字幕菜单。
     */
    private fun probeExternalAssForDanmaku() {
        val mediaId = currentMediaId
        if (mediaId.isBlank()) return
        val sourceId = currentSourceId() ?: "mediasource_$mediaId"
        val candidates = currentStreams.filter {
            val c = (it.codec ?: "").lowercase()
            it.type.equals("Subtitle", true) && it.isExternal == true &&
                (c == "ass" || c == "ssa") &&
                !isDanmakuStream(it) && it.index != null && !danmakuByContent.containsKey(it.index)
        }
        if (candidates.isEmpty()) return
        scope.launch {
            var changed = false
            for (st in candidates) {
                val idx = st.index ?: continue
                val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$idx" +
                    "/Stream.ass?api_key=${userToken()}"
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        java.net.URL(url).openConnection().let { c ->
                            c.connectTimeout = 8000
                            c.readTimeout = 20000
                            c.getInputStream().bufferedReader().use { it.readText() }
                        }
                    }.getOrNull()
                } ?: continue
                if (mediaId != currentMediaId) return@launch
                val moves = Regex("\\\\move\\(").findAll(text).count()
                val isDm = moves >= 20
                danmakuByContent[idx] = isDm
                Log.i(TAG, "字幕内容探测：流 $idx 位移标记 $moves 个 → ${if (isDm) "弹幕" else "普通字幕"}")
                if (isDm) changed = true
            }
            if (changed && mediaId == currentMediaId) {
                loadDanmaku()                       // 挂上弹幕层：画面上方滚动
            }
            refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
        }
    }

    /**
     * 图片型字幕（蓝光 PGS / DVD VobSub 等）：是图像不是文字（2026-10-09）。
     *
     * 这种轨服务端**转不出文字** —— 实测请求 `Subtitles/3/Stream.srt` 返回 200 但
     * 0 字节，客户端拿到空内容只能清屏，看起来就是"怎么选都没有字幕"。
     * 所以既不列进字幕菜单，也不当成弹幕。
     */
    private fun isImageSubtitle(s: com.xxxx.emby_vr.data.model.MediaStreamDto): Boolean {
        val c = (s.codec ?: "").lowercase()
        /*
         * 闭路字幕（EIA-608 / CEA-608）是**文字**，不是图片（2026-10-09 复查发现）。
         *
         * 服务端对这种轨常常不回 IsTextSubtitleStream（默认 false），原来只看这个字段
         * 就会把它当图片字幕 —— 选中后触发一次多余的转码烧字幕，白等还降画质。
         * 所以这里先按编码名排除，再看服务端的字段。
         */
        if (c.startsWith("eia") || c.startsWith("cea") || c == "cc608" ||
            c == "eia_608" || c == "cea_608") {
            return false
        }
        if (s.isTextSubtitleStream == false) return true
        return c.contains("pgs") || c.contains("vobsub") || c.contains("dvdsub") ||
            c.contains("dvb") || c.contains("xsub") || c.contains("pgssub")
    }

    /**
     * 流清单（currentStreams）更新后立刻重算字幕 / 音轨菜单内容与索引表。
     *
     * 索引表（subtitleStreamIndices / audioStreamIndices）与菜单内容必须**同源同时**更新：
     * 菜单里点的第 N 项 → 用索引表换回流的 index。两者只要有一个还是上一部片的，
     * 就会出现「选项是别的片子的、点了不生效」（2026-10-10 修）。
     */
    private fun rebuildTrackMenus() {
        menuState.subtitleTracks = buildSubtitleRows()
        menuState.audioTracks = buildAudioRows()
        // 正开着的菜单要立刻看到新内容（音频菜单也一起，同理）
        menuState.kind?.let { refreshMenuRows(it) }
    }

    /** 菜单里选完一项后刷新列表的勾选态（菜单保持打开，勾要跟着动） */
    /**
     * 3D 设置的四个选择（父亲 2026-11-02）：
     * 0 = 关 · 1 = 标准 · 2 = 舒适 · 3 = 远程（深度改在点 15 上算，输入更大）。
     *
     * 视差照 DepthRay 的整数千分位：标准 6‰、舒适 4‰；舒适档另把边缘淡出加宽。
     * 写回 vr-tuning.txt，文件是唯一事实源，只调原生下一秒会被文件里的旧值覆盖。
     */
    private fun applyStereoChoice(index: Int) {
        /* 0 关 / 1 标准（本地）/ 2 舒适（本地）/ 3 远程标准 / 4 远程舒适 */
        val remote = index >= 3
        val mode = when (index) {
            0 -> 0
            2, 4 -> 2
            else -> 1
        }
        val disp = if (mode == 2) 0.009f else 0.014f
        val fade = if (mode == 2) 0.06f else 0.04f
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "stereo_mode", mode.toString())
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "stereo3d", if (mode == 0) "0" else "1")
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "stereo_disp", disp.toString())
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "stereo_fade", fade.toString())
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "depth_remote", if (remote) "1" else "0")
        /*
         * 远程的迟到是"网络 + 推理"两段固定开销，自动估算在配对稀疏时估不准，
         * 直接把环延迟压到能盖住它的值；切回本地立刻恢复自动。
         */
        val ringDelay = if (remote) 160f else 0f
        com.xxxx.emby_vr.vr.VrTuning.setValue(this, "stereo_ring_delay", ringDelay.toString())
        com.xxxx.emby_vr.vr.VrNative.setTuning(
            com.xxxx.emby_vr.vr.VrTuning.KEY_STEREO_RING_DELAY, ringDelay,
        )
        com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_STEREO_DISP, disp)
        com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_STEREO_FADE, fade)
        com.xxxx.emby_vr.vr.VrNative.setTuning(
            com.xxxx.emby_vr.vr.VrTuning.KEY_STEREO3D, if (mode == 0) 0f else 1f,
        )
        menuState.stereoMode = mode
        menuState.stereoRemote = remote
        Log.i(TAG, "3D 设置 → " + when {
            mode == 0 -> "关"
            remote -> "远程（点 15 算深度，视差 $disp）"
            mode == 2 -> "舒适（视差 $disp）"
            else -> "标准（视差 $disp）"
        })
    }

    /** 「3D 设置」二级菜单的四行：当前生效的那一行打勾 */
    private fun buildStereoRows(): List<com.xxxx.emby_vr.panel.MenuRowItem> {
        val cur = when {
            menuState.stereoMode == 0 -> 0
            menuState.stereoRemote -> if (menuState.stereoMode == 2) 4 else 3
            menuState.stereoMode == 2 -> 2
            else -> 1
        }
        val labels = listOf("关", "标准", "舒适", "远程标准", "远程舒适")
        return labels.mapIndexed { i, s -> com.xxxx.emby_vr.panel.MenuRowItem(s, i == cur) }
    }

    private fun refreshMenuRows(kind: com.xxxx.emby_vr.panel.MenuKind) {
        when (kind) {
            com.xxxx.emby_vr.panel.MenuKind.SUBTITLE ->
                menuState.subtitleTracks = buildSubtitleRows()
            com.xxxx.emby_vr.panel.MenuKind.AUDIO -> menuState.audioTracks = buildAudioRows()
            else -> fillMenuData(kind)
        }
    }

    /**
     * 拉这一集的弹幕（2026-10-06 父亲：弹幕没有接进来）。
     *
     * 弹幕在 Emby 里就是一条 ASS / SSA 字幕轨（服务器弹幕系统生成的），
     * 直接把字幕文件拉下来自己解析，用 Media3 的字幕通道不行 —— 它不认 \move，
     * 滚动弹幕会被当成普通字幕堆在画面底部。
     *
     * 任何一步失败都退化成「这一集没有弹幕」，绝不影响播放。
     */
    /**
     * 【2026-10-24 18:20 父亲定：Emby 片也改成「现拉」，不再读服务端下载的弹幕文件】
     *
     * 原因：弹幕原来是 `.弹幕.ass` 放在视频旁边，小雅换目录（当天凌晨《无可替代》从
     * 国产剧/ 挪到 115/）时视频重建、弹幕留在老目录 → 二刷时几集没弹幕。
     * 现在起播时按「剧名 + 集号」向 .18 的按需接口要一份：
     *   http://192.168.150.15:8790/danmaku?title=剧名&ep=1&year=2026&kind=tv
     * 接口那头还是先查腾讯 / 爱奇艺 / 优酷，只是不再落盘。
     * 拿不到（接口不通、源里没有这一集）就退回原来的文件那条路，不影响播放。
     */
    private fun loadDanmaku() {
        danmakuTrack = null
        danmakuView?.setTrack(null)
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)
        val mediaId0 = currentMediaId
        if (mediaId0.isBlank()) {
            loadDanmakuFromEmbyFile()
            return
        }
        scope.launch {
            /*
             * 一定要按**正在播的这一集**取条目，不能直接读 currentItem。
             *
             * currentItem 是 loadItemDetail() 拉完详情才赋值的（异步、比起播晚），
             * 而起播那一刻它还是上一部/上一集的条目 —— 直接用它会把上一集的集号
             * 当成这一集的（实测：画面已经切到第 10 集，却按第 11 集拉了弹幕，
             * 父亲 2026-10-26 14:22 报「弹幕和剧情对不上」）。
             */
            val item = currentItem?.takeIf { it.id == mediaId0 }
                ?: withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fetchItemDetailWithRetry(mediaId0) }.getOrNull()
                }
            if (item == null) {
                loadDanmakuFromEmbyFile()
                return@launch
            }
            val series = item.seriesName
            val isMovie = series.isNullOrBlank()
            val title = if (isMovie) item.name else series
            if (title.isNullOrBlank()) {
                loadDanmakuFromEmbyFile()
                return@launch
            }
            val ep = if (isMovie) 0 else (item.indexNumber ?: 0)
            val year = item.productionYear ?: 0
            val track = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { fetchLiveDanmaku(title, ep, year, isMovie) }.getOrNull()
            }
            if (mediaId0 != currentMediaId) return@launch      /* 中途换集，这份作废 */
            if (track != null) {
                danmakuTrack = track
                danmakuView?.setTrack(if (danmakuOn) track else null)
                Log.i(
                    TAG,
                    "⑦ 现拉弹幕已挂到弹幕层（" + track.items.size + " 条，剧名「" +
                        title.take(20) + "」第 " + ep + " 集）",
                )
            } else {
                Log.i(TAG, "⑦ 现拉没拿到，退回读服务端弹幕文件")
                loadDanmakuFromEmbyFile()
            }
        }
    }

    /**
     * 网页流（三站）起播后按标题/集号拉一份弹幕。
     *
     * 原来弹幕只在 Emby 片子那条路上拉（loadDanmaku 用的是 Emby 的 item），
     * 网页接管这条根本没有 item，于是三站接管后一条弹幕都进不来
     * （父亲 2026-10-26 00:45 报「弹幕没有接进来」）。
     */
    private fun loadDanmakuForWeb(titleRaw: String, epNo: Int, isMovie: Boolean) {
        val title = titleRaw.trim()
        if (title.isEmpty()) {
            Log.i(TAG, "⑦ 网页流弹幕：标题为空，跳过")
            return
        }
        val key = currentMediaId
        scope.launch {
            val track = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { fetchLiveDanmaku(title, epNo, 0, isMovie) }.getOrNull()
            }
            if (key != currentMediaId) return@launch
            if (track != null) {
                danmakuTrack = track
                danmakuView?.setTrack(if (danmakuOn) track else null)
                Log.i(
                    TAG,
                    "⑦ 网页流弹幕已挂上（" + track.items.size + " 条，" +
                        "「" + title.take(20) + "」" + (if (isMovie) "" else " 第 " + epNo + " 集") + "）",
                )
            } else {
                Log.i(TAG, "⑦ 网页流弹幕没拉到（" + title.take(20) + "）")
            }
        }
    }

    /** 向 .18 的按需弹幕接口要一份；成功返回轨道，失败返回 null */
    private fun fetchLiveDanmaku(
        title: String,
        ep: Int,
        year: Int,
        isMovie: Boolean,
    ): com.xxxx.emby_vr.danmaku.DanmakuTrack? {
        val base = "http://192.168.150.15:8790/danmaku"   /* 父亲 2026-10-24 18:27 定：跟 Emby 放一起，常开的是 .15 */
        val q = buildString {
            append("?title=").append(java.net.URLEncoder.encode(title, "UTF-8"))
            if (ep > 0) append("&ep=").append(ep)
            if (year > 0) append("&year=").append(year)
            append("&kind=").append(if (isMovie) "movie" else "tv")
        }
        val txt = httpGetText(base + q, timeoutMs = 40000) ?: return null
        val obj = runCatching { org.json.JSONObject(txt) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok")) {
            Log.i(TAG, "⑦ 现拉接口说没有：" + obj.optString("error"))
            return null
        }
        val arr = obj.optJSONArray("items") ?: return null
        val parsed = ArrayList<Triple<Long, String, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val one = arr.optJSONArray(i) ?: continue
            val ms = one.optLong(0, -1L)
            val t = one.optString(1, "").trim()
            if (ms < 0L || t.isEmpty()) continue
            parsed.add(Triple(ms, t, one.optLong(2, 16777215L)))
        }
        if (parsed.isEmpty()) return null
        Log.i(TAG, "⑦ 现拉拿到 " + parsed.size + " 条（源 " + obj.optString("source") + "）")
        return buildDanmakuTrack(parsed)
    }

    private fun loadDanmakuFromEmbyFile() {
        danmakuTrack = null
        danmakuView?.setTrack(null)
        // 只清弹幕内容，不收整层：这一层还承载提示文字 / 片名 logo（父亲 2026-10-07）
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)

        val mediaId = currentMediaId
        if (mediaId.isBlank()) return
        // 诊断（父亲 2026-10-06 晚：勾了弹幕不显示）：看清这一集到底有没有弹幕轨
        val allSubs = currentStreams.filter { it.type.equals("Subtitle", ignoreCase = true) }
        Log.i(TAG, "弹幕诊断：字幕流 ${allSubs.size} 条 → " +
            allSubs.joinToString(" | ") { "${it.index}:${it.codec}/${it.displayTitle ?: "-"}" })
        val sub = currentStreams.firstOrNull { stream ->
            stream.type.equals("Subtitle", ignoreCase = true) && isDanmakuStream(stream)
        } ?: run {
            Log.i(TAG, "这一集没有弹幕轨（没有 ass / ssa 字幕流）")
            return
        }
        val index = sub.index
        // 片源编号要属于当前这一集（理由同 loadSubtitleTrack，2026-10-09）
        val sourceId = currentSourceId() ?: "mediasource_$mediaId"
        Log.i(TAG, "弹幕诊断：挑中弹幕轨 index=$index source=$sourceId")
        if (index == null || sourceId.isNullOrBlank()) {
            Log.w(TAG, "弹幕轨信息不全，跳过（index=$index source=$sourceId）")
            return
        }
        val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
            "/Stream.ass?api_key=${userToken()}"
        scope.launch {
            /*
             * 弹幕文件：**失败重试 3 次**（2026-10-10 系统排查）。
             * 原来只有一次机会，网络抖一下就是"这一集没弹幕"，而且不再补 ——
             * 明明重新起播就有。重试间隔 1.5 秒，中途换集立刻放弃。
             */
            var raw: String? = null
            for (attempt in 0 until 3) {
                if (mediaId != currentMediaId) return@launch
                raw = withContext(Dispatchers.IO) {
                    runCatching {
                        java.net.URL(url).openConnection().let { conn ->
                            conn.connectTimeout = 8000
                            conn.readTimeout = 15000
                            conn.getInputStream().bufferedReader().use { it.readText() }
                        }
                    }.getOrNull()
                }
                if (!raw.isNullOrBlank()) break
                Log.w(TAG, "弹幕文件没拉到（第 ${attempt + 1} 次）：$mediaId")
                if (attempt < 2) kotlinx.coroutines.delay(1500L)
            }
            if (mediaId != currentMediaId) return@launch          // 中途换集了，丢弃
            val text = raw
            if (text.isNullOrBlank()) {
                Log.w(TAG, "弹幕文件三次都没拉到：$mediaId（这一集先不显示弹幕）")
                return@launch
            }
            val track = runCatching {
                com.xxxx.emby_vr.danmaku.AssDanmakuParser.parse(text)
            }.getOrNull()
            if (track == null || track.items.isEmpty()) {
                Log.w(TAG, "弹幕解析失败或没有内容：$mediaId")
                return@launch
            }
            danmakuTrack = track
            danmakuView?.userScale = danmakuScale
            danmakuView?.setTrack(if (danmakuOn) track else null)
            // 整层常开（承载提示文字与 logo）；弹幕开关只作用到 setTrack
            com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)
            Log.i(
                TAG,
                "弹幕层加载完成：${track.items.size} 条 / 画布 ${track.playResX}x${track.playResY}" +
                    "（${if (danmakuOn) "开" else "关"}）",
            )
        }
    }

    /**
     * 取条目详情，**失败重试 3 次**（2026-10-10 系统排查后统一）。
     * 返回 null 表示三次都没拿到；中途换片直接放弃。
     */
    private suspend fun fetchItemDetailWithRetry(
        id: String,
    ): com.xxxx.emby_vr.data.model.BaseItemDto? {
        repeat(3) { attempt ->
            if (id != currentMediaId) return null
            val item = runCatching {
                EmbyApi.getMediaInfo(
                    context = this@MainActivity,
                    serverUrl = userServer(),
                    apiKey = userToken(),
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    mediaId = id,
                )
            }.getOrNull()
            if (item != null) return item
            Log.w(TAG, "取详情失败（第 ${attempt + 1} 次）：$id")
            if (attempt < 2) kotlinx.coroutines.delay(800L)
        }
        return null
    }

    /** 取这一集的详情：剧集 id / 季 id / 简介 / 演员（信息与演职人员菜单用） */
    private fun loadItemDetail() {
        /*
         * 同上（2026-10-18 00:23）：信息和演职人员也是"打开菜单就去拉"，
         * 放网站视频时不许拉 —— 清空之后又被拉回来，等于没清。
         */
        if (webPlayingNow) {
            menuState.info = null
            menuState.people = emptyList()
            Log.i(TAG, "信息/演职人员：现在放的是网站视频，不去拉 Emby 的条目详情")
            return
        }
        val id = currentMediaId
        if (id.isBlank()) return
        scope.launch {
            try {
                /*
                 * 详情**失败重试 3 次**（2026-10-10 系统排查）。
                 * 原来只试一次：拿不到的话，信息、选集、演员三块全空着，用户看不出为什么。
                 */
                val item = fetchItemDetailWithRetry(id) ?: run {
                    Log.w(TAG, "详情三次都没取到：$id（信息/选集/演员先空着）")
                    return@launch
                }
                if (id != currentMediaId) return@launch   // 中途换片了，丢弃
                currentItem = item
                /*
                 * 换剧才清选集（父亲 2026-11-02：同一部剧切下一集，选集不要被清空）。
                 * 清空动作必须发生在新详情回来、能和旧剧号对比之后，不能放在起播那一刻。
                 */
                val sameSeries = item.seriesId != null && item.seriesId == currentSeriesId
                if (!sameSeries) {
                    menuState.episodes = emptyList()
                    menuState.episodesTitle = ""
                    episodeIds = emptyList()
                    episodePositions = emptyList()
                }
                currentSeriesId = item.seriesId
                currentSeasonId = item.seasonId
                /*
                 * 剧集一开始就把选集列表填好（父亲 2026-11-02）：
                 * 以前要等打开选集菜单才去拉，起播前那几秒里菜单是空的，
                 * 想看下一集没法直接选；现在详情一到就在后台预取，切集也不清空。
                 */
                if (!item.seriesId.isNullOrBlank()) loadEpisodes()
                menuState.info = com.xxxx.emby_vr.panel.MediaInfoView(
                    /*
                     * 第一行显示**剧名**（父亲 2026-10-07）：剧集用所属剧的名字，
                     * 电影没有剧名才退回自己的名字。集名放在下一行的 episodeLine 里。
                     */
                    title = item.seriesName?.takeIf { it.isNotBlank() }
                        ?: item.name ?: osdState.title,
                    year = item.productionYear?.toString() ?: "",
                    runtime = item.runTimeTicks?.let {
                        com.xxxx.emby_vr.panel.osdTimeText(it / 10_000)
                    } ?: "",
                    rating = item.communityRating?.let { "★ %.1f".format(it) } ?: "",
                    genres = item.genres?.take(3)?.joinToString(" / ") ?: "",
                    overview = item.overview ?: "",
                    officialRating = item.officialRating ?: "",
                    episodeLine = buildString {
                        val sn = item.parentIndexNumber
                        val ep = item.indexNumber
                        if (sn != null && ep != null) append("第 $sn 季 第 $ep 集")
                        val nm = item.name
                        if (!nm.isNullOrBlank() && nm != item.seriesName) {
                            if (isNotEmpty()) append(" · ")
                            append(nm)
                        }
                        // 剧名不在这里重复 —— 它已经是信息面板的第一行（父亲 2026-10-07）
                    },
                    techLine = techLineOf(),
                )
                /*
                 * 海报（父亲 2026-10-06 晚定）：剧集用**剧的海报**，不要当前这一集的
                 * 剧照；电影没有剧集 id 才用自己那张。照电视版信息面板的做法。
                 */
                val seriesId = item.seriesId
                val seriesTag = item.seriesPrimaryImageTag
                menuState.posterUrl = when {
                    !seriesId.isNullOrBlank() && !seriesTag.isNullOrBlank() ->
                        "${userServer()}/emby/Items/$seriesId/Images/Primary" +
                            "?maxWidth=520&tag=$seriesTag&quality=90&api_key=${userToken()}"
                    !seriesId.isNullOrBlank() ->
                        "${userServer()}/emby/Items/$seriesId/Images/Primary" +
                            "?maxWidth=520&quality=90&api_key=${userToken()}"
                    else -> item.imageTags?.get("Primary")?.let { tag ->
                        "${userServer()}/emby/Items/$id/Images/Primary" +
                            "?maxWidth=520&tag=$tag&quality=90&api_key=${userToken()}"
                    }
                }
                // 演职人员头像
                menuState.people = (item.people ?: emptyList()).take(40).map { p ->
                    val pid = p.id
                    val ptag = p.primaryImageTag
                    com.xxxx.emby_vr.panel.PersonItem(
                        name = p.name ?: "",
                        role = p.role ?: "",
                        avatarUrl = if (!pid.isNullOrBlank() && !ptag.isNullOrBlank()) {
                            "${userServer()}/emby/Items/$pid/Images/Primary" +
                                "?maxHeight=420&tag=$ptag&quality=90&api_key=${userToken()}"
                        } else {
                            null
                        },
                    )
                }
                /*
                 * 片名 logo（父亲 2026-10-06）：剧集 / 单集用所属剧集的 Logo，
                 * 电影用条目自己的 Logo（与电视版同一套规则，tag 缺了会 404 就不显示）。
                 */
                val sid = item.seriesId
                val ownLogoTag = item.imageTags?.get("Logo")
                val logoAddr = when {
                    !sid.isNullOrBlank() ->
                        "${userServer()}/emby/Items/$sid/Images/Logo" +
                            "?maxHeight=200&api_key=${userToken()}"
                    !ownLogoTag.isNullOrBlank() ->
                        "${userServer()}/emby/Items/$id/Images/Logo" +
                            "?maxHeight=200&tag=$ownLogoTag&api_key=${userToken()}"
                    else -> null
                }
                logoUrl.value = logoAddr
                Log.i(TAG, "片名 logo 地址 → ${logoAddr ?: "（这一集没有 logo）"}")
                loadLogoBitmap(logoAddr)
                Log.i(
                    TAG,
                    "详情已取到：${item.name}（演员 ${menuState.people.size} 人" +
                        "，logo ${if (logoAddr.isNullOrBlank()) "无" else "有"}）",
                )
                // 等待期把片名补进提示（父亲 2026-10-07：剧集要显示「剧名 + 第X集 + 集名」）
                if (waitingFirstFrame) {
                    val t = itemTitleOf(item)
                    if (t.isNotBlank()) danmakuHint.value = "即将播放：$t"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "取详情失败", t)
            }
            /*
             * 菜单开着时数据变了要**就地更新**（父亲 2026-10-07：点了另一集、或者换了
             * 音轨，菜单里的勾选要跟着移到新选项 —— 不能还停在旧的那条）。
             */
            menuState.kind?.let { refreshMenuRows(it) }
        }
    }

    /** 选集列表（同一季的剧集；当前集打勾） */
    private fun loadEpisodes() {
        /*
         * 【2026-10-18 00:23 父亲实测：正在放 B站 视频，打开"选集"却是 60 集（上一部 Emby 片的）】
         *
         * 换源清场确实把 episodes 清空了，但菜单**一打开就去重新拉** —— 而 currentSeriesId
         * 还指着上一部 Emby 片子，于是刚清掉的列表又被原样拉回来。
         * 正在放网站视频时，这条路直接不走：网站视频的剧集由网页自己选（父亲 2026-10-17 定，
         * 客户端不做网站选集）。
         */
        if (webPlayingNow) {
            menuState.episodes = emptyList()
            menuState.episodesTitle = ""
            Log.i(TAG, "选集：现在放的是网站视频，不去拉 Emby 的剧集列表")
            return
        }
        val seriesId = currentSeriesId
        if (seriesId.isNullOrBlank()) {
            menuState.episodes = emptyList()
            Log.w(TAG, "选集：还不知道剧集 id（详情还没取回来）")
            return
        }
        val seasonId = currentSeasonId
        scope.launch {
            try {
                val eps = EmbyApi.getEpisodes(
                    context = this@MainActivity,
                    serverUrl = userServer(),
                    apiKey = userToken(),
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    seriesId = seriesId,
                    seasonId = seasonId,
                )
                episodeIds = eps.map { it.id ?: "" }
                episodePositions = eps.map { it.userData?.playbackPositionTicks ?: 0L }
                /*
                 * 季号提出来当**菜单标题**（父亲 2026-10-10）：
                 * 原来每一行都写"第 X 季 第 Y 集"，季号重复刷屏；
                 * 现在标题写一次"第 X 季"，下面每行只写"第 Y 集 集名"。
                 */
                val seasonNo = eps.firstOrNull()?.parentIndexNumber
                    ?: currentItem?.parentIndexNumber
                menuState.episodesTitle = if (seasonNo != null) "第 $seasonNo 季" else ""
                menuState.episodes = eps.mapIndexed { i, e ->
                    val num = e.indexNumber ?: (i + 1)
                    val name = e.name ?: ""
                    com.xxxx.emby_vr.panel.MenuRowItem(
                        label = if (name.isBlank()) "第 $num 集" else "第 $num 集  $name",
                        selected = e.id == currentMediaId,
                    )
                }
                Log.i(TAG, "选集列表 ${eps.size} 集（标题=${menuState.episodesTitle}）")
            } catch (t: Throwable) {
                Log.e(TAG, "取选集失败", t)
                hud("取选集失败")
            }
        }
    }

    /** 倍速循环：1.0 → 1.25 → 1.5 → 2.0 → 0.75 → 1.0 */
    private fun cycleSpeed() {
        val steps = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        val idx = steps.indexOfFirst { kotlin.math.abs(it - playSpeed) < 0.01f }
        playSpeed = steps[(idx + 1) % steps.size]
        // 走播放接口层（父亲 2026-10-08）：内核模式下倍速同样生效
        ctl.setSpeed(playSpeed)
        osdState.speed = playSpeed
        Log.i(TAG, "倍速 → ${playSpeed}x")
    }

    /**
     * 「选片」开关（父亲 2026-10-05 定：点击打开海报，再点关闭海报）。
     *
     * 打开：暂停播放、画面切回面板（面板本身就是海报墙），控制条留着 —— 于是
     * 「再点一次选片」仍然点得到；选中别的片（面板里点卡片）会自动起播并关掉选片态。
     * 关闭：画面切回视频、继续播放。
     */
    /**
     * 「选片」开关（父亲 2026-10-05 定：点一下把海报墙收起来，再点一下摆出来）。
     *
     * 海报墙现在常驻左手边、斜着摆，播放不受影响 —— 这个按钮只控制它出不出现，
     * 不再像以前那样为了看海报把播放画面顶掉。
     */
    private fun togglePicking() {
        val show = !com.xxxx.emby_vr.vr.VrNative.panelShown
        com.xxxx.emby_vr.vr.VrNative.updatePanelShown(show)
        Log.i(TAG, if (show) "选片：海报墙摆出来" else "选片：海报墙收起来")
    }

    private fun togglePlayPause() {
        // 走播放接口层：内核模式下也生效（父亲 2026-10-08）
        if (!ctl.togglePlayPause()) {
            Log.w(TAG, "暂停/继续：当前没有可操作的播放引擎")
            return
        }

        /*
         * 弹幕跟着暂停/继续。
         *
         * 【2026-10-17】原来只有系统播放器的 `onIsPlayingChanged` 会冻/解冻弹幕，
         * 内核（B站 接管那条路）没有 —— 补上，两个引擎一个口径。
         */
        setDanmakuPaused(!ctl.isPlaying())
    }

    /** 最近一次 seek 的目标位置与发起时间（毫秒）；用于连跳时的基准 */
    private var seekTargetMs: Long? = null
    private var seekTargetAt = 0L

    /** 跳转中止的统一出口：把时钟恢复回跳转前的位置 */
    /** 等"第一套新鲜参数"到达再解除保持；8 秒兜底（豆豆 2026-11-21） */
    private fun waitFreshThenUnhold(tries: Int) {
        if (com.xxxx.emby_vr.vr.VrNative.doviFreshReady() || tries >= 80) {
            if (tries >= 80) Log.w(TAG, "保持超时 8 秒：新鲜参数仍未到达，强制解除")
            com.xxxx.emby_vr.vr.VrNative.setDoviHold(false)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ waitFreshThenUnhold(tries + 1) }, 100L)
    }

    private fun abortJump(why: String) {
        val from = jumpFromSec
        jumpFromSec = -1.0
        /* 先作废这次尝试：看门狗、首帧回调、音频回调都会据此退场 */
        selfTx.incrementAndGet()
        if (from < 0.0) return
        Log.e(TAG, "跳转中止：$why → 时钟回到 ${"%.2f".format(from)}s")
        runCatching { playClock?.start((from * 1_000_000.0).toLong()) }
        /* 闸门不能再关着，否则画面永远不上屏（17:22 现场：队列涨到 639 秒） */
        selfPipeWaitingFirstFrame.set(false)
        selfPipePhase = "起播失败"
        com.xxxx.emby_vr.vr.VrNative.setDoviHold(false)
    }

    private fun seekBy(deltaMs: Long) {
        if (!ctl.hasEngine()) return
        /*
         * 银幕上的进度条在这里统一触发（父亲 2026-10-16：快进快退时银幕上不出进度条）。
         * 原来只有"长推摇杆"那一路会显示，短推（推一下立刻回中）和控制条上的按键
         * 都走别处，于是那条进度条在多数情况下根本不出现。
         */
        showSeekHud()
        // ExoPlayer 的 seekTo 是异步的：连续快进时上一跳还没落地，
        // currentPosition 仍是旧值，基于它算下一跳会越跳越偏
        // （父亲 2026-10-04 实测：连跳几次落点与预期不符）。
        // 500ms 内的连跳一律以「上一跳的目标」为基准，之后回归真实位置。
        // 位置从播放接口层取 —— 内核模式下原来取的是空播放器，快进按了没反应。
        val withinChain = seekTargetMs != null &&
            System.currentTimeMillis() - seekTargetAt < 500
        val base = if (withinChain) seekTargetMs!! else ctl.positionMs()
        val target = (base + deltaMs).coerceAtLeast(0L)
        seekTargetMs = target
        seekTargetAt = System.currentTimeMillis()
        // 快进 / 快退期间弹幕停在原地（父亲 2026-10-07：不然会一跳一跳）
        freezeDanmakuForSeek()
        ctl.seekTo(target)
        // 进度条（HUD）正显示时把目标位置立刻写进去：条与游标跟手，不等下一拍
        if (seekHudShown) {
            danmakuView?.setSeekHud(true, target, ctl.durationMs(), ctl.bufferedMs())
        }
        val sec = target / 1000
        Log.i(TAG, "seek ${deltaMs / 1000}s → ${sec / 60}:${"%02d".format(sec % 60)} (基准 ${if (withinChain) "连跳" else "实时"})")
        // 状态绿字已取消（父亲 2026-10-05），跳转结果只进日志
        Log.i(TAG, "跳转落点 ${sec / 60}:${"%02d".format(sec % 60)}")
    }

    /** 停止播放，回到界面（面板层） */
    private fun stopPlayback() {
        // 先取位置再释放播放器：这一条决定服务端记住看到哪儿
        // （位置走播放接口层，内核模式下同样有效）
        reportPlaybackStopped(ctl.positionMs() * 10_000)
        stopPlaybackInternal()
        renderer.videoActive = false
        renderer.setHudText("")
        /*
         * 【2026-10-22 修 P0】停的是网页流 → 一并把网页流状态复位。
         * 原来只停播放器、状态留着：控制条继续被旧片名刷、返回键还会把人带回网页。
         */
        if (webPlayingNow) {
            resetWebStreamState("停止播放网页流")
            clearFilmInfoFromOsd()
        }
        Log.i(TAG, "停止播放，回到界面")
    }

    // ---- 播放进度上报（父亲 2026-10-05：app 记不住看到第几集） ----
    //
    // 为什么以前记不住：电视版的进度上报在**播放屏 ViewModel**里
    // （PlayerViewModel.reportPlaying/Progress/Stopped），VR 版把播放屏换成了
    // 原生播放器，那三个调用点跟着一起没了 —— 于是本地播得再欢，服务端一无所知，
    // 「继续观看」和详情页永远停在别的客户端最后一次上报的位置（=第 17 集）。
    //
    // 现在按 Emby 的标准三件套上报：开始 / 每 10 秒进度 / 停止。
    /*
     * 两个身份，别混：
     *   pending*  —— 这次**要播的**那部（取播放地址时就定好了）
     *   reported* —— 此刻**正在播的**那部（服务端上报用）
     *
     * 2026-10-07 父亲实测《无可替代》不上报的根因就在这：
     * 原来两者共用一个字段 —— 取新片地址时先把它改成新片，紧接着换片流程
     * 「停掉旧片」把同一个字段清成 null，等真的起播要报「开始播放」时已经没身份了，
     * 三次上报全被 `?: return` 挡掉；服务端只收到一条「停止 0 秒」的脏数据。
     */
    private var pendingItemId: String? = null
    private var pendingPlaySessionId: String? = null
    private var pendingMediaSourceId: String? = null
    /**
     * PlaybackInfo 里那条片源的**真实地址**（走网盘挂载的条目是 strm 里的网络地址）。
     * 中转直连网盘要靠它拼小雅 /d/ 地址，见 [alistUrlOf]。
     */
    private var pendingSourcePath: String? = null
    private var pendingRunTimeTicks: Long = 0L

    private var reportedItemId: String? = null
    private var reportedPlaySessionId: String? = null
    private var reportedMediaSourceId: String? = null
    private var reportedRunTimeTicks: Long = 0L
    private var progressJob: kotlinx.coroutines.Job? = null

    /**
     * 播放链路（取播放地址 + 进度上报）用的身份。
     *
     * 原来这一层用的是服务端 API Key（`BuildConfig.EMBY_API_KEY`，控制台里那把「DSH」），
     * 它**没有用户身份**：Emby 给 VR 建的会话挂不上任何用户，播放进度因此写不进用户数据
     * （父亲 2026-10-07 实测：《无可替代》在 VR 里播多少集，服务端进度都不动；
     * 服务端 /Sessions 里 VR 的会话 UserId 为空，而界面层那条会话挂着 wangbob）。
     * 现在统一用登录后拿到的用户令牌 + userId —— 面板层一直用的是这一套，所以读一直是对的。
     */
    private val embySession get() = com.xxxx.emby_vr.data.session.SessionManager.getInstance(this)

    private fun userToken(): String = embySession.apiKey ?: BuildConfig.EMBY_API_KEY

    /*
     * 手动地址入口（2026-10-16，诊断用，可长期保留）。
     *
     * 用途：把"抓地址"和"能不能播"两件事分开验 —— 扩展那条路还没修好时，
     * 也能先确认我们的播放器在头显上真能把这条流播起来。
     *
     * 约定：外部存储放了 vr-manual-stream.json 就直接播它，格式与网页抓来的那份一致：
     *   {"kind":"play","frame":"https://www.bilibili.com/","durSec":0,
     *    "streams":[{"id":30080,"height":1080,"width":1920,"video":"…","audio":"…"}]}
     * 路径：/sdcard/Android/data/com.xxxx.emby_vr/files/vr-manual-stream.json
     * （用 adb push 写，不需要应用申请存储权限；地址有效期约 2 小时，用完即删。）
     */
    /**
     * 取 strm 里写的原始播放地址（.15 上的只读接口）。
     * 拿不到返回 null，调用方回退服务端那条路。
     */
    private suspend fun fetchStrmUrl(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val host = userServer().substringAfter("://").substringBefore(":").substringBefore("/")
            val q = java.net.URLEncoder.encode(path, "UTF-8")
            val conn = (java.net.URL("http://$host:7791/strm?path=$q")
                .openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "取 strm 直链失败：${e.message}")
            null
        }
    }

    private fun userServer(): String = embySession.serverUrl ?: BuildConfig.EMBY_SERVER

    private fun playbackReportBody(
        itemId: String,
        positionTicks: Long,
        isPaused: Boolean,
        eventName: String? = null,
    ): Map<String, Any?> = buildMap {
        put("ItemId", itemId)
        put("MediaSourceId", reportedMediaSourceId ?: "")
        put("PlaySessionId", reportedPlaySessionId ?: "")
        put("PositionTicks", positionTicks)
        // 进度记在谁名下：登录令牌已经决定了用户，这里再显式带上，双保险
        embySession.userId?.let { put("UserId", it) }
        put("IsPaused", isPaused)
        put("IsMuted", false)
        put("VolumeLevel", 100)
        put("PlayMethod", "DirectStream")
        put("CanSeek", true)
        put("PlaybackStartTimeTicks", System.currentTimeMillis() * 10_000)
        put("SeekableRanges", listOf(mapOf("start" to 0L, "end" to reportedRunTimeTicks)))
        put("BufferedRanges", emptyList<Any>())
        if (eventName != null) put("EventName", eventName)
    }

    private fun reportToServer(path: String, body: Map<String, Any?>, what: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                when (path) {
                    "playing" -> EmbyApi.playing(
                        this@MainActivity, userServer(), userToken(),
                        EmbyContent.DEVICE_ID, body,
                    )
                    "progress" -> EmbyApi.reportPlaybackProgress(
                        this@MainActivity, userServer(), userToken(),
                        EmbyContent.DEVICE_ID, body,
                    )
                    else -> EmbyApi.stopped(
                        this@MainActivity, userServer(), userToken(),
                        EmbyContent.DEVICE_ID, body,
                    )
                }
            }.onFailure { Log.w(TAG, "上报${what}失败: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 起播后：立刻上报"开始播放"，随后每 10 秒上报一次进度 */
    private fun startPlaybackReporting() {
        val itemId = reportedItemId ?: return
        // 起点位置走播放接口层：内核模式下同样能取到续播位置（父亲 2026-10-08）
        val startTicks = ctl.positionMs().times(10_000)
        Log.i(TAG, "进度上报: 开始 item=$itemId user=${embySession.userId?.take(8) ?: "-"} playSession=${reportedPlaySessionId ?: "-"}")
        progressJob?.cancel()
        progressJob = scope.launch {
            /*
             * 先报一次客户端能力：Emby 靠这一步把「这台设备 + 这个用户」的会话建起来，
             * 之后的 /Sessions/Playing 三件套才匹配得上、进度才会落到用户数据里。
             */
            runCatching {
                EmbyApi.reportCapabilities(
                    this@MainActivity, userServer(), userToken(), EmbyContent.DEVICE_ID,
                )
            }
            runCatching {
                EmbyApi.playing(
                    this@MainActivity, userServer(), userToken(), EmbyContent.DEVICE_ID,
                    playbackReportBody(itemId, startTicks, isPaused = false),
                )
            }
            while (renderer.videoActive) {
                kotlinx.coroutines.delay(10_000)
                val item = reportedItemId ?: continue
                /*
                 * 位置与播放态走播放接口层（父亲 2026-10-08）。
                 * 原来这一句是 `val p = player ?: continue` —— 内核模式下播放器对象为空，
                 * 于是**进度一条都不上报**，服务端记不住看到哪儿（app 记不住第几集的老毛病）。
                 */
                reportToServer(
                    "progress",
                    playbackReportBody(item, ctl.positionMs() * 10_000, !ctl.isPlaying(), "timeupdate"),
                    "进度",
                )
                Log.i(TAG, "进度上报: ${ctl.positionMs() / 1000}s item=$item")
            }
        }
    }

    private fun reportPlaybackStopped(positionTicks: Long) {
        progressJob?.cancel()
        progressJob = null
        // 让首页/详情页重新拉数据：面板没销毁，不主动刷新就一直是播放前的旧进度
        com.xxxx.emby_vr.data.PlaybackSync.bump()
        val itemId = reportedItemId ?: return
        reportToServer("stopped", playbackReportBody(itemId, positionTicks, isPaused = false), "停止")
        Log.i(TAG, "进度上报: 停止 ${positionTicks / 10_000_000}s item=$itemId")
        reportedItemId = null
    }

    /**
     * 停掉播放器并清理。
     *
     * @param keepUi 换轨重播时传 true：控制条与已展开的菜单**留着**（父亲 2026-10-06 晚定），
     *               否则它们会在起播过程中被收掉，等标志生效时界面早没了。
     */
    /**
     * 当前播放位置（毫秒）。**统一走 ctl（播放接口层）**，与控制条、银幕进度条同一个口径。
     *
     * 【2026-10-17 19:40 现场定案：只有这一部片子"弹幕不滚动"】
     *
     * 原来这里是自己写的"先问 ExoPlayer、没有再问内核"，而控制条走的是 `ctl.positionMs()`
     * （**先问内核**、再问 ExoPlayer）。两者顺序相反 —— 当内核在放、ExoPlayer 又还在
     * （位置停在那里不动）时，控制条进度正常走，弹幕却拿到那个停在 3 秒的旧值：
     * 每 250ms 被"纠正"回 3 秒一次 → 弹幕永远停在 3 秒那一屏，看着就是"不滚动"
     * （日志：`弹幕画笔：… 本帧画了=4 条 进度=3秒`，几百帧不变）。
     * 别部片子只有一个引擎，两个口径恰好一致，所以只有这一部露出问题。
     * 这正是 2026-10-13 记下的隐患"同一界面两套取位置优先级就是 bug 温床"，现在收掉。
     */
    private fun currentPositionMs(): Long = ctl.positionMs()

    private fun stopPlaybackInternal(keepUi: Boolean = false) {
        /* 停播时复位断流标记，否则下一次起播会被上一次的残留冻住（豆豆 2026-11-21 清单第 8 条） */
        avStall = false
        /* 换代：正在建的那条线程建完会发现代次不符，自己收场，不留孤儿管线 */
        selfGen.incrementAndGet()
        /*
         * 换片固定步骤（豆豆 2026-11-21 的硬约束）：
         * 参数表、文件标记、最后一套、"就地保持"全部复位，
         * 否则"P5 换 P5"时 streamActive 一直是 1，上一部片的参数会被继续用下去。
         */
        /*
         * 渲染层的还原总开关也必须关（豆豆 2026-11-21 清单第②条）：
         * 它只在 P5 起播时被置 true，此前没有任何收场路径复位；
         * 不复位时，只要某趟绘制忘了显式写 uniform，就会把杜比着色路径带到海报墙/面板上。
         */
        /* 自管解码标志跟着这条片子走：停播即复位，否则下一部非 P5 片子会误走服务端文本字幕（豆豆 2026-11-22） */
        selfPipeActive = false
        /* 荧幕进度条的时长缓存跟着这条片子走，别把上一部的总时长带过去 */
        danmakuView?.resetSeekHudCache()
        /* 解封装字幕跟着这条片子走 */
        demuxSubTrackWanted = -1
        demuxSubCues.clear()
        com.xxxx.emby_vr.vr.VrNative.setDvRendererReshape(false)
        com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(false)
        com.xxxx.emby_vr.vr.VrNative.resetDoviAll()
        /* 停播等多久只报数不判死（图 §4.1）：记起点，停完把耗时交给健康行 */
        val stopT0 = android.os.SystemClock.elapsedRealtime()
        /*
         * 先上报「停止播放」，再释放播放器（父亲 2026-10-07：播放历史没上传到 Emby）。
         *
         * 原来只有"自然播完（STATE_ENDED）"才发 Stopped —— 切片、退出播放、换集都不发，
         * 于是服务端只收到 Progress、进度不落盘（《无可替代》一直停在 S1E17）。
         * 位置必须在 release 之前取，否则拿到的永远是 0。
         */
        reportedItemId?.let {
            val posTicks = currentPositionMs().times(10_000L)
            reportPlaybackStopped(posTicks)
        }
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        // mpv 内核同样要收干净（父亲 2026-10-08）：否则下一次起播会是两条内核抢同一块画面
        /*
         * 换片清场（施工图 §4.2.3.1）：A 类开关在这里全量恢复，起播口不再清。
         * 位置是硬约束 —— 必须排在停内核之前，且是"任何可能销毁内核的调用"之前。
         */
        stopPipeHealth()
        /* 收场：水位监视一起停（否则下一部片还在按旧时长算水位） */
        stopWaterMonitor()
        pipeBootInFlight.set(false)
        selfPipePhase = "已收场"
        clearDoviState()
        /*
         * 静音必须在停内核**之前**还回去（施工图 §4.2.1 与 §4.4 ⑨）。
         * 起播借了 mute=yes，凡没走到「首帧上屏」的收场路径都得还：
         * mpv 实例进程级只建一次（全仓无 destroy），留着会让下一部片一起静音。
         * 清场里已经恢复过一次，这里再显式还一次 —— 它是"没声音"的最后一道保险。
         */
        restoreOption("mute", "no")
        mpvBackend?.let {
            runCatching { it.stop() }
        }
        mpvBackend = null
        lastStopWaitedMs = android.os.SystemClock.elapsedRealtime() - stopT0
        /*
         * 全包档收场顺序（图 §2-8）：先 abort Session 把阻塞在队列上的消费端叫醒，
         * 再收两条 Pipe，最后关 Session —— 反过来会让 Pipe 的 join 等到超时。
         */
        selfSession?.let { s -> runCatching { s.abort() } }
        /* 自管音频先收：它占着音频设备，留着会让下一部片一开播就有人声 */
        audioPipe?.let { runCatching { it.stop() } }
        audioPipe = null
        playClock = null
        selfAudioOn = false
        // 自管解码同样要收：它在往同一张画面纹理里送帧，留着会和下一次起播抢画面
        videoPipe?.let {
            runCatching { it.stop() }
        }
        videoPipe = null
        selfSession?.let { s ->
            runCatching { s.stop() }
            Log.i(TAG, "全包档收场：Session 已关（连接归还）")
        }
        selfSession = null
        /*
         * 画图时发现的问题（2026-11-17）：控制层的跳转钩子和这部的地址/表面在停播时没清。
         * 停掉 P5 再播别的片，一按快进仍会走到"重启自管解码"那一支，用旧地址把已经停掉的
         * 解码器重新拉起来 —— 表现是画面无故闪一下、或者跳转把新片打断。
         */
        ctl.pipeSeek = null
        ctl.selfPositionProvider = null
        ctl.selfBufferedProvider = null
        ctl.selfDurationProvider = null
        ctl.selfPlayingProvider = null
        ctl.selfTogglePlayPause = null
        selfPipeUrl = null
        selfPipeSurface = null
        mpvSubtitleJob?.cancel()
        mpvSubtitleJob = null
        seekTargetMs = null
        // 停止播放：快进快退进度条一并收起（别留在画面上）
        hideSeekHud()
        stickSeekHoldJob?.cancel()
        stickSeekHoldJob = null
        stickSeekDir = 0
        stickSeekAccel = false
        // 播放结束：VR 画面切回面板（非 VR 模式下这条调用没有副作用）
        com.xxxx.emby_vr.vr.VrNative.setVideoActive(false)
        /*
         * 注意：这里**不要**关转圈（2026-10-07 实测的坑）。
         * 起播流程里 startPlayer 也会走这个函数，一转圈刚开就被它关掉 ——
         * 日志里就是「银幕转圈 → 等第一帧」96 毫秒后紧跟一条「→ 收起」。
         * 转圈只由 clearForNewMedia 开、由第一帧（reveal）关。
         */
        picking = false
        osdJob?.cancel()
        osdJob = null
        // 片头守望一起收，按钮收掉（父亲 2026-10-12）—— 不留一颗点了没反应的按钮在条上
        introJob?.cancel()
        introJob = null
        osdState.showSkipIntroButton = false
        stopPosTicker()
        if (!keepUi) {
            setOsdVisible(false)
            // 弹幕层与片名 logo 一起收（它们贴在银幕上，不随控制条走）
            com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(false)
            com.xxxx.emby_vr.vr.VrNative.setLogoVisible(false)
        }
        danmakuView?.setSubtitle("")
        danmakuTrack = null
        danmakuView?.setTrack(null)
        // 平滑时钟一起清零：下一集从头对齐，免得拿上一集的位置去纠偏
        smoothAtMs = 0L
        smoothPosMs = 0.0
        posBaseAtMs = 0L
        posBaseMs = 0L
        logoUrl.value = null
        /*
         * 这里**不再**做两件事（父亲 2026-10-07）：
         *  ① 不打开海报墙 —— 用户主动关掉的就该保持关掉；切片（keepUi=true）
         *     与起播流程内部都会走这个函数，自动打开等于让"我关了它"失效。
         *  ② 不清控制条的播放数据 —— 起播流程内部也会走这个函数，
         *     在这里清会让换轨（选集 / 音频）之后按钮变灰且没人恢复。
         * 需要清的地方只有切片一处：clearForNewMedia 负责。
         */
    }

    /**
     * 换片 / 首播等待期收黑幕的**与门**（父亲 2026-10-07 第二次修复）。
     *
     * 只等原生"有帧"不够：实测换片后 80 毫秒就报首帧到位，可那是 ExoPlayer 准备阶段
     * 提交到 Surface 的帧（残留 / 占位），新片画面根本还没出来 —— 黑幕一收就露旧画面。
     * 只等 ExoPlayer 的 onRenderedFirstFrame 也不够：那时帧还没进我们的纹理。
     * 所以两个条件同时成立才算"新片画面真的到了"。
     */
    private var playerFrameSeen = false
    private var nativeFrameSeen = false

    /** 等待第一帧的兜底定时器：切片时要先撤掉上一只（父亲 2026-10-07） */
    private var firstFrameFallback: Runnable? = null

    /**
     * 等待期最短停留（父亲 2026-10-09 定：至少两秒）。
     *
     * 换片时黑幕 / 转圈 /「即将播放：片名」至少要亮这么久 ——
     * 内核起得快的时候（一秒钟不到）原来会一闪而过，观感上"像没黑屏"。
     */
    private val kMinWaitingMs = 2000L


    /** 本次等待期从什么时候开始（算最短停留用） */
    private var waitingStartedAtMs = 0L

    /**
     * 让播放器选中某条**内封文字字幕**（父亲 2026-10-09）。
     *
     * 为什么走播放器：直连播放时原片就在播放器手里，内封字幕它能就地解出来（毫秒级），
     * 官方客户端就是这么做的。走服务端则要在网盘上把整片读一遍，几十秒起步。
     *
     * 序号对应：Emby 的内封文字轨内嵌在文件里，顺序与文件一致；播放器报的文字轨
     * 也按文件顺序，所以第 N 条内封文字轨 → 播放器第 N 条文字轨（跨分组拉平）。
     * 找不到（例如这条其实是外挂轨）就退回服务端取流那条老路。
     */
    /**
     * 把「播放器实际选中的字幕轨」回填成 Emby 的流序号（父亲 2026-10-10：字幕在放、菜单没勾）。
     *
     * 反查口径与 selectEmbeddedTextTrack 对齐：先按语言、再按序号（只算内封文字轨，按 index 排序）。
     * 内核路径不插手（那儿的字幕选择归内核那套）。
     */
    private fun syncSelectedSubtitleFromPlayer() {
        val p = player ?: return
        /*
         * 内核在放画面时这一路不适用（字幕也归内核）。
         * 但自管解码那条路（videoPipe 在跑）例外：画面是我们画的、字幕仍由播放器解析后
         * 由我们绘制，这时必须回填，否则就是父亲 2026-11-16 实测的
         * 「字幕在画面上显示着，菜单里一项都没勾」。
         */
        if (ctl.kernelActive && videoPipe == null) return
        /*
         * 转码时服务端只推**请求里那一条**字幕轨（父亲 2026-10-11），播放器选中的必然是
         * 用户要的那条；这里再按语言回填会串到同语言的另一条（简/繁都是 chi）→ 菜单勾选
         * 自己跳走。所以转码且有用户选择时，菜单以用户选择为准，不回填。
         */
        if (lastPlayMethod == "Transcode" && selectedSubtitleIndex != null) return

        var ordinal = -1
        var lang = ""
        var counter = 0
        val groups = p.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_TEXT
        }
        loop@ for (g in groups) {
            for (ti in 0 until g.length) {
                if (g.isTrackSelected(ti)) {
                    ordinal = counter
                    lang = (g.mediaTrackGroup.getFormat(ti).language ?: "").lowercase().take(3)
                    break@loop
                }
                counter++
            }
        }

        val subs = currentStreams
            .filter {
                it.type.equals("Subtitle", ignoreCase = true) &&
                    it.isExternal != true && !isImageSubtitle(it)
            }
            .sortedBy { it.index ?: 0 }
        val byLang = if (lang.isNotBlank()) {
            subs.firstOrNull { (it.language ?: "").lowercase().take(3) == lang }
        } else {
            null
        }
        val idx = (byLang ?: subs.getOrNull(ordinal))?.index ?: return

        if (idx != selectedSubtitleIndex) {
            Log.i(TAG, "播放器自动选中的字幕 = 流 $idx（语言=$lang）→ 回填菜单勾选")
            selectedSubtitleIndex = idx
            refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
        }
    }

    /**
     * 转码路径：显示**服务端推来的那条字幕轨**（父亲 2026-10-11 定）。
     *
     * 为什么不用自绘：转码时画面本身与进度条有几秒的、忽早忽晚的偏差（服务端切片所致），
     * 自绘字幕跟着进度条走 → 必然与画面错开。服务端推的字幕与画面是同一路流出来的，
     * 天然同步。
     *
     * 转码会话一次只推一条字幕轨（就是请求里 SubtitleStreamIndex 那条），所以这里直接
     * 把它选上；轨还没报出来就轮询等（与内封轨同样的等待策略），等不到才退回自绘。
     */
    private fun selectServerSubtitleTrack(streamIndex: Int, attempt: Int = 0) {
        val p = player ?: return
        if (lastPlayMethod != "Transcode") {
            selectEmbeddedTextTrack(streamIndex, attempt)
            return
        }
        val groups = p.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_TEXT
        }
        if (groups.isEmpty()) {
            if (attempt < kSubtitleTrackWaitAttempts) {
                Log.i(TAG, "服务端字幕轨 $streamIndex：还没报出来（第 ${attempt + 1} 次）→ 1.2 秒后再试")
                handler.postDelayed({
                    if (currentMediaId.isNotBlank() && selectedSubtitleIndex == streamIndex) {
                        selectServerSubtitleTrack(streamIndex, attempt + 1)
                    }
                }, 1200L)
            } else {
                Log.w(TAG, "服务端字幕轨 $streamIndex 等不到 → 退回服务端转 SRT 自绘")
                loadSubtitleTrack(streamIndex)
            }
            return
        }
        /*
         * 优先按标题配（服务端给的轨名就是那条字幕的显示名），配不上取第一条 ——
         * 转码会话只推这一条。
         */
        val want = currentStreams.firstOrNull { it.index == streamIndex }?.displayTitle.orEmpty()
        var hit: Pair<androidx.media3.common.TrackGroup, Int>? = null
        outer@ for (g in groups) {
            for (ti in 0 until g.length) {
                if (hit == null) hit = g.mediaTrackGroup to ti
                val lbl = g.mediaTrackGroup.getFormat(ti).label.orEmpty()
                if (want.isNotBlank() && lbl.isNotBlank() &&
                    (lbl.contains(want.take(12), true) || want.contains(lbl.take(12), true))
                ) {
                    hit = g.mediaTrackGroup to ti
                    break@outer
                }
            }
        }
        hit?.let { (grp, ti) ->
            applyTextOverride(p, grp, ti)
            Log.i(TAG, "服务端字幕轨 $streamIndex → 选中播放器文字轨（与画面同源）")
        }
    }

    private fun selectEmbeddedTextTrack(streamIndex: Int, attempt: Int = 0) {
        val p = player ?: return
        val embedded = currentStreams
            .filter {
                it.type.equals("Subtitle", true) && it.isExternal != true && !isImageSubtitle(it)
            }
            .sortedBy { it.index ?: 0 }
        val pos = embedded.indexOfFirst { it.index == streamIndex }
        if (pos < 0) {
            Log.i(TAG, "字幕 $streamIndex 不是内封文字轨 → 退回服务端取流")
            loadSubtitleTrack(streamIndex)
            return
        }
        val textGroups = p.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_TEXT
        }
        /*
         * 优先按**语言**配（2026-10-09 复查）：只按序号配在有闭路字幕（EIA-608）或
         * 播放器不认某些轨时会错位一条，字幕就串到别的语言去了。
         * 语言一致时再按序号兜底。
         */
        val wantLang = (currentStreams.firstOrNull { it.index == streamIndex }?.language ?: "")
            .lowercase().take(3)
        var counter = 0
        var ordinalHit: Pair<androidx.media3.common.TrackGroup, Int>? = null
        for (g in textGroups) {
            for (ti in 0 until g.length) {
                val fmtLang = (g.mediaTrackGroup.getFormat(ti).language ?: "").lowercase().take(3)
                if (counter == pos) ordinalHit = g.mediaTrackGroup to ti
                if (wantLang.isNotBlank() && fmtLang == wantLang) {
                    applyTextOverride(p, g.mediaTrackGroup, ti)
                    Log.i(TAG, "内封字幕 $streamIndex → 按语言 $wantLang 选中播放器文字轨（就地解析，秒出）")
                    return
                }
                counter++
            }
        }
        ordinalHit?.let { (grp, ti) ->
            applyTextOverride(p, grp, ti)
            Log.i(TAG, "内封字幕 $streamIndex → 按序号第 $pos 条选中（就地解析，秒出）")
            return
        }
        /*
         * 轨还没报出来（刚起播/刚换画质重播，还在缓冲）→ **轮询等它**。
         *
         * 2026-10-10 修：原来只重试两次（约 3 秒）就放弃，然后退到"请服务端从原文件里
         * 挖字幕"那条路 —— 而网盘片源上服务端挖不动（实测：那条请求挂住不响应，
         * 客户端连接被重置、0 字节），结果就是**换画质之后中文字幕整个不显示**，
         * 切回原画（直连、不用等缓冲）才正常。
         *
         * 现在最多等 kSubtitleTrackWaitAttempts × 1.2 秒（约 24 秒），
         * 期间换片或改选别的字幕就放弃这次等待。
         */
        if (attempt < kSubtitleTrackWaitAttempts) {
            Log.i(TAG, "内封字幕 $streamIndex：播放器还没报出文字轨（第 ${attempt + 1} 次）→ 1.2 秒后再试")
            handler.postDelayed({
                if (currentMediaId.isNotBlank() && selectedSubtitleIndex == streamIndex) {
                    selectEmbeddedTextTrack(streamIndex, attempt + 1)
                }
            }, 1200L)
            return
        }
        Log.w(
            TAG,
            "内封字幕 $streamIndex 等了 ${(kSubtitleTrackWaitAttempts * 1.2).toInt()} 秒仍未报出轨道 → 退回服务端取流",
        )
        loadSubtitleTrack(streamIndex)
    }

    /** 把播放器的文字轨切到指定的一条（跨分组） */
    private fun applyTextOverride(
        p: androidx.media3.exoplayer.ExoPlayer,
        group: androidx.media3.common.TrackGroup,
        trackIndex: Int,
    ) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group, listOf(trackIndex)),
            )
            .build()
    }

    /** 弹幕偏移连发：按住扳机时用 */
    private var offsetRepeatJob: Runnable? = null
    private var offsetRepeatDelayMs = 1000L

    /**
     * 弹幕时间偏移的"按住连发"（父亲 2026-10-09 定）。
     *
     * 手感：点一下走 0.5 秒/10 秒那两档各一次；按住不放先等 0.5 秒，
     * 然后每隔一段时间自动走一档，间隔从 1 秒开始每次乘 0.85（越跑越快），
     * 最快到 0.12 秒一档；扳机一松立刻停。
     */
    private fun startOffsetRepeat(step: () -> Unit) {
        stopOffsetRepeat()
        offsetRepeatDelayMs = 1000L
        val job = object : Runnable {
            override fun run() {
                step()
                placePrefs.edit()
                    .putInt("danmaku_offset_ms", danmakuOffsetMs)
                    .apply()
                menuState.danmakuOffsetMs = danmakuOffsetMs
                offsetRepeatDelayMs = (offsetRepeatDelayMs * 85L / 100L).coerceAtLeast(120L)
                handler.postDelayed(this, offsetRepeatDelayMs)
            }
        }
        offsetRepeatJob = job
        // 先等 0.5 秒再开始连发：让"点一下"和"按住"区分开
        handler.postDelayed(job, 500L)
    }

    /** 弹幕时间偏移走一档（父亲 2026-10-09：点一下 0.5 秒、按住连续跑） */
    private fun stepDanmakuOffset(index: Int, n: Int) {
        when (index) {
            n + 1 -> {
                danmakuOffsetMs = (danmakuOffsetMs + 500).coerceAtMost(kDanmakuOffsetMaxMs)
                Log.i(TAG, "弹幕提前 0.5 秒 → 偏移 ${danmakuOffsetMs}ms")
            }
            n + 2 -> {
                danmakuOffsetMs = (danmakuOffsetMs - 500).coerceAtLeast(-kDanmakuOffsetMaxMs)
                Log.i(TAG, "弹幕推后 0.5 秒 → 偏移 ${danmakuOffsetMs}ms")
            }
            n + 3 -> {
                danmakuOffsetMs = (danmakuOffsetMs + 10_000).coerceAtMost(kDanmakuOffsetMaxMs)
                Log.i(TAG, "弹幕提前 10 秒 → 偏移 ${danmakuOffsetMs}ms")
            }
            n + 4 -> {
                danmakuOffsetMs = (danmakuOffsetMs - 10_000).coerceAtLeast(-kDanmakuOffsetMaxMs)
                Log.i(TAG, "弹幕推后 10 秒 → 偏移 ${danmakuOffsetMs}ms")
            }
        }
    }

    private fun stopOffsetRepeat() {
        offsetRepeatJob?.let { handler.removeCallbacks(it) }
        offsetRepeatJob = null
        offsetRepeatDelayMs = 1000L
    }

    /** 内核内嵌字幕的取文轮询（Profile 5 专用，2026-10-09） */
    private var mpvSubtitleJob: kotlinx.coroutines.Job? = null
    /** 起播看门狗的 job（见 switchToKernelForFallback，父亲 2026-10-11） */
    private var kernelFallbackJob: kotlinx.coroutines.Job? = null
    /** 这一部片是否已经自动切过内核 —— 每部只切一次，防两边来回切 */
    private var kernelFallbackDone = false

    /**
     * 内核内嵌字幕：每 80ms 问一次当前这句的纯文本，交给自己那层画（2026-10-09）。
     *
     * 为什么不用内核自己画：这台设备上它找不到字体，一个字都画不出来（见内核选项处的注释）。
     * 为什么是轮询：内核没有"字幕变了"的回调，属性读一次很便宜（一次 JNI 字符串读）。
     * 只在等待期结束后往层上写，避免把黑幕期的字幕提前露出来。
     */
    private fun startMpvSubtitlePoll() {
        mpvSubtitleJob?.cancel()
        mpvSubtitleJob = scope.launch {
            var lastLogged = ""
            var subVisibleDisabled = false
            var idleTicks = 0
            while (isActive) {
                val backend = mpvBackend ?: break
                val raw = runCatching { backend.currentSubtitleText() }.getOrNull().orEmpty()
                /*
                 * 内核出画判定（父亲 2026-10-09）：解出画面尺寸了才算真的出画，
                 * 配合最短停留两秒，收黑幕 / 转圈 /「即将播放」。
                 */
                /*
                 * 内核播到结尾（父亲 2026-10-09）：内核没有 ExoPlayer 那个"播放结束"回调，
                 * 拿 mpv 的 eof-reached 当判据。"播完停止"这一档由界面收场；
                 * 另外两档循环开着，正常不会走到这里。
                 */
                if (!waitingFirstFrame && playModeIndex == 2 &&
                    runCatching { backend.endReached() }.getOrDefault(false)
                ) {
                    /*
                     * 攒分片（腾讯网页那条路）：手里的段播完不等于这一部播完 ——
                     * 网页还在后台一段一段取。照"播完停播"处理，父亲看到的就是
                     * "回到网页、再点全屏、又只播一小段"（2026-10-18 实测）。
                     */
                    if (webFragMode && System.currentTimeMillis() - webFragLastAt < 40000L) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - webFragEofLogged > 10000L) {
                            webFragEofLogged = nowMs
                            Log.i(TAG, "攒分片：手里这段播完了，等网页接下一段（不当作播完）")
                        }
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    Log.i(TAG, "内核播放结束（eof-reached）→ 停止并回到界面")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        runCatching { stopPlayback() }
                    }
                    break
                }
                if (waitingFirstFrame && runCatching { backend.videoReady() }.getOrDefault(false)) {
                    playerFrameSeen = true
                    withContext(kotlinx.coroutines.Dispatchers.Main) { tryRevealWaitingFrame() }
                }
                val text = cleanSubtitleText(raw)
                /*
                 * 内核那层字幕的收放（2026-10-09）：
                 * 文字字幕（内核画不出来，字体缺失）→ 我们自己画，同时把内核那层关掉；
                 * 图形字幕（内核画得了、我们读不到文字）→ 把内核那层放开。
                 */
                if (text.isNotEmpty()) {
                    if (!subVisibleDisabled) {
                        backend.setSubVisible(false)
                        subVisibleDisabled = true
                    }
                } else if (subVisibleDisabled) {
                    idleTicks += 1
                    if (idleTicks > 40) {          // 约 3 秒没有文字 → 多半是图形字幕
                        backend.setSubVisible(true)
                        subVisibleDisabled = false
                        idleTicks = 0
                    }
                }
                if (text != subtitleNow) {
                    subtitleNow = text
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (!waitingFirstFrame) danmakuView?.setSubtitle(text)
                    }
                }
                if (text.isNotEmpty() && text != lastLogged) {
                    lastLogged = text
                    Log.i(TAG, "内核字幕：${text.take(40)}")
                }
                kotlinx.coroutines.delay(80L)
            }
        }
    }

    /** 网页流：内核是否已报出真实画面尺寸（2026-10-20，用于等尺寸再揭幕） */
    @Volatile private var webSizeKnown = false

    private fun tryRevealWaitingFrame() {
        if (!waitingFirstFrame) return
        /*
         * 这里不再判断"首帧到没到"。
         *
         * 两个调用点本身就已经是"首帧到了"：普通片是播放器/内核的首帧回调，
         * 自管解码是画面首帧真正上屏的那一刻（onFirstFrame）。
         * 原先这里有一道 playerFrameSeen 判断，自管解码永远满足不了它，
         * 黑幕于是只能等 20 秒兜底（2026-11-21 实测）。删掉，避免以后再看错。
         */
        /*
         * 【2026-10-20 18:47 父亲实测：第一次接管时画面是拉扁的，第二次才对】
         *
         * 根因：起播那一刻还不知道片源真实尺寸，银幕先按 16:9 建好、视频层先按 1920x1080 建，
         * 等内核报出真实尺寸（比如 3840x1606）才改正 —— 这段时间揭幕，看到的就是被拉扁的画面。
         * 第二次接管时尺寸已经有了，所以直接正确。
         * 修法：网页流起播时，等内核报出真实画面尺寸之后再揭幕（最多多等 1.2 秒，仍在黑幕里）。
         */
        /*
         * 颜色没到就不收黑幕（父亲 2026-11-21 定的口径）：
         * 第一眼看到的必须是正确颜色，而不是"过一会儿自己变对"。
         * 复用这里的重试循环（120ms 一拍），不另写第二套等待。
         * 20 秒兜底：真遇到没有杜比参数的片子也要收幕，否则就是永久黑屏。
         */
        if (selfAudioOn && !com.xxxx.emby_vr.vr.VrNative.doviParamsReady()) {
            val waited = android.os.SystemClock.uptimeMillis() - waitingStartedAtMs
            if (waited < 20000L) {
                handler.postDelayed({ tryRevealWaitingFrame() }, 120L)
                return
            }
            Log.w(TAG, "收黑幕兜底：${waited}ms 参数仍未到达（本片可能没有杜比参数）")
            hud("本片颜色参数未到达")
        }
        if (webStreamSite != null && !webSizeKnown) {
            val waited = android.os.SystemClock.uptimeMillis() - waitingStartedAtMs
            if (waited < kMinWaitingMs + 1200L) {
                handler.postDelayed({ tryRevealWaitingFrame() }, 120L)
                return
            }
            Log.i(TAG, "收黑幕：等尺寸超时（${waited}ms），按当前比例揭幕")
        }
        /*
         * 最短停留（父亲 2026-10-09：至少两秒）——两条路都走这里，所以统一卡在这。
         * 不够就等够再来，别让「即将播放」一闪而过。
         */
        val since = android.os.SystemClock.uptimeMillis() - waitingStartedAtMs
        if (since < kMinWaitingMs) {
            handler.postDelayed({ tryRevealWaitingFrame() }, kMinWaitingMs - since)
            return
        }
        Log.i(TAG, "收黑幕：播放器已渲染第一帧（等待 ${since}ms）")
        revealDanmakuSubtitleLogo()
    }

    /**
     * 换片：先把旧片整个收掉，再让新片起播（父亲 2026-10-07）。
     *
     * 「停旧片 → 银幕空一拍（转圈顶上）→ 弹幕 / 字幕 / 片名 logo 全清」本来就是
     * stopPlaybackInternal 的行为，这里只给它一个语义入口 + 一行日志。
     * 真正的起播由 playMedia → startPlayer 接手。
     */
    private fun clearForNewMedia() {
        // 换片：兜底标记复位，新片可以再自动切一次内核（父亲 2026-10-11）
        kernelFallbackDone = false
        kernelFallbackJob?.cancel()
        kernelFallbackJob = null
        /*
         * hadPlayback 要算上内核与网页流（父亲 2026-11-07）：
         * 网页流/内核播放时 player 与 currentMediaId 都可能是空的，原来只按这两项判，
         * 结果是"网页里换片"走了起播分支、旧的那一路没人停 —— 旧字幕轨、旧流还在，
         * 新片旁边飘着上一部的台词。
         */
        val hadPlayback = player != null || currentMediaId.isNotBlank() ||
            mpvBackend != null || webStreamSite != null || webPlayingNow
        if (hadPlayback) {
            Log.i(TAG, "换片：停掉旧片、清空银幕 / 弹幕 / 字幕 / logo，等新片起播")
            /*
             * keepUi = true：切片时**控制条不收**（父亲 2026-10-07）。
             * 控制条留在原位、按钮置灰、进度显示 --，等新片第一帧再恢复内容 ——
             * 这样"正在切片"这件事在界面上是连续可见的，不会闪一下就没。
             */
            runCatching { stopPlaybackInternal(keepUi = true) }
        } else {
            Log.i(TAG, "起播：先清空银幕 / 弹幕 / 字幕 / logo，等第一帧")
        }
        /*
         * 换片：旧的流清单立刻作废（2026-10-10 修）。
         * 不清的话，在新片的播放信息请求回来之前打开字幕/音轨菜单，会看到**上一部片**的选项，
         * 点下去序号对不上、什么都不发生；等清单更新后重建（rebuildTrackMenus）才恢复正常。
         * 清成空表后这段时间菜单是空的（诚实的"还没拿到"），而不是错的。
         */
        currentStreams = emptyList()
        audioStreamIndices = emptyList()
        subtitleStreamIndices = emptyList()
        /*
         * 从这一刻起「藏起来等第一帧」（父亲 2026-10-07）：
         * 弹幕 / 字幕 / 片名 logo 都等新片画面出来再一起亮。
         * 兜底：万一第一帧迟迟不来（起播失败），8 秒后照样放出来，别让它们永远不显示。
         */
        /*
         * 控制条上的「正在播放」**先消失**（父亲 2026-10-07）：
         * 旧片信息立刻清掉、播放按钮置灰，等新片第一帧到了再一起恢复。
         */
        osdState.hasPlayback = false
        osdState.title = ""
        osdState.durationMs = 0L
        osdState.positionMs = 0L
        /*
         * 片名 logo 立刻作废（父亲 2026-10-17 23:44 实测：从 Emby 片切到网站视频之后，
         * 那部 Emby 片的片名 logo 还留在弹幕层上）。
         *
         * logo 是画在弹幕层画布左上角的那张位图（painter.logoBitmapProvider），
         * 只关原生那一层（setLogoVisible(false)）不够 —— 位图还在对象里，
         * 等第一帧过去、waitingFirstFrame 落回 false，画笔照样把它画出来。
         * 这里连位图和「已下载地址」一起清：换到网站视频时它必然消失，
         * 换到下一部 Emby 片时按新地址重新下载（不能只清位图留地址 ——
         * loadLogoBitmap 见地址相同会直接 return，logo 就再也不出现了）。
         */
        logoBitmap.value = null
        logoLoadedUrl = null
        com.xxxx.emby_vr.vr.VrNative.setLogoVisible(false)
        /*
         * 控制条上「跟着片子走」的那几份菜单数据也要清（父亲 2026-10-17 23:46 实测：
         * 从 Emby 片切到网站视频后，选集、信息、演职人员三个菜单还挂着上一部片的内容
         * —— 只有「正在播放」那行跟着换了）。
         *
         * 这几份数据只有 Emby 片才有，来源是 loadItemDetail；网站视频那条路不会重新填，
         * 所以必须清空。空菜单是诚实的「这部没有」，留着上一部的才是错的。
         * 音轨 / 字幕 / 质量三个菜单的数据源是流清单（currentStreams 等），上面已经清过。
         */
        /*
         * 选集不在这里清：同一部剧切下一集要保留着（父亲 2026-11-02），
         * 真换了剧由 loadItemDetail 拿到新剧号再清。见该函数里的 sameSeries 判断。
         */
        menuState.people = emptyList()
        menuState.info = null
        episodeIds = emptyList()
        episodePositions = emptyList()
        /*
         * 片头标记作废（父亲 2026-10-12）：新片的范围等取播放信息那一步再立。
         * 不清的话，新片还没拿到章节之前，「跳过片头」会拿着**上一部片**的时间轴站着。
         */
        introStartMs = null
        introEndMs = null
        hasAutoSkippedIntro = false
        osdState.showSkipIntroButton = false
        // 收黑幕的与门也要复位，等这一部自己的两个信号（父亲 2026-10-07）
        playerFrameSeen = false
        nativeFrameSeen = false
        waitingFirstFrame = true
        /*
         * 银幕比例先复位成 16:9（父亲 2026-10-07 实测：换片时转圈和提示被压扁、圈成椭圆）。
         *
         * 原因：弹幕层的 quad 尺寸跟着**视频真实比例**走（native 侧的 sp.aspect），
         * 换片瞬间它还停在上一部片的比例上（比如 2.39:1 或 4:3），而画布固定 2560×1440，
         * 于是被非等比拉伸 —— 圆成了椭圆、字被压扁。首播时比例还是默认 16:9，所以正常。
         * 新片第一帧到位后 onVideoSizeChanged 会把它改回真实比例。
         */
        /*
         * 画布比例必须跟着一起回 16:9（父亲 2026-10-07：等待期的转圈与「即将播放」变形）。
         * 等待期画在弹幕层上，而这一层此刻按 16:9 摆 —— 画布若还留着上一部（比如 2.39:1）
         * 的比例，圆就被压成椭圆。两者永远是成对改的（applyScreenAspect 里就是成对的）。
         */
        applyScreenAspect(16f / 9f, "换源清场回 16:9")
        waitingStartedAtMs = android.os.SystemClock.uptimeMillis()
        /*
         * 字幕表必须跟着换片清掉（2026-10-09 复查发现）。
         *
         * 自绘字幕是"按当前播放位置在表里找命中的那一条"——表不清，新的一集就会拿
         * **上一集的字幕表**去匹配，时间轴又都是 0 起点，于是新集开头几分钟显示的是
         * 上一集的台词，直到新表取回来才纠正。边界情况下（新表取失败）会一直错下去。
         */
        subtitleCues = emptyList()
        subtitleCueStream = null
        subtitleNow = ""
        /*
         * 换片了：上一次为图形字幕立的「改走内核」标记作废（父亲 2026-10-09）。
         * 不清的话下一部片子会莫名其妙走内核。
         */
        com.xxxx.emby_vr.player.PlaybackFlags.forceKernelForImageSubs = false
        /*
         * 片源格式判定属于「上一部」的结论，换片这一刻一并丢掉（父亲 2026-11-07）：
         * 杜比视界标记残留会让这一部普通片被当成 P5，画质菜单显示 1920/1280 两档、
         * 解码路径也可能被带偏。清成未知，由本次取播放信息重新探。
         */
        com.xxxx.emby_vr.player.PlaybackFlags.clearSourceFormat()
        /*
         * 等待期这一层要露出来 —— 它上面要显示「即将播放：片名」那行提示
         * （弹幕内容与 logo 仍然等第一帧，见 logoBitmapProvider 的门控）。
         */
        danmakuHint.value = "即将播放…"
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(true)
        // 银幕清空之后立刻进入"转圈"状态（父亲 2026-10-07：清空了但没有转圈）
        com.xxxx.emby_vr.vr.VrNative.setSpinnerWanted(true)
        /*
         * 兜底定时器要能撤销（父亲 2026-10-07：切片频繁时黑幕被上一次的定时器收掉）。
         *
         * 日志实证：16:00:10.987 换片 → 16:00:13.499 就报「等第一帧超时（8 秒）」，
         * 只隔 2.5 秒 —— 那是**上一次切片**排的定时器到点了。
         * 所以每次切片先把上一只撤掉，再排这一只。
         */
        firstFrameFallback?.let { handler.removeCallbacks(it) }
        val fallback = Runnable {
            if (waitingFirstFrame) {
                /*
                 * 兜底放行（2026-10-10 从 8 秒放宽到 20 秒）。
                 *
                 * 8 秒是照"本地/局域网片源"估的，但我们的片源在网盘上，冷启动
                 * （服务端现拉原文件）经常超过 8 秒 —— 实测日志里已经在打
                 * 「等第一帧超时（8 秒）」，于是黑幕提前收掉：画面还没到，
                 * 弹幕/字幕/logo 先亮，看起来就是"空屏/旧画面 + 弹幕在飘"。
                 * 放宽到 20 秒，正常起播不会等满（帧到就收），真失败时才兜底。
                 */
                Log.w(TAG, "等第一帧超时（20 秒），兜底把弹幕 / 字幕 / logo 放出来")
                revealDanmakuSubtitleLogo()
            }
        }
        firstFrameFallback = fallback
        handler.postDelayed(fallback, 20_000L)
    }

    /**
     * 新片第一帧到了：弹幕 / 字幕 / 片名 logo 与画面**同时**出现（父亲 2026-10-07）。
     *
     * 数据其实早就加载好了（弹幕文件、详情、logo 位图都是异步拉的），
     * 这里只是把「显示」这一步推迟到画面出来的这一刻。
     */
    private fun revealDanmakuSubtitleLogo() {
        if (!waitingFirstFrame) return
        waitingFirstFrame = false
        // 新片画面到了：控制条的「正在播放」与按钮一起恢复（父亲 2026-10-07）
        osdState.hasPlayback = true
        danmakuHint.value = null                                  // 提示收起
        com.xxxx.emby_vr.vr.VrNative.setSpinnerWanted(false)   // 画面来了，转圈收起
        applyDanmakuSetting()                    // 弹幕层可见 + 按开关挂轨道
        danmakuView?.setSubtitle(subtitleNow)    // 字幕跟上
        com.xxxx.emby_vr.vr.VrNative.setLogoVisible(logoBitmap.value != null)
        Log.i(TAG, "新片第一帧：弹幕 / 字幕 / 片名 logo 一起亮")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { com.xxxx.emby_vr.player.SharedReadCache.start(applicationContext) }
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: B0BEmby VR 启动")
        startFdCensus()
        /*
         * 腾讯「要地址」验证入口（2026-10-18 转签名路线）。
         *
         * 只用于验证：`adb shell am start -n com.xxxx.emby_vr/.MainActivity --es b0b_vkey_vid <vid>`，
         * 结果全在日志里（标签 B0BEmbyVR-Vkey）。父亲不用戴头显、不用点任何东西。
         */
        intent?.getStringExtra("b0b_vkey_vid")?.takeIf { it.isNotBlank() }?.let { vid ->
            Log.i(TAG, "收到腾讯要地址验证请求：vid=$vid")
            com.xxxx.emby_vr.web.TencentVkeyProbe.run(this, vid, com.xxxx.emby_vr.ui.currentWebCookie())
        }
        /*
         * VR 影院模式的第一步（2026-10-05）：确认原生层能编出来、官方 OpenXR loader
         * 能链上。加载失败不影响 2D 面板模式（VrNative 内部已吞异常）。
         */
        Log.i(TAG, "原生层探针：${com.xxxx.emby_vr.vr.VrNative.probe()}")

        // VR 应用需保持屏幕常亮（头显内不存在系统熄屏，但某些盒子/模拟器需要）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        renderer = VrRenderer(this, vrSession)

        /*
         * 面板层（UI 复用验证，2026-10-04）：
         * 画面纹理由原生渲染线程建好后推过来（2026-10-05 晚修，见
         * VrNative.TextureSink），这里负责把 SurfaceTexture 交给 PanelLayer
         * （它内部切主线程建虚拟显示器与 Presentation）。
         */
        panel = PanelLayer(
            this,
            /*
             * 只有电视版主面板认「轮播滚动区」（父亲 2026-10-12）：
             * 那张表是全局的、矩形按首页面板的坐标登记，菜单/控制条是另外的画布，
             * 拿它判落点会串台（菜单里推摇杆变成翻首页轮播，菜单自己不滚）。
             */
            usesScrollZones = true,
        ) {
            // 面板里放的就是电视版的界面：PanelApp 是它的导航装配（见 panel/PanelApp.kt）
            PanelApp(
                onPlayRequested = { mediaId, positionTicks ->
                    onPanelPlayRequested(mediaId, positionTicks)
                },
                /* 点站点图标 = 那个网站的网页整块上大银幕（父亲 2026-10-13） */
                onOpenWeb = { site -> openWeb(site) },
            )
        }

        /*
         * 控制条（2026-10-05）：独立的一块 1920×270 小面板，放在观影者身前近场。
         * 按钮语义沿用电视版播放页，第一批六颗（其余随后补）。
         */
        osd = PanelLayer(
            this,
            content = { com.xxxx.emby_vr.panel.PlayerOsdBar(osdState) },
            /*
             * 父亲 2026-10-06 晚按图定稿：2331 × 474 像素
             * （宽 = 114×2 + 12 个正方形按钮框 159×12 + 框缝 5×9 + 组间 75×2；
             *   高 = 75 + 标题 50 + 30 + 进度 55 + 30 + 按钮 159 + 75）。
             * 与原生 kOsdPxW / kOsdPxH 必须一致，否则点击坐标会错位。
             */
            panelW = 2331,
            panelH = 474,
            name = "b0bemby-osd",
            activatesVrPanel = false,
            // 控制条是纯 Compose 界面，没登记进电视版那张控件坐标表 → 点击要直通派发
            directClick = true,
        )
        /*
         * 展开菜单（2026-10-06 父亲定）：架在控制条**正上方**的另一块面板，
         * 与控制条等宽（2560），窗口背景透明 —— 只有菜单卡片有底色。
         */
        menu = PanelLayer(
            this,
            content = { com.xxxx.emby_vr.panel.PlayerMenuPanel(menuState, osdState) },
            panelW = com.xxxx.emby_vr.panel.MENU_PANEL_W,
            panelH = com.xxxx.emby_vr.panel.MENU_PANEL_H,
            name = "b0bemby-menu",
            activatesVrPanel = false,
            directClick = true,
        )
        /*
         * 弹幕层（2026-10-06 晚改画法）。
         *
         * 原来走「虚拟显示器 + 界面容器」，实测那块画布一次都没画过 —— 容器按内容
         * 量尺寸，而自绘层的内容尺寸是 0，量出来就是 0×0。现在不走窗口系统了：
         * 纹理一到就自己开画布、逐帧调自绘层（见 DanmakuSurfacePainter）。
         */
        val dSt = pendingDanmakuSt
        if (dSt != null) {
            Log.i(TAG, "弹幕纹理已到，启动画笔")
            attachDanmakuSurface(dSt)
        } else {
            Log.w(TAG, "弹幕纹理还没到，等回调")
        }

        /*
         * 片名 logo（2026-10-06）：银幕左上角的小透明面板。
         * 位置与尺寸由原生按电视版比例钉在银幕左上角，这里只管画那张图。
         */
        /*
         * 网页自己点"全屏"（父亲 2026-10-13）：什么都不用做。
         *
         * 网页现在整块就在大银幕上，网页里的"全屏"只是它自己把播放器铺满页面，
         * 我们照样只把这块画面贴到银幕上；退出全屏同理。所以这里只留日志。
         * 银幕上只放网页本身 —— 转圈、片名 logo、弹幕都不往上画。
         */
        com.xxxx.emby_vr.web.WebPageHub.fullscreenHandler = { on ->
            Log.i(TAG, if (on) "网页自己进入全屏（画面仍在大银幕上）" else "网页自己退出全屏")
        }

        /*
         * 网页面（父亲 2026-10-13）：1920×1080，与银幕同一尺寸；不在世界里出现，
         * 只作为银幕的画面来源。按需建（点站点图标才建），之后一直留着复用。
         */
        web = PanelLayer(
            this,
            content = {
                val s = webSiteState.value
                if (s != null) {
                    /*
                     * 【2026-10-17 16:39 父亲定稿：银幕上是网页时按 B 一路回退到首页】
                     * 网页面在接管时被销毁重建，真实历史栈已断 —— 网页屏凭自己
                     * 登记的合成历史回退；这里把"跳到某地址"的动作接回去。
                     */
                    com.xxxx.emby_vr.web.WebPageHub.navHandler = { u ->
                        runOnUiThread { webStartUrlState.value = u }
                    }
                    com.xxxx.emby_vr.ui.WebLibraryScreen(
                        site = s,
                        startUrl = webStartUrlState.value,
                        /*
                         * 回调来自火狐内核自己的线程，而播放链路（建 mpv、挂 surface、改界面状态）
                         * 必须在主线程 —— 先切回主线程再动（2026-10-16 自审补的，避免装机即崩）。
                         * 顺手记住站点：按 B 要回到同一个网页继续选片。
                         */
                        onWebStream = { obj ->
                            runOnUiThread {
                                webStreamSite = s
                                playWebStream(obj)
                            }
                        },
                        /*
                         * 攒到的分片走这里（2026-10-18）：腾讯那种站没有整片地址也没有
                         * 播放列表，第一段起播、后面的接在播放列表末尾，网页留在后台继续取。
                         */
                        onWebFrags = { obj -> onBridgeFrags(obj) },
                        /*
                         * 提前通报（2026-10-19 父亲定）：网页那边一拿到整集清单就把
                         * 这一集的信息报上来，先填控制条，等他按控制条上的播放键再起播。
                         */
                        onWebPreview = { obj -> runOnUiThread { onWebPreviewMsg(obj) } },
                    )
                }
            },
            panelW = 1920,
            panelH = 1080,
            name = "b0bemby-web",
            activatesVrPanel = false,
        )
        val wSt = pendingWebSt
        if (wSt != null) {
            Log.i(TAG, "网页面建好，补挂纹理")
            web?.attach(wSt)
        }

        logo = PanelLayer(
            this,
            content = {
                // 框内等比缩放：图不会变形（电视版同款 ContentScale.Fit）
                val bmp = logoBitmap.value
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            panelW = 512,
            panelH = 220,
            name = "b0bemby-logo",
            activatesVrPanel = false,
        )
        val lSt = pendingLogoSt
        if (lSt != null) {
            Log.i(TAG, "logo 面板建好，补挂纹理")
            logo.attach(lSt)
        } else {
            Log.w(TAG, "logo 面板建好，但纹理还没到（原生回调未触发？）")
        }

        /*
         * 画面的接收口（2026-10-05 晚修）：纹理由原生渲染线程在自己的 GL
         * 上下文里建好，建好立刻回调这里（如果已经建好，注册时补推一次）。
         * 之前是反过来（Java 建好纹理再给原生用），跨上下文导致三块屏互相串画面。
         */
        com.xxxx.emby_vr.vr.VrNative.attachTextureSink(
            object : com.xxxx.emby_vr.vr.VrNative.TextureSink {
                /*
                 * 2026-10-06 晚改成单一入口：原来六块画面各有一个方法，实测弹幕与
                 * 片名 logo 这两个方法在原生侧 GetMethodID 拿到空，两块画面从未
                 * 绘制过。现在按编号分发，编号与原生 pushTexturesToJava 一一对应。
                 */
                override fun onTexture(kind: Int, st: android.graphics.SurfaceTexture) {
                    when (kind) {
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_PANEL -> panel.attach(st)
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_VIDEO -> renderer.setVrVideoSurface(st)
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_OSD -> osd.attach(st)
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_MENU -> menu.attach(st)
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_DANMAKU -> {
                            Log.i(TAG, "收到弹幕层纹理，启动画笔")
                            pendingDanmakuSt = st
                            attachDanmakuSurface(st)
                        }
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_LOGO -> {
                            Log.i(TAG, "收到片名 logo 纹理（面板已建=${::logo.isInitialized}）")
                            pendingLogoSt = st
                            if (::logo.isInitialized) logo.attach(st)
                        }
                        com.xxxx.emby_vr.vr.VrNative.TEXTURE_WEB -> {
                            Log.i(TAG, "收到网页面纹理（已建=${web != null}）")
                            pendingWebSt = st
                            web?.attach(st)
                        }
                    }
                }
            },
        )
        osdState.onButton = { button -> onOsdButton(button) }
        // 控制条的时钟独立于播放：应用一起来就走（父亲 2026-10-07）
        startClockTicker()
        // 拖进度条：拖动中只动显示（跟手），松手才真跳
        osdState.onSeekPreview = { frac ->
            val dur = osdState.durationMs
            if (dur > 0L) osdState.positionMs = (dur * frac).toLong()
        }
        osdState.onSeekCommit = { frac ->
            val dur = osdState.durationMs
            if (dur > 0L && ctl.hasEngine()) {
                val target = (dur * frac).toLong()
                osdState.positionMs = target
                // 拖进度条也是 seek：弹幕同样冻住（父亲 2026-10-07）
                freezeDanmakuForSeek()
                // 走播放接口层：内核模式下也生效（父亲 2026-10-08）
                ctl.seekTo(target)
                Log.i(TAG, "控制条拖进度 → ${target / 1000} 秒 / ${dur / 1000} 秒")
            }
        }
        menuState.onSelect = { kind, index -> onMenuSelect(kind, index) }
        menuState.onClose = { closeMenu() }
        menuState.onBack = { menuBack() }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true

            /*
             * PICO 手柄的指针/扳机走触摸通道（见 InputRouter.onTouchEvent 的抓包结论），
             * 因此必须显式打开点击与悬停两类事件；GLSurfaceView 默认只收 DOWN/UP，
             * 不加 HOVER 就收不到「手柄指向移动」。
             */
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnHoverListener { _, e ->
                input.onTouchEvent(
                    event = e,
                    viewW = width,
                    viewH = height,
                    onPointer = { },
                    onConfirm = { },
                    // 播放页快进快退由摇杆承担（Action.LEFT/RIGHT → seekBy）。
                    // 「按住扳机拖动 = seek」是旧语义，已停用：新语义里扳机 = 鼠标左键
                    onDrag = { },
                    onDown = { },
                )
            }
            setOnTouchListener { _, e ->
                input.onTouchEvent(
                    event = e,
                    viewW = width,
                    viewH = height,
                    onPointer = { },
                    onConfirm = { },
                    // 播放页快进快退由摇杆承担（Action.LEFT/RIGHT → seekBy）。
                    // 「按住扳机拖动 = seek」是旧语义，已停用：新语义里扳机 = 鼠标左键
                    onDrag = { },
                    onDown = { },
                )
            }
        }
        setContentView(glView)

        // 请求焦点：手柄的悬停/按键事件必须先有焦点才会送到本视图
        glView.requestFocus()

        // 启动 XR 会话（失败不崩，退化为普通 2D 渲染，便于在没有头显时调试）
        val ok = vrSession.start(this)
        Log.i(TAG, "XR 会话启动: $ok")

        /*
         * VR 影院模式（2026-10-05）：起官方 OpenXR 会话，由原生线程做立体渲染。
         * 第一步只画黑底 + 正前方一块平面（先证明 VR 模式能出画面），
         * 面板纹理与手柄输入随后接。失败不影响应用活着（原生线程自己吞错误）。
         */
        /*
         * 运行时可调参数（父亲 2026-10-08：不要写死，戴着调参比反复编译划算）。
         *
         * 超采样在建交换链时读，所以先同步读一次文件灌进原生层；
         * 其余参数（画布尺寸 / 等图超时 / 层开关 / 内核属性）交给每秒一次的轮询，
         * 文件一改就生效，不用重编重装。
         */
        com.xxxx.emby_vr.vr.VrTuning.applyStartup(this)
        /* 菜单里「3D 模式」显示的档位，以调参文件为准（老配置只有 stereo3d 时按标准算） */
        menuState.stereoMode = com.xxxx.emby_vr.vr.VrTuning.value(this, "stereo_mode")?.toIntOrNull()
            ?: if (com.xxxx.emby_vr.vr.VrTuning.value(this, "stereo3d") == "1") 1 else 0
        menuState.stereoRemote = com.xxxx.emby_vr.vr.VrTuning.value(this, "depth_remote") == "1"
        com.xxxx.emby_vr.vr.VrTuning.start(
            this,
            scope,
            onVideoSize = { w, h ->
                runCatching {
                    if (this::renderer.isInitialized) renderer.setVideoBufferSize(w, h)
                    mpvBackend?.setSurfaceSize(w, h)
                }
            },
            onMpvOption = { name, value ->
                com.xxxx.emby_vr.player.MpvBackend.setOptionRuntime(name, value)
            },
            onMpvCommand = { args ->
                com.xxxx.emby_vr.player.MpvBackend.commandRuntime(args)
            },
        )

        /*
         * 影厅亮度记住上次（父亲 2026-10-10：「默认设为 0，或者记住我上次选的亮度」）：
         * 没存过 → 1%（父亲 2026-10-12 改的默认值）；存过 → 用上次那一档。
         *
         * 一次性迁移（2026-10-12）：默认值从 0 改成 1% 时，老机器上早就存着一个 0
         * （老默认就是 0，只要动过摇杆就被记下来），会把新默认值顶掉 —— 父亲装完新版
         * 一打开看到的还是 0%，日志里正是"影厅亮度恢复：0%"。所以升级后第一次启动
         * 强制回到 1%，之后照旧记住他调的那一档。
         */
        runCatching {
            if (!placePrefs.getBoolean(PREF_BRIGHT_RESET_V2, false)) {
                placePrefs.edit()
                    .putFloat(PREF_CINEMA_BRIGHT, 0.01f)
                    .putBoolean(PREF_BRIGHT_RESET_V2, true)
                    .apply()
                Log.i(TAG, "影厅亮度默认值迁移：旧存值重置为 1%")
            }
            /*
             * 第二轮默认值迁移（父亲 2026-10-14 定：影厅亮度 3%、座椅高 0.11、前后 -0.32）。
             * 老机器上这三个值早就存着旧数（亮度 1%、座椅 0.23 / 0），只改代码里的默认值
             * 会被旧存档顶掉 —— 所以升级后第一次启动强制写一遍，之后照旧记住他调过的档。
             */
            if (!placePrefs.getBoolean(PREF_CINEMA_RESET_V3, false)) {
                placePrefs.edit()
                    .putFloat(PREF_CINEMA_BRIGHT, 0.03f)
                    .putFloat(PREF_SEAT_UP, 0.11f)
                    .putFloat(PREF_SEAT_FWD, -0.32f)
                    .putBoolean(PREF_CINEMA_RESET_V3, true)
                    .apply()
                Log.i(TAG, "影厅默认值迁移 V3：亮度 3%、座椅高 0.11、前后 -0.32")
            }
            val saved = placePrefs.getFloat(PREF_CINEMA_BRIGHT, 0.03f).coerceIn(0f, 1f)
            menuState.cinemaBright = saved
            com.xxxx.emby_vr.vr.VrNative.setTuning(
                com.xxxx.emby_vr.vr.VrTuning.KEY_CINEMA_BRIGHT, saved,
            )
            Log.i(TAG, "影厅亮度恢复：${(saved * 100).toInt()}%")

            /* 座椅调节也恢复上次的位置（父亲 2026-10-10 22:56：调好一次就别再动） */
            val up = placePrefs.getFloat(PREF_SEAT_UP, 0.11f)
                .coerceIn(-com.xxxx.emby_vr.panel.SEAT_UP_RANGE, com.xxxx.emby_vr.panel.SEAT_UP_RANGE)
            val fwd = placePrefs.getFloat(PREF_SEAT_FWD, -0.32f)
                .coerceIn(-com.xxxx.emby_vr.panel.SEAT_FWD_RANGE, com.xxxx.emby_vr.panel.SEAT_FWD_RANGE)
            menuState.seatUp = up
            menuState.seatFwd = fwd
            com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_UP, up)
            com.xxxx.emby_vr.vr.VrNative.setTuning(com.xxxx.emby_vr.vr.VrTuning.KEY_SEAT_FWD, fwd)
            Log.i(TAG, "座椅调节恢复：高 ${"%.2f".format(up)} 前后 ${"%.2f".format(fwd)}")
        }

        /*
         * 座椅布纹（父亲 2026-10-10：只贴座椅、只贴观影位前后各两排）：
         * 在 Java 侧用 BitmapFactory 解码 PNG（native 里不带图像解码），
         * 像素交给原生，GL 上传由渲染线程在上下文就绪后做。
         */
        runCatching {
            assets.open("seat_fabrics.png").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    com.xxxx.emby_vr.vr.VrNative.setSeatTexture(px, bmp.width, bmp.height)
                    Log.i(TAG, "座椅贴图集已解码：${bmp.width}x${bmp.height}")
                    bmp.recycle()
                } else {
                    Log.w(TAG, "座椅布纹解码失败")
                }
            }
        }.onFailure { Log.w(TAG, "座椅布纹准备失败：${it.message}") }

        /*
         * 影厅资源（父亲 2026-10-10）：assets 里的影厅几何与环境光贴图先落到应用目录，
         * 再把路径交给原生 —— 必须在 startVr 之前，原生在渲染线程起 GL 时就要读它们。
         */
        runCatching {
            val cinema = copyAssetIfNeeded("cinema.b0bcin")
            val env = copyAssetIfNeeded("cinema_env.b0benv")
            if (cinema != null) {
                com.xxxx.emby_vr.vr.VrNative.setAssetPaths(cinema, env ?: "")
                Log.i(TAG, "影厅资源就绪：几何=$cinema 环境光=$env")
            } else {
                Log.w(TAG, "影厅几何缺失，VR 里只有黑背景")
            }
        }.onFailure { Log.w(TAG, "影厅资源准备失败：${it.message}") }

        /*
         * 杜比参数交接文件（2026-11-13）：内核写、渲染层读。
         * 必须在开始 VR 之前设好，内核那边是按这个环境变量落盘的。
         */
        com.xxxx.emby_vr.vr.VrNative.setDoviParamPath(
            java.io.File(
                getExternalFilesDir(null) ?: filesDir,
                "dovi_params.bin",
            ).absolutePath,
        )
        val vrOk = com.xxxx.emby_vr.vr.VrNative.startVr(this)
        Log.i(TAG, "OpenXR 会话启动: $vrOk")
        // 光柱输入回推（VR 模式下唯一的输入源：指向 / 扳机 / 摇杆 / B 键）
        com.xxxx.emby_vr.vr.VrNative.attachInputSink(vrInput)

        // P2 海报墙已取消（父亲 2026-10-04：选片走电视版界面，不再需要 VR 原生海报墙）
        // loadLibrary()
    }

    // ---- Emby 内容加载（P2）----

    /**
     * 面板层请求播放（在电视版界面上点了播放）。
     *
     * 播放屏是 VR 原生的（视频画面 + 弹幕 + 字幕 + 控制条），
     * 本轮先把请求记录下来，下一步接 VR 原生播放：按 mediaId 取播放地址 →
     * 交给现有的 ExoPlayer 管线渲染到虚拟屏，面板同时收起。
     */
    private fun onPanelPlayRequested(mediaId: String, positionTicks: Long) {
        if (mediaId.isBlank()) return
        Log.i(TAG, "面板请求播放: mediaId=$mediaId positionTicks=$positionTicks")
        /*
         * 从海报墙点片 = 切片：起播路径里有两处 stop 都看 replaying 这个标记，
         * 少了它 startPlayer 会按 keepUi=false 把控制条收掉（父亲 2026-10-07 实测）。
         */
        replaying = true
        com.xxxx.emby_vr.panel.PanelSignals.bump()
        /*
         * 从海报墙点片 = 换片：**先停旧片、清屏**（父亲 2026-10-07）。
         * 这里不比较 mediaId —— 点片就是"换一个看"的明确意图，
         * 哪怕点到同一部片也该重新加载（清屏 → 转圈 → 起播）。
         */
        clearForNewMedia()
        playMedia(mediaId, positionTicks)
    }

    /** 播放器：面板点播放起播，返回键释放 */
    private var player: ExoPlayer? = null

    /**
     * mpv 解码内核（父亲 2026-10-08）：系统解码器吃不下杜比视界这类片源时改用它。
     * 同一时刻只有一条内核在跑 —— 走 mpv 时 player 为 null，反之亦然。
     */
    private var mpvBackend: com.xxxx.emby_vr.player.MpvBackend? = null

    /**
     * 自管解码（`videopipe=1` 时才有实例）。
     *
     * 打开后视频由它解到画面纹理，mpv 只留音频与播放控制。
     * 关掉时恒为 null，行为与原来完全一致。
     */
    private var videoPipe: com.xxxx.emby_vr.player.VideoPipe? = null

    /** P5 自管音频：声音由我们自己解、自己送设备，内核只当控制载体（且静音） */
    @Volatile private var selfAudioOn = false
    private var audioPipe: com.xxxx.emby_vr.player.AudioPipe? = null

    /**
     * 播放时钟（P5 自管路的位置来源）。
     *
     * 每部片一个，跳转/失步引起的重建沿用同一个实例 —— 重建若另起一个，位置从零开始，
     * 画面会立刻跳回片头。
     */
    private var playClock: com.xxxx.emby_vr.player.PlaybackClock? = null

    /** 自管路的暂停状态：内核不参与之后，暂停只有一个状态位 */
    @Volatile private var selfPaused = false

    /** 本次起播的时间轴起点（画面首帧的真实时间，秒） */
    @Volatile private var selfOriginSec = 0.0

    /**
     * 任意一路卡住时置 true：画面据此握帧、声音据此暂停。
     *
     * 与 [selfPaused]（用户按的暂停）分开存：用户暂停时两路都停，数据卡住时两路也都停，
     * 但恢复的判据不同 —— 用户暂停只有用户能解，数据恢复自己就能解。
     */
    @Volatile private var avStall = false

    /**
     * 画面首帧**真正上屏**了没有。
     *
     * 放行动作（也就是声音设备起动）必须先看到它是 true。
     * 之前只检查"首帧解出来了"，于是音频那边准备好的信号一到就放行，声音照旧抢在前面。
     */
    @Volatile private var selfFirstShown = false

    /**
     * 起播代次：每次重建 +1。所有管线回调都要带代次，晚到的旧回调一律丢弃。
     * 只比 URL 拦不住 —— 同片跳转 URL 完全相等。
     */
    private val selfGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 重建期间用户又拖了一次：只留最新目标，等这次结束再执行 */
    @Volatile private var pendingJumpSec = -1.0

    /**
     * 本次跳转开始前的真实位置（<0 表示当前不是跳转）。
     * 认领跳转时先把时钟拨到目标；中途任何一条中止都必须把它恢复回去，
     * 否则刻度停在目标、画面黑着、只能重进应用（豆豆 2026-11-21）。
     */
    /**
     * 睡眠挂起（2026-11-21，豆豆裁定 A 方案的第一步）：
     * 摘头显后系统走 onPause，但我们原来什么都不做 —— 声音设备停了、时钟照样走，
     * 醒来那一刻时钟把整段睡眠算成播放时间，画面怎么追都对不上。
     * 这里记下挂起位置与挂起时刻，恢复时据此决定"原地"还是"整条重建"。
     */
    @Volatile private var sleepSuspendSec = -1.0
    @Volatile private var sleepSuspendAtMs = 0L

    /** 每次起播/重建尝试的编号：旧尝试的定时器与回调据此作废（豆豆 2026-11-21 第七环） */
    private val selfTx = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var jumpFromSec = -1.0


    /**
     * 自管解码起播时，声音要等画面首帧。
     *
     * 为 true 时：画面只放第一帧然后停住，声音也挂在校起播位置不发。
     * 首帧到位后置 false 并放开声音，两路从同一时刻起步。
     */
    private val selfPipeWaitingFirstFrame = java.util.concurrent.atomic.AtomicBoolean(false)

    /*
     * 起播可观测性（父亲 2026-11-17 终版口径：**不设超时判死**，替代机制是健康行从起播第一秒就开打）。
     * 这些只是"看得见"，任何一条都不触发自动处置。
     */
    @Volatile private var selfPipeCanOpen = false
    @Volatile private var selfPipeReady = false
    @Volatile private var selfPipeReleased = false
    @Volatile private var selfPipePhase = "未起播"

    /*
     * ── 单一闸门 I5（图 §5）──
     *
     * 放行动作**只有这一个入口**：前置条件唯一 = 声音侧就绪（三样可观测事实）；
     * `setPaused(false)` 与 `setMute(false)` 全仓只允许出现在这里（验收 grep）。
     * 起播与重建不许各写一套 —— 实测后果是起播黑屏、快进而重建路径绕过闸门直接出画，
     * 但静音从未解除 → "有画无声"（2026-11-17）。
     */
    private val pipeReleasedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 起播防重入：同进程只允许一套起播在飞 */
    private val pipeBootInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun releasePlayback(kind: String, source: String): Boolean {
        if (!com.xxxx.emby_vr.player.MpvBackend.isReady()) {
            Log.w(TAG, "放行被拒：engineNotReady|kind=$kind|source=$source")
            return false
        }
        /*
         * 水位恢复（WATER）不看"声音侧是否就绪"：那个前提是给"起播/重建等首帧"用的，
         * 水位停等发生时内核早就在播了；若照旧卡这道门，恢复请求会被一律拒掉，
         * 现场就是"灰色缓冲已经回来了，但画面声音一直停着"（2026-11-18 后退实测）。
         */
        /*
         * 自管音频模式下，"声音侧就绪"= 第一块 PCM 已经写进设备。
         * 这时内核只是控制载体、静音跑着，它的就绪状态不再代表能不能放行。
         */
        if (kind != "WATER" && selfAudioOn) {
            val ap = audioPipe
            if (ap == null || ap.firstPcmAtMs == 0L) {
                Log.w(TAG, "放行被拒：自管音频未出声|kind=$kind|source=$source|轨=${ap?.trackDesc}")
                return false
            }
        } else if (kind != "WATER" && !com.xxxx.emby_vr.player.MpvBackend.audioReady()) {
            Log.w(
                TAG,
                "放行被拒：声音侧未就绪（${com.xxxx.emby_vr.player.MpvBackend.canOpenBlocker()}）" +
                    "|kind=$kind|source=$source",
            )
            return false
        }
        selfPipeWaitingFirstFrame.set(false)
        if (kind == "BOOT" || kind == "WATER") {
            /* 起播 / 水位恢复：解除暂停（水位恢复时是"我们为等数据停的"，用户没按暂停） */
            runCatching { mpvBackend?.setPaused(false) }
        }
        /*
         * 重建只在"是我们为等画面而暂停"时才解除暂停；
         * 用户自己暂停着快进的情况不动（那是另一个 bug）。
         */
        if (kind != "BOOT" && pipePausedForRebuild) {
            runCatching { mpvBackend?.setPaused(false) }
            pipePausedForRebuild = false
        }
        /* 自管音频时内核必须一直静音：解除静音就是双声 */
        if (!selfAudioOn) runCatching { mpvBackend?.setMute(false) }
        pipeReleasedOnce.set(true)
        selfPipeReleased = true
        selfPipePhase = if (kind == "BOOT") "PLAYING" else "PLAYING(重建)"
        Log.i(TAG, "放行完成|kind=$kind|source=$source|动过的开关=${if (kind == "BOOT") "pause+mute" else "mute(幂等)"}")
        return true
    }

    /** 是否为"等画面就绪"而暂停过声音（重建路径用，放行时再解除） */
    @Volatile private var pipePausedForRebuild = false

    /** 是否为"数据不够"而把播放停住（运行水位；用户自己按的暂停不在此列） */
    @Volatile private var pipePausedByWater = false
    /** 是否见过"位置之后有余量"：起播头几拍不算，避免一上来就被水位判停 */
    @Volatile private var waterSeen = false
    private var waterTick: Runnable? = null
    /** 本次播放的时长（秒），水位换算用 */
    @Volatile private var pipeDurationSec = 0.0

    /** 当前缓冲档位对应的三档水位：起播攒多少 / 低了停 / 攒回多少（秒） */
    private fun bufferWatermarks(): Triple<Double, Double, Double> =
        com.xxxx.emby_vr.panel.BUFFER_WATERMARKS[
            bufferPresetIndex.coerceIn(0, com.xxxx.emby_vr.panel.BUFFER_WATERMARKS.size - 1),
        ]

    /**
     * 运行水位：位置之后剩不到"低了停"就停住等，回到"攒回多少"再继续（父亲 2026-11-18 定）。
     *
     * 判据只有一条 —— 播放位置之后还有多少秒本地数据；不再用"这一拍有没有读到"，
     * 那种判法一抖就停、数据一来就走，现场表现是一路抖。
     */
    private fun startWaterMonitor() {
        stopWaterMonitor()
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val (_, low, high) = bufferWatermarks()
                /* 水位只认播放进度（mpv 的读位置含它自己的预读，会假性偏低） */
                runCatching { mpvBackend?.positionExtrapolated() }.getOrNull()?.let {
                    com.xxxx.emby_vr.player.SharedReadCache.setPlayPosSec(it)
                }
                val water = com.xxxx.emby_vr.player.SharedReadCache.waterAheadSecs(pipeDurationSec)
                /*
                 * 只在"确实有过余量"之后才允许按水位停。
                 * 起播那一刻水位天然是 0（数据还没落盘），若照判就会一上来就停住，
                 * 而恢复要等水位涨到高水位 —— 拉流稍有闪失就变成永久黑屏、进度停在 0。
                 */
                if (water > 0.0) waterSeen = true
                if (water >= 0.0 && waterSeen && videoPipe != null) {
                    if (!pipePausedByWater && water < low) {
                        pipePausedByWater = true
                        runCatching { mpvBackend?.setPaused(true) }
                        Log.i(TAG, "停等|水位=${"%.1f".format(water)}s < ${low}s（先停住，别让它抖）")
                        hud("缓冲中…")
                    } else if (pipePausedByWater && water >= high) {
                        pipePausedByWater = false
                        /* 走唯一闸门（I5）：水位恢复只是解除暂停，不动静音 */
                        releasePlayback("WATER", "水位恢复")
                        Log.i(TAG, "恢复|水位=${"%.1f".format(water)}s ≥ ${high}s")
                    }
                }
                ui.postDelayed(this, 500)
            }
        }
        waterTick = tick
        ui.postDelayed(tick, 1000)
    }

    private fun stopWaterMonitor() {
        waterTick?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        waterTick = null
        pipePausedByWater = false
        waterSeen = false
    }

    /** 跳转代数：每跳一次 +1，上一跳的回调一律作废（连跳时只认最后一次） */
    @Volatile private var seekReleaseGen = 0

    /**
     * 跳转放行的判定（父亲 2026-11-18 定）。
     *
     * 顺序：画面跳到目标之后的第一帧 → 声音跳到这一帧（只往前，不回退）→ 水位够 → 放行。
     * 判据里不要求"帧等于目标"：画面只能落在关键帧上，要求相等等于靠运气。
     */
    private fun releaseAfterSeek(gen: Int, targetSec: Double, shownSec: Double) {
        if (gen != seekReleaseGen) return
        val m = mpvBackend ?: return
        /* 第一步：声音对齐到画面这一帧（只允许往前跳） */
        val pos = runCatching { m.positionExtrapolated() }.getOrNull()
        if (pos != null && shownSec - pos > 0.3) {
            Log.i(
                TAG,
                "跳转对齐：声音 ${"%.2f".format(pos)}s → 画面帧 ${"%.2f".format(shownSec)}s" +
                    "（目标 ${"%.2f".format(targetSec)}s）",
            )
            runCatching { m.seekToSec(shownSec) }
        }
        /* 第二步：等水位够再放行 */
        val (_, _, high) = bufferWatermarks()
        var tries = 0
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (gen != seekReleaseGen) return
                val water = com.xxxx.emby_vr.player.SharedReadCache.waterAheadSecs(pipeDurationSec)
                if (water < 0.0 || water >= high) {
                    Log.i(
                        TAG,
                        "跳转放行：画面帧=${"%.2f".format(shownSec)}s 水位=${"%.1f".format(water)}s" +
                            "（第 ${tries + 1} 拍）",
                    )
                    releasePlayback("REBUILD", "跳转放行")
                    return
                }
                tries++
                ui.postDelayed(this, 300)
            }
        }
        ui.postDelayed(tick, 300)
    }

    /* 等待时长只报数不判死（图 §4.1 / §12 L23）：起播、跳转、收场各记一个起点 */
    @Volatile private var pipeLoadT0 = 0L
    @Volatile private var pipeSeekT0 = 0L
    @Volatile private var lastStopWaitedMs = -1L
    private var pipeHealthTick: Runnable? = null

    /** 起播健康行：每 2s 一行，覆盖 STARTING / 等信号 / 预热 / 等放行 / PLAYING / 卡住 全过程 */
    private fun startPipeHealth() {
        stopPipeHealth()
        selfPipeCanOpen = false
        selfPipeReady = false
        selfPipeReleased = false
        selfPipePhase = "等 CAN_OPEN"
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val p = videoPipe
                /* 自管路的读数只有自管时钟一个来源：问内核等于问人不在场 */
                val pos = if (selfAudioOn) playClock?.positionSec() else runCatching { mpvBackend?.positionSec() }.getOrNull()
                val shown = p?.lastShownPts ?: -1.0
                val nexus = if (pos != null && shown > 0 && selfPipeReleased) {
                    "%.0fms".format((pos - shown) * 1000.0)
                } else "-"
                Log.i(
                    TAG,
                    "PIPEHEALTH|state=$selfPipePhase|CAN_OPEN=${if (selfPipeCanOpen) "到" else "-"}" +
                        "|READY=${if (selfPipeReady) "到" else "-"}" +
                        "|decoded=${p?.decodedFrames ?: 0}|released=${p?.releasedFrames ?: 0}|held=${p?.heldSlot() ?: -1}" +
                        "|dequeued=${p?.dequeuedFrames ?: 0}|tryAgainStreak=${p?.tryAgainStreak ?: 0}" +
                        "|nexus=$nexus|rpu=${if (p?.rpuReadyNow() == true) "有" else "-"}" +
                        /*
                         * 等待时长只报数不判死。单位是毫秒，没开始计就打 `-`（不打 0，
                         * 免得读日志的人以为"已经等了 0 毫秒"）。三维里 input/buffer/stall
                         * 目前拿不到真值，同样打 `-`，不编数。
                         */
                        "|loadWaitedMs=${if (pipeLoadT0 > 0L) android.os.SystemClock.elapsedRealtime() - pipeLoadT0 else "-"}" +
                        "|seekWaitedMs=${if (pipeSeekT0 > 0L) android.os.SystemClock.elapsedRealtime() - pipeSeekT0 else "-"}" +
                        "|bufferWaitedMs=-|stopWaitedMs=${if (lastStopWaitedMs >= 0L) lastStopWaitedMs else "-"}" +
                        "|queueSec=${"%.1f".format(videoPipe?.queuedSec() ?: 0.0)}" +
                        "|lateMs=${videoPipe?.lateStreakMs ?: 0L}" +
                        "|loadInput=-|loadDecoder=${if (selfPipeReady) "ok" else "-"}" +
                        "|loadAudioOut=${if (selfPipeCanOpen) "ok" else "-"}" +
                        "|canOpenBlk=${com.xxxx.emby_vr.player.MpvBackend.canOpenBlocker()}" +
                        "|ageMs=${mpvBackend?.clockSampleAgeMs() ?: -1L}" +
                        "|posFresh=${mpvBackend?.posFresh() == true}" +
                        "|audioStallMs=-(音频是否推进看 mpv time-pos)" +
                        "|mute=${if (runCatching { mpvBackend?.isMuted() }.getOrNull() == true) "yes" else "no"}" +
                        "|paused=${if (runCatching { mpvBackend?.isPaused() }.getOrNull() == true) "yes" else "no"}",
                )
                ui.postDelayed(this, 2000)
            }
        }
        pipeHealthTick = tick
        ui.postDelayed(tick, 1000)
    }

    private fun stopPipeHealth() {
        pipeHealthTick?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        pipeHealthTick = null
    }

    /** 自管解码当前的地址与目标表面：跳转时要按新位置重建解码器。 */
    private var selfPipeUrl: String? = null
    private var selfPipeSurface: android.view.Surface? = null

    /**
     * 失步重启：停掉旧实例、按目标位置重建。
     *
     * 与 [restartVideoPipeAt] 的区别：这条**不重新静音、不重走五步门**（门保持放行）——
     * 声音一直在正常播，重新静音会掐掉一段；重走门会再等一次首帧、多黑几帧。
     */
    /**
     * 按指定位置重建自管画面解码器。
     *
     * 只在"解码器不在跑"或"失步靠丢帧追不回来"时用。能用热跳就别走这里：
     * 重建要重新开流、跳到关键帧，实测六到九秒；热跳是几百毫秒。
     */
    private fun restoreVideoPipeAt(sec: Double) {
        /*
         * 全包档不重建：重建出来的是 off 档 pipe（它自己起 feedLoop），会和还活着的 Session
         * 各读一条连接（豆豆 2026-11-22 P0-2）。失步只让 Session 重新定位。
         */
        val sessRestore = selfSession
        if (sessRestore != null) {
            Log.i(TAG, "全包档：失步重启改为 Session 定位（不重建）→ ${"%.2f".format(sec)}s")
            sessRestore.requestSeek((sec * 1000.0).toLong())
            return
        }
        val url = selfPipeUrl ?: return
        val surface = selfPipeSurface ?: return
        /*
         * 先把时钟设到新位置，再重建画面（2026-11-21 父亲实测：左跳后画面被拽回原处）。
         *
         * 原因：重建时画面已经在新位置（比如 6 分钟），而时钟还停在旧位置（20 多分钟），
         * 「起播对齐」就把画面判成落后一千多秒、又把它拽回旧位置；两路互相拉，最终错开。
         */
        selfOriginSec = sec
        playClock?.resetTo((sec * 1_000_000.0).toLong())
        val pipe = com.xxxx.emby_vr.player.VideoPipe()
        videoPipe = pipe
        /*
         * 新管线起播前先清空原生参数表（豆豆 2026-11-21）：
         * 放在 pipe.start() 之前 —— 此刻新解码线程还没起，不可能有旧推送抢在前面；
         * 代次同时带下去，万一旧线程晚到一条推送也会被原生按代次丢弃。
         */
        val ok = pipe.start(
            url, emptyMap(), surface,
            /* 时钟只有自管这一个来源：不再问内核 */
            { playClock?.positionSec() ?: sec },
            { playClock?.isRunning() == true },
            sec,
            java.io.File(getExternalFilesDir(null) ?: filesDir, "dovi_params.bin").absolutePath,
            holdGate = { false },
            /*
             * 跳转重建期间，时钟可能还停在旧位置（实测：左跳到 156 秒时时钟仍是 1177 秒，
             * 于是被判"画面落后一千秒"、画面被拽回十九分钟）。
             * 规则：时钟与目标差超过 5 秒，一律以目标为准。
             */
            clockReal = { playClock?.positionSec() ?: sec },
            pausedNow = { selfPaused },
            onFirstFrame = { },
            onOriginSec = { selfOriginSec = it },
            originFromPicture = true,
            onDoviParams = { com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(true) },
            onNeedRestart = { t ->
                if (selfSession != null) {
                    selfSession?.requestSeek((t * 1000.0).toLong())
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { runCatching { restoreVideoPipeAt(t) } }, 1500,
                    )
                }
            },
        )
        if (ok) {
            Log.i(TAG, "画面重建完成：位置=${"%.2f".format(sec)}s（声音未重新对齐，两路同一起点）")
        } else {
            Log.e(TAG, "画面重建失败：host=${hostOf(url)} 原因=${com.xxxx.emby_vr.player.VideoPipe.lastError}（银幕会黑）")
        }
    }

    /**
     * 跳转后按新位置重启自管解码。
     *
     * 画面是我们自己解的，内核跳转对它无效 —— 不重启的话画面留在原处、声音跳走了
     * （父亲实测：「一快进就回到 0，音画不同步」）。重建解码器会短暂黑一下，但位置是准的。
     */
    private fun restartVideoPipeAt(sec: Double) {
        val url = selfPipeUrl ?: return
        val surface = selfPipeSurface ?: return
        val pipe = videoPipe ?: return
        val m = mpvBackend ?: return
        /*
         * 快进重建期间让声音先停住（2026-11-17 实测定案）。
         *
         * 重建要重新开流、跳关键帧、解出首帧，实测 6~21 秒；这段时间声音若继续走，
         * 画面首帧一出来就已经落后五到二十秒，只能被"丢旧帧"全部丢掉 —— 观感就是
         * 快进后永远黑屏或一动就冻。按双信号原则：声音等画面，就绪后再一起放。
         */
        /*
         * 优先走**热解码器内跳转**（父亲 2026-11-17 定）：解码器一直在跑，跳位置只需
         * extractor.seekTo + codec.flush，几百毫秒；只有解码器不在跑或跳转失败才重建。
         * 重建要重新开流、重找关键帧，实测 6~21 秒，是"快进后半天没画面"的根源。
         */
        if (pipe.isRunning()) {
            /*
             * 快进：**两条链路用同一个目标一起跳**。
             * 只跳画面不跳内核，会得到"画面到新位置、声音还在旧位置"——
             * 听感就是声音丢了（2026-11-17 实测）。所以先把内核跳过去，再让画面跟过去。
             */
            Log.i(TAG, "快进：内核与画面同时跳到 ${"%.1f".format(sec)}s（热解码器，不重建）")
            /*
             * 用户跳转（三个入口都汇到这里）：通知中转改写入位置。
             * 往回跳清空重铺；往前跳落在缓存里只删左边；目标处没缓存就清空重铺。
             * 中转自己不再从读位置猜快进快退（父亲 2026-11-18 定）。
             */
            runCatching {
                val dur = m.durationSec()
                val from = m.positionExtrapolated()
                if (dur > 0.0) {
                    Log.i(TAG, "用户跳转：${"%.1f".format(from)}s → ${"%.1f".format(sec)}s（通知中转）")
                    com.xxxx.emby_vr.player.SharedReadCache.jumpTo(from, sec, dur)
                }
            }
            runCatching { m.seekToSec(sec) }
            if (selfSession != null) {
                /* 全包档：跳转只有 Session 一个入口；两条 Pipe 只清解码器与本地状态（图 §2-7） */
                pipe.flushForSessionSeek()
                selfSession?.requestSeek((sec * 1000.0).toLong())
                audioPipe?.onSessionSeek(sec, sec)
            } else {
                pipe.requestSeek(sec)
            }
            /*
             * 跳转后**不立刻放开**（父亲 2026-11-18 双信号口径）：先把声音压住，
             * 等画面出新帧（`pipe.onSeekRendered`）+ 内核报出的位置和这一帧对得上，
             * 再同一拍解除暂停与静音。
             * 原来这里立刻 setPaused(false)：声音先跑、画面还在跳，现场表现是
             * "跳转后要手点一次播放键"。
             */
            pipePausedForRebuild = true
            runCatching { m.setPaused(true) }
            seekReleaseGen++
            val mySeekGen = seekReleaseGen
            pipe.onSeekRendered = { shown ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    releaseAfterSeek(mySeekGen, sec, shown)
                }
            }
            /*
             * 兜底：2 秒没等到画面新帧（解码器卡住 / 那一段没数据）也必须放一次，
             * 否则声音被一直扣着，现场是"跳完没声音，得手点一下"。
             */
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (mySeekGen == seekReleaseGen && pipePausedForRebuild) {
                    val p2 = runCatching { m.isPaused() }.getOrNull()
                    val pos = runCatching { m.positionExtrapolated() }.getOrNull()
                    Log.w(
                        TAG,
                        "跳转放行兜底：2 秒内没等到画面新帧|paused=$p2" +
                            " 位置=${pos?.let { "%.1f".format(it) }} 目标=${"%.1f".format(sec)}",
                    )
                    releasePlayback("REBUILD", "跳转兜底")
                }
            }, 2000)
            return
        }
        pipePausedForRebuild = true
        runCatching { m.setPaused(true) }
        Log.i(TAG, "跳转重建：先暂停声音，等画面就绪再一起放 @ ${"%.1f".format(sec)}s")
        /*
         * 图 §4.4：跳转重建必须判返回值。原来这里静默丢弃 false ——
         * `setDataSource`/`seekTo` 抛异常时一声不响，用户观感就是"快进无效"。
         */
        val ok = pipe.start(
            url, emptyMap(), surface,
            /* 重建路径也用推算时钟（同一条口径，不再分可用/不可用） */
            { m.positionExtrapolated() },
            { true },
            sec,
            java.io.File(getExternalFilesDir(null) ?: filesDir, "dovi_params.bin").absolutePath,
            /* 重建路径不放行整段：声音没就绪就压住（既不等预热，也不绕过闸门） */
            holdGate = { !com.xxxx.emby_vr.player.MpvBackend.audioReady() },
            onFirstFrame = { },
            onDoviParams = { com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(true) },
            onNeedRestart = { target ->
                /* 已是重启路径，防递归：只在偏差仍然很大时再排一次 */
                Log.w(TAG, "seek 后仍失步，排一次延迟重启 @ $target")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { runCatching { restartVideoPipeAt(target) } }, 800,
                )
            },
        )
        if (ok) {
            Log.i(TAG, "跳转重启完成：位置=$sec")
            /* 重建场景：只确保不静音（幂等），绝不动 pause */
            releasePlayback("REBUILD", "跳转重建")
        } else {
            Log.e(TAG, "跳转重启失败：host=${hostOf(url)} 原因=${com.xxxx.emby_vr.player.VideoPipe.lastError}")
            hud("快进后画面起不来")
        }
    }

    /** 日志里只留主机名，不落完整地址（图 §12 L2 口径：url 只留 host + 末 12 位） */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url.take(32) }.getOrDefault(url.take(32))

    /**
     * 把片源的真实地址拼成小雅 `/d/` 地址，供中转直连网盘用（2026-11-18）。
     *
     * 片源形如 `http://xiaoya.host:5678/d/每日更新/…/x.mkv`。`xiaoya.host` 是 .15 容器内部的名字，
     * 头显上解析不了，所以主机一律换成本机 Emby 服务那台机器的地址，端口取调参值（默认 5678）。
     * 拼不出来就返回 null —— 这一部片照旧走 Emby，直连只做加速，不许成为新的失败点。
     */
    private fun alistUrlOf(rawPath: String?): String? {
        if (rawPath.isNullOrBlank()) return null
        val idx = rawPath.indexOf("/d/")
        if (idx < 0) return null
        val host = runCatching { java.net.URI(userServer()).host }.getOrNull() ?: return null
        val port = com.xxxx.emby_vr.vr.VrTuning.relayAlistPort
        /* 中文与全角括号必须编码；按 '/' 分段编码，避免把分隔符本身编掉 */
        val enc = rawPath.substring(idx).split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "http://$host:$port$enc"
    }


    /**
     * 自管解码起播：画面交给 [VideoPipe]，音频与播放控制仍归 mpv。
     *
     * 为什么两条一起跑：位置、时长、音轨切换、字幕、进度上报现在全在 mpv 那条路上，
     * 一次性搬过来风险太大。先只把「画面」这一段换掉，其余不动，出问题好定位。
     *
     * `vid=no` 必须用属性接口（`setPropertyString`）——`setOptionString` 只在 mpv
     * 初始化之前有效，播出中调等于没调且不报错（r560 实测过这个坑）。两者都走
     * MpvBackend 的单线程队列，所以顺序上 loadfile 一定在它前面。
     */
    /**
     * P5 自管起播：画面与声音全部由我们自己解，内核（mpv）在这条路上完全不参与。
     *
     * 为什么这么彻底：内核一参与就要开自己的连接、自己解一遍音频、自己报位置，
     * 两个解码器之间又要靠中转去协调 —— 那套协调是两天来黑屏与卡顿的来源。
     * 现在只有一个解码器：画面一条连接、声音一条连接，网盘的两路额度正好用满。
     */
    private fun startSelfVideoPipe(
        rawUrl: String,
        startSec: Double,
        surface: android.view.Surface,
        m: com.xxxx.emby_vr.player.MpvBackend,
        /** 调用来源（正常起播 / 重建），进日志便于对账 */
        source: String = "正常起播",
        /** 入口已经用 CAS 认领过（跳转路径）：这里不再重复认领 */
        alreadyClaimed: Boolean = false,
    ) {
        selfAudioOn = true
        videoEos = false
        audioEos = false
        videoEosCount = 0
        eosWatchdogPosted = false
        /* 跳转/重建：字幕表跟着清，否则新位置的画面上会显示旧位置的字幕（豆豆 2026-11-22） */
        demuxSubCues.clear()
        subtitleCues = if (demuxSubTrackWanted >= 0) emptyList() else subtitleCues
        /* 清完当场重绘，否则旧字幕会留在屏上直到下一次重绘（豆豆 2026-11-22） */
        if (demuxSubTrackWanted >= 0) {
            subtitleNow = ""
            danmakuView?.setSubtitle("")
        }
        val gen = selfGen.incrementAndGet()
        val tx = selfTx.incrementAndGet()
        if (playClock == null) playClock = com.xxxx.emby_vr.player.PlaybackClock()
        /* 主线程投递句柄：这个函数现在可能跑在工作线程（跳转重建） */
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        ctl.selfPositionProvider = { if (selfAudioOn) playClock?.positionUs()?.div(1000L) else null }
        ctl.selfDurationProvider = {
            if (!selfAudioOn) null
            else videoPipe?.durationSec?.takeIf { it > 0.0 }?.let { (it * 1000.0).toLong() }
        }
        ctl.selfPlayingProvider = { if (selfAudioOn) !selfPaused else null }
        /*
         * 缓冲段：位置 + 队列里还攒着的秒数（内核不再加载文件，原来那个数没人给了）。
         */
        ctl.selfBufferedProvider = {
            if (!selfAudioOn) null
            else {
                val ahead = (videoPipe?.queuedSec() ?: 0.0) * 1000.0
                (playClock?.positionUs()?.div(1000L) ?: 0L) + ahead.toLong()
            }
        }
        ctl.selfTogglePlayPause = {
            if (!selfAudioOn) null else {
                selfPaused = !selfPaused
                if (selfPaused) {
                    playClock?.pause()
                    runCatching { audioPipe?.pause() }
                } else {
                    playClock?.resume()
                    runCatching { audioPipe?.resume() }
                }
                Log.i(TAG, if (selfPaused) "暂停：画面与声音同一拍停" else "继续：画面与声音同一拍走")
                true
            }
        }

        /* 直连：这条路上不经中转，画面与声音各一条连接 */
        val url = rawUrl

        if (videoPipe?.fatal == true) {
            /*
             * 读线程卡在网络读里退不出来，连接也就还不回去；强开新连接只会被网盘拒。
             * 这种状态只能退出应用重进（豆豆 2026-11-21）。
             */
            abortJump("上一路解码器卡死")
            Log.e(TAG, "拒绝起播：上一路解码器卡死，连接未归还")
            ui.post { hud("解码器卡死，请退出应用重进") }
            /* 这里还没 CAS，标志不属于自己：清了会把别人正在飞的那条拆掉（豆豆 2026-11-21） */
            return
        }
        if (!alreadyClaimed && !pipeBootInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "起播入口被拒：已有起播在飞|source=$source|url=${url.take(60)}")
            return
        }
        Log.i(
            TAG,
            "起播入口|source=$source|startSec=${"%.1f".format(startSec)}|直连主机=${hostOf(url)}|MPV=不参与",
        )
        com.xxxx.emby_vr.player.PlaybackFlags.webSource = false

        /*
         * 两路同时停（豆豆 2026-11-21）：串行 join 最坏 1.5+0.6+1.5+1.5 ≈ 5.1 秒，
         * 并行只取其中最大的那个 ≈ 2.6 秒，直接省 2~3 秒，风险为零。
         */
        val oldVideo = videoPipe
        val oldAudio = audioPipe
        videoPipe = null
        audioPipe = null
        var oldDead = true
        var audioDead = true
        val stopVideo = Thread({ oldDead = runCatching { oldVideo?.stop() ?: true }.getOrDefault(false) }, "stop-video")
        val stopAudio = Thread({ audioDead = runCatching { oldAudio?.stop() ?: true }.getOrDefault(false) }, "stop-audio")
        stopVideo.start()
        stopAudio.start()
        runCatching { stopVideo.join(9000) }
        runCatching { stopAudio.join(9000) }
        if (!oldDead || !audioDead) {
            /* 偶发没退干净：隔 200ms 各再收一次（保留重试，缩到 200ms） */
            Log.w(TAG, "旧管线未全部退出（画面=${oldDead} 声音=${audioDead}），再收一次")
            Thread.sleep(200)
            if (!oldDead) oldDead = runCatching { oldVideo?.stop() ?: true }.getOrDefault(false)
            if (!audioDead) audioDead = runCatching { oldAudio?.stop() ?: true }.getOrDefault(false)
        }
        if (!oldDead) {
            Log.e(TAG, "PIPE 起不来：zombie=true（旧解码线程未退出，拒绝新建，需重启应用）")
            ui.post { hud("画面解码起不来（需重启应用）") }
            pipeBootInFlight.set(false)
            abortJump("旧画面线程未退出")
            return
        }
        /*
         * 声音必须确认停干净再往下建（父亲 2026-11-21 实测：第二次跳转起有画面没声音）。
         *
         * 日志是「音频解封装准备超时」：旧的一路还没释放连接，新的那一路就又要连接，
         * 网盘只给两路并发，第三路连不上，等满二十秒就判失败。
         */
        if (!audioDead) {
            /*
             * 旧音频没退干净就绝不开新连接：网盘只给两路，第三路必被拒，
             * 结果是"画面在新位置、声音在旧位置"这类随机态（豆豆 2026-11-21 审出）。
             * 宁可停在旧画面上并明确报错。
             */
            Log.e(TAG, "跳转中止：旧音频连接未归还（避免第三路连接）")
            abortJump("旧音频连接未归还")
            pipeBootInFlight.set(false)
            /* 文案要如实：这时画面已经停了，写"请重试"会让人以为画面还在（豆豆 2026-11-21） */
            ui.post { hud("跳转失败：画面已停，请重试") }
            return
        }
        /* 给解封装释放连接留一拍，避免新旧连接在网盘那一侧叠在一起 */
        Thread.sleep(150)
        selfOriginSec = 0.0
        selfPaused = false
        selfFirstShown = false

        /*
         * 跳转：三样一起动，顺序固定 —— 时钟 → 声音 → 画面。
         * 画面在同一个解码器内跳（几百毫秒），不重建：重建要重新开连接、重新定位，实测六到九秒。
         */
        ctl.pipeSeek = jump@{ ms ->
            val sec = (ms / 1000.0).coerceAtLeast(0.0)
            pipeSeekT0 = android.os.SystemClock.elapsedRealtime()
            /*
             * 全包档：跳转**不重建**。重建会新建第二个 Session，旧的那条没人 stop
             * —— 客户端两条连接、旧读线程还在往队列塞包，网盘额度当场爆（豆豆 2026-11-22 P0-1）。
             * 这里只让 Session 定位；解码器清理由 flushForSessionSeek 登记、两条 Pipe 自己执行。
             */
            val sessJump = selfSession
            if (sessJump != null) {
                /* 先立刻静音：落点回灌前不能让旧位置的声音继续放完 */
                audioPipe?.silenceForSessionSeek()
                /* 用字段访问：这个 lambda 在 pipe 局部变量声明之前，直接引用会编译不过 */
                videoPipe?.flushForSessionSeek()
                sessJump.requestSeek(ms)
                Log.i(TAG, "全包档跳转：Session 定位到 ${"%.1f".format(sec)}s（不重建）")
                /*
                 * 3 秒兜底：目标靠近片尾或读取失败时，首个视频包可能永远不来，
                 * 落点回灌也就永不发生 —— 静音必须是有时限的（豆豆 2026-11-22 P1-2）。
                 */
                val mySeq = ++seekOriginSeq
                ui.postDelayed({
                    if (mySeq == seekOriginSeq && gen == selfGen.get()) {
                        Log.w(TAG, "全包档跳转 3 秒未收到落点 → 用请求目标兜底恢复声音")
                        /* 这次跳转的起点已经定过：晚到的回灌作废，别再清一次解码器/断一次声 */
                        seekOriginSeq++
                        audioPipe?.onSessionSeek(sec, sec, -1L)
                    }
                }, 3000L)
                return@jump
            }
            /*
             * 跳转 = 按新位置把整条自管路重建一遍（画面与声音一起）。
             *
             * 热跳（同一个解码器里跳）理论上更快，但它要求"解码器就地跳、音频换起点、时钟重置"
             * 三件事落在同一拍；实测三者一旦错拍，现场就是"声音跳到别处、画面停在原处"。
             * 位置准比省两秒重要，所以这条路上先只保留重建这一种做法。
             */
            val jumpUrl = selfPipeUrl
            val jumpSurface = selfPipeSurface
            if (jumpUrl == null || jumpSurface == null) {
                Log.w(TAG, "跳转被忽略：当前没有在播的自管内容")
            } else {
                /*
                 * 单飞：重建要 3~8 秒，这期间再按一次快进不能又起一条（两路并发重建会抢同一张
                 * Surface、各开一路连接，豆豆 2026-11-21 第二遍审出）。
                 * 已有一条在飞：只记下最新目标，等这条结束后再看一眼。
                 */
                /*
                 * 入口用 CAS 认领：读和写在同一拍，避免"判定在一处、认领在另一处"的缝
                 * （豆豆 2026-11-21：入口 get() + 起线程前 set(false) + 线程里 CAS 是三拍）。
                 */
                if (!pipeBootInFlight.compareAndSet(false, true)) {
                    pendingJumpSec = sec
                    Log.i(TAG, "跳转排队：已有重建在飞，记下目标 ${"%.2f".format(sec)}s")
                    return@jump
                }
                Log.i(TAG, "跳转认领：目标=${"%.2f".format(sec)}s")
                jumpFromSec = playClock?.positionSec() ?: 0.0
                /* 冻结参数：屏幕上还是上一帧，别让新场景的参数把它染色 */
                com.xxxx.emby_vr.vr.VrNative.setDoviHold(true)
                Log.i(TAG, "跳转：按 ${"%.2f".format(sec)}s 重建整条自管路（画面+声音+时钟）")
                /*
                 * 重建一开始就把时钟设到目标（父亲 2026-11-21 实测「进度条先退回老位置」）。
                 *
                 * 时钟原来要等画面首帧到位、白画面放行那一刻才重设（tryRelease 里 start），
                 * 而重建要三四秒 —— 这几秒进度条读到的还是旧时钟，看起来就是"跳回老位置"。
                 */
                selfOriginSec = sec
                /*
                 * 重建期间把时钟冻住（豆豆 2026-11-21 审出）。
                 *
                 * 原来只 resetTo，时钟仍在跑：重建要几秒，墙钟就自己走了几秒，
                 * 日志里「时钟比起点多 8.3 秒 ≈ 重建耗时」正是这条。
                 * 冻结后由放行那一刻 start(origin) 一次起步。
                 */
                playClock?.pause()
                playClock?.resetTo((sec * 1_000_000.0).toLong())
                /*
                 * 整条重建挪到工作线程（豆豆 2026-11-21 审出）。
                 *
                 * 重建里有「旧管线 join」「解封装 latch 最多 20 秒」「解码器 configure」，
                 * 原来全跑在界面线程上，跳一次界面就冻十几秒。
                 */
                val jumpBody = Runnable {
                    runCatching {
                        startSelfVideoPipe(jumpUrl, sec, jumpSurface, m, "跳转重建", alreadyClaimed = true)
                    }.onFailure {
                        /* 认领了却没跑起来：必须把标志交回去，否则之后再也跳不动 */
                        Log.e(TAG, "跳转线程启动失败：${it.message}")
                        pipeBootInFlight.set(false)
                        ui.post { hud("跳转启动失败，请重试") }
                    }
                }
                runCatching { Thread(jumpBody, "pipe-jump").start() }.onFailure {
                    Log.e(TAG, "跳转线程无法创建：${it.message}")
                    pipeBootInFlight.set(false)
                    abortJump("跳转线程无法创建")
                    ui.post { hud("跳转启动失败，请重试") }
                }
            }
        }

        selfPipeWaitingFirstFrame.set(true)
        pipeLoadT0 = android.os.SystemClock.elapsedRealtime()
        startPipeHealth()

        val doviPath = java.io.File(
            getExternalFilesDir(null) ?: filesDir,
            "dovi_params.bin",
        ).absolutePath
        selfPipeUrl = url
        selfPipeSurface = surface
        val pipe = com.xxxx.emby_vr.player.VideoPipe()
        /*
         * 全包档：画面/声音/字幕走**同一条**连接（图 §2）。
         * 默认 off；装配任何一步失败都退回 off（off 是已经验收过的路径，不许被这版拖坏）。
         */
        /*
         * 全包档开关：demux_mode=full 时画面/声音/字幕走同一条连接（图 §2）。
         * 这里只读调参文件；真正开会话是一次网络动作，必须放到工作线程
         * （2026-11-22 真机实测：放在界面线程会把控制条/海报墙一起冻死）。
         */
        val wantFullDemux = runCatching {
            (com.xxxx.emby_vr.vr.VrTuning.value(this, "demux_mode") ?: "off").trim().lowercase() == "full"
        }.getOrDefault(false)
        var session: com.xxxx.emby_vr.player.DemuxSession? = null
        var fullVideoFormat: android.media.MediaFormat? = null
        /*
         * 新管线起播前先清空原生参数表（豆豆 2026-11-21）：
         * 放在 pipe.start() 之前 —— 此刻新解码线程还没起，不可能有旧推送抢在前面；
         * 代次同时带下去，万一旧线程晚到一条推送也会被原生按代次丢弃。
         */
        pipe.pipeGen = gen.toLong()
        com.xxxx.emby_vr.vr.VrNative.resetDoviParams(gen.toLong())
        videoPipe = pipe

        var pipeReady = false
        var released = false

        /*
         * 放行：画面三帧齐 + 声音第一块已写进设备，两件都到才一起开跑。
         *
         * 这里没有水位、没有第二道闸门：数据直连，解码器自己要得到就说明数据在。
         */
        fun tryRelease(tries: Int) {
            /* 旧代的放行回调一律作废（豆豆 2026-11-21 第二遍审出） */
            if (gen != selfGen.get()) return
            if (released) {
                /*
                 * 已经放行过（多半是看门狗强制放行的），但声音可能此刻才建好。
                 * 这时必须补启动，否则"放行"这一刻之后再也不会有第二次机会 ——
                 * 现场就是：看门狗放行 → 首帧后到 → 声音起播 → 永久静音
                 * （2026-11-21 17:22 实测）。
                 */
                audioPipe?.let { ready ->
                    if (!ready.isStarted()) {
                        Log.i(TAG, "放行后补启动声音（看门狗放行时它还没建好）")
                        runCatching {
                            ready.bindClockToken(playClock?.token ?: 0L)
                            ready.startNow()
                        }
                    }
                }
                return
            }
            if (!pipeReady) return
            /*
             * 硬条件：画面首帧还没真正上屏，就不许放声音（父亲 2026-11-21 追问到底）。
             * 只判"解出来"会让声音先出十几秒 —— 那段时间画面还在首帧渲染上。
             */
            if (!selfFirstShown) return
            /* 与收黑幕同一个判据：声音也不许比正确画面先出来 */
            if (!com.xxxx.emby_vr.vr.VrNative.doviParamsReady()) {
                if (tries < 80) ui.postDelayed({ tryRelease(tries + 1) }, 100)
                return
            }
            /*
             * 队列要有存货才放行（豆豆 2026-11-21 第 2 条）。
             *
             * 原来准备完就放，等于第一秒裸跑网络：网盘一抖就直接暴露。
             * 门槛取 2 秒（约档位低水位的三分之一）。
             */
            val bufferedSec = videoPipe?.queuedSec() ?: 0.0
            /*
             * 门槛 1.0 秒（豆豆 2026-11-21）：0.5 秒 4K 只有三帧，抖一下就停；
             * 跳转是用户明确要到那个位置，没必要像冷起播那样攒两秒。
             */
            if (bufferedSec < 1.0) {
                if (tries < 80) {
                    ui.postDelayed({ tryRelease(tries + 1) }, 100)
                } else {
                    /*
                     * 队列攒不到门槛也必须放行并报错（豆豆 2026-11-21 第三遍审出）。
                     *
                     * 原来超时只打一行日志就 return —— 闸门永远关着：画面定格、没声音、没提示。
                     * 门槛是"尽量攒够"，不是"攒不够就不许播"。
                     */
                    Log.e(TAG, "放行超时：队列只攒到 ${"%.1f".format(bufferedSec)}s，先放画面并报错")
                    released = true
                    selfPipeWaitingFirstFrame.set(false)
                    ui.post { hud("缓冲不足（${"%.1f".format(bufferedSec)} 秒），先出画面") }
                    runCatching { playClock?.start((selfOriginSec * 1_000_000.0).toLong()) }
                    /*
                     * 音频其实已经就绪时也要一起起动（豆豆 2026-11-21）：
                     * released 置真之后 tryRelease 不会再进，不在这里起动就是"有画面没声音"。
                     */
                    audioPipe?.let { ready ->
                        runCatching {
                            ready.bindClockToken(playClock?.token ?: 0L)
                            ready.startNow()
                        }
                    }
                }
                return
            }
            val ap = audioPipe
            if (ap == null) {
                if (tries < 80) {
                    ui.postDelayed({ tryRelease(tries + 1) }, 100)
                } else {
                    /*
                     * 看门狗：等了八秒音频还没就绪，不能再把画面闸门永远关着
                     * （否则就是"永久定格、没有任何提示"）。放开闸门让画面先走，并明确报错。
                     */
                    Log.e(TAG, "放行超时：等音频解码器就绪已 ${tries * 100}ms，放开画面闸门并报错")
                    released = true
                    selfPipeWaitingFirstFrame.set(false)
                    ui.post { hud("声音起不来：画面先播，声音缺失") }
                    runCatching { playClock?.start((selfOriginSec * 1_000_000.0).toLong()) }
                }
                return
            }
            released = true
            selfPipeWaitingFirstFrame.set(false)
            selfPaused = false
            /*
             * 时钟、声音设备、画面闸门在同一拍动。
             *
             * 声音设备一经起动就开始按时间放样本，所以"起动"必须正好发生在画面放行的这一拍；
             * 早一拍就是先说话后出人，晚一拍就是开头少一句。这是实测出来的边界（PICO 4）。
             */
            playClock?.start((selfOriginSec * 1_000_000.0).toLong())
            /* 放行这一刻才把写权交给这一路音频（时钟刚换过令牌） */
            ap.bindClockToken(playClock?.token ?: 0L)
            runCatching { ap.startNow() }
            selfPipePhase = "PLAYING"
            selfPipeReleased = true
            /*
             * 放行成功 = 这条管线跑起来了，必须**无条件**交出在飞标志
             * （原来只在"有排队目标"时才清，导致无人排队时标志永远为真，
             *  之后左右跳全被判"已有重建在飞"而只记不执行 —— 2026-11-21 实测）。
             */
            pipeBootInFlight.set(false)
            jumpFromSec = -1.0
            waitFreshThenUnhold(0)
            com.xxxx.emby_vr.vr.VrNative.setDoviHold(false)
            /* 重建期间用户又拖过：现在这条已经跑起来，接着执行最新那个目标 */
            val pj = pendingJumpSec
            if (pj >= 0.0) {
                pendingJumpSec = -1.0
                Log.i(TAG, "执行排队中的跳转：${"%.2f".format(pj)}s")
                ui.post { runCatching { ctl.pipeSeek?.invoke((pj * 1000.0).toLong()) } }
            }
            Log.i(TAG, "放行：画面三帧齐 + 音频设备同刻起动（音轨=${ap.trackDesc}）")
        }

        /*
         * 强制看门狗（豆豆 2026-11-21）：tryRelease 前三条（gen / released / pipeReady /
         * selfFirstShown）任一为假就直接 return，根本不进那 80 次重试。
         * 所以只要首帧迟迟不来，放行永远不会发生 → 在飞标志永远为真 → 之后跳转全被挡。
         * 这条从起播那一刻计时，8 秒没放行就强制收场，保证标志一定被交出。
         */
        val watchdogT0 = android.os.SystemClock.elapsedRealtime()
        ui.postDelayed({
            /* 这次尝试已作废（失败/中止/换代）：定时器直接退场，不许再开火 */
            if (tx != selfTx.get() || gen != selfGen.get()) {
                Log.i(TAG, "看门狗作废（tx=$tx 当前=${selfTx.get()}）")
                return@postDelayed
            }
            /*
             * 只在「首帧还没上屏」时动手（豆豆 2026-11-21）：
             * 队列门槛与音频等待在 tryRelease 里是串行的，合法最坏路径约 16 秒；
             * 用 !released 判会把这条正常路径截断。首帧上屏之后就交给 tryRelease 自己的超时。
             */
            if (!released && (!pipeReady || !selfFirstShown)) {
                Log.e(
                    TAG,
                    "放行看门狗：${android.os.SystemClock.elapsedRealtime() - watchdogT0}ms 仍无首帧" +
                        "（pipeReady=$pipeReady 首帧上屏=$selfFirstShown）→ 强制放行并报错",
                )
                released = true
                selfPipeWaitingFirstFrame.set(false)
                pipeBootInFlight.set(false)
                ui.post { hud("画面迟迟不出，已强制放行") }
                runCatching { playClock?.start((selfOriginSec * 1_000_000.0).toLong()) }
                /* 音频若已就绪要一起起动，否则 released 之后 tryRelease 不再进 = 有画无声 */
                audioPipe?.let { ready ->
                    runCatching {
                        ready.bindClockToken(playClock?.token ?: 0L)
                        ready.startNow()
                    }
                }
            }
        }, 25_000L)

        /* 局部函数不能前向引用：先声明一个变量，等下面 Blocking 定义好再赋值，投线程时用它 */
        var pipelineLauncher: ((Double) -> Unit)? = null

        fun launchPipeline(audioStart: Double) {
            val body = pipelineLauncher
            if (body == null) {
                Log.e(TAG, "起播被拒：启动器还没就绪")
                return
            }
            Thread({ body(audioStart) }, "pipe-start").start()
        }

        fun launchPipelineBlocking(audioStart: Double) {
            Log.i(TAG, "画面管道启动：直连 起点=${"%.1f".format(audioStart)}s")
            /* 线程体兜底：任何意外异常都要落到"起播失败"，不许线程静默死掉（豆豆 2026-11-22 P1-1） */
            try {

            /* 建完发现代数已变（用户已切走/又跳了）：这条管线自己收场。
               注意这里不许碰 pipeBootInFlight —— 新一代可能已经 CAS 成功，
               在这里清标志会把它的单飞拆掉（豆豆 2026-11-21）。 */
            if (gen != selfGen.get()) {
                Log.w(TAG, "起播线程收场：代数已变（gen=$gen 当前=${selfGen.get()}），丢弃这条管线")
                runCatching { pipe.stop() }
                return
            }
            /*
             * 全包档会话在**工作线程**里开：nativeDemuxOpen 内含一次网络定位
             * （mkv 索引在文件尾，续播必须先读尾部），实测能把界面线程堵死几十秒。
             * 打开期没有句柄，靠 session.abortOpeningNow() 打断（15 秒兜底）。
             */
            if (wantFullDemux && session == null) {
                val tOpen = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "全包档：开始打开会话（工作线程，起点=${"%.1f".format(startSec)}s）")
                val probe = runCatching {
                    com.xxxx.emby_vr.player.DemuxSession(url, (startSec * 1000.0).toLong())
                }.getOrNull()
                /* 用原子量：工作线程写、守护线程读，普通变量在 JMM 下可能读到旧值 */
                val opened = java.util.concurrent.atomic.AtomicBoolean(false)
                if (probe != null) {
                    /* 15 秒兜底：到点还没返回就置打断标志，让 native 从网络定位里退出来 */
                    val watchdog = Thread {
                        try {
                            Thread.sleep(15_000)
                        } catch (t: InterruptedException) {
                            return@Thread
                        }
                        if (!opened.get()) {
                            Log.e(TAG, "全包档：打开超过 15 秒仍未返回 → 打断并退回 off")
                            runCatching { probe.abortOpeningNow() }
                        }
                    }
                    watchdog.isDaemon = true
                    watchdog.start()
                    opened.set(runCatching { probe.open() }.getOrDefault(false))
                    watchdog.interrupt()
                }
                if (!opened.get()) {
                    Log.w(TAG, "全包档：解封装打开失败 → 退回 off 档")
                    runCatching { probe?.stop() }
                } else {
                    val vf = probe!!.videoFormat()
                    if (vf == null) {
                        Log.w(TAG, "全包档：没有画面格式（csd 缺失）→ 退回 off 档")
                        runCatching { probe.stop() }
                    } else {
                        /* 字幕回调必须在 session.start() 之前挂好（图 §2-4） */
                        probe.onSubtitle = subCb@{ cStart, cEnd, text, track ->
                            /* 诊断（节流）：前 20 条逐条，之后每 50 条一行；轨号不匹配的单列 */
                            subRecv++
                            if (subRecv <= 20L || subRecv % 50L == 0L) {
                                Log.i(TAG, "字幕包到达#$subRecv 轨=$track 想要=$demuxSubTrackWanted 起点=${cStart}ms 长度=${text.length}")
                            } else if (track != demuxSubTrackWanted) {
                                Log.w(TAG, "字幕包轨号不匹配#$subRecv 来=$track 想要=$demuxSubTrackWanted")
                            }
                            if (track != demuxSubTrackWanted) return@subCb
                            ui.post {
                                if (gen != selfGen.get()) return@post
                                appendDemuxCue(cStart, cEnd, text, track)
                            }
                        }
                        probe.onRouteFailed = failCb@{ reason ->
                            Log.e(TAG, "全包解封装上报失败：$reason")
                            ui.post { if (gen == selfGen.get()) hud("全包解封装失败：$reason") }
                        }
                        probe.onSeekFailed = seekFail@{ reason ->
                            ui.post {
                                if (gen != selfGen.get()) return@post
                                seekOriginSeq++
                                Log.e(TAG, "全包档跳转失败：$reason")
                                hud("跳转失败：$reason")
                                audioPipe?.resumeAfterFailedSeek()
                            }
                        }
                        probe.onSeekOrigin = seekOrigin@{ targetMs, videoPtsUs, audioPtsUs ->
                            ui.post {
                                if (gen != selfGen.get()) return@post
                                seekOriginSeq++
                                /*
                                 * 新起点取"声音实际第一包"与"画面首包"里**更晚**的那个（三步法：定新起点再起播）。
                                 * 取画面首包会让声音晚到的那一段被补静音 → 跳转后声音一直落后 1.27 秒；
                                 * 取声音起点则让画面先把早于它的几帧当预滚丢掉，两边从同一点开跑。
                                 */
                                /*
                                 * 三步法（父亲 2026-11-21 定，2026-11-23 重申）：
                                 *   ① 时钟先到目标；② 在目标左侧找最近关键帧，拿到关键帧真实时刻后
                                 *   时钟拨到**关键帧时刻**；③ 画面从关键帧起解、早于目标的帧丢弃，
                                 *   声音也从关键帧时刻开始。
                                 * 所以共同的起点就是**关键帧时刻**，不是音频首包、也不补静音。
                                 */
                                val originSec = if (videoPtsUs > 0) videoPtsUs / 1_000_000.0 else targetMs / 1000.0
                                runCatching { playClock?.resetTo((originSec * 1_000_000.0).toLong()) }
                                /*
                                 * 回灌只改基准，**不再清解码器**（豆豆 2026-11-23）：
                                 * 清解码器已在 pipeSeek 那一刻做过一次；这里再清会把刚喂进去的关键帧冲掉，
                                 * 于是重新进入"等关键帧"，队列里剩下的多半是 P 帧 → 画面定住（快退定住即此因）。
                                 */
                                videoPipe?.setPrerollStart(originSec)
                                /* 声音从关键帧时刻开始：audioFirstUs 传 -1，避免又按"音频首包晚到"补静音 */
                                audioPipe?.onSessionSeek(originSec, originSec, -1L)
                                Log.i(
                                    TAG,
                                    "全包档落点：目标=${"%.3f".format(targetMs / 1000.0)}s " +
                                        "画面首包=${"%.3f".format(originSec)}s 音频首包=${if (audioPtsUs > 0) "%.3f".format(audioPtsUs / 1_000_000.0) else "未知"}",
                                )
                            }
                        }
                        session = probe
                        fullVideoFormat = vf
                        Log.i(
                            TAG,
                            "全包档就绪（${android.os.SystemClock.elapsedRealtime() - tOpen}ms）：" +
                                "${probe.videoWidth}x${probe.videoHeight} csd=${probe.videoCsd?.size ?: 0}B " +
                                "音频=${probe.audioCodecName}/${probe.sampleRate}Hz/${probe.channelCount}ch 字幕轨=${probe.subTrackCount}",
                        )
                    }
                }
            }
            val ok = pipe.start(
                url, emptyMap(), surface,
                { playClock?.positionSec() ?: startSec },
                { playClock?.isRunning() == true },
                audioStart, doviPath,
                holdGate = { selfPipeWaitingFirstFrame.get() },
                clockReal = { playClock?.positionSec() ?: startSec },
                /* 暂停只认我们自己的状态：内核已不参与，问它等于问一个不在场的人 */
                pausedNow = { selfPaused || avStall },
                onFirstFrame = frame@{
                    if (gen != selfGen.get()) return@frame
                    /*
                     * 首帧那一刻队列里已经有多少秒：用来判断"跳转慢"到底是不是放行门槛卡的
                     * （豆豆 2026-11-21：先有数再定门槛）。
                     */
                    Log.i(
                        TAG,
                        "首帧上屏|队列=${"%.1f".format(videoPipe?.queuedSec() ?: 0.0)}s" +
                            "|等待起播=${android.os.SystemClock.elapsedRealtime() - watchdogT0}ms",
                    )
                    selfFirstShown = true
                    /*
                     * 自管路的首帧上屏 = 收黑幕 / 转圈 / 片名的时机（父亲 2026-11-21 提的"黑屏没收掉"）。
                     *
                     * 收幕那条通道原来只认播放器首帧（ExoPlayer / mpv 的回调），自管路没有那个回调，
                     * 于是画面其实早就上屏了，黑幕却一直挂着，靠 20 秒兜底才收 —— 看起来就是"十几秒后才出画"。
                     */
                    /*
                     * 直接走收幕，不再经过 tryRevealWaitingFrame 的那组闸门。
                     * 自管路没有播放器首帧回调，那组闸门会把它挡回去（实测：一行"收黑幕"日志都没有）。
                     * 最短停留（父亲要的两秒）在这里单独保留。
                     */
                    ui.post { runCatching { tryRevealWaitingFrame() } }
                    /*
                     * 首帧**真正上屏**这一刻才允许放声音（父亲 2026-11-21 追问"为什么一直声音先出"）。
                     *
                     * 之前放行挂在"画面首帧解出来"上：解出来到显示还要一两帧渲染延迟，
                     * 而声音是立刻出声，所以永远快半拍。改成这里触发，才是真正的同时。
                     */
                    Log.i(TAG, "首帧上屏（自管路）→ 允许放行")
                    ui.post { tryRelease(0) }
                },
                /*
                 * 画面断流 → 声音与时钟一起停（父亲 2026-11-21 定：画面一停，声音必须跟着停）。
                 *
                 * 断流来自解码线程的读流监视（约两秒一拍）：两拍之间样本数不涨就算断。
                 * 恢复时只有"用户没有自己按暂停"才放开，否则会把用户的暂停顶掉。
                 */
                onStall = stall@{ stalled ->
                    if (gen != selfGen.get()) return@stall
                    /*
                     * 只在**已经放行之后**才让断流信号去动声音。
                     *
                     * 起播阶段读流监视也会报"断流/恢复"（第一次读数据前必然报一次），
                     * 恢复那一支会去 resume 音频 —— 那一刻还没放行，声音就提前出来了，
                     * 现场正是"声音先出、画面还在转圈"（2026-11-21 00:52 实测）。
                     */
                    if (selfAudioOn && selfPipeReleased) {
                        if (stalled) {
                            Log.w(TAG, "画面断流 → 声音与时钟一起停")
                            runCatching { audioPipe?.pause() }
                        } else if (!selfPaused) {
                            Log.i(TAG, "画面恢复 → 声音与时钟一起走")
                            runCatching { audioPipe?.resume() }
                        }
                    }
                },
                /*
                 * 画面首帧的真实时间到手 → 起音频（原型第 1 条）。
                 * 顺序不能反：先探测画面首帧再开音频，首帧出现之前的音频包才有地方放；
                 * 反过来做会把那半秒当成"早于起点"裁掉，声音开头少一截且补不回来。
                 */
                onOriginSec = origin@{ originSec ->
                    if (gen != selfGen.get()) {
                        Log.w(TAG, "丢弃迟到的画面起点上报（gen=$gen 当前=${selfGen.get()}）")
                        return@origin
                    }
                    selfOriginSec = originSec
                    Thread {
                        val clk = playClock
                        if (clk == null) return@Thread
                        /*
                         * 同一时刻只许有一个发声设备（2026-11-21 父亲实测「两个声音同时响」）。
                         * 日志：04:33:32.467 与 .606 两次「音频起播」，起点还不一样 —— 旧实例没停就又建了一个。
                         */
                        audioPipe?.let { old ->
                            /* 旧实例没退干净就不再建新的：第三路必被网盘拒（豆豆 2026-11-21 第二遍审出） */
                            if (!old.stop()) {
                                Log.e(TAG, "旧音频未退出，放弃这次音频起播")
                                return@Thread
                            }
                        }
                        audioPipe = null
                        /*
                         * 起音频这一刻把 Session 抓到局部：字段若正好被停播清掉，
                         * 传 null 会让 AudioPipe 退回 off 档、当场开第三条连接（豆豆 2026-11-22 P1-5）。
                         */
                        val sessAudio = selfSession
                        if (wantFullDemux && sessAudio == null) {
                            Log.e(TAG, "全包档 Session 已收场 → 不起音频（避免退回 off 开第三条连接）")
                            return@Thread
                        }
                        val ap = com.xxxx.emby_vr.player.AudioPipe()
                        /* 解封装随播放交付字幕：只收用户选中的那条轨 */
                        ap.onFeedEnd = feedEnd@{
                            /* 实例守卫：旧管线的回调不许打到新周期（豆豆 2026-11-22） */
                            if (audioPipe !== ap) return@feedEnd
                            ui.post {
                                if (audioPipe !== ap) return@post
                                audioEos = true
                                Log.i(TAG, "TRACK_DONE|audio|pts=${"%.3f".format(playClock?.positionUs()?.div(1_000_000.0) ?: 0.0)}")
                                maybeFinishPlayback()
                            }
                        }
                        ap.onSubtitle = subCb@{ startMs, endMs, text, track ->
                            if (audioPipe !== ap) return@subCb
                            if (track == demuxSubTrackWanted) {
                                ui.post { appendDemuxCue(startMs, endMs, text) }
                            }
                        }
                        /* 声音这一路停写 → 画面一起停；恢复 → 一起走（用户自己按暂停的情况除外） */
                        ap.onStall = stallCb@{ stalledNow ->
                            if (audioPipe !== ap) return@stallCb
                            ui.post {
                                avStall = stalledNow
                                if (stalledNow) {
                                    Log.w(TAG, "声音停写 → 画面一起停")
                                } else if (!selfPaused) {
                                    Log.i(TAG, "声音恢复 → 画面一起走")
                                }
                            }
                        }
                        /*
                         * 音频的起点必须用**画面第一帧的真实时间**，不能用用户拖到的目标位置。
                         *
                         * 实测（2026-11-21 05:05）：左跳到 333.39 秒，画面落到关键帧后的第一帧是 336.40，
                         * 而音频仍从 333.39 起——声音比画面早 3 秒，且此后一直早着。
                         */
                        val started = ap.start(
                            url, emptyMap(), startSec = originSec, originSec = originSec,
                            clock = clk, autoPlay = false,
                            memWindowSec = pipeMemWindowSec(),
                            /* 调参可覆盖：audio_decoder=system|ffmpeg；默认 auto（系统解不了的走自带库） */
                            decoderPref = com.xxxx.emby_vr.vr.VrTuning.value(this, "audio_decoder") ?: "auto",
                            /* 排查开关：demux_discard=0 时不丢弃非本路轨（默认 1）；验证通过后连开关一起删 */
                            demuxDiscard = com.xxxx.emby_vr.vr.VrTuning.value(this, "demux_discard")
                                ?.trim()?.toIntOrNull() ?: 1,
                            /* 默认走系统解封装读包 + 自带解码（avformat 路在本机读不到完整响应） */
                            demuxOff = com.xxxx.emby_vr.vr.VrTuning.value(this, "demux_off")
                                ?.trim()?.toIntOrNull() ?: 1,
                            /* 全包档：音频也从同一条连接的队列取，格式由 Session 给 */
                            demuxSession = sessAudio,
                            formatOverride = sessAudio?.audioFormat(),
                        )
                        Log.i(
                            TAG,
                            "音频自管起播：画面首帧=${"%.3f".format(originSec)}s 结果=$started 轨=${ap.trackDesc}" +
                                (ap.lastError?.let { " 错误=$it" } ?: ""),
                        )
                        ui.post {
                            /*
                             * 只有**真的换代**（更新的一代已经在跑）才收掉这个实例。
                             *
                             * 原来还带 released 条件，结果：看门狗强制放行 → 音频刚起动 →
                             * 就绪回调看到 released=true → 把它自己收掉 → 静音
                             * （2026-11-21 17:22 实测：音频起播后 10ms 线程退出，已写 0 帧）。
                             */
                            if (started && gen != selfGen.get()) {
                                Log.w(TAG, "音频就绪但已换代，收掉这个实例")
                                runCatching { ap.stop() }
                                return@post
                            }
                            if (started) {
                                audioPipe = ap
                                /* 解码器与设备都备好但没动；放行那一刻才起动（见 tryRelease） */
                                Log.i(TAG, "音频已备好（未起动）：${ap.trackDesc} 位置中枢=${ap.positionUs() / 1000}ms")
                                tryRelease(0)
                            } else {
                                /*
                                 * 音轨解不了（DTS/TrueHD 这类）：这一部只能没有声音。
                                 * 时钟仍要起来（按墙钟走），画面照常播，并在屏上说明原因。
                                 */
                                playClock?.start((originSec * 1_000_000.0).toLong())
                                selfPipeWaitingFirstFrame.set(false)
                                ui.post { hud("本片音轨无法自管（" + (ap.lastError ?: "未知") + "）") }
                                Log.w(TAG, "音频自管起不来：${ap.lastError}")
                            }
                        }
                    }.start()
                },
                /* 起点由画面首帧定：offset 恒为 0，帧 pts 直接与音频时钟比 */
                originFromPicture = true,
                memWindowSec = pipeMemWindowSec(),
                onNeedRestart = restart@{ target ->
                    if (selfSession != null) {
                        /* 全包档：失步只让 Session 重新定位，绝不再建一条连接 */
                        selfSession?.requestSeek((target * 1000.0).toLong())
                        return@restart
                    }
                    /* 回调来自解码线程：重建必须投主控线程，否则 start() 第一行会 join 自己 */
                    ui.post {
                        val dead = runCatching { videoPipe?.stop() ?: true }.getOrDefault(false)
                        videoPipe = null
                        if (!dead) {
                            Log.e(TAG, "失步重启失败：zombie=true（放弃重启，避免两路抢画面）")
                            ui.post { hud("画面卡住（需重启应用）") }
                            return@post
                        }
                        restoreVideoPipeAt(target)
                    }
                },
                onDoviParams = {
                    /*
                     * 两件事都要做，缺一不可（2026-11-21 查实）：
                     * ① 标记数据流是杜比 P5；② 打开渲染层还原总开关。
                     * 之前只做了 ①，总开关一直是 0，于是每帧直接吐原始颜色（日志：「开关=0 ... 生效=0」），
                     * 屏幕上是没做还原的原始画面 —— 这就是偏色的直接原因。
                     */
                    com.xxxx.emby_vr.vr.VrNative.setDoviStreamActive(true)
                    com.xxxx.emby_vr.vr.VrNative.setDvRendererReshape(true)
                    Log.i(TAG, "确认是杜比 P5：数据流与渲染层还原总开关都已打开")
                },
                onEos = {
                    if (videoPipe !== pipe) return@start
                    /*
                     * 假性 EOS 防护：网络抖一下 readSampleData 也会返回 -1。
                     * 只有"当前位置距总时长 5 秒以内"才算真结束，否则只重试、不宣布结束（豆豆 2026-11-22）。
                     */
                    val dur = pipe.durationSec
                    val pos = (playClock?.positionUs() ?: 0L) / 1_000_000.0
                    if (dur <= 1.0) {
                        videoEosCount++
                        if (videoEosCount < 2) {
                            Log.w(TAG, "画面报 EOS 但拿不到时长（第 $videoEosCount 次）→ 再等一次")
                            return@start
                        }
                    }
                    if (dur > 1.0 && pos < dur - 5.0) {
                        Log.w(TAG, "画面报 EOS 但位置 ${"%.1f".format(pos)}s 距总长 ${"%.1f".format(dur)}s 还有距离 → 不判结束")
                        return@start
                    }
                    /* 只标记自己结束；声音可能还有尾巴，不许把它一起按停 */
                    Log.i(TAG, "自管解码：画面到结尾（等声音也结束再收）")
                    videoEos = true
                    Log.i(TAG, "TRACK_DONE|video|pts=${"%.3f".format(playClock?.positionUs()?.div(1_000_000.0) ?: 0.0)}")
                    maybeFinishPlayback()
                },
                /* 出一帧就上屏（父亲 2026-11-20 定）：三帧预热换来的是起播多等一拍、声音先出 */
                preheatFrames = 1,
                onPreheatReady = { spanMs, rpuReady ->
                    ui.post {
                        Log.i(TAG, "READY 首帧=${spanMs}ms RPU=${if (rpuReady) "就绪" else "未就绪"}")
                        pipeReady = true
                        selfPipeReady = true
                        selfPipePhase = "等首帧上屏"
                        /* 放行不在这里做：等 onFirstFrame（真正上屏）再放声音 */
                    }
                },
                /* 全包档：把 Session 与画面格式交进去，本类不再建读线程/解封装 */
                demuxSession = session,
                formatOverride = fullVideoFormat,
                /*
                 * 取证开关（调参 video_probe=1）：把前 60 个视频包转 Annex-B 落到应用目录，
                 * 拉回 .15 用 `ffmpeg -f hevc -i 文件 -frames:v 3 -f null -` 试解，
                 * 一次切开"数据不对"还是"设备解码器不对"（豆豆 2026-11-22）。
                 */
                probeDumpPath = if (session != null && (com.xxxx.emby_vr.vr.VrTuning.value(this, "video_probe")
                        ?.trim()?.toIntOrNull() ?: 0) == 1) {
                    java.io.File(getExternalFilesDir(null) ?: filesDir, "pkt_dump.hevc").absolutePath
                } else {
                    null
                },
            )
            if (ok && session != null) {
                /*
                 * 先后顺序有讲究：**先发引用、再起读线程**。
                 * 反过来的话，读线程已开跑而字段还是 null，主线程这一瞬间按快进会走"整条重建"，
                 * 又开一条连接（豆豆 2026-11-22 P1-A）。跳转不会丢：requestSeek 只是写标志，
                 * 读循环第一轮就会取走。
                 */
                selfSession = session
                session.start()
                Log.i(TAG, "全包档已启动：画面/声音/字幕同一条连接")
            } else if (session != null) {
                runCatching { session.stop() }
            }
            if (!ok) {
                /*
                 * 立刻读：lastError 是静态字段，下一次 start 一开头就会清空它，
                 * 所以这里晚一点读就只剩 null（2026-11-21 17:22 实测：
                 * 跳转中止报的"PIPE 起不来：null"，真因被下一次重启擦掉了）。
                 */
                val why = com.xxxx.emby_vr.player.VideoPipe.lastError
                Log.e(TAG, "PIPE 起不来：$why（停在起播，不放行、不出声）")
                ui.post { hud("画面解码起不来") }
                pipeBootInFlight.set(false)
                /* 这条管线必须停掉：start 返回 false 不代表它已经退出 */
                runCatching { pipe.stop() }
                selfPipeWaitingFirstFrame.set(false)
                selfPipePhase = "起播失败"
                abortJump("画面解码起不来：$why")
            }
            } catch (t: Throwable) {
                Log.e(TAG, "起播线程异常：${t.javaClass.simpleName}: ${t.message}", t)
                ui.post { hud("起播线程异常：${t.javaClass.simpleName}") }
                /*
                 * 两处必须带代次/身份守卫（豆豆 2026-11-22）：
                 * 老一代线程抛异常时，新一代可能已经认领单飞并建好会话，
                 * 无条件清标志 = 拆掉它的防线（两路并发起播），无条件清会话 = 抹掉它的连接引用（泄漏）。
                 */
                if (gen == selfGen.get()) {
                    pipeBootInFlight.set(false)
                    selfPipeWaitingFirstFrame.set(false)
                    selfPipePhase = "起播失败"
                }
                /* 作废 25 秒放行看门狗：它只看代次，不清理会让用户连收两条"起播失败"提示 */
                selfTx.incrementAndGet()
                /* 异常可能发生在 pipe.start 之前（pipe 还没进全包档、不会替我们 abort），先叫停读线程 */
                runCatching { session?.abort() }
                runCatching { pipe.stop() }
                runCatching { session?.stop() }
                if (selfSession === session) selfSession = null
            }
        }

        pipelineLauncher = { it -> launchPipelineBlocking(it) }
        launchPipeline(startSec)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 睡眠挂起：记位置、冻时钟、停声音，**不调解码器**（重建太贵）。
     * 画面靠 holdGate 冻住最后一帧。
     */
    private fun suspendPlaybackForSleep() {
        if (!selfAudioOn) return
        val sec = playClock?.positionSec() ?: return
        sleepSuspendSec = sec
        sleepSuspendAtMs = android.os.SystemClock.elapsedRealtime()
        selfPipeWaitingFirstFrame.set(true)
        runCatching { playClock?.pause() }
        runCatching { audioPipe?.pause() }
        Log.i(TAG, "睡眠挂起|位置=${"%.2f".format(sec)}s|时刻=$sleepSuspendAtMs")
    }

    /**
     * 睡眠恢复（豆豆 2026-11-21 的阈值建议）：
     * 挂起 3 秒以内 → 原地继续；超过 3 秒 → 整条重建到挂起位置（复用跳转事务）。
     * 短挂起看起来正常是有欺骗性的 —— 音频线程还没被卡到"上报停走"，时钟没转墙钟。
     */
    private fun resumePlaybackFromSleep() {
        val sec = sleepSuspendSec
        if (sec < 0.0) return
        val suspendedMs = android.os.SystemClock.elapsedRealtime() - sleepSuspendAtMs
        sleepSuspendSec = -1.0
        if (suspendedMs < 3000L) {
            selfPipeWaitingFirstFrame.set(false)
            runCatching { playClock?.resume() }
            runCatching { audioPipe?.resume() }
            Log.i(TAG, "睡眠恢复|分支=原地|位置=${"%.2f".format(sec)}s|挂起=${suspendedMs}ms")
        } else {
            Log.i(TAG, "睡眠恢复|分支=整条重建|位置=${"%.2f".format(sec)}s|挂起=${suspendedMs}ms")
            selfPipeWaitingFirstFrame.set(false)
            /* 复用跳转事务（同一把单飞锁），不新写第三条重建路径。
               这里不在 ui 作用域里（onResume 是 Activity 方法），用主线程 handler 投递。 */
            /*
             * 重建分支里也要把"暂停态"解除：睡眠时 AudioPipe.pause() 把 paused 置真，
             * 只有短挂起那条分支会 resume；长挂起走重建时若不解，音频解码线程会一直
             * 停在"paused 就不写"上 → 唤醒后无声、时钟不走、进度与弹幕全冻（2026-11-23 真机）。
             */
            if (!selfPaused) {
                runCatching { audioPipe?.resume() }
                runCatching { playClock?.resume() }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { ctl.pipeSeek?.invoke((sec * 1000.0).toLong()) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumePlaybackFromSleep()
        glView.onResume()
        vrSession.resume()
        /*
         * 启动时还原海报墙摆放（父亲 2026-10-07：位置、大小、远近都要记住，
         * 下次打开 APP 回到上次那个样子）。以前只在「播放结束回海报墙」时还原，
         * 直接打开 APP 反而是原生默认摆位，看起来就像没记住。
         */
        if (!panelPlaceRestored) {
            panelPlaceRestored = true
            restorePanelPlace()
        }
    }

    override fun onPause() {
        /* 摘头显 = 睡眠：把播放挂起，别让时钟继续走（豆豆 2026-11-21 裁定 A 的第一步） */
        suspendPlaybackForSleep()
        // 海报墙的摆放先记下来，下次打开还原
        savePanelPlace()
        vrSession.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopPlaybackInternal()
        if (::panel.isInitialized) panel.release()
        renderer.releaseVideoPipeline()
        scope.cancel()
        vrSession.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        /*
         * 父亲 2026-10-07：光点在播放画面上时 B 键不响应 —— 播放页的操作全在控制条上。
         * 必须消费掉（return true），否则系统会拿这一下 BACK 当"关闭 Activity"，
         * 应用直接被退出。
         *
         * 光点在海报墙上时，往下走面板导航栈（下面那段），与是否正在播放无关。
         */
        /*
         * 银幕上放网页时 B 键不响应（父亲 2026-10-13 定）：退出网页这件事取消了 ——
         * 想换内容就在海报墙点别的片子，网页会被那部片子顶掉。
         * 光柱指海报墙时照旧走下面的面板导航栈（详情页按 B 回上一页）。
         */
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            /* 观测（2026-10-17）：系统返回键那条路的现场，与光柱那条对齐 */
            Log.i(
                TAG,
                "返回键现场：网页在屏=$webOnScreen 被播放器顶掉=$webHiddenByStream " +
                    "光标在面板=$panelPointerOnPanel 网页页=${webSiteState.value}",
            )
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && webOnScreen && !panelPointerOnPanel) {
            /*
             * 先让网页自己返回（父亲 2026-10-14 实测报障：B站 网页里按 B 键完全没反应）。
             *
             * WebPageHub.back() 早就定义好了，但**没有任何地方调用它** ——
             * 网页页内部的返回因此从来没生效过。这里接上：网页有上一页就退回去，
             * 已经在首页就保持原样（不退出网页页，按 2026-10-13 定的语义）。
             */
            val wentBack = com.xxxx.emby_vr.web.WebPageHub.back()
            Log.i(TAG, if (wentBack) "BACK 交给网页：退回上一页" else "BACK 忽略：网页已无上一页")
            return true
        }
        /*
         * 从我方播放器回网页（父亲 2026-10-16）：接管时网页没销毁、地址也没动，
         * 所以直接把大银幕交回网页即可，不需要任何导航 —— 落点就是起播前那一页。
         */
        /*
         * 光标在面板上时，返回键一律归面板（父亲 2026-10-16 定的总语义：
         * 光标指着谁才管谁）—— 这样"对着海报墙按 B"绝不会把大银幕顶回网页。
         */
        if (keyCode == KeyEvent.KEYCODE_BACK && panelPointerOnPanel) {
            if (::panel.isInitialized && panel.ready &&
                com.xxxx.emby_vr.vr.VrNative.panelActive
            ) {
                Log.i(TAG, "BACK → 面板返回（光标在面板上）")
                panel.back()
                return true
            }
        }
        /*
         * 系统返回键这条同理：只有"此刻在放网页流、且它被我们顶掉了"才回网页
         * （2026-10-17：播 Emby 片子时这条会把人送回 B站 网页）。
         */
        if (keyCode == KeyEvent.KEYCODE_BACK && webHiddenByStream && webStreamSite != null) {
            returnFromStreamToWeb()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && renderer.videoActive) {
            Log.i(TAG, "BACK 忽略：光标对着播放画面（播放页操作走控制条）")
            return true
        }
        // 返回键再给面板：复用界面的导航栈（NavHost）在面板窗口里，
        // 不进面板就等于「返回」失效（实测 PICO 面板模式基本不发按键，
        // 这里做兜底，主通道是扳机手势，见 PanelLayer.dispatch）
        if (keyCode == KeyEvent.KEYCODE_BACK && ::panel.isInitialized &&
            panel.ready && com.xxxx.emby_vr.vr.VrNative.panelActive
        ) {
            // 走面板自己的导航栈（2026-10-04：发 BACK 键会让面板窗把自己关掉=黑屏）
            panel.back()
            return true
        }
        // 手柄/遥控按键先给 InputRouter；它不认的（如音量键）再交给系统
        if (input.onKeyDown(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /**
     * [诊断] 最外层事件总入口：PICO 的手柄输入有可能走 key/motion 之外的路径，
     * 这里记录所有到达 Activity 的事件（含被 super 消费的），
     * 用于对比「B 站能收到、我们漏掉」的是什么（父亲 2026-10-04）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "DIAG keyEv: action=${event.action} keyCode=${event.keyCode} " +
            "scanCode=${event.scanCode} deviceId=${event.deviceId} repeat=${event.repeatCount} " +
            "source=0x${Integer.toHexString(event.source)}")
        return super.dispatchKeyEvent(event)
    }

    /**
     * 触摸事件总入口（2026-10-04 关键修复）。
     *
     * 实测对比：B 站播放视频时抓 `/dev/input/event4`，拨摇杆产生的是
     *   BTN_TOUCH DOWN → ABS_X 横向移动 800+ 像素 → BTN_TOUCH UP
     * 即「一次完整拖动」，B 站据此识别摇杆操作；
     * 而我们的应用日志里 1099 条指针事件**全是 MOVE，一条 DOWN 都没有**
     * —— GLSurfaceView 的触摸分发把 DOWN/UP 吃掉了，所以我们既识别不出
     * 摇杆拖动，也拿不到"按下/抬起"这个配对。
     *
     * 因此在 Activity 最外层拦下所有触摸事件，交给 InputRouter 统一处理。
     */
    // ---- 原始触摸流取证（父亲 2026-10-05）----
    //
    // 争议点：摇杆松手时到底有没有「抬起」事件。
    // 结论要能分辨两种可能 ——「系统没送」vs「送了被我们的判断吃掉」，
    // 所以这一层打在**最外层**（任何分支之前）：只要是系统送进 Activity 的
    // DOWN/MOVE/UP 都会留下一条，之后再对比 PanelLayer 的「按压画像」日志，
    // 就能确定是哪一种。
    private var rawMoveCount = 0
    private var rawLastTraceAt = 0L

    private fun traceRawTouch(event: MotionEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        val xy = "(${event.x.toInt()},${event.y.toInt()})"
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawMoveCount = 0
                rawLastTraceAt = now
                Log.i(TAG, "触摸流: DOWN $xy source=0x${Integer.toHexString(event.source)}")
            }
            MotionEvent.ACTION_UP -> {
                Log.i(TAG, "触摸流: UP $xy 本窗口MOVE=$rawMoveCount source=0x${Integer.toHexString(event.source)}")
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "触摸流: CANCEL $xy 本窗口MOVE=$rawMoveCount")
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                rawMoveCount++
                // 限流：最多每 100ms 一条，避免刷屏掩盖 UP
                if (now - rawLastTraceAt >= 100L) {
                    rawLastTraceAt = now
                    val kind = if (event.actionMasked == MotionEvent.ACTION_MOVE) "MOVE" else "HOVER"
                    Log.i(TAG, "触摸流: $kind#$rawMoveCount $xy 按下键=0x${Integer.toHexString(event.buttonState)}")
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceRawTouch(event)
        /*
         * 面板层优先（2026-10-04）：光标落在虚拟屏范围内时，事件换算成面板像素
         * 后派发进复用的界面（同进程 dispatchTouchEvent，不需要 INJECT_EVENTS）。
         *
         * 换算与渲染共用同一套布局常量（renderer.panelPixelAt），
         * 保证「看到的位置」和「点到的位置」一致。
         */
        /*
         * 播放中不喂面板（父亲 2026-10-04）：
         * 播放时画面是视频，摇杆要用来快进快退（走摇杆通道 / InputRouter 的方向动作），
         * 若仍把事件派进面板，播放中摇杆就失效、还会在隐藏的界面上乱移焦点。
         */
        if (!vrInputLive &&
            !renderer.videoActive &&
            ::panel.isInitialized && panel.ready && com.xxxx.emby_vr.vr.VrNative.panelActive &&
            ::glView.isInitialized && glView.width > 0 && glView.height > 0
        ) {
            val aspect = glView.width.toFloat() / glView.height.toFloat().coerceAtLeast(1f)
            val nx = (event.x / glView.width * 2f - 1f) * aspect
            val ny = 1f - event.y / glView.height * 2f
            val pix = renderer.panelPixelAt(nx, ny)
            val action = event.actionMasked
            /*
             * 抬起/取消**即使落点在面板外也必须转发**（父亲 2026-10-05 根因）：
             * 摇杆一次拨动的落点经常滑出面板（实测抬起坐标 y=-131），
             * 而 panelPixelAt 对面板外返回 null —— 原来这条 UP 就被丢掉了，
             * 面板一直以为还按着，于是"松手还在滚"。落点用最后一次面板坐标收尾。
             * ACTION_CANCEL 原来也整个被过滤掉，一并补上（系统常以 CANCEL 结束拨动）。
             */
            val isRelease = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            if (pix != null || (isRelease && panel.pressActive)) {
                val px = pix?.get(0) ?: panel.lastPanelX
                val py = pix?.get(1) ?: panel.lastPanelY
                if (action == MotionEvent.ACTION_DOWN ||
                    action == MotionEvent.ACTION_MOVE ||
                    isRelease
                ) {
                    /*
                     * 面板输入统一走 PanelLayer.dispatch：它把「扳机 + 光标」
                     * 翻译成电视版界面认识的按键（轻扣=OK、扣住不动=返回、
                     * 扣住拖动=方向键），详见 PanelLayer.dispatch 的说明。
                     */
                    panel.dispatch(px, py, action)
                    if (action == MotionEvent.ACTION_DOWN) {
                        Log.i(
                            TAG,
                            "面板光标: (${event.x.toInt()},${event.y.toInt()}) → " +
                                "面板像素 (${px.toInt()},${py.toInt()})",
                        )
                    } else if (isRelease && pix == null) {
                        Log.i(TAG, "面板外的抬起也转发: action=$action 收尾于 (${px.toInt()},${py.toInt()})")
                    }
                    return true
                }
            }
        }

        if (::glView.isInitialized) {
            val handled = input.onTouchEvent(
                event = event,
                viewW = glView.width,
                viewH = glView.height,
                onPointer = { },
                onConfirm = { },
                onDrag = { },
                onDown = { },
            )
            if (handled) return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // [诊断] 摇杆轴事件是否到达：记录 source 与轴值（父亲 2026-10-04 抓数据用）
        Log.i(TAG, "DIAG generic: action=${event.actionMasked} source=0x${Integer.toHexString(event.source)} " +
            "AXIS_X=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_X))} " +
            "AXIS_Y=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_Y))} " +
            "AXIS_Z=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_Z))} " +
            "AXIS_RZ=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_RZ))} " +
            "AXIS_HAT_X=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_HAT_X))} " +
            "AXIS_HAT_Y=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_HAT_Y))} " +
            "AXIS_SCROLL=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_SCROLL))} " +
            "AXIS_VSCROLL=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_VSCROLL))} " +
            "AXIS_HSCROLL=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_HSCROLL))} " +
            "AXIS_RX=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_RX))} " +
            "AXIS_RY=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_RY))} " +
            "AXIS_LTRIGGER=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_LTRIGGER))} " +
            "AXIS_THROTTLE=${"%.3f".format(event.getAxisValue(MotionEvent.AXIS_THROTTLE))}")
        if (input.onGenericMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    companion object {
        const val TAG = "B0BEmbyVR"

        /**
         * 攒分片模式取腾讯分片时用的 UA（2026-10-18）。
         *
         * 分片地址是"一段一签"的，CDN 认 Referer 为主；UA 用桌面浏览器这一串，
         * 与网页里那台浏览器一致，避免被判成异常客户端。
         */
        private const val WEB_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 弹幕时钟纠偏参数（父亲 2026-10-13 凌晨定）。
         *
         * 采样每 250ms 一次：落后时每拍最多追 [kDanmakuSlewMs]（≈100ms/s，眼睛看不出来），
         * 差值超过 [kDanmakuRealignMs] 判定为换集/seek/起播，直接对齐。
         * 超前一律原地等，绝不后退（后退就是最扎眼的抖动）。
         */
        private const val kDanmakuSlewMs = 25.0
        private const val kDanmakuRealignMs = 800.0
    }
}

/** 一条字幕：起止时间（毫秒）+ 文本。客户端自绘字幕用（父亲 2026-10-07） */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 带样式的行（字号倍数 / 颜色）；纯文本 SRT 时为空，退回 text 那条老路 */
    val lines: List<com.xxxx.emby_vr.danmaku.SubtitleLine> = emptyList(),
    /** 来自文件内第几条文字字幕（0 起）。切轨时按它过滤，避免显示上一条轨的条目 */
    val track: Int = 0,
)

/** 解析中间产物：一段字幕的原始行（还带着 HTML 风格样式标签） */
private data class RawCue(val startMs: Long, val endMs: Long, val lines: MutableList<String>)

/** 一行原始文本解析出来的三样东西 */
private data class StyledRaw(val text: String, val size: Int?, val color: Int?)
