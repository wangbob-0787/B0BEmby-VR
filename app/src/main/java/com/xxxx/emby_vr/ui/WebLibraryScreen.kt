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
 *
 * 【2026-10-14 修正】原来每次事件都拿"自己的时间"同时当按下时间和事件时间，
 * 一次点击的 DOWN 与 UP 于是按下时间不同 —— 网页那边把它当成两个不相干的手势，
 * 点击就丢了。现在按下时间统一（DOWN / MOVE / UP 共用一个），只在事件时间上递增，
 * 并在抬起前补一次 MOVE（真实点击就是这个序列）。
 * 另加运行时开关 <web_click>：0 = 鼠标语义、1 = 触摸语义，戴着切一下就知道哪种认。
 */
private fun dispatchMouse(
    wv: android.view.View,
    action: Int,
    x: Float,
    y: Float,
    downTime: Long,
    eventMs: Long,
) {
    /*
     * 两种发法，按 <web_click> 选：
     *   2（默认）= 鼠标按键式：按下/抬起发成"鼠标左键按下/抬起"，走**悬停同一条通道**
     *              （dispatchGenericMotionEvent + SOURCE_MOUSE）。
     *              父亲 2026-10-14 实测：光柱移到海报上网页有焦点反应，说明这条通道是通的；
     *              而原来那种触摸式按下它不认。
     *   1 = 触摸式：ACTION_DOWN/UP + SOURCE_TOUCHSCREEN。
     *   0 = 鼠标触摸式：ACTION_DOWN/UP + SOURCE_MOUSE。
     */
    val mode = com.xxxx.emby_vr.vr.VrTuning.webClickMode
    val hover = action == MotionEvent.ACTION_HOVER_MOVE
    if (mode == 2 && !hover) {
        /* 「鼠标左键按下 / 抬起」在 Android 里是通用运动事件，不是触摸的 DOWN / UP */
        val btnAction = when (action) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_BUTTON_PRESS
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_BUTTON_RELEASE
            else -> return /* MOVE 不重复发，否则等于点了两下 */
        }
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
            downTime, eventMs, btnAction, 1, props, coords,
            0,
            if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        val taken = runCatching { wv.dispatchGenericMotionEvent(ev) }.getOrDefault(false)
        android.util.Log.i(
            "B0BEmbyVR",
            "网页点击派发（鼠标按键式）：$btnAction 被接住=$taken 坐标=$x,$y " +
                "视图=${wv.javaClass.simpleName} ${wv.width}x${wv.height} " +
                "有焦点=${wv.hasFocus()} 窗口焦点=${wv.hasWindowFocus()}",
        )
        ev.recycle()
        return
    }
    val touchMode = !hover && mode == 1
    val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (touchMode) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_MOUSE
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
    /*
     * 鼠标左键必须显式带上 BUTTON_PRIMARY：按下时置位、抬起时清零。
     * 原来这里一律传 0，等于告诉网页"没有任何按键按下" —— 光有坐标没有左键，
     * 点击自然没反应（2026-10-14 复查事件构造时发现）。
     */
    val buttonState = when (action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MotionEvent.BUTTON_PRIMARY
        else -> 0
    }
    val ev = MotionEvent.obtain(
        downTime, eventMs, action, 1, props, coords,
        0, buttonState, 1f, 1f, 0, 0,
        if (touchMode) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_MOUSE,
        0,
    )
    val taken = runCatching {
        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            wv.dispatchGenericMotionEvent(ev)
        } else {
            wv.dispatchTouchEvent(ev)
        }
    }.getOrDefault(false)
    /*
     * 只有"按下 / 抬起"记日志（悬停太频繁，不记）。
     * 这一行是排查用的关键证据：被接住=false 说明内核根本没处理这个事件；
     * =true 却点不动，说明事件收下了但网页那边没用它 —— 两条路要查的东西完全不同。
     */
    if (action != MotionEvent.ACTION_HOVER_MOVE) {
        android.util.Log.i(
            "B0BEmbyVR",
            "网页点击派发（方式 $mode）：$action 被接住=$taken 坐标=$x,$y " +
                "视图=${wv.javaClass.simpleName} ${wv.width}x${wv.height} " +
                "有焦点=${wv.hasFocus()} 窗口焦点=${wv.hasWindowFocus()}",
        )
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

/*
 * 火狐内核的运行环境是**进程级单例**，只能有一个。
 *
 * run 528 实测崩溃（父亲：从腾讯切到 B站 直接闪退）：
 *   java.lang.IllegalStateException: Only one GeckoRuntime instance is allowed
 *     at GeckoRuntime.getDefault(GeckoRuntime.java:241)
 * 切站点会重建网页块 → 再进一次 factory → 第二次 create 抛「只允许一个」，
 * 连兜底的 getDefault 也抛同一个异常 → 整个应用退出。
 * 修法：拿到就留着复用，第二次直接用旧的；参数只在第一次生效
 * （所以改 <web_touch> 要重启应用）。
 */
private object WebRuntime {
    private var cached: org.mozilla.geckoview.GeckoRuntime? = null

    fun get(
        ctx: android.content.Context,
        keepTouch: Boolean,
        extraPrefs: List<String>,
    ): org.mozilla.geckoview.GeckoRuntime {
        cached?.let { return it }
        val made = runCatching {
            /*
             * 2026-10-15 修正：以前 keepTouch 为真时直接 getDefault()，等于**一个参数都不传**，
             * 调参文件里的 pref 全被丢掉。现在统一走 Builder，触摸那条只是"加不加"的区别。
             * 参数先在日志里打出来，免得以后再靠猜判断有没有生效。
             */
            val args = mutableListOf<String>()
            if (!keepTouch) args += "--setpref=dom.w3c_touch_events.enabled=0"
            extraPrefs.forEach { args += "--setpref=$it" }
            android.util.Log.i("B0BEmbyVR", "网页内核参数：${args.joinToString("；")}")
            val b = org.mozilla.geckoview.GeckoRuntimeSettings.Builder()
                .arguments(args.toTypedArray())
                /*
                 * 诊断（2026-10-15）：让页面自己的控制台消息进日志。
                 * run 527 用 ContentDelegate.onConsoleMessage 编译不过（该版本没这个方法），
                 * 改用设置项并用反射叫 —— 诊断项绝不能把编译或启动搞挂。
                 */
                runCatching {
                    b.javaClass.getMethod("consoleOutput", java.lang.Boolean.TYPE)
                        .invoke(b, true)
                }
            org.mozilla.geckoview.GeckoRuntime.create(ctx, b.build())
        }
        made.getOrNull()?.let {
            cached = it
            return it
        }
        android.util.Log.i("B0BEmbyVR", "内核参数没生效，退回默认内核：${made.exceptionOrNull()?.message}")
        val fallback = org.mozilla.geckoview.GeckoRuntime.getDefault(ctx)
        cached = fallback
        return fallback
    }

    /**
     * 上次清腾讯站数据的时刻（毫秒）。
     *
     * 这里**不能**做成"进程内只清一次"：run 535 清完当次是好的，但父亲重新访问腾讯时又黑了
     * （2026-10-15 13:57 实测：SW 报错 50 条、预检 100 条）—— 腾讯页面**每次访问都会重新注册**
     * 那个 Service Worker，所以每次进网页栏都得清一遍。时间戳只为挡 Compose 的重复重组。
     */
    private var lastWipeMs = 0L

    /**
     * 清掉腾讯站的网页数据（2026-10-15）。
     *
     * 为什么需要：腾讯页面注册的 Service Worker 会**持久化在 profile 里**。即使内核已经禁用
     * SW 能力（run 534 实测探针 `navigator.serviceWorker` 已消失为 undefined），这份**残留注册
     * 仍在拦截脚本请求** —— 日志里 SW 报错与脚本加载失败一条没少（各 10 条）。
     * 清掉站点数据即可去掉这份注册。
     *
     * 用 `clearDataFromHost` 只清 v.qq.com 一个域，不动 B站 / 爱奇艺；
     * 代价是腾讯网页的登录态会一起清掉（清完需要重新登录腾讯）。
     *
     * 由 `<web_wipe>=1` 触发，清一次即在本进程内失效。
     */
    fun wipeTencentOnce(ctx: android.content.Context, runtime: org.mozilla.geckoview.GeckoRuntime) {
        if (!com.xxxx.emby_vr.vr.VrTuning.webWipeNow(ctx)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWipeMs < 5000L) return
        lastWipeMs = now
        runCatching {
            runtime.storageController.clearDataFromHost(
                "v.qq.com",
                org.mozilla.geckoview.StorageController.ClearFlags.SITE_DATA,
            )
        }.onSuccess {
            android.util.Log.i("B0BEmbyVR", "已清 v.qq.com 站点数据（去掉残留的后台服务注册）")
        }.onFailure {
            android.util.Log.w("B0BEmbyVR", "清 v.qq.com 站点数据失败：${it.message}")
        }
    }
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
            /*
             * 2026-10-15：**把"关掉触摸事件"这条加回来**。
             *
             * 昨晚唯一一次腾讯播放页正常（r519）就带着这条设置；它被撤掉的理由是"父亲实测那一版
             * 包有问题"，而当时的症状始终没问清（那时还有"包损坏"这档事搅在一起），于是白撤了。
             * 之后 r521→r525 一路改"开新窗口的方式"，腾讯一直黑 —— 改错了地方。
             *
             * 只在启动参数里写这一条（builder 上设偏好项的方法不存在），失败就退回默认内核。
             * 运行时开关 <web_touch>：0 = 关（默认，本次要验证的就是这一档）、1 = 保留触摸。
             * 内核只建一次，切换后要重启应用。
             */
            val keepTouch = com.xxxx.emby_vr.vr.VrTuning.webTouchKept == 1
            val runtime = WebRuntime.get(ctx, keepTouch, com.xxxx.emby_vr.vr.VrTuning.webPrefsNow(ctx))
            android.util.Log.i("B0BEmbyVR", "网页内核就绪：触摸事件${if (keepTouch) "保留" else "已关掉"}")
            /* 一次性清腾讯站数据（<web_wipe>=1 时才做）：去掉残留的后台服务注册，见该函数注释。 */
            WebRuntime.wipeTencentOnce(ctx, runtime)
            /* 面板上正在显示的窗口；开新窗口时要更新它（事件必须发给正在显示的那个） */
            var panelView: org.mozilla.geckoview.GeckoView? = null

            /*
             * 造一个网页窗口：设置全按电脑版来，接上「新窗口」与「加载进度」两个回调。
             * 递归调用 —— 新窗口里再点开新窗口时，仍旧走同一套设置。
             */
            fun newBrowserSession(): org.mozilla.geckoview.GeckoSession {
                val s = org.mozilla.geckoview.GeckoSession()
                /*
                 * 三站一律按电脑版走（父亲 2026-10-14 定：腾讯、爱奇艺也要电脑版）。
                 *
                 * 火狐内核这里光换 UA 不够 —— 它有两个独立的"模式"开关，默认都是手机档，
                 * 页面照样按移动端排版：
                 *   userAgentMode = DESKTOP   告诉页面"我是电脑"
                 *   viewportMode  = DESKTOP   让页面按电脑宽度排版（网页宽度撑满大银幕靠这个）
                 * UA 再覆盖成与系统网页视图那条路完全相同的一串，两边口径一致。
                 */
                s.settings.userAgentMode =
                    org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                s.settings.viewportMode =
                    org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                s.settings.userAgentOverride = desktopUa
                s.navigationDelegate = object :
                    org.mozilla.geckoview.GeckoSession.NavigationDelegate {
                    /*
                     * 「在新窗口打开」= 交一个**全新的、还没打开过**的窗口给内核，由内核接过去显示。
                     *
                     * 前两次的教训（2026-10-14）：
                     *   run 519：把当前这个已经打开过的窗口交回去 → 内核断言崩溃
                     *            （父亲实测：点 B站/腾讯 海报闪退）；
                     *   run 517/521：回答「不提供」再自己把地址接到当前页面上 → 内核的收尾与我们
                     *            的发起的载入撞车，页面只开了一半（父亲实测：腾讯跳过去不加载内容；
                     *            B站 / 爱奇艺 不受影响 —— 它们的海报是原页跳转，根本不走这条路）。
                     * 「给内核一个新的未打开窗口」这条是官方示例的写法，前两次都没试到。
                     */
                    override fun onNewSession(
                        session: org.mozilla.geckoview.GeckoSession,
                        uri: String,
                    ): org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession>? {
                        android.util.Log.i("B0BEmbyVR", "网页新窗口就地打开（延后载入）：$uri")
                        /*
                         * 就在**当前这个窗口**里打开（run 525 定，回到"单窗口"这条路）。
                         *
                         * 三条路都走过（2026-10-14 深夜连着实测）：
                         *   · 交给内核（把当前窗口还回去，run 518）：腾讯能打开，但内核随后断言崩溃 —— 点海报闪退；
                         *   · 交一个**新窗口**给内核（run 522/523）：不崩，可新窗口是另一条历史 ——
                         *     B 键退不回去、离开网页页时关掉的还是旧窗口（B站 的声音在 Emby 播放时继续响）；
                         *   · 自己**马上** loadUri（run 517/521）：与内核的收尾撞车，页面只开一半。
                         *
                         * 现在：不提供新窗口（返回空 = 忽略这次新窗口请求，内核只收尾、不再导航），
                         * 并把载入**推迟到内核收尾之后**再做 —— 单窗口，历史连着，也没有两次载入互撞。
                         */
                        if (!uri.startsWith("about:")) {
                            /*
                             * run 530 编译失败：GeckoSession 这一版没有 currentUri（Unresolved reference）。
                             * 本次只为验证「带来源页」这一条假设，且腾讯首页固定是 v.qq.com ——
                             * 先用固定值，假设成立再改成从 onPageStart 记录的真实地址。
                             */
                            val from = "https://v.qq.com/"
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                runCatching {
                                    /*
                                     * <web_ref> 开关（2026-10-15）：再打开时带不带「来源页」。
                                     *
                                     * 差异起点：正常浏览器是从首页**点进去**的，页面拿得到来源页；
                                     * 我们是从代码里直接 loadUri，来源页为空 —— 页面可能因此走了另一条
                                     * 加载路径（run 528/529 日志：173 条跨域预检被腾讯 CDN 拒 400/403/405，
                                     * 播放器与页面主脚本一个都没进来；电脑上同一页面这些脚本全是 200）。
                                     * 用 Loader 把来源页显式设上，别的一律不动。
                                     */
                                    if (com.xxxx.emby_vr.vr.VrTuning.webRefOn == 1) {
                                        session.load(
                                            org.mozilla.geckoview.GeckoSession.Loader()
                                                .uri(uri)
                                                .referrer(from),
                                        )
                                    } else {
                                        session.loadUri(uri)
                                    }
                                }
                            }, 500L)
                        }
                        return null
                    }
                }
                /*
                 * 进度回调只接「加载结束」这一个。
                 *
                 * run 507 实测：onPageStart 的 URL 参数在接口里是非空 String，写成 String?
                 * 会被判「overrides nothing」；而它的默认实现本来就够用 ——
                 * 「正在加载」在发起加载时由外层置位，这里只负责收尾（成功=撤提示，失败=换提示）。
                 */
                s.progressDelegate = object : org.mozilla.geckoview.GeckoSession.ProgressDelegate {
                    /* 诊断用（2026-10-15）：确认腾讯到底把哪个地址载进来了 */
                    override fun onPageStart(ss: org.mozilla.geckoview.GeckoSession, url: String) {
                        android.util.Log.i("B0BEmbyVR", "网页开始载入：$url")
                    }

                    override fun onPageStop(ss: org.mozilla.geckoview.GeckoSession, success: Boolean) {
                        /*
                         * 只有"当前显示的那个窗口"的收尾才动提示。
                         * 被新窗口顶掉的旧窗口也会收尾（success=false，其实是加载被中止），
                         * 不挡住的话，新页面明明是好的，面板上却挂着"请检查网络连接"。
                         */
                        if (panelView?.tag !== ss) return
                        onLoading(false)
                        onFailed(!success)
                    }
                }
                return s
            }

            val session = newBrowserSession()
            session.open(runtime)
            val view = org.mozilla.geckoview.GeckoView(ctx)
            view.setSession(session)
            /*
             * session 借 view.tag 带出去：factory 跑在布局阶段，在那里改 Compose 状态
             * 会在组合期改状态（轻则警告重则崩），所以引用交给 update 再回调。
             */
            view.tag = session
            panelView = view
            session.loadUri(url)
            view
        },
        update = { v ->
            /* 键盘（网页播放器认的左右键）要能进来：给它焦点；鼠标事件是直接派发的，不依赖焦点 */
            if (!v.isFocusable) {
                v.isFocusable = true
                v.isFocusableInTouchMode = true
            }
            if (!v.hasFocus()) v.requestFocus()
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
    val desktopUa = if (com.xxxx.emby_vr.vr.VrTuning.webUaMode == 1) {
        /* 与真实内核一致的身份：火狐 131 / Windows。切 <web_ua> 后重启应用生效。 */
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
    } else {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    /*
     * 诊断用（2026-10-15）：调参文件里给了 <web_url> 就加载它（本地测试页），
     * 否则用三个站点的默认电脑版首页。换试验页面不必重编 APK。
     */
    val url = com.xxxx.emby_vr.vr.VrTuning.webUrl.takeIf { it.isNotEmpty() }
        ?: when (site) {
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
    /*
     * 网页内核做成可实时切换（2026-10-14）：VrTuning 不是 Compose 状态，
     * 直接读它切不动 —— 这里每秒对一次，文件里改了，网页块立刻按新引擎重建。
     */
    var webEngine by remember { mutableStateOf(com.xxxx.emby_vr.vr.VrTuning.webEngine) }
    LaunchedEffect(Unit) {
        while (true) {
            val e = com.xxxx.emby_vr.vr.VrTuning.webEngine
            if (e != webEngine) webEngine = e
            kotlinx.coroutines.delay(1000L)
        }
    }
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
            /* 火狐内核那条路也要能点：geckoRef 插在中间（2026-10-14） */
            val wv: android.view.View? = customView ?: geckoRef ?: webRef
            if (r != null && wv != null && r.width > 0f && r.height > 0f) {
                val u = (px - r.left) / r.width
                val v = (py - r.top) / r.height
                if (u in 0f..1f && v in 0f..1f) {
                    val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
                    val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
                    val now = SystemClock.uptimeMillis()
                    runCatching {
                        /*
                         * 只发"按下 → 抬起"，干净的一次点击。
                         *
                         * 原来在按下前还补两条"鼠标移动"（想让网页控制条浮出来），但光柱本来
                         * 就一直在发悬停，那两条是重复的，还会让网页先看到"鼠标"、再看到
                         * "触摸/别的设备"，设备语义来回切 —— 2026-10-14 实测这种夹着移动的序列
                         * 点不动海报。控制条浮出交给光柱的悬停就够了。
                         */
                        dispatchMouse(wv, MotionEvent.ACTION_DOWN, x, y, now, now)
                        dispatchMouse(wv, MotionEvent.ACTION_UP, x, y, now, now + 40)
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
            val wv = customView ?: geckoRef ?: webRef ?: return@hover
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
                val hoverNow = SystemClock.uptimeMillis()
                dispatchMouse(
                    wv,
                    MotionEvent.ACTION_HOVER_MOVE,
                    x,
                    y,
                    hoverNow,
                    hoverNow,
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
        WebPageHub.backHandler = back@{
            /*
             * 火狐内核那条路：直接让它的会话后退（它没有同步的 canGoBack 可问，
             * 退不动时就是什么都不发生，与原来"退不动返回 false"的观感一致）。
             */
            geckoSessionRef?.let { s ->
                runCatching { s.goBack() }
                return@back true
            }
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
            val wv: android.view.View? = customView ?: geckoRef ?: webRef
            val r = webRect
            /*
             * 摇杆左右 = 网页里快退 / 快进（父亲 2026-10-13 定，2026-10-14 放宽）。
             *
             * 原条件要求「网页自己触发了全屏播放」（customView 非空）。实测（父亲 2026-10-14）：
             * B站 网页播放器在页面内播放时根本不走那条路，customView 一直是空的 ——
             * 快进快退于是永远不生效。现在改成「银幕上有网页就认」：三个站的播放器都吃键盘
             * ← →，发方向键最稳（网页自己跳、自己更新进度条）；列表页的左右键网页本来也无害。
             */
            if (wv != null &&
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
            /* 火狐内核：停会话、放引用（视图本身随 Compose 一起销毁） */
            geckoSessionRef?.let { s ->
                runCatching { s.stop() }
                runCatching { s.close() }
            }
            geckoSessionRef = null
            geckoRef = null
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
