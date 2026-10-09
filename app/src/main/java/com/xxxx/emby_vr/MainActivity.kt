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
    private var danmakuCanvasH = 1440
    private var danmakuView: com.xxxx.emby_vr.danmaku.DanmakuView? = null

    /** 当前这一集解析好的弹幕（菜单里把弹幕关掉时先留着，开回来直接用） */
    private var danmakuTrack: com.xxxx.emby_vr.danmaku.DanmakuTrack? = null

    /** 片名 logo 层（银幕左上角那块小透明面板） */
    private lateinit var logo: PanelLayer
    private val logoUrl = androidx.compose.runtime.mutableStateOf<String?>(null)

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
    private var audioStreamIndices: List<Int> = emptyList()
    private var subtitleStreamIndices: List<Int> = emptyList()
    private var episodeIds: List<String> = emptyList()

    /** 每集上次看到的位置（tick）：选集换片也从那儿接着播（父亲 2026-10-06 晚定） */
    private var episodePositions: List<Long> = emptyList()

    /** 字幕菜单第一行是不是「弹幕」开关（有弹幕轨才有，决定点击下标要不要左移一位） */
    private var subtitleDanmakuRow = false

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

    /** 位置刷新定时器（独立于控制条：控制条收起时也要继续跑） */
    private val posTicker = object : Runnable {
        override fun run() {
            refreshPosBase()
            updateDrawnSubtitle()
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

    /** 主线程调：刷新播放位置基准（每 250ms 一次） */
    private fun refreshPosBase() {
        posBaseMs = currentPositionMs()
        posBaseAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** 任何线程可调：按基准推算当前播放位置 */
    private fun playbackPosEstimate(): Long {
        val base = posBaseAtMs
        if (base == 0L) return 0L
        val delta = android.os.SystemClock.elapsedRealtime() - base
        return posBaseMs + delta.coerceAtLeast(0L)
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

        override fun onPanelFocus(onPanel: Boolean) {
            // 原生只在变化时推：光柱是否落在海报墙上
            runOnUiThread { panelPointerOnPanel = onPanel }
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
                if (panelInputReady()) {
                    Log.i(TAG, "光柱点击 (${px.toInt()}, ${py.toInt()}) → 交给面板")
                    panel.vrClick(px, py)
                } else if (!renderer.videoActive) {
                    Log.w(TAG, "光柱点击被丢弃：面板未就绪（已激活=${com.xxxx.emby_vr.vr.VrNative.panelActive}）")
                }
            }
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
            runOnUiThread { if (osdReady()) osd.vrPointerPressed(px, py, pressed) }
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

        override fun onMenuPointer(px: Float, py: Float) {
            vrInputLive = true
            lastMenuPointerAt = android.os.SystemClock.uptimeMillis()
            runOnUiThread { if (menuReady()) menu.vrPointer(px, py) }
        }

        override fun onMenuClick(px: Float, py: Float) {
            vrInputLive = true
            runOnUiThread {
                if (menuReady()) {
                    Log.i(TAG, "菜单点击 (${px.toInt()}, ${py.toInt()})")
                    menu.vrClick(px, py)
                }
            }
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

        override fun onBack() {
            vrInputLive = true
            runOnUiThread {
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
                } else if (renderer.videoActive && !panelPointerOnPanel) {
                    Log.i(TAG, "光柱 B 键忽略：光点在播放画面上（播放页操作走控制条）")
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
        val size = if (v?.width != null && v.height != null) "${v.width}×${v.height}" else null
        return listOfNotNull(
            v?.displayTitle?.takeIf { it.isNotBlank() },
            size,
            a?.displayTitle?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    /**
     * 启动弹幕画笔：把原生给的那张纹理包成画布，逐帧画弹幕。
     *
     * 尺寸用 2560×1440（16:9，够弹幕文字清晰），与原生弹幕层的缓冲尺寸一致。
     */
    /**
     * 弹幕画布比例跟影片走（父亲 2026-10-07 定的第二条方案）。
     *
     * 宽度钉死 2560，高度 = 2560 ÷ 画面比例：画布比例与银幕完全一致，
     * 弹幕、片名 logo、字幕、快进快退进度条都不再被拉伸，也不会跑到画面外。
     */
    private fun applyDanmakuCanvas(aspect: Float) {
        if (aspect <= 0.2f || aspect > 6f) return
        val w = 2560
        val h = Math.round(w / aspect).coerceIn(600, 3200)
        if (h == danmakuCanvasH) return
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
        val sourceId = (pendingMediaSourceId ?: reportedMediaSourceId)
            ?.takeIf { it.endsWith(mediaId) }
            ?: "mediasource_$mediaId"
        if (mediaId.isBlank()) return
        subtitleCueStream = index
        val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
            "/Stream.srt?api_key=${userToken()}"
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
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
            // 中途换片 / 换字幕轨 → 结果丢掉
            if (mediaId != currentMediaId || subtitleCueStream != index) return@launch
            val cues = parseSrt(raw)
            subtitleCues = cues
            Log.i(TAG, "自绘字幕：流 $index 取到 ${cues.size} 条")
            /*
             * 取不到就**清屏**（2026-10-09 父亲要求）。
             *
             * 原来失败了什么都不做，屏幕上继续显示上一条字幕的残留 ——
             * 切到别的语言时用户看到的就是"上一次的内容"，误以为是乱字符。
             * 现在明确清空：宁可不显示，也不显示错的。
             */
            if (cues.isEmpty()) {
                Log.w(TAG, "自绘字幕：流 $index 没取到内容（${raw?.length ?: 0} 字节）→ 清屏")
                subtitleNow = ""
                if (!waitingFirstFrame) danmakuView?.setSubtitle("")
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
        // 内核（Profile 5）这条路的字幕由 mpvSubtitleJob 从内嵌轨道取文，别两边抢着写
        if (mpvBackend != null) return
        if (subtitleCues.isEmpty()) return
        val pos = currentPositionMs()
        val cue = subtitleCues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
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
                val painter = com.xxxx.emby_vr.danmaku.DanmakuSurfacePainter(
                    context = this,
                    surfaceTexture = st,
                    widthPx = 2560,
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
                Log.i(TAG, "弹幕画笔已启动（2560x$danmakuCanvasH，开关=${if (danmakuOn) "开" else "关"}）")
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
    private fun itemTitleOf(item: com.xxxx.emby_vr.data.model.BaseItemDto?): String {
        val series = item?.seriesName
        val ep = item?.indexNumber
        val name = item?.name
        return when {
            !series.isNullOrBlank() && ep != null ->
                if (!name.isNullOrBlank() && name != series) "$series 第${ep}集 $name"
                else "$series 第${ep}集"
            !series.isNullOrBlank() -> series
            else -> name ?: ""
        }
    }

    private fun osdTitleText(prefix: String = "正在播放："): String =
        prefix + itemTitleOf(currentItem)

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
        placePrefs.edit().putString("panel_place", v.joinToString(",")).apply()
        Log.i(TAG, "海报墙摆放已记住")
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
        val v = danmakuView ?: return
        seekHudShown = true
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
    }

    /** 控制条显隐（VR 侧画不画那块面板） */
    private fun setOsdVisible(visible: Boolean) {
        osdVisible = visible
        com.xxxx.emby_vr.vr.VrNative.setOsdVisible(visible)
        // 控制条收起来时菜单一起收（父亲 2026-10-06：菜单挂在控制条上）
        if (!visible) closeMenu()
        Log.i(TAG, if (visible) "控制条显示" else "控制条隐藏")
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
                osdState.title = osdTitleText()
                // 弹幕画布要的播放位置：主线程取，画笔线程只读缓存
                refreshPosBase()
                kotlinx.coroutines.delay(1000)
            }
            osdState.playing = false
        }
    }

    /** 面板此刻能不能接输入：界面就绪、没在播放 */
    /**
     * 面板能不能接光柱输入。
     *
     * 2026-10-06 父亲要求：播放期间海报墙照样能操作（点、滚、拖、B 返回）。
     * 所以不再因为"在播放"就整块关掉 —— 改由光柱位置决定：光柱指在海报墙上
     * （原生回推 onPanelFocus）时就接，指别处时不接。
     */
    private fun panelInputReady(): Boolean =
        ::panel.isInitialized && panel.ready &&
            com.xxxx.emby_vr.vr.VrNative.panelActive &&
            (!renderer.videoActive || panelPointerOnPanel)

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
                if (::panel.isInitialized && panel.ready &&
                    com.xxxx.emby_vr.vr.VrNative.panelActive) panel.back()
            }
            InputRouter.Action.PLAY_PAUSE ->
                if (renderer.videoActive && !panelTouchedRecently()) togglePlayPause()
            InputRouter.Action.SEEK_BACK -> if (renderer.videoActive) seekBy(-10_000)
            InputRouter.Action.SEEK_FORWARD -> if (renderer.videoActive) seekBy(+10_000)
            else -> { /* 余下动作后续接 */ }
        }
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
         * 父亲 2026-10-06：播放中点海报墙的片子起不来、反而把正在播的暂停了。
         * 原因是这里原来有一句"正在播就切播放/暂停"的老逻辑 —— 那是给面板上的
         * 播放键用的，不该拦起播。现在点谁就播谁，旧片由 startPlayer 里的
         * stopPlaybackInternal 收掉。
         */
        scope.launch {
            try {
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
                    disableHevc = forceH264,
                )
                var source = media.mediaSources?.firstOrNull()
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
                            disableHevc = forceH264,
                        )
                        source = media.mediaSources?.firstOrNull()
                    }
                }
                // 记下这次播放的身份，供服务端上报（播放历史/继续观看靠它）
                pendingItemId = mediaId
                pendingPlaySessionId = media.playSessionId
                pendingMediaSourceId = source?.id
                pendingRunTimeTicks = source?.runTimeTicks ?: 0L
                // 字幕 / 音轨菜单要用它列选项（切轨靠 Emby 重新出流）
                currentStreams = source?.mediaStreams ?: emptyList()
                /*
                 * 按服务端给的判定挑地址（2026-10-06 父亲报「长宽比不对 / 没声音 / 灰蒙蒙」）。
                 * 原来无脑优先 directStreamUrl，等于把服务端的转码决定（音频转 AAC、
                 * HDR 转 SDR、换封装）全绕过去了。现在照 TV 版那套判定走。
                 */
                val method = Utils.determinePlayMethod(media)
                val path0 = when (method) {
                    "DirectPlay" -> source?.directStreamUrl
                    else -> source?.transcodingUrl ?: source?.directStreamUrl
                }
                Log.i(TAG, "播放方式=$method")
                if (path0 == null) {
                    hud("取不到播放地址（服务器未返回直链）")
                    return@launch
                }
                var path = path0
                // 直链缺 api_key 时补上（Emby 视频直链默认不带 token）
                if (!path.contains("api_key=")) {
                    path += (if (path.contains("?")) "&" else "?") + "api_key=${userToken()}"
                }
                val url = "${userServer()}/emby$path"
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
    private fun startPlayer(url: String, title: String, startMs: Long) {
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
            /*
             * 杜比视界片源：改走 mpv 解码内核（父亲 2026-10-08）。
             *
             * 系统解码器解不了杜比视界的 H.265 流，表现是「只有声音没有画面」；
             * 让服务端转码也不行（Emby 那条路走 QSV 硬解，日志里 hevc_qsv 报
             * unknown error (-21) 一万条后直接失败）。所以自己解 —— 和 PICO 上
             * 能正常播的 4XVR 一个路子。标记由取播放信息那一步立起来。
             */
            if (com.xxxx.emby_vr.player.PlaybackFlags.useKernelDecoder) {
                Log.i(TAG, "起播走 mpv 内核（杜比视界 Profile 5，片源 ${com.xxxx.emby_vr.player.PlaybackFlags.videoDescriptor}，" +
                    "版本 ${com.xxxx.emby_vr.player.PlaybackFlags.dolbyVisionProfile ?: "未探到"}）")
                mpvBackend?.stop()
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
                val directUrl = buildString {
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
                 * 画布尺寸（父亲 2026-10-08）。
                 *
                 * 一是必须设：我们的画面纹理从来没有缓冲尺寸，硬解时解码器会自己设，
                 * 内核自己渲染时不设就按默认尺寸画，屏幕上是整屏拉伸的色块。
                 *
                 * 二是别设成 4K：实测画布给到 3840x2160 时，内核每帧要处理的像素太多，
                 * 帧率在 12~24 之间跳、整个 VR 场景跟着抖。银幕在头显里根本用不到 4K，
                 * 宽度上限压到 1920（高度按片源比例算），像素量降到四分之一。
                 */
                val srcW = com.xxxx.emby_vr.player.PlaybackFlags.videoWidth.takeIf { it > 0 } ?: 1920
                val srcH = com.xxxx.emby_vr.player.PlaybackFlags.videoHeight.takeIf { it > 0 } ?: 1080
                /*
                 * 2026-10-09：上限可运行时调（vr-tuning 的 `video_surface_w`）。
                 * 实测杜比还原每帧吃约 15ms GPU，开销与像素量成正比，
                 * 把内核处理宽度压下来是当前最直接的省 GPU 手段。
                 */
                val cap = com.xxxx.emby_vr.vr.VrTuning.videoSurfaceMaxW.let { if (it > 0) it else 1920 }
                val vw = if (srcW > cap) cap else srcW
                val vh = (srcH.toLong() * vw / srcW).toInt().coerceAtLeast(64)
                renderer.setVideoBufferSize(vw, vh)
                mpvBackend = com.xxxx.emby_vr.player.MpvBackend(this).also { m ->
                    m.attachSurface(surface)
                    m.setSurfaceSize(vw, vh)
                    m.play(directUrl, startMs / 1000.0)
                    /*
                     * 起播后把内核侧轨道清单打进日志（父亲 2026-10-08）。
                     * 切音轨是按顺序映射的，清单里能看到内核认出的音轨 id 与语言，
                     * 万一映射对不上，一眼就能看出来。
                     */
                    scope.launch {
                        kotlinx.coroutines.delay(4000)
                        m.dumpTracks()
                        m.dumpVideoParams()
                        /*
                         * 上次选过的字幕在内核路径要重新选上（2026-10-09）：
                         * 内封轨道清单要等文件打开后才就绪，所以放在这个延时里。
                         */
                        val sel = selectedSubtitleIndex
                        if (sel != null) {
                            val ord = subtitleStreamIndices.indexOf(sel)
                            if (ord >= 0) {
                                Log.i(TAG, "内核模式：起播后恢复字幕 ${sel} → 序号 $ord 结果=" +
                                    m.setSubtitleByOrdinal(ord))
                            }
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
                // 比例先用片源探到的尺寸；探不到就按 16:9
                com.xxxx.emby_vr.vr.VrNative.setVideoAspect(
                    com.xxxx.emby_vr.player.PlaybackFlags.videoAspect ?: (16f / 9f),
                )
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
                picking = false
                reportedItemId = pendingItemId
                reportedPlaySessionId = pendingPlaySessionId
                reportedMediaSourceId = pendingMediaSourceId
                reportedRunTimeTicks = pendingRunTimeTicks
                osdState.durationMs = pendingRunTimeTicks / 10_000
                osdState.positionMs = 0L
                osdState.speed = playSpeed
                applyPlayMode(playModeIndex)
                startOsdTicker()
                startPosTicker()
                // 弹幕与片名 logo：起播后就去拉，任何一步失败都不影响播放
                loadDanmaku()
                loadItemDetail()
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
                com.xxxx.emby_vr.vr.VrNative.setVideoLayerMaxW(1280)
                startPlaybackReporting()
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
                            com.xxxx.emby_vr.vr.VrNative.setVideoAspect(a)
                            applyDanmakuCanvas(a)
                            // 纹理尺寸也要给（锐化的邻域步长按真实像素算）
                            com.xxxx.emby_vr.vr.VrNative.setVideoSize(
                                videoSize.width,
                                videoSize.height,
                            )
                            Log.i(TAG, "视频尺寸 ${videoSize.width}x${videoSize.height} 比例 $a")
                        }
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
                        val text = cleanSubtitleText(
                            cueGroup.cues.joinToString("\n") { cue ->
                                cue.text?.toString().orEmpty()
                            }
                        )
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
                                osdState.title = osdTitleText()
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
            }
            renderer.videoActive = true
            /*
             * 系统播放器路径 GPU 只要 1.8~2.4ms，视频层可以放开到 1920（2026-10-09）：
             * 只有内核那条（杜比视界 Profile 5）才需要压到 1280 换稳定。
             */
            com.xxxx.emby_vr.vr.VrNative.setVideoLayerMaxW(1920)
            // 外挂 ASS 是弹幕还是片子自带字幕 —— 后台看内容定（父亲 2026-10-09）
            probeExternalAssForDanmaku()
            picking = false
            osdState.title = title
            // 旧片已经停过（停止上报用的是旧身份），新片身份从现在起正式生效 —— 后面的字幕、弹幕、上报都要用它
            /*
             * 用户选过字幕 → 把那一条整条取回来自己画（父亲 2026-10-07）。
             * 没选 → 清掉自绘，交给播放器按语言偏好自动挑。
             */
            selectedSubtitleIndex?.let { idx ->
                /*
                 * 起播恢复上次选的字幕（2026-10-09）：内封文字轨交给播放器就地解析（秒出），
                 * 其余（外挂/图形）仍走服务端取流那条路。
                 */
                val st = currentStreams.firstOrNull { it.index == idx }
                if (st != null && st.isExternal != true && !isImageSubtitle(st)) {
                    selectEmbeddedTextTrack(idx)
                } else {
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
                // 弹幕与片名 logo：起播后就去拉，任何一步失败都不影响播放
                loadDanmaku()
                loadItemDetail()
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
            com.xxxx.emby_vr.panel.OsdButton.SEEK_BACK -> if (player != null) seekBy(-10_000)
            com.xxxx.emby_vr.panel.OsdButton.PLAY_PAUSE -> togglePlayPause()
            com.xxxx.emby_vr.panel.OsdButton.SEEK_FWD -> if (player != null) seekBy(+10_000)
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

    /** 退出 = 结束进程（父亲 2026-10-06 定：点退出就是退出 B0BEmby VR） */
    private fun exitApp() {
        Log.i(TAG, "控制条：退出应用（结束进程）")
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
                var i = index
                if (subtitleDanmakuRow) {
                    if (i == 0) {
                        // 第一行是弹幕开关：只动弹幕，字幕选择原样保留
                        danmakuOn = !danmakuOn
                        applyDanmakuSetting()
                        refreshMenuRows(com.xxxx.emby_vr.panel.MenuKind.SUBTITLE)
                        Log.i(TAG, "字幕菜单里切弹幕 → ${if (danmakuOn) "开" else "关"}（字幕不动）")
                        return
                    }
                    i -= 1
                }
                /*
                 * 点已勾上的那一条 = 取消勾选（父亲 2026-10-06 晚：不再单列「关闭字幕」，
                 * 取消勾选就起到关闭字幕的作用）。
                 */
                val picked = subtitleStreamIndices.getOrNull(i)
                selectedSubtitleIndex =
                    if (picked != null && picked == selectedSubtitleIndex) null else picked
                Log.i(TAG, "字幕 → ${selectedSubtitleIndex ?: "关闭"}（菜单保持打开）")
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
                     * 序号映射：Emby 字幕清单（buildSubtitleRows 已排除弹幕轨）里的第几个
                     * → 内核侧第几条（两边都按文件内顺序）。
                     */
                    val ordinal = selectedSubtitleIndex?.let { subtitleStreamIndices.indexOf(it) }
                    val pick = ordinal?.takeIf { it >= 0 }
                    val ok = mpvBackend?.setSubtitleByOrdinal(pick) == true
                    Log.i(TAG, "内核模式：字幕 ${selectedSubtitleIndex ?: "关闭"} → 序号 $pick 结果=$ok")
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
                    val wasBurning = com.xxxx.emby_vr.player.PlaybackFlags.burnSubtitleIndex != null
                    com.xxxx.emby_vr.player.PlaybackFlags.burnSubtitleIndex =
                        if (image) selectedSubtitleIndex else null
                    when {
                        image -> {
                            Log.i(TAG, "图形字幕 ${selectedSubtitleIndex} → 请服务端烧进画面（自动转码）")
                            replayKeepingPosition()
                        }
                        wasBurning -> {
                            Log.i(TAG, "取消图形字幕 → 回直送（不再转码）")
                            replayKeepingPosition()
                        }
                        embeddedText -> {
                            subtitleCues = emptyList()
                            subtitleCueStream = null
                            selectEmbeddedTextTrack(selectedSubtitleIndex!!)
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
                    val ordinal = audioStreamIndices.indexOf(selectedAudioIndex).coerceAtLeast(0)
                    val ok = mpvBackend?.setAudioTrackByOrdinal(ordinal) == true
                    Log.i(TAG, "内核模式：切音轨 流=${selectedAudioIndex ?: "默认"} 序号=$ordinal 结果=$ok")
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
        qualityIndex = index.coerceIn(0, com.xxxx.emby_vr.panel.QUALITY_STEPS.size - 1)
        Log.i(TAG, "视频质量 → ${com.xxxx.emby_vr.panel.QUALITY_STEPS[qualityIndex].second}")
        /*
         * 内核（Profile 5）路径**不重起播**（2026-10-09 父亲要求）。
         * 这条菜单改的是「让 Emby 转码到什么码率」，而 P5 走直链、服务端不推流，
         * 档位对它没有任何作用 —— 重起播只是白等一次，还有踩崩溃的风险。
         * 仅记录选择（下次走 Emby 取流的片源会用到），当前播放保持不动。
         */
        if (ctl.kernelActive) {
            Log.i(TAG, "内核直链播放：画质档位对当前片源无效，不重起播（保持续播）")
            hud("内核直链播放：画质由片源决定，已记录该档位")
            return
        }
        replayKeepingPosition()
    }

    private fun bitrateForQuality(): Int {
        val v = com.xxxx.emby_vr.panel.QUALITY_STEPS.getOrNull(qualityIndex)?.first ?: 0
        return if (v <= 0) 200_000_000 else v
    }

    /** 播放模式：列表循环 / 单集循环 / 播完停止 */
    private fun applyPlayMode(index: Int) {
        playModeIndex = index.coerceIn(0, 2)
        player?.repeatMode = when (playModeIndex) {
            0 -> androidx.media3.common.Player.REPEAT_MODE_ALL
            1 -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
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
            Log.i(TAG, "内核播放：缓冲档位对内核无效（走 mpv 缓存策略），不重起播（保持续播）")
            hud("内核播放：缓冲由内核自己管理，已记录该档位")
            return
        }
        replayKeepingPosition()
    }

    private fun bufferMinMs(): Int = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex].second

    private fun bufferMaxMs(): Int = com.xxxx.emby_vr.panel.BUFFER_PRESETS[bufferPresetIndex].third

    /** 保持当前位置重新起播（切字幕 / 音轨 / 质量 / 缓冲用） */
    private fun replayKeepingPosition() {
        if (currentMediaId.isBlank()) return
        replaying = true
        playMedia(currentMediaId, 0L, keepPosition = true)
    }

    /** 往菜单里填数据（打开菜单时调） */
    private fun fillMenuData(kind: com.xxxx.emby_vr.panel.MenuKind) {
        menuState.serverUrl = userServer()
        when (kind) {
            com.xxxx.emby_vr.panel.MenuKind.SPEED -> menuState.speed = playSpeed
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
            com.xxxx.emby_vr.panel.MenuKind.SUBTITLE -> menuState.subtitleTracks = buildSubtitleRows()
            com.xxxx.emby_vr.panel.MenuKind.EPISODES -> loadEpisodes()
            com.xxxx.emby_vr.panel.MenuKind.INFO, com.xxxx.emby_vr.panel.MenuKind.CAST ->
                loadItemDetail()
            com.xxxx.emby_vr.panel.MenuKind.MORE -> Unit
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
         * 字幕菜单里弹幕与字幕**可以同时勾**（父亲 2026-10-06 晚）。
         *
         * 弹幕与字幕是两套并行通道：弹幕那条 ASS 轨由我们自己下载、自绘在弹幕层；
         * 普通字幕由播放器回调交给弹幕层底部。菜单形态（父亲 2026-10-06 晚定稿）：
         *   · 有弹幕轨时第一行是「弹幕」，勾选态 = 弹幕开关，点一下切换；
         *   · 之后是各条文本字幕，只能选一条；
         *   · 没有「关闭字幕」这一项 —— 点已勾上的那一条就是取消勾选（关字幕），
         *     弹幕同理，点一下勾上、再点一下取消。
         * 两者互不覆盖，一屏里可以同时看到两个勾。
         */
        val hasDanmakuTrack = currentStreams.any {
            it.type.equals("Subtitle", ignoreCase = true) && isDanmakuStream(it)
        }
        subtitleDanmakuRow = hasDanmakuTrack
        /*
         * 图形字幕（PGS 等）分两条路对待（父亲 2026-10-09 定）：
         *   · 内核路径（Profile 5）：**列出来**，内核画得了图片字幕，选中就能显示；
         *   · 普通路径：也列出来，选中后自动请服务端把字幕烧进画面（见 EmbyApi 的
         *     burnSubtitleIndex 分支）—— 那条路必然转码，取消选择就回直送。
         * 所以这里不再过滤，只留一行日志说明这条轨是图形字幕。
         */
        val subs = currentStreams.filter { it.type == "Subtitle" && !isDanmakuStream(it) }
        val images = subs.filter { isImageSubtitle(it) }
        if (images.isNotEmpty()) {
            Log.i(TAG, "字幕菜单：图形字幕 ${images.size} 条 → " +
                images.joinToString(" | ") { "${it.index}:${it.codec}" } +
                "（内核=" + (mpvBackend != null) + "）")
        }
        subtitleStreamIndices = subs.mapNotNull { it.index }
        val rows = mutableListOf<com.xxxx.emby_vr.panel.MenuRowItem>()
        if (hasDanmakuTrack) {
            rows += com.xxxx.emby_vr.panel.MenuRowItem("弹幕", danmakuOn)
        }
        subs.forEachIndexed { i, s ->
            val label = s.displayTitle?.takeIf { it.isNotBlank() }
                ?: s.language?.takeIf { it.isNotBlank() }
                ?: s.codec?.uppercase()
                ?: "字幕 ${i + 1}"
            rows += com.xxxx.emby_vr.panel.MenuRowItem(label, s.index == selectedSubtitleIndex)
        }
        return rows
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
        val sourceId = (pendingMediaSourceId ?: reportedMediaSourceId)
            ?.takeIf { it.endsWith(mediaId) } ?: "mediasource_$mediaId"
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

    /** 菜单里选完一项后刷新列表的勾选态（菜单保持打开，勾要跟着动） */
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
    private fun loadDanmaku() {
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
        val sourceId = (pendingMediaSourceId ?: reportedMediaSourceId)
            ?.takeIf { it.endsWith(mediaId) }
            ?: "mediasource_$mediaId"
        Log.i(TAG, "弹幕诊断：挑中弹幕轨 index=$index source=$sourceId")
        if (index == null || sourceId.isNullOrBlank()) {
            Log.w(TAG, "弹幕轨信息不全，跳过（index=$index source=$sourceId）")
            return
        }
        val url = "${userServer()}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
            "/Stream.ass?api_key=${userToken()}"
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.URL(url).openConnection().let { conn ->
                        conn.connectTimeout = 8000
                        conn.readTimeout = 15000
                        conn.getInputStream().bufferedReader().use { it.readText() }
                    }
                }.getOrNull()
            }
            if (mediaId != currentMediaId) return@launch          // 中途换集了，丢弃
            if (raw.isNullOrBlank()) {
                Log.w(TAG, "弹幕文件没拉到：$mediaId")
                return@launch
            }
            val track = runCatching {
                com.xxxx.emby_vr.danmaku.AssDanmakuParser.parse(raw)
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

    /** 取这一集的详情：剧集 id / 季 id / 简介 / 演员（信息与演职人员菜单用） */
    private fun loadItemDetail() {
        val id = currentMediaId
        if (id.isBlank()) return
        scope.launch {
            try {
                val item = EmbyApi.getMediaInfo(
                    context = this@MainActivity,
                    serverUrl = userServer(),
                    apiKey = userToken(),
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    mediaId = id,
                )
                if (id != currentMediaId) return@launch   // 中途换片了，丢弃
                currentItem = item
                currentSeriesId = item.seriesId
                currentSeasonId = item.seasonId
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
                menuState.episodes = eps.mapIndexed { i, e ->
                    val season = e.parentIndexNumber
                    val num = e.indexNumber ?: (i + 1)
                    val name = e.name ?: ""
                    com.xxxx.emby_vr.panel.MenuRowItem(
                        label = if (season != null) "第 $season 季 第 $num 集  $name"
                        else "第 $num 集  $name",
                        selected = e.id == currentMediaId,
                    )
                }
                Log.i(TAG, "选集列表 ${eps.size} 集")
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
        if (!ctl.togglePlayPause()) Log.w(TAG, "暂停/继续：当前没有可操作的播放引擎")
    }

    /** 最近一次 seek 的目标位置与发起时间（毫秒）；用于连跳时的基准 */
    private var seekTargetMs: Long? = null
    private var seekTargetAt = 0L

    private fun seekBy(deltaMs: Long) {
        if (!ctl.hasEngine()) return
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
     * 当前播放位置（毫秒）。ExoPlayer 与 mpv 两条内核都从这里取，
     * 进度上报、弹幕时间轴、控制条进度共用同一个口径（父亲 2026-10-08）。
     */
    private fun currentPositionMs(): Long =
        player?.currentPosition ?: ((mpvBackend?.positionSec() ?: 0.0) * 1000.0).toLong()

    private fun stopPlaybackInternal(keepUi: Boolean = false) {
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
        mpvBackend?.let {
            runCatching { it.stop() }
        }
        mpvBackend = null
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

    /** 弹幕时间偏移上限：±2 分半（父亲 2026-10-09 定） */
    private val kDanmakuOffsetMaxMs = 150_000

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
    private fun selectEmbeddedTextTrack(streamIndex: Int, retry: Boolean = true) {
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
         * 轨还没报出来（刚起播、还在缓冲）——**先等一会儿再试一次**，
         * 别急着退到"服务端挖整片"那条慢路（2026-10-09 复查）。
         */
        if (retry) {
            Log.i(TAG, "内封字幕 $streamIndex：播放器还没报出文字轨，1.5 秒后重试")
            handler.postDelayed({
                if (currentMediaId.isNotBlank()) selectEmbeddedTextTrack(streamIndex, retry = false)
            }, 1500L)
            return
        }
        Log.w(TAG, "内封字幕 $streamIndex 两次都没选中 → 退回服务端取流（可能要等）")
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

    private fun tryRevealWaitingFrame() {
        if (!waitingFirstFrame) return
        // 只认"播放器已渲染出第一帧"这一个条件（+150ms 纹理余量，见 onRenderedFirstFrame）
        if (!playerFrameSeen) return
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
        val hadPlayback = player != null || currentMediaId.isNotBlank()
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
        com.xxxx.emby_vr.vr.VrNative.setVideoAspect(16f / 9f)
        /*
         * 画布比例必须跟着一起回 16:9（父亲 2026-10-07：等待期的转圈与「即将播放」变形）。
         * 等待期画在弹幕层上，而这一层此刻按 16:9 摆 —— 画布若还留着上一部（比如 2.39:1）
         * 的比例，圆就被压成椭圆。两者永远是成对改的。
         */
        applyDanmakuCanvas(16f / 9f)
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
         * 换片了：上一次为图形字幕立的「请服务端烧字幕」标记作废（父亲 2026-10-09）。
         * 不清的话下一部片子会莫名其妙走转码。
         */
        com.xxxx.emby_vr.player.PlaybackFlags.burnSubtitleIndex = null
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
                Log.w(TAG, "等第一帧超时（8 秒），兜底把弹幕 / 字幕 / logo 放出来")
                revealDanmakuSubtitleLogo()
            }
        }
        firstFrameFallback = fallback
        handler.postDelayed(fallback, 8000L)
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
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: B0BEmby VR 启动")
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
        panel = PanelLayer(this) {
            // 面板里放的就是电视版的界面：PanelApp 是它的导航装配（见 panel/PanelApp.kt）
            PanelApp(onPlayRequested = { mediaId, positionTicks ->
                onPanelPlayRequested(mediaId, positionTicks)
            })
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onResume() {
        super.onResume()
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
        if (keyCode == KeyEvent.KEYCODE_BACK && renderer.videoActive && !panelPointerOnPanel) {
            Log.i(TAG, "BACK 忽略：光点在播放画面上（播放页操作走控制条）")
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

    }
}

/** 一条字幕：起止时间（毫秒）+ 文本。客户端自绘字幕用（父亲 2026-10-07） */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 带样式的行（字号倍数 / 颜色）；纯文本 SRT 时为空，退回 text 那条老路 */
    val lines: List<com.xxxx.emby_vr.danmaku.SubtitleLine> = emptyList(),
)

/** 解析中间产物：一段字幕的原始行（还带着 HTML 风格样式标签） */
private data class RawCue(val startMs: Long, val endMs: Long, val lines: MutableList<String>)

/** 一行原始文本解析出来的三样东西 */
private data class StyledRaw(val text: String, val size: Int?, val color: Int?)
