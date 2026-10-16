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
import androidx.compose.material.icons.filled.SkipNext
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
 * 2026-10-12 父亲加第 13 颗：**「跳过片头」**，条件显示 —— 只在片头范围内出现
 * （挨着倍速，顶在右组最左），片头一过就消失，控制条回到上面那一版。
 * 它在场时两处组间缝从 75px 收到 40px（面板宽按 12 颗算死，塞不下第 13 颗的 164px）。
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
    /*
     * 「跳过片头」（父亲 2026-10-12）。**条件按钮**：只在片头范围内出现
     * （片头结束自动收起），所以它不在常驻的 OSD_RIGHT_GROUP 里，
     * 而是由启动时的 OSD_RIGHT_GROUP_SKIP 顶在右组最左、紧邻倍速。
     */
    SKIP_INTRO("跳过片头", Icons.Filled.SkipNext),
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
/** 空闲（没在播放）时也能点的按钮：选片、退出，以及「更多」—— 更多里第一件是「选座」
 *  （父亲 2026-10-10：没播放时也想能点选座） */
val OSD_ALWAYS_ENABLED = setOf(OsdButton.PICK, OsdButton.EXIT, OsdButton.MORE)

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
 * 片头出现时的右组：最左边多一颗「跳过片头」（父亲 2026-10-12：挨着倍速）。
 *
 * 只多这一颗按钮、不动常驻的 12 颗，是刻意的 —— 片头一过它就消失，
 * 控制条回到父亲 2026-10-06 定稿的那一版。
 */
val OSD_RIGHT_GROUP_SKIP = listOf(OsdButton.SKIP_INTRO) + OSD_RIGHT_GROUP

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

/**
 * 「跳过片头」在场时用的组间缝（父亲 2026-10-12）。
 *
 * 面板宽 2331 是**按 12 颗按钮算死的**（内容正好 2103 = 2331 − 114×2），
 * 一行里塞第 13 颗按钮会多出 164px：EXIT 会被顶出面板右边缘，
 * 而面板宽与原生 kOsdPxW、银幕几何绑在一起，不能为这个动。
 * 所以第 13 颗出现时把两处组间缝从 75 收到 40（共省 70px），
 * 行内容 2197px、右边缘落在 2311（面板 2331），13 颗全在面板内、右侧仍留 20px；
 * 组与组之间仍有 40px 的缝（组内缝是 5px），三组的结构看得出来。
 * 片头一过按钮消失，缝回到 75。
 */
private const val GROUP_GAP_SKIP_PX = 40f

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

/**
 * 「跳过片头」图标的颜色（父亲 2026-10-12）。
 *
 * 它旁边就是「快进10」，两颗都是"往前跳"的箭形图标 —— 白色图标容易被当成快进。
 * 用菜单里已经在用的那个浅蓝（PlayerMenuPanel 的 0xFF4FC3F7，那里表示"当前这一项"），
 * 不引新色相；与白色普通按钮、青绿选中态都区分得开，暗底上对比度约 11:1。
 */
private val SKIP_INTRO_TINT = Color(0xFF4FC3F7)

/** px 转 dp（Compose 只收 dp） */
private fun px(v: Float) = (v / PX_PER_DP).dp

/** 控制条面板像素宽（必须与原生 kOsdPxW 一致：光柱坐标是按面板像素给的） */
const val OSD_PANEL_W = 2331f

/** 控制条面板高度（像素）—— 与原生 kOsdPxH 一致，光点纵向归一化要用它 */
const val OSD_PANEL_H = 474f

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

    /**
     * 「跳过片头」这颗**条件按钮**显不显示（父亲 2026-10-12）。
     *
     * 主线程按播放位置写：位置落进片头范围 → true；片头结束 / 停止播放 / 换片 → false。
     * 界面只在 true 时把它排进右组（它一出现，整行按钮会重排一次，
     * 组间缝同时从 75px 收到 40px，见 GROUP_GAP_SKIP_PX）。
     */
    var showSkipIntroButton by mutableStateOf(false)

    /**
     * 银幕上正在放网页时，那个站点的名字（父亲 2026-10-13）。
     *
     * 网页不是 Emby 的"片源"，所以它不进 hasPlayback —— 于是银幕在放 B 站时，
     * 控制条原来显示「什么都没在播」，而声音明明是网页在放。
     * 现在 openWeb 时写站点名（如"哔哩哔哩"），关掉网页清空，
     * 标题行就显示「正在播放：哔哩哔哩」。
     */
    var webSiteName by mutableStateOf("")

    /** 是否正在拖进度条（父亲 2026-10-13）：拖动中条与左侧时间跟手，松手回到播放位置 */
    var seekDragging by mutableStateOf(false)

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

    /**
     * 光柱在控制条里的纵向位置（归一化 0…1；负值 = 不在面板上）。
     * 悬停必须连纵向一起判（父亲 2026-10-07：光点在标题行 / 进度行横向移动时，
     * 下面那排按钮会挨个亮起来，看着像扫动）。
     */
    var pointerNy by mutableStateOf(-1f)

    /** 按钮行的纵向范围（归一化），由界面自己量；悬停判定用它排除标题行与进度行 */
    var buttonRowTop by mutableStateOf(0f)
    var buttonRowBottom by mutableStateOf(0f)

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
    /*
     * 面板高度也要在 **Composable 作用域**里先读出来：LocalWindowInfo.current 是
     * @Composable 属性，直接写进 onGloballyPositioned 的 lambda 会编译报错
     * （"@Composable invocations can only happen from the context of a @Composable function"）。
     * 按钮行的纵向范围要用它归一化。
     */
    val panelHpx = LocalWindowInfo.current.containerSize.height
    /*
     * 片头按钮（条件按钮）在场时：右组多一颗、两处组间缝一起收紧。
     * 两者必须成对改 —— 只加按钮不收缝，EXIT 会被顶出面板右边缘（见 GROUP_GAP_SKIP_PX）。
     */
    val showSkipIntro = state.showSkipIntroButton
    val rightGroup = if (showSkipIntro) OSD_RIGHT_GROUP_SKIP else OSD_RIGHT_GROUP
    val groupGapPx = if (showSkipIntro) GROUP_GAP_SKIP_PX else GROUP_GAP_PX
    val groupGap = px(groupGapPx)
    /*
     * 按钮行的左右边距**按内容居中算**（父亲 2026-10-13 修）。
     *
     * 原来写死 114：12 颗时内容 2103px，两侧正好各 114；第 13 颗在场时内容涨到 2192px，
     * 左仍 114、右只剩 20 —— 父亲看到的就是「左边 114、右边只剩 20」的不对称。
     * 现在按「(面板宽 − 内容宽) / 2」算，12 颗时算出来仍是 114（和原来一模一样），
     * 13 颗时两侧各 69.5px，居中不再偏。
     */
    val buttonCount = OSD_LEFT_GROUP.size + OSD_CENTER_GROUP.size + rightGroup.size
    val innerGapCount = (OSD_LEFT_GROUP.size - 1) + (OSD_CENTER_GROUP.size - 1) + (rightGroup.size - 1)
    val buttonRowSide = px(
        ((OSD_PANEL_W - (buttonCount * BUTTON_BOX_PX + innerGapCount * BUTTON_GAP_PX + 2 * groupGapPx)) / 2f)
            .coerceAtLeast(0f)
    )
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
                 * 标题行三种状态（父亲 2026-10-13）：
                 *  · 银幕在放网页 → 「正在播放：哔哩哔哩」（网页不是 Emby 片源，hasPlayback 是 false，
                 *    以前这里就空着，看着像"什么都没在播"）；
                 *  · 真的有片子在播 → 片名；
                 *  · 什么都没播 → 「未在播放」（原来留一整行空白，不是说明性空态）。
                 * 右侧时间不受影响，照常显示。
                 */
                text = when {
                    /*
                     * 【2026-10-16 父亲定的口径：不分优先级】
                     * 正在播什么就显示什么 —— 标题只有一个来源，谁在播谁写它：
                     * Emby 片子写片名，网页流写"站点-片名"。
                     */
                    state.title.isNotEmpty() -> "正在播放：${state.title}"
                    state.hasPlayback -> state.title
                    else -> "未在播放"
                },
                color = if (state.hasPlayback || state.title.isNotEmpty()) {
                    Color(0xFFFFFFFF)
                } else {
                    // 未播放时压暗一档，别和真在播的标题一个分量
                    Color(0xFF9E9E9E)
                },
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
                // 未播放时显示 --（父亲 2026-10-07：比 00:00 更好懂）
                text = if (state.hasPlayback) osdTimeText(state.positionMs) else "--",
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
                text = if (state.hasPlayback) {
                    "-" + osdTimeText((state.durationMs - state.positionMs).coerceAtLeast(0L)) +
                        " / " + osdTimeText(state.durationMs)
                } else {
                    "-- / --"
                },
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
                .onGloballyPositioned { coords ->
                    /*
                     * 把按钮行的纵向范围报给 OsdState（父亲 2026-10-07）：
                     * 悬停判定要排除上面的标题行与进度行，否则光点在它们上面横向移动时，
                     * 这排按钮会挨个亮起来。
                     */
                    if (panelHpx > 0) {
                        state.buttonRowTop =
                            (coords.positionInRoot().y / panelHpx).coerceIn(0f, 1f)
                        state.buttonRowBottom =
                            ((coords.positionInRoot().y + coords.size.height) / panelHpx)
                                .coerceIn(0f, 1f)
                    }
                }
                .padding(horizontal = buttonRowSide),
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
            Spacer(modifier = Modifier.width(groupGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(px(BUTTON_GAP_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OSD_CENTER_GROUP.forEach { OsdButtonView(it, state, panelWpx) }
            }
            Spacer(modifier = Modifier.width(groupGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(px(BUTTON_GAP_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rightGroup.forEach { OsdButtonView(it, state, panelWpx) }
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
    /*
     * 拖动中进度条与左侧时间要**跟手**（父亲 2026-10-13）。
     *
     * 原来 seekFrac 只写不读：拖的时候条和游标仍按播放位置 positionMs 画，
     * 手指移了、条不动（注释里写的"跟手"其实没生效）。
     * 现在拖动中用 seekFrac，松手立刻回到播放位置。
     */
    val frac = if (state.seekDragging) {
        state.seekFrac.coerceIn(0f, 1f)
    } else if (duration > 0L) {
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
                    onDragStart = { state.seekDragging = true },
                    onDragEnd = {
                        state.onSeekCommit?.invoke(state.seekFrac)
                        state.seekDragging = false
                    },
                    onDragCancel = {
                        state.onSeekCommit?.invoke(state.seekFrac)
                        state.seekDragging = false
                    },
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
    /*
     * 「跳过片头」是条件按钮（父亲 2026-10-12）：片头不在范围里就整颗不画。
     * 右组列表已经按这个状态换过了，这一条是双保险 —— 别处误排进来也不会显示。
     */
    if (button == OsdButton.SKIP_INTRO && !state.showSkipIntroButton) return
    val selected = state.activeMenuButton == button
    val range = state.buttonRange[button]
    /*
     * 悬停要**横向 + 纵向**都落在按钮行上（父亲 2026-10-07）。
     * 只看横向时，光点在标题行 / 进度行横向扫过，这排按钮会挨个亮 —— 就是"扫动"。
     */
    val hovered = !selected && range != null && state.pointerNx >= 0f &&
        state.pointerNy >= state.buttonRowTop && state.pointerNy <= state.buttonRowBottom &&
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
        // 跳过片头：暖色，跟旁边的「快进10」分开（父亲 2026-10-12）
        button == OsdButton.SKIP_INTRO -> SKIP_INTRO_TINT
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
