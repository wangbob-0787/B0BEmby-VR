package com.xxxx.emby_vr.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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

    /*
     * 切屏 = 界面真的发生了动作。
     *
     * 点击生效的判定靠这个计数器（见 [PanelSignals]）：鼠标式点击之后
     * 220ms 内计数器没变，就说明点击没起作用，补发 OK 键。
     */
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> PanelSignals.bump() }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    val isLoaded = mainViewModel.isLoaded
    val isLoggedIn = mainViewModel.isLoggedIn

    LaunchedEffect(Unit) {
        mainViewModel.initialize()
    }

    // 加载完成后一律进 home：
    // 已登录 → 正常首页；未登录 → 首页显示「请添加 Emby 服务器」空状态
    // （父亲 2026-10-04：登录面板取消，进入应用后自己添加服务器，支持多服务器切换）
    /*
     * 已保存服务器/账号，但当前没有会话（例如上次退出时会话没落住）→ 自动切回第一个账号。
     * 不这么做的话首页一片「暂无数据」，而 VR 里没有进「账号」页的入口
     * （2026-10-05 父亲实测：首页拉不到数据）。
     */
    val accountViewModel: LoginViewModel = viewModel()
    val savedAccounts = accountViewModel.savedAccounts
    LaunchedEffect(isLoaded, isLoggedIn, savedAccounts.size) {
        if (isLoaded && !isLoggedIn && savedAccounts.isNotEmpty()) {
            android.util.Log.w("B0BEmbyVR", "有 ${savedAccounts.size} 个已保存服务器但没有会话，自动切回第一个")
            accountViewModel.switchAccount(savedAccounts.first().id, onSuccess = {
                android.util.Log.i("B0BEmbyVR", "自动切回账号成功")
            }, onError = { msg ->
                android.util.Log.e("B0BEmbyVR", "自动切回账号失败：$msg")
            })
        }
    }

    LaunchedEffect(isLoaded, isLoggedIn) {
        // 登录成功（isLoggedIn 变 true）也会触发，自动从登录页回到首页
        if (isLoaded && navController.currentDestination?.route != "home") {
            navController.navigate("home") { popUpTo(0) { inclusive = true } }
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
                        val loginViewModel: LoginViewModel = viewModel()
                        var isInitialLoaded by rememberSaveable { mutableStateOf(false) }

                        LaunchedEffect(backStackEntry) {
                            if (isInitialLoaded) homeViewModel.loadData() else isInitialLoaded = true
                        }

                        // 还没有添加任何 Emby 服务器 → 空状态引导添加
                        // （父亲 2026-10-04：第一次进入应用，首页显示「请添加 Emby 服务器」）
                        if (loginViewModel.savedAccounts.isEmpty()) {
                            EmptyServerScreen(onAdd = { navController.navigate("login") })
                        } else {
                            HomeScreen(
                                homeViewModel = homeViewModel,
                                mainViewModel = mainViewModel,
                                navController = navController,
                                onSwitchAccount = { navController.navigate("account") },
                                // 直接起播、不跳页：海报墙原地不动，不闪
                                onPlayNow = { mediaId, position ->
                                    onPlayRequested(mediaId, position)
                                },
                            )
                        }
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

                    /*
                     * 播放路由（VR 版专用，2026-10-05 补）。
                     *
                     * 电视版「播放」是面板里的一整屏（player 路由）；VR 版把播放屏换成了
                     * 原生 VR 播放器，所以这里只做一件事：把请求转给 Activity，然后立刻
                     * 退出这一屏 —— 原生播放时面板本来就不显示，退出后播放结束回到的
                     * 就是刚才那一页（详情/首页）。
                     *
                     * 父亲 2026-10-05 实测「首页大海报、电视直播、继续观看扣扳机，
                     * 焦点移过去了但不起播」，根因就是这个路由缺失：
                     * navigate 抛 "destination ... cannot be found"，被兜底 try 吞掉，
                     * 表现为静默无动作。凡是走 goPlay 的入口（都是直接播放）都踩这条。
                     */
                    composable(
                        "player/{mediaId}?position={position}",
                        arguments = listOf(
                            navArgument("mediaId") { type = NavType.StringType },
                            navArgument("position") {
                                type = NavType.LongType
                                defaultValue = 0L
                            },
                        ),
                    ) { entry ->
                        val mediaId = entry.arguments?.getString("mediaId").orEmpty()
                        val position = entry.arguments?.getLong("position") ?: 0L
                        LaunchedEffect(entry.id) {
                            if (mediaId.isNotBlank()) onPlayRequested(mediaId, position)
                            navController.popBackStack()
                        }
                        /*
                         * 这一格**什么也不画**（父亲 2026-10-06 晚：点「继续播放」的海报，
                         * 海报墙先黑一下再回来）。原因是这里原来渲染 Loading()——一整块
                         * 深色加载页，navigate 过来的那一帧就把它顶上去了，下一帧才
                         * popBackStack 回首页。空着就只闪一帧透明，看不到黑。
                         */
                        Box(modifier = Modifier.fillMaxSize())
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
                            // 合集 → 用本页网格列它的子项（影片/剧集），见 LibraryScreen 注释
                            onOpenBoxSet = { boxSetId, boxSetName ->
                                navController.navigate(
                                    "library/$boxSetId?libraryName=$boxSetName" +
                                        "&type=Movie,Series,Video"
                                )
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

/**
 * 空状态屏：还没有添加任何 Emby 服务器时显示（父亲 2026-10-04 要求）。
 *
 * 文案用父亲原话「请添加 Emby 服务器」；点「添加服务器」去登录页
 * （登录页输入服务器地址 + 凭证，登录成功即添加了一个服务器）。
 * 多服务器切换走账号管理页（AccountScreen，电视版已支持多账号）。
 */
@Composable
private fun EmptyServerScreen(onAdd: () -> Unit) {
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { addFocus.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "请添加 Emby 服务器",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(28.dp))
            /*
             * 自绘按钮（亮绿底 + 黑字）。
             *
             * 为什么不用 TV 库的 Button：深色主题下它的默认配色是深底深字，
             * 头显里几乎看不见（父亲 2026-10-04 反馈「按钮看不清楚」）。
             * 这里直接用背景色 + 点击，颜色可控，也不依赖库的配色参数。
             */
            Box(
                modifier = Modifier
                    .background(Color(0xFF4CD137), RoundedCornerShape(8.dp))
                    // clickable 同时认触摸点击和 OK 键（焦点状态下按 OK 即触发），
                    // 所以遥控器通道和光标通道都走它；focusRequester 用于进屏抢焦点
                    .clickable { onAdd() }
                    .focusRequester(addFocus)
                    .padding(horizontal = 40.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "添加服务器",
                    style = MaterialTheme.typography.titleMedium,
                    // 绿底白字 = 电视版主按钮风格（父亲 2026-10-04）
                    color = Color.White,
                )
            }
        }
    }
}
