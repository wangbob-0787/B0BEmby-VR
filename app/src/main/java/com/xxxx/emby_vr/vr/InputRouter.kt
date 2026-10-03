package com.xxxx.emby_vr.vr

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 手柄输入处理（P0 核心）。
 *
 * ## 为什么要专门做这一层
 *
 * 之前把 TV 版装到 PICO 上实测：界面能渲染，但**按钮点不动** ——
 * TV 版是「方向键 + 焦点移动」模型，PICO 手柄发的是**指针/触碰事件**，
 * 两套对不上。本类就是把两套输入统一成一份**语义动作**。
 *
 * ## 映射表（父亲 2026-10-03 定的「同 TV 版交互逻辑，适配 VR 手柄」）
 *
 * | 来源 | 语义 |
 * |---|---|
 * | 方向键 / 左摇杆 | 移动焦点（同 TV 版） |
 * | 确定键 / 手柄 A | 确认（扳机） |
 * | 返回键 / 手柄 B | 返回上一层 |
 * | 射线指向（指针） | 悬停 → 焦点跟随指针 |
 * | 摇杆左右（播放中） | 快退 / 快进（同 TV 版左右键） |
 *
 * ## 两只手柄都要接
 *
 * 踩过的坑：WebXR 阶段只监听 0 号手柄，导致另一只的扳机无反应。
 * 这里按 deviceId 区分，两只都收。
 */
class InputRouter(
    private val onAction: (Action) -> Unit,
) {

    /** 语义动作 —— 与 TV 版共用一套，界面层只认这个 */
    enum class Action {
        UP, DOWN, LEFT, RIGHT,
        CONFIRM,
        BACK,
        SEEK_BACK,
        SEEK_FORWARD,
        PLAY_PAUSE,
        /** 指针悬停到某点（归一化坐标 0..1），用于射线选卡 */
        HOVER,
        /** 手柄射线 3D 位置（米，相对原点），P2 起用于射线-海报命中判定 */
        RAY_POS,
        /** 手柄射线方向（单位向量，世界系），配合 RAY_POS 求射线 */
        RAY_DIR,
    }

    /**
     * 是否启用模拟射线。
     *
     * 真实 OpenXR 手柄姿态数据（PICO controller pose）接入前（P5），
     * 用摇杆推行位置模拟一条指向屏幕平面的假射线，让海报能被"指"到。
     */
    @Volatile
    var simulatedRay = true

    /**
     * 模拟射线最近一次在屏幕平面上的坐标。
     *
     * 坐标口径与渲染一致：**视口归一化坐标**，半高 = 1，
     * 横向上限 = 视口宽高比（竖屏时 ≤1，横屏 4320x2160 时为 2.0）。
     */
    @Volatile
    var lastSimRay: FloatArray? = null

    /** 记录最近一次按键时间，用于区分「点按」与「长按连发」 */
    private var lastRepeatAt = 0L

    /**
     * 处理按键。返回 true 表示已消费。
     *
     * 注意：手柄的 A 键在不同设备上可能是 `KEYCODE_BUTTON_A` 或 `KEYCODE_DPAD_CENTER`，
     * 两类都接受（这是 PICO 与普通蓝牙手柄的差异点）。
     */
    fun onKeyDown(event: KeyEvent): Boolean {
        // 过滤按键自动重复：手柄长按会持续发 DOWN，这里做节流
        val now = System.currentTimeMillis()
        if (event.repeatCount > 0 && now - lastRepeatAt < REPEAT_MS) return true
        lastRepeatAt = now

        val action = mapKey(event.keyCode) ?: return false
        Log.i(TAG, "按键 ${VrSession.describeKey(event.keyCode)} → $action (device=${event.deviceId})")
        onAction(action)
        return true
    }

    /**
     * 处理摇杆/扳机等模拟输入。
     *
     * PICO 手柄的扳机是模拟量（0..1），推到底产生一次 CONFIRM；
     * 摇杆推行方向产生连续的方向动作。
     */
    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0 &&
            event.source and InputDevice.SOURCE_GAMEPAD == 0
        ) return false

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)

        // 摇杆 → 方向（带死区，防漂移误触）
        if (kotlin.math.abs(x) > STICK_DEADZONE || kotlin.math.abs(y) > STICK_DEADZONE) {
            val action = if (kotlin.math.abs(x) > kotlin.math.abs(y)) {
                if (x > 0) Action.RIGHT else Action.LEFT
            } else {
                if (y > 0) Action.DOWN else Action.UP
            }
            val now = System.currentTimeMillis()
            if (now - lastRepeatAt >= REPEAT_MS) {
                lastRepeatAt = now
                onAction(action)
            }
            return true
        }

        // 模拟射线：摇杆在死区内但仍有偏转时，把偏转量映射到视口坐标。
        // 横屏 4320x2160 宽高比 = 2.0，所以 x 满偏映射到 ±2。
        // P5 接入真实 controller pose 后删除此段，改用 OpenXR 给的手柄朝向。
        if (simulatedRay && (kotlin.math.abs(x) > 0.02f || kotlin.math.abs(y) > 0.02f)) {
            val px = x * RAY_X_EXTENT
            val py = -y                      // 摇杆上推 → 焦点上移（屏幕坐标 y 向下）
            lastSimRay = floatArrayOf(px, py)
            currentSimRay = lastSimRay
            onAction(Action.RAY_POS)
            onAction(Action.RAY_DIR)
            Log.d(TAG, "模拟射线: x=$px y=$py")
            return true
        }
        return false
    }

    private fun mapKey(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Action.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Action.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Action.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Action.RIGHT

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,          // 手柄 A / 扳机按下
        -> Action.CONFIRM

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_B,          // 手柄 B
        -> Action.BACK

        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Action.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND -> Action.SEEK_BACK
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.SEEK_FORWARD

        else -> null
    }

    companion object {
        private const val TAG = "B0BEmbyVR"
        /** 方向键连发节流：200ms 一次，同 TV 版手感 */
        private const val REPEAT_MS = 200L
        private const val STICK_DEADZONE = 0.35f

        /**
         * 模拟射线的横向满偏范围（归一化坐标）。
         * 取值 2.0 = 4320x2160 横屏的宽高比，摇杆推到底刚好扫过整屏宽度。
         */
        private const val RAY_X_EXTENT = 2.0f

        /**
         * 模拟射线位置的全局快照。
         *
         * 为什么用伴生对象而不是实例字段：MainActivity 的输入 lambda 在
         * `input` 字段初始化过程中就会被调用，此时引用 `input.lastSimRay`
         * 属于「初始化未完成即自引用」，Kotlin 编译不过。
         */
        @Volatile
        var currentSimRay: FloatArray? = null
    }
}
