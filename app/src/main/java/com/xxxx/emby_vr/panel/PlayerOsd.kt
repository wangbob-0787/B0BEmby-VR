package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放控制条（VR 原生播放屏的 OSD）。
 *
 * 形态：一块架在观影者身前近场的矮条（3600×340 像素的虚拟显示器，与主面板同一套
 * VirtualDisplay + SurfaceTexture 机制）。窗口背景透明，圆角外透出影院画面。
 *
 * 2026-10-06 父亲定稿的 12 颗按钮，从左到右分三组：
 *  - 左组（贴左）：字幕 · 弹幕
 *  - 中组：快退 10 · 播放/暂停 · 快进 10
 *  - 右组（贴右）：播放速度 · 选集 · 选片 · 信息 · 演职人员 · 更多 · 退出
 *
 * 观感（父亲 2026-10-06 晚：要做得酷一点）：
 *  - 条身是玻璃质感：上亮下暗的渐变 + 一圈细高光描边；
 *  - 播放/暂停是**主按钮**：白色实心圆 + 深色图标，一眼能找到；
 *  - 光柱扫过按钮会亮起来，菜单开着的那颗常亮青色；
 *  - 进度条青绿渐变，拖动手柄带光晕；
 *  - 三组之间留的缝是组内按钮缝的两倍。
 */
enum class OsdButton(val label: String, val icon: ImageVector) {
    // ── 左组 ──
    SUBTITLE("字幕", Icons.Filled.ClosedCaption),
    DANMAKU("弹幕", Icons.Filled.Chat),

    // ── 中组 ──
    SEEK_BACK("快退10", Icons.Filled.Replay10),
    PLAY_PAUSE("播放", Icons.Filled.PlayArrow),
    SEEK_FWD("快进10", Icons.Filled.Forward10),

    // ── 右组 ──
    SPEED("播放速度", Icons.Filled.Speed),
    EPISODES("选集", Icons.Filled.ViewList),
    PICK("选片", Icons.Filled.MovieCreation),
    INFO("信息", Icons.Filled.Info),
    CAST("演职人员", Icons.Filled.Groups),
    MORE("更多", Icons.Filled.Settings),
    EXIT("退出", Icons.Filled.Logout),
}

/** 左组：贴左 */
val OSD_LEFT_GROUP = listOf(OsdButton.SUBTITLE, OsdButton.DANMAKU)

/** 中组：夹在左右两组之间 */
val OSD_CENTER_GROUP = listOf(OsdButton.SEEK_BACK, OsdButton.PLAY_PAUSE, OsdButton.SEEK_FWD)

/** 右组：贴右 */
val OSD_RIGHT_GROUP = listOf(
    OsdButton.SPEED,
    OsdButton.EPISODES,
    OsdButton.PICK,
    OsdButton.INFO,
    OsdButton.CAST,
    OsdButton.MORE,
    OsdButton.EXIT,
)

/**
 * 三组之间的固定间距（dp）= 按钮间距的两倍。
 *
 * 按钮横向内边距 24dp，相邻两颗之间视觉缝 48dp；组间取 96dp（父亲 2026-10-06 晚）。
 */
private const val GROUP_GAP_DP = 96

/** 控制条面板像素宽（必须与原生 kOsdPxW 一致：光柱坐标是按面板像素给的） */
const val OSD_PANEL_W = 3600f

/**
 * 控制条状态：主线程（Activity）写，面板界面读。
 *
 * 用 Compose 的 mutableStateOf，界面自动跟着播放进度/播放态刷新。
 */
class OsdState {
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var speed by mutableStateOf(1f)

    /** 播放/暂停按钮显示的图标：跟真实播放态走 */
    val playIcon: ImageVector get() = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow

    /**
     * 每颗按钮在控制条面板里的横向中心位置（归一化 0…1）。
     *
     * 菜单面板与控制条等宽，所以这个归一化值可以直接拿来把菜单卡片对齐到按钮正上方 ——
     * 界面自己量，不靠代码里另算一份布局（改了间距也不会对不上）。
     */
    val buttonX = mutableStateMapOf<OsdButton, Float>()

    /** 每颗按钮的横向范围（归一化左、右），光柱悬停判定用 */
    val buttonRange = mutableStateMapOf<OsdButton, Pair<Float, Float>>()

    /** 光柱在控制条里的横向位置（归一化 0…1；负值 = 光柱不在这块面板上） */
    var pointerNx by mutableStateOf(-1f)

    /** 当前打开的菜单对应哪颗按钮（那颗按钮常亮）；没开菜单时是 null */
    var activeMenuButton by mutableStateOf<OsdButton?>(null)

    /** 每颗按钮点了之后干什么（Agent 侧接 ExoPlayer） */
    var onButton: ((OsdButton) -> Unit)? = null

    // ── 进度条拖动（父亲 2026-10-06：激光瞄准 + 扣扳机直接拖）──
    /** 拖到哪儿了（0…1，松手时用它跳转） */
    var seekFrac by mutableStateOf(0f)

    /** 拖动中：只更新显示，不真跳（跟手） */
    var onSeekPreview: ((Float) -> Unit)? = null

    /** 松手 / 直接点进度条：真跳转 */
    var onSeekCommit: ((Float) -> Unit)? = null
}

/** 毫秒 → mm:ss（超过一小时给 h:mm:ss） */
fun osdTimeText(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 控制条界面本体（跑在控制条那台虚拟显示器上） */
@Composable
fun PlayerOsdBar(state: OsdState) {
    // 按钮位置按**整块面板**归一化（父 Row 有内边距，拿它当基准会有偏差）
    val panelWpx = LocalWindowInfo.current.containerSize.width
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(56.dp))
            // 玻璃质感：上亮下暗
            .background(
                Brush.verticalGradient(listOf(Color(0xF0252830), Color(0xE6131518))),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color(0x3DFFFFFF), Color(0x08FFFFFF)),
                ),
                shape = RoundedCornerShape(56.dp),
            )
            .padding(horizontal = 88.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        // ① 进度行：左侧「已播 / 总时长」（固定宽度，进度走动时右边不会抖）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${osdTimeText(state.positionMs)} / ${osdTimeText(state.durationMs)}",
                color = Color(0xFFEAF6EE),
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(216.dp),
            )
            OsdProgress(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 32.dp),
            )
        }

        // 进度行与按钮行之间的留白（父亲 2026-10-06：间距 +1/3）
        Spacer(modifier = Modifier.height(32.dp))

        // ② 按钮行：左组贴左 · 中组居中 · 右组贴右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OSD_LEFT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            Spacer(modifier = Modifier.width(GROUP_GAP_DP.dp))
            OSD_CENTER_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            Spacer(modifier = Modifier.width(GROUP_GAP_DP.dp))
            OSD_RIGHT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
        }
    }
}

/**
 * 进度条：可以点，也可以按住拖（父亲 2026-10-06）。
 *
 * 热区比轨道高得多（34dp），激光好瞄；拖动过程中只更新左侧时间与已播长度，
 * 松手（或直接点一下）才真的跳。
 */
@Composable
private fun OsdProgress(state: OsdState, modifier: Modifier = Modifier) {
    val frac = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = modifier
            .height(34.dp)
            .pointerInput(state.durationMs) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    state.seekFrac = f
                    state.onSeekCommit?.invoke(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { state.onSeekCommit?.invoke(state.seekFrac) },
                    onDragCancel = { state.onSeekCommit?.invoke(state.seekFrac) },
                ) { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    state.seekFrac = f
                    state.onSeekPreview?.invoke(f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Color(0x2EFFFFFF), RoundedCornerShape(6.dp)),
        )
        // 已播：青绿渐变
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(12.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF2FD57C), Color(0xFF8EF7C0))),
                    RoundedCornerShape(6.dp),
                ),
        )
        // 手柄：一圈光晕 + 白心，拖到哪儿一眼能看出来
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(34.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0x3D2FD57C), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun OsdButtonView(button: OsdButton, state: OsdState, panelWpx: Int) {
    val selected = state.activeMenuButton == button
    val range = state.buttonRange[button]
    val hovered = !selected && range != null && state.pointerNx >= 0f &&
        state.pointerNx >= range.first && state.pointerNx <= range.second
    val isPlayPause = button == OsdButton.PLAY_PAUSE

    val background = when {
        selected -> Color(0x3D2FD57C)
        hovered -> Color(0x24FFFFFF)
        else -> Color.Transparent
    }
    val tint = when {
        selected -> Color(0xFF8EF7C0)
        hovered -> Color.White
        else -> Color(0xFFE3E3E3)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { coords ->
                val w = if (panelWpx > 0) {
                    panelWpx
                } else {
                    coords.parentLayoutCoordinates?.size?.width ?: 0
                }
                if (w > 0) {
                    val left = coords.positionInRoot().x
                    val width = coords.size.width.toFloat()
                    state.buttonX[button] = ((left + width / 2f) / w).coerceIn(0f, 1f)
                    state.buttonRange[button] = (left / w).coerceIn(0f, 1f) to
                        ((left + width) / w).coerceIn(0f, 1f)
                }
            }
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable { state.onButton?.invoke(button) }
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (isPlayPause) state.playIcon else button.icon,
            contentDescription = button.label,
            // 播放/暂停是主按钮：白色实心圆 + 深色图标
            tint = if (isPlayPause) Color(0xFF101214) else tint,
            modifier = Modifier
                .size(58.dp)
                .then(
                    if (isPlayPause) {
                        Modifier
                            .background(Color(0xFFF1F5F7), CircleShape)
                            .padding(9.dp)
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when (button) {
                OsdButton.SPEED -> "速度 ${"%.1f".format(state.speed)}x"
                OsdButton.PLAY_PAUSE -> if (state.playing) "暂停" else "播放"
                else -> button.label
            },
            color = when {
                selected -> Color(0xFF8EF7C0)
                hovered -> Color.White
                else -> Color(0xFFD8D8D8)
            },
            fontSize = 22.sp,
        )
    }
}
