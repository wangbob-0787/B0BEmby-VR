package com.xxxx.emby_vr.ui.components

import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import coil3.compose.AsyncImage
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.ui.FocusMemory
import kotlinx.coroutines.delay

/**
 * 首页「电视直播」一行（父亲 2026-10-02 定的简单版）：
 * 列出 Emby 里的直播频道，选中直接进播放页；每条显示台标 + 频道号 + 台名 + 正在播出的节目。
 *
 * 为什么不做完整节目指南：父亲选的是「简单版」，先能用；EPG 网格留待以后。
 * 播放走既有通道（`/Items/{频道id}/PlaybackInfo`，已带 AutoOpenLiveStream=true），
 * 服务端返回带 api_key 的 HLS 地址，播放页不需要为直播改代码。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvRow(
    title: String,
    channels: List<BaseItemDto>,
    serverUrl: String,
    onChannelClick: (BaseItemDto) -> Unit,
    pendingFocusId: String? = null,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return

    val rowState = rememberLazyListState()
    // 焦点回原位：把要恢复的那条滚进视野（自身 requestFocus 由 BuildItem 那套逻辑之外的
    // 频道卡自己做，见下）
    LaunchedEffect(pendingFocusId) {
        val idx = channels.indexOfFirst { it.id == pendingFocusId }
        if (idx >= 0) rowState.scrollToItem(idx)
    }

    Column(modifier = modifier) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 16.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            itemsIndexed(channels, key = { _, c -> c.id ?: c.hashCode() }) { _, channel ->
                ChannelCard(
                    channel = channel,
                    serverUrl = serverUrl,
                    onClick = { onChannelClick(channel) },
                    autoFocus = channel.id != null && channel.id == pendingFocusId
                )
            }
        }
    }

    Box(modifier = Modifier.height(32.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelCard(
    channel: BaseItemDto,
    serverUrl: String,
    onClick: () -> Unit,
    autoFocus: Boolean = false,
) {
    val myFocusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(120)
            runCatching { myFocusRequester.requestFocus() }
        }
    }
    val tag = channel.imageTags?.get("Primary")
    val logoUrl = if (!serverUrl.isNullOrEmpty() && !tag.isNullOrEmpty()) {
        "$serverUrl/emby/Items/${channel.id}/Images/Primary?maxWidth=400&tag=$tag&quality=80"
    } else {
        null
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.secondary))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White
        ),
        modifier = Modifier
            .width(214.dp)
            .onFocusChanged { if (it.isFocused) FocusMemory.lastItemId = channel.id }
            .focusRequester(myFocusRequester)
    ) {
        Column {
            // 台标区：16:9，台标居中留白（不裁切，免得把台标裁掉）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    )
                } else {
                    Text(
                        text = channel.name ?: "",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 频道号角标
                channel.channelNumber?.let { num ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = num, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                text = channel.name ?: "",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = channel.currentProgram?.name ?: "",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
