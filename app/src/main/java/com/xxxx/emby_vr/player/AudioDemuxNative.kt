package com.xxxx.emby_vr.player

/**
 * 自管音频那一路的解封装（FFmpeg avformat）。
 *
 * 存在理由：系统解封装一次只能读一条轨，想同时拿音频包和字幕包就得再开一路连接，
 * 而网盘对同一文件只给两路 —— 第三路必被拒（2026-11-22 实测服务端取字幕 90 秒零字节）。
 * 这里一次打开就把两样都拿到，连接数不变。
 *
 * 线程：与 AudioPipe 的读线程同线程调用，内部不持锁。
 */
object AudioDemuxNative {

    private val libLoaded: Boolean = runCatching { System.loadLibrary("audiodec") }.isSuccess
    val available: Boolean get() = libLoaded

    /** 打开：startMs 为起点（0 = 从头）。失败返回 0。 */
    external fun nativeDemuxOpen(url: String, startMs: Long): Long

    /**
     * 读下一个包。
     * meta[0]：0 = 音频包，2 = 字幕文本
     * meta[1]：字节数；meta[2]：时间戳（毫秒）；meta[3]：第几条字幕轨；meta[4]：字幕结束时间（毫秒，0=未知）
     * 返回 >=0 字节数；-1 结束；<0 出错。
     */
    external fun nativeDemuxRead(handle: Long, out: ByteArray, meta: IntArray): Int

    /** out[0]=采样率 out[1]=声道数 out[2]=codec_id out[3]=文字字幕轨条数 out[4]=时长毫秒(0=未知) */
    external fun nativeDemuxInfo(handle: Long, out: IntArray): Int

    /** 音频解码器名（eac3/truehd/dca 等） */
    external fun nativeDemuxCodecName(handle: Long): String

    /** 打断阻塞中的读/打开（stop 用）；打断后这个 handle 作废，必须重新 open */
    external fun nativeDemuxAbort(handle: Long)

    external fun nativeDemuxSeek(handle: Long, ms: Long): Int

    external fun nativeDemuxClose(handle: Long)

    /** out[0]=open out[1]=close out[2]=fail out[3]=dupe out[4]=inFlight */
    external fun nativeDemuxStats(out: IntArray): Int
}
