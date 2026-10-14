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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
/**
 * 藏掉"打开APP / 下载客户端"这类浮层（父亲 2026-10-13：爱奇艺一直有个横幅提示打开app）。
 *
 * 只动"固定定位 + 文字命中关键词 + 面积不大"的元素，避免误伤正文。
 */
private const val HIDE_APP_BANNER_JS = """
(function(){
  try{
    var kw=['打开APP','打开 APP','立即打开','下载APP','下载客户端','打开客户端','去APP看','打开看看'];
    var all=document.querySelectorAll('div,a,section,header,span,p');
    for(var i=0;i<all.length;i++){
      var e=all[i];
      var t=(e.innerText||'').trim();
      if(!t||t.length>40){continue;}
      var hit=false;
      for(var k=0;k<kw.length;k++){ if(t.indexOf(kw[k])>=0){hit=true;break;} }
      if(!hit){continue;}
      var st=window.getComputedStyle(e);
      if(st.position!=='fixed'&&st.position!=='sticky'){continue;}
      e.style.display='none';
    }
  }catch(err){}
})()
"""

/**
 * 按"鼠标"身份给网页派发一个事件（父亲 2026-10-13）。
 *
 * 用 SOURCE_MOUSE 而不是默认的触摸：网页播放器的控制条是"鼠标一活动才浮出来"的，
 * 光柱停着不动时它一直藏着 —— 表现就是"网页全屏后点不到控制按钮"。
 * 移动类事件走 dispatchGenericMotionEvent，按下/抬起走 dispatchTouchEvent。
 */
private fun dispatchMouse(
    wv: android.view.View,
    action: Int,
    x: Float,
    y: Float,
    whenMs: Long,
) {
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
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            size = 1f
        },
    )
    val ev = MotionEvent.obtain(
        whenMs, whenMs, action, 1, props, coords,
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
    )
    runCatching {
        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            wv.dispatchGenericMotionEvent(ev)
        } else {
            wv.dispatchTouchEvent(ev)
        }
    }
    ev.recycle()
}

/**
 * 给网页发一次"左右方向键"（父亲 2026-10-13：网页全屏播放时摇杆左右 = 快退/快进）。
 *
 * 三个站的网页播放器都认键盘的 ← →（B站一档 5 秒、腾讯同类），所以不去猜进度条坐标，
 * 直接按键最稳 —— 网页自己跳、自己更新进度条。
 */
private fun dispatchArrowKey(wv: android.view.View, right: Boolean) {
    val code = if (right) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    } else {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT
    }
    val now = SystemClock.uptimeMillis()
    runCatching {
        wv.dispatchKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0),
        )
        wv.dispatchKeyEvent(
            android.view.KeyEvent(now + 8, now + 8, android.view.KeyEvent.ACTION_UP, code, 0),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLibraryScreen(
    site: Int,
) {
    /*
     * 换站点 = 整棵网页子树重建（父亲 2026-10-13 报"B站切腾讯打不开，要先去播一部片子才能开"）。
     *
     * 原因：建 WebView 的那一步（AndroidView 的 factory）只在**第一次**组合这块内容时跑，
     * 换站点不会重跑；而清理逻辑在换站时把这个 WebView `destroy()` 掉了 ——
     * 于是新站点被 loadUrl 到一个已经销毁的窗口上，什么都不会发生，加载提示一直转。
     * 去播一部 Emby 片子再回来 = 重新进这块内容，factory 重跑、新窗口建起来，所以能开。
     * 用 key(site) 按站点分组：换站时旧的整棵销毁（连窗口一起）、新的整棵重建。
     */
    key(site) { WebPageForSite(site) }
}

@SuppressLint("SetJavaScriptEnabled")
/**
 * 火狐内核的网页块（2026-10-14 父亲定）。
 *
 * 为什么要它：头显系统自带的网页视图停在 Chromium 90，B站这类重页面渲染慢；
 * GeckoView 是把火狐的内核当依赖打进应用，内核版本跟着 Mozilla 走，不受系统限制。
 *
 * 这一版只负责「把页面显示出来 + 报告加载状态」，点击/悬停/滚动在下一版接上。
 */
@Composable
private fun GeckoWebView(
    url: String,
    desktopUa: String,
    onRect: (Rect) -> Unit,
    onReady: (org.mozilla.geckoview.GeckoView, org.mozilla.geckoview.GeckoSession) -> Unit,
    onLoading: (Boolean) -> Unit,
    onFailed: (Boolean) -> Unit,
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val p = coords.positionInRoot()
                onRect(Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height))
            },
        factory = { ctx ->
            val runtime = org.mozilla.geckoview.GeckoRuntime.getDefault(ctx)
            val session = org.mozilla.geckoview.GeckoSession()
            /* 三站都要电脑版排版：UA 覆盖成桌面版（与系统网页视图那条路同一套口径） */
            session.settings.userAgentOverride = desktopUa
            session.open(runtime)
            /*
             * 进度回调只接「加载结束」这一个。
             *
             * run 507 实测：onPageStart 的 URL 参数在接口里是非空 String，写成 String?
             * 会被判「overrides nothing」；而它的默认实现本来就够用 ——
             * 「正在加载」在发起加载时由外层置位，这里只负责收尾（成功=撤提示，失败=换提示）。
             */
            session.progressDelegate = object : org.mozilla.geckoview.GeckoSession.ProgressDelegate {
                override fun onPageStop(s: org.mozilla.geckoview.GeckoSession, success: Boolean) {
                    onLoading(false)
                    onFailed(!success)
                }
            }
            val view = org.mozilla.geckoview.GeckoView(ctx)
            view.setSession(session)
            /*
             * session 借 view.tag 带出去：factory 跑在布局阶段，在那里改 Compose 状态
             * 会在组合期改状态（轻则警告重则崩），所以引用交给 update 再回调。
             */
            view.tag = session
            session.loadUri(url)
            view
        },
        update = { v ->
            (v.tag as? org.mozilla.geckoview.GeckoSession)?.let { onReady(v, it) }
        },
    )
}

@Composable
private fun WebPageForSite(site: Int) {
    val context = LocalContext.current
    /*
     * 三个站点全部走**电脑版**网页（父亲 2026-10-13 定：腾讯也从手机版改成电脑版）。
     *
     * 之前腾讯走 m.v.qq.com + 手机浏览器标识（实测能播），现在换成电脑版
     * v.qq.com + 电脑浏览器标识，与 B站 / 爱奇艺同一套口径。
     * 手机宽度那套上下文（phoneWidthContext）随之没有调用方，按父亲"不留死代码"
     * 的要求一并删掉。
     */
    val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
    /* 火狐内核那两个（2026-10-14）：view 用来发事件，session 用来加载与前进后退 */
    var geckoRef by remember { mutableStateOf<org.mozilla.geckoview.GeckoView?>(null) }
    var geckoSessionRef by remember { mutableStateOf<org.mozilla.geckoview.GeckoSession?>(null) }
    /* 网页要"全屏"时，它把自己的播放视图塞进这个容器里（腾讯的「全屏」按钮走这条路） */
    var fullscreen by remember { mutableStateOf(false) }
    var customContainer by remember { mutableStateOf<android.widget.FrameLayout?>(null) }
    var customViewCallback by remember { mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null) }
    /*
     * 全屏时网页塞过来的那个播放视图（父亲 2026-10-13 实测：全屏后点不到东西）。
     * 它被我们从 WebView 里摘出来挂到自己的容器上 —— 事件必须发给它，
     * 发给 WebView 等于发给一块不再显示内容的控件，点了什么都不会发生。
     */
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    /* 网页这块矩形在面板画布里的位置（面板像素），用来换算光柱落点 */
    var webRect by remember { mutableStateOf<Rect?>(null) }
    /*
     * 加载态与失败态（父亲 2026-10-13：网页打开过程中「可以有个正在加载」，
     * 加载不出来要提示「请检查网络连接」）。
     *
     * 这两个状态只驱动面板正中一行提示字：不加底色、不登记点击
     * （登记了就会在光柱那张坐标表里跟网页抢同一块矩形），
     * 也不是"贴一层盖住网页"的蒙版 —— 网页照常铺满整块面板，
     * 加载完（或重新开始加载）提示立刻从界面上撤掉。
     */
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    /*
     * 悬停与快进的节流器（父亲 2026-10-13）。
     *
     * 悬停每帧都发会把网页拖慢（网页要为每个 mousemove 做命中测试与重绘），
     * 挪动不足 3 像素不发；快进每 250ms 最多一次，避免摇杆一推就跳出去十几分钟。
     * 用数组装是为了让闭包能改到同一个值（普通 var 会被 lambda 捕获成副本语义更绕）。
     */
    val lastHoverX = remember { floatArrayOf(-9999f) }
    val lastHoverY = remember { floatArrayOf(-9999f) }
    val lastArrowAt = remember { longArrayOf(0L) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        /*
         * 两套网页内核（2026-10-14 父亲定）。
         *
         * 系统自带的网页视图停在 Chromium 90，B站这类重页面在它上面渲染慢；
         * 火狐内核（GeckoView）作为依赖打进本应用，版本不受系统限制。
         * 运行时可切：改 <files/vr-tuning.txt> 的 web_engine（0=系统，1=火狐），
         * 下一秒整块网页按新引擎重建，不用重装、不用重起播。
         */
        val webEngine = com.xxxx.emby_vr.vr.VrTuning.webEngine
        if (webEngine == 1) {
            GeckoWebView(
                url = url,
                desktopUa = desktopUa,
                onRect = { webRect = it },
                onReady = { v, s ->
                    geckoRef = v
                    geckoSessionRef = s
                },
                onLoading = { loading = it },
                onFailed = { loadFailed = it },
            )
        } else {
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
                    /* 三站都是电脑版：用原上下文，站点给的就是电脑排版（父亲 2026-10-13 要的） */
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        /* 网页播放器默认要"用户手势"才播，这里放开（父亲 2026-10-13） */
                        settings.mediaPlaybackRequiresUserGesture = false
                        /*
                         * http 资源放行（2026-10-13 实测抓到）：这些站点的封面/接口还有走 http 的，
                         * 默认策略全拦掉（日志里一片"图片加载失败"），页面就残了。
                         */
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        /*
                         * 让站点自己的 viewport 生效；页面比屏幕宽时整体缩到屏幕宽度（看得全）。
                         */
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        /*
                         * 不许网页自己弹新窗口（弹出来就是个空白窗，画面留在原地不动）。
                         * 配合下面的 onCreateWindow 一起挡。
                         */
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = desktopUa
                        webViewClient = object : WebViewClient() {
                            /*
                             * 拦掉"打开APP"这类跳转（父亲 2026-10-13）：
                             * 页面点视频或横幅时会甩出 bilibili:// iqiyi:// qiyi-iphone:// 这些自家协议，
                             * 我们没装那些 App，系统接不住，页面就卡在原地不动 —— 直接吃掉。
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean {
                                val u = request.url.toString()
                                if (u.startsWith("http://") || u.startsWith("https://")) return false
                                android.util.Log.i("WebLib", "拦下 App 跳转 $u")
                                return true
                            }

                            /*
                             * 每开始一次加载就把状态复位回「正在加载」（父亲 2026-10-13）。
                             * 失败后用户点别的站点、或在网页里点链接进下一页，都从这里回到加载态，
                             * 不会把上一次的「请检查网络连接」留在画面上。
                             */
                            override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                                loading = true
                                loadFailed = false
                            }

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
                                /* "打开APP/下载客户端"横幅飘在页面上挡内容，按文案找出来藏掉 */
                                view.evaluateJavascript(HIDE_APP_BANNER_JS, null)
                                view.postDelayed(
                                    { view.evaluateJavascript(HIDE_APP_BANNER_JS, null) },
                                    1500,
                                )
                            }

                            /*
                             * 打不开就提示「请检查网络连接」（父亲 2026-10-13 定）。
                             *
                             * 只认**主文档**的失败：图片、埋点、广告这些子请求天天有失败的
                             * （这些站点的请求被拦是常态），拿它们当"网页打不开"会误报 ——
                             * 页面明明好好地在那儿，正中却挂着一行"请检查网络连接"。
                             *
                             * ERROR_UNKNOWN 也放过：这一档多半是"我们自己中断或重定向"
                             * （换站点时 stopLoading、点链接跳转），不是网络不通。
                             */
                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                android.util.Log.w(
                                    "WebLib",
                                    "加载失败 ${request.url} : ${error.errorCode} ${error.description}",
                                )
                                if (request.isForMainFrame &&
                                    error.errorCode != WebViewClient.ERROR_UNKNOWN
                                ) {
                                    loadFailed = true
                                }
                            }

                            /* HTTP 4xx / 5xx（页面在、但服务器回绝）：同样是主文档才算整页打不开 */
                            override fun onReceivedHttpError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                android.util.Log.w(
                                    "WebLib",
                                    "HTTP ${errorResponse.statusCode} ${request.url}",
                                )
                                if (request.isForMainFrame) loadFailed = true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            /* 网页想开新窗口（target=_blank / window.open）一律拒掉，留在当前页面播 */
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message,
                            ): Boolean {
                                android.util.Log.i("WebLib", "拦下网页新窗口")
                                return false
                            }

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
                                customView = view
                                box.addView(
                                    view,
                                    android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                box.visibility = android.view.View.VISIBLE
                                fullscreen = true
                                /* 告诉主界面：把画面投到影院大银幕（父亲 2026-10-13） */
                                WebPageHub.setFullscreen(true)
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
                                customView = null
                                fullscreen = false
                                WebPageHub.setFullscreen(false)
                                android.util.Log.i("WebLib", "网页全屏：已退出")
                            }

                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.i("WebLib", "控制台 ${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }
                        /* 加载放到 LaunchedEffect(site) 里做，换站点时能重新加载 */
                    }
                },
                update = { webRef = it },
            )
        }   /* 结束「系统网页视图」分支；火狐分支见上方 if（2026-10-14） */
        /*
         * 网页全屏用的容器：平时藏着，网页要全屏时把它的播放视图挂进来铺满整块画面。
         * 它排在网页之后 = 画在最上层。
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
        /*
         * 面板正中的加载 / 失败提示（父亲 2026-10-13）。
         *
         * 排在两个网页容器之后 = 画在它们上面，但只在"还没加载完"或"没加载出来"时存在：
         * 加载完这个 if 直接为假，整块提示从界面树上撤掉，不存在"压着网页"的可能。
         * 不铺底色是因为网页自己会画白底页面的加载过程，蒙一层反而看不清它在动。
         */
        if (loading || loadFailed) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (loadFailed) "请检查网络连接" else "正在加载…",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                )
            }
        }
    }

    /* 打开（或换）站点：网页整块在大银幕上（换站由外层 key(site) 重建整棵子树，见上） */
    LaunchedEffect(Unit) {
        /* 换站点把提示复位回"正在加载"，别把上一个站点的失败提示带过来 */
        loading = true
        loadFailed = false
        webRef?.loadUrl(url)
    }

    /*
     * 登记交互：光柱点击 / 摇杆滚动都派发给网页。
     * 落点先按"网页在面板里的矩形"换算成网页自己的像素 ——
     * 落在矩形外（比如点在顶栏上）就不处理，交给原来的查表逻辑。
     */
    DisposableEffect(Unit) {
        WebPageHub.tapHandler = handler@{ px, py ->
            val r = webRect
            /*
             * 全屏时事件发给**网页塞过来的那个播放视图**，不是 WebView
             * （父亲 2026-10-13 实测：网页全屏播放后光标点不动大银幕）——
             * 那个视图已经从 WebView 里摘出来挂到我们自己的容器上了，
             * 发给 WebView 等于发给一块不再显示内容的控件，什么都不会发生。
             */
            val wv = customView ?: webRef
            if (r != null && wv != null && r.width > 0f && r.height > 0f) {
                val u = (px - r.left) / r.width
                val v = (py - r.top) / r.height
                if (u in 0f..1f && v in 0f..1f) {
                    val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
                    val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
                    val now = SystemClock.uptimeMillis()
                    runCatching {
                        /*
                         * 先来两下"鼠标移动"（父亲 2026-10-13 报"网页全屏后点不了控制按钮"）：
                         * 网页播放器的控制条是**鼠标一活动才浮出来**的，光柱停着不动时
                         * 它一直藏着 —— 看着就是"按钮点不到"。先晃两下再点。
                         */
                        dispatchMouse(wv, MotionEvent.ACTION_HOVER_MOVE, x, y, now)
                        dispatchMouse(wv, MotionEvent.ACTION_HOVER_MOVE, x + 2f, y + 1f, now + 16)
                        dispatchMouse(wv, MotionEvent.ACTION_DOWN, x, y, now + 32)
                        dispatchMouse(wv, MotionEvent.ACTION_UP, x, y, now + 72)
                    }
                    return@handler true
                }
            }
            /* 落在网页外面（顶栏等）：交回常规查表 */
            false
        }
        /*
         * 光柱在银幕上移动 = 给网页发鼠标悬停（父亲 2026-10-13：手柄当鼠标用）。
         *
         * 只由原生"银幕上放着网页"那一路推过来（onWebHover），所以自带"限制在大屏幕上"。
         * 发的是 HOVER_MOVE（SOURCE_MOUSE）—— 网页据此更新 :hover / 触发 mouseenter，
         * 停在一些按钮上（比如 B站头像）就会像真鼠标那样浮出菜单。
         */
        WebPageHub.hoverHandler = hover@{ px, py ->
            val r = webRect ?: return@hover
            val wv = customView ?: webRef ?: return@hover
            if (r.width <= 0f || r.height <= 0f) return@hover
            val u = (px - r.left) / r.width
            val v = (py - r.top) / r.height
            if (u !in 0f..1f || v !in 0f..1f) return@hover
            val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
            val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
            /* 挪动不足 3 像素不发（网页每个 mousemove 都要做命中测试，太密会拖慢） */
            if (kotlin.math.abs(x - lastHoverX[0]) < 3f &&
                kotlin.math.abs(y - lastHoverY[0]) < 3f
            ) {
                return@hover
            }
            lastHoverX[0] = x
            lastHoverY[0] = y
            runCatching {
                dispatchMouse(
                    wv,
                    MotionEvent.ACTION_HOVER_MOVE,
                    x,
                    y,
                    SystemClock.uptimeMillis(),
                )
            }
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
        WebPageHub.scrollHandler = scroll@{ px, py, sx, sy ->
            /* 同上：全屏时滚的是那个播放视图 */
            val wv = customView ?: webRef
            val r = webRect
            /*
             * 摇杆左右 = 网页里快退 / 快进（父亲 2026-10-13 定）。
             *
             * 只在**网页全屏播放**时生效（那时我们接管了网页塞过来的播放视图）——
             * 全屏时上下滚动已经没有意义，左右正好空出来；没全屏时左右不动作，
             * 免得在列表页里被当成翻页之类。发方向键，网页播放器自己跳。
             */
            if (wv != null && customView != null &&
                kotlin.math.abs(sx) > 0.35f && kotlin.math.abs(sx) >= kotlin.math.abs(sy)
            ) {
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastArrowAt[0] > 250L) {
                    lastArrowAt[0] = nowMs
                    dispatchArrowKey(wv, sx > 0f)
                }
                return@scroll
            }
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
            /* 离开网页页一定要收回全屏状态，否则银幕会一直贴着面板纹理 */
            WebPageHub.setFullscreen(false)
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

/*
 * 这一页原来还有一个私有 composable `TopBarButton`（顶栏「返回海报墙」小按钮）。
 * 顶栏那份「返回海报墙」按钮随详情页改版一起去掉了 —— 电影 / 剧集详情页也没有它，
 * 返回统一靠 B 键，于是它成了没人调用的死代码，父亲 2026-10-13 要求不留未使用的私有函数，删掉。
 */

/**
 * 网络视频详情页（父亲 2026-10-13 定：**做成媒体库页的样子**）。
 *
 * 父亲这一页要的东西只有两样：左上角一个标题「网络视频」，标题下面三张站点卡。
 * 原来那版是照电影详情页拼的（渐变底 + 下半屏压暗 + 元数据行 + 站点名行 + 简介 +
 * 三颗小按钮 + 右侧大图 + 「站点」分区标题），可这一页背后没有"一部片子"可介绍 ——
 * 它就是三个站点入口，堆上去的字都是废话，还把三张卡挤到下半屏。
 * 现在砍到只剩标题 + 三张卡，卡片的规格与首页「我的媒体库」那一行的库卡一致
 * （260 宽、图区 16:9、图区圆角 12dp、图下 4dp 居中标题），
 * 观感上就是"进了又一个媒体库"。
 *
 * 顶栏仍然没有「返回海报墙」按钮：媒体库页也没有，返回统一靠 B 键。
 */
@Composable
fun WebSiteDetailScreen(onOpen: (Int) -> Unit, onBack: () -> Unit) {
    val sites = listOf(
        "哔哩哔哩" to R.drawable.web_bilibili_icon,
        "腾讯视频" to R.drawable.web_tencent_icon,
        "爱奇艺" to R.drawable.web_iqiyi_icon,
    )
    /* 底色用全站暗色体系的底（0xFF0B0C0F），不再走原来那条"详情页专用"的渐变 */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C0F)),
    ) {
        Column(
            /* 页边距 40dp，与媒体库页 / 详情页一致（父亲 2026-10-13） */
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
        ) {
            Text(
                text = "网络视频",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            /* 标题到卡片行的间距：媒体库页是"标题 → 排序行 → 网格"，这里没有排序行，留一段呼吸 */
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                /* 卡片间距 20dp：与首页「我的媒体库」那一行一致 */
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                sites.forEachIndexed { i, (name, icon) ->
                    NetVideoSiteCard(
                        name = name,
                        icon = icon,
                        key = "netvideo:big:$i",
                        onActivate = { onOpen(i) },
                    )
                }
            }
        }
    }
}

/**
 * 站点卡（父亲 2026-10-13 定：规格与首页「我的媒体库」的库卡一字不差）。
 *
 * 结构照 `ui/components/BuildItem.kt` 的库卡：Column = 图区 Box + 文字区 Column(padding 4dp, 居中)。
 * 卡片名字写白色而不是各站品牌色 —— 原来压在近白底上用品牌色，黄绿两家的对比度都不够，
 * 现在底是 0xFF101216 的暗块，白字才清楚（父亲要求 ≥3:1）。
 *
 * 站点 logo 是方形图标（447/225/216 见方），放在 16:9 的图区里用 Fit 保住整枚图标，
 * 用 Crop 会把上下切掉半个（爸爸要的是"图标"不是"截图"）。
 */
@Composable
private fun NetVideoSiteCard(
    name: String,
    icon: Int,
    key: String,
    onActivate: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101216))
            /* 圆角 12dp，与卡面一致：白块的圆角跟控件对不上时四个角会露出来（父亲 2026-10-13 实测） */
            .vrClickTarget(key = key, hoverRadius = 12.dp, onActivate = onActivate),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101216)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 「网络视频」卡片（父亲 2026-10-13 定）：放在海报墙首页「我的媒体库」那一行里，
 * 和普通媒体库并排；图用父亲发来的那张网络视频图标拼图（web_netvideo_card）。
 * 点它进网络视频详情页（三个站点卡）。
 *
 * 父亲原话：「网络视频库卡的说明文字和其他库卡说明文字的大小和位置都不一样」——
 * 原来这里把「网络视频」压在图上（左下角、22sp、底下垫一条 44dp 黑条），
 * 而同一行其他库卡的名字都在**图下面的居中位置**（16sp 粗体）。
 * 现在照 `ui/components/BuildItem.kt` 的库卡结构来：Column = 图区 Box + 文字区
 * Column(padding 4dp, 居中)；圆角也从 14dp 收到 12dp（全站圆角只有 8/12/16/28 四档）。
 */
@Composable
fun NetVideoCard(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101216))
            .vrClickTarget(key = "netvideo:card", hoverRadius = 12.dp, onActivate = onClick),
    ) {
        /* 图区 260×146 = 16:9，与「我的媒体库」那一行的库卡图区一样大 */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101216)),
        ) {
            Image(
                painter = painterResource(R.drawable.web_netvideo_card),
                contentDescription = "网络视频",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        /* 文字区：图下留 4dp，白字 16sp 粗体居中一行（与同排库卡的标题同规格） */
        Column(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "网络视频",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
