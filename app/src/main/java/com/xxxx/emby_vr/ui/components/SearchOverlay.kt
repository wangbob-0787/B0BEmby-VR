package com.xxxx.emby_vr.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.data.PinyinIndex
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.panel.ClickTargets
import com.xxxx.emby_vr.panel.vrClickBlocker
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.viewmodel.SearchViewModel

/** 摇杆每拨一次滚多少（大约一个海报行的距离） */
private const val kScrollStep = 300f

/** 未选中的底：和详情页季胶囊、控制条按钮同一档 */
private val kIdleBg = Color(0xFF3A3A3A)

/**
 * 控制条按钮的两档底色（父亲 2026-10-10：搜索面板的选中/聚焦要跟控制条一致）。
 * 取值与 `PlayerOsd.OsdButtonView` 里的一字不差。
 */
private val kOsdSelectedBg = Color(0x3D2FD57C)   // 选中：半透明绿
private val kOsdHoverBg = Color(0x24FFFFFF)      // 悬停/聚焦：半透明白

/**
 * 首页搜索浮层（父亲 2026-10-10 定稿）。
 *
 * 形态：**浮在海报墙上面**的一块面板，海报墙不动。
 *   · 第一行：输入框 + 「片名 / 演员」单选（记住上次选择）+「搜索」
 *   · 没开键盘时：结果标签（全部 / 电影 / 剧集 / 其他，有内容才出现）+ 海报网格
 *   · 开了键盘时：拼音候选条 + **内置键盘**
 *
 * 三点关键：
 *   1. **模态组**：VR 点击是「按坐标查表」、不看层级，所以浮层把自己登记成模态组，
 *      否则点浮层会点到后面的海报墙；再铺一块全屏兜底，点空白也不会回退成 OK。
 *   2. **内置键盘**：系统输入法弹不到我们这块自建虚拟屏上（实测），所以自己画。
 *      打字母靠 [PinyinIndex] 出中文候选（qyn → 庆余年）；也能直接打英文片名。
 *   3. **滚**：摇杆走「鼠标滚轮按坐标命中」，网格天然能滚；上下键再兜一手。
 *
 * 配色照全站：未选中 #3A3A3A、**聚焦或选中才是主题绿**（这个主题里 primary 是白色，
 * 绿色在 secondary，别再写错）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchOverlay(
    searchViewModel: SearchViewModel,
    serverUrl: String,
    onOpenItem: (BaseItemDto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { EmbyRepository.getInstance(context) }

    var query by remember { mutableStateOf(searchViewModel.currentQuery) }
    var tab by remember { mutableStateOf(SearchTab.ALL) }
    var keyboardOpen by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    /** 第一张结果海报的焦点锚点：输入框按「下」直接落到它上面 */
    val firstResultFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    /** 这一层浮层的"组"：所有控件都挂它，开着时设为模态组 */
    val overlayGroup = remember { Any() }

    // 拼音索引（首次打开键盘时建，之后用缓存）
    var indexReady by remember { mutableStateOf(PinyinIndex.isReady()) }
    var indexBuilding by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardOpen) {
        if (!keyboardOpen || PinyinIndex.isReady()) {
            indexReady = PinyinIndex.isReady()
            return@LaunchedEffect
        }
        indexBuilding = true
        runCatching { PinyinIndex.ensure(context, repository) }
        indexBuilding = false
        indexReady = PinyinIndex.isReady()
    }

    val results = searchViewModel.searchResults
    val isSearching = searchViewModel.isSearching
    val hint = searchViewModel.hint
    val channel = searchViewModel.channel

    DisposableEffect(overlayGroup) {
        ClickTargets.setModalGroup(overlayGroup)
        onDispose { ClickTargets.clearModalGroup(overlayGroup) }
    }

    // 手柄 B 键 = 返回：先关键盘，再关浮层（父亲 2026-10-10）
    BackHandler(enabled = true) {
        if (keyboardOpen) keyboardOpen = false else onClose()
    }

    LaunchedEffect(Unit) {
        fieldFocus.requestFocusRetry()
    }

    // 换了词或换了通道 → 回到「全部」
    LaunchedEffect(searchViewModel.currentQuery, channel) {
        tab = SearchTab.ALL
    }

    fun runSearch() {
        if (query.isBlank()) return
        searchViewModel.search(query)
        keyboardOpen = false
    }

    val allItems: List<BaseItemDto> = remember(results) {
        results.orEmpty().map { it.item }
    }
    val shown: List<BaseItemDto> = remember(allItems, tab) {
        when (tab) {
            SearchTab.ALL -> allItems
            SearchTab.MOVIE -> allItems.filter { it.type.equals("Movie", ignoreCase = true) }
            SearchTab.TV -> allItems.filter { it.type.equals("Series", ignoreCase = true) }
            SearchTab.OTHER -> allItems.filter { !isMovieOrSeries(it) }
        }
    }
    val counts: Map<SearchTab, Int> = remember(allItems) {
        mapOf(
            SearchTab.ALL to allItems.size,
            SearchTab.MOVIE to allItems.count { it.type.equals("Movie", ignoreCase = true) },
            SearchTab.TV to allItems.count { it.type.equals("Series", ignoreCase = true) },
            SearchTab.OTHER to allItems.count { !isMovieOrSeries(it) },
        )
    }
    /** 拼音候选（只在键盘开着、索引就绪时算） */
    val candidates = remember(query, indexReady, keyboardOpen) {
        if (keyboardOpen && indexReady) PinyinIndex.candidates(query, 8) else emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .vrClickBlocker(key = "search:bg", group = overlayGroup)
            .pointerInput(Unit) { detectTapGestures { } }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || keyboardOpen || shown.isEmpty()) {
                    return@onPreviewKeyEvent false
                }
                when (e.key) {
                    Key.DirectionDown -> {
                        gridState.dispatchRawDelta(kScrollStep)
                        true
                    }
                    Key.DirectionUp -> {
                        gridState.dispatchRawDelta(-kScrollStep)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .fillMaxHeight(if (keyboardOpen) 0.82f else 0.68f)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            // ---------- 第一行：输入框 + 通道单选 + 键盘开关 + 搜索 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .focusRequester(fieldFocus)
                        .focusProperties { down = firstResultFocus }
                        .vrClickTarget(
                            key = "search:field",
                            focusRequester = fieldFocus,
                            group = overlayGroup,
                            // 点输入框 = 开我们自己的键盘（系统键盘在我们面板上弹不出来）
                            onActivate = { keyboardOpen = true },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = if (channel == SearchViewModel.Channel.PERSON)
                                "输入演员名（拼音首字母也行）" else "输入片名（拼音首字母也行）",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 17.sp,
                        )
                    }
                    // 只读：文字只由我们自己的键盘写入（系统输入法在这块屏上不可用）
                    BasicTextField(
                        value = query,
                        onValueChange = { },
                        readOnly = true,
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                PillChip(
                    text = "片名",
                    selected = channel == SearchViewModel.Channel.NAME,
                    vrKey = "search:channel:name",
                    group = overlayGroup,
                    onClick = { searchViewModel.selectChannel(SearchViewModel.Channel.NAME) },
                )
                PillChip(
                    text = "演员",
                    selected = channel == SearchViewModel.Channel.PERSON,
                    vrKey = "search:channel:person",
                    group = overlayGroup,
                    onClick = { searchViewModel.selectChannel(SearchViewModel.Channel.PERSON) },
                )
                /*
                 * 这里原来还有一颗「搜索」按钮 —— 已删（父亲 2026-10-10）：
                 * 键盘上那颗「搜索」就够用，一行里两颗重复，点了还容易搞混。
                 */
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (keyboardOpen) {
                // ---------- 键盘模式：拼音候选 + 内置键盘 ----------
                when {
                    indexBuilding -> CenteredNote("正在建立拼音索引…（首次约几秒）")
                    candidates.isNotEmpty() -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            candidates.forEach { c ->
                                PillChip(
                                    text = c.name,
                                    selected = false,
                                    vrKey = "cand:${c.id}",
                                    group = overlayGroup,
                                    onClick = {
                                        query = c.name
                                        runSearch()
                                    },
                                )
                            }
                        }
                    }
                    else -> CenteredNote(
                        "用字母打拼音首字母（qyn = 庆余年），也可以直接打英文片名"
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                VrKeyboard(
                    group = overlayGroup,
                    onChar = { c -> query = (query + c).take(40) },
                    onSpace = { query = (query + " ").take(40) },
                    onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
                    onClear = { query = "" },
                    onSearch = { runSearch() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // ---------- 结果区 ----------
                when {
                    isSearching -> CenteredNote("正在搜索…")

                    hint != null -> CenteredNote(hint)

                    results == null -> CenteredNote("点上面的输入框，用键盘打片名或拼音首字母")

                    shown.isEmpty() -> CenteredNote("这个标签下没有内容")

                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SearchTab.entries.forEach { t ->
                                val n = counts[t] ?: 0
                                if (t == SearchTab.ALL || n > 0) {
                                    PillChip(
                                        text = if (t == SearchTab.ALL) "全部 ($n)"
                                        else "${t.label} ($n)",
                                        selected = tab == t,
                                        vrKey = "search:tab:${t.name}",
                                        group = overlayGroup,
                                        onClick = { tab = t },
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 118.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                shown,
                                key = { _, item -> item.id ?: item.hashCode().toString() },
                            ) { index, item ->
                                BuildItem(
                                    item = item,
                                    imgWidth = 118.dp,
                                    aspectRatio = (item.primaryImageAspectRatio ?: 0.6667).toFloat(),
                                    modifier = Modifier.fillMaxWidth(),
                                    isMyLibrary = false,
                                    serverUrl = serverUrl,
                                    autoFocus = index == 0,
                                    focusRequester = if (index == 0) firstResultFocus else null,
                                    // 挂浮层那个组：否则模态查表找不到它（点了不跳详情）
                                    clickGroup = overlayGroup,
                                    onItemClick = { onOpenItem(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isMovieOrSeries(item: BaseItemDto): Boolean =
    item.type.equals("Movie", ignoreCase = true) || item.type.equals("Series", ignoreCase = true)

@Composable
private fun CenteredNote(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
    }
}

/** 结果标签（父亲 2026-10-10：首页搜索只做 全部 / 电影 / 剧集 / 其他） */
enum class SearchTab(val label: String) {
    ALL("全部"),
    MOVIE("电影"),
    TV("剧集"),
    OTHER("其他"),
}

/**
 * 胶囊按钮：**聚焦或选中 = 主题绿底**，其余时间 #3A3A3A —— 全站一致，不用白底
 * （父亲 2026-10-10：浮层里的选中色不能是白底）。
 *
 * 两个坑（都踩过）：
 *   1. tv-material3 的默认焦点底色是白的，focusedContainerColor 必须显式给；
 *   2. 这个主题里 **primary 就是纯白**，绿色在 **secondary** —— 用 primary 当高亮
 *      就是"选中变白"，所以一律用 secondary。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PillChip(
    text: String,
    selected: Boolean,
    vrKey: String,
    group: Any?,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
        /*
         * 配色**跟播放页控制条那排按钮完全一致**（父亲 2026-10-10）：
         *   常态   = 透明底 + 白字
         *   悬停/聚焦 = 半透明白（14%）
         *   选中   = 半透明绿（#2FD57C 24%）
         * 控制条那边的取值就是这么写的（见 PlayerOsd.OsdButtonView），这里照抄，
         * 用户在两个界面看到的是同一套反馈语言。
         */
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) kOsdSelectedBg else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = if (selected) kOsdSelectedBg else kOsdHoverBg,
            focusedContentColor = Color.White,
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .vrClickTarget(
                key = vrKey,
                focusRequester = focusRequester,
                group = group,
                onActivate = onClick,
            ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
