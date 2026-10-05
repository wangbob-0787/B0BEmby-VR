package com.xxxx.emby_vr.vr

import android.util.Log

/**
 * OpenXR 原生层的 Kotlin 入口。
 *
 * 2026-10-05 起：VR 影院模式（PICO 的 VR 模式）必须自己接 VR 运行时，
 * 走官方 OpenXR（Khronos loader；PICO OS ≥ 5.9 支持，本机 5.13.7）。
 *
 * 现在只有探针函数，用来证明「云端 CI 能编原生代码 + 官方 loader 能链上」；
 * 真正的会话循环（立体渲染、手柄输入）随后加在 native 侧。
 */
object VrNative {

    private const val TAG = "B0BEmbyVR"

    /** 原生库是否加载成功（加载失败不崩：应用按 2D 面板模式继续跑） */
    val loaded: Boolean = try {
        System.loadLibrary("b0bvr")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "原生库加载失败：${t.message}")
        false
    }

    private external fun nativeProbe(): String

    private external fun nativeStartVr(activity: android.app.Activity): Boolean

    private external fun nativeStopVr()

    private external fun nativeCreatePanelSurfaceTexture(): android.graphics.SurfaceTexture?

    private external fun nativeSetPanelActive(active: Boolean)

    /**
     * 建面板纹理与 SurfaceTexture —— **在 VR 渲染线程的 GL 上下文里建**。
     *
     * 踩坑（run 97）：由 2D 线程建纹理再传 id 过来，VR 里全黑，
     * 因为 GL 纹理 id 只在创建它的上下文里有效。
     * 这里的 native 调用内部会切到渲染线程执行，返回可直接建 Surface 的对象。
     */
    fun createPanelSurfaceTexture(): android.graphics.SurfaceTexture? = try {
        if (loaded) nativeCreatePanelSurfaceTexture() else null
    } catch (t: Throwable) {
        Log.e(TAG, "创建面板纹理失败：${t.message}")
        null
    }

    /** 界面开始往面板 Surface 上画了 → 允许贴纹理 */
    fun setPanelActive(active: Boolean) {
        try {
            if (loaded) nativeSetPanelActive(active)
        } catch (t: Throwable) {
            Log.e(TAG, "设置面板激活状态失败：${t.message}")
        }
    }

    /** 起 VR 会话（原生渲染线程）；失败返回 false，应用继续按 2D 面板模式跑 */
    fun startVr(activity: android.app.Activity): Boolean = try {
        if (loaded) nativeStartVr(activity) else false
    } catch (t: Throwable) {
        Log.e(TAG, "启动 VR 会话失败：${t.message}")
        false
    }

    /**
     * VR 输入回调（2026-10-05）。
     *
     * VR 模式下没有系统合成的触摸流，光柱指向、扳机、摇杆、B 键全部由原生层
     * 在渲染线程里推过来。坐标是**面板像素**（1920×1080，与 PanelLayer 一致）。
     * 实现方要注意：回调不在主线程，操作界面元素前自己切主线程。
     */
    interface InputSink {
        fun onPointer(px: Float, py: Float)
        fun onClick(px: Float, py: Float)
        fun onScroll(px: Float, py: Float, dx: Float, dy: Float)
        fun onBack()
    }

    private external fun nativeAttachInputSink(sink: InputSink)

    fun attachInputSink(sink: InputSink) {
        try {
            if (loaded) nativeAttachInputSink(sink)
        } catch (t: Throwable) {
            Log.e(TAG, "注册 VR 输入回调失败：${t.message}")
        }
    }

    fun stopVr() {
        try {
            if (loaded) nativeStopVr()
        } catch (t: Throwable) {
            Log.e(TAG, "停止 VR 会话失败：${t.message}")
        }
    }

    /** 打一行探针日志（失败也只是日志缺失，不影响界面） */
    fun probe(): String = try {
        if (loaded) nativeProbe() else "原生库未加载"
    } catch (t: Throwable) {
        "探针失败：${t.message}"
    }
}
