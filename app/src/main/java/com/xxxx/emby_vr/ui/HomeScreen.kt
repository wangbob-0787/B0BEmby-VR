package com.xxxx.emby_vr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.*
import com.xxxx.emby_vr.data.model.BaseItemDto
import androidx.compose.ui.res.stringResource
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.panel.vrClickBlocker
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.FocusMemory
import com.xxxx.emby_vr.ui.components.BuildItem
import com.xxxx.emby_vr.ui.components.HomeHeroCarousel
import com.xxxx.emby_vr.ui.components.LiveTvRow
import com.xxxx.emby_vr.ui.components.Loading
import com.xxxx.emby_vr.ui.components.NoData
import com.xxxx.emby_vr.ui.components.SearchOverlay
import com.xxxx.emby_vr.ui.components.TopStatusBar
import com.xxxx.emby_vr.util.DiagLog
import com.xxxx.emby_vr.util.ErrorHandler
import com.xxxx.emby_vr.ui.viewmodel.HomeViewModel
import com.xxxx.emby_vr.ui.viewmodel.MainViewModel
import com.xxxx.emby_vr.ui.viewmodel.SearchViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    mainViewModel: MainViewModel,
    navController: NavController,
    /*
     * VR 版专用：直接起播，不跳页面（父亲 2026-10-06 晚定）。
     *
     * 电视版点「大海报 / 继续观看 / 直播」是 navigate 到播放页；VR 版的播放由原生
     * 播放器负责，面板只需要把「播哪一部」报上去。跳那个转发页会让海报墙先黑一下
     * —— 面板原地不动，一帧都不闪。
     */
    onPlayNow: ((mediaId: String, positionTicks: Long) -> Unit)? = null,
) {
    /*
     * 整屏包一层 Box（父亲 2026-10-09：「首页菜单按钮扣扳机点不开」）。
     *
     * 起根原因是菜单原来用弹窗窗口实现 —— 窗口没落在 VR 面板那张虚拟屏上，
     * 日志里能看到点击**命中了**（落点命中控件: top:menu → 控件动作已调用），
     * 界面却什么都不出。现在菜单改成**同一棵界面树里的浮层**，
     * 由这里的 Box 保证它铺满整屏、并且排在内容之后（画在最上层）。
     */
    Box(modifier = Modifier.fillMaxSize()) {
        /*
         * 整屏兜底矩形（父亲 2026-10-10）：放在内容**之前**，只负责让首页空白处
         * 「有东西可命中」—— 扣扳机落在空白上时不会回退成 OK 键去激活焦点上的东西
         * （那正是"点顶栏/点空白却把大海报的片子播起来"的原因）。
         * 查表取面积最小的，所以海报、按钮这些真控件永远优先于它。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .vrClickBlocker(key = "home:bg")
        )
    val context = LocalContext.current
    /*
     * 搜索浮层（父亲 2026-10-10 定）：原来的"菜单"整个去掉，放大镜挪到左上角，
     * 点开浮出一块面板（输入框 + 片名/演员单选 + 搜索），结果显示在同一块浮层里，
     * 后面的海报墙保持不动。
     */
    var showSearch by remember { mutableStateOf(false) }
    val searchViewModel: SearchViewModel = viewModel()

    // 获取 serverUrl
    val repository = remember { EmbyRepository.getInstance(context) }
    val serverUrl = repository.serverUrl ?: ""

    // 从 HomeViewModel 获取数据
    val resumeItems = homeViewModel.resumeItems
    val libraryLatestItems = homeViewModel.libraryLatestItems
    val favoriteItems = homeViewModel.favoriteItems
    val liveChannels = homeViewModel.liveChannels
    val liveTvView = homeViewModel.liveTvView
    val isLoading = homeViewModel.isLoading
    val errorMessage = homeViewModel.errorMessage
    val lastError = homeViewModel.lastError

    // 焦点是否还在顶部大片头上（离开就暂停自动轮播，父亲 2026-09-30 定）
    var heroFocused by remember { mutableStateOf(false) }

    // 剧集级元数据：单集(Episode)本身没有评分/类型/分级，这些都在所属剧集(Series)上。
    // 按 ParentBackdropItemId 批量取一次（1 个请求），供大片头显示完整元数据行。
    var seriesMeta by remember { mutableStateOf<Map<String, BaseItemDto>>(emptyMap()) }
    LaunchedEffect(resumeItems) {
        val ids = (resumeItems ?: emptyList())
            .mapNotNull { it.parentBackdropItemId ?: it.seriesId }
            .distinct()
        if (ids.isEmpty()) return@LaunchedEffect
        runCatching { repository.getItemsByIds(ids) }
            .onSuccess { list -> seriesMeta = list.associateBy { it.id ?: "" } }
            .onFailure { ErrorHandler.logError("HomeScreen", "取剧集元数据失败", it) }
    }

    /*
     * 播放回来要重新拉数据（父亲 2026-10-05：进度/继续观看不跟服务端同步）。
     * 面板在播放期间没销毁，不主动刷新就一直是播放前的那份。
     */
    val playbackVersion = com.xxxx.emby_vr.data.PlaybackSync.version.intValue
    LaunchedEffect(playbackVersion) {
        if (playbackVersion > 0) homeViewModel.refresh()
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            android.widget.Toast.makeText(context, errorMessage, android.widget.Toast.LENGTH_LONG).show()
            homeViewModel.clearError()
        }
    }

    // 检查更新
    LaunchedEffect(Unit) {
        mainViewModel.checkUpdate()
    }

    fun goPlay(item: BaseItemDto) {
        val id = item.id ?: ""
        val userData = item.userData
        val position = userData?.playbackPositionTicks ?: 0L
        // 记住"从哪一条走的"，返回时把焦点送回这张卡（父亲 2026-10-02）
        FocusMemory.lastItemId = id
        DiagLog.w(context, "focusSave", "去播放 id=$id")
        val playNow = onPlayNow
        if (playNow != null) {
            // VR 版：面板原地不动，直接把片子交给原生播放器
            playNow(id, position)
        } else {
            navController.navigate("player/$id?position=$position")
        }
    }

    /** 首页条目 → 进详情页(剧集进剧集详情,其余进通用详情);"继续观看"仍保留一键续播 */
    fun openDetail(item: BaseItemDto) {
        val id = item.id ?: return
        FocusMemory.lastItemId = id
        DiagLog.w(context, "focusSave", "去详情 id=$id")
        if (item.isSeries) {
            navController.navigate("series/$id")
        } else {
            navController.navigate("media/$id")
        }
    }

    // Calculate User Info
    val currentAccountId = repository.currentAccountId
    val currentAccount = repository.savedAccounts.find { it.id == currentAccountId }
    val userInfo = if (currentAccount != null) {
        val domain = try {
            val uri = java.net.URI(currentAccount.serverUrl)
            uri.host ?: currentAccount.serverUrl
        } catch (e: Exception) {
            currentAccount.serverUrl
        }
        "${currentAccount.username}@$domain"
    } else {
        null
    }

    // === 焦点回原位（父亲 2026-10-02）===
    // 进详情页/播放页前记下的条目 id；返回首页时据它定位到第几行、行内哪一条，
    // 先滚动到那一行，再让那张卡自己 requestFocus。
    //
    // 关键：**不能挂在"首次组合"上**。返回首页时这个页面通常还在内存里（没被销毁重建），
    // 那次 LaunchedEffect 不会再跑 → 焦点恢复永远不执行，表现为返回后整页没有任何焦点
    //（2026-10-02 实测：A2 截图无绿框、列表还停在顶部）。改成监听"首页重新成为当前页"。
    var restoreId by remember { mutableStateOf<String?>(null) }
    var heroFocusSignal by remember { mutableIntStateOf(0) }

    val heroItems = resumeItems ?: emptyList()
    // 「我的媒体库」那一排的第一张是「电视直播」入口（服务器上的 LiveTV 视图，自带主图）
    val libRow = listOfNotNull(liveTvView) + (libraryLatestItems ?: emptyList())
    val liveRow = liveChannels ?: emptyList()
    val resumeRow = resumeItems ?: emptyList()
    val favRow = favoriteItems ?: emptyList()
    val libLatestRows = libRow.filter { !it.latestItems.isNullOrEmpty() }

    // 卡片行的条目 id（**不含大片头那一行**：大片头里没有可聚焦的卡片，
    // 一旦把它的条目算进来，"继续观看"里同一集会被匹配到第 0 行 → 滚回顶部 → 那行压根没被
    // 画出来 → 谁都拿不到焦点。2026-10-02 实测日志：`恢复 id=3953271 行号=0`）
    val cardRowIds: List<List<String>> = buildList {
        add(libRow.mapNotNull { it.id })
        if (liveRow.isNotEmpty()) add(liveRow.mapNotNull { it.id })
        if (resumeRow.isNotEmpty()) add(resumeRow.mapNotNull { it.id })
        if (favRow.isNotEmpty()) add(favRow.mapNotNull { it.id })
        libLatestRows.forEach { r -> add((r.latestItems ?: emptyList()).mapNotNull { it.id }) }
    }
    // 大片头在列表里占一行（有才占），算滚动目标时要加回去
    val heroRowOffset = if (heroItems.isNotEmpty()) 1 else 0
    val listState = rememberLazyListState()
    val navEntry by navController.currentBackStackEntryAsState()
    val isCurrentScreen = navEntry?.destination?.route == "home"
    LaunchedEffect(isCurrentScreen) {
        if (!isCurrentScreen) return@LaunchedEffect
        val id = FocusMemory.consume() ?: return@LaunchedEffect
        val cardRow = cardRowIds.indexOfFirst { it.contains(id) }
        val inHero = heroItems.any { it.id == id }
        DiagLog.w(context, "focusRestore",
            "恢复 id=$id 卡片行=$cardRow 在片头内=$inHero 卡片行数=${cardRowIds.size}")
        if (cardRow >= 0) {
            listState.scrollToItem(cardRow + heroRowOffset)
            delay(80)                 // 等这一行铺完，再让卡片要焦点
            restoreId = id
        } else {
            // 目标不在首页这些行里（例如从库页进去播的）：退回大片头，别让整页没人聚焦
            restoreId = null
            heroFocusSignal++
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        /*
         * 首页数据出问题时，把原因写在屏幕最上面（VR 里看不到系统 Toast）。
         */
        if (!lastError.isNullOrBlank()) {
            Text(
                text = "首页数据异常：$lastError",
                color = Color(0xFFFF7A7A),
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 32.dp, top = 8.dp),
            )
        }

        // 顶部状态栏
        TopStatusBar(
            currentVersion = mainViewModel.currentVersion,
            newVersion = mainViewModel.newVersion,
            needUpdate = mainViewModel.needUpdate,
            userInfo = userInfo,
            /*
             * 放大镜放**右边、头像的左边**（父亲 2026-10-12：原来在左上角，
             * 现在挪到右侧跟头像并排）。左侧腾出来的位置由版本号顶上 ——
             * 所以这里不再传 onLeadingSearchClick（左组只剩版本号和更新提示，
             * 自然贴到最左，也就是原来放大镜那个位置）。
             */
            showSearchButton = true,
            onSearchClick = { showSearch = true },
            onUserInfoClick = {
                navController.navigate("account")
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 数据未加载完成时显示 Loading 组件
//        if(isLoading){
//            Loading()
//        }
        if (libraryLatestItems != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // 顶部大片头：取「继续观看」前 6 部，5 秒自动切、左右键手动切、OK 续播
                // （父亲 2026-09-30 定：放在滚动区里，往下滚时它跟着上移，下面的内容区才够大）
                // 返回首页时如果焦点要送回下面某张卡（pendingRow >= 0），大片头就不再抢焦点
                if (heroItems.isNotEmpty()) {
                    item {
                        HomeHeroCarousel(
                            items = heroItems,
                            serverUrl = serverUrl,
                            autoAdvance = heroFocused,   // 焦点在大图上才轮播，移到下面即暂停
                            onOpenItem = { item -> goPlay(item) },
                            onFocusChanged = { heroFocused = it },
                            // 元数据用所属剧集的（单集没有评分/类型/分级）
                            seriesMeta = seriesMeta,
                            requestInitialFocus = FocusMemory.peek() == null,
                            focusSignal = heroFocusSignal
                        )
                    }
                }

                // 我的媒体库
                item {
                    MediaSection(
                        title = stringResource(R.string.my_libraries),
                        items = libRow,
                        isMyLibrary = true,
                        serverUrl = serverUrl,
                        onItemSelected = { item ->
                            val id = item.id ?: ""
                            val title = item.name ?: ""
                            FocusMemory.lastItemId = null   // 进库走库页自己的焦点恢复
                            if (item.collectionType.equals("livetv", ignoreCase = true)) {
                                // 电视直播：走库页，类型 TvChannel → 频道以库网格样式列出，点了直接播
                                navController.navigate("library/$id?libraryName=$title&type=TvChannel")
                            } else {
                                val firstItem = item.latestItems?.firstOrNull()
                                val type = firstItem?.type ?: ""
                                navController.navigate("library/$id?libraryName=$title&type=$type")
                            }
                        },
                        // 「电视直播」图块是网络返回后才插到第一位；LazyRow 默认保持"原第一张"的位置，
                        // 新图块会被挤到屏幕左边外（父亲 2026-10-02 报"图标跑到左侧外面"）→ 出现后滚回行首
                        scrollToStartSignal = liveTvView?.id
                    )
                }

                /*
                 * 网络媒体库（父亲 2026-10-13）：B站 / 腾讯视频 / 爱奇艺 三张媒体库海报。
                 * 点进去和进普通 Emby 媒体库是同一个位置 —— 只是详情页的内容换成了该网站的网页版。
                 */
                item {
                    WebLibraryRow(onOpen = { site -> navController.navigate("weblib/$site") })
                }

                // 电视直播（简单版：频道列表，选中直接播；父亲 2026-10-02 定）
                if (liveRow.isNotEmpty()) {
                    item {
                        val liveTitle = stringResource(R.string.live_tv)
                        LiveTvRow(
                            title = liveTitle,
                            channels = liveRow,
                            serverUrl = serverUrl,
                            onChannelClick = { item -> goPlay(item) },
                            // 标题可点 → 进直播列表页（父亲 2026-10-11 定）
                            onTitleClick = {
                                val id = liveTvView?.id
                                if (id != null) {
                                    FocusMemory.lastItemId = null
                                    navController.navigate(
                                        "library/$id?libraryName=${android.net.Uri.encode(liveTitle)}" +
                                            "&type=TvChannel"
                                    )
                                }
                            },
                            pendingFocusId = restoreId
                        )
                    }
                }

                // 继续观看（标题可点 → 继续观看列表页；父亲 2026-10-11 定）
                if (resumeRow.isNotEmpty()) {
                    item {
                        val resumeTitle = stringResource(R.string.continue_watching)
                        MediaSection(
                            title = resumeTitle,
                            items = resumeRow,
                            isShowImg17 = true,
                            isContinueWatching = true,
                            serverUrl = serverUrl,
                            onItemSelected = { item -> goPlay(item) },
                            onTitleClick = {
                                FocusMemory.lastItemId = null
                                navController.navigate(
                                    "list/resume?title=${android.net.Uri.encode(resumeTitle)}"
                                )
                            },
                            focusTarget = restoreId
                        )
                    }
                }

                // 收藏（标题可点 → 收藏列表页；父亲 2026-10-11 定）
                if (favRow.isNotEmpty()) {
                    item {
                        val favTitle = stringResource(R.string.favorite)
                        MediaSection(
                            title = favTitle,
                            items = favRow,
                            isShowImg17 = true,
                            serverUrl = serverUrl,
                            onItemSelected = { item -> openDetail(item) },
                            onTitleClick = {
                                FocusMemory.lastItemId = null
                                navController.navigate(
                                    "list/favorites?title=${android.net.Uri.encode(favTitle)}"
                                )
                            },
                            focusTarget = restoreId
                        )
                    }
                }

                // 各库最新内容（空库不占一行——合集/PikPak电影/115蓝光原盘 没有最新条目，
                // 原来会渲染一行空占位；「我的媒体库」那一排仍然保留所有库的入口）
                itemsIndexed(
                    libLatestRows,
                    key = { _, library -> library.id ?: library.hashCode() }
                ) { _, library ->
                    val libTitle = library.name ?: ""
                    MediaSection(
                        title = libTitle,
                        items = library.latestItems ?: emptyList(),
                        serverUrl = serverUrl,
                        onItemSelected = { item -> openDetail(item) },
                        // 标题可点 → 进这个库的列表页（父亲 2026-10-11 定）
                        onTitleClick = {
                            val id = library.id
                            if (id != null) {
                                FocusMemory.lastItemId = null
                                val t = library.latestItems?.firstOrNull()?.type ?: ""
                                navController.navigate(
                                    "library/$id?libraryName=${android.net.Uri.encode(libTitle)}&type=$t"
                                )
                            }
                        },
                        focusTarget = restoreId
                    )
                }
            }
        }
    }

        /*
         * 搜索浮层（放在内容之后 = 画在最上层）。
         *
         * 输入框与**搜索结果都在这一块浮层里**，后面的海报墙保持不动
         * （父亲 2026-10-10：结果不要占海报墙）。
         */
        if (showSearch) {
            SearchOverlay(
                searchViewModel = searchViewModel,
                serverUrl = serverUrl,
                onOpenItem = { item -> openDetail(item) },
                onClose = { showSearch = false },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MediaSection(
    title: String,
    items: List<BaseItemDto>,
    isMyLibrary: Boolean = false,
    isShowImg17: Boolean = false,
    isContinueWatching: Boolean = false,
    serverUrl: String,
    onItemSelected: (BaseItemDto) -> Unit,
    /*
     * 行标题点击（父亲 2026-10-11）：点标题进这一行对应的列表页。
     * 为 null 时标题不可点（例如「我的媒体库」那一行 —— 它本身就是库入口集合，
     * 标题没有唯一对应的库，父亲明确不做）。
     */
    onTitleClick: (() -> Unit)? = null,
    // 从详情页返回时要恢复焦点到的那一条（父亲 2026-10-02）
    focusTarget: String? = null,
    // 变成非空时把这一行滚回行首（用于"首项是异步插入"的场景）
    scrollToStartSignal: Any? = null,
) {
    val maxLength = when {
        isMyLibrary -> 260.dp   // P1：库入口按官方做成宽银幕大图块
        else -> 214.dp
    }

    // P1：库入口固定 16:9（官方"我的媒体"就是宽银幕图块），内容行才按海报比例
    val maxAspectRatio = if (isMyLibrary) 1.7778f else items.mapNotNull {
        val ratio = it.primaryImageAspectRatio?.toFloat()
        if (ratio == null || ratio == 1.0f) null else ratio
    }.maxOrNull() ?: 0.666f

    val imgWidth = if (maxAspectRatio >= 1f) {
        maxLength
    } else {
        (maxLength.value * maxAspectRatio).dp
    }
    // 这里原来有一段"「继续观看」行组合时强制 requestFocus 到第一张卡"的老逻辑。
    // 首页加了顶部大片头之后，两处同时抢焦点 → 上下键焦点顺序错乱（父亲 2026-09-30 实测）。
    // 现在只由大片头在首次进入时请求一次焦点，其余交给系统的方向键导航。

    Column {
        Text(
            text = title,
            color = Color.White,
            // 与首页大片头的剧名字号保持一致（父亲 2026-09-30：我的媒体库/继续观看等标题同大片头剧集名）
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            /*
             * 可点时把点击登记在**留白外层**，让整块标题（文字 + 上下留白）都能点。
             *
             * 留白由 20/16 调成 24/12（合计仍 36dp，视觉位置一点不变）：点击区高度从
             * "只有文字 38 像素"变成"文字 + 36dp"，好瞄得多；标题下沿到海报行还剩
             * 12dp（面板 1dp = 1.5 像素 → 18 像素），仍大于光柱判定容差 16 像素，
             * 不会点标题点到海报（父亲 2026-10-11 要求先核对间距）。
             */
            modifier = Modifier
                /*
                 * 高亮框与下面的海报左对齐、标题在框里居中（父亲 2026-10-11）。
                 *
                 * 海报行的左右留白是 24dp（见下面 LazyRow 的 contentPadding），
                 * 所以框的左边缘取 24dp；框内再留 8dp 对称内边距 —— 标题在框里天然居中，
                 * 而文字本身仍落在 32dp 处（外 24 + 内 8），与原来位置一致。
                 * 原来是 24/12 不对称的上下留白被包进框里，框偏高、标题偏下。
                 * 总高仍是「文字 + 36dp」，和之前一样好瞄。
                 */
                .padding(start = 24.dp, top = 12.dp, bottom = 8.dp)
                .then(
                    if (onTitleClick != null) {
                        Modifier.vrClickTarget(key = "row-title:$title", onActivate = onTitleClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        if (items.isEmpty()) {
            NoData(modifier = Modifier.height(maxLength))
        } else {
            // 要恢复焦点的那条可能不在首屏，先横向滚过去，再让卡片自己 requestFocus
            val rowState = rememberLazyListState()
            LaunchedEffect(scrollToStartSignal) {
                if (scrollToStartSignal != null) runCatching { rowState.scrollToItem(0) }
            }
            LaunchedEffect(focusTarget) {
                val idx = items.indexOfFirst { it.id == focusTarget }
                if (idx >= 0) rowState.scrollToItem(idx)
            }
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(
                    items,
                    key = { _, item -> item.id ?: item.hashCode() }
                ) { index, item ->

                    val modifier = Modifier



                    BuildItem(
                        modifier = modifier,
                        item = item,
                        aspectRatio = maxAspectRatio,
                        imgWidth = imgWidth,
                        isShowImg17 = isShowImg17,
                        isMyLibrary = isMyLibrary,
                        serverUrl = serverUrl,
                        onItemClick = { onItemSelected(item) },
                        // 长按呼出菜单那条路随首页菜单一起去掉了（父亲 2026-10-10）
                        onMenuClick = null,
                        autoFocus = item.id != null && item.id == focusTarget,
                        rememberFocus = true,
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}
