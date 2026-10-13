package com.xxxx.emby_vr.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.web.WebPageHub

/**
 * 网页媒体库页（父亲 2026-10-13）。
 *
 * 海报墙首页上「B站 / 腾讯视频 / 爱奇艺」三张媒体库海报点进来就是这一页：
 * 面板整块换成该网站的网页版，宽度按面板宽度缩放，摇杆上下滚动看更多内容 ——
 * 和进一个普通 Emby 媒体库是同一个位置、同一个感觉，只是内容变成了网页。
 *
 * ## 为什么网页能放进面板
 * 面板本身就是"虚拟显示器 + 一块窗口"，网页在我们自己的进程里渲染，
 * 不需要任何系统特权（嵌别的应用窗口那条路普通应用拿不到画面也点不动，2026-10-13 实测）。
 *
 * ## 交互（这是关键）
 * 面板点击不走系统触摸，而是"光柱落点 → 查控件坐标表 → 无参动作"，
 * 而网页必须知道点在哪 —— 所以这里把"面板像素 → 网页像素"的派发登记到 [WebPageHub]，
 * 主界面收到光柱点击时直接交给这里（落点先按本页在面板里的矩形换算，再发给网页）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLibraryScreen(
    site: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val url = when (site) {
        0 -> "https://www.bilibili.com/"
        1 -> "https://v.qq.com/"
        else -> "https://www.iqiyi.com/"
    }
    val title = when (site) {
        0 -> "哔哩哔哩"
        1 -> "腾讯视频"
        else -> "爱奇艺"
    }

    var webRef by remember { mutableStateOf<WebView?>(null) }
    /* 网页要"全屏"时，它把自己的播放视图塞进这个容器里（腾讯的「全屏」按钮走这条路） */
    var fullscreen by remember { mutableStateOf(false) }
    var customContainer by remember { mutableStateOf<android.widget.FrameLayout?>(null) }
    var customViewCallback by remember { mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null) }
    /* 网页这块矩形在面板画布里的位置（面板像素），用来换算光柱落点 */
    var webRect by remember { mutableStateOf<Rect?>(null) }
    var loading by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        Column(modifier = Modifier.fillMaxSize()) {
            /* 一条自己的顶栏：网页里没有"回海报墙"的路，这里给一个（网页全屏时收起来） */
            if (!fullscreen) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xFF16181D))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarButton(label = "← 返回海报墙", onClick = onBack)
                Spacer(modifier = Modifier.width(24.dp))
                Image(
                    painter = painterResource(
                        when (site) {
                            0 -> R.drawable.web_bilibili_logo
                            1 -> R.drawable.web_tencent_logo
                            else -> R.drawable.web_iqiyi_logo
                        }
                    ),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(40.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    color = Color(0xFFE8EDF2),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (loading) {
                    Text(text = "正在打开网页…", color = Color(0xFF8C97A3), fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(24.dp))
                TopBarButton(label = "刷新", onClick = { webRef?.reload() })
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val p = coords.positionInRoot()
                        webRect = Rect(
                            p.x,
                            p.y,
                            p.x + coords.size.width,
                            p.y + coords.size.height,
                        )
                    },
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        /* 网页播放器默认要"用户手势"才播，这里放开（父亲 2026-10-13） */
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        /* 桌面版 UA：B站 / 腾讯 / 爱奇艺 的完整播放器只在桌面 UA 下给 */
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, u: String) {
                                loading = false
                                /*
                                 * 打完页面把布局尺寸打进日志（父亲 2026-10-13：
                                 * 爱奇艺"菜单在、内容看不见"）—— 用来看是不是
                                 * 页面比可视区宽/高得多，内容跑到画面外面去了。
                                 */
                                view.evaluateJavascript(
                                    "(function(){var d=document.documentElement;" +
                                        "return [window.innerWidth,window.innerHeight," +
                                        "d.scrollWidth,d.scrollHeight," +
                                        "document.body?document.body.scrollWidth:0].join(',');})()"
                                ) { r -> android.util.Log.i("WebLib", "页面尺寸 $u → $r") }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                android.util.Log.w(
                                    "WebLib",
                                    "加载失败 ${request.url} : ${error.errorCode} ${error.description}",
                                )
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            /*
                             * 网页要全屏（父亲 2026-10-13：腾讯有两个全屏，「网页全屏」有效、
                             * 「全屏」点了没反应）—— 没接这条通道，点了当然没反应。
                             * 接法：把网页塞过来的播放视图挂到我们自己的容器上，顶栏收起来，
                             * 让它铺满整块面板；退出全屏再把视图还回去。
                             */
                            override fun onShowCustomView(
                                view: android.view.View,
                                callback: CustomViewCallback,
                            ) {
                                val box = customContainer ?: run { callback.onCustomViewHidden(); return }
                                if (customViewCallback != null) {
                                    callback.onCustomViewHidden()
                                    return
                                }
                                customViewCallback = callback
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                box.addView(
                                    view,
                                    android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                box.visibility = android.view.View.VISIBLE
                                fullscreen = true
                                android.util.Log.i("WebLib", "网页全屏：已接管播放视图")
                            }

                            override fun onHideCustomView() {
                                val box = customContainer
                                if (box != null) {
                                    box.removeAllViews()
                                    box.visibility = android.view.View.GONE
                                }
                                customViewCallback?.onCustomViewHidden()
                                customViewCallback = null
                                fullscreen = false
                                android.util.Log.i("WebLib", "网页全屏：已退出")
                            }

                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.i("WebLib", "控制台 ${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }
                        loadUrl(url)
                    }
                },
                update = { webRef = it },
            )
        }
        /*
         * 网页全屏用的容器：平时藏着，网页要全屏时把它的播放视图挂进来铺满整块面板。
         * 它排在 Column 之后 = 画在最上层，顶栏和网页都被它盖住。
         */
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    visibility = android.view.View.GONE
                    customContainer = this
                }
            },
        )
    }

    LaunchedEffect(site) { loading = true }

    /*
     * 登记交互：光柱点击 / 摇杆滚动都派发给网页。
     * 落点先按"网页在面板里的矩形"换算成网页自己的像素 ——
     * 落在矩形外（比如点在顶栏上）就不处理，交给原来的查表逻辑。
     */
    DisposableEffect(site) {
        WebPageHub.tapHandler = handler@{ px, py ->
            val r = webRect
            val wv = webRef
            if (r != null && wv != null && r.width > 0f && r.height > 0f) {
                val u = (px - r.left) / r.width
                val v = (py - r.top) / r.height
                if (u in 0f..1f && v in 0f..1f) {
                    val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
                    val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
                    val now = SystemClock.uptimeMillis()
                    runCatching {
                        wv.dispatchTouchEvent(
                            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                        )
                        wv.dispatchTouchEvent(
                            MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
                        )
                    }
                    return@handler true
                }
            }
            /* 落在网页外面（顶栏等）：交回常规查表 */
            false
        }
        /*
         * 滚动：派发"滚轮事件"，和面板滚菜单用的是同一条路（父亲 2026-10-13 实测：
         * 之前用 scrollBy 三个网站都滚不动 —— 网页的滚动容器不是文档本身，
         * scrollBy 滚的是文档，所以没反应；滚轮事件按坐标做命中测试，
         * 落点在哪一块就滚哪一块，网页自己会把它滚起来）。
         * 滚轮量吃掉了就不管；万一没吃掉（网页没接），再用 scrollBy 兜一下。
         */
        /* 网页内返回（B 键）：只在网页的历史里退，退不动就什么都不做 */
        WebPageHub.backHandler = {
            val wv = webRef
            if (wv != null && wv.canGoBack()) {
                runCatching { wv.goBack() }
                true
            } else {
                false
            }
        }
        WebPageHub.scrollHandler = { px, py, sy ->
            val wv = webRef
            val r = webRect
            if (wv != null && r != null && r.width > 0f && r.height > 0f &&
                kotlin.math.abs(sy) > 0.2f
            ) {
                val u = ((px - r.left) / r.width).coerceIn(0.05f, 0.95f)
                val v = ((py - r.top) / r.height).coerceIn(0.05f, 0.95f)
                val x = u * wv.width
                val y = v * wv.height
                /* 方向跟面板一致：摇杆往上推 = 往回滚（滚轮往上），往下推 = 往下滚 */
                val notch = if (sy > 0f) 0.5f else -0.5f
                val props = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    },
                )
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = 1f
                        size = 1f
                        setAxisValue(MotionEvent.AXIS_VSCROLL, notch)
                    },
                )
                val now = SystemClock.uptimeMillis()
                @Suppress("DEPRECATION")
                val ev = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                val eaten = runCatching { wv.dispatchGenericMotionEvent(ev) }.getOrDefault(false)
                ev.recycle()
                if (!eaten) runCatching { wv.scrollBy(0, if (sy > 0f) -60 else 60) }
            }
        }
        onDispose {
            WebPageHub.clear()
            webRef?.let { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.destroy() }
            }
            webRef = null
        }
    }
}

/** 顶栏上的小按钮（网页页自己用，样式跟面板一致） */
@Composable
private fun TopBarButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2A2F38), Color(0xFF1D2128))
                )
            )
            .vrClickTarget(key = "weblib:$label", onActivate = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color(0xFFE8EDF2), fontSize = 20.sp)
    }
}


/**
 * 网络视频详情页（父亲 2026-10-13 定）。
 *
 * 从海报墙「我的媒体库」那一行的「网络视频」卡片点进来：里面是三个方形图标
 * （哔哩哔哩 / 腾讯视频 / 爱奇艺），点一个就进那个网站的网页版 ——
 * 和进一个普通 Emby 媒体库、再挑片子是同一个感觉。
 */
@Composable
fun WebSiteDetailScreen(onOpen: (Int) -> Unit, onBack: () -> Unit) {
    val sites = listOf(
        Triple("哔哩哔哩", R.drawable.web_bilibili_icon, Color(0xFFFB7299)),
        Triple("腾讯视频", R.drawable.web_tencent_icon, Color(0xFF2CA2F5)),
        Triple("爱奇艺", R.drawable.web_iqiyi_icon, Color(0xFF19B36B)),
    )
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBarButton(label = "← 返回海报墙", onClick = onBack)
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "网络视频",
                    color = Color(0xFFE8EDF2),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                sites.forEachIndexed { i, (name, icon, accent) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(accent.copy(alpha = 0.85f), Color(0xFF14161B))
                                )
                            )
                            .vrClickTarget(key = "netvideo:$i", onActivate = { onOpen(i) })
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                    ) {
                        Image(
                            painter = painterResource(icon),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(150.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = name,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「网络视频」卡片（父亲 2026-10-13 定）：放在海报墙首页「我的媒体库」那一行里，
 * 和普通媒体库并排；图用腾讯视频的横幅。点它进网络视频详情页（三个方形图标）。
 */
@Composable
fun NetVideoCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .height(146.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101216))
            .vrClickTarget(key = "netvideo:card", onActivate = onClick),
    ) {
        Image(
            painter = painterResource(R.drawable.web_tencent_logo),
            contentDescription = "网络视频",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        /* 底部压一条暗色，让「网络视频」四个字在任何图上都看得清 */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.BottomStart)
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "网络视频",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}
