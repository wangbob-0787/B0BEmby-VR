package com.xxxx.emby_vr.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.xxxx.emby_vr.panel.vrClickTarget
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.xxxx.emby_vr.data.model.BaseItemDto
import kotlinx.coroutines.delay

/**
 * 首页顶部"大片头"轮播（父亲 2026-09-30 定）
 *
 * 尺寸按参考图实测（参考图 2880×1800、比例 1.6，按占屏比例换算 1080p）：
 *   大图高度 1340/1800 = 74.4% 屏高   → 400dp
 *   标题字高 70/1800 = 3.89%          → 21sp
 *   元数据字高 33/1800 = 1.83%        → 12sp（父亲要求比实测再 +2）
 *   简介字高 28/1800 = 1.56%，共 2 行 → 11sp（同上 +2）
 *   圆点直径 14px                     → 5dp / 4dp
 *
 * 排版：标题一行；元数据一行 = ★金色星标 + 评分 | 年份 | 类型 | [分级(白边框标签)]；简介最多 2 行。
 * 整块宽度收到屏宽 55%（不左右顶满），左缘与下面内容行对齐（32dp）。
 *
 * 元数据取值：单集(Episode)本身没有评分/类型/分级，这些在所属剧集(Series)上，
 *   由 HomeScreen 按 ParentBackdropItemId 批量取好后经 seriesMeta 传进来。
 */
@Composable
fun HomeHeroCarousel(
    items: List<BaseItemDto>,
    serverUrl: String,
    modifier: Modifier = Modifier,
    // 焦点进了下面内容区时由 HomeScreen 传 false → 暂停自动轮播（父亲 2026-09-30 定）
    autoAdvance: Boolean = true,
    // 在大图上按 OK：从上次播放位置续播当前这一部（父亲 2026-09-30 补充）
    onOpenItem: (BaseItemDto) -> Unit = {},
    // 上报自身焦点：HomeScreen 用它决定"焦点离开大图就暂停轮播"
    onFocusChanged: (Boolean) -> Unit = {},
    // 剧集级元数据（key = 剧集 id）：补单集缺失的评分/类型/分级/年份
    seriesMeta: Map<String, BaseItemDto> = emptyMap(),
    // 首次进首页要不要把焦点给大图。从详情页返回时由首页传 false —— 焦点要送回原来那张卡
    //（父亲 2026-10-02：「回到上一页面时焦点要回到之前的焦点」）
    requestInitialFocus: Boolean = true,
    // 焦点兜底信号：首页"要恢复的条目找不到"时 +1，让大片头把焦点接过去（免得整页没人聚焦）
    focusSignal: Int = 0,
) {
    // 顶部大片头轮播（父亲 2026-09-30 定）
    //
    // 2026-10-02 修「没有图的剧集显示成全黑」（父亲反馈）：
    //   1) 取图 URL 必须有 tag（原来无 tag 也拼 URL → 服务端 404 → 黑屏）；
    //   2) 一张图都没有的条目直接不进轮播（宁少一条，不留黑屏）；
    //   3) 轮播里一条可用都没有 → 整块不显示。
    if (items.isEmpty()) return
    val list = remember(items, serverUrl) {
        items.filter { backdropUrlOf(it, serverUrl) != null || primaryUrlOf(it, serverUrl) != null }
            .take(6)
    }
    if (list.isEmpty()) return
    var index by remember(list) { mutableIntStateOf(0) }
    val heroFocus = remember { FocusRequester() }
    var focusRequested by rememberSaveable { mutableStateOf(false) }

    // 首次进首页把焦点给大图：轮播才跑得起来；用户按下键即进下面内容区（轮播随即暂停）
    LaunchedEffect(Unit) {
        if (!requestInitialFocus) return@LaunchedEffect
        if (focusRequested) return@LaunchedEffect
        delay(150)
        runCatching { heroFocus.requestFocus() }
        focusRequested = true
    }

    // 兜底：要恢复的卡片没找到时，把焦点接过来（首次组合时 focusSignal=0，不触发）
    LaunchedEffect(focusSignal) {
        if (focusSignal <= 0) return@LaunchedEffect
        delay(80)
        runCatching { heroFocus.requestFocus() }
    }

    // 自动切换：5 秒一次；焦点移到下面内容时暂停
    LaunchedEffect(index, autoAdvance, list.size) {
        if (!autoAdvance) return@LaunchedEffect
        if (list.size <= 1) return@LaunchedEffect
        delay(5000)
        index = (index + 1) % list.size
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp)          // 参考图实测：大图底边 1340/1800 = 74.4% 屏高
    ) {
        // 焦点层只覆盖左侧 55%（与信息块同宽）。
        // 原因：整块全宽可聚焦时，大片头的焦点中心落在屏幕正中，按 ↓ 时系统按"水平最近"
        // 会落到下面那一行的中间某张卡；收到左侧后焦点中心左移，↓ 才会落到第一张卡。
        // 父亲 2026-09-30 反馈"首页上下键焦点顺序很奇怪"即源于此。
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.55f)
                .fillMaxHeight()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .focusRequester(heroFocus)
                .focusable()
                // 登记进点击表：指着大海报扣扳机 = 续播当前这一部
                //（父亲 2026-10-05：大海报扣扳机没反应 = 没登记）
                .vrClickTarget(
                    key = "hero",
                    focusRequester = heroFocus,
                    onActivate = { onOpenItem(list[index.coerceIn(0, list.lastIndex)]) },
                )
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> {
                            index = (index - 1 + list.size) % list.size
                            true
                        }
                        Key.DirectionRight -> {
                            index = (index + 1) % list.size
                            true
                        }
                        // OK / 回车：从上次播放位置续播当前显示的这一部
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            onOpenItem(list[index.coerceIn(0, list.lastIndex)])
                            true
                        }
                        else -> false
                    }
                }
        )
        // 切换过渡：淡出/淡入各 1 秒（父亲 2026-09-30 定，原先 500ms 太快）
        Crossfade(
            targetState = index,
            animationSpec = tween(durationMillis = 1000),
            label = "hero"
        ) { i ->
            val item = list[i.coerceIn(0, list.lastIndex)]
            val meta = seriesMeta[item.parentBackdropItemId ?: item.seriesId ?: ""]
            Box(modifier = Modifier.fillMaxSize()) {
                // 只画一张图（2026-10-02 电视端 GPU 实测：同屏帧时间 30ms、其中 GPU 9ms，
                // 官方客户端同机 2ms）。原来固定先画竖版海报（55% 透明度）再叠一张横版剧照，
                // 有剧照时海报被完全盖住，却要多付一次全屏贴图 + 一次混合。
                // 现在按「横版剧照 → 竖版海报兜底」只取一张；兜底那张仍压暗到 55%，
                // 观感与原来一致。
                val backdrop = backdropUrlOf(item, serverUrl)
                val heroImg = backdrop ?: primaryUrlOf(item, serverUrl)
                if (heroImg != null) {
                    AsyncImage(
                        model = heroImg,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = if (backdrop == null) {
                            Modifier.fillMaxSize().alpha(0.55f)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
                }

                // 底部渐变：保证左下角文字在亮剧照上也读得清。
                // 只铺下面 60%（顶部两段本来就是全透明），少画四成的不透明混合。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.30f),
                                    Color.Black.copy(alpha = 0.92f)
                                )
                            )
                        )
                )

                // 左下信息块：宽度收到屏宽 55%（不左右顶满），左缘与下面内容行对齐
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.55f)
                        // 底部留出空隙，避免文字压住最底部的分页圆点（父亲 2026-09-30）
                        .padding(start = 32.dp, bottom = 30.dp)
                ) {
                    // 第一行：剧名
                    Text(
                        text = item.seriesName ?: item.name ?: "",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 第二行：★评分 | 年份 | 类型 | [分级]
                    val rating = meta?.communityRating ?: item.communityRating
                    val year = meta?.productionYear ?: item.productionYear
                    val genre = meta?.genres?.firstOrNull() ?: item.genres?.firstOrNull()
                    val cert = meta?.officialRating ?: item.officialRating
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rating != null && rating > 0) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),      // 参考图：金色星标
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "%.1f".format(rating),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "  |  ",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                        if (year != null && year > 0) {
                            Text(
                                text = year.toString(),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "  |  ",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                        if (!genre.isNullOrBlank()) {
                            Text(
                                text = genre,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!cert.isNullOrBlank()) {
                                Text(
                                    text = "  |  ",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        if (!cert.isNullOrBlank()) {
                            // 参考图：分级是带白边框的小标签（如 KR-15）
                            Box(
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = cert,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 第三行起：简介，最多 2 行
                    val overview = meta?.overview ?: item.overview
                    if (!overview.isNullOrBlank()) {
                        Text(
                            text = overview,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                }
            }
        }

        // 分页圆点（放 Crossfade 外面，切换时不跟着闪）
        if (list.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp),   // 贴在最底部，与上方信息块分离
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                list.forEachIndexed { i, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (i == index) 5.dp else 4.dp)
                            .background(
                                color = if (i == index) Color.White else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

/**
 * 取横版剧照 URL。回退链：
 *   自己的 Backdrop → 父级(剧集)的 Backdrop → 所属剧集的 Backdrop
 * （单集通常没有 Backdrop：实测 /Items/{epId}/Images 只有 Primary/Thumb）
 */
/**
 * 取剧集竖版海报 URL（backdrop 缺失时的兜底背景）
 */
private fun primaryUrlOf(item: BaseItemDto, serverUrl: String): String? {
    if (serverUrl.isEmpty()) return null
    val sid = item.seriesId ?: item.id ?: return null
    // 必须有 tag：无 tag 的 /Images/Primary 服务端返回 404，客户端就是一片黑（2026-10-02）
    val tag = item.seriesPrimaryImageTag ?: item.imageTags?.get("Primary") ?: return null
    if (tag.isEmpty()) return null
    return "$serverUrl/emby/Items/$sid/Images/Primary?maxWidth=1920&tag=$tag&quality=80"
}

private fun backdropUrlOf(item: BaseItemDto, serverUrl: String): String? {
    if (serverUrl.isEmpty()) return null
    val id = item.id ?: return null
    val own = item.backdropImageTags
    val parent = item.parentBackdropImageTags
    val parentId = item.parentBackdropItemId
    return when {
        !own.isNullOrEmpty() && own[0].isNotEmpty() ->
            "$serverUrl/emby/Items/$id/Images/Backdrop?maxWidth=1920&tag=${own[0]}&quality=80"
        !parent.isNullOrEmpty() && !parentId.isNullOrEmpty() && parent[0].isNotEmpty() ->
            "$serverUrl/emby/Items/$parentId/Images/Backdrop?maxWidth=1920&tag=${parent[0]}&quality=80"
        // 没有 tag 就没图可拿：返回 null，让调用方走兜底/跳过（原来自拼无 tag URL → 404 → 黑屏）
        else -> null
    }
}
