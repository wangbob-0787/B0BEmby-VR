package com.xxxx.emby_vr.player

import android.util.Log

/**
 * 样本队列（照 ffplay 的 `AVPacketQueue` 抄的结构，2026-11-21）。
 *
 * ffplay 的做法只有三条：
 *  1. 一个读线程把解出来的包塞进队列，一个解码线程从队列取；
 *  2. 队列按**字节**封顶，塞满了读线程就等着（不是丢最旧的）；
 *  3. 跳转时整队清空，并唤醒两端。
 *
 * 为什么不是"缓存原始字节"：原始字节那层要求能任意位置随机读（解封装要读文件尾索引），
 * 我们两次都栽在那里。到这一层，每个元素是"一帧已经解出来的样本 + 它的时间戳"，
 * 顺序读到底就行，不存在随机访问的问题 —— 缓冲也一样生效。
 */
class SampleQueue(private val maxBytes: Int) {

    companion object {
        private const val TAG = "SampleQueue"
    }

    /** 一帧样本：数据、字节数、片源时间戳（微秒） */
    class Packet(val data: ByteArray, val size: Int, val ptsUs: Long)

    private val lock = Object()
    private val deque = ArrayDeque<Packet>()
    private var bytes = 0
    private var eos = false
    private var aborted = false

    @Volatile
    var droppedAfterAbort = 0L
        private set

    /**
     * 队尾那一帧的时间戳（微秒），没有数据时为 -1。
     *
     * 水位用「队尾 pts 减当前播放位置」算最准：不用估码率，低码率片也不会被高估。
     */
    @Volatile
    var lastPtsUs: Long = -1L
        private set

    /** 塞一帧；队列满了就等（ffplay 的 SDL_CondWait 等价） */
    fun put(packet: Packet): Boolean {
        synchronized(lock) {
            while (!aborted && bytes + packet.size > maxBytes && deque.isNotEmpty()) {
                try {
                    lock.wait(200)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (aborted) {
                droppedAfterAbort++
                return false
            }
            deque.addLast(packet)
            bytes += packet.size
            if (packet.ptsUs > lastPtsUs) lastPtsUs = packet.ptsUs
            lock.notifyAll()
            return true
        }
    }

    /** 取一帧；空了就等。返回 null = 结束或中止 */
    fun get(): Packet? {
        synchronized(lock) {
            while (!aborted && deque.isEmpty() && !eos) {
                try {
                    lock.wait(200)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (aborted) return null
            val p = deque.removeFirstOrNull()
            if (p != null) {
                bytes -= p.size
                lock.notifyAll()
            }
            return p
        }
    }

    fun putEos() {
        synchronized(lock) {
            eos = true
            lock.notifyAll()
        }
    }

    /** 跳转用：整队清空（ffplay 的 packet_queue_flush） */
    fun flush() {
        synchronized(lock) {
            deque.clear()
            bytes = 0
            /* 水位是"队尾时间戳"，清队必须一起清，否则跳转后用水位判断会读到旧队列的值 */
            lastPtsUs = -1L
            eos = false
            lock.notifyAll()
        }
    }

    /** 重新启用（同一实例复用时调用：stop() 会把队列置成中止，起播前必须解开） */
    fun reset() {
        synchronized(lock) {
            deque.clear()
            bytes = 0
            lastPtsUs = -1L
            eos = false
            aborted = false
            lock.notifyAll()
        }
    }

    fun abort() {
        synchronized(lock) {
            aborted = true
            lock.notifyAll()
        }
    }

    fun sizeBytes(): Int = synchronized(lock) { bytes }
    fun isEos(): Boolean = synchronized(lock) { eos && deque.isEmpty() }

    fun logState(tag: String) {
        synchronized(lock) {
            Log.i(TAG, "$tag 队列=${deque.size}帧 ${bytes / 1024}KB / 上限 ${maxBytes / 1024}KB")
        }
    }
}
