package com.xxxx.emby_vr.web

/**
 * 网页交互中转（父亲 2026-10-13）。
 *
 * ## 为什么需要它
 * 面板上的点击**不走系统触摸**，而是"光柱落点 → 查控件坐标表 → 调控件动作"那一套
 * （见 panel/ClickTargets.kt）。查表拿到的动作是**无参**的，没有坐标 ——
 * 而网页里点哪儿、点哪个按钮，恰恰全靠坐标。
 *
 * 所以网页这一页自己把"收到点击(面板像素)该怎么派发给网页"登记到这里，
 * 主界面收到光柱点击时先问这里有没有人在用网页：有就直接把坐标交过去（最准），
 * 没有再走原来的查表逻辑。滚动（摇杆）同理。
 *
 * 网页页离开界面时（DisposableEffect 的 onDispose）一定要 [clear]，
 * 否则坐标会被派发给一个已经销毁的网页。
 */
object WebPageHub {

    /**
     * 网页页登记：把面板像素(px,py)派发给网页。
     * 返回 true = 这一下点进了网页（外面就别再处理了）；
     * false = 落在网页之外（比如本页自己的顶栏按钮），交回常规查表逻辑。
     */
    var tapHandler: ((Float, Float) -> Boolean)? = null

    /**
     * 网页页登记：摇杆。
     * 参数 = 光柱落点(px,py，面板像素) + 摇杆横向 sx + 纵向 sy。
     * 带落点是必须的 —— 滚动事件按坐标做命中测试，落点在哪就滚哪一块。
     * 横向量给界面层判"网页全屏播放时的快退/快进"（父亲 2026-10-13）。
     */
    var scrollHandler: ((Float, Float, Float, Float) -> Unit)? = null

    /**
     * 网页页登记：光柱在银幕上移动 = 鼠标悬停（父亲 2026-10-13：手柄当鼠标）。
     *
     * 只由"银幕上放着网页"这一路推过来（原生的 onWebHover），所以天然限制在大银幕内。
     */
    var hoverHandler: ((Float, Float) -> Unit)? = null

    /**
     * 网页页登记：网页内返回（B 键）。
     * 返回 true = 真的退回了上一页；false = 已经在网页首页（父亲 2026-10-13 定：
     * 网页里 B 键只在网页不同页面之间返回，不返回 Emby 片库、也不回海报墙首页）。
     */
    var backHandler: (() -> Boolean)? = null

    /** 当前是否有网页页在用交互 */
    val active: Boolean
        get() = tapHandler != null

    fun tap(px: Float, py: Float): Boolean {
        val h = tapHandler ?: return false
        return h(px, py)
    }

    fun scroll(px: Float, py: Float, sx: Float, sy: Float) {
        scrollHandler?.invoke(px, py, sx, sy)
    }

    fun hover(px: Float, py: Float) {
        hoverHandler?.invoke(px, py)
    }

    /**
     * 网页全屏开关通知（父亲 2026-10-13）：true = 网页全屏了，把画面投到影院大银幕；
     * false = 退出全屏，银幕还给影片。真正的动作在主界面（它拿得到渲染器）。
     */
    var fullscreenHandler: ((Boolean) -> Unit)? = null

    fun setFullscreen(on: Boolean) {
        fullscreenHandler?.invoke(on)
    }

    fun back(): Boolean = backHandler?.invoke() ?: false

    fun clear() {
        tapHandler = null
        scrollHandler = null
        hoverHandler = null
        backHandler = null
    }
}
