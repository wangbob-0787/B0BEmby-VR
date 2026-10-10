package com.xxxx.emby_vr.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「被光柱扫到」的统一反馈（父亲 2026-10-10）。
 *
 * ## 为什么之前没有
 *
 * 光柱位置一直只有**控制条**在用（`PlayerOsd` 拿 `pointerNx/Ny` 算按钮悬停），
 * 海报墙和各个页面从来没接过这层判断 —— 光柱扫过去自然什么都没有。
 * 上一版给海报卡加的白层挂在**焦点**上，而光柱扫过不产生焦点，于是等于没加。
 *
 * ## 现在怎么算
 *
 * 光柱每动一下，用控件坐标表 [ClickTargets] 查当前指着谁（有模态组时只在模态组里查），
 * 把结果写进 [VrHover.key]；控件在 draw 阶段比对自己的实例钥匙，
 * 命中就盖一层半透明白。与扣扳机用的是**同一张表、同一套命中规则**，
 * 所以"看到亮的"和"点下去中的"永远是同一个控件。
 *
 * 性能：state 只在**扫到的目标变了**的时候才写（扫过一张海报只写一次），
 * 读它的控件只重绘那一层，不重建组合。
 */
object VrHover {

    /** 当前被扫到的控件实例钥匙（控件自己用 `remember { Any() }` 生成） */
    var key: Any? by mutableStateOf(null)
        private set

    /** 扫到的白层：与控制条按钮同一档（`PlayerOsd.OsdButtonView` 的悬停色） */
    val overlayColor: Color = Color(0x24FFFFFF)

    fun update(next: Any?) {
        if (key !== next) key = next
    }

    fun clear() = update(null)

    /**
     * 模态组开着时，只有同组的控件可以亮（浮层开着时后面海报墙不该亮）。
     * 与 [ClickTargets.findAt] 的过滤规则保持一致。
     */
    fun allowed(group: Any?): Boolean = ClickTargets.modalAllows(group)
}

/**
 * 光柱坐标（面板像素），给**没有登记进坐标表**的控件用。
 *
 * 坐标表覆盖的是「能点、且需要精确命中」的控件；播放菜单里的行是靠
 * 坐标注入点击的（没进表），拿不到实例钥匙，所以它们改用这个坐标自己判断。
 *
 * 发布做了节流：位置变化超过 [kPublishStepPx] 像素、或距上次超过 [kPublishMs] 毫秒
 * 才写一次 state —— 否则每个像素都会让所有读它的控件重绘一遍（整墙海报 60Hz 重绘）。
 */
object VrPointer {

    /** 位置变化阈值（像素） */
    private const val kPublishStepPx = 10f

    /** 时间阈值（毫秒） */
    private const val kPublishMs = 70L

    var px: Float by mutableStateOf(Float.NaN)
        private set
    var py: Float by mutableStateOf(Float.NaN)
        private set

    private var publishedX = Float.NaN
    private var publishedY = Float.NaN
    private var publishedAt = 0L

    fun publish(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val moved = px.isNaN() ||
            kotlin.math.abs(x - publishedX) >= kPublishStepPx ||
            kotlin.math.abs(y - publishedY) >= kPublishStepPx
        if (!moved && now - publishedAt < kPublishMs) return
        publishedX = x
        publishedY = y
        publishedAt = now
        px = x
        py = y
    }

    fun clear() {
        publishedX = Float.NaN
        publishedY = Float.NaN
        publishedAt = 0L
        px = Float.NaN
        py = Float.NaN
    }
}

/**
 * 「被光柱扫到就盖一层白」——按坐标判断的版本（用在没进坐标表的控件上）。
 *
 * 用法：贴在控件自己的 modifier 链上，参数是圆角半径（与控件自身圆角一致）。
 */
@Composable
fun Modifier.vrHoverPointer(radius: Dp = 12.dp, group: Any? = null): Modifier {
    val bounds = remember { mutableStateOf<Rect?>(null) }
    return this
        .onGloballyPositioned { coords ->
            if (!coords.isAttached) return@onGloballyPositioned
            val b: Rect = coords.boundsInWindow()
            if (b.width <= 1f || b.height <= 1f) return@onGloballyPositioned
            bounds.value = b
        }
        .drawWithContent {
            drawContent()
            val b = bounds.value ?: return@drawWithContent
            val x = VrPointer.px
            val y = VrPointer.py
            if (x.isNaN() || y.isNaN()) return@drawWithContent
            if (x < b.left || x > b.right || y < b.top || y > b.bottom) return@drawWithContent
            if (!VrHover.allowed(group)) return@drawWithContent
            drawRoundRect(
                color = VrHover.overlayColor,
                cornerRadius = CornerRadius(radius.toPx()),
            )
        }
}
