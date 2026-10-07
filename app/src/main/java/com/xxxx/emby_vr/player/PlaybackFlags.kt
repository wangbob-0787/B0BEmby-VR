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
     * 本条片源是杜比视界，系统解码器吃不下 —— 起播时走 mpv 内核。
     */
    @Volatile
    var dolbyVisionSource: Boolean = false

    /** 片源的视频描述，写日志用（如 "hevc DolbyVision 3840x2160"）。 */
    @Volatile
    var videoDescriptor: String? = null

    fun reset() {
        dolbyVisionSource = false
        videoDescriptor = null
    }
}
