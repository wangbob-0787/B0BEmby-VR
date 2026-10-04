package com.xxxx.emby_vr.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect

/**
 * 控件坐标表（自建，2026-10-04 父亲定案）。
 *
 * ## 为什么需要它
 *
 * VR 的交互模型是「光点指哪儿，扣扳机就打哪儿」：光点位置一直跟踪，
 * **扣扳机的那一刻**才去算「光点落在哪个控件上」，然后把焦点移过去并触发它。
 *
 * 但 Compose 没有「给我一个坐标、告诉我那是哪个控件」的公开接口
 * （命中测试在框架内部，不外露）。所以自己建一张表：每个可点控件在
 * 排版完成时把自己的矩形登记进来，扣扳机时按坐标查表。
 *
 * ## 约定
 *
 * - 坐标是**面板像素**（0..1920 × 0..1080），与 [PanelLayer] 派发输入用的坐标系一致。
 * - 登记用的矩形取 `boundsInWindow()`：面板窗口铺满整块虚拟显示器，
 *   所以窗口坐标就是面板坐标（`PanelLayerTest` 日志里可比对）。
 * - 只登记**能点的**控件（海报卡、按钮、胶囊、集行……），空白处不登记
 *   —— 扣扳机落在空白上就什么都不做（父亲 2026-10-04 定的规则）。
 * - 全部读写都在主线程（Compose 排版 + 输入派发都在主线程），因此用普通
 *   LinkedHashMap 即可；仍然加了同步块，防止以后有人从别的线程调用。
 */
object ClickTargets {

    /** 一个可点控件：矩形 + 可选焦点 + 触发动作 */
    class Target(
        val key: Any,
        /** 日志用名字（同一个内容出现在两排里时，两把钥匙不同但名字一样） */
        val label: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val focus: (() -> Unit)?,
        val activate: () -> Unit,
    ) {
        val area: Float get() = (right - left) * (bottom - top)

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        /** 点到矩形外的距离（点在矩形内为 0）——诊断用 */
        fun distance(x: Float, y: Float): Float {
            val dx = maxOf(left - x, 0f, x - right)
            val dy = maxOf(top - y, 0f, y - bottom)
            return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }

        override fun toString(): String =
            "$label (${left.toInt()},${top.toInt()})-(${right.toInt()},${bottom.toInt()})"
    }

    private val targets = LinkedHashMap<Any, Target>()

    /** 登记/查表诊断计数（首次装机时用来核对坐标是否与面板像素一致） */
    private var putCount = 0

    fun put(
        key: Any,
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        focus: (() -> Unit)?,
        activate: () -> Unit,
    ) {
        synchronized(targets) {
            targets[key] = Target(key, label, left, top, right, bottom, focus, activate)
            putCount++
            // 头 15 条 + 每 50 条打一行：既能核对坐标，又不会把日志刷爆
            if (putCount <= 15 || putCount % 50 == 0) {
                android.util.Log.i(
                    "B0BEmbyVR",
                    "坐标表登记 #$putCount $label = ($left, $top)-($right, $bottom) 表内 ${targets.size} 项",
                )
            }
        }
    }

    fun remove(key: Any) {
        synchronized(targets) {
            targets.remove(key)
        }
    }

    /** 面板重建时清空（旧控件已经不在了，留着只会误命中） */
    fun clear() {
        synchronized(targets) {
            targets.clear()
        }
    }

    fun size(): Int = synchronized(targets) { targets.size }

    /**
     * 查光点落在哪个控件上。
     *
     * 有嵌套时（大控件里套小控件）取**面积最小**的那个 —— 最具体的赢，
     * 否则点海报卡里的按钮会打到整张卡。
     */
    fun findAt(x: Float, y: Float): Target? = synchronized(targets) {
        targets.values.filter { it.contains(x, y) }.minByOrNull { it.area }
    }

    /**
     * 诊断用：离点击点最近的几个控件（含距离，单位=面板像素）。
     *
     * 用在「点下去什么都没命中」的时候 —— 一眼能看出是**压根没登记**（最近目标很远），
     * 还是**坐标/矩形对不上**（最近目标只差几像素）。
     */
    fun nearest(x: Float, y: Float, count: Int = 5): List<Pair<Target, Float>> =
        synchronized(targets) {
            targets.values
                .map { it to it.distance(x, y) }
                .sortedBy { it.second }
                .take(count)
        }
}

/**
 * 把一个 Compose 控件登记进 [ClickTargets]。
 *
 * 用法（贴在控件自己的 modifier 链上，通常紧跟 focusRequester）：
 * ```
 * Modifier.vrClickTarget(
 *     key = "card:${item.id}",
 *     focusRequester = focusAnchor,
 *     onActivate = onItemClick,
 * )
 * ```
 *
 * 生命周期：控件离开组合（列表回收、切屏）时自动摘除，不会留下幽灵条目。
 */
@Composable
fun Modifier.vrClickTarget(
    key: Any,
    focusRequester: FocusRequester? = null,
    onActivate: () -> Unit,
): Modifier {
    // 用 rememberUpdatedState 拿最新的闭包/请求器，避免登记的是上一帧的旧值
    val activate by rememberUpdatedState(onActivate)
    val requester by rememberUpdatedState(focusRequester)
    /*
     * 每个控件实例一把**独立**钥匙（父亲 2026-10-05 实测：继续观看那排点不动）。
     *
     * 原因：同一条内容会同时出现在两排里（继续观看 + 最近添加），
     * 以前用内容 id 当钥匙，后登记的那条把先登记的矩形**覆盖**掉，
     * 于是其中一排的坐标从表里消失 → 扣扳机判成"点在空白" → 没反应。
     * 钥匙只用来标识"这块矩形是谁的"，生命周期跟着这个控件实例走，
     * 所以实例唯一即可；`key` 字符串只留给日志看。
     */
    val instance = remember { Any() }

    DisposableEffect(instance) {
        onDispose { ClickTargets.remove(instance) }
    }

    return this.onGloballyPositioned { coords ->
        if (!coords.isAttached) return@onGloballyPositioned
        val b: Rect = coords.boundsInWindow()
        if (b.width <= 1f || b.height <= 1f) return@onGloballyPositioned
        ClickTargets.put(
            key = instance,
            label = key.toString(),
            left = b.left,
            top = b.top,
            right = b.right,
            bottom = b.bottom,
            focus = requester?.let { r -> { runCatching { r.requestFocus() } } },
            activate = { activate() },
        )
    }
}
