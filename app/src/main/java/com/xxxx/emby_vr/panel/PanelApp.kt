package com.xxxx.emby_vr.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xxxx.emby_vr.ui.AccountScreen
import com.xxxx.emby_vr.ui.HomeScreen
import com.xxxx.emby_vr.ui.LibraryScreen
import com.xxxx.emby_vr.ui.LoginScreen
import com.xxxx.emby_vr.ui.MediaDetailScreen
import com.xxxx.emby_vr.ui.ProxySettingsScreen
import com.xxxx.emby_vr.ui.SearchScreen
import com.xxxx.emby_vr.ui.components.BuildGradientBackground
import com.xxxx.emby_vr.ui.components.Loading
// 注意：电视版的 Theme.kt 没有 package 声明（顶层在默认包），所以按电视版
// MainActivity 的写法直接 import 默认包符号，不能写成 ui.theme.Emby_tvTheme。
import Emby_tvTheme
import com.xxxx.emby_vr.ui.theme.ThemeColorManager
import com.xxxx.emby_vr.ui.viewmodel.DetailViewModel
import com.xxxx.emby_vr.ui.viewmodel.HomeViewModel
import com.xxxx.emby_vr.ui.viewmodel.LibraryViewModel
import com.xxxx.emby_vr.ui.viewmodel.LoginViewModel
import com.xxxx.emby_vr.ui.viewmodel.MainViewModel
import com.xxxx.emby_vr.ui.viewmodel.SearchViewModel

/**
 * 面板层的界面骨架（2026-10-04）。
 *
 * 这就是电视版 B0BEmby 的导航装配（原 `EmbyTvApp()`），界面文件本身一字未改
 * （只换了包名），这里只做三处调整：
 *
 *   1. **去掉 update 路由** —— VR 版不做应用内更新检查（更新走云端编译装机）
 *   2. **去掉 player 路由** —— 播放屏是 VR 原生的（视频 + 弹幕 + 字幕 + 控制条），
 *      点播放改为回调 [onPlayRequested] 交给 Activity，不在这里起播放器
 *   3. **去掉返回键退出确认** —— VR 里返回键用于退出播放，不退出应用
 *
 * 父亲 2026-10-04 定的方向：除弹幕、字幕、控制条外全部复用电视版界面。
 * 因此这里不重写任何一屏，只负责把电视版的屏组装起来。
 */
@Composable
fun PanelApp(onPlayRequested: (mediaId: String, positionTicks: Long) -> Unit) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()

    val isLoaded = mainViewModel.isLoaded
    val isLoggedIn = mainViewModel.isLoggedIn

    LaunchedEffect(Unit) {
        mainViewModel.initialize()
    }

    // 与电视版一致：未加载完 → loading；已登录 → home；否则 → login
    LaunchedEffect(isLoaded, isLoggedIn) {
        when {
            !isLoaded -> {
                if (navController.currentDestination?.route != "loading") {
                    navController.navigate("loading") { popUpTo(0) { inclusive = true } }
                }
            }

            isLoggedIn -> {
                if (navController.currentDestination?.route != "home") {
                    navController.navigate("home") { popUpTo(0) { inclusive = true } }
                }
            }

            else -> {
                if (navController.currentDestination?.route != "login") {
                    navController.navigate("login") { popUpTo(0) { inclusive = true } }
                }
            }
        }
    }

    val context = LocalContext.current
    val currentThemeColor =
        ThemeColorManager.getThemeColorById(context, mainViewModel.currentThemeId)

    Emby_tvTheme(themeColor = currentThemeColor) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BuildGradientBackground(context = context, themeColor = currentThemeColor) {
                NavHost(navController = navController, startDestination = "loading") {
                    composable("loading") {
                        Loading()
                    }

                    composable("login") {
                        val loginViewModel: LoginViewModel = viewModel()
                        LoginScreen(
                            loginViewModel = loginViewModel,
                            mainViewModel = mainViewModel,
                            navController = navController,
                        )
                    }

                    composable("home") { backStackEntry ->
                        val homeViewModel: HomeViewModel = viewModel()
                        var isInitialLoaded by rememberSaveable { mutableStateOf(false) }

                        LaunchedEffect(backStackEntry) {
                            if (isInitialLoaded) homeViewModel.loadData() else isInitialLoaded = true
                        }

                        HomeScreen(
                            homeViewModel = homeViewModel,
                            mainViewModel = mainViewModel,
                            navController = navController,
                            onSwitchAccount = { navController.navigate("account") },
                        )
                    }

                    composable("account") {
                        val loginViewModel: LoginViewModel = viewModel()
                        AccountScreen(
                            mainViewModel = mainViewModel,
                            savedAccounts = loginViewModel.savedAccounts,
                            currentAccountId = loginViewModel.currentAccountId,
                            onSwitchAccount = { accountId ->
                                loginViewModel.switchAccount(
                                    accountId = accountId,
                                    onSuccess = {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    },
                                    onError = { },
                                )
                            },
                            onDeleteAccount = { accountId ->
                                loginViewModel.removeAccount(accountId)
                            },
                            onAddAccount = { mainViewModel.logout() },
                        )
                    }

                    composable("proxy_settings") {
                        ProxySettingsScreen(
                            mainViewModel = mainViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable("search") {
                        val searchViewModel: SearchViewModel = viewModel()
                        val loginViewModel: LoginViewModel = viewModel()
                        SearchScreen(
                            searchViewModel = searchViewModel,
                            loginViewModel = loginViewModel,
                            mainViewModel = mainViewModel,
                            onNavigateToSeries = { seriesId ->
                                navController.navigate("series/$seriesId")
                            },
                        )
                    }

                    composable(
                        "library/{libraryId}?libraryName={libraryName}&type={type}",
                        arguments = listOf(
                            navArgument("libraryId") { type = NavType.StringType },
                            navArgument("libraryName") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("type") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { backStackEntry ->
                        val libraryId = backStackEntry.arguments?.getString("libraryId") ?: ""
                        val libraryName = backStackEntry.arguments?.getString("libraryName") ?: ""
                        val type = backStackEntry.arguments?.getString("type") ?: ""
                        val libraryViewModel: LibraryViewModel = viewModel()

                        LibraryScreen(
                            parentId = libraryId,
                            title = libraryName,
                            type = type,
                            libraryViewModel = libraryViewModel,
                            onNavigateToSeries = { seriesId ->
                                navController.navigate("series/$seriesId")
                            },
                            onNavigateToPlayer = { channelId ->
                                onPlayRequested(channelId, 0L)
                            },
                        )
                    }

                    composable(
                        "series/{seriesId}",
                        arguments = listOf(navArgument("seriesId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
                        val detailViewModel: DetailViewModel = viewModel()
                        MediaDetailScreen(
                            seriesId = seriesId,
                            detailViewModel = detailViewModel,
                            onNavigateToSeries = { nestedSeriesId ->
                                navController.navigate("series/$nestedSeriesId")
                            },
                            onNavigateToPlayer = { mediaItem ->
                                onPlayRequested(
                                    mediaItem.id ?: "",
                                    mediaItem.userData?.playbackPositionTicks ?: 0L,
                                )
                            },
                        )
                    }

                    composable(
                        "media/{mediaId}",
                        arguments = listOf(navArgument("mediaId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
                        val detailViewModel: DetailViewModel = viewModel()
                        MediaDetailScreen(
                            seriesId = mediaId,
                            detailViewModel = detailViewModel,
                            onNavigateToSeries = { nestedSeriesId ->
                                navController.navigate("series/$nestedSeriesId")
                            },
                            onNavigateToPlayer = { mediaItem ->
                                onPlayRequested(
                                    mediaItem.id ?: "",
                                    mediaItem.userData?.playbackPositionTicks ?: 0L,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
