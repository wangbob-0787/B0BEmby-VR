package com.xxxx.emby_vr.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.model.PersonInfo
import androidx.compose.ui.res.stringResource
import com.xxxx.emby_vr.R
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.ui.components.BuildItem
import com.xxxx.emby_vr.Utils
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.ui.viewmodel.DetailViewModel
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.delay

@Composable
fun MediaDetailScreen(
    seriesId: String,
    detailViewModel: DetailViewModel,
    onNavigateToSeries: (String) -> Unit,
    onNavigateToPlayer: (BaseItemDto) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { EmbyRepository.getInstance(context) }
    val serverUrl = repository.serverUrl ?: ""
    
    val mediaInfo = detailViewModel.mediaInfo
    val playButtonFocusRequester = remember { FocusRequester() }
    // 顶部块（标题/元数据/海报区域）的焦点锚点：进页面时焦点放这里，不自动滚到下面的按钮
    //（父亲 2026-10-02：一进来标题和元数据就被"聚焦续播按钮"带得滚出屏幕）
    val headerFocusRequester = remember { FocusRequester() }

    // Series Data State
    var seasons by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var episodes by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var resume by remember { mutableStateOf<BaseItemDto?>(null) }
    var isLoadingSeriesData by remember { mutableStateOf(false) }
    // 季列表与"当前季"提到这里：这样上面的「续播/重播」按钮按下键也能落到**当前季**那颗胶囊上
    //（父亲 2026-10-02：焦点进季行时要落在当前季，多季时尤其重要）
    val seasonList = seasons ?: emptyList()
    val seasonFocusers = remember(seasonList.size) { List(seasonList.size) { FocusRequester() } }
    var selectedSeasonIndex by remember(seasonList.size) { mutableIntStateOf(0) }
    // 每行"用户上次停在哪一项"（父亲 2026-10-02：上下轮动要按用户选的焦点落回；
    // 集行与演员行都要记）
    var lastPillIndex by remember(seasonList.size) { mutableIntStateOf(0) }
    var lastEpisodeIndex by remember { mutableIntStateOf(0) }
    var lastCastIndex by remember { mutableIntStateOf(0) }
    var lastCrewIndex by remember { mutableIntStateOf(0) }
    val castPeople = remember(mediaInfo?.people) {
        mediaInfo?.people?.filter {
            it.type.equals("Actor", ignoreCase = true) || it.type.equals("GuestStar", ignoreCase = true)
        } ?: emptyList()
    }
    val crewPeople = remember(mediaInfo?.people) {
        mediaInfo?.people?.filter {
            val t = it.type ?: ""
            t.equals("Director", true) || t.equals("Writer", true) ||
                t.equals("Producer", true) || t.equals("Composer", true)
        } ?: emptyList()
    }
    val castFocusers = remember(castPeople.size) { List(castPeople.size) { FocusRequester() } }
    val crewFocusers = remember(crewPeople.size) { List(crewPeople.size) { FocusRequester() } }
    val episodeFocusers = remember(episodes?.size) {
        List(episodes?.size ?: 0) { FocusRequester() }
    }

    LaunchedEffect(seriesId) {
        detailViewModel.loadMediaInfo(seriesId)
    }

    LaunchedEffect(mediaInfo) {
        seasons = null
        episodes = null
        resume = null
        val isAlbumHere = mediaInfo?.type.equals("MusicAlbum", ignoreCase = true) == true
        if (mediaInfo != null && (mediaInfo.isSeries || isAlbumHere) && !isLoadingSeriesData) {
            isLoadingSeriesData = true
            try {
                if (isAlbumHere) {
                    // 音乐专辑：把专辑下的歌曲取出来（原来这里什么都不做 → 详情页看不到歌，无法选歌）
                    episodes = detailViewModel.getSeriesList(seriesId)
                    seasons = null
                    resume = null
                } else {
                    val seasonsList = detailViewModel.getSeasonList(seriesId)
                    val episodesList = detailViewModel.getSeriesList(seriesId)
                    val x = detailViewModel.getResumeItem(seriesId)

                    seasons = seasonsList
                    episodes = episodesList
                    resume = x
                }
            } catch (e: Exception) {
                ErrorHandler.logError("MediaDetailScreen", "加载数据失败", e)
            } finally {
                isLoadingSeriesData = false
            }
        }
    }

    // 进页面的默认焦点：放在顶部块（标题/元数据/海报）上，页面不滚动
    LaunchedEffect(mediaInfo, isLoadingSeriesData) {
        if (mediaInfo != null && !isLoadingSeriesData) {
            delay(150)
            runCatching { headerFocusRequester.requestFocus() }
        }
    }

    if (mediaInfo == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    } else {
        // Construct Backdrop URL
        val backdropTags = mediaInfo.backdropImageTags
        val parentBackdropTags = mediaInfo.parentBackdropImageTags
        val parentBackdropItemId = mediaInfo.parentBackdropItemId

        var finalBackdropUrl = ""
        if (!backdropTags.isNullOrEmpty()) {
            finalBackdropUrl =
                "$serverUrl/emby/Items/${mediaInfo.id}/Images/Backdrop?maxWidth=1920&tag=${backdropTags[0]}&quality=80"
        } else if (!parentBackdropTags.isNullOrEmpty() && parentBackdropItemId != null) {
            finalBackdropUrl =
                "$serverUrl/emby/Items/$parentBackdropItemId/Images/Backdrop?maxWidth=1920&tag=${parentBackdropTags[0]}&quality=80"
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 有剧照时不再垫一层纯黑：背景本来就是深色，垫黑纯属多一次全屏不透明填充
                //（2026-10-02 电视端 GPU 实测：详情页单帧 GPU 15.9ms，压掉这层后重测）。
                .then(
                    if (finalBackdropUrl.isEmpty()) Modifier.background(Color.Black)
                    else Modifier
                )
        ) {
            // 1. Backdrop Layer（用 AsyncImage 而非 SubcomposeAsyncImage：这里没有自定义
            // loading/error 槽，子组合纯属白付开销；低端电视上每次都多一次子组合）
            if (finalBackdropUrl.isNotEmpty()) {
                AsyncImage(
                    model = finalBackdropUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.6f
                )
            }

            // 2. Gradient Overlay (Scrim)：只铺下面 65%，顶部本来就是全透明
            //（2026-10-02：整屏渐变每帧都是一次全屏混合，是详情页 GPU 时间偏高的原因之一）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.65f)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = 0.9f),
                                Color.Black
                            )
                        )
                    )
            )

            // 3. Main Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 外层只留上下；左右不留 —— 行容器左右贴屏幕边，
                    // 内容靠行内 contentPadding 缩进（父亲 2026-10-02 定的结构）
                    .padding(vertical = 32.dp)
            ) {
                // 顶部焦点锚点：必须放在**滚动内容里**，这样从下面的按钮按 ↑ 回到它时页面才会滚回顶部
                //（放在滚动区外面的话只能"丢掉焦点"，页面不动；父亲 2026-10-02 实测）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .focusRequester(headerFocusRequester)
                        .focusProperties { down = playButtonFocusRequester }
                        .focusable()
                )

                // Header Section：左 = 标题/元数据/简介/按钮；右 = 剧集 Logo（同排，各占一份宽度，
                // 所以永远不会和左边的文字重叠；父亲 2026-10-02 要求 logo 自适应大小）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Info Column —— 剧集页左边与下面「第 1 季」胶囊对齐（同一个左边缘），
                    // 上边留一段距离；右边让给 logo（父亲 2026-10-02）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = mediaInfo.name ?: "",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )

                        // 元数据两行文字（官方形态：剧集/电影一致）
                        //   第一行 ★评分  年份-现在  于 制作方  分级
                        //   第二行 类型 · N 播出季（电影没有季，只显示类型）
                        val yearText = when {
                            mediaInfo.productionYear == null -> null
                            mediaInfo.status.equals("Continuing", ignoreCase = true) ->
                                "${mediaInfo.productionYear} - 现在"
                            else -> mediaInfo.productionYear.toString()
                        }
                        val studioName = mediaInfo.seriesStudio
                            ?: mediaInfo.studios?.firstOrNull()?.name
                        val metaLine1 = listOfNotNull(
                            mediaInfo.communityRating?.let { "★$it" },
                            yearText,
                            studioName?.takeIf { it.isNotBlank() }?.let { "于 $it" },
                            mediaInfo.officialRating?.takeIf { it.isNotBlank() }
                        ).joinToString("    ")
                        val seasonCount = seasons?.size ?: 0
                        val metaLine2 = listOfNotNull(
                            mediaInfo.genres?.firstOrNull()?.takeIf { it.isNotBlank() },
                            if (seasonCount > 0) "$seasonCount 播出季" else null
                        ).joinToString(" · ")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (metaLine1.isNotBlank()) Text(
                                text = metaLine1,
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 15.sp
                            )
                            if (metaLine2.isNotBlank()) Text(
                                text = metaLine2,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 15.sp
                            )
                        }

                        // Overview —— 官方顺序是「标题 → 元数据 → 简介 → 按钮」（父亲 2026-10-02）
                        mediaInfo.overview?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = Color.White.copy(alpha = 0.9f),
                                    lineHeight = TextUnit(1.5f, TextUnitType.Em)
                                ),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 动作按钮（父亲 2026-10-02 定：只要两个，方块图标 + 下方文字）
                        //   续播 —— 从上次看到的位置接着播
                        //   重播 —— 从上次那一集的开头（0 分钟）开始播
                        val playTarget = resume
                            ?: if (mediaInfo.isSeries) episodes?.firstOrNull() else mediaInfo
                        // 没播过（没有续播点）时，第一个按钮显示「播放」而不是「续播」（父亲 2026-10-02）
                        val hasProgress = (mediaInfo.userData?.playbackPositionTicks ?: 0L) > 0
                            || (resume?.userData?.playbackPositionTicks ?: 0L) > 0
                            || resume != null
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ActionTile(
                                icon = Icons.Default.PlayArrow,
                                label = if (hasProgress) stringResource(R.string.resume_play)
                                else stringResource(R.string.play),
                                focusRequester = playButtonFocusRequester,
                                upFocus = headerFocusRequester,
                                downFocus = seasonFocusers.getOrNull(lastPillIndex)
                            ) {
                                playTarget?.let { onNavigateToPlayer(it) }
                            }
                            ActionTile(
                                icon = Icons.Default.Replay,
                                label = stringResource(R.string.replay),
                                upFocus = headerFocusRequester,
                                downFocus = seasonFocusers.getOrNull(lastPillIndex)
                            ) {
                                // 抹掉 userData → 播放页拿不到续播位置，即从 0 分钟开始
                                playTarget?.let { onNavigateToPlayer(it.copy(userData = null)) }
                            }
                        }
                        if (resume != null) Box(
                            modifier = Modifier
                                .width(150.dp)
                                .height(3.dp)
                                .background(Color.Gray.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = resume?.playbackProgress ?: 0f)
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                        }
                        if (((mediaInfo.userData?.playbackPositionTicks
                                ?: 0L) > 0)
                        ) Box(
                            modifier = Modifier
                                .width(150.dp)
                                .height(3.dp)
                                .background(Color.Gray.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = mediaInfo.playbackProgress)
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                        }
                        if (resume != null) Text(
                            text = "S${resume?.parentIndexNumber}:E${resume?.indexNumber} ${resume?.name}",
                            color = MaterialTheme.colorScheme.onSecondary
                        )

                    }

                    // 右侧 Logo：占剩余宽度、按比例缩放（Fit），不设固定宽度（剧集/电影一致）
                    run {
                        val logoTag = mediaInfo.imageTags?.get("Logo")
                        if (!logoTag.isNullOrEmpty()) {
                            AsyncImage(
                                model = "$serverUrl/emby/Items/${mediaInfo.id}/Images/Logo?maxWidth=800&tag=$logoTag&quality=90",
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                // 父亲 2026-10-02：固定宽度、等比缩放（小图放大、大图缩小），高度自动
                                modifier = Modifier
                                    .width(400.dp)
                                    .padding(top = 12.dp, end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 季选择器 + 只渲染当前季的集（2026-09-27 按官方形态重做）
                // 原实现把所有季一次铺开：想看第 5 季要从第 1 季第 1 集一路按 ↓（上百次）
                if (mediaInfo.isSeries && !seasons.isNullOrEmpty()) {
                    val firstEpisodeFocus = remember { FocusRequester() }
                    val noEpisodesText = stringResource(R.string.no_episodes_found)
                    // 当前季的集（季胶囊的"下键去向"和集行都要用，所以先算出来）
                    val currentSeasonName = seasonList.getOrNull(selectedSeasonIndex)?.name ?: ""
                    val seasonEpisodes =
                        episodes?.filter { it.seasonName == currentSeasonName } ?: emptyList()

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        // 上下留出焦点放大（1.03x + 白描边）需要的余量，别被容器裁掉
                        contentPadding = PaddingValues(
                            start = 40.dp, end = 40.dp, top = 14.dp, bottom = 22.dp
                        )
                    ) {
                        itemsIndexed(seasonList) { index, season ->
                            val selected = index == selectedSeasonIndex
                            Surface(
                                onClick = { selectedSeasonIndex = index },
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    // 父亲 2026-10-02：**只有聚焦才是绿底**；当前季不聚焦时也是暗灰
                                    containerColor = Color(0xFF3A3A3A),
                                    contentColor = Color.White,
),
                                modifier = Modifier
                                    .width(DetailButtonWidth)
                                    .height(DetailButtonHeight)
                                    .focusRequester(seasonFocusers[index])
                                    .vrClickTarget(
                                        key = "season:$index",
                                        focusRequester = seasonFocusers[index],
                                        onActivate = { selectedSeasonIndex = index },
                                    )
                                    .onFocusChanged { if (it.isFocused) lastPillIndex = index }
                                    .focusProperties {
                                        // 上键回「续播」按钮；下键进集行"上次停的那一集"（默认第一集）
                                        up = playButtonFocusRequester
                                        down = episodeFocusers.getOrNull(
                                            lastEpisodeIndex.coerceIn(0, (seasonEpisodes.size - 1).coerceAtLeast(0))
                                        ) ?: firstEpisodeFocus
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = season.name ?: "",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (seasonEpisodes.isNotEmpty()) {
                        // 集列表：官方形态 —— 横向滚动的 16:9 集卡
                        // （缩略图 + 第一行剧名 + 第二行 "S1:E1 集名"，由 BuildItem(isShowImg17) 出）
                        // 季的切换仍是我们的胶囊行（父亲 2026-10-02 定：季用自己的方式，集用官方方式）
                        LazyRow(
                            contentPadding = PaddingValues(
                                start = 40.dp, end = 40.dp, top = 16.dp, bottom = 26.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            itemsIndexed(
                                seasonEpisodes,
                                key = { _, ep -> ep.id ?: ep.hashCode() }
                            ) { idx, ep ->
                                BuildItem(
                                    modifier = Modifier,
                                    item = ep,
                                    aspectRatio = 16f / 9f,
                                    imgWidth = 240.dp,
                                    isShowImg17 = true,
                                    isMyLibrary = false,
                                    serverUrl = serverUrl,
                                    onItemClick = { onNavigateToPlayer(ep) },
                                    focusRequester = episodeFocusers.getOrNull(idx) ?: firstEpisodeFocus,
                                    upFocus = seasonFocusers.getOrNull(lastPillIndex),
                                    downFocus = castFocusers.getOrNull(lastCastIndex),
                                    onFocused = { lastEpisodeIndex = idx },
                                    // 每集自己的静帧（默认回落会取到父级剧照 → 每张一样）
                                    imageUrlOverride = Utils.getEpisodeStillUrl(serverUrl, ep)
                                )
                            }
                        }
                    } else {
                        Text(text = noEpisodesText, color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                // 专辑曲目：音乐库专辑详情里列出歌曲，可逐首选择播放
                if (mediaInfo.type.equals("MusicAlbum", ignoreCase = true) && !episodes.isNullOrEmpty()) {
                    Text(
                        text = stringResource(R.string.tracks),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        episodes!!.forEachIndexed { idx, song ->
                            SongRow(index = idx + 1, song = song) { onNavigateToPlayer(song) }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                // People List（Cast / Crew）—— 只在有人员数据时渲染这几行
                if (castPeople.isNotEmpty() || crewPeople.isNotEmpty()) {
                    if (castPeople.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.cast),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 40.dp, bottom = 16.dp)
                        )
                        PersonRow(
                            people = castPeople,
                            serverUrl = serverUrl,
                            focusers = castFocusers,
                            upFocus = episodeFocusers.getOrNull(
                                lastEpisodeIndex.coerceIn(0, (episodeFocusers.size - 1).coerceAtLeast(0))
                            ),
                            downFocus = crewFocusers.getOrNull(lastCrewIndex),
                            onFocused = { lastCastIndex = it }
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                    if (crewPeople.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.crew),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 40.dp, bottom = 16.dp)
                        )
                        PersonRow(
                            people = crewPeople,
                            serverUrl = serverUrl,
                            focusers = crewFocusers,
                            upFocus = castFocusers.getOrNull(lastCastIndex),
                            onFocused = { lastCrewIndex = it }
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }

                // Details Box (Bottom)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.details),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )

                        // Meta Rows
                        MetaRow(
                            stringResource(R.string.genres),
                            mediaInfo.genres?.joinToString(", ") ?: ""
                        )

                        val studios =
                            mediaInfo.studios?.mapNotNull { it.name }?.joinToString(", ")
                        MetaRow(stringResource(R.string.studios), studios ?: "")

                        MetaRow(
                            stringResource(R.string.premiere),
                            Utils.formatDate(mediaInfo.premiereDate)
                        )
                        MetaRow(
                            stringResource(R.string.end),
                            Utils.formatDate(mediaInfo.dateCreated)
                        )
                        MetaRow(
                            stringResource(R.string.status),
                            mediaInfo.status ?: ""
                        )
                        MetaRow(
                            stringResource(R.string.tagline),
                            mediaInfo.taglines?.joinToString(", ") ?: ""
                        )

                        val providerIds = mediaInfo.providerIds
                        val providersStr =
                            providerIds?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }
                        MetaRow(stringResource(R.string.provider_ids), providersStr ?: "")

                        MetaRow(stringResource(R.string.path), mediaInfo.path ?: "")

                        if (mediaInfo.isSeries) {
                            val seasonCount = seasons?.size ?: 0
                            val episodeCount = episodes?.size ?: 0
                            MetaRow(
                                stringResource(R.string.counts),
                                "$seasonCount ${stringResource(R.string.seasons)} · $episodeCount ${
                                    stringResource(R.string.episodes)
                                }"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
fun MetaPill(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(color = Color.White),
        )
    }
}

@Composable
fun MetaRow(label: String, value: String) {
    if (value.isNotEmpty()) {
        Surface(
            onClick = {},
            enabled = true,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            ),
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = 1f
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.width(140.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.7f)
                    )
                )
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                )
            }
        }
    }
}

@Composable
fun PersonCard(
    person: PersonInfo,
    imgWidth: Dp,
    aspectRatio: Float,
    serverUrl: String,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
) {

    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent))),
        scale = ClickableSurfaceDefaults
            .scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.2f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            pressedContentColor = MaterialTheme.colorScheme.surface,
),
        modifier = Modifier
            .width(imgWidth)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .focusProperties {
                if (upFocus != null) up = upFocus
                if (downFocus != null) down = downFocus
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .background(
                        Color(0xFF2D2D2D),
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val imageUrl = if (person.primaryImageTag != null) {
                    "$serverUrl/emby/Items/${person.id}/Images/Primary?maxWidth=300&tag=${person.primaryImageTag}&quality=80"
                } else null

                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = person.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = person.name ?: "",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )

            Text(
                text = person.role ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            )
        }
    }
}

/**
 * 一行人物卡（Cast / Crew 共用）。官方形态：竖版头像 + 姓名 + 角色名或职务。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PersonRow(
    people: List<PersonInfo>,
    serverUrl: String,
    focusers: List<FocusRequester> = emptyList(),
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onFocused: ((Int) -> Unit)? = null,
) {
    val maxAspectRatio = 0.66f
    val imgWidth = (160f * maxAspectRatio).dp
    LazyRow(
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(
            people,
            key = { index, person -> "${person.id ?: person.hashCode()}-$index" }
        ) { index, person ->
            PersonCard(
                person = person,
                imgWidth = imgWidth,
                aspectRatio = maxAspectRatio,
                serverUrl = serverUrl,
                focusRequester = focusers.getOrNull(index),
                upFocus = upFocus,
                downFocus = downFocus,
                onFocused = onFocused?.let { cb -> { cb(index) } }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRow(
    index: Int,
    song: BaseItemDto,
    onPlay: () -> Unit,
) {
    val duration = song.runTimeTicks?.let { Utils.formatRuntimeFromTicks(it) } ?: ""
    Surface(
        onClick = onPlay,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = index.toString().padStart(2, '0'),
                color = Color.Gray,
                fontSize = 14.sp
            )
            Text(
                text = song.name ?: "",
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = duration, color = Color.Gray, fontSize = 14.sp)
        }
    }
}

/**
 * 详情页动作按钮（父亲 2026-10-02 定稿形态）：
 *   长方形，和「第 N 季」胶囊同样尺寸（176×60），图标 + 文字横排居中；
 *   未选中 = 暗灰底白图标/白字；选中（有焦点）= 绿底白图标/白字。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        // 父亲 2026-10-02：获得焦点不要白边，只靠绿底表示
        border = ClickableSurfaceDefaults.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF3A3A3A),
            contentColor = Color.White,
),
        modifier = Modifier
            .width(DetailButtonWidth)
            .height(DetailButtonHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 登记进控件坐标表（VR：扣扳机按光点命中）
            .vrClickTarget(
                key = "action:$label",
                focusRequester = focusRequester,
                onActivate = onClick,
            )
            // ↑ 回背景（顶部）；↓ 落到**当前季**那颗胶囊（多季时不会跑到第一季去）
            .focusProperties {
                if (upFocus != null) up = upFocus
                if (downFocus != null) down = downFocus
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}

/** 详情页按钮统一尺寸（动作按钮与季胶囊一致；字体与元数据同为 15sp，图标 20dp） */
private val DetailButtonWidth = 108.dp
private val DetailButtonHeight = 44.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRow(
    index: Int,
    episode: BaseItemDto,
    serverUrl: String,
    focusRequester: FocusRequester? = null,
    onPlay: () -> Unit,
) {
    val thumb = Utils.getImageUrl(serverUrl, episode, true)
    val duration = episode.runTimeTicks?.let { Utils.formatRuntimeFromTicks(it) } ?: ""
    val hasSub = episode.mediaStreams?.any { it.type == "Subtitle" } == true
    val meta = buildString {
        append("S${episode.parentIndexNumber ?: 1} E${episode.indexNumber ?: index}")
        if (duration.isNotEmpty()) append("   ·   $duration")
        if (hasSub) append("   ·   CC")
    }

    Surface(
        onClick = onPlay,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            // 登记进控件坐标表：扣扳机点集行 = 播这一集
            .vrClickTarget(
                key = "episode:${episode.id ?: index}",
                focusRequester = focusRequester,
                onActivate = onPlay,
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(1.7778f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2D2D2D))
            ) {
                if (thumb.isNotEmpty()) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "$index. ${episode.name ?: ""}",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = meta, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                val overview = episode.overview
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
