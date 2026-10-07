package com.xxxx.emby_vr.panel

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Choreographer
import android.view.WindowManager
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
    /** 面板像素尺寸：默认 1920x1080（电视版布局）；控制条面板走 1920x270 这种矮条 */
    private val panelW: Int = W,
    private val panelH: Int = H,
    /** 虚拟显示器名字（日志与 dumpsys 里区分主面板 / 控制条） */
    private val name: String = "b0bemby-panel",
    /** 是否把"面板已就绪"告诉 VR 侧：主面板 true，控制条 false（各管各的） */
    private val activatesVrPanel: Boolean = true,
    /**
     * 直通点击（2026-10-05）：控制条这类**纯 Compose 界面**用 true。
     *
     * 背景：主面板的点击先查 ClickTargets 坐标表（电视版海报墙那套），表里一旦有条目，
     * 查不中就直接返回、不再往下派发真实点击。控制条从没登记过这张表，于是表现成
     * 「按钮看得到、扣扳机点不动」。开这个开关就跳过查表，直接派发鼠标式点击。
     */
    private val directClick: Boolean = false,
    /*
     * 内容必须放在**最后一个参数**：Kotlin 的尾随 lambda 只绑最后一个参数，
     * 放到中间会让 `PanelLayer(this) { … }` 这种写法去匹配别的参数（run 112 编译失败）。
     */
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

        /**
         * 近失兜底距离（面板像素，2026-10-05）。
         *
         * 点离控件只差这么多像素时，仍按命中处理。用于吸收卡片聚焦放大动画
         * 与瞄准抖动造成的几像素偏差；1920 宽下 16px ≈ 0.8%，不会误点真空白。
         */
        private const val NEAR_MISS_PX = 16f

        /**
         * 摇杆滚动的步长（滚轮格数，父亲 2026-10-05 定案：摇杆=滚轮，不碰焦点）。
         *
         * Compose 把一格滚轮折合约 64dp（面板 240dpi ≈ 128px），一格滚一行多一点；
         * 配合下面 120ms 的限速，连续推的感觉约每秒 4 格。手感不对就调这一个数。
         */
        private const val SCROLL_NOTCH = 0.16f

        /**
         * VR 摇杆的死区与速度档（格/秒，2026-10-05）。
         *
         * 摇杆量过 [VR_STICK_DEAD] 才开始滚，推到顶是 [VR_RATE_MAX]；
         * 中间按 1.3 次方曲线过渡（轻推好控、猛推够快）。
         * 速度不准就调这两个数 —— 上限落在既有 RATE_MAX(22) 以内，避免被二次钳制。
         */
        private const val VR_STICK_DEAD = 0.55f
        private const val VR_RATE_MIN = 3f
        private const val VR_RATE_MAX = 12f

        /*
         * 滚动节奏（2026-10-05 定稿）：不再用固定节拍定时器，改成**每渲染帧**回调一次，
         * 按这一帧实际时长算位移（见 [frameTick]）。历史上试过 160ms → 60ms → 33ms，
         * 与刷新率对不齐始终"一跳一跳"。
         */

        /**
         * 「推住不动」的判定：光点安静超过这么久，就认为摇杆被压在一个方向上没动。
         * 这时滚动速度按**猛推速度**（父亲 2026-10-05 定：推住不动松手没有滑行，
         * 但按住期间要快，按猛推的速度滚）。
         */
        private const val HOLD_QUIET_MS = 120L

        /**
         * 兜底上限：万一某次「抬起」彻底丢失（历史上真发生过），不让它无限滚下去。
         * 正常路径靠抬起事件停，不依赖这个数。
         */
        private const val HOLD_SAFETY_MS = 15000L

        /**
         * 滚动速度（格/秒）：轻推慢滚、猛推快滚（父亲 2026-10-05 定）。
         *
         * 摇杆不给力度值，只能拿**光点移动速度**换算：[SPEED_TO_RATE]。
         * 设上下限，避免"几乎不动"和"飞出去"。
         */
        private const val RATE_MIN = 1.2f
        private const val RATE_MAX = 22f

        /**
         * 光点速度（像素/秒）→ 滚动速度（格/秒）：每 120 像素/秒记 1 格/秒。
         *
         * 2026-10-05 父亲要「最大速度再快一点」：原来 1/220 时，要 2200px/s 才到
         * 10 格/秒，实测拨动多在 800~3500px/s，很难摸到上限；改成 1/120 后
         * 常见拨动就能进入 7~18 格/秒。
         */
        private const val SPEED_TO_RATE = 1f / 90f

        /**
         * 惯性：松手后按松手瞬间的速度继续滑，**线性减速到 0，总时长 1 秒**
         * （父亲 2026-10-05 定：不要急停，减速停 1 秒就够）。
         * 松手时光点是静止的（推住不动再松手）→ 没速度 → 不滑行。
         */
        private const val INERTIA_MS = 1000L

        /** 低于这个速度就不滑了（格/秒） */
        private const val INERTIA_MIN_RATE = 0.8f

        /**
         * 轮播区（首页大海报）步进限速：横向拨动落在轮播区里时不滚列表、改切一张，
         * 最快 200ms 一张（按住不放就连着切）。
         */
        private const val ZONE_STEP_MS = 200L

    }

    /**
     * 触发一个控件的动作（扣扳机命中后统一走这里）。
     *
     * 先移焦点再触发：焦点事务要下一帧才生效，动作推到下一帧，
     * 避免"没聚焦就被点"（电视版控件按焦点态渲染）。
     */
    private fun fireTarget(target: ClickTargets.Target) {
        Log.i(TAG, "落点命中控件: $target → 聚焦并触发")
        target.focus?.invoke()
        decor?.post {
            runCatching { target.activate() }
                .onFailure { Log.e(TAG, "控件触发失败: ${it.javaClass.simpleName}: ${it.message}") }
            Log.i(TAG, "控件动作已调用: ${target.label}")
        }
    }

    /**
     * 摇杆滚动：往面板派发一次**鼠标滚轮**事件（父亲 2026-10-05 定案）。
     *
     * 滚的是**光点底下的那个可滚容器** —— Compose 的 LazyRow / LazyColumn
     * 认 ACTION_SCROLL，并且是按坐标做命中测试的，所以
     * 「光点在第三排就滚第三排、在详情页就滚详情页」天然成立，
     * 而且**完全不碰焦点**（焦点只在扣扳机那一刻按光点算）。
     *
     * @param dx,dy 这次位移（面板像素，向下/向右为正）
     */
    private fun scrollAt(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        why: String,
        notch: Float = SCROLL_NOTCH,
    ) {
        // 取移动量更轴的那一向：斜推时只滚主方向，免得两轴一起乱滚
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        // 记住这一步的方向与落点：节拍器按它继续滚
        lastScrollX = x
        lastScrollY = y
        lastScrollDx = dx
        lastScrollDy = dy
        // 派发点用锚点（他按下时看着的那一片），不是光点当前可能已顶到边缘的位置
        val (ax, ay) = scrollAnchorPoint()
        val now = SystemClock.uptimeMillis()

        /*
         * 轮播区（首页大海报）：它不是可滚列表，是自己按索引切换的轮播，
         * 滚轮事件落上去它不认 —— 所以左右拨没反应（父亲 2026-10-05 实测）。
         * 这里分流：横向拨动且锚点落在轮播区里 → 直接调它的「上一张 / 下一张」。
         */
        if (horizontal && dx != 0f) {
            val zone = ClickTargets.zoneAt(ax, ay)
            if (zone != null) {
                val dir = if (dx > 0) 1f else -1f
                /*
                 * 边沿触发（父亲 2026-10-07）：同方向推着不放只翻一张；
                 * 回中（见 vrStick 的回中分支）或反向推，才重新生效。
                 */
                if (zoneStepArmed || dir != zoneStepDir) {
                    zoneStepArmed = false
                    zoneStepDir = dir
                    lastZoneStepAt = now
                    Log.i(
                        TAG,
                        "$why：横向拨动落在轮播区(${zone.label}) → 切${if (dx > 0) "下一张" else "上一张"}",
                    )
                    zone.onStep(if (dx > 0) 1 else -1)
                }
                return
            }
        }

        val h = if (horizontal) (if (dx > 0) notch else -notch) else 0f
        val v = if (!horizontal) (if (dy > 0) -notch else notch) else 0f
        // 注意：MotionEvent.setAxisValue 是隐藏 API（编译期 Unresolved），
        // 滚轮量必须写在 PointerCoords 上再 obtain。
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = ax
                this.y = ay
                pressure = 1f
                size = 1f
                if (h != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, h)
                if (v != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            },
        )
        @Suppress("DEPRECATION")
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        val hit = decor?.dispatchGenericMotionEvent(ev) ?: false
        ev.recycle()
        Log.i(
            TAG,
            "$why：滚轮 光点=(${x.toInt()},${y.toInt()}) 派发点=(${ax.toInt()},${ay.toInt()}) " +
                "位移=(${dx.toInt()},${dy.toInt()}) Δ=($h,$v) 被接住=$hit",
        )
        /*
         * 兜底：万一这条通道整条不被接住（界面里没有可滚容器 / 系统不认），
         * 退化为方向键 —— 宁可滚得不够准，也不能"推了完全不动"。
         * 滚轮正常工作的情况下这里不会执行，焦点依旧不受影响。
         */
        if (!hit) {
            val code = dirKeyOf(dx, dy)
            if (code != 0) {
                Log.i(TAG, "$why：滚轮没被接住 → 退化为方向键 ${dirName(code)}")
                key(code)
            }
        }
    }

    /** 控制条实例是否就绪（只有 activatesVrPanel=false 的控制条会用到） */
    @Volatile
    var osdReady = false
        private set

    /** 面板是否可用（Presentation 已显示、decorView 已就绪） */
    @Volatile
    var ready = false
        private set

    /**
     * 面板当前是否处于「按下」状态。
     *
     * 用途（父亲 2026-10-05 实测根因）：摇杆一次拨动的落点常会滑出面板
     * （实测抬起坐标 y=-131），而 `panelPixelAt` 对面板外的点返回 null，
     * 于是「抬起」根本没被转发进面板 → 面板一直以为还按着 →
     * 松手后还在滚。MainActivity 靠这个标志决定「落点在面板外也要转发抬起」。
     */
    val pressActive: Boolean get() = isDown

    /** 最近一次派发进面板的面板像素坐标（抬起落在面板外时用它收尾） */
    var lastPanelX = 0f
        private set
    var lastPanelY = 0f
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PanelPresentation? = null
    private var decor: View? = null

    /** 面板自己的返回栈宿主（Presentation 与本类共用同一个实例） */
    private var backOwner: PanelBackOwner? = null

    /** 摇杆滚动的最近一次方向/落点，供"扳住不动"时持续滚 */
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var lastScrollDx = 0f
    private var lastScrollDy = 0f

    /** 光点最后一次移动的时间（"推住不动"判定的唯一依据） */
    private var lastMoveAt = 0L

    /** 上一次移动采样的位置：用来算光点速度（→ 滚动快慢） */
    private var lastMovePx = 0f
    private var lastMovePy = 0f

    /** 光点速度（面板像素/秒，指数平滑）—— 摇杆没有力度值，只能用它估 */
    private var pointerSpeed = 0f

    /** VR 摇杆当前速度（格/秒）；回中时用它作为惯性初速 */
    private var vrStickRate = 0f

    /** 惯性：松手瞬间的滚动速度（格/秒）、起始时刻、方向 */
    private var inertiaRate = 0f
    private var inertiaStartAt = 0L
    private var inertiaDx = 0f
    private var inertiaDy = 0f

    /** 轮播区上次步进的时刻（限速用） */
    private var lastZoneStepAt = 0L

    /**
     * 轮播区（首页大海报）的步进改成**边沿触发**（父亲 2026-10-07）。
     *
     * 原来按 200ms 节流：只要摇杆推着，就每 0.2 秒翻一张 —— 推到顶时看着就是连续跳。
     * 现在只认"推没推、往哪边推"：同方向必须**回中或反向**之后才能再翻一张，
     * 于是一次推 = 一张，与播放页快进快退的"武装"机制同款。
     */
    private var zoneStepArmed = true
    private var zoneStepDir = 0f

    /**
     * 滚轮事件的**派发锚点** = 这次摇杆推动的起点（父亲 2026-10-05 实测）。
     *
     * 为什么不能打在光点当前位置：推上滚时，光点会被一路顶到面板最上沿
     * （实测 y=1），那里是顶部状态栏那一排、不是可滚容器 —— 滚轮事件落在那里
     * 就没人接，表现成「上滚只滚一下就停」（下滚时锚点在底部内容区里，所以正常）。
     * 锚点固定用按下时的位置（用户当时看着的那一片），并把极边缘往内收一点。
     */
    private var scrollAnchorX = -1f
    private var scrollAnchorY = -1f

    /**
     * 滚动驱动：**跟着渲染帧走**（父亲 2026-10-05 定案）。
     *
     * 为什么不用定时器：定时器（原来 60ms，约 30 次/秒）和面板刷新率对不上，
     * 每帧分到的位移不均匀，观感是「一跳一跳」。改成每渲染帧回调一次，
     * 按**这一帧实际过了多少毫秒**算该滚多少（位移量切碎到每帧十几像素），
     * 天然与刷新率对齐，最平滑。
     *
     * 每帧只做两件事：① 惯性阶段按时间线性减速；② 按住阶段按速度档位滚。
     */
    private var frameScheduled = false
    private var lastFrameAt = 0L

    private val choreographer: Choreographer
        get() = Choreographer.getInstance()

    private val frameTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            // 这一帧过了多久（首帧给 16ms；长卡顿钳到 40ms，避免一帧挪一大段）。
            // 注意：帧时间戳只用来算"间隔"，惯性/安静的判定统一用 uptimeMillis（同一基准，
            // 否则两套时钟混用会让惯性一上来就判成超时结束）。
            val dtMs = if (lastFrameAt > 0L) {
                ((frameTimeNanos - lastFrameAt) / 1_000_000L).coerceIn(1L, 40L)
            } else {
                16L
            }
            lastFrameAt = frameTimeNanos
            val now = SystemClock.uptimeMillis()

            // ① 惯性阶段（松手之后）：按松手初速线性减速，1 秒内停
            if (inertiaRate > 0f) {
                val elapsed = (now - inertiaStartAt).coerceAtLeast(0L)
                val rate = inertiaRate * (1f - elapsed.toFloat() / INERTIA_MS.toFloat())
                if (rate < INERTIA_MIN_RATE) {
                    inertiaRate = 0f
                    Log.i(TAG, "惯性结束：滑行 ${elapsed}ms")
                    return
                }
                scrollAt(
                    lastScrollX, lastScrollY, inertiaDx, inertiaDy, "惯性滑行",
                    rate * dtMs / 1000f,
                )
                scheduleFrame()
                return
            }

            // ② 按住阶段：轻推慢滚、猛推快滚；推住不动按猛推速度继续滚
            if (!isDown || !isDragging) return
            val quiet = SystemClock.uptimeMillis() - lastMoveAt
            if (quiet > HOLD_SAFETY_MS) {
                Log.i(TAG, "摇杆滚动停止：光点静了 ${quiet}ms（抬起事件疑似丢失，兜底停）")
                return
            }
            val rate = if (quiet > HOLD_QUIET_MS) RATE_MAX else rateOf(pointerSpeed)
            scrollAt(
                lastScrollX, lastScrollY, lastScrollDx, lastScrollDy, "摇杆保持",
                rate * dtMs / 1000f,
            )
            scheduleFrame()
        }
    }

    /** 光点速度（像素/秒）→ 滚动速度（格/秒），带上下限 */
    private fun rateOf(speedPx: Float): Float =
        (speedPx * SPEED_TO_RATE).coerceIn(RATE_MIN, RATE_MAX)

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        runCatching { choreographer.postFrameCallback(frameTick) }
    }

    private fun stopFrames() {
        frameScheduled = false
        lastFrameAt = 0L
        runCatching { choreographer.removeFrameCallback(frameTick) }
    }

    /**
     * 滚动派发点：这次推动的起点，并把极边缘往内容区里收一点
     * （顶部那一条是状态栏、最底部是安全区，都不属于可滚容器）。
     */
    private fun scrollAnchorPoint(): Pair<Float, Float> {
        val w = (decor?.width ?: 0).toFloat()
        val h = (decor?.height ?: 0).toFloat()
        var x = if (scrollAnchorX >= 0f) scrollAnchorX else downPx
        var y = if (scrollAnchorY >= 0f) scrollAnchorY else downPy
        if (w > 0f) x = x.coerceIn(w * 0.10f, w * 0.90f)
        if (h > 0f) y = y.coerceIn(h * 0.20f, h * 0.80f)
        return x to y
    }

    /** 这一步主要是横向还是纵向 */
    private fun scrollAxisIsHorizontal(dx: Float, dy: Float): Boolean =
        kotlin.math.abs(dx) > kotlin.math.abs(dy)

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
            st.setDefaultBufferSize(panelW, panelH)
            val surface = Surface(st)
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            /*
             * OWN_CONTENT_ONLY：这块虚拟显示器只显示本应用自己的窗口，
             * 不接收其他应用投屏（PUBLIC 会让别人也能往里投）。
             */
            val vd = dm.createVirtualDisplay(
                name,
                panelW,
                panelH,
                DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = vd

            val owner = backOwner ?: PanelBackOwner(activity).also { backOwner = it }
            val p = PanelPresentation(
                activity, vd.display, activity, content, owner,
                !activatesVrPanel,
            ) { view ->
                decor = view
                ready = true
                /*
                 * 界面真的开始画了 → 通知 VR 侧可以贴面板纹理了
                 * （2026-10-05：纹理建在 VR 上下文里，激活状态也归它管）。
                 */
                if (activatesVrPanel) com.xxxx.emby_vr.vr.VrNative.setPanelActive(true)
                osdReady = true
                Log.i(TAG, "面板层就绪: ${panelW}x$panelH dpi=$DPI displayId=${vd.display.displayId} name=$name")
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
     * ---- VR 模式（OpenXR 光柱）输入口，2026-10-05 起 ----
     *
     * VR 模式下不再有系统合成的触摸流：光柱坐标、扳机、摇杆、B 键都由原生层
     * 直接推上来（VrNative.InputSink）。因此这里给三条干净入口，不走
     * dispatch() 里那套「从合成触摸反推摇杆」的启发式：
     *
     *   vrPointer —— 光柱指到面板上的位置（面板像素）
     *   vrClick   —— 扣扳机：在光柱位置做一次点击（等同 tap）
     *   vrScroll  —— 拨摇杆：滚光柱底下的那一排（走鼠标滚轮，不碰焦点）
     */
    fun vrPointer(px: Float, py: Float) {
        lastPanelX = px
        lastPanelY = py
        scrollAnchorX = px
        scrollAnchorY = py
    }

    /**
     * 扣扳机 = 在光柱位置点一下（焦点先落到光点所在控件，再看有没有动作）。
     *
     * 点之前先**把正在滚的停住**（父亲 2026-10-06 晚实测：扣扳机去点某张海报的那一瞬间，
     * 海报还在上下左右滚，想点的那张会跑掉）。扣扳机时手会用力，摇杆常被带偏一点，
     * 那份摇杆量正推着海报滚 —— 所以这里先刹车，再点。
     */
    fun vrClick(px: Float, py: Float) {
        stopVrScroll()
        vrPointer(px, py)
        tap(px, py)
    }

    /**
     * 立刻停掉光柱滚动：摇杆推着的那份速度、惯性滑行、按压态全清零。
     *
     * 与原生侧的「按住扳机时不再推摇杆」配套：原生停发新的摇杆量，这里停掉
     * 已经攒下的那份滚动，两头都掐断，扣扳机那一瞬间海报一定不动。
     */
    fun stopVrScroll() {
        vrStickRate = 0f
        inertiaRate = 0f
        pointerSpeed = 0f
        isDown = false
        isDragging = false
    }

    /*
     * ---- 直通按压流（控制条上扣扳机拖进度条，2026-10-06 下午）----
     *
     * 扳机按住 → 按下；按住期间光点移动 → 拖动；松扳机 → 抬起。走鼠标式事件，
     * 但**不走** dispatch() 里那套「按压反推摇杆/焦点」的启发式：控制条是纯 Compose
     * 界面，进度条要吃到完整的按下 → 移动 → 抬起序列。
     *
     * 按钮照样点得动：Compose 的 clickable 在「按下 → 原地抬起」时触发；
     * 按住拖走了就不触发，顺带挡掉误触。
     */
    private var vrPressActive = false

    /** 原生推上来的控制条指针（带扳机态） */
    fun vrPointerPressed(px: Float, py: Float, pressed: Boolean) {
        lastPanelX = px
        lastPanelY = py
        scrollAnchorX = px
        scrollAnchorY = py
        if (pressed) {
            if (vrPressActive) {
                sendPress(MotionEvent.ACTION_MOVE, px, py)
            } else {
                downTime = SystemClock.uptimeMillis()
                vrPressActive = true
                sendPress(MotionEvent.ACTION_DOWN, px, py)
            }
        } else if (vrPressActive) {
            vrPressActive = false
            sendPress(MotionEvent.ACTION_UP, px, py)
        }
    }

    private fun sendPress(action: Int, px: Float, py: Float) {
        val v = decor ?: return
        val ev = mouseEvent(
            downTime, SystemClock.uptimeMillis(), action, px, py,
            if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_PRIMARY,
        )
        try {
            v.dispatchTouchEvent(ev)
        } catch (t: Throwable) {
            Log.e(TAG, "控制条按压派发失败: ${t.message}")
        } finally {
            ev.recycle()
        }
    }

    /** 拨摇杆 = 滚光柱底下那一排；dx/dy 为面板像素位移（向下/向右为正） */
    fun vrScroll(px: Float, py: Float, dx: Float, dy: Float) {
        vrPointer(px, py)
        scrollAt(px, py, dx, dy, "VR 摇杆")
    }

    /**
     * VR 摇杆（状态式，2026-10-05 父亲定案：要平滑、要惯性减速后停住）。
     *
     * 原生层按 30Hz 送当前摇杆量，回中时补一帧 (0,0)；这里把它喂进**已有的**
     * 逐帧滚动机制（[frameTick]，与 2D 面板模式同一套，父亲 2026-10-05 验收过：
     * 轻推慢滚、猛推快滚、松手线性减速 1 秒内停）：
     *
     *   推着 → 每帧按当前速度派发一次滚轮（速度由摇杆量换算，见 [VR_RATE_MIN]/[VR_RATE_MAX]）
     *   回中 → 用最后一次的速度进入惯性滑行，线性减速到停
     *
     * 复用既有机制的好处：手感与 2D 面板模式一致，不用重新调一套参数。
     *
     * @param sx,sy 摇杆量：x 右为正、**y 上为正**（OpenXR 约定），面板坐标 y 向下为正，故取反。
     */
    fun vrStick(px: Float, py: Float, sx: Float, sy: Float) {
        vrPointer(px, py)
        val ax = kotlin.math.abs(sx)
        val ay = kotlin.math.abs(sy)
        val mag = if (ax > ay) ax else ay
        val now = SystemClock.uptimeMillis()

        if (mag < VR_STICK_DEAD) {
            // 回中：轮播区的步进重新"武装"（父亲 2026-10-07：回中后才能再翻下一张）
            zoneStepArmed = true
            // 回中：进入惯性滑行（速度取最后一次推着的速度）
            if (isDown || isDragging) {
                isDown = false
                isDragging = false
                if (vrStickRate > 0f) {
                    inertiaDx = lastScrollDx
                    inertiaDy = lastScrollDy
                    inertiaRate = vrStickRate
                    inertiaStartAt = now
                    scheduleFrame()
                    Log.i(TAG, "VR 摇杆回中：惯性滑行 速度=${"%.1f".format(vrStickRate)}格/秒 方向=(${inertiaDx.toInt()}, ${inertiaDy.toInt()})")
                }
                vrStickRate = 0f
            }
            return
        }

        // 主方向：横拨滚横向、竖拨滚竖向（斜推只取更轴的一向，免得两轴一起乱滚）
        val horizontal = ax > ay
        lastScrollDx = if (horizontal) (if (sx > 0f) 1f else -1f) else 0f
        lastScrollDy = if (horizontal) 0f else (if (sy > 0f) -1f else 1f)
        lastScrollX = px
        lastScrollY = py

        // 摇杆量 → 速度（格/秒）：过死区后线性上升，轻微曲线让轻推更细腻
        val t = ((mag - VR_STICK_DEAD) / (1f - VR_STICK_DEAD)).coerceIn(0f, 1f)
        val curved = Math.pow(t.toDouble(), 1.3).toFloat()
        vrStickRate = VR_RATE_MIN + (VR_RATE_MAX - VR_RATE_MIN) * curved

        // 复用既有的「速度(px/s) → 格/秒」换算：反推一个等价的指针速度喂给逐帧回调
        pointerSpeed = vrStickRate / SPEED_TO_RATE
        isDown = true
        isDragging = true
        lastMoveAt = now
        scheduleFrame()
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
        lastPanelX = px
        lastPanelY = py
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now
                downPx = px
                downPy = py
                isDown = true
                isDragging = false
                lastFireAt = now
                lastMoveAt = now
                lastMovePx = px
                lastMovePy = py
                pointerSpeed = 0f
                inertiaRate = 0f
                lastDirKey = 0
                pressMoveCount = 0
                pressArrows = 0
                pressMaxDx = 0f
                pressMaxDy = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return
                // 光点速度（指数平滑）：摇杆没有力度值，轻推/猛推只能靠它区分
                val dtMs = (now - lastMoveAt).coerceAtLeast(1L)
                val inst = kotlin.math.hypot(
                    (px - lastMovePx).toDouble(),
                    (py - lastMovePy).toDouble(),
                ).toFloat() / dtMs * 1000f
                pointerSpeed = if (pointerSpeed <= 0f) inst else pointerSpeed * 0.6f + inst * 0.4f
                lastMovePx = px
                lastMovePy = py
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
                    pressArrows++
                    /*
                     * 摇杆滚动 = 往面板派发「鼠标滚轮」事件（父亲 2026-10-05 定案）。
                     *
                     * 规则：**摇杆一律不碰焦点**，滚的是光点底下那个可滚容器；
                     * 焦点只在扣扳机那一刻按光点位置计算。
                     * （原来发方向键：方向键打在当前焦点上，必然出现
                     *  「要么不滚、要么滚错了一排」。）
                     */
                    lastMoveAt = now
                    scrollAnchorX = px
                    scrollAnchorY = py
                    scrollAt(px, py, dx, dy, "摇杆起始步", rateOf(pointerSpeed) * 0.03f)
                    scheduleFrame()
                    return
                }
                // 摇杆滚动：从上一次发键的位置算位移，够一格且过了限速就再发一格
                val sx = px - stepPx
                val sy = py - stepPy
                val movedEnough =
                    kotlin.math.abs(sx) >= STEP_PX || kotlin.math.abs(sy) >= STEP_PX

                if (movedEnough) {
                    stepPx = px
                    stepPy = py
                    lastMoveAt = now
                    pressArrows++
                    // 同向继续推：只刷新方向与落点，滚动交给节拍器（否则"指针 + 定时器"
                    // 叠加成双倍速，一顿一顿的其中一半原因就在这）
                    val sameDir = (scrollAxisIsHorizontal(sx, sy) == scrollAxisIsHorizontal(lastScrollDx, lastScrollDy)) &&
                        (if (scrollAxisIsHorizontal(sx, sy)) (sx > 0) == (lastScrollDx > 0)
                         else (sy > 0) == (lastScrollDy > 0))
                    if (sameDir) {
                        lastScrollX = px
                        lastScrollY = py
                    } else {
                        scrollAt(px, py, sx, sy, "摇杆换向")
                    }
                    return
                }
                // 位移不足一格：等下一次 MOVE。
                // （滚轮事件按坐标命中，不存在"指针贴边就滚不动"的问题，
                //  原来的贴边续滚与方向记忆一起废弃 —— 它们都是焦点模型的产物。）
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDown) return
                isDown = false
                scrollAnchorX = -1f
                scrollAnchorY = -1f
                stopFrames()
                val dragged = isDragging
                isDragging = false
                /*
                 * 惯性（父亲 2026-10-05 定，第二轮修正）：**不管怎么松手都滑**。
                 *
                 *  - 松手时光点还在动（快拨）→ 初速 = 当时的光点速度；
                 *  - 推住不动再松手 → 初速 = 快档速度（RATE_MAX）。
                 *    （第一版按"没速度就不滑"做，结果父亲实测推住再松手完全不滑 —— 不对）
                 * 松手后 1 秒内线性减速到 0。
                 * 轮播区里不滑行（否则会连着切好几张，不好控制）。
                 */
                if (dragged) {
                    val quietAtRelease = now - lastMoveAt
                    val inZone = ClickTargets.zoneAt(px, py) != null
                    if (!inZone) {
                        inertiaRate =
                            if (quietAtRelease > HOLD_QUIET_MS) RATE_MAX else rateOf(pointerSpeed)
                        inertiaStartAt = now
                        inertiaDx = lastScrollDx
                        inertiaDy = lastScrollDy
                        Log.i(
                            TAG,
                            "松手滑行：光点静了 ${quietAtRelease}ms 速度 ${pointerSpeed.toInt()}px/s " +
                                "→ 初速 ${"%.1f".format(inertiaRate)}格/秒，1 秒内减速停",
                        )
                        scheduleFrame()
                    }
                }
                Log.i(
                    TAG,
                    "按压画像: MOVE=${pressMoveCount} 方向键=${pressArrows} " +
                        "最大位移=(${pressMaxDx.toInt()},${pressMaxDy.toInt()}) " +
                        "时长=${now - downTime}ms 判定=${if (dragged) "摇杆" else "点击"} " +
                        "起点=(${downPx.toInt()},${downPy.toInt()})",
                )
                if (dragged) return                                // 摇杆推动：只发方向键，不发 OK

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
                /*
                 * 直通点击（控制条这类纯 Compose 界面，2026-10-05）：
                 * 它们没登记进 ClickTargets，而表里已有主面板条目 → 查表不中会直接
                 * return，「按钮看得到、扣扳机点不动」就是这么来的。这类层直接派发。
                 */
                if (directClick) {
                    clickSignalBefore = PanelSignals.seq
                    val hit = mouseClick(px, py)
                    Log.i(TAG, "直通点击($name): 鼠标式被接住=$hit 位置 (${px.toInt()},${py.toInt()})")
                    return
                }

                val target = ClickTargets.findAt(px, py)
                if (target != null) {
                    fireTarget(target)
                    return
                }
                if (ClickTargets.size() > 0) {
                    // 诊断：离得最近的几个控件是谁、差多远。最近目标很远 = 没登记（漏控件）；
                    // 只差几像素 = 坐标/矩形对不上（另一种病，治法不同）。
                    val near = ClickTargets.nearest(px, py, 5)
                    Log.i(
                        TAG,
                        "落点未命中任何控件 位置=(${px.toInt()},${py.toInt()}) 表内 ${ClickTargets.size()} 项",
                    )
                    near.forEach { (t, d) ->
                        Log.i(TAG, "  最近控件 $t 距离=${d.toInt()}px")
                    }
                    /*
                     * 近失兜底（2026-10-05）：只差一点点就算命中。
                     * 卡片有聚焦放大动画、瞄准时手也会轻微偏移，
                     * 差十几像素就判成"点在空白"太苛刻 —— 那正是父亲
                     * 「明明指着卡片，扣扳机却没反应」的一类来源。
                     * 16px 在 1920 宽的面板上约等于 0.8%，不会把真的空白点成控件。
                     */
                    val closest = near.firstOrNull()
                    if (closest != null && closest.second <= NEAR_MISS_PX) {
                        Log.i(TAG, "近失兜底：按命中处理（差 ${closest.second.toInt()}px）")
                        fireTarget(closest.first)
                    }
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
        stopFrames()
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
    /**
     * 控制条专用：窗口自己不能有黑底。
     *
     * 父亲 2026-10-06 报「控制条叠了两层，下层没有倒圆角」—— 圆角只画在内容那一层，
     * 底下是 Presentation 窗口自己的黑底，圆角外面就露出一块黑方块。
     * 这里把窗口背景清成透明，配合 GL 侧的 alpha 混合，圆角外才真的透出去。
     *
     * 注意：它必须排在 [onReady] 前面 —— onReady 是尾随 lambda，得留在最后一个形参位。
     */
    private val transparentWindow: Boolean = false,
    private val onReady: (View) -> Unit,
) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (transparentWindow) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            /*
             * 关键（2026-10-06 晚，弹幕层挡住视频的根因）：
             * 只把背景画成透明还不够 —— 窗口默认像素格式是不透明的，Surface 出来的
             * 纹理根本没有 alpha 通道，透明区域会被当成黑色。必须显式设成半透明格式，
             * 下游（弹幕层独立合成层）的按源透明度混合才有东西可混。
             */
            window?.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        }
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
            @OptIn(ExperimentalFoundationApi::class)
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides backOwner,
                /*
                 * 父亲 2026-10-06：「扣扳机点继续播放列时，列会左右滚」。
                 * 那是 Compose 的焦点自动滚动 —— 控件一拿到焦点就被滚进视野中央。
                 * 面板模式没有方向键，滚动全靠摇杆派发滚轮事件（scrollAt），
                 * 不依赖这套自动滚动，所以把滚动距离固定为 0：点谁就是点谁，列表不动。
                 */
                LocalBringIntoViewSpec provides object : BringIntoViewSpec {
                    override fun calculateScrollDistance(
                        offset: Float,
                        size: Float,
                        containerSize: Float,
                    ) = 0f
                },
            ) {
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
