package com.xxxx.emby_vr.player

import android.util.Log
import androidx.media3.common.Player

/**
 * 播放接口统一层（父亲 2026-10-08）。
 *
 * ### 为什么要有它
 * 客户端现在有两种解码内核：
 *   · 普通片源 —— 系统播放器（ExoPlayer + MediaCodec）
 *   · 杜比视界 Profile 5 —— 自带的 mpv 内核（系统解不了这种片源）
 *
 * 但界面（控制条数据、银幕进度条、快进快退、拖进度条、暂停/继续、倍速）原来全都
 * 直接挂在系统播放器对象上。一换内核，播放器对象是空的，于是「按了没反应、进度条是空的」
 * —— 这不是某个按钮坏了，是界面和播放引擎之间少了一层。
 *
 * 这一层把「播放状态读取 + 播放操作」收敛成一组接口，界面只认这层，内部按当前哪条
 * 内核在跑分发。以后再加解码方式，只需要在这里多接一条实现，界面不用动。
 *
 * 取引用的方式：两个 provider（延迟求值），内核的创建与销毁仍由 MainActivity 按原样
 * 管理，这一层只做分发，不持生命周期。
 */
class PlaybackControl {

    companion object {
        private const val TAG = "B0BEmbyVR-Ctl"
    }

    /** 系统播放器（普通片源）。延迟求值，避免与 MainActivity 的属性初始化顺序耦合。 */
    var exoProvider: () -> Player? = { null }

    /** mpv 内核（杜比视界 Profile 5）。为空表示当前没走内核。 */
    var mpvProvider: () -> MpvBackend? = { null }

    /** 当前是否走内核 */
    val kernelActive: Boolean get() = mpvProvider() != null

    /** 现在有没有可操作的播放引擎 */
    fun hasEngine(): Boolean = kernelActive || exoProvider() != null

    fun isPlaying(): Boolean {
        mpvProvider()?.let { return !it.isPaused() }
        return exoProvider()?.playWhenReady ?: false
    }

    /** 暂停 / 继续。返回 false 表示当前没有可操作的引擎。 */
    fun togglePlayPause(): Boolean {
        mpvProvider()?.let { m ->
            val toPause = !m.isPaused()
            m.setPaused(toPause)
            Log.i(TAG, if (toPause) "内核：已暂停" else "内核：继续播放")
            return true
        }
        val p = exoProvider() ?: return false
        p.playWhenReady = !p.playWhenReady
        Log.i(TAG, if (p.playWhenReady) "继续播放" else "已暂停")
        return true
    }

    fun positionMs(): Long {
        mpvProvider()?.let { return (it.positionSec() * 1000.0).toLong() }
        return exoProvider()?.currentPosition ?: 0L
    }

    fun durationMs(): Long {
        mpvProvider()?.let {
            val d = (it.durationSec() * 1000.0).toLong()
            return if (d > 0L) d else 0L
        }
        return exoProvider()?.duration ?: 0L
    }

    /**
     * 缓冲进度。
     *
     * 内核不报自己的缓冲位置（mpv 有 demuxer-cache-time，精度一般），用当前位置兜底，
     * 进度条上那一段浅色不至于空着。
     */
    fun bufferedMs(): Long {
        mpvProvider()?.let { return positionMs() }
        return exoProvider()?.bufferedPosition ?: 0L
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        mpvProvider()?.let {
            it.seekToSec(target / 1000.0)
            return
        }
        exoProvider()?.seekTo(target)
    }

    fun setSpeed(speed: Float) {
        mpvProvider()?.setSpeed(speed)
        exoProvider()?.setPlaybackSpeed(speed)
    }
}
