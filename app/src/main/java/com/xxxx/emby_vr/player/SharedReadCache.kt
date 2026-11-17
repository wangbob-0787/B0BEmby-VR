package com.xxxx.emby_vr.player

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 应用内共享读缓存（本机中转，127.0.0.1:8898）。
 *
 * 结构（父亲 2026-11-18 定稿）：
 *  1. **只有中转去网盘取**，内核对画面两条解码链都只读本机缓存，缺块就等，绝不直连网盘。
 *  2. 对网盘保持**两条长连接**顺序读，一直往前铺，直到缓存上限（400MB）。
 *  3. 缓存里只留**还没读的数据**：被读过、已经落在播放位置后面的块立即删除，把空间让给新数据。
 *  4. 跳转到远处（比如播到 30 分退到 5 分）→ **清空整个缓存**，从新位置重新往前铺。
 *
 * 为什么这么定：网盘对同一文件并发上限约两路（实测三路起丢块、四路起被拒），
 * 而 4K 片源单块读取要 1.6 秒（Emby 每次都要重新打开文件），
 * 所以必须"少而稳地读、读在前面、读过的立刻让位"。
 */
object SharedReadCache {

    private const val TAG = "B0BEmbyVR-Relay"
    private const val PORT = 8898
    private const val BLOCK = 16 * 1024 * 1024L
    private const val CAPACITY_BYTES = 400L * 1024 * 1024
    private const val UA = "B0BEmbyRelay/1.0"
    /** 缓存半满时才开始回收旧块，避免刚读过的块立刻被删 */
    private const val KEEP_BEHIND_BLOCKS = 1L

    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    private var cacheDir: File? = null

    private val index = LinkedHashMap<String, Long>()
    private val lock = Any()
    private var used = 0L
    private val inflight = ConcurrentHashMap<String, Object>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val upstreamBytes = AtomicLong()

    fun enabled(): Boolean = com.xxxx.emby_vr.vr.VrTuning.relay == 1

    fun start(ctx: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            cacheDir = File(ctx.cacheDir, "relay").apply { mkdirs() }
            /*
             * 启动先清空：分块大小/编号规则变过一次（4MB → 16MB），旧块会被当成新位置的数据，
             * 表现为"回源为零、播出来的内容不对或卡住"。缓存本来就是可丢弃的，直接清干净。
             */
            runCatching {
                cacheDir?.listFiles()?.forEach { it.delete() }
                Log.i(TAG, "启动清空旧缓存块")
            }
            try {
                server = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
                started = true
                thread(name = "relay-accept", isDaemon = true) { acceptLoop() }
                /* 两条填充线程 = 对网盘两条长连接 */
                for (i in 0..1) thread(name = "relay-fill-$i", isDaemon = true) { fillLoop(i) }
                Log.i(TAG, "共享读缓存已启动 127.0.0.1:$PORT 上限 ${CAPACITY_BYTES / 1048576}MB 两条长连接 ${cacheDir?.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "共享读缓存启动失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun rewrite(url: String): String {
        if (!enabled() || !started) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        return "http://127.0.0.1:$PORT/stream?url=" + URLEncoder.encode(url, "UTF-8")
    }

    /**
     * 跳转预热：把这个位置当成新的起点。
     *
     * 父亲 2026-11-18：跳到远处时**清空整个缓存**，从新位置重新往前铺 ——
     * 旧位置的数据对接下来没用，留着只会挤占空间。
     */
    fun warm(url: String, targetSec: Double, durationSec: Double) {
        if (!enabled() || !started) return
        if (durationSec <= 0.0 || targetSec < 0.0) return
        val inner = unwrap(url) ?: return
        thread(isDaemon = true) {
            val total = totalSize(inner)
            if (total <= 0L) return@thread
            val blocks = (total + BLOCK - 1) / BLOCK
            val frac = (targetSec / durationSec).coerceIn(0.0, 1.0)
            val idx = (frac * blocks).toLong().coerceIn(0L, blocks - 1)
            restartAt(inner, idx, "跳转到 ${"%.0f".format(targetSec)}s")
        }
    }

    fun stats(): String =
        "命中=${hits.get()} 回源=${misses.get()} 缓存=${used / 1048576}MB 上游=${upstreamBytes.get() / 1048576}MB"

    // ---------------- 服务端 ----------------

    private fun acceptLoop() {
        while (true) {
            val s = try {
                server?.accept() ?: break
            } catch (t: Throwable) {
                break
            }
            thread(isDaemon = true) { handle(s) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sk ->
                val input = sk.getInputStream().bufferedReader()
                val reqLine = input.readLine() ?: return
                val parts = reqLine.split(' ')
                if (parts.size < 2) return
                val pathQ = parts[1]
                var rangeHeader: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }
                val out = sk.getOutputStream()
                if (pathQ.startsWith("/status")) {
                    val body = stats().toByteArray()
                    writeHeaders(out, 200, "OK", "application/json", body.size.toLong(), null)
                    out.write(body)
                    out.flush()
                    return
                }
                if (!pathQ.startsWith("/stream")) {
                    writeHeaders(out, 404, "Not Found", "text/plain", 0, null)
                    return
                }
                val query = pathQ.substringAfter('?', "")
                val upstream = query.split('&').firstOrNull { it.startsWith("url=") }
                    ?.substringAfter("url=")?.let { URLDecoder.decode(it, "UTF-8") }
                if (upstream.isNullOrBlank()) {
                    writeHeaders(out, 400, "Bad Request", "text/plain", 0, null)
                    return
                }
                serveStream(upstream, rangeHeader, out)
            }
        } catch (_: Throwable) {
        }
    }

    private fun serveStream(upstream: String, rangeHeader: String?, out: OutputStream) {
        val total = totalSize(upstream)
        if (total <= 0L) {
            writeHeaders(out, 502, "Bad Gateway", "text/plain", 0, null)
            return
        }
        var start = 0L
        var end = total - 1
        val m = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader ?: "")
        if (m != null) {
            start = m.groupValues[1].toLong()
            if (m.groupValues[2].isNotBlank()) end = minOf(m.groupValues[2].toLong(), total - 1)
        }
        if (start >= total) {
            writeHeaders(out, 416, "Range Not Satisfiable", "text/plain", 0, null)
            return
        }

        val firstIdx = start / BLOCK
        val prev = readIdx[upstream]
        /* 位置不连续（跳转/回退）→ 清空缓存，从新位置重新铺 */
        if (prev != null && (firstIdx < prev || firstIdx > prev + 4L)) {
            restartAt(upstream, firstIdx, "读取位置不连续（$prev → $firstIdx）")
        }
        readIdx[upstream] = maxOf(firstIdx, readIdx[upstream] ?: firstIdx)
        if (!cursor.containsKey(upstream)) cursor[upstream] = firstIdx

        writeHeaders(out, 206, "Partial Content", "application/octet-stream", end - start + 1,
            "bytes $start-$end/$total")

        var pos = start
        while (pos <= end) {
            val idx = pos / BLOCK
            val off = pos % BLOCK
            val key = keyOf(upstream, idx)
            var blk = fileFor(key)
            if (blk == null) {
                /* 缺块：登记"急需"，由两条长连接之一去取，自己在这里等（绝不直连网盘） */
                urgent[upstream] = idx
                val deadline = System.currentTimeMillis() + 30000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                    blk = fileFor(key)
                    if (blk != null) break
                }
            }
            val f = blk ?: break
            f.inputStream().use { ins ->
                ins.skip(off)
                var remain = minOf(BLOCK - off, end - pos + 1)
                val buf = ByteArray(64 * 1024)
                while (remain > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                    remain -= n
                }
            }
            readIdx[upstream] = pos / BLOCK
        }
        out.flush()
    }

    // ---------------- 填充（两条长连接） ----------------

    private val readers = ConcurrentHashMap<String, MutableList<SeqReader>>()
    private val readIdx = ConcurrentHashMap<String, Long>()
    private val cursor = ConcurrentHashMap<String, Long>()
    private val urgent = ConcurrentHashMap<String, Long>()
    private val fillLock = Any()

    /** slot 0/1 各一条长连接，互不干扰 */
    private fun readerFor(url: String, slot: Int): SeqReader {
        val list = readers.getOrPut(url) { mutableListOf(SeqReader(url), SeqReader(url)) }
        return list[slot % list.size]
    }

    private fun fillLoop(slot: Int) {
        while (true) {
            try {
                Thread.sleep(80)
            } catch (t: InterruptedException) {
                break
            }
            val url = readIdx.keys.firstOrNull() ?: continue
            val total = runCatching { totalSize(url) }.getOrDefault(0L)
            if (total <= 0L) continue
            val totalBlocks = (total + BLOCK - 1) / BLOCK

            /* 1) 先处理解码器等着的块 */
            val need = urgent[url]
            if (need != null && fileFor(keyOf(url, need)) == null) {
                fetchBlock(url, need, total, slot)
                continue
            }

            /* 2) 再往前铺：缓存没满就继续 */
            val full = synchronized(lock) { used >= CAPACITY_BYTES * 95 / 100 }
            if (full) continue
            val next = synchronized(fillLock) {
                val cur = cursor.getOrPut(url) { readIdx[url] ?: 0L }
                if (cur >= totalBlocks) null else {
                    cursor[url] = cur + 1
                    cur
                }
            } ?: continue
            if (fileFor(keyOf(url, next)) == null) fetchBlock(url, next, total, slot)
        }
    }

    private fun fetchBlock(upstream: String, idx: Long, total: Long, slot: Int): File? {
        val key = keyOf(upstream, idx)
        val mine = synchronized(inflight) { inflight.putIfAbsent(key, Object()) == null }
        if (!mine) return null
        try {
            misses.incrementAndGet()
            val start = idx * BLOCK
            val end = minOf(start + BLOCK - 1, total - 1)
            val want = (end - start + 1).toInt()
            var data = readerFor(upstream, slot).read(start, want)
            if (data == null) {
                /* 长连接断了就重开一次；仍失败则本次作罢，等待重试 */
                data = readerFor(upstream, slot).read(start, want)
            }
            if (data == null || data.isEmpty()) return null
            val dir = cacheDir ?: return null
            val f = File(dir, "$key.blk")
            f.writeBytes(data)
            upstreamBytes.addAndGet(data.size.toLong())
            synchronized(lock) {
                index[key] = data.size.toLong()
                used += data.size
                trimLocked()
            }
            return f
        } catch (t: Throwable) {
            Log.w(TAG, "回源失败 idx=$idx：${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            synchronized(inflight) { inflight.remove(key) }
        }
    }

    /** 缓存回收：读过的块（落在播放位置后面的）立即删掉，把空间让给新数据 */
    private fun trimLocked() {
        val dir = cacheDir ?: return
        val url = readIdx.keys.firstOrNull()
        if (url != null) {
            val prefix = Integer.toHexString(url.hashCode()) + "-"
            val guard = (readIdx[url] ?: 0L) - KEEP_BEHIND_BLOCKS
            val stale = index.keys.filter { k ->
                k.startsWith(prefix) && (k.removePrefix(prefix).toLongOrNull() ?: 0L) < guard
            }
            for (k in stale) {
                val size = index.remove(k) ?: continue
                used -= size
                File(dir, "$k.blk").delete()
            }
        }
        while (used > CAPACITY_BYTES && index.size > 1) {
            val eldest = index.keys.first()
            val size = index.remove(eldest) ?: 0L
            used -= size
            File(dir, "$eldest.blk").delete()
        }
    }

    /** 清空缓存并从 idx 重新开始（跳转时用） */
    private fun restartAt(url: String, idx: Long, why: String) {
        synchronized(lock) {
            val dir = cacheDir
            if (dir != null) {
                for (k in index.keys.toList()) File(dir, "$k.blk").delete()
            }
            index.clear()
            used = 0
        }
        readIdx[url] = idx
        cursor[url] = idx
        urgent.remove(url)
        Log.i(TAG, "缓存重置：$why → 从第 $idx 块重新往前铺")
    }

    // ---------------- 上游 ----------------

    private class SeqReader(val url: String) {
        private var conn: HttpURLConnection? = null
        private var ins: InputStream? = null
        private var pos: Long = -1L

        @Synchronized
        fun read(start: Long, len: Int): ByteArray? {
            if (ins == null || pos != start) open(start)
            val stream = ins ?: return null
            val buf = ByteArray(len)
            var off = 0
            return try {
                while (off < len) {
                    val n = stream.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                    pos += n
                }
                if (off == 0) { close(); null } else buf.copyOf(off)
            } catch (t: Throwable) {
                close()
                null
            }
        }

        fun open(start: Long) {
            close()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=$start-")
                setRequestProperty("User-Agent", UA)
                connectTimeout = 20000
                readTimeout = 60000
            }
            if (c.responseCode !in 200..206) { c.disconnect(); return }
            conn = c
            ins = c.inputStream
            pos = start
        }

        fun close() {
            runCatching { ins?.close() }
            runCatching { conn?.disconnect() }
            ins = null
            conn = null
            pos = -1L
        }
    }

    private fun totalSize(upstream: String): Long {
        val cached = totalCache[upstream]
        if (cached != null) return cached
        return try {
            val c = (URL(upstream).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", UA)
                connectTimeout = 15000
                readTimeout = 15000
            }
            c.responseCode
            val cr = c.getHeaderField("Content-Range") ?: ""
            c.disconnect()
            val v = Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (v > 0L) totalCache[upstream] = v
            v
        } catch (t: Throwable) {
            0L
        }
    }

    private val totalCache = ConcurrentHashMap<String, Long>()

    private fun unwrap(url: String): String? {
        if (!url.startsWith("http://127.0.0.1:")) return url
        val q = url.substringAfter("url=", "")
        return if (q.isEmpty()) null else URLDecoder.decode(q, "UTF-8")
    }

    private fun keyOf(upstream: String, idx: Long): String =
        Integer.toHexString(upstream.hashCode()) + "-" + idx

    private fun fileFor(key: String): File? {
        synchronized(lock) {
            val dir = cacheDir ?: return null
            val f = File(dir, "$key.blk")
            if (!f.exists()) return null
            /*
             * 只认本进程登记过的块。目录里可能残留旧版本（4MB 分块时代）的块，
             * 遇到就地删除——既不用错数据，也立刻把空间还给后面的新数据
             * （父亲 2026-11-18：「读到旧文件也应该把旧文件删了」）。
             */
            if (!index.containsKey(key)) {
                f.delete()
                return null
            }
            index.remove(key)
            index[key] = f.length()
            hits.incrementAndGet()
            return f
        }
    }

    private fun writeHeaders(out: OutputStream, code: Int, msg: String, type: String, len: Long, contentRange: String?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $msg\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("Content-Range: $contentRange\r\n")
        sb.append("Content-Length: $len\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }
}
