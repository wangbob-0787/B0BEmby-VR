package com.xxxx.emby_vr.ui

import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.ui.components.BuildItem
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.xxxx.emby_vr.ui.components.VrHoverBg
import com.xxxx.emby_vr.ui.components.VrSelectedBg
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.panel.ClickTargets
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.components.Loading
import com.xxxx.emby_vr.ui.components.NoData
import com.xxxx.emby_vr.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    parentId: String,
    title: String,
    type: String,
    libraryViewModel: LibraryViewModel,
    /*
     * 特殊来源（父亲 2026-10-11）：首页「继续观看 / 收藏」行的标题点进来复用本页。
     * "" = 普通媒体库（按 parentId 取）；"resume" = 继续观看；"favorites" = 收藏。
     */
    special: String = "",
    onNavigateToSeries: (String) -> Unit,
    // 直播频道（TvChannel）点击直接播（父亲 2026-10-02：电视直播库点进去就是频道）
    onNavigateToPlayer: (String) -> Unit = {},
    /*
     * 合集（BoxSet）点击：打开它自己的子项网格（2026-10-10 修「合集点进去看不到影片」）。
     * 合集不是剧集 —— 详情页取季/集用的 /Shows/{id}/Seasons 对它直接 404（实测），
     * 所以页面是空白的。合集的孩子是影片，用本页的网格列出来最省事。
     */
    onOpenBoxSet: (String, String) -> Unit = { _, _ -> },
    /*
     * 继续观看列表页专用（父亲 2026-10-11）：点一条**直接续播**，不再进详情页
     * —— 与首页「继续观看」那一排的行为一致。
     */
    onResumePlay: ((BaseItemDto) -> Unit)? = null,
) {
    val context = LocalContext.current
    val firstItemFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var isInitialScrollDone by rememberSaveable { mutableStateOf(false) }
    var returnFocusIndex by rememberSaveable { mutableIntStateOf(-1) }
    var shouldFocusOnReturn by rememberSaveable { mutableStateOf(false) }
    val returnFocusRequester = remember { FocusRequester() }

    val repository = remember { EmbyRepository.getInstance(context) }
    val serverUrl = repository.serverUrl ?: ""

    val libraryItems = libraryViewModel.libraryItems
    val isLoadingMore = libraryViewModel.isLoadingMore
    val hasMoreData = libraryViewModel.hasMoreData
    val totalCount = libraryViewModel.totalCount

    val currentSortBy = libraryViewModel.currentSortBy
    val currentSortOrder = libraryViewModel.currentSortOrder
    val currentFilter = libraryViewModel.currentFilter

    var showSortDialog by remember { mutableStateOf(false) }

    val sortByLabel = stringResource(
        LibraryViewModel.SORT_OPTIONS.find { it.first == currentSortBy }?.second
            ?: R.string.sort_name
    )
    val sortIcon = if (currentSortOrder == "Ascending") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward

    LaunchedEffect(parentId, type, special) {
        libraryViewModel.loadItems(parentId, type, special)
    }

    LaunchedEffect(libraryItems) {
        if (libraryItems != null && libraryItems.isNotEmpty()) {
            if (!isInitialScrollDone) {
                gridState.scrollToItem(0)
                firstItemFocusRequester.requestFocus()
                isInitialScrollDone = true
            } else if (shouldFocusOnReturn && returnFocusIndex >= 0) {
                shouldFocusOnReturn = false
                if (returnFocusIndex < libraryItems.size) {
                    gridState.scrollToItem(returnFocusIndex)
                    withFrameNanos { }
                    returnFocusRequester.requestFocus()
                }
            }
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItemsCount > 0 && lastVisibleItemIndex >= totalItemsCount - 5
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreData && !isLoadingMore) {
                libraryViewModel.loadMore()
            }
        }
    }

    /*
     * 媒体库页等待状态（父亲 2026-10-13 定，与首页同一套）：
     * 数据没回来写「正在加载…」，8 秒还没回来或已报错写「请检查网络连接」，之后每 5 秒自己重试。
     * 判据用「libraryItems 是否为 null」——请求回来（哪怕是空库）就不再算等待。
     */
    var libWait by remember { mutableStateOf(LibWait.Loading) }
    LaunchedEffect(parentId, type, special) {
        var waited = 0L
        while (true) {
            kotlinx.coroutines.delay(500L)
            waited += 500L
            if (libraryViewModel.libraryItems != null) {
                libWait = LibWait.Done
                waited = 0L
                continue
            }
            libWait = if (waited < 8_000L && libraryViewModel.errorMessage == null) {
                LibWait.Loading
            } else {
                LibWait.NoNetwork
            }
            if (waited >= 8_000L && (waited - 8_000L) % 5_000L == 0L) libraryViewModel.refresh()
        }
    }

    /*
     * 排序面板改**同一棵界面树里的浮层**（父亲 2026-10-10「媒体库所有按钮无法操作」）。
     *
     * 与首页菜单同一个病：`Dialog` 会另开一个窗口，那个窗口不落在 VR 面板那张虚拟屏上，
     * 所以排序面板在头显里既看不见也点不着；而且它的坐标也不在该屏的坐标系里，
     * 光点命中判定必然对不上。改成同树浮层后，光柱照常点，浮层也排在内容之后（在最上层）。
     * 下面 124 行起用 Box 把整页内容与浮层包在一起。
     */
    Box(modifier = Modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            /*
             * 整页页边距 40dp，与详情页一致（父亲 2026-10-11）。
             *
             * 原来这里是 16dp，而下面的海报网格自己又加了 24dp 的 contentPadding ——
             * 标题 / 排序按钮在 16dp、海报在 40dp，三者不在一条竖线上。
             * 现在外层统一 40dp（详情页就是 40dp），网格左右内边距归零，
             * 标题、排序按钮、海报全部左对齐。
             */
            .padding(40.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (title.isNotEmpty()) title else stringResource(R.string.my_libraries),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                /*
                 * 标题右移 16dp（父亲 2026-10-13）：排序按钮保留底色块、按钮里的文字不动，
                 * 所以要让标题跟**按钮里的文字**左对齐（按钮内有 16dp 水平内边距）。
                 * 海报网格仍在 40dp 不动 —— 父亲明确「海报仍留在 40 不动」。
                 */
                modifier = Modifier.padding(start = 16.dp)
            )

            if (libraryItems != null && totalCount > 0) {
                Text(
                    // 原来写「42/3456」，没有标签没有单位，读不出是什么（父亲 2026-10-13）
                    text = "共 $totalCount 部",
                    // 原来 bodyMedium（14sp）压 70% 白，父亲说右上角字太小 → 提到 16sp / 85% 白
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { showSortDialog = true },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White,
                focusedContainerColor = VrHoverBg,
                focusedContentColor = Color.White,
            ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent))),
                modifier = Modifier
                    .padding(end = 12.dp)
                    // 排序入口登记进坐标表（父亲 2026-10-10：媒体库按钮点不动）
                    // hoverRadius 跟控件自身的胶囊圆角一致（父亲 2026-10-13：白块不能和控件对不上）
                    .vrClickTarget(key = "lib:sort", hoverRadius = 24.dp) { showSortDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.sort)}: $sortByLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = sortIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                }
            }

            LibraryViewModel.FILTER_OPTIONS.forEach { (filterValue, labelRes) ->
                val label = stringResource(labelRes)
                val isSelected = currentFilter == filterValue

                // 同一个动作引用给 onClick 与坐标表（VR 扣扳机走登记那条路）
                val doFilter: () -> Unit = {
                    if (!isSelected) {
                        libraryViewModel.updateSortAndFilter(
                            currentSortBy, currentSortOrder, filterValue
                        )
                    }
                }

                Surface(
                    onClick = doFilter,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .vrClickTarget(key = "lib:filter:$filterValue", hoverRadius = 24.dp, onActivate = doFilter),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        /*
                         * 选中态统一用绿 24%（父亲 2026-10-13：全站"选中"只留一种样子）。
                         * 原来是变暗底 + onSurfaceVariant 灰字，和别处（控制条、菜单）的绿底不一致。
                         */
                        containerColor = if (isSelected) VrSelectedBg else Color.Transparent,
                        contentColor = if (isSelected) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.7f)
                        },
                focusedContainerColor = if (isSelected) VrSelectedBg else VrHoverBg,
                focusedContentColor = if (isSelected) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.7f)
                        },
            ),
                    border = ClickableSurfaceDefaults.border(
                        // 未选中项的 1dp 描边太暗（30% 白对底色约 2.66:1，低于组件边界 3:1），
                        // 提到 45% 白（约 3.4:1，父亲 2026-10-13）
                        border = if (!isSelected) Border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
                        ) else Border(BorderStroke(0.dp, Color.Transparent)),
                        focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent)))
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        /*
         * 媒体库页两态（父亲 2026-10-13 定，与首页同一套口径）：
         *  · 数据没回来 → 「正在加载…」（原来只有 Loading 转圈，看不出是在加载还是卡死）；
         *  · 8 秒还没回来或已报错 → 「请检查网络连接」，之后每 5 秒自己重试一次。
         * 原来这里既没有错误态也没有重试，请求失败就永远停在转圈或「无数据」上。
         */
        if (libWait != LibWait.Done) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (libWait == LibWait.Loading) "正在加载…" else "请检查网络连接",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp
                )
            }
        } else if (libraryItems == null) {
            Loading()
        } else if (libraryItems.isEmpty()) {
            NoData()
        } else {
            val items = libraryItems

            val maxLength = 220.dp

            // 电视直播库：台标是横版图，必须用 16:9 卡片，否则被裁（父亲 2026-10-02："比例不对"）
            val isLiveTv = type.equals("TvChannel", ignoreCase = true) ||
                items.any { it.type.equals("TvChannel", ignoreCase = true) }

            val (aspectRatioOver1List, aspectRatioUnder1List) = items
                .mapNotNull { it.primaryImageAspectRatio?.toFloat() }
                .partition { it > 1.0f }

            val maxAspectRatio = if (isLiveTv) {
                16f / 9f
            } else if (aspectRatioOver1List.size >= aspectRatioUnder1List.size) {
                aspectRatioOver1List.maxOrNull() ?: 1.777f
            } else {
                aspectRatioUnder1List.maxOrNull() ?: 0.666f
            }

            val imgWidth = if (maxAspectRatio >= 1.0f) {
                maxLength
            } else {
                maxLength * maxAspectRatio
            }

            val num = if (isLiveTv) 5 else if (maxAspectRatio > 1.0f) 4 else 6

            LazyVerticalGrid(
                columns = GridCells.Fixed(num),
                state = gridState,
                /*
                 * 左右内边距归零（父亲 2026-10-11）：外层 Column 已经给了 40dp，
                 * 这里再加 24dp 海报就会比标题、排序按钮右移一截，三者对不齐。
                 * 上下的 24dp 保留（滚动时的呼吸感）。
                 */
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items.size, key = { index -> "${items[index].id ?: index}-$index" }) { index ->
                    val item = items[index]
                    val id = item.id ?: ""
                    if (id.isNotEmpty()) {
                        val itemModifier = when {
                            index == returnFocusIndex -> Modifier
                                .fillMaxWidth()
                                .focusRequester(returnFocusRequester)
                            index == 0 -> Modifier
                                .fillMaxWidth()
                                .focusRequester(firstItemFocusRequester)
                            else -> Modifier.fillMaxWidth()
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            BuildItem(
                                item = item,
                                imgWidth = imgWidth,
                                aspectRatio = maxAspectRatio,
                                modifier = itemModifier,
                                // 频道卡当"图块"渲染：不画下面的年份/分级占位("--")、不画播放进度条
                                isMyLibrary = isLiveTv,
                                /*
                                 * 频道卡下面显示"当前正在播的节目名"（父亲 2026-10-12）。
                                 * 首页「电视直播」那行一直有，这里以前没有：
                                 * 详情页卡走的是 isMyLibrary=true 分支，副标题整个不画。
                                 * 数据靠 EmbyApi.getLibraryList 的 Fields=CurrentProgram 带回来。
                                 */
                                subtitleOverride = if (isLiveTv) item.currentProgram?.name else null,
                                imageScale = if (isLiveTv) ContentScale.Fit else ContentScale.Crop,
                                serverUrl = serverUrl,
                                onItemClick = {
                                    returnFocusIndex = index
                                    shouldFocusOnReturn = true
                                    when {
                                        // 继续观看列表：点一条直接续播（父亲 2026-10-11）
                                        special == "resume" && onResumePlay != null ->
                                            onResumePlay.invoke(item)
                                        item.type.equals("TvChannel", ignoreCase = true) ->
                                            onNavigateToPlayer(id)
                                        // 合集 → 打开它自己的子项网格（影片/剧集都能列）
                                        item.type.equals("BoxSet", ignoreCase = true) ->
                                            onOpenBoxSet(id, item.name ?: "")
                                        else -> onNavigateToSeries(id)
                                    }
                                }
                            )
                        }
                    }
                }

                if (isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
        }

        // 排序浮层：排在内容之后（最上层），点空白关掉
        if (showSortDialog) {
            SortDialog(
                currentSortBy = currentSortBy,
                currentSortOrder = currentSortOrder,
                onSortSelected = { sortBy, sortOrder ->
                    libraryViewModel.updateSortAndFilter(sortBy, sortOrder, currentFilter)
                    showSortDialog = false
                },
                onDismiss = { showSortDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SortDialog(
    currentSortBy: String,
    currentSortOrder: String,
    onSortSelected: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequesters = remember { LibraryViewModel.SORT_OPTIONS.map { FocusRequester() } }

    /*
     * 同树浮层（不再用 Dialog 窗口）：Dialog 会另开窗口，那个窗口不落在 VR 面板那张
     * 虚拟屏上 —— 排序面板在头显里既看不见也点不着，而且它的坐标不在该屏坐标系里，
     * 光点命中判定必然对不上（父亲 2026-10-10「媒体库所有按钮无法操作」）。
     */
    androidx.activity.compose.BackHandler(enabled = true) { onDismiss() }
    /*
     * 排序面板是一层**模态组**（父亲 2026-10-13：点在面板外不该激活背后的海报）。
     *
     * 以前没带组：光柱落在面板外时坐标表按"面积最小优先"命中 —— 铺满整页的遮罩面积
     * 比背后的海报卡大得多，永远排在海报后面，于是点在面板外把海报激活了。
     * 带上组之后，组开着时坐标表只认同组控件（这层遮罩 + 下面每一个排序选项），
     * 海报墙一律不参与命中；遮罩自己仍然负责"点面板外关掉面板"。
     */
    val group = remember { Any() }
    DisposableEffect(group) {
        ClickTargets.setModalGroup(group)
        onDispose { ClickTargets.clearModalGroup(group) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .vrClickTarget(
                key = "sort:scrim",
                group = group,
                hover = false,
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
            Surface(
                modifier = Modifier
                    // 弹窗宽度收到统一档位 420dp（父亲 2026-10-13：全站弹窗只有 420/560/760 三档，
                    // 原来这里是 360）
                    .width(420.dp)
                    .heightIn(max = 480.dp),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = Color(0xFF1A1A1A).copy(alpha = 0.95f),
                    contentColor = Color.White,
            ),
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sort),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            LibraryViewModel.SORT_OPTIONS,
                            key = { _, opt -> opt.first }
                        ) { index, (sortByValue, labelRes) ->
                            val label = stringResource(labelRes)
                            val isSelected = currentSortBy == sortByValue

                            // 同一个动作引用给 onClick 与坐标表：VR 扣扳机走的是登记那条路
                            val doSort: () -> Unit = {
                                val newOrder = if (sortByValue == currentSortBy) {
                                    if (currentSortOrder == "Ascending") "Descending" else "Ascending"
                                } else {
                                    currentSortOrder
                                }
                                onSortSelected(sortByValue, newOrder)
                            }

                            Surface(
                                onClick = doSort,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    /*
                                     * 行高钉成固定值 52dp（父亲 2026-10-13 第二次实测：还是不一样大）。
                                     *
                                     * 上一版只钉了**下限** 48dp —— 实测截图量出来：未选中的行内容矮，
                                     * 被最小触控高度顶到 48dp；选中那一行因为粗体文字 + 右侧箭头图标
                                     * 把内容撑到约 55dp，于是绿块比扫过的白块高约 12%。
                                     * 现在上下限都钉死，选中 / 未选中一律同高，两块必然一样大。
                                     */
                                    .height(52.dp)
                                    .focusRequester(focusRequesters[index])
                                    .vrClickTarget(
                                        key = "sort:$sortByValue",
                                        focusRequester = focusRequesters[index],
                                        // 选项块自身是 8dp 圆角，高亮白层必须一致 ——
                                        // 之前白层写死 12dp，四角露出绿色，父亲看成"两块大小不一"（2026-10-13）
                                        hoverRadius = 8.dp,
                                        /*
                                         * 面板里的选项**不做放大**（父亲 2026-10-13：白块要和绿块一样大）：
                                         * 全局默认扫到会放大 5%，越出绿块边界，看着就是两块不一样大。
                                         */
                                        hoverScale = 1f,
                                        group = group,
                                        onActivate = doSort,
                                    ),
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    // 选中态统一用绿 24%（父亲 2026-10-13）
                                    containerColor = if (isSelected) VrSelectedBg else Color.Transparent,
                                    contentColor = Color.White,
                focusedContainerColor = if (isSelected) VrSelectedBg else VrHoverBg,
                focusedContentColor = Color.White,
            )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = if (currentSortOrder == "Ascending") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            val currentSortIndex = LibraryViewModel.SORT_OPTIONS.indexOfFirst { it.first == currentSortBy }
            LaunchedEffect(Unit) {
                val targetIndex = if (currentSortIndex >= 0) currentSortIndex else 0
                if (targetIndex < focusRequesters.size) {
                    focusRequesters[targetIndex].requestFocus()
                }
            }
    }
}

/**
 * 媒体库页首屏的等待状态（父亲 2026-10-13 定，与首页同一套口径）。
 *
 * [Loading] = 数据还没回来，写「正在加载…」；
 * [NoNetwork] = 8 秒还没回来或已报错，写「请检查网络连接」（之后每 5 秒自动重试）；
 * [Done] = 请求回来了（含空库），交给下面的转圈/空态/网格去画。
 */
private enum class LibWait { Loading, NoNetwork, Done }
