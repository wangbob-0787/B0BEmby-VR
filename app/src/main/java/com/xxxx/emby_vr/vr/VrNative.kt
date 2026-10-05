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

    private external fun nativeSetPanelTexture(textureId: Int)

    private external fun nativeAttachPanelSurfaceTexture(surfaceTexture: android.graphics.SurfaceTexture?)

    /**
     * 把现有面板界面（Compose 那套）的纹理交给 VR 渲染线程。
     *
     * 调用时机：GL 线程里建好外部纹理之后（VrRenderer.createPanelPipeline）。
     * textureId = 0 表示解绑。
     */
    fun setPanelTexture(textureId: Int) {
        try {
            if (loaded) nativeSetPanelTexture(textureId)
        } catch (t: Throwable) {
            Log.e(TAG, "绑定面板纹理失败：${t.message}")
        }
    }

    /** 注册面板帧更新器（内部每帧调 SurfaceTexture.updateTexImage） */
    fun attachPanelSurfaceTexture(st: android.graphics.SurfaceTexture?) {
        try {
            if (loaded) nativeAttachPanelSurfaceTexture(st)
        } catch (t: Throwable) {
            Log.e(TAG, "注册面板帧更新器失败：${t.message}")
        }
    }

    /** 起 VR 会话（原生渲染线程）；失败返回 false，应用继续按 2D 面板模式跑 */
    fun startVr(activity: android.app.Activity): Boolean = try {
        if (loaded) nativeStartVr(activity) else false
    } catch (t: Throwable) {
        Log.e(TAG, "启动 VR 会话失败：${t.message}")
        false
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
