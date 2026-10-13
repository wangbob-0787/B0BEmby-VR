package com.xxxx.emby_vr.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    /* 网页这块矩形在面板画布里的位置（面板像素），用来换算光柱落点 */
    var webRect by remember { mutableStateOf<Rect?>(null) }
    var loading by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        Column(modifier = Modifier.fillMaxSize()) {
            /* 一条自己的顶栏：网页里没有"回海报墙"的路，这里给一个 */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xFF16181D))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarButton(label = "← 返回海报墙", onClick = onBack)
                Spacer(modifier = Modifier.width(24.dp))
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
                            }
                        }
                        webChromeClient = object : WebChromeClient() {}
                        loadUrl(url)
                    }
                },
                update = { webRef = it },
            )
        }
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
        WebPageHub.scrollHandler = { dy ->
            webRef?.let { wv ->
                val step = (dy * 120f).toInt()
                if (step != 0) runCatching { wv.scrollBy(0, step) }
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

/** 网页媒体库那一排（首页用） */
@Composable
fun WebLibraryRow(onOpen: (Int) -> Unit) {
    val sites = listOf(
        Triple("哔哩哔哩", "B站网页版", Color(0xFFFB7299)),
        Triple("腾讯视频", "v.qq.com", Color(0xFF2CA2F5)),
        Triple("爱奇艺", "iqiyi.com", Color(0xFF19B36B)),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "网络媒体库",
            color = Color(0xFFE8EDF2),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.padding(start = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            sites.forEachIndexed { i, (name, sub, accent) ->
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(170.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.85f), Color(0xFF14161B))
                            )
                        )
                        .vrClickTarget(key = "weblib:card$i", onActivate = { onOpen(i) }),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = name,
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = sub, color = Color(0xCCFFFFFF), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
