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


@Composable
fun TopStatusBar(
    currentVersion: String,
    newVersion: String,
    needUpdate: Boolean,
    showSearchButton: Boolean = false,
    userInfo: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    onUserInfoClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val menuFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val userInfoFocusRequester = remember { FocusRequester() }
    val proxyEnabled = remember { PreferencesManager(context).proxyEnabled }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            // 顶栏放大（父亲 2026-10-09：图标与字都太小）：四边留白跟着放大
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMenuClick != null) {
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
                    Spacer(modifier = Modifier.width(12.dp))
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

                if (proxyEnabled) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f),
                                RoundedCornerShape(100)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.proxy_indicator),
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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

                // 用户信息
                if (userInfo != null) {
                    Spacer(modifier = Modifier.width(20.dp))

                    Surface(
                        onClick = onUserInfoClick ?: {},
                        modifier = Modifier
                            .focusRequester(userInfoFocusRequester)
                            .vrClickTarget(
                                key = "top:user",
                                focusRequester = userInfoFocusRequester,
                                onActivate = { (onUserInfoClick ?: {}).invoke() },
                            ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = MaterialTheme.colorScheme.onSecondary,
            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 14.dp, start = 6.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            // 头像：用父亲 2026-10-02 给的图（圆形裁剪）
                            Image(
                                painter = painterResource(R.drawable.ic_user_avatar),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                            )
                            Text(
                                text = userInfo,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                }
            }
        }
    }
}
