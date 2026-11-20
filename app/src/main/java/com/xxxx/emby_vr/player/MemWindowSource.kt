package com.xxxx.emby_vr.player

import android.media.MediaDataSource
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内存缓冲数据源：解码器要哪一段，就现去网盘拉那一段，拉回来放在内存里。
 *
 * 父亲 2026-11-21 定的做法（不是磁盘缓存，也不是"从起播点顺序往后抓"）：
 *   · 读什么由解码器决定 —— 它要文件头就要头、要尾部索引就要尾部；
 *   · 拉回来的按块留在内存里，第二次要同一块直接从内存给，不再碰网络。
 *
 * 为什么不能做成"从头往后一条线地读"（上一版就是这样，实测失败）：
 * 解封装第一步往往要读文件尾部的索引，顺序窗口里根本没有那块，于是判定"容器里没有视频轨"，
 * 起播直接停住。所以必须按请求位置去取，而不是替解码器决定读的顺序。
 *
 * 块大小与预算：块 256KB（一次网络往返拿得动、内存碎片小）；
 * 预算由上层按"缓冲秒数 × 码率"给，超了按最久未用淘汰。
 */
class MemWindowSource(
    private val url: String,
    private var budgetBytes: Int,
) : MediaDataSource() {

    companion object {
        private const val TAG = "MemWindowSource"
        /** 一块的大小：太小则往返次数多，太大则随机访问时白拉 */
        private const val BLOCK = 256 * 1024
        /** 预热不让一次拉太多：解码器一次只要几十 KB 到几 MB */
        private const val FETCH_TIMEOUT_MS = 15_000
    }

    private val lock = Any()

    /** 块号 → 数据；按访问顺序淘汰（accessOrder = true） */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {}

    @Volatile
    private var totalSize = -1L

    @Volatile
    private var closed = false

    /** 统计：命中 / 回源次数与字节，排问题时看这个 */
    @Volatile
    var hits = 0L
        private set
    @Volatile
    var misses = 0L
        private set
    @Volatile
    var fetchedBytes = 0L
        private set

    override fun readAt(position: Long, out: ByteArray, offset: Int, size: Int): Int {
        if (closed || position < 0L || size <= 0) return -1
        val total = ensureSize()
        if (total >= 0L && position >= total) return -1

        var pos = position
        var done = 0
        while (done < size) {
            if (total >= 0L && pos >= total) break
            val blockIndex = pos / BLOCK
            val blockOffset = (pos % BLOCK).toInt()
            val block = block(blockIndex) ?: break
            val room = minOf(BLOCK - blockOffset, size - done)
            val avail = if (total >= 0L) minOf(room.toLong(), total - pos).toInt() else room
            if (avail <= 0) break
            System.arraycopy(block, blockOffset, out, offset + done, avail)
            done += avail
            pos += avail
        }
        return if (done > 0) done else -1
    }

    override fun getSize(): Long = ensureSize()

    override fun close() {
        closed = true
        synchronized(lock) { blocks.clear() }
    }

    /** 预算变了（上层按码率算好之后调用）：只记账，超出的由淘汰自然处理 */
    fun configureBudget(bytes: Int) {
        synchronized(lock) {
            budgetBytes = bytes
            evictIfNeeded()
        }
        Log.i(TAG, "内存预算设为 ${bytes / 1048576}MB（块 ${BLOCK / 1024}KB）")
    }

    // ---------------------------------------------------------------- 内部

    private fun block(index: Long): ByteArray? {
        synchronized(lock) {
            blocks[index]?.let {
                hits++
                return it
            }
        }
        /* 不在内存里：现去网盘拉这一段（只拉这一段，不替解码器预判顺序） */
        val from = index * BLOCK
        val total = ensureSize()
        val to = if (total > 0L) minOf(from + BLOCK, total) - 1 else from + BLOCK - 1
        if (to < from) return null
        val data = fetchRange(from, to) ?: return null
        misses++
        fetchedBytes += data.size.toLong()
        synchronized(lock) {
            blocks[index] = data
            evictIfNeeded()
        }
        return data
    }

    private fun evictIfNeeded() {
        val maxBlocks = (budgetBytes / BLOCK).coerceAtLeast(8)
        while (blocks.size > maxBlocks) {
            val it = blocks.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    private fun ensureSize(): Long {
        if (totalSize >= 0L) return totalSize
        synchronized(lock) {
            if (totalSize >= 0L) return totalSize
            totalSize = probeSize()
            Log.i(TAG, "文件大小：${if (totalSize > 0) "${totalSize / 1048576}MB" else "未知"}")
            return totalSize
        }
    }

    private fun probeSize(): Long {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
            }
            val range = c.getHeaderField("Content-Range")
            val len = c.contentLengthLong
            c.disconnect()
            /* Content-Range: bytes 0-0/12345678 → 取斜杠后面的总数 */
            range?.substringAfterLast('/')?.toLongOrNull() ?: if (len > 0) len else -1L
        } catch (t: Throwable) {
            Log.w(TAG, "取文件大小失败：${t.message}")
            -1L
        }
    }

    private fun fetchRange(from: Long, to: Long): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=$from-$to")
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code != 206 && code != 200) {
                Log.w(TAG, "回源 HTTP $code（$from-$to）")
                conn.disconnect()
                return null
            }
            val expect = (to - from + 1).toInt()
            val out = ByteArray(expect)
            var filled = 0
            conn.inputStream.use { input: InputStream ->
                while (filled < expect) {
                    val n = input.read(out, filled, expect - filled)
                    if (n <= 0) break
                    filled += n
                }
            }
            conn.disconnect()
            if (filled <= 0) null else if (filled == expect) out else out.copyOf(filled)
        } catch (t: Throwable) {
            Log.w(TAG, "回源失败（$from-$to）：${t.message}")
            runCatching { conn?.disconnect() }
            null
        }
    }
}
