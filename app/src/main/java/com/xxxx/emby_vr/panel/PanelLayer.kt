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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
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

        /**
         * 摇杆滚动的步长（面板像素）与限速（毫秒）。
         *
         * PICO 摇杆是「合成 按下+拖动+抬起」，一次推可达 800+ 像素。
         * 父亲 2026-10-04 定：摇杆用来滚动列表 —— 所以推住不放要能连续滚（按步长反复发方向键），
         * 但不能一次推就把焦点连飞十几格，因此加 130ms 限速（约每秒 7 格，滚动顺滑）。
         */
        private const val STEP_PX = 110f
        private const val FIRE_INTERVAL_MS = 130L

    }

    /** 面板是否可用（Presentation 已显示、decorView 已就绪） */
    @Volatile
    var ready = false
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PanelPresentation? = null
    private var decor: View? = null

    /** 本次按下的锁定位置（抬起时若没拖动，就用它收尾成一次干净的点击） */
    private var downPx = 0f
    private var downPy = 0f

    /** 是否处于按下状态（含拖动），以及是否已越过阈值进入拖动 */
    private var isDown = false
    private var isDragging = false

    /** 上一次发方向键的位置与时间（摇杆滚动：按步长 + 限速反复发） */
    private var stepPx = 0f
    private var stepPy = 0f
    private var lastFireAt = 0L

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

    /**
     * 给面板发一个按键（遥控器语义）。
     *
     * 电视版界面是「焦点 + OK」的遥控器模型：输入框、按钮的激活都走
     * 方向键移动焦点 + OK 键确认，`clickable` 在登录页甚至被注释掉了
     * （LoginScreen.kt:315）。所以复用电视版界面时，扳机必须翻译成 OK 键，
     * 光标点击对这类组件不生效（父亲 2026-10-04 实测：登录按钮点不动）。
     */
    fun key(keyCode: Int) {
        val v = decor ?: return
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, action, keyCode, 0)
            try {
                val consumed = v.dispatchKeyEvent(ev)
                Log.i(TAG, "面板按键 key=$keyCode action=$action 被界面接收=$consumed")
            } catch (t: Throwable) {
                Log.e(TAG, "按键派发失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * 鼠标式点击（父亲 2026-10-04 要求「光标指哪儿、扣扳机就激活哪儿」）。
     *
     * 为什么不用触摸式：实测合成触摸事件虽然被界面「接住」（dispatchTouchEvent 返回
     * true），但电视版的组件不响应 —— 同一个位置用触摸点海报/播放按钮都没动作，
     * 而把焦点移上去按 OK 就有动作。Compose 对 **MOUSE 源**的按下/抬起按鼠标点击处理
     * （Android TV 本来就支持鼠标），所以这里改发鼠标事件：
     *   1) ACTION_HOVER_MOVE 先把指针悬停到该位置（建立 hover 状态）
     *   2) ACTION_DOWN（buttonState = 主键）→ ACTION_UP
     *
     * @return 是否有视图接住
     */
    private fun mouseClick(px: Float, py: Float): Boolean {
        val v = decor ?: return false
        val now = SystemClock.uptimeMillis()
        var handled = false

        MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, px, py, 0).let { e ->
            e.source = InputDevice.SOURCE_MOUSE
            try {
                handled = v.dispatchGenericMotionEvent(e) || handled
            } catch (t: Throwable) {
                Log.e(TAG, "悬停派发失败: ${t.message}")
            } finally {
                e.recycle()
            }
        }

        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0).let { e ->
            e.source = InputDevice.SOURCE_MOUSE
            e.buttonState = MotionEvent.BUTTON_PRIMARY
            try {
                handled = v.dispatchTouchEvent(e) || handled
            } catch (t: Throwable) {
                Log.e(TAG, "鼠标按下派发失败: ${t.message}")
            } finally {
                e.recycle()
            }
        }

        MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, px, py, 0).let { e ->
            e.source = InputDevice.SOURCE_MOUSE
            e.buttonState = 0
            try {
                handled = v.dispatchTouchEvent(e) || handled
            } catch (t: Throwable) {
                Log.e(TAG, "鼠标抬起派发失败: ${t.message}")
            } finally {
                e.recycle()
            }
        }
        return handled
    }

    /**
     * 按面板像素位置送一次触摸点击（DOWN + UP）。
     *
     * @return 是否有视图接住（decor.dispatchTouchEvent 的返回值）
     */
    private fun touchClick(px: Float, py: Float): Boolean {
        val v = decor ?: return false
        val now = SystemClock.uptimeMillis()
        var handled = false
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(now, now, action, px, py, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                handled = v.dispatchTouchEvent(ev) || handled
            } catch (t: Throwable) {
                Log.e(TAG, "触摸派发失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                ev.recycle()
            }
        }
        return handled
    }

    /** 一次点击（自动自测用）：DOWN + UP 同点 */
    fun tap(px: Float, py: Float) {
        dispatch(px, py, MotionEvent.ACTION_DOWN)
        dispatch(px, py, MotionEvent.ACTION_UP)
    }

    /**
     * 把一次「扳机 + 光标」操作翻译成电视版界面认识的按键。
     *
     * ## 为什么不是触摸（2026-10-04 实机定位）
     *
     * 电视版界面是**遥控器（焦点 + OK）模型**：按钮/输入框靠焦点 + OK 激活，
     * 列表靠方向键滚动，登录页的 `clickable` 甚至是注释掉的
     * （LoginScreen.kt:315）。光标模拟触摸对这类组件不生效 ——
     * 实测表现就是「光标能指到按钮，点不动」。
     *
     * PICO 面板模式只给两样输入：光标坐标 + 扳机（BTN_TOUCH DOWN/UP），
     * 没有任何按键事件（实测 dispatchKeyEvent 一条都没收到）。所以在这两样上
     * 合成一套遥控器语义：
     *
     * ## 手柄 ↔ 遥控器统一语义（父亲 2026-10-04 定）
     *
     * | 手柄 | 遥控器 | 作用 |
     * |---|---|---|
     * | A 键 / 扳机 | OK 键 | 确定（激活当前焦点） |
     * | B 键 | 返回键 | 返回上一层（在 MainActivity 里转发） |
     * | 摇杆 | 方向键 | 四向滚动 / 移动焦点；播放页左右 = 快进快退 |
     *
     * 摇杆在 PICO 面板模式下是「合成 按下+拖动+抬起」送进来的，
     * 所以这里按位移方向合成方向键；位移小于 slop 的才算一次确定。
     *
     * 坐标是**面板像素坐标**（0..W, 0..H），由调用方换算。
     */
    fun dispatch(px: Float, py: Float, action: Int) {
        if (decor == null) return
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downPx = px
                downPy = py
                isDown = true
                isDragging = false
                lastFireAt = now
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return
                val dx = px - downPx
                val dy = py - downPy
                if (!isDragging) {
                    if (dx * dx + dy * dy < SLOP_PX2) return       // 手抖：不算摇杆
                    isDragging = true
                    stepPx = px
                    stepPy = py
                    lastFireAt = now
                    // 方向测量：起始点 → 当前点（判断 PICO 摇杆位移的符号）
                    Log.i(
                        TAG,
                        "摇杆测量 起点=(${downPx.toInt()},${downPy.toInt()}) " +
                            "当前=(${px.toInt()},${py.toInt()}) 位移=(${dx.toInt()},${dy.toInt()})",
                    )
                    return
                }
                // 摇杆滚动：从上一次发键的位置算位移，够一格且过了限速就再发一格
                val sx = px - stepPx
                val sy = py - stepPy
                if (kotlin.math.abs(sx) < STEP_PX && kotlin.math.abs(sy) < STEP_PX) return
                if (now - lastFireAt < FIRE_INTERVAL_MS) return
                stepPx = px
                stepPy = py
                lastFireAt = now
                val code = if (kotlin.math.abs(sx) >= kotlin.math.abs(sy)) {
                    if (sx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                } else {
                    if (sy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                }
                Log.i(TAG, "摇杆发键 位移=(${sx.toInt()},${sy.toInt()}) → key=$code（19上/20下/21左/22右）")
                key(code)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDown) return
                isDown = false
                val dragged = isDragging
                isDragging = false
                if (dragged) return                                // 摇杆推动：只发方向键，不发 OK
                /*
                 * 双通道点击（2026-10-04 排查用）：
                 *   1) 按光标位置送一次触摸点击 —— 对自绘 clickable 组件有效
                 *   2) 送 OK 键 —— 对电视版「焦点 + OK」组件有效
                 * 两条都发、各自记录是否被界面接住，实机看日志即可确定哪条有效，
                 * 确认后删掉多余的那条（避免同一个按钮被激活两次）。
                 */
                val mouseHit = mouseClick(px, py)
                Log.i(TAG, "面板点击: 鼠标式被接住=$mouseHit 位置 (${px.toInt()},${py.toInt()})")
                if (!mouseHit) {
                    // 鼠标式没人接 → 回退到「焦点 + OK」通道（电视版原有语义）
                    Log.i(TAG, "面板点击: 鼠标式无响应 → 回退发 OK 键")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                }
            }
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
        /*
         * 必须让面板视图拿到焦点，否则 Compose 收不到按键（2026-10-04 实测）：
         * 「添加服务器」按钮是 clickable，clickable 只在**节点获得焦点**时
         * 才响应 OK/Enter 键；而按键要先经 Android 视图焦点路由到 ComposeView。
         * 面板挂在 Presentation 窗口里，系统不会自动给焦点。
         */
        cv.isFocusable = true
        cv.isFocusableInTouchMode = true
        cv.requestFocus()
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
        /*
         * 返回键宿主：**必须用面板自己的**，不能直接把 Activity 给进去。
         *
         * 2026-10-04 实测（父亲反馈「按 B 返回变成黑屏」）：
         * 把 Activity 当 OnBackPressedDispatcherOwner 时，面板里的 NavHost
         * 把栈弹空之后会继续落到 Activity 的默认返回行为 → 整个应用退到
         * PICO 桌面，看起来就是黑屏。这里用自带 no-op 兜底的调度器：
         * 面板栈能弹就弹，弹不动就什么都不做。
         */
        val backOwner = PanelBackOwner(activity)
        cv.setViewTreeOnBackPressedDispatcherOwner(backOwner)
        cv.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner) {
                content()
            }
        }
        setContentView(cv)
        window?.decorView?.let { root ->
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.requestFocus()
            root.post { cv.requestFocus() }
        }

        onReady(window!!.decorView)
    }
}

/**
 * 面板窗口的返回栈宿主。
 *
 * 生命周期/ViewModelStore/SavedStateRegistry 都委托给宿主 Activity
 * （Compose 需要它们），但**返回栈是自己的**，且栈底是 no-op：
 * 这样面板里的 NavHost 弹到根之后不会再触发 Activity 的返回（退出应用）。
 */
private class PanelBackOwner(private val activity: ComponentActivity) : OnBackPressedDispatcherOwner {

    /** 兜底：面板栈已空 —— 什么都不做（不退出应用） */
    private val dispatcher = OnBackPressedDispatcher(Runnable { })

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = dispatcher

    /** OnBackPressedDispatcherOwner 同时是 LifecycleOwner，直接用宿主的 */
    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = activity.lifecycle
}
