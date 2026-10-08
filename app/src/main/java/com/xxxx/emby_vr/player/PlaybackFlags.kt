package com.xxxx.emby_vr.player

/**
 * 播放方式的跨层标记（父亲 2026-10-08）。
 *
 * 取播放信息（EmbyApi.getPlaybackInfo）时已经能看到片源的色彩范围，那里一旦发现
 * 是杜比视界，就把标记立起来；真正起播（MainActivity.startPlayer）在另一处，
 * 靠这个标记决定走哪条解码内核。用一个进程内的简单标记，不引额外数据流。
 */
object PlaybackFlags {

    /**
     * 本条片源是杜比视界（不管哪一版）。只作诊断与日志用。
     */
    @Volatile
    var dolbyVisionSource: Boolean = false

    /**
     * 起播是否改走 mpv 内核（父亲 2026-10-08 定）。
     *
     * **只有杜比视界 Profile 5 才需要**：那一版的画面用杜比私有的色彩编码，
     * 系统解码器既解不出画面、也还原不了颜色。
     *
     * 而 Profile 8.1 是「HDR10 兼容」的那一版 —— 底层就是标准 HDR10，
     * 系统播放器把它当普通 HDR 播，颜色是对的（父亲 2026-10-08 在 Mac 上实测
     * 《律界战争》就是 8.1，服务端能正常送）。所以**普通片源与 8.1 一律保持
     * 原路径**，内核只留给 5.0。
     */
    @Volatile
    var useKernelDecoder: Boolean = false

    /** 探到的杜比视界版本（如 "DoviProfile81" / "DoviProfile50" / "未探到"），只写日志。 */
    @Volatile
    var dolbyVisionProfile: String? = null

    /** 片源的视频描述，写日志用（如 "hevc DolbyVision 3840x2160"）。 */
    @Volatile
    var videoDescriptor: String? = null

    /** 片源画面比例（宽/高），探片源时顺带算出；探不到就按 16:9。 */
    @Volatile
    var videoAspect: Float? = null

    /**
     * 片源分辨率（探片源时拿到）。
     *
     * 用途：起播前设画面缓冲尺寸。硬解的解码器会自己按视频尺寸设，内核不会 ——
     * 不设就按默认尺寸画，屏幕上是拉伸的纯色块（父亲 2026-10-08 实测）。
     */
    @Volatile
    var videoWidth: Int = 0

    @Volatile
    var videoHeight: Int = 0

    fun reset() {
        dolbyVisionSource = false
        useKernelDecoder = false
        dolbyVisionProfile = null
        videoDescriptor = null
        videoAspect = null
        videoWidth = 0
        videoHeight = 0
    }
}
