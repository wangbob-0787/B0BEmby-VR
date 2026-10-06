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

    private external fun nativeSetPanelActive(active: Boolean)

    /**
     * 三张画面纹理的回调（2026-10-05 晚修）。
     *
     * 纹理由**原生渲染线程在自己的 GL 上下文里**建好 —— 这是关键：之前建在 2D
     * 那条 GL 线程的上下文里，VR 侧按同一个编号取到的是另一张纹理，三块屏因此
     * 互相串画面（控制条贴视频 / 播放屏贴控制条）。
     * 建好后原生主动回调这里，界面层拿 SurfaceTexture 去建虚拟显示器或交给播放器。
     */
    interface TextureSink {
        /** 主面板（电视版界面）画面 */
        fun onPanelTexture(st: android.graphics.SurfaceTexture)

        /** 播放画面（交给 ExoPlayer 输出视频） */
        fun onVideoTexture(st: android.graphics.SurfaceTexture)

        /** 控制条画面 */
        fun onOsdTexture(st: android.graphics.SurfaceTexture)

        /** 展开菜单画面（架在控制条正上方的透明面板） */
        fun onMenuTexture(st: android.graphics.SurfaceTexture)

        /** 弹幕层画面（贴在银幕前、比银幕小一圈的透明层，2026-10-06） */
        fun onDanmakuTexture(st: android.graphics.SurfaceTexture)

        /** 片名 logo 画面（银幕左上角的小透明层，2026-10-06） */
        fun onLogoTexture(st: android.graphics.SurfaceTexture)
    }

    private external fun nativeAttachTextureSink(sink: TextureSink)

    /** 注册纹理回调：原生那边建好会立刻回调；如果已经建好，注册时补推一次 */
    fun attachTextureSink(sink: TextureSink) {
        try {
            if (loaded) nativeAttachTextureSink(sink)
        } catch (t: Throwable) {
            Log.e(TAG, "注册纹理回调失败：${t.message}")
        }
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

    /** 播放开始/结束：true 贴视频画面，false 回到面板 */
    fun setVideoActive(active: Boolean) {
        try {
            if (loaded) nativeSetVideoActive(active)
        } catch (t: Throwable) {
            Log.e(TAG, "设置播放画面状态失败：${t.message}")
        }
    }

    private external fun nativeSetPanelShown(shown: Boolean)

    private external fun nativeGetPanelPlace(): FloatArray

    private external fun nativeSetPanelPlace(
        x: Float,
        y: Float,
        z: Float,
        yaw: Float,
        pitch: Float,
        width: Float,
    )

    /** 读海报墙当前摆放（位置 / 朝向 / 宽度）；拿不到返回 null */
    fun getPanelPlace(): FloatArray? = try {
        if (loaded) nativeGetPanelPlace() else null
    } catch (t: Throwable) {
        Log.e(TAG, "读海报墙摆放失败：${t.message}")
        null
    }

    /**
     * 还原海报墙摆放（父亲 2026-10-06 晚：位置、大小、远近都要记住，
     * 下次打开 APP 回到上次退出前那个样子）。
     */
    fun setPanelPlace(place: FloatArray) {
        if (place.size < 6) return
        try {
            if (loaded) {
                nativeSetPanelPlace(place[0], place[1], place[2], place[3], place[4], place[5])
            }
        } catch (t: Throwable) {
            Log.e(TAG, "还原海报墙摆放失败：${t.message}")
        }
    }

    /**
     * 海报墙显示 / 收起（控制条上的「选片」按钮切换，父亲 2026-10-05 定）。
     *
     * 海报墙常驻在左手边，播放不受影响；这个开关只决定它出不出现。
     * 状态记在这里，界面层随时可查当前是开还是关。
     */
    @Volatile
    var panelShown = true
        private set

    /**
     * 改海报墙显示状态并告诉原生层。
     *
     * 方法名不能叫 setPanelShown —— 那样会和上面 panelShown 自动生成的 setter
     * 撞成同一个 JVM 签名（run 123 编译失败：Platform declaration clash）。
     */
    fun updatePanelShown(shown: Boolean) {
        panelShown = shown
        try {
            if (loaded) nativeSetPanelShown(shown)
        } catch (t: Throwable) {
            Log.e(TAG, "设置海报墙状态失败：${t.message}")
        }
    }

    private external fun nativeSetVideoAspect(aspect: Float)

    private external fun nativeSetImageAdjust(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        sharpen: Float,
        temperature: Float,
    )

    private external fun nativeSetVideoSize(width: Int, height: Int)

    /**
     * 画面调整五件套：亮度、对比度、饱和度、锐度、色温。
     *
     * 父亲 2026-10-06 定的流程：先在控制条上手动调好，再把数值抄进代码当默认值，
     * 最后把这些调节入口撤掉。
     * 亮度/对比度/饱和度 1.0 = 原样；锐度 0 = 不锐化；色温 -1 冷 … +1 暖。
     */
    fun setImageAdjust(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        sharpen: Float,
        temperature: Float,
    ) {
        try {
            if (loaded) {
                nativeSetImageAdjust(brightness, contrast, saturation, sharpen, temperature)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "设置画面调整失败：${t.message}")
        }
    }

    /** 视频纹理尺寸（锐化邻域步长用）*/
    fun setVideoSize(width: Int, height: Int) {
        try {
            if (loaded) nativeSetVideoSize(width, height)
        } catch (t: Throwable) {
            Log.e(TAG, "设置视频尺寸失败：${t.message}")
        }
    }

    /**
     * 告诉原生层当前片子的宽高比（宽/高），银幕按它调高度。
     *
     * 父亲 2026-10-06：「有些片子长宽比不对」—— 银幕原来固定 16:9，
     * 2.35:1 或 4:3 的片子贴上去被拉伸。
     */
    fun setVideoAspect(aspect: Float) {
        try {
            if (loaded) nativeSetVideoAspect(aspect)
        } catch (t: Throwable) {
            Log.e(TAG, "设置视频比例失败：${t.message}")
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
         * 海报墙上按住扳机拖动 → 滚动量（2026-10-06 晚加）。
         *
         * 单位是海报墙面板像素：手柄往右拖，dx 为负（内容跟手往左走）。
         */
        fun onPanelScroll(dx: Float, dy: Float)

        /**
         * 光柱是否落在海报墙上（2026-10-06 加）。
         *
         * 只在状态变化时回推。界面层用它决定两件事：播放期间面板接不接输入、
         * B 键是给面板导航栈还是退出播放。
         */
        fun onPanelFocus(onPanel: Boolean)

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
        /**
         * 控制条上的指针位置。
         *
         * @param pressed 扳机是否按住 —— 按住期间界面按「按下 / 拖动 / 抬起」处理，
         *                进度条才拖得动（父亲 2026-10-06：激光瞄准 + 扣扳机直接拖）。
         */
        fun onOsdPointer(px: Float, py: Float, pressed: Boolean)

        /** 控制条上的一次点击（扣扳机） */
        fun onOsdClick(px: Float, py: Float)

        /** 展开菜单上的指针（坐标是菜单面板像素：2560×1200） */
        fun onMenuPointer(px: Float, py: Float)

        /** 展开菜单上的一次点击（扣扳机） */
        fun onMenuClick(px: Float, py: Float)

        /** 播放中扣扳机 = 开关控制条（由 Activity 决定显隐） */
        fun onToggleOsd()
    }

    private external fun nativeAttachInputSink(sink: InputSink)

    private external fun nativeSetVideoActive(active: Boolean)

    private external fun nativeSetOsdVisible(visible: Boolean)

    private external fun nativeSetMenuVisible(visible: Boolean)

    private external fun nativeSetDanmakuVisible(visible: Boolean)

    private external fun nativeSetLogoVisible(visible: Boolean)

    private external fun nativeSetMenuHitRect(l: Float, t: Float, r: Float, b: Float)

    /**
     * 展开菜单显示/隐藏（2026-10-06）。
     *
     * 菜单是**另一块面板**，架在控制条正上方；控制条那块矮条尺寸不变。
     */
    fun setMenuVisible(visible: Boolean) {
        try {
            if (loaded) nativeSetMenuVisible(visible)
        } catch (t: Throwable) {
            Log.e(TAG, "设置展开菜单状态失败：${t.message}")
        }
    }

    /** 弹幕层显示/隐藏（父亲 2026-10-06：弹幕要接进来） */
    fun setDanmakuVisible(visible: Boolean) {
        try {
            if (loaded) nativeSetDanmakuVisible(visible)
        } catch (t: Throwable) {
            Log.e(TAG, "设置弹幕层状态失败：${t.message}")
        }
    }

    /** 片名 logo 显示/隐藏（播放中出现，停止后收起） */
    fun setLogoVisible(visible: Boolean) {
        try {
            if (loaded) nativeSetLogoVisible(visible)
        } catch (t: Throwable) {
            Log.e(TAG, "设置片名 logo 状态失败：${t.message}")
        }
    }

    /**
     * 上报菜单卡片实际占的那块矩形（归一化 0…1，相对整块菜单面板）。
     *
     * 光柱落在卡片外（透明区）时，射线要穿过去打到后面的控制条 / 银幕上
     * （父亲 2026-10-06）。菜单收起时上报全 0，等于不拦。
     */
    fun setMenuHitRect(l: Float, t: Float, r: Float, b: Float) {
        try {
            if (loaded) nativeSetMenuHitRect(l, t, r, b)
        } catch (e: Throwable) {
            Log.e(TAG, "上报菜单卡片位置失败：${e.message}")
        }
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
