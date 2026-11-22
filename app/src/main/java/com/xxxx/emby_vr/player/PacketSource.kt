package com.xxxx.emby_vr.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log

/**
 * 一路码流的包来源。
 *
 * "打开数据源、选轨、按需跳转、把样本一包一包取出来"这件事有两种做法：系统解封装
 * （MediaExtractor，一次只读一条轨）与一次解封装全包（avformat，一条连接同时出画面/
 * 声音/字幕）。做法不同，上层读循环、队列、解码器完全一样，所以把"喂包"收成这个接口，
 * Pipe 只认接口 —— 换做法不动 Pipe。
 *
 * 线程约束：除 [flush] 外，所有方法只在对应 Pipe 的读线程里调用；[next] 允许阻塞
 * （网络读、队列等待），停止由 [close] 或实现自己的中止标志打断。
 */
interface PacketSource {

    /** 准备阶段产出的轨道格式；全包档没有 MediaFormat，返回 null */
    val format: MediaFormat?

    /** 最近一次失败的说明；[open] 返回 false 时必须给出原因。
     *  不叫 error：实现类里还会用 kotlin.error() 做断言，同名会让那处编译失败。
     */
    val lastError: String?

    /** 打开数据源、选轨、定位到起点 */
    fun open(): Boolean

    /** 下一个样本；null = 本路结束 */
    fun next(): Sample?

    /** 跳到 [sec] 秒。落在哪一帧由实现决定（各实现的定位粒度不同） */
    fun seekTo(sec: Double)

    /**
     * 只清本侧的本地状态（刚跳过标志、重试计数）；队列归 Pipe，不许在这里碰。
     *
     * **不许在 seekTo 之后调用**：刚跳过的那段"读不到数据不算结尾"的重试窗口
     * 就靠这个标志撑着，清掉等于把窗口关掉（2026-11-21 实测：seek 后立刻读会返回 -1）。
     */
    fun flush()

    /** 释放连接 */
    fun close()

    /** 这一路用的是哪种解封装（只用于日志对账：off 档与全包档要能分开） */
    val name: String get() = ""

    /** 解封装落点的样本时间（微秒）；-1 = 未知 */
    val startUs: Long get() = -1L

    /** 容器声明的码率（bps），0 = 未知 */
    val bitRate: Int get() = 0

    /** 片长（秒），0 = 未知 */
    val durationSec: Double get() = 0.0

    /** 读缓冲当前大小（字节），只用于日志对账 */
    val bufferBytes: Int get() = 0

    /** 上一包之后这一路是否已不可用：真结尾为 false，读坏/推进失败为 true */
    val endedByError: Boolean get() = false

    /** 内存窗口预算（字节）。不走内存窗口的实现忽略它 */
    fun configureBudget(bytes: Int) {}

    /** 收尾诊断：真读完与读失败要能分开 */
    fun endDetail(): String = ""

    /** 一包样本：数据 + 片源时间戳（微秒） */
    class Sample(val data: ByteArray, val ptsUs: Long)
}

/**
 * 系统解封装 —— 画面这一路。
 *
 * 打开、选轨、定位、读样本全部在这里，Pipe 的解码线程不再碰解封装。
 * 读缓冲先给 4MB，装不下按帧实际大小扩：4K P5 的关键帧常到 2~4MB，固定 2MB 时接口
 * 回 -1，与"读完了"一模一样，读线程一帧不读就退出（2026-11-21 实测）。
 */
class ExtractorVideoSource(
    private val tag: String,
    private val url: String,
    private val headers: Map<String, String>,
    private val memWindowSec: Int,
    private val startSec: Double,
) : PacketSource {

    override var format: MediaFormat? = null
        private set
    override var lastError: String? = null
        private set

    override var bitRate: Int = 0
        private set

    override var durationSec: Double = 0.0
        private set

    /** 内存窗口档位下的数据源（要按码率配预算）；直读档为 null */
    var memSource: MemWindowSource? = null
        private set

    override var bufferBytes: Int = 4 * 1024 * 1024
        private set

    override var endedByError: Boolean = false
        private set

    private var ex: MediaExtractor? = null
    private var arr = ByteArray(4 * 1024 * 1024)
    private var bb = java.nio.ByteBuffer.wrap(arr)
    private var justSought = false
    private var retries = 0
    private var lastSize = -1

    override fun open(): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(tag, "解封装准备开始：url=${url.take(80)} 头=${headers.size} 个 起点=${"%.1f".format(startSec)}s")
        val e = MediaExtractor()
        try {
            if (memWindowSec > 0 && headers.isEmpty()) {
                val m = MemWindowSource(url, 8 * 1024 * 1024)
                memSource = m
                e.setDataSource(m)
            } else if (headers.isEmpty()) {
                e.setDataSource(url)
            } else {
                e.setDataSource(url, headers)
            }
            Log.i(tag, "数据源已打开：耗时=${SystemClock.elapsedRealtime() - t0}ms 轨道数=${e.trackCount}")
            var trackIndex = -1
            var fmt: MediaFormat? = null
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    fmt = f
                    break
                }
            }
            if (trackIndex < 0 || fmt == null) {
                lastError = "容器里没有视频轨"
                e.release()
                return false
            }
            e.selectTrack(trackIndex)
            bitRate = runCatching { fmt.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
            Log.i(
                tag,
                "选轨完成：轨=$trackIndex 编码=${fmt.getString(MediaFormat.KEY_MIME)} " +
                    "${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)} " +
                    "码率=${bitRate / 1000}kbps",
            )
            durationSec = runCatching {
                val us = fmt.getLong(MediaFormat.KEY_DURATION)
                if (us > 0L) us / 1_000_000.0 else 0.0
            }.getOrDefault(0.0)
            Log.i(tag, "片长：${"%.1f".format(durationSec)}s（来自容器头）")
            if (startSec > 0.5) {
                e.seekTo((startSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                justSought = true
                Log.i(tag, "跳到 ${"%.1f".format(startSec)} 秒起播")
            }
            ex = e
            this.format = fmt
            bufferBytes = arr.size
            Log.i(
                tag,
                "解封装准备完成：总耗时=${SystemClock.elapsedRealtime() - t0}ms " +
                    "首样本大小=${e.sampleSize} 首样本时间=${e.sampleTime}",
            )
            return true
        } catch (t: Throwable) {
            lastError = "打开输入失败：${t.javaClass.simpleName}: ${t.message}"
            runCatching { e.release() }
            return false
        }
    }

    override fun next(): PacketSource.Sample? {
        val e = ex ?: return null
        bb.clear()
        retries = 0
        var size = -1
        while (true) {
            size = try {
                e.readSampleData(bb, 0)
            } catch (t: Throwable) {
                Log.w(tag, "读样本异常：${t.javaClass.simpleName}: ${t.message}")
                -1
            }
            if (size >= 0) break
            /* 装不下就按帧大小扩缓冲，重读一次 */
            val need = e.sampleSize          /* 长整型：接口给的是 Long */
            if (need > bb.capacity().toLong()) {
                arr = ByteArray((need + 1024L * 1024L).toInt())
                bb = java.nio.ByteBuffer.wrap(arr)
                bufferBytes = arr.size
                bb.clear()
                Log.i(tag, "读缓冲扩到 ${arr.size / 1048576}MB（这一帧需要 $need 字节）")
                size = try {
                    e.readSampleData(bb, 0)
                } catch (t: Throwable) {
                    -1
                }
                if (size >= 0) break
            }
            if (!justSought) break
            /* 刚跳过：给解封装一点时间把定位做完，最多等两秒 */
            if (retries++ >= 100) break
            Thread.sleep(20)
        }
        lastSize = size
        if (size >= 0) {
            justSought = false
            retries = 0
        }
        if (size < 0) return null
        val pts = try {
            e.sampleTime
        } catch (t: Throwable) {
            Log.w(tag, "取样本时间失败（解封装已失效？）：${t.javaClass.simpleName}")
            endedByError = true
            return null
        }
        val data = ByteArray(size)
        bb.position(0)
        bb.get(data, 0, size)
        try {
            e.advance()
        } catch (t: Throwable) {
            /* 这一包已经读出来了，照常交给解码线程；但解封装不能再往下走 */
            Log.w(tag, "推进样本失败：${t.javaClass.simpleName}")
            endedByError = true
        }
        return PacketSource.Sample(data, pts)
    }

    override fun seekTo(sec: Double) {
        val e = ex ?: return
        runCatching { e.seekTo((sec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
        justSought = true
        retries = 0
    }

    override fun flush() {
        justSought = false
        retries = 0
    }

    override fun close() {
        runCatching { ex?.release() }
        ex = null
    }

    override fun configureBudget(bytes: Int) {
        memSource?.configureBudget(bytes)
    }

    override fun endDetail(): String = buildString {
        append("本帧返回=$lastSize")
        append(" 样本大小=").append(runCatching { ex?.sampleSize }.getOrDefault(-1L))
        append(" 缓冲=${arr.size / 1048576}MB 重试=$retries 刚跳过=$justSought")
        append(" 样本时间=").append(runCatching { ex?.sampleTime }.getOrDefault(-1L))
        append(" 轨道=").append(runCatching { ex?.sampleTrackIndex }.getOrDefault(-1))
    }
}

/**
 * 系统解封装 —— 声音这一路。
 *
 * 选哪条轨由 Pipe 传进来的 [chooseTrack] 决定（按可解码性排序的逻辑留在 Pipe，
 * 它要跟着后端偏好一起变）。其余与画面那一路同理，只是定位粒度用最近关键帧：
 * 音频包小、连续，用"上一关键帧"会把起点拖远。
 */
class ExtractorAudioSource(
    private val tag: String,
    private val url: String,
    private val headers: Map<String, String>,
    private val memWindowSec: Int,
    private val startSec: Double,
    private val chooseTrack: (MediaExtractor) -> Int,
) : PacketSource {

    override val name: String get() = "MediaExtractor"
    override var startUs: Long = -1L
        private set

    override var format: MediaFormat? = null
        private set
    override var lastError: String? = null
        private set

    /** 选中的音轨序号 */
    var trackIndex: Int = -1
        private set

    override var endedByError: Boolean = false
        private set

    private var ex: MediaExtractor? = null
    private var arr = ByteArray(1024 * 1024)
    private var bb = java.nio.ByteBuffer.wrap(arr)
    private var justSought = false
    private var retries = 0

    override fun open(): Boolean {
        val e = MediaExtractor()
        try {
            val hdrs = headers
            if (memWindowSec > 0 && hdrs.isEmpty()) {
                e.setDataSource(MemWindowSource(url, 32 * 1024 * 1024))
            } else if (hdrs.isEmpty()) {
                e.setDataSource(url)
            } else {
                e.setDataSource(url, hdrs)
            }
            val pick = chooseTrack(e)
            if (pick < 0) {
                lastError = "没有可解的音轨"
                e.release()
                return false
            }
            val fmt = e.getTrackFormat(pick)
            e.selectTrack(pick)
            if (startSec > 0.5) {
                e.seekTo((startSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                justSought = true
            }
            startUs = e.sampleTime
            format = fmt
            trackIndex = pick
            ex = e
            Log.i(
                tag,
                "音频读线程就绪：轨道=$pick 起点=${"%.3f".format(startUs.coerceAtLeast(0L) / 1_000_000.0)}s",
            )
            return true
        } catch (t: Throwable) {
            lastError = "音频解封装异常：${t.javaClass.simpleName}: ${t.message}"
            runCatching { e.release() }
            return false
        }
    }

    override fun next(): PacketSource.Sample? {
        val e = ex ?: return null
        bb.clear()
        retries = 0
        var size = -1
        while (true) {
            size = try {
                e.readSampleData(bb, 0)
            } catch (t: Throwable) {
                -1
            }
            if (size >= 0) break
            /* 装不下就按帧大小扩缓冲（音频帧通常很小，这里只是兜底） */
            val need = runCatching { e.sampleSize }.getOrDefault(0L)
            if (need > bb.capacity().toLong()) {
                arr = ByteArray((need + 1024L * 1024L).toInt())
                bb = java.nio.ByteBuffer.wrap(arr)
                bb.clear()
                size = try {
                    e.readSampleData(bb, 0)
                } catch (t: Throwable) {
                    -1
                }
                if (size >= 0) break
            }
            if (!justSought) break
            if (retries++ >= 100) break
            Thread.sleep(20)
        }
        if (size >= 0) justSought = false
        if (size < 0) return null
        val pts = try {
            e.sampleTime
        } catch (t: Throwable) {
            endedByError = true
            return null
        }
        val data = ByteArray(size)
        bb.position(0)
        bb.get(data, 0, size)
        try {
            e.advance()
        } catch (t: Throwable) {
            endedByError = true
        }
        return PacketSource.Sample(data, pts)
    }

    override fun seekTo(sec: Double) {
        val e = ex ?: return
        runCatching { e.seekTo((sec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
        justSought = true
        retries = 0
        startUs = runCatching { e.sampleTime }.getOrDefault(-1L)
    }

    override fun flush() {
        justSought = false
        retries = 0
    }

    override fun close() {
        runCatching { ex?.release() }
        ex = null
    }
}
