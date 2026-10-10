package com.xxxx.emby_vr.ui.components

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.delay

/** 摇杆每拨一次滚多少（大约一个海报行的距离） */
private const val kScrollStep = 300f

/**
 * 首页搜索浮层（父亲 2026-10-10 定稿）。
 *
 * 形态：**浮在海报墙上面**的一块面板，海报墙不动。
 *   · 第一行：输入框 + 「片名 / 演员」单选（单选、记住上次选择）+「搜索」+「关闭」
 *   · 下面：结果标签（全部 / 电影 / 剧集 / 其他，**有内容才出现**）+ 海报网格
 *   · 点海报 → 进详情页（父亲 2026-10-10：先进详情页，不直接播）
 *
 * 视觉规格照海报墙现有的那套（父亲 2026-10-10：新界面要和现有风格一致）：
 *   面板底 = 半透明黑 + 1dp 白 15% 描边 + 12dp 圆角（详情页的信息框同款）；
 *   卡片底 = 白 6%；未选中胶囊 = #3A3A3A；强调 = 主题色（绿）；
 *   正文字号 16~18sp，标题 21sp（首页行标题同档）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchOverlay(
    searchViewModel: SearchViewModel,
    serverUrl: String,
    onOpenItem: (BaseItemDto) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf(searchViewModel.currentQuery) }
    var tab by remember { mutableStateOf(SearchTab.ALL) }
    val fieldFocus = remember { FocusRequester() }
    /** 第一张结果海报的焦点锚点：输入框按「下」直接落到它上面（摇杆就能开始滚结果） */
    val firstResultFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current

    val results = searchViewModel.searchResults
    val isSearching = searchViewModel.isSearching
    val hint = searchViewModel.hint
    val channel = searchViewModel.channel

    /*
     * 手柄 B 键 = 返回：浮层开着时，B 先关浮层（父亲 2026-10-10 要求）。
     * 走的是面板那条返回通道（PanelLayer.back → onBackPressedDispatcher），
     * 浮层在 HomeScreen 里后注册，优先级高于 NavHost，所以 B 先落到这里。
     */
    androidx.activity.compose.BackHandler(enabled = true) {
        runCatching { keyboard?.hide() }
        onClose()
    }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fieldFocus.requestFocus() }
    }

    // 换了词或换了通道 → 回到「全部」
    LaunchedEffect(searchViewModel.currentQuery, channel) {
        tab = SearchTab.ALL
    }

    fun runSearch() {
        if (query.isBlank()) return
        searchViewModel.search(query)
        runCatching { keyboard?.hide() }
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
    /** 标签上的条数按实际拿到的算（服务端的总数不可信） */
    val counts: Map<SearchTab, Int> = remember(allItems) {
        mapOf(
            SearchTab.ALL to allItems.size,
            SearchTab.MOVIE to allItems.count { it.type.equals("Movie", ignoreCase = true) },
            SearchTab.TV to allItems.count { it.type.equals("Series", ignoreCase = true) },
            SearchTab.OTHER to allItems.count { !isMovieOrSeries(it) },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 压暗背景，海报墙仍可见 —— 是浮层，不是把海报墙换掉
            .background(Color.Black.copy(alpha = 0.45f))
            /*
             * 浮层开着时把点击吃掉：不然扣扳机指着空白处会点到**后面海报墙**上的卡片，
             * 焦点跟着跑出浮层，摇杆就滚不动这块浮层了（父亲 2026-10-10：摇杆要接进来）。
             */
            .pointerInput(Unit) { detectTapGestures { } }
            /*
             * 摇杆 = 遥控器方向键（父亲 2026-10-10：摇杆必须能滚这块浮层）。
             *
             * 这里直接在浮层根上截「上/下」，自己滚结果网格 —— 不靠焦点移动，
             * 因为焦点可能停在输入框里（文本框会把方向键吃掉），那样摇杆看着像没反应。
             * 左右键不截，留给文本框移光标；没有结果时不截，照常走焦点（方便回到输入框）。
             */
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || shown.isEmpty()) {
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
                /*
                 * 浮层尺寸（父亲 2026-10-10：浮动框不要太大）。
                 * 收成 78% × 68% 居中，不铺满整屏 —— 结果是一屏看不完的（每个标签最多 60 条），
                 * 靠摇杆/焦点滚动看剩下的，不是要一次全排下。
                 */
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.68f)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            // ---------- 第一行：输入 + 通道单选 + 搜索 + 关闭 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .focusRequester(fieldFocus)
                        /*
                         * 输入框按「下」直接进结果网格（父亲 2026-10-10：摇杆要能滚）。
                         * 文本框会把方向键吃掉当光标移动，不这样接一下，焦点就一直困在框里，
                         * 摇杆看着像没反应。
                         */
                        .focusProperties { down = firstResultFocus }
                        .vrClickTarget(
                            key = "search:field",
                            focusRequester = fieldFocus,
                            onActivate = { runCatching { fieldFocus.requestFocus() } },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = if (channel == SearchViewModel.Channel.PERSON)
                                "输入演员名，例如：刘德华" else "输入片名，例如：庆余年",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 17.sp,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus),
                    )
                }

                PillChip(
                    text = "片名",
                    selected = channel == SearchViewModel.Channel.NAME,
                    vrKey = "search:channel:name",
                    onClick = { searchViewModel.setChannel(SearchViewModel.Channel.NAME) },
                )
                PillChip(
                    text = "演员",
                    selected = channel == SearchViewModel.Channel.PERSON,
                    vrKey = "search:channel:person",
                    onClick = { searchViewModel.setChannel(SearchViewModel.Channel.PERSON) },
                )

                Button(
                    onClick = { runSearch() },
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        focusedContainerColor = MaterialTheme.colorScheme.primary,
                        focusedContentColor = Color.White,
                    ),
                ) {
                    Text("搜索", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                PillChip(
                    text = "关闭",
                    selected = false,
                    vrKey = "search:close",
                    onClick = {
                        runCatching { keyboard?.hide() }
                        onClose()
                    },
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ---------- 结果区 ----------
            when {
                isSearching -> CenteredNote("正在搜索…")

                hint != null -> CenteredNote(hint)

                results == null -> CenteredNote("选好片名或演员，输入关键词后点「搜索」")

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
                                    onClick = { tab = t },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LazyVerticalGrid(
                        state = gridState,
                        // 海报缩小一档（父亲 2026-10-10：浮层要小）：一行约 6 张，一屏 3 行
                        columns = GridCells.Adaptive(minSize = 118.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        /*
                         * 结果出来后焦点自动落到第一张（父亲 2026-10-10：摇杆要能滚）。
                         * 网格本身会跟着焦点滚；焦点在输入框里时方向键被文本框吃掉，
                         * 所以搜完就把焦点交出来，摇杆立刻能上下滚结果。
                         */
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
                                onItemClick = { onOpenItem(item) },
                            )
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
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
 * 胶囊按钮：未选中 = #3A3A3A、选中 = 主题色 —— 和详情页那颗季胶囊同一套配色。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PillChip(
    text: String,
    selected: Boolean,
    vrKey: String,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A),
            contentColor = Color.White,
            focusedContainerColor = if (selected) MaterialTheme.colorScheme.primary
            else Color.White.copy(alpha = 0.18f),
            focusedContentColor = Color.White,
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .vrClickTarget(
                key = vrKey,
                focusRequester = focusRequester,
                onActivate = onClick,
            ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
