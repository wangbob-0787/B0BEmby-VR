package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 未播放时仍然可用的按钮（父亲 2026-10-07）。
 *
 * 只留与"当前这部片"无关的入口 —— 选片（换一部看）与退出。
 * 其余按钮在没有片子可操作时置灰且点不动。
 */
val OSD_ALWAYS_ENABLED = setOf(OsdButton.PICK, OsdButton.EXIT)

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
 * 间距（父亲 2026-10-06 晚按图定稿，全部用像素算）：
 *
 *  · 按钮框之间的缝 5px（框是 159 的正方形，图标 87 居中，所以图标到图标的
 *    距离是 5 + 36 + 36 = 77px）；
 *  · 组与组之间 75px；
 *  · 按钮行左右各留 114px，即图标距控制条边缘 114 + 36 = 150px（与标题行、进度行对齐）。
 *
 * 面板宽 2331 = 114 + 12 框 × 159 + 组内缝 9×5 + 组间缝 2×75 + 114，内容正好铺满。
 */
private const val BUTTON_GAP_PX = 5f
private const val GROUP_GAP_PX = 75f
private const val BUTTON_ROW_SIDE_PX = 114f

/** 面板上下边距（像素，父亲 2026-10-06 晚定） */
private const val PANEL_PAD_Y_PX = 75f

/**
 * 按钮框边长（像素）：正方形，与原来按钮框的宽度一致。
 *
 * 父亲 2026-10-06 晚定稿 —— 按钮下方的说明文字去掉后，12 颗按钮可以做成一样大的
 * 正方形：图标 87（58dp）居中，四周各留 36（24dp）。顺带把「说明文字被面板底边裁掉」
 * 那个问题一起消掉（行高不再由文字行高决定）。
 */
private const val BUTTON_BOX_PX = 159f

/** 控制条面板 240dpi：1dp = 1.5px */
private const val PX_PER_DP = 1.5f

/** 按钮里的图标边长（像素）：58dp */
private const val ICON_PX = 87f

/** px 转 dp（Compose 只收 dp） */
private fun px(v: Float) = (v / PX_PER_DP).dp

/** 控制条面板像素宽（必须与原生 kOsdPxW 一致：光柱坐标是按面板像素给的） */
const val OSD_PANEL_W = 2331f

/**
 * 控制条状态：主线程（Activity）写，面板界面读。
 *
 * 用 Compose 的 mutableStateOf，界面自动跟着播放进度/播放态刷新。
 */
class OsdState {
    var title by mutableStateOf("")

    /** 控制条第一行右侧的当前时间（YYYY-MM-DD HH:MM:SS） */
    var nowClock by mutableStateOf("")
    var playing by mutableStateOf(false)

    /**
     * 当前有没有正在播放的片子（父亲 2026-10-07 定的生命周期）。
     *
     * · 刚开 app / 停止播放 / 播完 → false：「正在播放：…」整行留空，播放相关按钮置灰
     * · 起播（从头播或续播）到**第一帧** → true：显示片名，按钮恢复可用
     * · 播放中暂停 → **不变**（只要没切片，「正在播放」就不消失）
     * · 切片 → 先变 false（旧片信息立刻消失），新片第一帧再变 true
     *
     * 注意：标题行右侧的年月日时分秒**不受它影响**，永远显示当前时间。
     */
    var hasPlayback by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)

    /** 已缓冲到的位置（进度条上那段浅色，照电视版） */
    var bufferedMs by mutableStateOf(0L)
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
            /*
             * 水平内边距不放在这里：三行的水平边距不一样（父亲 2026-10-06 晚定稿的图）——
             *   · 标题行、进度行：左右各 150px
             *   · 按钮行：左右各 114px（= 150 − 36 的按钮内边距），这样**图标**视觉上
             *     正好落在 150px，而不是按钮框落在 150px、图标却缩进一格
             * 上下各 50px。
             */
            .padding(vertical = px(PANEL_PAD_Y_PX)),
        verticalArrangement = Arrangement.Top,
    ) {
        // ① 标题行：左「正在播放：片名 第X集 集名」 右「当前时间」（父亲 2026-10-06 晚定）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 100.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                /*
                 * 只有真的有片子在播时才显示这行（父亲 2026-10-07）：
                 * 未播放时留空 —— 停止播放 / 切片起步阶段都是这个状态。
                 * 右侧时间不受影响，照常显示。
                 */
                text = if (state.hasPlayback) state.title else "",
                color = Color(0xFFFFFFFF),
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.nowClock,
                color = Color(0xFF9E9E9E),
                fontSize = 24.sp,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ② 进度行：左「已播时长」 右「剩余时长 / 总时长」（父亲 2026-10-06 晚定）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 100.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * 两侧时间**不加固定宽**（父亲 2026-10-06 晚）：原来装在固定框里，
             * 字少的时候框内空一大截，看着离进度条很远。现在文字自然宽，
             * 与进度条各留 10px。数字用等宽（tnum），宽度不随内容跳，进度条也不抖。
             */
            Text(
                text = osdTimeText(state.positionMs),
                color = Color(0xFFFFFFFF),
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            OsdProgress(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 7.dp),
            )
            Text(
                text = "-" + osdTimeText((state.durationMs - state.positionMs).coerceAtLeast(0L)) +
                    " / " + osdTimeText(state.durationMs),
                color = Color(0xFFFFFFFF),
                fontSize = 26.sp,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }

        // 进度行与按钮行之间的留白
        Spacer(modifier = Modifier.height(20.dp))

        /*
         * ② 按钮行：左组贴左 · 中组居中 · 右组贴右。
         *
         * 必须用 SpaceBetween 把三组撑开 —— 原来只是「左组 + 96dp + 中组 + 96dp + 右组」
         * 按内容从左排，右边会空出一大块（父亲 2026-10-06 晚：「内容按缩短的尺寸布置，
         * 控制条本身没缩短，像两根叠一起」），退出按钮也就贴不到右边。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = px(BUTTON_ROW_SIDE_PX)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * 组内按钮框之间留 5px、组与组之间留 50px，都是固定值，不用 SpaceBetween：
             * 面板宽就是按「114 + 框总宽 + 组内缝 + 组间缝 + 114」算准的，内容正好铺满，
             * 右组自然贴右。（2026-10-06 晚父亲校准：所谓「按钮间距」是矩形框的缝。）
             */
            Row(
                horizontalArrangement = Arrangement.spacedBy(px(BUTTON_GAP_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OSD_LEFT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            }
            Spacer(modifier = Modifier.width(px(GROUP_GAP_PX)))
            Row(
                horizontalArrangement = Arrangement.spacedBy(px(BUTTON_GAP_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OSD_CENTER_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            }
            Spacer(modifier = Modifier.width(px(GROUP_GAP_PX)))
            Row(
                horizontalArrangement = Arrangement.spacedBy(px(BUTTON_GAP_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OSD_RIGHT_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            }
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
    val duration = state.durationMs
    val frac = if (duration > 0L) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufFrac = if (duration > 0L) {
        (state.bufferedMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    /*
     * 复刻电视版播放页的进度条（父亲 2026-10-06 晚）：细轨道 + 一段浅色缓冲 +
     * 已播实色 + **一根绿色竖线游标**（电视版实测就是一根细竖条，不是圆点）。
     * 尺寸按 VR 面板放大一档，1 米外才看得清。
     */
    BoxWithConstraints(
        modifier = modifier
            .height(34.dp)
            .pointerInput(duration) {
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
    ) {
        val barWidth = maxWidth
        // 轨道（很细，电视版是 2dp）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .align(Alignment.CenterStart)
                .background(Color(0x33FFFFFF), RoundedCornerShape(3.dp)),
        )
        if (duration > 0L) {
            // 缓冲段：浅一档的白（电视版白 22%）
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufFrac)
                    .height(5.dp)
                    .align(Alignment.CenterStart)
                    .background(Color(0x3DFFFFFF), RoundedCornerShape(3.dp)),
            )
            // 已播段：实色
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(5.dp)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF2FD57C), RoundedCornerShape(3.dp)),
            )
            // 游标：绿色竖线（电视版同款）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = barWidth * frac - 3.dp)
                    .width(6.dp)
                    .height(28.dp)
                    .background(Color(0xFF2FD57C), RoundedCornerShape(3.dp)),
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
    /*
     * 未播放时，播放相关的按钮置灰且点不动（父亲 2026-10-07）。
     * 只留「选片」「退出」这类与当前片子无关的入口 —— 置灰的按钮点了没反应，
     * 比点了什么都不发生更清楚。
     */
    val enabled = state.hasPlayback || button in OSD_ALWAYS_ENABLED

    val background = when {
        !enabled -> Color.Transparent
        selected -> Color(0x3D2FD57C)
        hovered -> Color(0x24FFFFFF)
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> Color(0x59FFFFFF)          // 35% 白：看得见，但明显弱于可用态
        selected -> Color(0xFFFFFFFF)
        hovered -> Color.White
        else -> Color(0xFFFFFFFF)
    }

    Box(
        contentAlignment = Alignment.Center,
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
            .size(px(BUTTON_BOX_PX))
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            // 置灰的按钮点不动（父亲 2026-10-07）
            .clickable(enabled = enabled) { state.onButton?.invoke(button) },
    ) {
        Icon(
            imageVector = if (isPlayPause) state.playIcon else button.icon,
            contentDescription = button.label,
            // 播放/暂停是主按钮：白色实心圆 + 深色图标；未播放时整体压暗
            tint = if (isPlayPause) {
                if (enabled) Color(0xFF101214) else Color(0x8A101214)
            } else {
                tint
            },
            modifier = Modifier
                .size(px(ICON_PX))
                .then(
                    if (isPlayPause) {
                        Modifier
                            .background(
                                if (enabled) Color(0xFFF1F5F7) else Color(0x59F1F5F7),
                                CircleShape,
                            )
                            .padding(9.dp)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
