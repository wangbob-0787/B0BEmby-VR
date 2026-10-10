package com.xxxx.emby_vr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.ui.components.BuildItem
import com.xxxx.emby_vr.ui.viewmodel.PersonWorkGroup
import com.xxxx.emby_vr.ui.viewmodel.classifyPersonWork
import com.xxxx.emby_vr.util.ErrorHandler
import com.xxxx.emby_vr.util.Utils

/**
 * 演员页（父亲 2026-10-10 定）。
 *
 * 从详情页点演员头像进来。内容两段：
 *   1. 顶部：演员头像 + 姓名 + 出生日期 + **人物小传**（Emby 里的 Overview）
 *   2. 下面：他在这套库里的作品，按 电影 / 电视剧 / 演唱会 / 纪录片 / 其他 分组，
 *      **每组标题带条数，没有内容的那一组不显示**；组内按上映/开播时间从新到旧。
 *
 * 点海报 → 进详情页（父亲 2026-10-10：先进详情页，不直接播）。
 * 分组全在本地算（一次请求就把 Type 与 Genres 带回来了）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ActorScreen(
    personId: String,
    personName: String,
    imageTag: String?,
    onOpenItem: (BaseItemDto) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { EmbyRepository.getInstance(context) }
    val serverUrl = repository.serverUrl ?: ""

    var person by remember { mutableStateOf<BaseItemDto?>(null) }
    var works by remember { mutableStateOf<List<BaseItemDto>?>(null) }

    LaunchedEffect(personId) {
        if (personId.isBlank()) return@LaunchedEffect
        runCatching { repository.getPersonInfo(personId) }
            .onSuccess { person = it }
            .onFailure { ErrorHandler.logError("ActorScreen", "取演员资料失败", it) }
        runCatching { repository.getWorksByPerson(personId) }
            .onSuccess { works = it }
            .onFailure {
                ErrorHandler.logError("ActorScreen", "取演员作品失败", it)
                works = emptyList()
            }
    }

    val list = works
    // 头像 tag 优先用传进来的（详情页点的那张就有），没有再从演员资料里取
    val tag = imageTag ?: person?.imageTags?.get("Primary")
    val avatarUrl = if (serverUrl.isNotBlank() && tag != null) {
        "$serverUrl/emby/Items/$personId/Images/Primary?maxWidth=400&tag=$tag&quality=90"
    } else null

    /** 分组：按固定顺序，只保留有内容的组 */
    val groups: List<Pair<PersonWorkGroup, List<BaseItemDto>>> = remember(list) {
        if (list == null) emptyList()
        else PersonWorkGroup.entries.map { g -> g to list.filter { classifyPersonWork(it) == g } }
            .filter { it.second.isNotEmpty() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 40.dp),
    ) {
        // ---------- 顶部：头像 + 姓名 + 生日 + 小传 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2D2D2D)),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = personName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("无头像", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person?.name ?: personName,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val birth = Utils.formatDate(person?.premiereDate)
                if (birth.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "出生日期：$birth",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 16.sp,
                    )
                }

                val overview = person?.overview
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        overview.isNullOrBlank() && person != null -> "Emby 里没有这位演员的简介"
                        overview.isNullOrBlank() -> "正在读取演员资料…"
                        else -> overview
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---------- 作品分组 ----------
        when {
            list == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在读取作品…", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
            }

            groups.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "库里没有这位演员的作品",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                )
            }

            else -> groups.forEachIndexed { groupIndex, (group, groupItems) ->
                Text(
                    text = "${group.label} (${groupItems.size})",
                    // 分节标题照详情页那套（titleLarge + 粗体），全站一致
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 40.dp, top = 18.dp, bottom = 10.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
                ) {
                    items(
                        groupItems,
                        key = { it.id ?: "${it.name}-${it.hashCode()}" },
                    ) { item ->
                        BuildItem(
                            item = item,
                            imgWidth = 150.dp,
                            aspectRatio = (item.primaryImageAspectRatio ?: 0.6667).toFloat(),
                            modifier = Modifier,
                            isMyLibrary = false,
                            serverUrl = serverUrl,
                            // 进页面时焦点落在第一组的第一张（VR：光柱/遥控器都有落点）
                            autoFocus = groupIndex == 0 && item === groupItems.firstOrNull(),
                            onItemClick = { onOpenItem(item) },
                        )
                    }
                }
            }
        }
    }
}
