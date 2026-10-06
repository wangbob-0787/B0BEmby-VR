package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放控制条（VR 原生播放屏的 OSD）。
 *
 * 形态：一块架在观影者身前近场的矮条（2560×300 像素的虚拟显示器，与主面板同一套
 * VirtualDisplay + SurfaceTexture 机制）。窗口背景透明，圆角外透出影院背景。
 *
 * 2026-10-06 父亲定稿的 12 颗按钮，从左到右分三组：
 *  - 左组（贴左）：字幕 · 弹幕
 *  - 中组（在左右两组之间的剩余空间里居中）：快退 10 · 播放/暂停 · 快进 10
 *  - 右组（贴右）：播放速度 · 选集 · 选片 · 信息 · 演职人员 · 更多 · 退出
 *
 * 图标全部取自同一个图标库（Material Icons），风格统一。
 * 原来的画面调整三键（亮度/对比度/饱和度…）按父亲要求删除。
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
            .padding(horizontal = 40.dp, vertical = 16.dp),
    ) {
        // ① 进度行：当前时间 + 进度条 + 总时长
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = osdTimeText(state.positionMs),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 40.dp)
                    .height(8.dp)
                    .background(Color(0x40FFFFFF), RoundedCornerShape(4.dp)),
            ) {
                val frac = if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = osdTimeText(state.durationMs),
                color = Color(0xFFBDBDBD),
                fontSize = 24.sp,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ② 按钮行：左组贴左 · 中组在剩余空间居中 · 右组贴右
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (button == OsdButton.PLAY_PAUSE) state.playIcon else button.icon,
            contentDescription = button.label,
            tint = if (selected) Color.White else Color(0xFFEDEDED),
            modifier = Modifier.size(46.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
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
