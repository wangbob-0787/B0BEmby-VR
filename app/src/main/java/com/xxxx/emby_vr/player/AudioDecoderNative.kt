package com.xxxx.emby_vr.player

/**
 * 自带音频解码器的入口（FFmpeg n7.1，只带音频解码 + 重采样）。
 *
 * 存在理由：PICO 自带的解码器没有 EAC3 能力，杜比数字+ 的片子出来是静音。
 * 这里只解「已经由系统拆好的音频包」：喂进去一个包的字节，吐出来 s16 交错 PCM。
 *
 * 线程：同一个 handle 不能并发调用；调用方自己保证串行（跳转重建时先 close 再 new）。
 */
object AudioDecoderNative {

    /** 库没编进来时（本地无预编译库）不要崩，调用方先查 available。 */
    private val libLoaded: Boolean = runCatching { System.loadLibrary("audiodec") }.isSuccess

    val available: Boolean get() = libLoaded

    init {
        if (!libLoaded) {
            android.util.Log.w("B0BEmbyVR-AudioDec", "audiodec 未加载，自管音频解码不可用")
        }
    }

    /** 建一个解码器句柄，失败返回 0。 */
    external fun nativeNew(): Long

    /**
     * 打开。codec 用 FFmpeg 的解码器名（eac3 / truehd / dca / ac3 / aac ...）。
     * extradata 可为 null；targetChannels 传 0 表示保持源声道布局，传 2 表示下混立体声。
     * 返回 0 成功，负数失败。
     */
    external fun nativeOpen(handle: Long, codec: String, extradata: ByteArray?, targetChannels: Int): Int

    /**
     * 喂一个音频包。返回写进 out 的字节数（s16 交错，必然是每帧字节数的整数倍）；
     * 0 表示这次没有产出，需要继续喂；负数表示错误或已到结尾。
     */
    external fun nativeDecode(handle: Long, input: ByteArray, inputLen: Int, out: ByteArray): Int

    /** 收尾：把解码器里残留的 PCM 全部取出来。返回字节数，0 表示收干净了。 */
    external fun nativeFlush(handle: Long, out: ByteArray): Int

    /** 释放句柄。 */
    external fun nativeClose(handle: Long)
}
