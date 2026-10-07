package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 播放控制条的展开菜单（2026-10-06 父亲定稿）。
 *
 * ## 形态
 *
 * 控制条本身不变（3600×340 的矮条）。菜单是**另一块面板**，架在控制条正上方：
 *  - 窄菜单（字幕 / 弹幕 / 速度 / 选集 / 更多 / 音频 / 质量 / 模式 / 缓冲）：
 *    一张卡片，横向对齐到**触发它的那颗按钮**正上方。
 *  - 宽菜单（信息 / 演职人员）：卡片宽度从控制条最左铺到最右。
 *
 * 面板窗口背景透明，只有卡片本身有底色，其余透出影院画面。
 *
 * ## 层级
 *
 * 「更多」是二级入口：点开先出四项（音频选择 / 视频质量 / 播放模式 / 缓冲设置），
 * 点其中一项换成对应菜单，仍在「更多」按钮正上方。跳过片头按父亲要求取消。
 */
enum class MenuKind(val title: String) {
    MORE("更多"),
    AUDIO("音频选择"),
    QUALITY("视频质量"),
    MODE("播放模式"),
    BUFFER("缓冲设置"),
    SUBTITLE("字幕"),
    DANMAKU("弹幕设置"),
    SPEED("播放速度"),
    EPISODES("选集"),
    INFO("信息"),
    CAST("演职人员"),
}

/**
 * 二级菜单（从控制条「更多」里点进去的那四项，父亲 2026-10-06）。
 *
 * 选中后**不关菜单**：B 键（返回）从二级退回「更多」那一级，再按一次才关掉。
 */
val MenuKind.isSubMenu: Boolean
    get() = this == MenuKind.AUDIO || this == MenuKind.QUALITY ||
        this == MenuKind.MODE || this == MenuKind.BUFFER

/** 菜单里的一行（轨道 / 集数这类：显示名 + 是否当前选中） */
data class MenuRowItem(val label: String, val selected: Boolean, val payload: Int = -1)

/** 演职人员一项 */
data class PersonItem(val name: String, val role: String, val avatarUrl: String?)

/** 信息菜单要显示的内容 */
data class MediaInfoView(
    /** 季集行：第 X 季 第 Y 集 · 集名（剧集才有，父亲 2026-10-06 晚） */
    val episodeLine: String = "",
    /** 分级（如 TV-14 / PG-13） */
    val officialRating: String = "",
    /** 技术行：视频轨 / 分辨率 / 音轨，照电视版信息面板（父亲 2026-10-06 晚） */
    val techLine: String = "",
    val title: String,
    val year: String,
    val runtime: String,
    val rating: String,
    val genres: String,
    val overview: String,
)

/** 缓冲设置菜单的当前值（毫秒） */
data class BufferView(
    val presetIndex: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
)

/**
 * 菜单状态：主线程写，菜单面板界面读。
 *
 * 同一时刻只有一个菜单（[kind]），[anchor] 是要对齐的那颗控制条按钮。
 */
class MenuState {
    var kind by mutableStateOf<MenuKind?>(null)

    /** 对齐到哪颗按钮正上方（按按钮在控制条里的归一化横向位置对齐） */
    var anchor by mutableStateOf<OsdButton?>(null)

    /*
     * 列表滚动（父亲 2026-10-06 晚：选集和演职人员滚不动）。
     * 界面层把摇杆的上下推量累加进 scrollDelta，并让 scrollTick 自增；
     * 列表消费后清零 —— 用「tick 变化」触发，避免每帧都滚。
     */
    var scrollDelta: Float = 0f
    var scrollTick: Int by mutableStateOf(0)

    /** 列表要滚多少（正数 = 内容往上走，看到后面的项） */
    fun requestScroll(dy: Float) {
        if (dy == 0f) return
        scrollDelta += dy
        scrollTick++
    }

    // ── 各菜单的数据（由 Activity 填） ──
    var speed by mutableStateOf(1f)
    var quality by mutableStateOf(0)
    var playMode by mutableStateOf(0)
    var danmakuOn by mutableStateOf(true)
    var danmakuScale by mutableStateOf(1f)

    var subtitleTracks by mutableStateOf<List<MenuRowItem>>(emptyList())
    var audioTracks by mutableStateOf<List<MenuRowItem>>(emptyList())
    var episodes by mutableStateOf<List<MenuRowItem>>(emptyList())
    var people by mutableStateOf<List<PersonItem>>(emptyList())
    var info by mutableStateOf<MediaInfoView?>(null)
    var buffer by mutableStateOf<BufferView?>(null)

    /** 服务器地址（演职人员头像用） */
    var serverUrl by mutableStateOf("")

    /** 信息菜单里的海报地址（父亲 2026-10-06：信息要带海报） */
    var posterUrl by mutableStateOf<String?>(null)

    /** 菜单里有东西被选中：(菜单类型，行号) */
    var onSelect: ((MenuKind, Int) -> Unit)? = null

    /** 请求关闭菜单 */
    var onClose: (() -> Unit)? = null

    /** 请求返回上一级（B 键 / 卡片右上角「返回」）：二级 → 回「更多」，一级 → 关掉菜单 */
    var onBack: (() -> Unit)? = null

    /** 播放进度之类不在这里，控制条自己刷 */
    fun visible(): Boolean = kind != null
}

/** 倍速档位（与电视版一致） */
val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f, 4.0f)

/** 视频质量档位：值 = 码率上限（0 表示原画不转码） */
val QUALITY_STEPS = listOf(
    0 to "原画（不转码）",
    10_000_000 to "1080p（10 兆）",
    5_000_000 to "1080p（5 兆）",
    1_000_000 to "1080p（1 兆·省流）",
)

/** 播放模式 */
val PLAY_MODE_STEPS = listOf("列表循环", "单集循环", "播完停止")

/** 弹幕字号 */
val DANMAKU_SCALES = listOf(0.8f to "小", 1.0f to "标准", 1.3f to "大", 1.6f to "特大")

/** 缓冲档位：三档够用（电视版那一页参数太细，遥控器上调不动） */
val BUFFER_PRESETS = listOf(
    Triple("标准", 15_000, 50_000),
    Triple("较大", 30_000, 90_000),
    Triple("最大", 60_000, 180_000),
)

/**
 * 菜单面板像素尺寸（与控制条等宽 → 归一化横向坐标可以直接复用）。
 *
 * 高度 = 控制条的高度（父亲 2026-10-07：菜单面板的厚度改成和控制条一样）。
 * 原生侧 openxr_renderer.cpp 的 kMenuPxH 必须同步改，否则物理尺寸与光柱命中判定对不上。
 */
const val MENU_PANEL_W = 2331
const val MENU_PANEL_H = 474

/** 窄卡片宽度（像素） */
private const val CARD_W = 920f
/**
 * 信息 / 演职人员这种宽面板的左右留白（像素）。
 *
 * 父亲 2026-10-06 晚定：信息面板左侧与「正在播放」左侧对齐，演职人员面板右侧与
 * 右上角时间右侧对齐 —— 而控制条标题行与时间行的左右留白都是 100dp = 150px。
 */
private const val CARD_WIDE_PAD = 150f

@Composable
fun PlayerMenuPanel(menu: MenuState, osd: OsdState) {
    val kind = menu.kind ?: return
    val wide = kind == MenuKind.INFO || kind == MenuKind.CAST

    // 信息面板（父亲 2026-10-06）：宽高各减半、左边与控制条左边对齐
    val isInfo = kind == MenuKind.INFO
    val panelW = MENU_PANEL_W.toFloat()
    val panelH = MENU_PANEL_H.toFloat()
    val leftPx = when {
        isInfo -> 0f                                  // 与控制条左边对齐
        wide -> CARD_WIDE_PAD
        else -> {
            val bx = menu.anchor?.let { osd.buttonX[it] } ?: 0.5f
            (bx * panelW - CARD_W / 2f).coerceIn(CARD_WIDE_PAD, panelW - CARD_W - CARD_WIDE_PAD)
        }
    }
    val leftDp = pxToDp(leftPx)
    val widthDp = when {
        isInfo -> pxToDp(panelW / 2f)                  // 宽度减半
        wide -> pxToDp(panelW - CARD_WIDE_PAD * 2f)
        else -> pxToDp(CARD_W)
    }
    // 所有菜单卡片都固定成控制条的厚度（父亲 2026-10-07），内容超出就滚动

    /*
     * 卡片实际占的那块矩形要报给原生（父亲 2026-10-06）：
     * 光柱落在卡片外的透明区时要穿过去打到后面的面，不能在半路被拦住。
     */
    val windowW = LocalWindowInfo.current.containerSize.width
    val windowH = LocalWindowInfo.current.containerSize.height

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .padding(start = leftDp, bottom = 8.dp)
                .width(widthDp)
                .height(pxToDp(panelH))
                .onGloballyPositioned { coords ->
                    if (windowW <= 0 || windowH <= 0) return@onGloballyPositioned
                    val x = coords.positionInRoot().x
                    val y = coords.positionInRoot().y
                    val w = coords.size.width.toFloat()
                    val h = coords.size.height.toFloat()
                    com.xxxx.emby_vr.vr.VrNative.setMenuHitRect(
                        (x / windowW).coerceIn(0f, 1f),
                        (y / windowH).coerceIn(0f, 1f),
                        ((x + w) / windowW).coerceIn(0f, 1f),
                        ((y + h) / windowH).coerceIn(0f, 1f),
                    )
                }
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF0141518))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 信息面板不显示「信息」二字（父亲 2026-10-06 晚定），其余菜单照旧
                    text = if (kind == MenuKind.INFO) "" else kind.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )

                /*
                 * 关闭 / 返回上级按钮取消（父亲 2026-10-06 晚定）：所有菜单都用 B 键
                 * 返回 —— 二级菜单回上一级、一级菜单直接关。界面上不再放这个按钮。
                 */
            }
            Spacer(modifier = Modifier.height(14.dp))
            when (kind) {
                MenuKind.MORE -> MoreMenu(menu)
                MenuKind.SPEED -> SpeedMenu(menu)
                MenuKind.QUALITY -> QualityMenu(menu)
                MenuKind.MODE -> ModeMenu(menu)
                MenuKind.BUFFER -> BufferMenu(menu)
                MenuKind.DANMAKU -> DanmakuMenu(menu)
                MenuKind.SUBTITLE -> TrackMenu(menu, menu.subtitleTracks, MenuKind.SUBTITLE)
                MenuKind.AUDIO -> TrackMenu(menu, menu.audioTracks, MenuKind.AUDIO)
                MenuKind.EPISODES -> EpisodeMenu(menu)
                MenuKind.INFO -> InfoMenu(menu)
                MenuKind.CAST -> CastMenu(menu)
            }
        }
    }
}

/** 像素 → dp（面板 240dpi，1dp = 1.5px） */
private fun pxToDp(px: Float) = (px / 1.5f).dp

@Composable
private fun MoreMenu(menu: MenuState) {
    val entries = listOf(
        MenuKind.AUDIO to "音频选择",
        MenuKind.QUALITY to "视频质量",
        MenuKind.MODE to "播放模式",
        MenuKind.BUFFER to "缓冲设置",
    )
    Column {
        entries.forEachIndexed { i, (k, label) ->
            MenuRow(
                label = label,
                value = when (k) {
                    MenuKind.QUALITY -> QUALITY_STEPS
                        .getOrNull(menu.quality)?.second ?: ""
                    MenuKind.MODE -> PLAY_MODE_STEPS.getOrNull(menu.playMode) ?: ""
                    MenuKind.AUDIO -> menu.audioTracks.firstOrNull { it.selected }?.label ?: ""
                    else -> {
                        val b = menu.buffer
                        if (b != null) BUFFER_PRESETS.getOrNull(b.presetIndex)?.first ?: "" else ""
                    }
                },
                selected = false,
                hasSub = true,
                onClick = { menu.onSelect?.invoke(MenuKind.MORE, i) },
            )
        }
    }
}

@Composable
private fun SpeedMenu(menu: MenuState) {
    Column {
        SPEED_STEPS.forEachIndexed { i, s ->
            val label = if (s == 1.0f) "1.0x（正常）" else "${s}x"
            MenuRow(
                label = label,
                value = "",
                selected = kotlin.math.abs(menu.speed - s) < 0.01f,
                onClick = { menu.onSelect?.invoke(MenuKind.SPEED, i) },
            )
        }
    }
}

@Composable
private fun QualityMenu(menu: MenuState) {
    Column {
        QUALITY_STEPS.forEachIndexed { i, (_, label) ->
            MenuRow(
                label = label,
                value = "",
                selected = menu.quality == i,
                onClick = { menu.onSelect?.invoke(MenuKind.QUALITY, i) },
            )
        }
    }
}

@Composable
private fun ModeMenu(menu: MenuState) {
    Column {
        PLAY_MODE_STEPS.forEachIndexed { i, label ->
            MenuRow(
                label = label,
                value = "",
                selected = menu.playMode == i,
                onClick = { menu.onSelect?.invoke(MenuKind.MODE, i) },
            )
        }
    }
}

@Composable
private fun BufferMenu(menu: MenuState) {
    val b = menu.buffer
    Column {
        BUFFER_PRESETS.forEachIndexed { i, (label, minMs, maxMs) ->
            MenuRow(
                label = label,
                value = "起播 ${minMs / 1000} 秒 · 上限 ${maxMs / 1000} 秒",
                selected = b?.presetIndex == i,
                onClick = { menu.onSelect?.invoke(MenuKind.BUFFER, i) },
            )
        }
    }
}

@Composable
private fun DanmakuMenu(menu: MenuState) {
    Column {
        MenuRow(
            label = if (menu.danmakuOn) "显示弹幕：开" else "显示弹幕：关",
            value = "",
            selected = menu.danmakuOn,
            onClick = { menu.onSelect?.invoke(MenuKind.DANMAKU, 0) },
        )
        DANMAKU_SCALES.forEachIndexed { i, (v, label) ->
            MenuRow(
                label = "字号：$label",
                value = "",
                selected = kotlin.math.abs(menu.danmakuScale - v) < 0.01f,
                onClick = { menu.onSelect?.invoke(MenuKind.DANMAKU, i + 1) },
            )
        }
    }
}

@Composable
private fun TrackMenu(menu: MenuState, rows: List<MenuRowItem>, kind: MenuKind) {
    if (rows.isEmpty()) {
        Text("没有可选项", color = Color(0xFFFFFFFF), fontSize = 24.sp)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(menu.scrollTick) {
        val dy = menu.scrollDelta
        if (dy != 0f) {
            menu.scrollDelta = 0f
            listState.scrollBy(dy)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 200.dp)) {
        itemsIndexed(rows) { i, row ->
            MenuRow(
                label = row.label,
                value = "",
                selected = row.selected,
                onClick = { menu.onSelect?.invoke(kind, i) },
            )
        }
    }
}

@Composable
private fun EpisodeMenu(menu: MenuState) {
    if (menu.episodes.isEmpty()) {
        Text("没有可选的集数", color = Color(0xFFFFFFFF), fontSize = 24.sp)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(menu.scrollTick) {
        val dy = menu.scrollDelta
        if (dy != 0f) {
            menu.scrollDelta = 0f
            listState.scrollBy(dy)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 200.dp)) {
        itemsIndexed(menu.episodes) { i, row ->
            MenuRow(
                label = row.label,
                value = "",
                selected = row.selected,
                onClick = { menu.onSelect?.invoke(MenuKind.EPISODES, i) },
            )
        }
    }
}

/**
 * 信息（父亲 2026-10-06 下午定的形态，与电视版播放页「信息」一致）：
 * 左边海报，右边片名 + 年份/时长/评分/类型 + 简介；不写「信息」两个字当标题。
 * 整块从控制条最左铺到最右。
 */
@Composable
private fun InfoMenu(menu: MenuState) {
    val info = menu.info ?: run {
        Text("暂无信息", color = Color(0xFFFFFFFF), fontSize = 24.sp)
        return
    }
    Row(modifier = Modifier.fillMaxSize()) {
        /*
         * 海报（2:3）：卡片厚度改成控制条高度后（父亲 2026-10-07），
         * 海报跟着缩到内容区里，保持 2:3。
         */
        Box(
            modifier = Modifier
                .width(133.dp)
                .height(200.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x22FFFFFF)),
        ) {
            if (!menu.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = menu.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.width(30.dp))
        /*
         * 卡片高度 = 控制条厚度（父亲 2026-10-07）之后不再留 1/3 的顶部空档：
         * 文字直接顶到上边，内容装不下就滚动。
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = info.title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (info.episodeLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = info.episodeLine,
                    color = Color(0xFFFFFFFF),
                    fontSize = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row {
                listOf(info.officialRating, info.year, info.runtime, info.rating, info.genres)
                    .filter { it.isNotBlank() }
                    .forEach { badge ->
                        Text(
                            text = badge,
                            color = Color(0xFFFFFFFF),
                            fontSize = 24.sp,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22FFFFFF))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = info.overview.ifBlank { "暂无简介" },
                color = Color(0xFFFFFFFF),
                fontSize = 21.sp,
                lineHeight = 32.sp,
                modifier = Modifier.heightIn(max = 560.dp),
            )
            if (info.techLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = info.techLine,
                    color = Color(0xFFFFFFFF),
                    fontSize = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CastMenu(menu: MenuState) {
    if (menu.people.isEmpty()) {
        Text("暂无演职人员信息", color = Color(0xFFFFFFFF), fontSize = 24.sp)
        return
    }
    // 演职人员是横向一排，摇杆左右（或上下）推着滚（父亲 2026-10-06 晚：滚不动）
    val rowState = rememberLazyListState()
    LaunchedEffect(menu.scrollTick) {
        val dy = menu.scrollDelta
        if (dy != 0f) {
            menu.scrollDelta = 0f
            rowState.scrollBy(dy)
        }
    }
    LazyRow(state = rowState, contentPadding = PaddingValues(vertical = 6.dp)) {
        items(menu.people) { person ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = 26.dp)
                    .width(150.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(212.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!person.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = person.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = person.name.take(1),
                            color = Color(0xFFFFFFFF),
                            fontSize = 40.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = person.name,
                    color = Color.White,
                    fontSize = 24.sp,
                    maxLines = 1,
                )
                if (person.role.isNotBlank()) {
                    Text(
                        text = person.role,
                        color = Color(0xFFFFFFFF),
                        fontSize = 17.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    value: String,
    selected: Boolean,
    hasSub: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x2E4CAF50) else Color(0x14FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 24.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (value.isNotBlank()) {
            Text(
                text = value,
                color = Color(0xFFFFFFFF),
                fontSize = 19.sp,
                maxLines = 1,
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFFFFFFFF),
                modifier = Modifier.size(24.dp),
            )
        } else if (hasSub) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "›", color = Color(0xFFFFFFFF), fontSize = 26.sp)
        }
    }
}
