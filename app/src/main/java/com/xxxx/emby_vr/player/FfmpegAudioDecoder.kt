package com.xxxx.emby_vr.player

import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 自带音频解码器（FFmpeg，libaudiodec.so）的薄封装。
 *
 * 只做三件事：把系统拆好的一个包喂进去、取回 s16 交错 PCM、保证输出的字节数是整帧倍数。
 * 为什么不直接用 native 返回：重采样到 48k 时 swr 返回的样本数不保证整帧倍数（豆豆 2026-11-21 修正），
 * 直接把半帧写进 AudioTrack 会与设备帧长错位，所以尾巴留在 [carry] 里，下次拼上再交出去。
 *
 * 线程：与 MediaCodec 那条路一样，只允许解码线程调用。
 */
class FfmpegAudioDecoder(
    private val mime: String,
    private val format: MediaFormat,
    private val targetChannels: Int = 0,
) {

    companion object {
        private const val TAG = "AudioDec"
        private const val OUT_BYTES = 512 * 1024
    }

    private var handle = 0L
    private var carry = ByteArray(0)
    private val out = ByteArray(OUT_BYTES)

    /** 实际输出声道数（open 之后才有效，用于算每帧字节数） */
    var outChannels = 0
        private set

    /** 日志用：解码器名与 extradata 情况 */
    var desc = ""
        private set

    val isOpen: Boolean get() = handle != 0L

    fun open(): Boolean {
        close()
        if (!AudioDecoderNative.available) {
            Log.e(TAG, "自检失败：audiodec 未加载（库没编进 APK 或符号未导出）")
            return false
        }
        handle = AudioDecoderNative.nativeNew()
        if (handle == 0L) {
            Log.e(TAG, "自检失败：nativeNew 返回 0")
            return false
        }
        val codecName = codecNameOf(mime)
        /*
         * 键名用字面量 "csd-0"：SDK 里没有公开的 MediaFormat.KEY_CSD0 常量（编译期 Unresolved reference）。
         * csd 缓冲区常带 position 偏移，直接 get 会读到错位的数据，所以先 duplicate。
         */
        val csd: ByteArray? = runCatching {
            format.getByteBuffer("csd-0")?.let { bb ->
                val view = bb.duplicate()
                ByteArray(view.remaining()).also { view.get(it) }
            }
        }.getOrNull()
        val rc = AudioDecoderNative.nativeOpen(handle, codecName, csd, targetChannels)
        if (rc != 0) {
            Log.e(TAG, "自检失败：nativeOpen($codecName) rc=$rc")
            close()
            return false
        }
        outChannels = targetChannels.coerceAtLeast(0).takeIf { it > 0 }
            ?: runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
        desc = "$codecName|csd=${csd?.size ?: 0}B|$outChannels ch"
        Log.i(TAG, "解码器就绪：$desc")
        return true
    }

    /** 喂一个包；返回可直接写设备的那一段（可能为空：需要更多输入）。 */
    fun decode(data: ByteArray, size: Int, ptsUs: Long): ByteArray? {
        if (handle == 0L) return null
        val n = AudioDecoderNative.nativeDecode(handle, data, size, out)
        if (n < 0) {
            Log.e(TAG, "解码失败 rc=$n pts=${ptsUs / 1000}ms")
            return null
        }
        if (n == 0) return null
        return alignOut(out, n)
    }

    /** 收尾：把解码器里剩下的取干净。 */
    fun flush(): ByteArray? {
        if (handle == 0L) return null
        val n = AudioDecoderNative.nativeFlush(handle, out)
        if (n <= 0) return null
        return alignOut(out, n)
    }

    fun close() {
        if (handle != 0L) {
            runCatching { AudioDecoderNative.nativeClose(handle) }
            handle = 0L
        }
        carry = ByteArray(0)
    }

    /** 拼上上次的余量，只交出整帧部分。 */
    private fun alignOut(buf: ByteArray, len: Int): ByteArray? {
        val frameBytes = outChannels.coerceAtLeast(1) * 2
        val merged = if (carry.isEmpty()) {
            buf.copyOf(len)
        } else {
            ByteArray(carry.size + len).also {
                System.arraycopy(carry, 0, it, 0, carry.size)
                System.arraycopy(buf, 0, it, carry.size, len)
            }
        }
        val usable = merged.size - merged.size % frameBytes
        if (usable <= 0) {
            carry = merged
            return null
        }
        carry = merged.copyOfRange(usable, merged.size)
        return merged.copyOf(usable)
    }

    private fun codecNameOf(mime: String): String = when {
        mime.contains("eac3", true) -> "eac3"
        mime.contains("ac3", true) -> "ac3"
        mime.contains("truehd", true) -> "truehd"
        mime.contains("dts", true) -> "dca"
        mime.contains("aac", true) || mime.contains("mp4a", true) -> "aac"
        mime.contains("flac", true) -> "flac"
        mime.contains("opus", true) -> "opus"
        mime.contains("vorbis", true) -> "vorbis"
        mime.contains("mpeg", true) -> "mp3"
        else -> mime.substringAfter('/')
    }
}
