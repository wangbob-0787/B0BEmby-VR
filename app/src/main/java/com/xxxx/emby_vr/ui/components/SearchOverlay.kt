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
import com.xxxx.emby_vr.panel.ClickTargets
import com.xxxx.emby_vr.panel.vrClickBlocker
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.delay

/** 摇杆每拨一次滚多少（大约一个海报行的距离） */
private const val kScrollStep = 300f

/** 未选中的底：和详情页季胶囊、控制条按钮同一档 */
private val kIdleBg = Color(0xFF3A3A3A)

/**
 * 首页搜索浮层（父亲 2026-10-10）。
 *
 * 形态：**浮在海报墙上面**的一块面板，海报墙不动。
 *   · 第一行：输入框 + 「片名 / 演员」单选（记住上次选择）+「搜索」
 *     （不要关闭按钮 —— 手柄 B 键就是返回，父亲 2026-10-10 定）
 *   · 下面：结果标签（全部 / 电影 / 剧集 / 其他，**有内容才出现**）+ 海报网格
 *   · 点海报 → 进详情页
 *
 * 两个关键机制：
 *   1. **模态组**：VR 的点击是「按坐标查表」、不看层级，所以浮层必须把自己
 *      登记成模态组（[ClickTargets.setModalGroup]），否则点浮层会点到后面的海报墙；
 *      同时铺一块全屏兜底矩形，点浮层空白处不会回退成 OK 键去激活焦点上的东西。
 *   2. **滚**：摇杆走的是「鼠标滚轮按坐标命中」，浮层里的网格天然能滚；
 *      这里再截上下键兜一手（焦点停在输入框里时文本框会吃掉方向键）。
 *
 * 视觉照全站那套：面板半透明黑 + 1dp 白 15% 描边 + 12dp 圆角；
 * 胶囊未选中 #3A3A3A、**聚焦或选中才是绿底**（不用白底，父亲 2026-10-10）。
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
    /** 第一张结果海报的焦点锚点：输入框按「下」直接落到它上面 */
    val firstResultFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current

    /** 这一层浮层的"组"：所有控件都挂它，开着时设为模态组 */
    val overlayGroup = remember { Any() }

    val results = searchViewModel.searchResults
    val isSearching = searchViewModel.isSearching
    val hint = searchViewModel.hint
    val channel = searchViewModel.channel

    DisposableEffect(overlayGroup) {
        ClickTargets.setModalGroup(overlayGroup)
        onDispose { ClickTargets.clearModalGroup(overlayGroup) }
    }

    /*
     * 手柄 B 键 = 返回：浮层开着时先关浮层（父亲 2026-10-10）。
     * 走面板那条返回通道（PanelLayer.back → onBackPressedDispatcher），
     * 浮层在 HomeScreen 里后注册，优先级高于 NavHost。
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
             * 全屏兜底矩形（挂浮层组）：保证浮层范围内任何一处点击都"有东西可命中"，
             * 不会穿透到后面的海报墙，也不会回退成 OK 键激活焦点上的东西。
             * 查表取面积最小，所以真正的控件永远优先于它。
             */
            .vrClickBlocker(key = "search:bg", group = overlayGroup)
            // Compose 那层命中测试也吃掉，双保险
            .pointerInput(Unit) { detectTapGestures { } }
            /*
             * 再截一手上下键：焦点停在输入框里时，方向键会被文本框吃掉，
             * 摇杆看着像滚不动结果。左右键不截，留给光标。
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
                 * 收成 78% × 68% 居中，不铺满整屏 —— 结果一屏看不完，
                 * 靠滚看剩下的，不是要一次全排下。
                 */
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.68f)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            // ---------- 第一行：输入 + 通道单选 + 搜索 ----------
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
                        // 输入框按「下」直接进结果网格
                        .focusProperties { down = firstResultFocus }
                        .vrClickTarget(
                            key = "search:field",
                            focusRequester = fieldFocus,
                            group = overlayGroup,
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
                                    group = overlayGroup,
                                    onClick = { tab = t },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LazyVerticalGrid(
                        state = gridState,
                        // 海报缩小一档（浮层要小）：一行约 6 张
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
 * 胶囊按钮：**聚焦或选中 = 主题绿底**，其余时间 #3A3A3A —— 全站一致，不用白底
 * （父亲 2026-10-10：浮层里的选中色不能是白底）。
 *
 * 注：tv-material3 的默认焦点底色是白的，所以 focusedContainerColor 必须显式给，
 * 否则一聚焦就变白（2026-10-05 踩过一次）。
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else kIdleBg,
            contentColor = Color.White,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
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
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
