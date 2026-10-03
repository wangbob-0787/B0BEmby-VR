package com.xxxx.emby_vr.vr

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * XR 会话封装。
 *
 * ## 当前实现（P0，2026-10-03）
 *
 * 走 **Android 标准 API 检测 + 标准 GL 渲染**，不依赖 PICO 专有 SDK：
 *   - 优点：无第三方 AAR 依赖，云端 CI 一定能编译通过，第一版必定能出包装机
 *   - 代价：拿到的是「单眼/平面画面」，不是真正的双目立体渲染
 *
 * 在 PICO 上，系统会把这类应用放进 VR 空间的一块虚拟屏里显示（等同 2D 面板），
 * 这一步足以验证：**手柄输入能不能收到、射线/按键映射对不对、界面能不能用**。
 * 这三件事正是「VR 手柄兼容」的核心风险，先验证它们，比先啃立体渲染更划算。
 *
 * ## 升级到真 OpenXR（P1 起）
 *
 * 需要引入 PICO 的 OpenXR 绑定（二选一）：
 *   1. **原生路线**：Khronos OpenXR-SDK + `XR_KHR_android_surface_swapchain`
 *      参考 PICO 官方演示 https://github.com/picoxr/OpenXR_VideoPlayer_Demo
 *   2. **封装路线**：PICO 提供的 Android 层 XR 库（`pico_openxr` AAR）
 * 届时本类的 `start/resume/pause/stop` 改为真会话生命周期，
 * `VrRenderer` 改为每眼各渲染一次（用 XR 给的投影矩阵与视图矩阵）。
 */
class VrSession {

    private var activity: Activity? = null
    private var started = false

    /** 是否检测到 VR/XR 运行环境（用于日志与行为分支） */
    var isVrEnvironment: Boolean = false
        private set

    fun start(activity: Activity): Boolean {
        this.activity = activity
        isVrEnvironment = detectVrEnvironment(activity)
        started = true
        Log.i(TAG, "VrSession.start —— VR 环境=$isVrEnvironment")
        logConnectedInputDevices(activity)
        return isVrEnvironment
    }

    fun resume() {
        if (!started) return
        Log.i(TAG, "VrSession.resume")
    }

    fun pause() {
        if (!started) return
        Log.i(TAG, "VrSession.pause")
    }

    fun stop() {
        started = false
        activity = null
        Log.i(TAG, "VrSession.stop")
    }

    /**
     * 判断是否运行在头显上。
     * PICO 4 会在 features 里声明 `android.hardware.vr.headtracking`。
     */
    private fun detectVrEnvironment(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val hasHeadTracking = pm.hasSystemFeature("android.hardware.vr.headtracking")
        val hasHighPerf = pm.hasSystemFeature("android.hardware.vr.high_performance")
        Log.i(TAG, "系统特性: headtracking=$hasHeadTracking high_performance=$hasHighPerf")
        return hasHeadTracking || hasHighPerf
    }

    /**
     * 打印当前接入的手柄设备。
     *
     * 这一步是为排查「手柄按键收不到」准备的第一手证据 ——
     * 之前 WebXR 阶段踩过「只监听 0 号手柄导致扳机无反应」的坑，
     * 这次开机就把所有输入设备的名字/来源列出来，一眼能看出系统认出了几只。
     */
    private fun logConnectedInputDevices(ctx: Context) {
        val ids = InputDevice.getDeviceIds()
        Log.i(TAG, "输入设备共 ${ids.size} 个:")
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            val src = dev.sources
            val kind = buildString {
                if (src and InputDevice.SOURCE_GAMEPAD != 0) append("GAMEPAD ")
                if (src and InputDevice.SOURCE_JOYSTICK != 0) append("JOYSTICK ")
                if (src and InputDevice.SOURCE_DPAD != 0) append("DPAD ")
                if (src and InputDevice.SOURCE_MOUSE != 0) append("MOUSE ")
                if (src and InputDevice.SOURCE_TOUCHSCREEN != 0) append("TOUCH ")
            }
            Log.i(TAG, "  [${dev.id}] ${dev.name}  来源={$kind}")
        }
    }

    companion object {
        private const val TAG = "B0BEmbyVR"

        /** 手柄按键的语义映射表（供上层查询用；P0 先记录原始 keycode） */
        fun describeKey(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "手柄 A（扳机/确认）"
            KeyEvent.KEYCODE_BUTTON_B -> "手柄 B（返回）"
            KeyEvent.KEYCODE_BUTTON_X -> "手柄 X"
            KeyEvent.KEYCODE_BUTTON_Y -> "手柄 Y"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "确定"
            KeyEvent.KEYCODE_DPAD_UP -> "上"
            KeyEvent.KEYCODE_DPAD_DOWN -> "下"
            KeyEvent.KEYCODE_DPAD_LEFT -> "左"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "右"
            KeyEvent.KEYCODE_BACK -> "返回"
            else -> "其它($keyCode)"
        }
    }
}
