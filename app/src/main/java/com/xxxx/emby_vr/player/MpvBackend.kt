package com.xxxx.emby_vr.player

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mpv 解码内核（父亲 2026-10-08）。
 *
 * ### 为什么要有它
 * 系统解码器（MediaCodec / Media3）吃不下杜比视界的 H.265 流：装了《挑情丑闻》
 * 只出声音不出画面。而 PICO 上 4XVR 能正常播、颜色也对 —— 说明片源、网盘、网络
 * 都没问题，差在客户端解码能力。4XVR 的做法是自带解码内核（它包里就是 ffmpeg）。
 * 这里走同一条路：libmpv（FFmpeg 驱动，包 `dev.jdtech.mpv:libmpv:0.4.1`）。
 *
 * ### 接入方式
 * 和 ExoPlayer 一样，我们只给它一块 Surface —— 那块 Surface 背后是原生层的 OES
 * 纹理，最终贴到 VR 银幕上。所以接法就是 `attachSurface(同一个 surface)`。
 *
 * ### 与 ExoPlayer 的分工
 * 默认仍走 ExoPlayer（性能好、字幕与弹幕的时间轴现成）。只有系统解码器明确
 * 吃不下（杜比视界）时，才切到这条内核。
 *
 * 注意：MPVLib 是静态单例（native 层一份实例），本类只做参数与命令封装。
 */
class MpvBackend(private val context: Context) {

    companion object {
        private const val TAG = "B0BEmbyVR-Mpv"

        /** 是否已初始化过（MPVLib 是静态的，重复 create 会重复建实例） */
        private val created = AtomicBoolean(false)

        /**
         * 初始化内核。幂等，重复调用直接返回。
         */
        fun ensureCreated(context: Context) {
            if (created.compareAndSet(false, true)) {
                try {
                    MPVLib.create(context.applicationContext)
                    /*
                     * 画面输出（2026-10-08 实测调整）。
                     *
                     * 第一版用 `vo=gpu` + `gpu-context=android`：内核起来了、片源也认对了，
                     * 但银幕上什么都没有 —— gpu 渲染器要自己建 EGL 上下文画进 Surface，
                     * 在 OpenXR 这种合成环境里这条路没走通。
                     *
                     * 改成 mediacodec_embed：解码结果直接交给系统的硬解输出通道，
                     * 由它把画面吐到我们给的 Surface。PICO 上 4XVR 能正常播杜比视界，
                     * 说明这套硬件解码器本来就能解 —— 走这条通道正好用上它。
                     */
                    MPVLib.setOptionString("vo", "mediacodec_embed")
                    /*
                     * 画面输出：mediacodec_embed（解码结果直接交给系统硬解输出通道吐到 Surface）。
                     *
                     * 2026-10-08 实测记录（给下一次攻颜色的人）：
                     * · `vo=gpu` + `gpu-context=android` + 软解 → 银幕上全是粉/蓝纯色不断闪，
                     *   渲染器出来了但内容不对，这条路当天没走通，已回退。
                     * · `vo=mediacodec_embed` + 硬解 → 画面正常、4K 流畅，但杜比视界偏色
                     *   （解码器报 bt.2020-ncl/bt.2020/bt.1886，DV 自己的 IPTPQc2 没被认出来，
                     *   等于按普通 HDR 送出去，没有 HDR→SDR 映射）。
                     * 下次攻颜色优先试：软解 + `vo=gpu` 但换 `gpu-api`/`gpu-context` 组合，
                     * 或给 mediacodec_embed 补 `--target-colorspace-hint` 之外的映射手段。
                     */
                    MPVLib.setOptionString("hwdec", "mediacodec")
                    // 杜比视界 / HDR 片源按色域提示交给显示端（否则偏色更明显）
                    MPVLib.setOptionString("target-colorspace-hint", "yes")
                    // 字幕我们自己画（弹幕层带字幕位），别让 mpv 再画一遍
                    MPVLib.setOptionString("sub-auto", "no")
                    MPVLib.setOptionString("sid", "no")
                    // 静音状态由我们控制，先不静音
                    MPVLib.setOptionString("mute", "no")
                    // 把内核自己的日志接到 logcat：出问题时能直接看它内部报什么
                    MPVLib.addLogObserver { prefix, level, text ->
                        Log.i(TAG, "mpv[$level] ${prefix ?: ""}$text")
                    }
                    MPVLib.init()
                    Log.i(TAG, "mpv 内核已就绪（gpu 渲染器 + 软解兜底）")
                } catch (t: Throwable) {
                    created.set(false)
                    Log.e(TAG, "mpv 内核初始化失败: ${t.message}")
                }
            }
        }
    }

    private var surfaceAttached = false

    /**
     * 把画面输出接到这块 Surface（背后是原生层 OES 纹理 → VR 银幕）。
     */
    fun attachSurface(surface: Surface?) {
        ensureCreated(context)
        try {
            if (surface == null) {
                MPVLib.detachSurface()
                surfaceAttached = false
            } else {
                MPVLib.attachSurface(surface)
                surfaceAttached = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "接画面输出失败: ${t.message}")
        }
    }

    /**
     * 播一个地址。[startSec] 是起播位置（秒）。
     */
    fun play(url: String, startSec: Double = 0.0) {
        ensureCreated(context)
        try {
            MPVLib.command(arrayOf("loadfile", url, "replace"))
            if (startSec > 0.5) {
                // 起播位置：% 是百分比定位，秒数绝对定位更稳
                MPVLib.command(arrayOf("seek", startSec.toString(), "absolute+exact"))
            }
            setPaused(false)
        } catch (t: Throwable) {
            Log.e(TAG, "起播失败: ${t.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        try { MPVLib.setPropertyBoolean("pause", paused) } catch (_: Throwable) {}
    }

    fun isPaused(): Boolean = try { MPVLib.getPropertyBoolean("pause") ?: false } catch (_: Throwable) { false }

    /** 播放位置（秒） */
    fun positionSec(): Double = try { MPVLib.getPropertyDouble("time-pos") ?: 0.0 } catch (_: Throwable) { 0.0 }

    /** 总时长（秒） */
    fun durationSec(): Double = try { MPVLib.getPropertyDouble("duration") ?: 0.0 } catch (_: Throwable) { 0.0 }

    fun seekToSec(sec: Double) {
        try { MPVLib.command(arrayOf("seek", sec.toString(), "absolute")) } catch (_: Throwable) {}
    }

    fun setSpeed(speed: Float) {
        try { MPVLib.setPropertyDouble("speed", speed.toDouble()) } catch (_: Throwable) {}
    }

    /** 是否已经吃进流、开始出画 */
    fun hasVideo(): Boolean = try { MPVLib.getPropertyBoolean("video") ?: false } catch (_: Throwable) { false }

    fun stop() {
        try { MPVLib.command(arrayOf("stop")) } catch (_: Throwable) {}
        try {
            MPVLib.detachSurface()
            surfaceAttached = false
        } catch (_: Throwable) {}
    }
}
