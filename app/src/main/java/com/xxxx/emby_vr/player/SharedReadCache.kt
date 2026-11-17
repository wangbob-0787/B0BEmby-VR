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
 * 应用内共享读缓存（本机小服务）。
 *
 * 为什么需要（2026-11-17 实测定案）：
 *   杜比 P5 这条链路上有两个读取端 —— 内核 mpv 负责出声、自管解码 VideoPipe 负责出画，
 *   两边各自去 Emby/网盘取同一个大文件。结果是：
 *   ① 起播时要各自打开流、各自读索引（实测画面侧这一段花了 10.7 秒）；
 *   ② 播放中互相争抢，一方读出停顿（实测读到 72.6MB 后不再增长，画面随之冻住）。
 *   父亲一句话点破：「声音没了，画面反而不卡」。
 *
 * 做法：应用内起一个只监听 127.0.0.1 的 HTTP 服务，两条链路都指向它；
 *   它去上游取一次、按 4MB 分块缓存到应用私有目录，多端共享；同一块的并发请求只回源一次。
 *
 * 开关：vr-tuning.txt 里 `relay=1` 开（默认开），`relay=0` 退回直连（不用重装）。
 */
object SharedReadCache {

    private const val TAG = "B0BEmbyVR-Relay"
    private const val PORT = 8898
    private val BLOCK: Long get() = com.xxxx.emby_vr.vr.VrTuning.relayBlockMb.toLong() * 1024 * 1024
    private const val CAPACITY_BYTES = 400L * 1024 * 1024
    private const val UA = "B0BEmbyRelay/1.0"

    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    private var cacheDir: File? = null

    private val index = LinkedHashMap<String, Long>()   // key -> 块大小
    private val lock = Any()
    private var used = 0L
    private val inflight = ConcurrentHashMap<String, Object>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val upstreamBytes = AtomicLong()

    /** 开/关（VrTuning.relay）；关闭时 [rewrite] 原样返回，不改变任何行为 */
    fun enabled(): Boolean = com.xxxx.emby_vr.vr.VrTuning.relay == 1

    fun start(ctx: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            cacheDir = File(ctx.cacheDir, "relay").apply { mkdirs() }
            try {
                server = ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))
                started = true
                thread(name = "relay-accept", isDaemon = true) { acceptLoop() }
                Log.i(TAG, "共享读缓存已启动 127.0.0.1:$PORT 上限 ${CAPACITY_BYTES / 1048576}MB 目录 ${cacheDir?.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "共享读缓存启动失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * 把上游地址改写为本机中转地址；未开启或启动失败时原样返回。
     * 只改 http(s) 直链，其它（content://、本地文件）不碰。
     */
    fun rewrite(url: String): String {
        if (!enabled() || !started) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val enc = URLEncoder.encode(url, "UTF-8")
        return "http://127.0.0.1:$PORT/stream?url=$enc"
    }

    /**
     * 跳转预热 / 换片清空：这一版**刻意保持空实现**。
     *
     * 父亲 2026-11-18 实测结论：刚加中转的这一版最好用（r1445）。
     * 后来加的前视预取、跳转并发预热、按位置清空，都把播放改坏过，
     * 所以对外接口留着（调用点不用改），行为回到"读到多少存多少、缺块现取"。
     */
    fun warm(url: String, targetSec: Double, durationSec: Double) = Unit

    fun clearAll(why: String) = Unit

    fun stats(): String = "命中=${hits.get()} 回源=${misses.get()} 缓存=${used / 1048576}MB 上游=${upstreamBytes.get() / 1048576}MB"

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
        writeHeaders(out, 206, "Partial Content", "application/octet-stream", end - start + 1,
            "bytes $start-$end/$total")

        var pos = start
        while (pos <= end) {
            val idx = pos / BLOCK
            val off = pos % BLOCK
            val key = keyOf(upstream, idx)
            val f = fileFor(key) ?: fetchBlock(upstream, idx, total) ?: break
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
        }
        out.flush()
    }

    /** 探总长度：Range: bytes=0-0 + 读 Content-Range */
    private fun totalSize(upstream: String): Long {
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
            Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    private fun keyOf(upstream: String, idx: Long): String =
        Integer.toHexString(upstream.hashCode()) + "-" + idx

    private fun fileFor(key: String): File? {
        synchronized(lock) {
            val dir = cacheDir ?: return null
            val f = File(dir, "$key.blk")
            if (!f.exists()) return null
            // LRU：移到末尾
            index.remove(key)
            index[key] = f.length()
            hits.incrementAndGet()
            return f
        }
    }

    /** 取一块并入库；同一块并发只回源一次 */
    private fun fetchBlock(upstream: String, idx: Long, total: Long): File? {
        val key = keyOf(upstream, idx)
        val mine = synchronized(inflight) { inflight.putIfAbsent(key, Object()) == null }
        if (!mine) {
            // 别人在拉：等它落地
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                fileFor(key)?.let { return it }
                Thread.sleep(50)
            }
            return null
        }
        try {
            misses.incrementAndGet()
            val start = idx * BLOCK
            val end = minOf(start + BLOCK - 1, total - 1)
            val c = (URL(upstream).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=$start-$end")
                setRequestProperty("User-Agent", UA)
                connectTimeout = 20000
                readTimeout = 30000
            }
            if (c.responseCode !in 200..206) {
                c.disconnect()
                return null
            }
            val data = c.inputStream.use { it.readBytes() }
            c.disconnect()
            if (data.isEmpty()) return null
            val dir = cacheDir ?: return null
            val f = File(dir, "$key.blk")
            f.writeBytes(data)
            upstreamBytes.addAndGet(data.size.toLong())
            synchronized(lock) {
                index[key] = data.size.toLong()
                used += data.size
                while (used > CAPACITY_BYTES && index.size > 1) {
                    val eldest = index.keys.first()
                    val size = index.remove(eldest) ?: 0L
                    used -= size
                    File(dir, "$eldest.blk").delete()
                }
            }
            return f
        } catch (t: Throwable) {
            Log.w(TAG, "回源失败 idx=$idx：${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            synchronized(inflight) { inflight.remove(key) }
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
