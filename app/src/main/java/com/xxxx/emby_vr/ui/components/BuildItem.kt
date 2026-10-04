package com.xxxx.emby_vr.ui.components

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import com.xxxx.emby_vr.ui.FocusMemory
import com.xxxx.emby_vr.util.DiagLog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp

import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.Utils
import com.xxxx.emby_vr.data.model.BaseItemDto

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BuildItem(
    item: BaseItemDto,
    imgWidth: Dp,
    aspectRatio: Float,
    modifier: Modifier,
    isMyLibrary: Boolean,
    isShowImg17: Boolean = false,
    isShowOverview: Boolean = false,
    serverUrl: String, // 直接接受 serverUrl 而不是 AppModel
    accountName: String? = null,
    isPlaying: Boolean = false,
    onItemClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    // 返回上一页时把焦点送回这一张（父亲 2026-10-02）；由宿主页面按 FocusMemory 里的 id 置 true
    autoFocus: Boolean = false,
    // 是否把"最后聚焦的条目"记进 FocusMemory。只给首页的行开：
    // 播放页选集菜单/搜索页也有卡片，它们写了会污染返回首页的焦点恢复目标。
    rememberFocus: Boolean = false,
    // 宿主指定的焦点锚点（详情页"季 → 下键进第一集"要指到第一张集卡上）
    focusRequester: FocusRequester? = null,
    // 宿主直接给图 URL（详情页集卡要用"本集静帧"，不能走默认的父级剧照回落）
    imageUrlOverride: String? = null,
    // 上键去向（详情页集卡按上键要回到季胶囊）
    upFocus: FocusRequester? = null,
    // 下键去向（详情页集卡按下键要去演员行"上次停的那一位"）
    downFocus: FocusRequester? = null,
    // 宿主想知道"这一张什么时候拿到焦点"（详情页用它记住用户停在哪一集）
    onFocused: (() -> Unit)? = null,
    // 图片缩放方式（默认裁切；直播台标传 Fit，避免横版台标被裁）
    imageScale: ContentScale = ContentScale.Crop,
) {
    val myFocusRequester = remember { FocusRequester() }
    val focusAnchor = focusRequester ?: myFocusRequester
    val ctx = LocalContext.current
    LaunchedEffect(autoFocus, focusAnchor) {
        if (!autoFocus) return@LaunchedEffect
        // 等这一帧布局完再要焦点，失败就重试（列表还在铺的时候 requestFocus 会被忽略）
        repeat(3) { attempt ->
            delay(if (attempt == 0) 120L else 260L)
            if (runCatching { focusAnchor.requestFocus() }.isSuccess) {
                DiagLog.w(ctx, "focusAuto", "id=${item.id} 第${attempt + 1}次要焦点 成功")
                return@LaunchedEffect
            }
        }
        DiagLog.w(ctx, "focusAuto", "id=${item.id} 三次要焦点都失败")
    }
    val primaryColor = MaterialTheme.colorScheme.secondary
    val isSeries = item.isSeries
    val userData = item.userData
    val isPlayed = userData?.played ?: false
    val itemId = item.id
    val imageTags = item.imageTags
    val primaryTag = imageTags?.get("Primary")

    // Construct Image URL using Utils.getImageUrl
    val imageUrl = imageUrlOverride ?: Utils.getImageUrl(serverUrl, item, isShowImg17)

    // TV 端核心组件：Surface 自动处理焦点缩放、边框和点击
    Surface(
        onClick = onItemClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        // P1 视觉对齐（2026-09-27）：焦点态改官方 Emby 的绿色描边（原来是白色描边 + 白底反色）
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.secondary
                )
            )
        ),
        // 放大倍数收到 1.06：1.1 时会被所在行裁掉边缘（父亲 2026-10-02）
        scale = ClickableSurfaceDefaults
            .scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            // 卡片底色改全透明（2026-10-02 GPU 实测）：原来每张卡垫一层 20% 黑，
            // 海报完全盖住它，却要多付一次整卡面积的混合（同屏十来张 = 一屏多余混合）。
            // 焦点态保留高亮（同屏只有一张卡带焦点）。
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Black.copy(alpha = 0.35f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            pressedContentColor = MaterialTheme.colorScheme.secondary,
            focusedContentColor = MaterialTheme.colorScheme.secondary
        ),

        modifier = modifier
            .width(imgWidth)
            // 兼容移动端点击  TODO：移除
            // .clickable(interactionSource = null, onClick = onItemClick)
            .wrapContentHeight()
            // 记住"最后聚焦的条目"：从详情页返回时用它把焦点送回来（父亲 2026-10-02）
            .onFocusChanged {
                if (it.isFocused) {
                    if (rememberFocus) FocusMemory.lastItemId = itemId
                    onFocused?.invoke()
                }
            }
            .focusRequester(focusAnchor)
            .focusProperties {
                if (upFocus != null) up = upFocus
                if (downFocus != null) down = downFocus
            }
            .onKeyEvent { keyEvent ->
                if (onMenuClick != null && keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Menu -> {
                            onMenuClick()
                            true
                        }
                        Key.Bookmark -> {
                            onMenuClick()
                            true
                        }

                        else -> false
                    }
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. 顶部图片区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {

                // 静态占位垫在底层，海报加载完盖住它。
                // 2026-10-02 曾试过「只在没有海报时画占位」，结果加载中的卡片会空着（父亲看到空卡），
                // 已改回：release 包 GPU 只占 5ms/16.7ms 预算，这点填充付得起，观感优先。
                // （子组合 + 转圈动画在 2026-09-27 移除，那是当时 90~150ms 帧时间的主因）
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.Gray.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp)
                )

                // 使用 Coil 加载图片（无子组合版本）
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = imageScale
                )

                // 账号名称显示
                if (!accountName.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = accountName,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(primaryColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.playing),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                // 播放标记
                if (isSeries || isPlayed) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .background(primaryColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlayed) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Finished",
                                modifier = Modifier
                                    .size(13.dp)
                                    .align(Alignment.Center),
                                tint = Color.White
                            )
                        }
                        else {
                            Text(
                            text = userData?.unplayedItemCount?.toString() ?: "",
                                color = Color.White,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // 2. 播放进度条
                if (!isSeries && !isMyLibrary) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .height(3.dp)
                            .background(Color.Gray.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = item.playbackProgress)
                                .background(primaryColor)
                        )
                    }
                }
            }


            // 3. 文字内容区
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val title = if (isShowOverview) {
                    item.name
                } else {
                    item.seriesName ?: item.name
                } ?: "Unknown"

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                if (!isMyLibrary) {
                    val subTitle = if (item.parentIndexNumber != null) {
                        "S${item.parentIndexNumber}:E${item.indexNumber} ${item.name}"
                    } else if (item.type == "Actor") {
                        item.role ?: ""
                    } else {
                        // 官方卡片在标题下显示"分级 + 年份"，这里对齐
                        listOfNotNull(item.officialRating, item.productionYear?.toString())
                            .joinToString("  ").ifEmpty { "--" }
                    }
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }

                if (isShowOverview) {
                    Text(
                        text = item.overview ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
