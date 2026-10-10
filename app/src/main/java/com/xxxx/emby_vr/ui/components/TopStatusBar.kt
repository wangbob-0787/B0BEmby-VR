package com.xxxx.emby_vr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    newVersion: String,
    needUpdate: Boolean,
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
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
                                onActivate = { onLeadingSearchClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color.Transparent,
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
                                onActivate = { onMenuClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        // 父亲 2026-10-02：聚焦 = 绿底白图标
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                focusedContainerColor = Color.Transparent,
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

                Text(
                    text ="B0BEmby "+ currentVersion,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                if (needUpdate) {
                    Text(
                        text = " ( ${stringResource(R.string.new_version_available, newVersion)} )",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 18.sp,
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
                                onActivate = { onSearchClick?.invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        // 父亲 2026-10-02：聚焦 = 绿底白图标
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                focusedContainerColor = Color.Transparent,
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
                            .focusRequester(userInfoFocusRequester)
                            .vrClickTarget(
                                key = "top:user",
                                focusRequester = userInfoFocusRequester,
                                onActivate = { (onUserInfoClick ?: {}).invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(
                            androidx.compose.foundation.shape.CircleShape
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color.Transparent,
                            focusedContentColor = Color.White,
                        )
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
