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
         * 按控件实际尺寸反推（父亲 2026-10-04 定的算法）：面板 1920x1080 下，
         * 详情页按钮约 162x66 像素，是界面上最小的可点控件 —— 分界不能超过它高度的一半，
         * 否则扣扳机时手一飘就被当成拨摇杆。取 30 像素：30 内算点击，超过才算摇杆拖动。
         */
        private const val SLOP_PX = 30f
        private const val SLOP_PX2 = SLOP_PX * SLOP_PX

        /**
         * 摇杆滚动的步长（面板像素）与限速（毫秒）。
         *
         * PICO 摇杆是「合成 按下+拖动+抬起」，一次推可达 800+ 像素。
         * 父亲 2026-10-04 定：摇杆用来滚动列表 —— 所以推住不放要能连续滚（按步长反复发方向键），
         * 但不能一次推就把焦点连飞十几格，因此加 130ms 限速（约每秒 7 格，滚动顺滑）。
         */
        private const val STEP_PX = 40f
        private const val FIRE_INTERVAL_MS = 120L

        /**
         * 指针离面板边缘多近算「贴边」。
         *
         * 摇杆被系统转成指针位移，指针顶到边缘后就没有位移了 —— 这时若还按同方向
         * 继续推，位移恒为 0，方向信息丢失。解法：已判定过方向且指针贴边时，
         * 按**同方向**继续滚（父亲 2026-10-04：光标在下方就滚不动的问题）。
         */
        private const val EDGE_PX = 40f

        /** 方向记忆的有效窗口：这段时间内滚过，贴边无位移时沿用同方向 */
        private const val DIR_MEMORY_MS = 2000L

        /**
         * 「鼠标式点击」之后等多久判定它有没有真的生效。
         *
         * 2026-10-04 父亲实测：指着海报扣扳机没反应。日志里
         * `面板点击: 鼠标式被接住=true`，但界面一点动作都没有 —— 说明
         * 「被接住」这个返回值不可信（悬停事件也会返回 true），不能拿它当
         * 「点击生效」的证据。改成看**界面的真实反应**：导航变了 / 开始播放了
         * 才算生效；否则补一个 OK 键（电视版界面本来就是「焦点 + OK」模型）。
         */
        private const val CLICK_VERIFY_MS = 220L

    }

    /** 面板是否可用（Presentation 已显示、decorView 已就绪） */
    @Volatile
    var ready = false
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PanelPresentation? = null
    private var decor: View? = null

    /** 面板自己的返回栈宿主（Presentation 与本类共用同一个实例） */
    private var backOwner: PanelBackOwner? = null

    /** 点击前的界面动作序号：用来判定鼠标式点击有没有真生效 */
    private var clickSignalBefore = 0L
    private val clickVerify = Runnable { verifyClick() }

    /** 本次按下的锁定位置（抬起时若没拖动，就用它收尾成一次干净的点击） */
    private var downPx = 0f
    private var downPy = 0f

    /** 是否处于按下状态（含拖动），以及是否已越过阈值进入拖动 */
    private var isDown = false
    private var isDragging = false

    /** 本次按下的时间戳（按压画像用） */
    private var downTime = 0L

    /** 上一次发方向键的位置与时间（摇杆滚动：按步长 + 限速反复发） */
    private var stepPx = 0f
    private var stepPy = 0f
    private var lastFireAt = 0L

    /** 本次按下最后发出的方向键（指针贴边后按同方向续滚用） */
    private var lastDirKey = 0

    /**
     * 跨按次的方向记忆。
     *
     * 父亲 2026-10-04：B站里「光标在可滚动区域就能滚」，而我们这边指针一顶到
     * 屏幕最外圈，位移就恒为 0，方向信息彻底丢失（界面留空解决不了 —— 光标位置
     * 由系统控制，照样能顶到最外圈）。所以补一条记忆：如果这次推动贴着边、
     * 一点位移都没有，就沿用刚才那次的方向继续滚，而不是当成点击。
     */
    private var lastDirKeyGlobal = 0
    private var lastDirKeyGlobalAt = 0L

    // ---- 按压画像（实机取证用，判断摇杆与扳机在事件流上的差异）----
    private var pressMoveCount = 0      // 本次按下收到多少 MOVE
    private var pressArrows = 0         // 本次按下发了多少方向键
    private var pressMaxDx = 0f         // 位移绝对值最大值（x）
    private var pressMaxDy = 0f         // 位移绝对值最大值（y）

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

            val owner = backOwner ?: PanelBackOwner(activity).also { backOwner = it }
            val p = PanelPresentation(activity, vd.display, activity, content, owner) { view ->
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
     * 面板像素位移 → 方向键。
     *
     * 符号取反的依据（父亲 2026-10-04 实机反馈「上下左右都反了」）：
     * PICO 把摇杆合成成一次「指针拖动」，而**指针扫动的方向与手上的推动方向相反**
     * （这也解释了他早前说的「光标要在上部才能向下滚」）。所以这里按手感发键：
     * 指针向上扫 = 手感向下推 = 发 DOWN。日志里会打出方向名，实机再校一次即可。
     */
    private fun dirKeyOf(dx: Float, dy: Float): Int {
        if (dx == 0f && dy == 0f) return 0
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (dx > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            if (dy > 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        }
    }

    /** 方向名（日志用，方便一眼看出「发的是哪个方向」） */
    private fun dirName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "右"
        else -> "?"
    }

    /**
     * 点击是否真的生效 —— 用**界面反应**判定，不看事件返回值。
     *
     * 2026-10-04 父亲实测：指着海报扣扳机没反应。日志里
     * `鼠标式被接住=true`（悬停事件也返回 true，这个返回值不说明点击生效），
     * 所以改成看界面自己动没动：导航序号变了 = 鼠标点击已生效，跳过 OK；
     * 没变 = 电视版界面不吃鼠标，补发 OK 键（它就是「焦点 + OK」模型）。
     */
    private fun verifyClick() {
        if (PanelSignals.seq != clickSignalBefore) {
            Log.i(TAG, "面板点击: 鼠标式已生效（界面有动作），跳过 OK")
            return
        }
        Log.i(TAG, "面板点击: 鼠标式无动作 → 补发 OK 键")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** 指针是否贴在面板边缘（贴边后没有位移，需要按同方向续滚） */
    private fun isNearEdge(px: Float, py: Float): Boolean =
        px <= EDGE_PX || py <= EDGE_PX || px >= W - EDGE_PX || py >= H - EDGE_PX

    /**
     * 返回上一层（手柄 B 键 / 返回键）。
     *
     * 2026-10-04 实机定位「按 B 黑屏」的真正原因：以前是往面板窗口里派发
     * 一个 BACK 按键，而面板窗口是 `Presentation`（本质是 Dialog），
     * Dialog 对没人消费的 BACK 的默认行为就是**把自己关掉** ——
     * 于是面板消失，只剩黑底。现在改成直接调面板自己的返回栈：
     * 能弹就弹一层，弹不动就什么都不做，绝不关窗口。
     */
    fun back() {
        val owner = backOwner ?: return
        Log.i(TAG, "面板返回: 交给面板导航栈")
        owner.onBackPressedDispatcher.onBackPressed()
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

        // 1) 悬停到该位置（建立 hover 状态）
        val hover = mouseEvent(now, now, MotionEvent.ACTION_HOVER_MOVE, px, py, 0)
        try {
            handled = v.dispatchGenericMotionEvent(hover) || handled
        } catch (t: Throwable) {
            Log.e(TAG, "悬停派发失败: ${t.message}")
        } finally {
            hover.recycle()
        }

        // 2) 主键按下 → 抬起（Compose 按鼠标点击处理，并把焦点落到被点元素）
        val down = mouseEvent(now, now, MotionEvent.ACTION_DOWN, px, py, MotionEvent.BUTTON_PRIMARY)
        try {
            handled = v.dispatchTouchEvent(down) || handled
        } catch (t: Throwable) {
            Log.e(TAG, "鼠标按下派发失败: ${t.message}")
        } finally {
            down.recycle()
        }

        val up = mouseEvent(now, now + 60, MotionEvent.ACTION_UP, px, py, 0)
        try {
            handled = v.dispatchTouchEvent(up) || handled
        } catch (t: Throwable) {
            Log.e(TAG, "鼠标抬起派发失败: ${t.message}")
        } finally {
            up.recycle()
        }
        return handled
    }

    /**
     * 造一个鼠标事件。
     *
     * 必须用带 `buttonState` 的重载：`MotionEvent.setButtonState()` 是隐藏 API，
     * Kotlin 侧 `buttonState` 只读（写成 `ev.buttonState = 1` 会编译不过）。
     */
    private fun mouseEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        px: Float,
        py: Float,
        buttonState: Int,
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                pressure = 1f
                size = 1f
            },
        )
        @Suppress("DEPRECATION")
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0,
        )
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
                downTime = now
                downPx = px
                downPy = py
                isDown = true
                isDragging = false
                lastFireAt = now
                lastDirKey = 0
                pressMoveCount = 0
                pressArrows = 0
                pressMaxDx = 0f
                pressMaxDy = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return
                val dx = px - downPx
                val dy = py - downPy
                pressMoveCount++
                if (kotlin.math.abs(dx) > pressMaxDx) pressMaxDx = kotlin.math.abs(dx)
                if (kotlin.math.abs(dy) > pressMaxDy) pressMaxDy = kotlin.math.abs(dy)
                if (!isDragging) {
                    if (dx * dx + dy * dy < SLOP_PX2) return       // 手抖：不算摇杆
                    isDragging = true
                    /*
                     * 识别成摇杆的**第一步立刻走**（父亲 2026-10-04 实测）：
                     * 原来这一步只做标记、不发键，小推一下（几十像素）就完全没反应，
                     * 手感是「光标明明在画面里，推了却不动」。
                     * 方向就用「按下点 → 当前点」，这是摇杆这一次推动的主方向。
                     */
                    stepPx = px
                    stepPy = py
                    lastFireAt = now
                    val dir = dirKeyOf(dx, dy)
                    if (dir != 0) {
                        lastDirKey = dir
                        lastDirKeyGlobal = dir
                        lastDirKeyGlobalAt = now
                        pressArrows++
                        Log.i(
                            TAG,
                            "摇杆起始步 位移=(${dx.toInt()},${dy.toInt()}) → " +
                                "方向=${dirName(dir)} key=$dir",
                        )
                        key(dir)
                    }
                    return
                }
                // 摇杆滚动：从上一次发键的位置算位移，够一格且过了限速就再发一格
                val sx = px - stepPx
                val sy = py - stepPy
                val movedEnough =
                    kotlin.math.abs(sx) >= STEP_PX || kotlin.math.abs(sy) >= STEP_PX

                if (movedEnough) {
                    if (now - lastFireAt < FIRE_INTERVAL_MS) return
                    stepPx = px
                    stepPy = py
                    lastFireAt = now
                    val code = dirKeyOf(sx, sy)
                    lastDirKey = code
                    lastDirKeyGlobal = code
                    lastDirKeyGlobalAt = now
                    pressArrows++
                    Log.i(
                        TAG,
                        "摇杆发键 指针位移=(${sx.toInt()},${sy.toInt()}) → " +
                            "方向=${dirName(code)} key=$code（19上/20下/21左/22右）",
                    )
                    key(code)
                    return
                }

                // 没有新位移：多半是指针被顶到面板边缘了。
                // 已经判定过方向 + 指针贴边 + 过了限速 → 按同方向继续滚，
                // 否则「光标在下半屏就滚不动」。
                if (lastDirKey != 0 && isNearEdge(px, py) && now - lastFireAt >= FIRE_INTERVAL_MS) {
                    lastFireAt = now
                    lastDirKeyGlobal = lastDirKey
                    lastDirKeyGlobalAt = now
                    Log.i(TAG, "摇杆续滚（指针贴边）: key=$lastDirKey")
                    key(lastDirKey)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDown) return
                isDown = false
                val dragged = isDragging
                isDragging = false
                Log.i(
                    TAG,
                    "按压画像: MOVE=${pressMoveCount} 方向键=${pressArrows} " +
                        "最大位移=(${pressMaxDx.toInt()},${pressMaxDy.toInt()}) " +
                        "时长=${now - downTime}ms 判定=${if (dragged) "摇杆" else "点击"} " +
                        "起点=(${downPx.toInt()},${downPy.toInt()})",
                )
                if (dragged) return                                // 摇杆推动：只发方向键，不发 OK

                /*
                 * 贴边且这次一点位移都没有：方向信息丢了。
                 * 若最近 2 秒内刚滚过，就沿用那个方向继续滚 ——
                 * 对应父亲说的「B站里光标在可滚动区域就能滚」的体感。
                 * 代价：贴边位置刚滚完马上想点按钮时，可能被当成继续滚动
                 * （已打日志，实测若误判再收紧窗口或去掉）。
                 */
                val recentDir = lastDirKeyGlobal != 0 &&
                    now - lastDirKeyGlobalAt < DIR_MEMORY_MS
                val noMove = pressMaxDx < SLOP_PX && pressMaxDy < SLOP_PX
                if (noMove && isNearEdge(px, py) && recentDir) {
                    Log.i(TAG, "贴边沿用方向续滚: key=$lastDirKeyGlobal（无位移，不当点击）")
                    lastDirKeyGlobalAt = now
                    key(lastDirKeyGlobal)
                    return
                }
                /*
                 * 一次点击 = 鼠标式点击 + 「真的没反应就补 OK 键」。
                 *
                 * 双通道的原因（2026-10-04 实机两轮反馈）：
                 *   - 指针式点击对着自绘 `clickable` 组件有效（登录页、添加服务器按钮）
                 *   - 电视版的海报卡是 TV 库的 Surface，实测吃鼠标事件但不动作
                 * 所以先发鼠标式（保留「指哪儿点哪儿」），等 220ms 看界面有没有反应，
                 * 没反应再补 OK —— OK 打在**当前焦点**上，而焦点已被悬停跟随指针移过来了。
                 */
                /*
                 * VR 语义（父亲 2026-10-04 定案）：扣扳机时才去算「光点落在哪个控件上」，
                 * 命中就把焦点移过去并触发它；落在空白处则什么都不做。
                 * 查表用的是**抬起时的光点位置**（他瞄哪儿就是哪儿）。
                 */
                val target = ClickTargets.findAt(px, py)
                if (target != null) {
                    Log.i(TAG, "落点命中控件: $target → 聚焦并触发")
                    target.focus?.invoke()
                    // 焦点事务要下一帧才生效，动作推到下一帧，避免"没聚焦就被点"
                    decor?.post {
                        runCatching { target.activate() }
                            .onFailure { Log.e(TAG, "控件触发失败: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    return
                }
                if (ClickTargets.size() > 0) {
                    // 这一屏已经接入坐标表，说明就是点在空白处 → 不动作
                    Log.i(TAG, "落点未命中任何控件（空白处）→ 不动作 位置=(${px.toInt()},${py.toInt()})")
                    return
                }
                // 这一屏还没接入坐标表：退回旧的「鼠标式点击 + 验证」通道，保证其它屏可用
                Log.i(TAG, "该屏未接入坐标表 → 退回鼠标式点击")
                clickSignalBefore = PanelSignals.seq
                val mouseHit = mouseClick(px, py)
                Log.i(TAG, "面板点击: 鼠标式被接住=$mouseHit 位置 (${px.toInt()},${py.toInt()})")
                decor?.removeCallbacks(clickVerify)
                decor?.postDelayed(clickVerify, CLICK_VERIFY_MS)
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
        ClickTargets.clear()
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
    private val backOwner: PanelBackOwner,
    private val onReady: (View) -> Unit,
) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*
         * 面板不是「弹窗」，绝不能按返回就被关掉（2026-10-04 父亲实测「按 B 黑屏」）：
         * Presentation 继承 Dialog，Dialog 对没人消费的返回键默认行为是关闭自己
         * —— 面板一关，VR 里就只剩黑底。这里两道保险：
         *   1) 不可取消（isCancelable=false）
         *   2) 返回键转交面板自己的导航栈（弹一层；栈空则什么都不做）
         */
        setCancelable(false)
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
         * 2026-10-04 实测（父亲反馈「按 B 返回变成黑屏」）：面板窗口是
         * Presentation（Dialog），没人消费的 BACK 会让 Dialog 关掉自己 ——
         * 面板一关就只剩黑底。所以：窗口不可取消 + BACK 一律转交面板导航栈
         * （见 [PanelBackOwner] 与 [PanelLayer.back]）。
         */
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

    /**
     * 返回键兜底：**绝不关面板**。
     *
     * 面板窗口是 Dialog，默认行为是把自己关掉（父亲实测就是「黑屏」）。
     * 这里改成转交面板导航栈：能弹一层就弹一层，栈空什么都不做。
     */
    override fun onBackPressed() {
        backOwner.onBackPressedDispatcher.onBackPressed()
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

/**
 * 面板界面的「动作信号」计数器。
 *
 * 2026-10-04 为什么需要它：判断一次点击有没有生效，**不能信事件返回值**
 * （鼠标点击返回 true 只说明事件被视图接了，不代表界面有动作 ——
 * 悬停事件也会返回 true，就是它把上一轮误导了）。这里让界面在真正
 * 发生动作时（导航切屏 / 开始播放）把计数器加一，点击后对比计数：
 * 变了 = 鼠标式点击生效；没变 = 补发 OK 键（电视版界面是「焦点 + OK」模型）。
 */
object PanelSignals {
    @Volatile
    var seq: Long = 0
        private set

    fun bump() {
        seq++
    }
}
