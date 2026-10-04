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
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner

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
    private val onUiClick: () -> Unit,
) {

    companion object {
        const val TAG = "B0BEmbyVR"

        /** 面板像素尺寸：与电视版 1080p 布局一致，界面代码搬过来不用改尺寸 */
        const val W = 1920
        const val H = 1080

        /** 240dpi → 1920x1080 折合 1280x720 dp，与电视版十尺布局接近 */
        const val DPI = 240
    }

    /** 面板是否可用（Presentation 已显示、decorView 已就绪） */
    @Volatile
    var ready = false
        private set

    /** 面板里按钮被点击的次数（自动自测与实机点击都会累加） */
    @Volatile
    var clickCount = 0
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PanelPresentation? = null
    private var decor: View? = null
    private var downTime = 0L

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

            val p = PanelPresentation(activity, vd.display, activity, onUiClick) { view ->
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
     */
    fun dispatch(px: Float, py: Float, action: Int) {
        val v = decor ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
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
    fun onPanelClick() {
        clickCount += 1
        onUiClick()
    }

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
    private val onUiClick: () -> Unit,
    private val onReady: (View) -> Unit,
) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cv = ComposeView(context)
        ViewTreeLifecycleOwner.set(cv, activity)
        ViewTreeViewModelStoreOwner.set(cv, activity)
        ViewTreeSavedStateRegistryOwner.set(cv, activity)
        cv.setContent {
            PanelUi(onClick = onUiClick)
        }
        setContentView(cv)

        onReady(window.decorView)
    }
}
