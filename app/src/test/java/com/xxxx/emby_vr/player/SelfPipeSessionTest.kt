package com.doudou.p5.sm

/**
 * 状态机断言（2026-11-21，豆豆）
 *
 * 不依赖 JUnit / Android / 头显；直接 main 跑：
 *   kotlinc src/SelfPipeSession.kt test/SelfPipeSessionTest.kt -include-runtime -d sm.jar
 *   java -jar sm.jar
 * 全过打印 ALL PASS；任何一条失败以非零退出。
 *
 * 每条断言都对应一次真机上踩过的坑（括号里是现场）。
 */

class FakeVideo(var openOk: Boolean = true, var firstSec: Double = 0.0,
                var closeOk: Boolean = true) : VideoPort {
    var opens = 0
    override fun open(sec: Double): Boolean { opens++; return openOk }
    override fun firstFrameSec(): Double = firstSec
    override fun closeConfirmed(): Boolean = closeOk
}

class FakeAudio(var openOk: Boolean = true, var closeOk: Boolean = true) : AudioPort {
    var opens = 0
    var starts = 0
    override fun open(originSec: Double): Boolean { opens++; return openOk }
    override fun start() { starts++ }
    override fun closeConfirmed(): Boolean = closeOk
}

class FakeClock : ClockPort {
    private var tok = 0L
    private var running = false
    private var pos = 0.0
    override fun pause() { running = false }
    override fun resetTo(sec: Double) { pos = sec }
    override fun start(sec: Double): Long { tok++; pos = sec; running = true; return tok }
    override fun token(): Long = tok
    override fun noteAudioPosition(us: Long, fromToken: Long): Boolean {
        if (fromToken < 0L || fromToken != tok) return false
        pos = us / 1_000_000.0
        return true
    }
    override fun isRunning(): Boolean = running
    override fun positionSec(): Double = pos
}

class FakeConns(private val cap: Int = 2) : ConnQuota {
    override var used = 0
        private set
    override fun acquire(who: String): Boolean {
        if (used >= cap) return false
        used++; return true
    }
    override fun release() { if (used > 0) used-- }
}

class FakeQueue(var ahead: Double = 0.0) : QueuePort {
    override fun aheadSec(nowSec: Double): Double = ahead
}

object Assert {
    var pass = 0
    var fail = 0
    fun ok(name: String, cond: Boolean, detail: String = "") {
        if (cond) { pass++; println("PASS  " + name) }
        else { fail++; println("FAIL  " + name + (if (detail.isEmpty()) "" else "  → " + detail)) }
    }
}

/** 跑全部断言，返回失败条数（0 = 全过）。JUnit 包装与命令行两条入口都用它。 */
fun runAll(): Int {
    // T1 起播成功后必须交还在飞标志（r1594：标志从不清 → 左右跳全被挡）
    run {
        val s = SelfPipeSession(FakeVideo(), FakeAudio(), FakeClock(), FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0)
        s.tryRelease(0.0)
        Assert.ok("T1 起播放行后交还在飞标志", !s.bootInFlight, "bootInFlight=" + s.bootInFlight)
    }

    // T2 起播期间连跳：只记最新目标，不并发构建
    run {
        val v = FakeVideo(); val s = SelfPipeSession(v, FakeAudio(), FakeClock(), FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0)
        s.jump(300.0, 10); s.jump(330.0, 20)
        Assert.ok("T2 在飞期间只排队不并发", v.opens == 1 && s.pendingJumpSec == 330.0,
            "opens=" + v.opens + " pending=" + s.pendingJumpSec)
    }

    // T3 放行后执行排队目标（旧 bug：被单飞判据挡回、永久丢失）
    run {
        val v = FakeVideo(); val s = SelfPipeSession(v, FakeAudio(), FakeClock(), FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0)
        s.jump(300.0, 10)
        s.tryRelease(0.0)
        Assert.ok("T3 放行后执行排队目标", v.opens == 2 && s.pendingJumpSec < 0.0,
            "opens=" + v.opens + " pending=" + s.pendingJumpSec)
    }

    // T4 续播 2577s：门槛必须真的等 2 秒存货，不能因为"起点大"直接放行
    run {
        val q = FakeQueue(0.0); val s = SelfPipeSession(FakeVideo(firstSec = 2577.0), FakeAudio(), FakeClock(), FakeConns(), q)
        s.boot(2577.0, 0)
        val early = s.tryRelease(2577.0)
        q.ahead = 2.5
        val late = s.tryRelease(2577.0)
        Assert.ok("T4 续播门槛按存货判、不按绝对位置", !early && late, "early=" + early + " late=" + late)
    }

    // T5 旧管线没确认归还：一条新连接都不开
    run {
        val c = FakeConns(); val s = SelfPipeSession(FakeVideo(closeOk = false), FakeAudio(), FakeClock(), c, FakeQueue(3.0))
        val started = s.jump(100.0, 0)
        Assert.ok("T5 停不干净就不开新连接", !started && c.used == 0, "started=" + started + " used=" + c.used)
    }

    // T6 连接额度：第三路必须被拒，且不静默占住
    run {
        val c = FakeConns(2)
        val a = c.acquire("a"); val b = c.acquire("b"); val d = c.acquire("c")
        Assert.ok("T6 第三路连接被拒", a && b && !d && c.used == 2, "used=" + c.used)
    }

    // T7 重建期时钟冻结、放行才起走
    run {
        val clk = FakeClock()
        val s = SelfPipeSession(FakeVideo(firstSec = 300.0), FakeAudio(), clk, FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0); s.tryRelease(0.0)
        s.jump(600.0, 100)
        val duringBuild = clk.isRunning()
        s.tryRelease(600.0)
        Assert.ok("T7 重建期时钟不跑、放行才走", !duringBuild && clk.isRunning(),
            "during=" + duringBuild + " after=" + clk.isRunning())
    }

    // T8 令牌换发后旧上报必须被丢弃
    run {
        val clk = FakeClock()
        val s = SelfPipeSession(FakeVideo(), FakeAudio(), clk, FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0); s.tryRelease(0.0)
        val oldTok = clk.token()
        s.jump(600.0, 100); s.tryRelease(600.0)
        val acceptedOld = s.reportAudioPosition(1_000_000L, oldTok)
        val acceptedNew = s.reportAudioPosition(600_500_000L, clk.token())
        Assert.ok("T8 旧令牌上报被丢、新令牌被收", !acceptedOld && acceptedNew,
            "old=" + acceptedOld + " new=" + acceptedNew)
    }

    // T9 看门狗强制放行时，已备好的音频必须一起起动（否则有画无声）
    run {
        val a = FakeAudio(); val s = SelfPipeSession(FakeVideo(firstSec = 0.0), a, FakeClock(), FakeConns(), FakeQueue(0.0))
        s.boot(0.0, 0)                       // 首帧到了，队列没存货 → 正常路径放不了
        s.forceReleaseByWatchdog(9_000L)     // 8 秒看门狗
        Assert.ok("T9 看门狗强制放行时音频一起起动", s.released && a.starts == 1,
            "released=" + s.released + " starts=" + a.starts)
    }

    // T10 首帧不来时看门狗也要交还标志（否则之后跳不动）
    run {
        val v = FakeVideo(openOk = true); v.let { }
        val s = SelfPipeSession(FakeVideo(firstSec = -1.0), FakeAudio(), FakeClock(), FakeConns(), FakeQueue(0.0))
        s.boot(0.0, 0)
        s.forceReleaseByWatchdog(9_000L)
        Assert.ok("T10 首帧不来时看门狗交还标志", !s.bootInFlight && s.released,
            "bootInFlight=" + s.bootInFlight + " released=" + s.released)
    }

    // T11 收场后迟到的回调不生效（代数守卫）
    run {
        val s = SelfPipeSession(FakeVideo(), FakeAudio(), FakeClock(), FakeConns(), FakeQueue(3.0))
        s.boot(0.0, 0); s.tryRelease(0.0)
        val g = s.currentGen()
        s.stopAll()
        Assert.ok("T11 收场后代数变化、旧回调作废", s.currentGen() != g, "gen=" + s.currentGen())
    }

    println()
    println("PASS=" + Assert.pass + " FAIL=" + Assert.fail)
    if (Assert.fail > 0) println("SOME FAILED") else println("ALL PASS")
    return Assert.fail
}

/** 命令行入口（原 main 的行为：有失败就非零退出） */
fun main() {
    if (runAll() > 0) kotlin.system.exitProcess(1)
}
