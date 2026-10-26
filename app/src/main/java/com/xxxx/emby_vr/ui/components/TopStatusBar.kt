package com.xxxx.emby_vr.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import com.xxxx.emby_vr.panel.vrClickBlocker
import com.xxxx.emby_vr.panel.vrClickTarget
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.data.local.PreferencesManager
import com.xxxx.emby_vr.ui.viewmodel.UpdatePhase

/**
 * 顶栏元素之间的统一间距（父亲 2026-10-10 定）。
 *
 * 父亲原话：左边「菜单图标 ↔ 版本号」的间距加大一点，右边
 * 「放大镜 ↔ 头像 ↔ wangbob@192.168.150.15」的间距与它**相同**。
 * 所以三处都用这一个常量，改一处三处一起变。
 */
private val TopBarGap = 24.dp


@Composable
fun TopStatusBar(
    currentVersion: String,
    /**
     * 更新流程阶段（父亲 2026-10-26：只做「检查更新」，点版本号才更新）。
     * 只有这里不是 Idle 时版本号才可点；点下去开始下载并闪烁，装包阶段不再响应。
     */
    updatePhase: UpdatePhase = UpdatePhase.Idle,
    updateProgress: Int = 0,
    updateNote: String = "",
    onVersionClick: () -> Unit = {},
    showSearchButton: Boolean = false,
    userInfo: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    /*
     * 左上角的搜索按钮（父亲 2026-10-10：菜单按钮整个去掉，放大镜挪到那个位置）。
     * 与 onMenuClick 共用同一个位置：给了它就画放大镜，否则才画菜单图标。
     */
    onLeadingSearchClick: (() -> Unit)? = null,
    onUserInfoClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val menuFocusRequester = remember { FocusRequester() }
    val leadingSearchFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val userInfoFocusRequester = remember { FocusRequester() }
    val versionFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * 黑条下沿做 24dp 渐隐（父亲 2026-10-13：黑条 70% 是硬边，跟下面的片头图直接相接）。
             *
             * 为什么画在**条内**、而不是在条下面另加一段：
             *   顶栏在宿主里是 Column 的第一个孩子（HomeScreen / LoginScreen），
             *   条下方紧跟的是片头图 —— 画到条外的渐变会被后面画的内容盖掉，
             *   而"把顶栏加高 24dp"又会把下方内容整体下推。
             *   所以把黑条的**最后 24dp** 从 70% 黑渐隐到全透明：顶栏占的高度不变，
             *   边界从一条硬线变成过渡。
             * drawBehind 放在 padding **之前**，量到的才是含内边距的整条高度。
             */
            .drawBehind {
                val fade = 24.dp.toPx()
                val solid = (size.height - fade).coerceAtLeast(0f)
                val barColor = Color.Black.copy(alpha = 0.7f)
                drawRect(color = barColor, size = Size(size.width, solid))
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(barColor, Color.Transparent),
                        startY = solid,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, solid),
                    size = Size(size.width, fade),
                )
            }
            // 顶栏放大（父亲 2026-10-09：图标与字都太小）：四边留白跟着放大
            .padding(horizontal = 16.dp, vertical = 8.dp)
            /*
             * 整行铺一块「点了什么都不做」的兜底矩形（父亲 2026-10-10）。
             * 不铺的话，点版本号那种空白处查不到控件 → 输入层回退成 OK 键 →
             * 把当前焦点上的东西激活（首页就是大海报的「续播」，于是开始播片）。
             */
            .vrClickBlocker(key = "top:bar")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                /*
                 * 左上角：搜索（父亲 2026-10-10 —— 原来的菜单按钮去掉，放大镜挪到这里）。
                 * 给了 onLeadingSearchClick 就画放大镜，否则保持原来的菜单图标。
                 */
                if (onLeadingSearchClick != null) {
                    Surface(
                        onClick = onLeadingSearchClick,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(leadingSearchFocusRequester)
                            .vrClickTarget(
                                key = "top:search",
                                focusRequester = leadingSearchFocusRequester,
                                // 圆形控件：高亮白块圆角 = 半径（48/2），否则白块是"圆角方"（全站口径）
                                hoverRadius = 24.dp,
                                onActivate = { onLeadingSearchClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = VrHoverBg,
                            focusedContentColor = Color.White,
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(TopBarGap))
                } else if (onMenuClick != null) {
                    Surface(
                        onClick = onMenuClick,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(menuFocusRequester)
                            // 登记进点击表（父亲 2026-10-05：顶栏图标扣扳机没反应 = 没登记）
                            .vrClickTarget(
                                key = "top:menu",
                                focusRequester = menuFocusRequester,
                                // 圆形控件：高亮白块圆角 = 半径（48/2），否则白块是"圆角方"（全站口径）
                                hoverRadius = 24.dp,
                                onActivate = { onMenuClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        // 父亲 2026-10-02：聚焦 = 绿底白图标
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                focusedContainerColor = VrHoverBg,
                focusedContentColor = Color.White,
            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(TopBarGap))
                }

                /*
                 * 版本号就是更新入口（父亲 2026-10-26 定）：
                 *   没有新版 —— 一行说明文字，点不动；
                 *   有新版   —— 后面加括号，整块可点，点了开始下载并闪烁，再点一次取消；
                 *   装包阶段 —— 文字换成「正在安装…」，不再响应点击。
                 */
                val blink = rememberInfiniteTransition(label = "version-blink").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "version-alpha"
                )
                val clickable = updatePhase == UpdatePhase.Available ||
                        updatePhase == UpdatePhase.Failed ||
                        updatePhase == UpdatePhase.Downloading
                val suffix = when (updatePhase) {
                    UpdatePhase.Available, UpdatePhase.Failed -> "（有新版本）"
                    UpdatePhase.Downloading -> "（正在下载 $updateProgress%）"
                    UpdatePhase.Installing -> "（正在安装…）"
                    UpdatePhase.Idle -> ""
                }
                val alphaValue = if (updatePhase == UpdatePhase.Downloading) blink.value else 1f
                val versionModifier = if (clickable) {
                    Modifier
                        .focusRequester(versionFocusRequester)
                        .vrClickTarget(
                            key = "top:version",
                            focusRequester = versionFocusRequester,
                            hoverRadius = 8.dp,
                            onActivate = onVersionClick,
                        )
                } else {
                    Modifier
                }

                Text(
                    text = "B0BEmby " + currentVersion,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = versionModifier.alpha(alphaValue)
                )

                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(alphaValue)
                    )
                }

                if (updatePhase == UpdatePhase.Failed && updateNote.isNotEmpty()) {
                    Text(
                        text = " $updateNote",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 搜索按钮（右侧）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSearchButton && onSearchClick != null) {
                    Surface(
                        onClick = onSearchClick,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(searchFocusRequester)
                            .vrClickTarget(
                                key = "top:search",
                                focusRequester = searchFocusRequester,
                                // 圆形控件：高亮白块圆角 = 半径（48/2），否则白块是"圆角方"（全站口径）
                                hoverRadius = 24.dp,
                                onActivate = { onSearchClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        // 父亲 2026-10-02：聚焦 = 绿底白图标
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                focusedContainerColor = VrHoverBg,
                focusedContentColor = Color.White,
            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // 用户信息：只留头像，贴最右边（父亲 2026-10-10：用户名去掉、头像放最右）
                if (userInfo != null) {
                    Spacer(modifier = Modifier.width(TopBarGap))

                    Surface(
                        onClick = { (onUserInfoClick ?: {}).invoke() },
                        modifier = Modifier
                            /*
                             * 头像的点击区/容器跟搜索、菜单统一成 48dp（父亲 2026-10-13）：
                             * 三个可点目标的命中范围一致，光柱扫到时的白块也一样大。
                             * 头像图本身仍是 38dp（大小不变，只是外面这层容器变大）。
                             */
                            .size(48.dp)
                            .focusRequester(userInfoFocusRequester)
                            .vrClickTarget(
                                key = "top:user",
                                focusRequester = userInfoFocusRequester,
                                // 圆形控件：高亮白块的圆角要等于半径（48/2），
                                // 否则白块是个"圆角方"，与圆形头像对不上（全站口径）
                                hoverRadius = 24.dp,
                                onActivate = { (onUserInfoClick ?: {}).invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(
                            androidx.compose.foundation.shape.CircleShape
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = VrHoverBg,
                            focusedContentColor = Color.White,
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            // 头像：用父亲 2026-10-02 给的图（圆形裁剪）
                            Image(
                                painter = painterResource(R.drawable.ic_user_avatar),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}
