package com.doudou.p5.sm

/**
 * P5 自管解码 · 起播/跳转状态机（2026-11-21，豆豆）
 *
 * 为什么要有它：起播与跳转的问题全部出在「状态」而不在「编解码」——
 * 在飞标志没交还、闸门没打开、排队目标被丢、停不干净又开新连接。
 * 这些都能在不碰 Android、不碰头显的前提下用假实现跑出来。
 *
 * 这个类只做状态转移，不碰 MediaCodec/MediaExtractor/AudioTrack/Surface；
 * 真机上把 Port 的实现换成真实管线即可，状态机一行不改。
 */

interface VideoPort {
    fun open(sec: Double): Boolean
    fun firstFrameSec(): Double
    /** 停并释放；true = 两个线程都确认退出、连接已归还 */
    fun closeConfirmed(): Boolean
}

interface AudioPort {
    fun open(originSec: Double): Boolean
    fun start()
    /** true = 连接已归还 */
    fun closeConfirmed(): Boolean
}

interface ClockPort {
    fun pause()
    fun resetTo(sec: Double)
    /** 起播/放行：换发新令牌并开始走 */
    fun start(sec: Double): Long
    fun token(): Long
    /** 音频上报；令牌不匹配必须丢弃 */
    fun noteAudioPosition(us: Long, fromToken: Long): Boolean
    fun isRunning(): Boolean
    fun positionSec(): Double
}

interface ConnQuota {
    fun acquire(who: String): Boolean
    fun release()
    val used: Int
}

interface QueuePort {
    /** 队尾时间 − 播放位置（秒），不足为 0 */
    fun aheadSec(nowSec: Double): Double
}

class SelfPipeSession(
    private val video: VideoPort,
    private val audio: AudioPort,
    private val clock: ClockPort,
    private val conns: ConnQuota,
    private val queue: QueuePort,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val GATE_SEC = 2.0
        const val GATE_TIMEOUT_MS = 8_000L
        /** 只盯「首帧不来」的看门狗 */
        const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    }

    enum class Phase { IDLE, BUILDING, PLAYING, STOPPED }

    var phase = Phase.IDLE
        private set
    var bootInFlight = false
        private set
    var pendingJumpSec = -1.0
        private set
    var released = false
        private set
    var firstShown = false
        private set
    var audioReady = false
        private set
    var originSec = 0.0
        private set

    private var gen = 0
    private var startedAtMs = 0L
    /** 队列门槛超时是否已用掉（一代替只放一次） */
    private var gateTimedOut = false

    fun currentGen(): Int = gen

    // ---------------------------------------------------------------- 起播

    fun boot(startSec: Double, nowMs: Long): Boolean {
        if (bootInFlight) { log("起播被拒：已有起播在飞"); return false }
        if (!stopBothConfirmed()) { log("起播中止：旧管线未确认归还"); return false }
        gen++
        val myGen = gen
        bootInFlight = true
        startedAtMs = nowMs
        phase = Phase.BUILDING
        released = false
        firstShown = false
        audioReady = false
        gateTimedOut = false
        originSec = startSec
        pendingJumpSec = -1.0
        return build(myGen, startSec)
    }

    // ---------------------------------------------------------------- 跳转

    /** 已有在飞时只记最新目标（合并），不并发 */
    fun jump(sec: Double, nowMs: Long): Boolean {
        if (phase == Phase.STOPPED) { log("跳转被拒：已收场"); return false }
        if (bootInFlight) {
            pendingJumpSec = sec
            log("跳转排队：目标=" + "%.2f".format(sec))
            return false
        }
        // 单飞：认领与判定同一拍
        if (!stopBothConfirmed()) { log("跳转中止：旧管线未确认归还"); return false }
        gen++
        val myGen = gen
        bootInFlight = true
        startedAtMs = nowMs
        phase = Phase.BUILDING
        released = false
        firstShown = false
        audioReady = false
        gateTimedOut = false
        originSec = sec
        clock.pause()
        clock.resetTo(sec)
        log("跳转认领：目标=" + "%.2f".format(sec))
        return build(myGen, sec)
    }

    private fun build(myGen: Int, sec: Double): Boolean {
        if (myGen != gen) { log("构建作废：代数已变"); return false }
        if (!conns.acquire("video")) { abortBuild("画面连接被拒"); return false }
        if (!video.open(sec)) { conns.release(); abortBuild("画面打不开"); return false }
        val first = video.firstFrameSec()
        if (first >= 0.0) { originSec = first; firstShown = true }
        if (!conns.acquire("audio")) { abortBuild("音频连接被拒（画面连接已占）"); return false }
        audioReady = audio.open(originSec)
        if (!audioReady) { conns.release(); log("音频起不来：画面先播，声音缺失") }
        phase = Phase.PLAYING
        return true
    }

    private fun abortBuild(why: String) {
        log("构建中止：" + why)
        bootInFlight = false
        phase = Phase.IDLE
    }

    // ---------------------------------------------------------------- 放行

    fun tryRelease(nowSec: Double): Boolean {
        if (released) return false
        if (!firstShown) return false
        if (!audioReady) return false
        if (queue.aheadSec(nowSec) < GATE_SEC) return false
        commit("正常放行")
        return true
    }

    /** 队列门槛超时：放画面，音频若已就绪必须一起起动 */
    fun forceReleaseByGateTimeout() {
        if (released) return
        gateTimedOut = true
        commit("队列门槛超时放行")
    }

    /** 看门狗：只盯「首帧不来」，到点强制放行并交出在飞标志 */
    fun forceReleaseByWatchdog(nowMs: Long) {
        if (released) return
        if (nowMs - startedAtMs < FIRST_FRAME_TIMEOUT_MS) return
        log("看门狗：" + (nowMs - startedAtMs) + "ms 首帧未上屏 → 强制放行")
        commit("看门狗强制放行")
    }

    private fun commit(why: String) {
        released = true
        val tok = clock.start(originSec)
        if (audioReady) audio.start()
        // 放行成功 = 管线跑起来了，必须无条件交出在飞标志
        bootInFlight = false
        log(why + "：origin=" + "%.3f".format(originSec) + " 令牌=" + tok +
            "（音频" + (if (audioReady) "已起动" else "缺失") + "）")
        drainPending()
    }

    private fun drainPending() {
        val pj = pendingJumpSec
        if (pj < 0.0) return
        pendingJumpSec = -1.0
        log("执行排队目标：" + "%.2f".format(pj))
        jump(pj, startedAtMs)
    }

    // ---------------------------------------------------------------- 画面/音频

    /** 返回 true = 要求解码器内跳（自管路恒 false） */
    fun onFrame(ptsSec: Double, originFromPicture: Boolean = true): Boolean {
        val clockUs = (clock.positionSec() * 1_000_000.0).toLong()
        val early = (ptsSec * 1_000_000.0).toLong() - clockUs
        if (early < -500_000L && originFromPicture) {
            log("严重迟到 " + (-early / 1000) + "ms：自管路不内跳，直接上屏")
            return false
        }
        return early < -500_000L
    }

    fun reportAudioPosition(us: Long, tok: Long): Boolean = clock.noteAudioPosition(us, tok)

    // ---------------------------------------------------------------- 收场

    fun stopAll(): Boolean {
        gen++
        bootInFlight = false
        pendingJumpSec = -1.0
        phase = Phase.STOPPED
        clock.pause()
        return stopBothConfirmed()
    }

    private fun stopBothConfirmed(): Boolean {
        val v = video.closeConfirmed()
        val a = audio.closeConfirmed()
        if (v) conns.release()
        if (a) conns.release()
        return v && a
    }
}
