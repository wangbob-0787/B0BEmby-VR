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
import com.xxxx.emby_vr.panel.isSubMenu
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private lateinit var danmaku: PanelLayer
    private var danmakuView: com.xxxx.emby_vr.danmaku.DanmakuView? = null

    /** 当前这一集解析好的弹幕（菜单里把弹幕关掉时先留着，开回来直接用） */
    private var danmakuTrack: com.xxxx.emby_vr.danmaku.DanmakuTrack? = null

    /** 片名 logo 层（银幕左上角那块小透明面板） */
    private lateinit var logo: PanelLayer
    private val logoUrl = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** 本次起播是不是「重起播」（切字幕 / 音轨 / 质量 / 缓冲 / 换集）：是就别收控制条与菜单 */
    private var replaying = false

    /** 换轨重播时往回退多少毫秒（父亲 2026-10-06 晚定） */
    private val kReplayBackMs = 10_000L

    // ── 光柱交互状态（2026-10-06 下午）──
    /** 最近一次控制条 / 菜单指针到达的时间：用来判断「光柱指着画面还是指着面板」 */
    private var lastOsdPointerAt = 0L
    private var lastMenuPointerAt = 0L
    /** 摇杆快进快退要回中一次才能再触发（免得住一个方向连续快进） */
    private var stickSeekArmed = true

    /** 光柱在海报墙上的最近位置（滚动时要带上去） */
    private var lastPanelPx = 0f
    private var lastPanelPy = 0f

    /** 面板最近一次吃下点击 / 滚动的时间：这一下扳机归面板，不能再当播放暂停 */
    private var lastPanelClickAt = 0L

    /** 海报墙摆放的本地存档（下次打开 APP 还原） */
    private val placePrefs by lazy { getSharedPreferences("b0bemby_vr", MODE_PRIVATE) }

    // ── 播放上下文（切字幕 / 音轨 / 质量 / 选集都要用它重新起播）──
    private var currentMediaId = ""
    private var currentSeriesId: String? = null
    private var currentSeasonId: String? = null
    private var currentItem: com.xxxx.emby_vr.data.model.BaseItemDto? = null
    private var currentStreams: List<com.xxxx.emby_vr.data.model.MediaStreamDto> = emptyList()
    private var audioStreamIndices: List<Int> = emptyList()
    private var subtitleStreamIndices: List<Int> = emptyList()
    private var episodeIds: List<String> = emptyList()

    /** 每集上次看到的位置（tick）：选集换片也从那儿接着播（父亲 2026-10-06 晚定） */
    private var episodePositions: List<Long> = emptyList()
    private var selectedAudioIndex: Int? = null
    private var selectedSubtitleIndex: Int? = null
    private var qualityIndex = 0
    private var bufferPresetIndex = 0
    private var playModeIndex = 0
    private var danmakuOn = true
    private var danmakuScale = 1f


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

        /**
         * 海报墙上按住扳机拖动 → 滚动（父亲 2026-10-06 晚）。
         *
         * 原生把「按住 + 移动手柄」的位移按面板像素送过来，直接喂给面板的滚动通道；
         * 顺手记一下时间，好让这一下扳机不再被当成播放 / 暂停。
         */
        override fun onPanelScroll(dx: Float, dy: Float) {
            vrInputLive = true
            runOnUiThread {
                lastPanelClickAt = android.os.SystemClock.uptimeMillis()
                if (panelInputReady()) {
                    panel.vrScroll(lastPanelPx, lastPanelPy, dx, dy)
                }
            }
        }

        override fun onPanelFocus(onPanel: Boolean) {
            // 原生只在变化时推：光柱是否落在海报墙上
            runOnUiThread { panelPointerOnPanel = onPanel }
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
            runOnUiThread {
                /*
                 * 光柱指着**画面**时，摇杆左右 = 快退 / 快进（父亲 2026-10-06）。
                 *
                 * 「指着画面」的判断：原生只在光柱真的落在控制条 / 菜单上时才推指针，
                 * 所以 400ms 内没有面板指针 = 光柱在画面这一侧。
                 */
                val nowMs = android.os.SystemClock.uptimeMillis()
                val onPanelUi = nowMs - maxOf(lastOsdPointerAt, lastMenuPointerAt) < 400L
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

    /** 摇杆左右快进快退：推过 0.7 触发一次，回中（< 0.35）后才能再触发 */
        private fun stickSeek(sx: Float) {
            if (player == null) return
            val mag = kotlin.math.abs(sx)
            if (mag < 0.35f) {
                stickSeekArmed = true
                return
            }
            if (!stickSeekArmed || mag < 0.7f) return
            stickSeekArmed = false
            seekBy(if (sx > 0f) 10_000 else -10_000)
            Log.i(TAG, "光柱指着画面 + 摇杆 → ${if (sx > 0f) "快进" else "快退"} 10 秒")
        }

        override fun onOsdPointer(px: Float, py: Float, pressed: Boolean) {
            vrInputLive = true
            lastOsdPointerAt = android.os.SystemClock.uptimeMillis()
            // 归一化横向位置：界面拿它判断光柱停在哪儿（按钮悬停高亮）
            osdState.pointerNx = if (px < 0f) {
                -1f                                             // 光柱不在控制条上：清掉悬停高亮
            } else {
                (px / com.xxxx.emby_vr.panel.OSD_PANEL_W).coerceIn(0f, 1f)
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

        override fun onBack() {
            vrInputLive = true
            runOnUiThread {
                /*
                 * 播放中 B 键 = 停止播放回面板（2026-10-05 父亲实测：点了播放就回不来）。
                 * 原来这里用 panelInputReady() 当门，而它在播放时恒为 false（playing 期间
                 * 面板不接输入）→ B 键被静默丢弃，只能杀应用。
                 */
                if (menuState.kind != null) {
                    Log.i(TAG, "光柱 B 键 → 菜单返回上一级")
                    menuBack()
                } else if (renderer.videoActive) {
                    Log.i(TAG, "光柱 B 键 → 停止播放回面板")
                    stopPlayback()
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

    /** 控制条第一行右侧的时间格式 */
    private val osdClockFormat =
        java.text.SimpleDateFormat("yyyy年MM月dd HH:mm:ss", java.util.Locale.getDefault())

    /**
     * 控制条第一行的片名（父亲 2026-10-06 晚定稿）：
     *  · 电影：`正在播放：片名`
     *  · 剧集：`正在播放：剧名 第X集 集名`
     *  · 有剧名没集号：`正在播放：剧名`
     */
    private fun osdTitleText(): String {
        val item = currentItem
        val series = item?.seriesName
        val ep = item?.indexNumber
        val name = item?.name
        val base = when {
            !series.isNullOrBlank() && ep != null ->
                if (!name.isNullOrBlank() && name != series) "$series 第${ep}集 $name"
                else "$series 第${ep}集"
            !series.isNullOrBlank() -> series
            else -> name ?: osdState.title
        }
        return "正在播放：$base"
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

    /** 控制条显隐（VR 侧画不画那块面板） */
    private fun setOsdVisible(visible: Boolean) {
        osdVisible = visible
        com.xxxx.emby_vr.vr.VrNative.setOsdVisible(visible)
        // 控制条收起来时菜单一起收（父亲 2026-10-06：菜单挂在控制条上）
        if (!visible) closeMenu()
        Log.i(TAG, if (visible) "控制条显示" else "控制条隐藏")
    }

    /** 播放进度 → 控制条（每秒刷一次，进度条才走得动） */
    private fun startOsdTicker() {
        osdJob?.cancel()
        osdJob = scope.launch {
            while (renderer.videoActive || picking) {
                val p = player
                if (p != null) {
                    osdState.playing = p.playWhenReady
                    osdState.positionMs = p.currentPosition
                    // 缓冲进度：进度条上那一段浅色（电视版进度条有这个）
                    osdState.bufferedMs = p.bufferedPosition
                    val d = p.duration
                    if (d > 0L) osdState.durationMs = d
                }
                // 控制条第一行：正在播放什么 + 当前时间（父亲 2026-10-06 晚）
                osdState.title = osdTitleText()
                osdState.nowClock = osdClockFormat.format(java.util.Date())
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

    // ---- 摇杆手势识别（父亲 2026-10-04 规格）----
    //
    // 规格：左拨一次（500ms 内拨出→回中）= 快退 10 秒；拨住不回中 = 持续快退。
    //       右拨同理快进。
    //
    // 难点：PICO 只给光标流，摇杆与手部晃动同源。区分依据 ——
    //   摇杆拨动：**平滑单向**移动，且停在新位置（不回中）或迅速弹回（回中）
    //   手部晃动：**快速往复**，方向频繁反转，始终围绕原点
    // 因此：方向反转即清零累计（晃动永远累计不到阈值）。
    private var stickRunStart = 0f      // 本轮单向移动起点
    private var stickRunDir = 0         // 本轮方向 ±1
    private var stickLastX = 0f
    private var stickLastAt = 0L
    private var stickHoldFiredAt = 0L   // 持续模式上次续跳时间

    private fun onStickMotion(x: Float) {
        if (!renderer.videoActive) return
        val now = System.currentTimeMillis()
        val dt = now - stickLastAt
        stickLastAt = now

        // 停顿 >400ms 视为新手势起点
        if (dt > STICK_GAP_MS) {
            stickRunStart = x
            stickRunDir = 0
            stickLastX = x
            stickHoldFiredAt = 0L
            return
        }
        val dx = x - stickLastX
        stickLastX = x

        // 指针跳变过滤（父亲 2026-10-04 实测：晃手时光标会瞬间窜一大段，
        // 例如 0.656 → 1.778，超过屏幕范围，被误当成一次拨动）。
        // 真摇杆拨动是平滑的，单帧位移远小于此；跳变一律当噪音丢弃并重起手势。
        if (kotlin.math.abs(dx) > STICK_MAX_JUMP) {
            stickRunStart = x
            stickRunDir = 0
            stickHoldFiredAt = 0L
            Log.i(TAG, "指针跳变 ${"%.3f".format(dx)}，丢弃")
            return
        }

        val dir = when {
            dx > STICK_EPS -> 1
            dx < -STICK_EPS -> -1
            else -> 0
        }
        if (dir != 0 && dir != stickRunDir) {
            // 方向反转（晃手特征）→ 重新起算
            stickRunDir = dir
            stickRunStart = x - dx
        }
        if (stickRunDir == 0) return

        val run = x - stickRunStart

        // 回中检测：指针回到起点附近（拨一下松手）→ 结束持续模式。
        // 这是父亲规格里「500ms 内回中 = 只跳一次」的判定点。
        if (stickHoldFiredAt > 0 && kotlin.math.abs(run) < STICK_RUN / 2f) {
            stickHoldFiredAt = 0
            return
        }

        // 持续模式：拨住不回中（指针停在偏位）→ 每 500ms 续跳一次
        if (stickHoldFiredAt > 0 && now - stickHoldFiredAt >= STICK_HOLD_MS) {
            stickHoldFiredAt = now
            Log.i(TAG, "摇杆持续拨住: run=${"%.3f".format(run)} → 续跳 10 秒")
            seekBy(if (run > 0) -10_000 else +10_000)
            return
        }
        // 首次触发：累计单向位移过阈值
        if (kotlin.math.abs(run) < STICK_RUN) return
        if (now - lastSeekAt < SEEK_COOLDOWN_MS) return
        lastSeekAt = now
        stickHoldFiredAt = now
        Log.i(TAG, "摇杆拨动: run=${"%.3f".format(run)} → ${if (run > 0) "快退" else "快进"} 10 秒")
        // 位移为正（指针 x 增大）实机上对应视觉左侧 → 快退
        seekBy(if (run > 0) -10_000 else +10_000)
    }

    /** 按住扳机拖拽已触发的档位数与上次触发时间 */
    private var dragFiredSteps = 0
    private var lastSeekAt = 0L

    /**
     * 拨摇杆 → 快进/快退（2026-10-04 对 B 站抓包后定案）。
     *
     * 实测机制：拨摇杆时系统合成一次「拖动手势」——
     *   BTN_TOUCH DOWN → ABS_X 横向移动 800+ 像素 → BTN_TOUCH UP
     * 而晃动手柄只有坐标抖动、**没有 DOWN/UP**，所以不会走到这里。
     * 这就是"晃手无反应、拨摇杆有效"的根本原因，也是 B 站的做法。
     *
     * 档位按「离按下点的距离」数，只在档位**增加**时触发：
     * 手回到按下点的过程只让档位回落，不会反向误触发。
     *
     * @param dx 相对按下点的横向位移（归一化坐标，半高 = 1）
     */
    @Suppress("unused")
    private fun onTriggerDrag(dx: Float) {
        if (!renderer.videoActive) return
        val steps = (kotlin.math.abs(dx) / DRAG_SEEK_STEP).toInt()
        if (steps <= dragFiredSteps) return
        val now = System.currentTimeMillis()
        if (now - lastSeekAt < SEEK_COOLDOWN_MS) return
        lastSeekAt = now
        dragFiredSteps = steps
        Log.i(TAG, "摇杆拖动: dx=${"%.3f".format(dx)} → ${if (dx > 0) "快退" else "快进"} 10 秒")
        // 位移为正（指针 x 增大）在实机上对应视觉左侧 → 快退
        seekBy(if (dx > 0) -10_000 else +10_000)
    }

    /** 按键通道的射线同步（方向键分支走这里），复用同一套 seek 逻辑 */
    /** 每次「按下」开始新的拖动窗口，档位归零 */
    private fun onPointerDown() {
        dragFiredSteps = 0
    }

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
                val from = ((player?.currentPosition ?: 0L) - kReplayBackMs).coerceAtLeast(0L)
                Log.i(TAG, "重播起始位置 ${from / 1000} 秒（当前位置回退 ${kReplayBackMs / 1000} 秒）")
                from * 10_000L
            } else {
                startTicks
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
                val media = EmbyApi.getPlaybackInfo(
                    context = this@MainActivity,
                    serverUrl = BuildConfig.EMBY_SERVER,
                    apiKey = BuildConfig.EMBY_API_KEY,
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    mediaId = mediaId,
                    startTimeTicks = effectiveStart,
                    selectedAudioIndex = selectedAudioIndex,
                    selectedSubtitleIndex = selectedSubtitleIndex,
                    maxStreamingBitrate = bitrateForQuality(),
                )
                val source = media.mediaSources?.firstOrNull()
                // 记下这次播放的身份，供服务端上报（播放历史/继续观看靠它）
                reportedItemId = mediaId
                reportedPlaySessionId = media.playSessionId
                reportedMediaSourceId = source?.id
                reportedRunTimeTicks = source?.runTimeTicks ?: 0L
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
                    path += (if (path.contains("?")) "&" else "?") + "api_key=${BuildConfig.EMBY_API_KEY}"
                }
                val url = "${BuildConfig.EMBY_SERVER}/emby$path"
                withContext(Dispatchers.Main) {
                    startPlayer(url, mediaId)
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
    private fun startPlayer(url: String, title: String) {
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
            player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { p ->
                /*
                 * 起始位置一并交给播放器：服务端虽然按 startTimeTicks 从该位置出流，
                 * 播放器自己仍会从流的第 0 秒开始放 —— 换轨重播「从头开始」就是这个。
                 */
                p.setMediaItem(MediaItem.fromUri(url), effectiveStart / 10_000L)
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
                        val text = cueGroup.cues.joinToString("\n") { cue ->
                            cue.text?.toString().orEmpty()
                        }.trim()
                        danmakuView?.setSubtitle(text)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // 自然播完 → 上报停止（服务端据此记"已看"与进度）
                        if (state == Player.STATE_ENDED) {
                            Log.i(TAG, "播放结束 → 上报停止")
                            reportPlaybackStopped(player?.duration ?: 0L)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 先停 videoActive（ticker 下一圈自行退出），再写错误提示，
                        // 否则每秒刷新的绿字会把错误盖掉
                        renderer.videoActive = false
                        hud("播放出错：${friendlyError(error)}")
                    }
                })
            }
            renderer.videoActive = true
            picking = false
            osdState.title = title
            osdState.durationMs = reportedRunTimeTicks / 10_000
            osdState.positionMs = 0L
            osdState.speed = playSpeed
            applyPlayMode(playModeIndex)   // 播放模式在起播时也应用一次
            if (com.xxxx.emby_vr.vr.VrNative.vrRunning) {
                /*
                 * 起播不自动亮控制条（父亲 2026-10-06）：要看控制条，指着银幕扣扳机。
                 * 但**重起播**（切字幕 / 音轨 / 质量 / 缓冲、选集换片）时控制条和已经
                 * 展开的菜单都要留着 —— 父亲 2026-10-06 晚明确：不要收起、不要关闭。
                 */
                if (replaying) {
                    replaying = false
                } else {
                    setOsdVisible(false)
                }
                startOsdTicker()
                // 弹幕与片名 logo：起播后就去拉，任何一步失败都不影响播放
                loadDanmaku()
                loadItemDetail()
            }
            if (useVrScreen) {
                // 播放时贴视频画面、收起面板（VR 原生播放屏）
                com.xxxx.emby_vr.vr.VrNative.setVideoActive(true)
                Log.i(TAG, "播放画面已切到 VR 原生（面板收起）")
            }
            dragFiredSteps = 0
            lastSeekAt = 0L
            stickRunDir = 0
            stickRunStart = 0f
            stickLastAt = 0L
            stickHoldFiredAt = 0L
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
                if (index == 0) {
                    danmakuOn = !danmakuOn
                } else {
                    com.xxxx.emby_vr.panel.DANMAKU_SCALES.getOrNull(index - 1)
                        ?.let { danmakuScale = it.first }
                }
                menuState.danmakuOn = danmakuOn
                menuState.danmakuScale = danmakuScale
                // 开关与字号立刻作用到弹幕层：关掉整层不画，开回来立刻显示
                danmakuView?.userScale = danmakuScale
                danmakuView?.setTrack(if (danmakuOn) danmakuTrack else null)
                com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(danmakuOn && danmakuTrack != null)
                Log.i(TAG, "弹幕设置 → ${if (danmakuOn) "开" else "关"}，字号 ${danmakuScale}")
            }
            com.xxxx.emby_vr.panel.MenuKind.SUBTITLE -> {
                // 第 0 行是「关闭字幕」，其余按顺序对应文本字幕流
                selectedSubtitleIndex =
                    if (index == 0) null else subtitleStreamIndices.getOrNull(index - 1)
                Log.i(TAG, "字幕 → ${selectedSubtitleIndex ?: "关闭"}（菜单保持打开）")
                replayKeepingPosition()
            }
            com.xxxx.emby_vr.panel.MenuKind.AUDIO -> {
                selectedAudioIndex = audioStreamIndices.getOrNull(index)
                Log.i(TAG, "音轨 → 流 ${selectedAudioIndex ?: "默认"}（菜单保持打开）")
                replayKeepingPosition()
            }
            com.xxxx.emby_vr.panel.MenuKind.EPISODES -> {
                val id = episodeIds.getOrNull(index)
                val resume = episodePositions.getOrNull(index) ?: 0L
                if (!id.isNullOrBlank()) {
                    Log.i(TAG, "选集 → $id（续播 ${resume / 10_000_000} 秒，菜单保持打开）")
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
        player?.setPlaybackSpeed(speed)
        osdState.speed = speed
        Log.i(TAG, "倍速 → ${speed}x")
    }

    /** 视频质量：换码率上限重起播（服务端据此决定转码档位） */
    private fun applyQuality(index: Int) {
        qualityIndex = index.coerceIn(0, com.xxxx.emby_vr.panel.QUALITY_STEPS.size - 1)
        Log.i(TAG, "视频质量 → ${com.xxxx.emby_vr.panel.QUALITY_STEPS[qualityIndex].second}")
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
        menuState.serverUrl = BuildConfig.EMBY_SERVER
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
        val subs = currentStreams.filter { it.type == "Subtitle" }
        subtitleStreamIndices = subs.mapNotNull { it.index }
        val rows = mutableListOf(
            com.xxxx.emby_vr.panel.MenuRowItem("关闭字幕", selectedSubtitleIndex == null),
        )
        subs.forEachIndexed { i, s ->
            val label = s.displayTitle?.takeIf { it.isNotBlank() }
                ?: s.language?.takeIf { it.isNotBlank() }
                ?: s.codec?.uppercase()
                ?: "字幕 ${i + 1}"
            rows += com.xxxx.emby_vr.panel.MenuRowItem(label, s.index == selectedSubtitleIndex)
        }
        return rows
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
        com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(false)

        val mediaId = currentMediaId
        if (mediaId.isBlank()) return
        val sub = currentStreams.firstOrNull { stream ->
            val codec = (stream.codec ?: "").lowercase()
            stream.type.equals("Subtitle", ignoreCase = true) && (codec == "ass" || codec == "ssa")
        } ?: run {
            Log.i(TAG, "这一集没有弹幕轨（没有 ass / ssa 字幕流）")
            return
        }
        val index = sub.index
        val sourceId = reportedMediaSourceId
        if (index == null || sourceId.isNullOrBlank()) {
            Log.w(TAG, "弹幕轨信息不全，跳过（index=$index source=$sourceId）")
            return
        }
        val url = "${BuildConfig.EMBY_SERVER}/emby/Videos/$mediaId/$sourceId/Subtitles/$index" +
            "/Stream.ass?api_key=${BuildConfig.EMBY_API_KEY}"
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
            com.xxxx.emby_vr.vr.VrNative.setDanmakuVisible(danmakuOn)
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
                    serverUrl = BuildConfig.EMBY_SERVER,
                    apiKey = BuildConfig.EMBY_API_KEY,
                    deviceId = EmbyContent.DEVICE_ID,
                    userId = BuildConfig.EMBY_USER_ID,
                    mediaId = id,
                )
                if (id != currentMediaId) return@launch   // 中途换片了，丢弃
                currentItem = item
                currentSeriesId = item.seriesId
                currentSeasonId = item.seasonId
                menuState.info = com.xxxx.emby_vr.panel.MediaInfoView(
                    title = item.name ?: osdState.title,
                    year = item.productionYear?.toString() ?: "",
                    runtime = item.runTimeTicks?.let {
                        com.xxxx.emby_vr.panel.osdTimeText(it / 10_000)
                    } ?: "",
                    rating = item.communityRating?.let { "★ %.1f".format(it) } ?: "",
                    genres = item.genres?.take(3)?.joinToString(" / ") ?: "",
                    overview = item.overview ?: "",
                )
                // 海报（父亲 2026-10-06：信息面板要带海报）
                val posterTag = item.imageTags?.get("Primary")
                menuState.posterUrl = if (posterTag.isNullOrBlank()) {
                    null
                } else {
                    "${BuildConfig.EMBY_SERVER}/emby/Items/$id/Images/Primary" +
                        "?maxWidth=520&tag=$posterTag&quality=90"
                }
                // 演职人员头像
                menuState.people = (item.people ?: emptyList()).take(40).map { p ->
                    val pid = p.id
                    val ptag = p.primaryImageTag
                    com.xxxx.emby_vr.panel.PersonItem(
                        name = p.name ?: "",
                        role = p.role ?: "",
                        avatarUrl = if (!pid.isNullOrBlank() && !ptag.isNullOrBlank()) {
                            "${BuildConfig.EMBY_SERVER}/emby/Items/$pid/Images/Primary" +
                                "?maxHeight=420&tag=$ptag&quality=90"
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
                        "${BuildConfig.EMBY_SERVER}/emby/Items/$sid/Images/Logo?maxHeight=200"
                    !ownLogoTag.isNullOrBlank() ->
                        "${BuildConfig.EMBY_SERVER}/emby/Items/$id/Images/Logo" +
                            "?maxHeight=200&tag=$ownLogoTag"
                    else -> null
                }
                logoUrl.value = logoAddr
                com.xxxx.emby_vr.vr.VrNative.setLogoVisible(!logoAddr.isNullOrBlank())
                Log.i(
                    TAG,
                    "详情已取到：${item.name}（演员 ${menuState.people.size} 人" +
                        "，logo ${if (logoAddr.isNullOrBlank()) "无" else "有"}）",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "取详情失败", t)
            }
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
                    serverUrl = BuildConfig.EMBY_SERVER,
                    apiKey = BuildConfig.EMBY_API_KEY,
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
        player?.setPlaybackSpeed(playSpeed)
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
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        Log.i(TAG, if (p.playWhenReady) "继续播放" else "已暂停")
    }

    /** 最近一次 seek 的目标位置与发起时间（毫秒）；用于连跳时的基准 */
    private var seekTargetMs: Long? = null
    private var seekTargetAt = 0L

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        // ExoPlayer 的 seekTo 是异步的：连续快进时上一跳还没落地，
        // currentPosition 仍是旧值，基于它算下一跳会越跳越偏
        // （父亲 2026-10-04 实测：连跳几次落点与预期不符）。
        // 500ms 内的连跳一律以「上一跳的目标」为基准，之后回归真实位置。
        val withinChain = seekTargetMs != null &&
            System.currentTimeMillis() - seekTargetAt < 500
        val base = if (withinChain) seekTargetMs!! else p.currentPosition
        val target = (base + deltaMs).coerceAtLeast(0L)
        seekTargetMs = target
        seekTargetAt = System.currentTimeMillis()
        p.seekTo(target)
        val sec = target / 1000
        Log.i(TAG, "seek ${deltaMs / 1000}s → ${sec / 60}:${"%02d".format(sec % 60)} (基准 ${if (withinChain) "连跳" else "实时"})")
        // 状态绿字已取消（父亲 2026-10-05），跳转结果只进日志
        Log.i(TAG, "跳转落点 ${sec / 60}:${"%02d".format(sec % 60)}")
    }

    /** 停止播放，回到界面（面板层） */
    private fun stopPlayback() {
        // 先取位置再释放播放器：这一条决定服务端记住看到哪儿
        reportPlaybackStopped(player?.currentPosition?.times(10_000) ?: 0L)
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
    private var reportedItemId: String? = null
    private var reportedPlaySessionId: String? = null
    private var reportedMediaSourceId: String? = null
    private var reportedRunTimeTicks: Long = 0L
    private var progressJob: kotlinx.coroutines.Job? = null

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
                        this@MainActivity, BuildConfig.EMBY_SERVER, BuildConfig.EMBY_API_KEY,
                        EmbyContent.DEVICE_ID, body,
                    )
                    "progress" -> EmbyApi.reportPlaybackProgress(
                        this@MainActivity, BuildConfig.EMBY_SERVER, BuildConfig.EMBY_API_KEY,
                        EmbyContent.DEVICE_ID, body,
                    )
                    else -> EmbyApi.stopped(
                        this@MainActivity, BuildConfig.EMBY_SERVER, BuildConfig.EMBY_API_KEY,
                        EmbyContent.DEVICE_ID, body,
                    )
                }
            }.onFailure { Log.w(TAG, "上报${what}失败: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 起播后：立刻上报"开始播放"，随后每 10 秒上报一次进度 */
    private fun startPlaybackReporting() {
        val itemId = reportedItemId ?: return
        val startTicks = (player?.currentPosition ?: 0L).times(10_000)
        reportToServer("playing", playbackReportBody(itemId, startTicks, isPaused = false), "开始播放")
        Log.i(TAG, "进度上报: 开始 item=$itemId playSession=${reportedPlaySessionId ?: "-"}")
        progressJob?.cancel()
        progressJob = scope.launch {
            while (renderer.videoActive) {
                kotlinx.coroutines.delay(10_000)
                val p = player ?: continue
                val item = reportedItemId ?: continue
                reportToServer(
                    "progress",
                    playbackReportBody(item, p.currentPosition * 10_000, p.playWhenReady.not(), "timeupdate"),
                    "进度",
                )
                Log.i(TAG, "进度上报: ${p.currentPosition / 1000}s item=$item")
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
    private fun stopPlaybackInternal(keepUi: Boolean = false) {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        seekTargetMs = null
        // 播放结束：VR 画面切回面板（非 VR 模式下这条调用没有副作用）
        com.xxxx.emby_vr.vr.VrNative.setVideoActive(false)
        picking = false
        osdJob?.cancel()
        osdJob = null
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
        // 回海报墙的时候确保它摆着（播放中可以把它收起来，别让收起来的状态带回去）
        // 海报墙回到上次退出前的位置 / 大小 / 远近（父亲 2026-10-06 晚）
        restorePanelPlace()
        com.xxxx.emby_vr.vr.VrNative.updatePanelShown(true)
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
         * 弹幕层（2026-10-06）：贴银幕前的一块透明面板，尺寸照 16:9 给足像素，
         * 弹幕文字才不会糊。整层由原生按开关决定画不画。
         */
        danmaku = PanelLayer(
            this,
            content = {
                androidx.compose.ui.viewinterop.AndroidView(factory = { ctx ->
                    com.xxxx.emby_vr.danmaku.DanmakuView(ctx).apply {
                        // 每帧按播放器当前进度重算坐标：掉帧只会跳一下，不会越走越偏
                        setPositionProvider { player?.currentPosition ?: 0L }
                        userScale = danmakuScale
                        start()
                        danmakuView = this
                    }
                })
            },
            panelW = 2560,
            panelH = 1440,
            name = "b0bemby-danmaku",
            activatesVrPanel = false,
        )
        // 纹理回调要是比这里先到，现在补挂上（否则这块画面永远不绘制）
        val dSt = pendingDanmakuSt
        if (dSt != null) {
            Log.i(TAG, "弹幕面板建好，补挂纹理")
            danmaku.attach(dSt)
        } else {
            Log.w(TAG, "弹幕面板建好，但纹理还没到（原生回调未触发？）")
        }

        /*
         * 片名 logo（2026-10-06）：银幕左上角的小透明面板。
         * 位置与尺寸由原生按电视版比例钉在银幕左上角，这里只管画那张图。
         */
        logo = PanelLayer(
            this,
            content = {
                val url = logoUrl.value
                if (!url.isNullOrBlank()) {
                    // 框内等比缩放：图不会变形（电视版同款 ContentScale.Fit）
                    coil3.compose.AsyncImage(
                        model = url,
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
                            Log.i(TAG, "收到弹幕层纹理（面板已建=${::danmaku.isInitialized}）")
                            pendingDanmakuSt = st
                            if (::danmaku.isInitialized) danmaku.attach(st)
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
        // 拖进度条：拖动中只动显示（跟手），松手才真跳
        osdState.onSeekPreview = { frac ->
            val dur = osdState.durationMs
            if (dur > 0L) osdState.positionMs = (dur * frac).toLong()
        }
        osdState.onSeekCommit = { frac ->
            val dur = osdState.durationMs
            val p = player
            if (dur > 0L && p != null) {
                val target = (dur * frac).toLong()
                osdState.positionMs = target
                p.seekTo(target)
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
                    onDown = { onPointerDown() },
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
                    onDown = { onPointerDown() },
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
        com.xxxx.emby_vr.panel.PanelSignals.bump()
        playMedia(mediaId, positionTicks)
    }

    /** 播放器：面板点播放起播，返回键释放 */
    private var player: ExoPlayer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onResume() {
        super.onResume()
        glView.onResume()
        vrSession.resume()
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
         * 播放中按 B（返回）：先停播放，回界面。
         *
         * 2026-10-05 父亲实测「进了播放页按 B 返回不了，只能关 app」：
         * 以前 BACK 一律先给面板导航栈，而播放时面板仍然活着（panelActive=true），
         * 于是 B 被面板吃掉（在背后做了一次界面返回），播放一点没停 —— 看起来就是没反应。
         * 现在按「谁在前面谁先接」：播放在前 → 停播放。
         */
        if (keyCode == KeyEvent.KEYCODE_BACK && renderer.videoActive) {
            Log.i(TAG, "BACK：播放中 → 停止播放回界面")
            stopPlayback()
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
                onDown = { onPointerDown() },
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
         * 按住扳机拖拽触发一次快进/快退的横向位移（归一化坐标，半高 = 1）。
         * 0.9 ≈ 半屏宽度的一半：按住扳机左右拖一下就能到。
         */
        private const val DRAG_SEEK_STEP = 0.9f

        /**
         * 拨摇杆判定：单向累计位移过该值算一次「拨动」（归一化坐标，半高=1）。
         *
         * 取值 0.9 来自 build-38 实机数据分布（父亲 2026-10-04 实测）：
         *   真拨摇杆：累计位移 1.5 ~ 2.1
         *   晃动手柄：累计位移 0.35 ~ 0.51
         *   0.6 ~ 1.4 之间无样本 —— 天然分界带，取 0.9 居中。
         */
        private const val STICK_RUN = 0.9f

        /** 判定指针移动方向的最小步长，滤掉落点抖动 */
        private const val STICK_EPS = 0.003f

        /**
         * 单帧位移上限：超过即视为指针跳变噪音（非摇杆拨动）。
         * 真摇杆拨动是平滑的，实测单帧位移在 0.1 以内；
         * 晃手时的光标跳变可达 1.0+（build-37 实机日志 0.656→1.778）。
         */
        private const val STICK_MAX_JUMP = 0.25f

        /** 停顿超过该时长视为新手势（摇杆回到中位） */
        private const val STICK_GAP_MS = 400L

        /** 拨住不放时的续跳间隔（父亲规格：500ms 内不回中 → 持续快进快退） */
        private const val STICK_HOLD_MS = 500L

        /** 两次快进/快退的最小间隔（防连发） */
        private const val SEEK_COOLDOWN_MS = 800L
    }
}
