package com.xxxx.emby_vr.panel

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 面板层（UI 复用验证，2026-10-04）。
 *
 * ## 要解决的问题
 *
 * 父亲定的方向：除弹幕、字幕、控制条外，**全部复用电视版 B0BEmby 的界面**。
 * 电视版界面是 Jetpack Compose 写的，VR 版是 OpenGL 手绘的，代码不能直接拷，
 * 所以必须先验证一条通道：
 *
 * ```
 * Compose 界面 → 虚拟显示器 → SurfaceTexture → GL 外部纹理 → 贴到 VR 平面
 * ```
 *
 * ## 为什么走 VirtualDisplay + Presentation
 *
 * - `VirtualDisplay` 是公开 API，不需要任何签名权限；输出 surface 用
 *   `SurfaceTexture`，正好能变成 GL 纹理贴进场景。
 * - `Presentation` 是挂在指定显示器上的窗口，能承载 ComposeView，
 *   并且**窗口就在本进程里** —— 这一点决定了输入方案。
 *
 * ## 输入为什么不用系统注入
 *
 * 2026-09-27 探针实测：`INJECT_EVENTS` 在 PICO 上是签名权限（`granted=false`），
 * 注入系统事件这条路已被否掉。但窗口在本进程里，可以直接
 * `decorView.dispatchTouchEvent(构造的 MotionEvent)` —— 同进程派发不需要权限。
 * 坐标由 Activity 侧做「光标 → 虚拟屏 → 面板像素」换算后传进来。
 *
 * ## 与最终形态的关系
 *
 * 这是地基验证：面板尺寸固定 1920x1080（1080p 电视版布局直接可用）。
 * 验证通过后再决定面板的物理尺寸、拖动、以及选片墙的落位。
 */
class PanelLayer(
    private val activity: ComponentActivity,
    private val content: @Composable () -> Unit,
) {

    companion object {
        const val TAG = "B0BEmbyVR"

        /** 面板像素尺寸：与电视版 1080p 布局一致，界面代码搬过来不用改尺寸 */
        const val W = 1920
        const val H = 1080

        /** 240dpi → 1920x1080 折合 1280x720 dp，与电视版十尺布局接近 */
        const val DPI = 240

        /**
         * 点击/拖动的分界（面板像素）。
         *
         * 240dpi 下面板 1dp = 1.5px，取系统惯例 8dp = 12px 的 touch slop：
         * 按下后位移小于它算手抖（仍按点击处理），超过它才算拖动。
         */
        private const val SLOP_PX = 12f
        private const val SLOP_PX2 = SLOP_PX * SLOP_PX
    }

    /** 面板是否可用（Presentation 已显示、decorView 已就绪） */
    @Volatile
    var ready = false
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PanelPresentation? = null
    private var decor: View? = null
    private var downTime = 0L

    /** 本次按下的锁定位置（抬起时若没拖动，就用它收尾成一次干净的点击） */
    private var downPx = 0f
    private var downPy = 0f

    /** 是否处于按下状态（含拖动），以及是否已越过阈值进入拖动 */
    private var isDown = false
    private var isDragging = false

    /**
     * GL 线程建好 OES 纹理与 SurfaceTexture 后调用。
     * 内部切到主线程建虚拟显示器与 Presentation（窗口操作必须在主线程）。
     */
    fun attach(st: SurfaceTexture) {
        surfaceTexture = st
        activity.runOnUiThread { buildDisplay(st) }
    }

    private fun buildDisplay(st: SurfaceTexture) {
        try {
            st.setDefaultBufferSize(W, H)
            val surface = Surface(st)
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            /*
             * OWN_CONTENT_ONLY：这块虚拟显示器只显示本应用自己的窗口，
             * 不接收其他应用投屏（PUBLIC 会让别人也能往里投）。
             */
            val vd = dm.createVirtualDisplay(
                "b0bemby-panel",
                W,
                H,
                DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = vd

            val p = PanelPresentation(activity, vd.display, activity, content) { view ->
                decor = view
                ready = true
                Log.i(TAG, "面板层就绪: ${W}x$H dpi=$DPI displayId=${vd.display.displayId}")
            }
            p.show()
            presentation = p
        } catch (t: Throwable) {
            // 失败不崩：渲染器会回落到原来的静态屏，日志里留明确原因
            Log.e(TAG, "面板层创建失败: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /** 一次点击（自动自测用）：DOWN + UP 同点 */
    fun tap(px: Float, py: Float) {
        dispatch(px, py, MotionEvent.ACTION_DOWN)
        dispatch(px, py, MotionEvent.ACTION_UP)
    }

    /**
     * 把一次触摸事件派发进面板窗口。
     *
     * 坐标是**面板像素坐标**（0..W, 0..H），由调用方换算。
     * 用 `dispatchTouchEvent` 而不是 `injectInputEvent`：同进程派发，
     * 不需要 INJECT_EVENTS（该权限在 PICO 上拿不到）。
     *
     * ## 为什么要做「按下后小幅飘移不算拖动」（2026-10-04 实机定位）
     *
     * 实测日志：扳机按下与抬起时激光光标位置差约 100 像素（面板像素），
     * Compose 的点击判定要求按下→抬起期间指针不超出 touch slop，
     * 于是每一次点击都被判成「拖动」而取消 —— 表现就是**光标能指到按钮但点不动**。
     * 电视版在电视上用遥控器按键导航，压根没有指针，所以这块在复用界面里无代码可抄，
     * 必须在派发层补：按下时锁定位置，位移小于阈值当抖动吞掉，越过阈值才算拖动
     * （拖动正是父亲定的「按住扳机拖 = 拖进度条 / 拖选片墙」所要的语义）。
     */
    fun dispatch(px: Float, py: Float, action: Int) {
        val v = decor ?: return
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now
                downPx = px
                downPy = py
                isDown = true
                isDragging = false
                send(v, px, py, action, now)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return
                if (!isDragging) {
                    val dx = px - downPx
                    val dy = py - downPy
                    if (dx * dx + dy * dy < SLOP_PX2) return   // 抖动：吞掉，不打扰界面
                    isDragging = true                          // 越过阈值 → 进入拖动
                    Log.i(TAG, "面板进入拖动: 从 (${downPx.toInt()},${downPy.toInt()}) 起")
                }
                send(v, px, py, action, now)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDown) return
                isDown = false
                val dragging = isDragging
                isDragging = false
                // 没拖动 → 抬起仍用按下时的位置，让 Compose 收到一次干净的点击
                val tx = if (dragging) px else downPx
                val ty = if (dragging) py else downPy
                send(v, tx, ty, action, now)
            }

            else -> send(v, px, py, action, now)
        }
    }

    private fun send(v: View, px: Float, py: Float, action: Int, now: Long) {
        val ev = MotionEvent.obtain(downTime, now, action, px, py, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            v.dispatchTouchEvent(ev)
        } catch (t: Throwable) {
            Log.e(TAG, "面板派发失败: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            ev.recycle()
        }
    }

    /** 面板 UI 内按钮点击回调（Compose 侧触发） */
    fun release() {
        try {
            presentation?.dismiss()
        } catch (_: Throwable) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        presentation = null
        virtualDisplay = null
        decor = null
        ready = false
    }
}

/**
 * 承载 Compose 界面的窗口（挂在虚拟显示器上）。
 *
 * Compose 需要 ViewTree 上的 Lifecycle / ViewModelStore / SavedStateRegistry
 * 三个 owner；Dialog 系的窗口不会自动提供（只有 ComponentActivity 的
 * decorView 才有），所以这里手动把 Activity 设进去。
 */
private class PanelPresentation(
    outer: Context,
    display: Display,
    private val activity: ComponentActivity,
    private val content: @Composable () -> Unit,
    private val onReady: (View) -> Unit,
) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cv = ComposeView(context)
        cv.setViewTreeLifecycleOwner(activity)
        cv.setViewTreeViewModelStoreOwner(activity)
        cv.setViewTreeSavedStateRegistryOwner(activity)
        /*
         * 除了 ViewTree 的三个 owner，还必须提供 LocalOnBackPressedDispatcherOwner。
         *
         * 2026-10-04 实机闪退（build-45）：
         *   IllegalStateException: No OnBackPressedDispatcherOwner was provided
         *   via LocalOnBackPressedDispatcherOwner
         *   at NavHostKt.NavHost
         * Navigation 的 NavHost 内部用 PredictiveBackHandler 处理返回手势，
         * 它从这个 CompositionLocal 取宿主；而我们的 ComposeView 挂在
         * Presentation 窗口里，不在 Activity 的 decorView 下，系统不会自动提供。
         * 把 Activity 自己（ComponentActivity 就是 OnBackPressedDispatcherOwner）提供进去即可。
         */
        cv.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides activity) {
                content()
            }
        }
        setContentView(cv)

        onReady(window!!.decorView)
    }
}
