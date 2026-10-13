package com.xxxx.emby_vr.web

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 网页银幕（父亲 2026-10-13）。
 *
 * ## 干什么
 * 把 B站 / 腾讯视频 / 爱奇艺 的**网页版**放到影院的银幕上 —— 网页在**我们自己的进程里**渲染，
 * 不需要任何系统特权（不需要嵌入别的应用窗口、不需要转发输入的系统接口）。
 * 这是 2026-10-13 实测选定的路线：嵌别人应用的窗口那条路（ActivityView / 虚拟屏）普通应用
 * 拿不到画面也点不动；网页这条路画面正常、能点、能放（B站 4K / 腾讯 720p 均实测不黑）。
 *
 * ## 怎么接进影院
 * 银幕上显示的东西来自原生渲染线程建的那张画面纹理（`renderer.vrVideoSurface`）。
 * 这里在**同一张 Surface 上开一块虚拟屏**，把 WebView 放进这块虚拟屏的窗口里
 * （VirtualDisplay + Presentation，和面板同一套公开 API），原生那头照旧采样这张纹理 ——
 * 于是网页就出现在银幕上，渲染管线一行都不用改。
 *
 * ## 全屏（父亲 2026-10-13 明确）
 * 网页里点全屏 = 画面铺满整块银幕，网页自带的边栏工具栏都收掉。做法是接住
 * `onShowCustomView`（网页请求全屏时系统交给宿主的那块视图），把它铺满整块画布，
 * 并通知调用方把银幕画面按"铺满"比例摆（见 onFullscreen 回调）。
 *
 * ## 输入
 * 光柱点击由 MainActivity 转成面板像素（1920×1080）后交给 [tap]，
 * 这里按比例映射成网页像素再派发给 WebView —— 是我们自己视图树内的事件，不需要任何权限。
 */
class WebScreen(
    private val activity: Activity,
    private val surface: Surface,
    private val onEvent: (String) -> Unit = {},
    /** 全屏状态变化：true = 网页全屏（画面该铺满银幕） */
    private val onFullscreen: (Boolean) -> Unit = {},
) {

    companion object {
        private const val TAG = "WebScreen"

        /** 虚拟屏分辨率：16:9 的 1080p，够清晰也不拖累 PICO 的 GPU */
        const val W = 1920
        const val H = 1080

        /** 面板画布像素（光柱坐标就是这一套，见 MainActivity 的 vrInput 注释） */
        private const val PANEL_W = 1920f
        private const val PANEL_H = 1080f

        /** 桌面版 UA：B站 / 腾讯 的完整播放器只在桌面 UA 下给 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    /** 是否正处在网页模式 */
    var active = false
        private set

    /** 网页是否已全屏 */
    var fullscreen = false
        private set

    /** 当前网址（退出/重进时记着用） */
    var url: String = ""
        private set

    /** 打开一个网址，银幕换成网页 */
    fun start(target: String) {
        stop()
        url = target
        try {
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(
                "b0b-web",
                W,
                H,
                240,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            display = vd
            val p = WebPresentation(activity, vd.display, ::buildContent)
            presentation = p
            p.show()
            active = true
            onEvent("网页银幕已打开：$target")
        } catch (t: Throwable) {
            onEvent("打开网页银幕失败：${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.e(TAG, "打开网页银幕失败", t)
            stop()
        }
    }

    /** 关掉网页银幕，把银幕还给播放器 */
    fun stop() {
        active = false
        setFullscreen(false)
        runCatching { webView?.stopLoading() }
        runCatching { webView?.loadUrl("about:blank") }
        runCatching { webView?.destroy() }
        webView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        container = null
        runCatching { display?.release() }
        display = null
    }

    /** 光柱点击：面板像素（1920×1080）→ 网页像素 */
    fun tap(px: Float, py: Float) {
        val wv = webView ?: return
        val x = (px / PANEL_W * W).coerceIn(0f, W - 1f)
        val y = (py / PANEL_H * H).coerceIn(0f, H - 1f)
        val now = SystemClock.uptimeMillis()
        runCatching {
            wv.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
            wv.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0))
        }
    }

    /** 摇杆上下 = 网页滚动 */
    fun scroll(dy: Float) {
        val wv = webView ?: return
        val step = (dy * 90f).toInt()
        if (step == 0) return
        runCatching { wv.evaluateJavascript("window.scrollBy(0, $step);", null) }
    }

    /**
     * 返回键：网页全屏时先退出全屏（返回 true = 这次按键被吃掉了）。
     * 没全屏就返回 false，交给上层退出网页模式。
     */
    fun onBack(): Boolean {
        if (fullscreen) {
            runCatching { webView?.evaluateJavascript("document.exitFullscreen && document.exitFullscreen();", null) }
            setFullscreen(false)
            return true
        }
        return false
    }

    // ---------------- 内部 ----------------

    private fun buildContent(ctx: Context): View {
        val box = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        container = box
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_UA
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) {
                onEvent("网页加载完成：$u")
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                onEvent("网页加载出错：${error.errorCode} ${error.description}")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                onEvent("网页请求全屏：画面铺满银幕")
                fullView = view
                fullCallback = callback
                (view.parent as? ViewGroup)?.removeView(view)
                box.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                onEvent("网页退出全屏")
                fullView?.let { box.removeView(it) }
                fullCallback?.onCustomViewHidden()
                fullView = null
                fullCallback = null
                setFullscreen(false)
            }
        }
        box.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView = wv
        val ver = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        onEvent("网页内核版本：$ver")
        wv.loadUrl(url)
        return box
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        runCatching { onFullscreen(on) }
    }

    /** 承载 WebView 的窗口（虚拟屏上的一块真窗口，公开 API） */
    private class WebPresentation(
        outer: Context,
        display: android.view.Display,
        private val build: (Context) -> View,
    ) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(build(context))
        }
    }
}
