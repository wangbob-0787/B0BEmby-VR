package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
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
 * 形态：一块架在观影者身前近场的矮条（2880×253 像素的虚拟显示器，与主面板同一套
 * VirtualDisplay + SurfaceTexture 机制）。窗口背景透明，圆角外透出影院背景。
 *
 * 2026-10-06 父亲定稿的 12 颗按钮，从左到右分三组：
 *  - 左组（贴左）：字幕 · 弹幕
 *  - 中组（在左右两组之间的剩余空间里居中）：快退 10 · 播放/暂停 · 快进 10
 *  - 右组（贴右）：播放速度 · 选集 · 选片 · 信息 · 演职人员 · 更多 · 退出
 *
 * 图标全部取自同一个图标库（Material Icons），风格统一。
 * 原来的画面调整三键（亮度/对比度/饱和度…）按父亲要求删除。
 *
 * 2026-10-06 下午父亲又定：整条加宽、高度缩短 1/4、内容左右留白加大，
 * 左侧显示「已播 / 总时长」，进度条可以激光瞄准 + 扣扳机直接拖。
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

/** 中组：夹在左右两组之间的剩余空间里居中 */
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

    /** 当前打开的菜单对应哪颗按钮（那颗按钮高亮）；没开菜单时是 null */
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
            // 父亲 2026-10-06 定：整条倒圆角、底色近黑（要暗到纯黑只留一点灰）
            .clip(RoundedCornerShape(56.dp))
            .background(Color(0xFF141518))
            /*
             * 左右留白再加大（父亲 2026-10-06 下午），上下留白相等：
             * 进度行贴上边、按钮行贴下边，两边的留白一样宽。
             */
            .padding(horizontal = 72.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ① 进度行：左侧「已播 / 总时长」+ 进度条（可拖）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${osdTimeText(state.positionMs)} / ${osdTimeText(state.durationMs)}",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            OsdProgress(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 36.dp),
            )
        }

        // ② 按钮行：左组贴左 · 中组在剩余空间居中 · 右组贴右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OSD_LEFT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            Spacer(modifier = Modifier.weight(1f))
            OSD_CENTER_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            Spacer(modifier = Modifier.weight(1f))
            OSD_RIGHT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
        }
    }
}

/**
 * 进度条：可以点，也可以按住拖（父亲 2026-10-06 下午）。
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
                .height(10.dp)
                .background(Color(0x40FFFFFF), RoundedCornerShape(5.dp)),
        )
        // 已播
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(10.dp)
                .background(Color(0xFF4CAF50), RoundedCornerShape(5.dp)),
        )
        // 拖动手柄：跟着已播的右端走，方便看清拖到哪儿了
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(34.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(Color.White, RoundedCornerShape(9.dp)),
            )
        }
    }
}

@Composable
private fun OsdButtonView(button: OsdButton, state: OsdState, panelWpx: Int) {
    val selected = state.activeMenuButton == button
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
                    val cx = coords.positionInRoot().x + coords.size.width / 2f
                    state.buttonX[button] = (cx / w.toFloat()).coerceIn(0f, 1f)
                }
            }
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color(0x2EFFFFFF) else Color.Transparent)
            .clickable { state.onButton?.invoke(button) }
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (button == OsdButton.PLAY_PAUSE) state.playIcon else button.icon,
            contentDescription = button.label,
            tint = if (selected) Color.White else Color(0xFFEDEDED),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = when (button) {
                OsdButton.SPEED -> "速度 ${"%.1f".format(state.speed)}x"
                OsdButton.PLAY_PAUSE -> if (state.playing) "暂停" else "播放"
                else -> button.label
            },
            color = if (selected) Color.White else Color(0xFFDDDDDD),
            fontSize = 17.sp,
        )
    }
}
