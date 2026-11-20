package com.xxxx.emby_vr.player

import android.os.SystemClock
import android.util.Log

/**
 * 播放时钟。
 *
 * 谁决定"现在播到哪"：音频。写进音频设备的样本被硬件按采样率匀速放出来，
 * 这是整条链路里唯一有物理依据的节拍；画面跟着它走，就不存在两套时钟互相追。
 *
 * 音频停走时（欠载、跳转、暂停、设备还没起来）退回墙钟推进：这时若继续听音频位置，
 * 画面会冻在原地。两条路的切换只在 [positionUs] 一处判断，不各自维护，避免出现
 * "画面按音频走、进度条按墙钟走"这种两套口径。
 *
 * 单调：除 [resetTo] 之外位置绝不回退。回退是最扎眼的现象（进度条跳一下、画面重一下），
 * 宁可停一拍也不退。
 */
class PlaybackClock {

    companion object {
        private const val TAG = "PlaybackClock"

        /**
         * 音频位置多久没更新就判为停走。
         *
         * 正常写入时 AudioPipe 每写一块就报一次（几十毫秒级），所以 400 毫秒足够宽松；
         * 取小了会在设备刚起来时报错切墙钟，取大了音频卡住时画面会跟着冻住。
         */
        private const val STALE_MS = 400L

        /**
         * 音频位置落后墙钟多少算"卡住"。
         *
         * 正常播放时音频位置比墙钟慢一点（输出延迟 + 缓冲），实测在 200 毫秒内；
         * 差到半秒说明这一路已经停写，画面不能跟着停。
         */
        private const val LAG_TOLERANCE_US = 500_000L
    }

    private val lock = Any()

    @Volatile
    private var running = false

    /** 暂停时刻的位置；未开始时就是起播位置 */
    private var pausedAtUs = 0L

    /** 最近一次音频报上来的位置与它到达的时刻 */
    private var audioUs = 0L
    private var audioAtMs = 0L

    /** 墙钟基准：某个已知位置上、当时的系统时刻 */
    private var wallBaseUs = 0L
    private var wallBaseMs = 0L

    /** 已返回过的最大位置（单调保证） */
    private var lastUs = 0L

    /** 最近一次判定用的来源，供日志对账 */
    @Volatile
    var lastSource: String = "none"
        private set

    fun start(atUs: Long = 0L) {
        synchronized(lock) {
            pausedAtUs = atUs
            wallBaseUs = atUs
            wallBaseMs = now()
            audioAtMs = 0L
            lastUs = atUs
            running = true
            Log.i(TAG, "时钟开始：位置=${atUs / 1000}ms")
        }
    }

    fun pause() {
        val p = positionUs()
        synchronized(lock) {
            pausedAtUs = p
            running = false
            audioAtMs = 0L
            Log.i(TAG, "时钟暂停：${p / 1000}ms")
        }
    }

    fun resume() {
        synchronized(lock) {
            if (running) return
            wallBaseUs = pausedAtUs
            wallBaseMs = now()
            audioAtMs = 0L
            running = true
            Log.i(TAG, "时钟恢复：${pausedAtUs / 1000}ms")
        }
    }

    /** 跳转：两边基准一起重置，位置立刻落到目标 */
    fun resetTo(us: Long) {
        synchronized(lock) {
            pausedAtUs = us
            wallBaseUs = us
            wallBaseMs = now()
            audioUs = us
            audioAtMs = 0L
            lastUs = us
            Log.i(TAG, "时钟重置：${us / 1000}ms")
        }
    }

    /**
     * 音频位置上报（已经减掉输出延迟）。
     *
     * 由 [AudioPipe] 在每次写入后调用；位置语义是"这一帧音频现在真正被听到的位置"。
     */
    fun noteAudioPosition(us: Long) {
        synchronized(lock) {
            audioUs = us
            audioAtMs = now()
        }
    }

    fun isRunning(): Boolean = running

    fun positionUs(): Long {
        synchronized(lock) {
            if (!running) return pausedAtUs
            val nowMs = now()
            val sinceAudio = if (audioAtMs > 0L) nowMs - audioAtMs else Long.MAX_VALUE
            val wall = wallBaseUs + (nowMs - wallBaseMs) * 1000L
            val p: Long
            if (sinceAudio <= STALE_MS) {
                /*
                 * 音频新鲜。但它可能整体落后墙钟一大截（这一路停写了，只是刚才还报过一次），
                 * 那种情况按墙钟走，否则画面会跟着音频一起冻住。
                 */
                p = if (audioUs + LAG_TOLERANCE_US < wall) wall else audioUs
                lastSource = if (audioUs + LAG_TOLERANCE_US < wall) "wall(audio落后)" else "audio"
            } else {
                p = wall
                lastSource = "wall(音频未更新)"
            }
            if (p > lastUs) lastUs = p
            return lastUs
        }
    }

    fun positionSec(): Double = positionUs() / 1_000_000.0

    private fun now(): Long = SystemClock.elapsedRealtime()
}
