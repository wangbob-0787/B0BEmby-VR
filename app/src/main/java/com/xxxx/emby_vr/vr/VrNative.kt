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
        panelActive = active
        try {
            if (loaded) nativeSetPanelActive(active)
        } catch (t: Throwable) {
            Log.e(TAG, "设置面板激活状态失败：${t.message}")
        }
    }

    /**
     * 界面是否已经在往面板上画（面板就绪）。
     *
     * 与 VrRenderer.panelActive 不是一回事：那个只在老的 2D 管线里被置位，
     * VR 模式下永远是 false —— 拿它当输入前置条件会把光柱输入全部丢掉
     * （run 109 实机现象：原生日志里点击一条条都在，界面却完全没反应）。
     */
    @Volatile
    var panelActive = false
        private set

    /** 起 VR 会话（原生渲染线程）；失败返回 false，应用继续按 2D 面板模式跑 */
    fun startVr(activity: android.app.Activity): Boolean = try {
        val ok = if (loaded) nativeStartVr(activity) else false
        vrRunning = ok
        ok
    } catch (t: Throwable) {
        Log.e(TAG, "启动 VR 会话失败：${t.message}")
        vrRunning = false
        false
    }

    /** VR 会话是否在跑：决定播放画面走 VR 原生纹理还是老的 2D 画面管线 */
    @Volatile
    var vrRunning = false
        private set

    /**
     * 建播放画面用的纹理与 SurfaceTexture（VR 原生播放屏，2026-10-05）。
     *
     * 与面板纹理同一个套路：VR 上下文里建 OES 外部纹理 → 包成 SurfaceTexture 交回，
     * ExoPlayer 直接往这个 Surface 输出视频帧 → 贴到 VR 里那块平面上。
     */
    fun createVideoSurfaceTexture(): android.graphics.SurfaceTexture? = try {
        if (loaded && vrRunning) nativeCreateVideoSurfaceTexture() else null
    } catch (t: Throwable) {
        Log.e(TAG, "创建播放画面纹理失败：${t.message}")
        null
    }

    /** 播放开始/结束：true 贴视频画面，false 回到面板 */
    fun setVideoActive(active: Boolean) {
        try {
            if (loaded) nativeSetVideoActive(active)
        } catch (t: Throwable) {
            Log.e(TAG, "设置播放画面状态失败：${t.message}")
        }
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

        /**
         * 摇杆状态（不是单次滚动量）。
         *
         * 原生层按 30Hz 把当前摇杆量送过来，回中时补一帧 (0,0) ——
         * 平滑与惯性都交给面板层逐帧算，原生不掺和节奏。
         * 方向：sx 右为正、sy **上**为正（与 OpenXR 一致），面板层内部再翻成面板坐标。
         */
        fun onStick(px: Float, py: Float, sx: Float, sy: Float)
        fun onBack()

        /** 控制条上的指针（坐标是控制条面板像素：1920×270） */
        fun onOsdPointer(px: Float, py: Float)

        /** 控制条上的一次点击（扣扳机） */
        fun onOsdClick(px: Float, py: Float)

        /** 播放中扣扳机 = 开关控制条（由 Activity 决定显隐） */
        fun onToggleOsd()
    }

    private external fun nativeAttachInputSink(sink: InputSink)

    private external fun nativeCreateVideoSurfaceTexture(): android.graphics.SurfaceTexture?

    private external fun nativeSetVideoActive(active: Boolean)

    private external fun nativeCreateOsdSurfaceTexture(): android.graphics.SurfaceTexture?

    private external fun nativeSetOsdVisible(visible: Boolean)

    /**
     * 建控制条（OSD）用的纹理与 SurfaceTexture（2026-10-05）。
     *
     * 控制条是一块架在观影者身前近场的小面板（1920×270），与主面板/视频同一套
     * VirtualDisplay + SurfaceTexture 机制，只是尺寸与位置不同。
     */
    fun createOsdSurfaceTexture(): android.graphics.SurfaceTexture? = try {
        if (loaded && vrRunning) nativeCreateOsdSurfaceTexture() else null
    } catch (t: Throwable) {
        Log.e(TAG, "创建控制条纹理失败：${t.message}")
        null
    }

    /** 控制条显示/隐藏 */
    fun setOsdVisible(visible: Boolean) {
        try {
            if (loaded) nativeSetOsdVisible(visible)
        } catch (t: Throwable) {
            Log.e(TAG, "设置控制条状态失败：${t.message}")
        }
    }

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
