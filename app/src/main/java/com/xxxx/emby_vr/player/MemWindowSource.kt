package com.xxxx.emby_vr.player

import android.media.MediaDataSource
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 内存窗数据源：给自管解码器做"内存缓冲"，不再让它逐次打开缓存文件。
 *
 * 为什么（父亲 2026-11-18 定）：
 *   中转把数据落成 4MB 一个文件，解码器原来每读 64KB 就要重新开一次文件、重新定位，
 *   四兆要开六十多次，播放因此发涩。改成：后台把中转的数据连续读进一块固定内存，
 *   解码器只从内存取；读走的那部分对应的中转文件由中转自己回收，网盘继续往前补。
 *
 * 关键约定：
 *  1. 内存窗只前进不后退；解码器要的位置不在窗内（跳转/回退）就整窗重建，从新位置重新读。
 *  2. 读与写各自持锁的一小段，拷贝在锁内（内存拷贝微秒级），不做磁盘 IO。
 *  3. 窗大小由 `pipe_mem_mb` 控制，默认 64MB（约 36 秒内容 @14Mbps）。
 */
class MemWindowSource(
    private val url: String,
    private val windowBytes: Int,
) : MediaDataSource() {

    private val lock = Any()
    private var buf = ByteArray(windowBytes)
    /** 窗内起始的媒体偏移 */
    private var baseOffset = 0L
    /** 窗内已填充字节数 */
    private var filled = 0
    /** 后台读线程要读的媒体偏移（与 baseOffset 不同=被要求跳转） */
    @Volatile private var readFrom = -1L
    @Volatile private var closed = false
    @Volatile private var totalSize = -1L

    private val filler = thread(name = "memwin-fill", isDaemon = true) { fillLoop() }

    private fun fillLoop() {
        while (!closed) {
            val start = readFrom
            if (start < 0L) {
                Thread.sleep(10)
                continue
            }
            readFrom = -1L
            var conn: HttpURLConnection? = null
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Range", "bytes=$start-")
                    setRequestProperty("User-Agent", "B0BEmbyMemWin/1.0")
                    connectTimeout = 20000
                    readTimeout = 60000
                }
                if (c.responseCode !in 200..206) {
                    c.disconnect()
                    Thread.sleep(200)
                    continue
                }
                conn = c
                c.inputStream.use { ins: InputStream ->
                    var pos = 0
                    while (!closed) {
                        val wantJump = readFrom >= 0L
                        if (wantJump) break
                        val room = synchronized(lock) { windowBytes - pos }
                        if (room <= 0) {
                            Thread.sleep(10)
                            continue
                        }
                        val n = ins.read(buf, pos, room)
                        if (n <= 0) break
                        synchronized(lock) {
                            pos += n
                            filled = pos
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("B0BEmbyVR-MemWin", "内存窗读流出错：${t.javaClass.simpleName}: ${t.message}")
                runCatching { Thread.sleep(200) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** 把窗挪到 position 并开始从那里读；直到窗内有数据才返回 */
    private fun reposition(position: Long) {
        synchronized(lock) {
            baseOffset = position
            filled = 0
        }
        readFrom = position
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline && !closed) {
            val have = synchronized(lock) { filled }
            if (have > 0) return
            Thread.sleep(5)
        }
    }

    override fun readAt(position: Long, out: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        var inWindow: Int
        synchronized(lock) {
            val rel = position - baseOffset
            inWindow = if (rel >= 0 && rel < filled) (filled - rel).coerceAtMost(size.toLong()).toInt() else -1
        }
        if (inWindow < 0) {
            reposition(position)
            synchronized(lock) {
                val rel = position - baseOffset
                inWindow = if (rel >= 0 && rel < filled) (filled - rel).coerceAtMost(size.toLong()).toInt() else -1
            }
            if (inWindow < 0) return -1
        }
        synchronized(lock) {
            val rel = (position - baseOffset).toInt()
            System.arraycopy(buf, rel, out, offset, inWindow)
        }
        return inWindow
    }

    override fun getSize(): Long {
        if (totalSize > 0) return totalSize
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = 15000
                readTimeout = 15000
            }
            c.responseCode
            val cr = c.getHeaderField("Content-Range") ?: ""
            c.disconnect()
            val v = Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            totalSize = v
            v
        } catch (t: Throwable) {
            -1L
        }
    }

    override fun close() {
        closed = true
        runCatching { filler.interrupt() }
    }
}
