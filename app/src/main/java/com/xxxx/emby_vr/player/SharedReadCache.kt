package com.xxxx.emby_vr.player

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 应用内共享读缓存（本机中转，127.0.0.1:8898）。
 *
 * 规则（父亲 2026-11-18 逐条定稿）：
 *  1. 去网盘取数据**只有中转**，两端解码器（内核 mpv 出声、自管解码出画）都只读本机缓存。
 *  2. 拉数据是**流式**的：一条长连接连续读，读到多少就落盘多少；缓存区满了就停下等空间。
 *  3. 解码器读缓存也是**流式**的：要多少给多少，没到的部分等数据到达后接着给，
 *     不等"凑满一块"（这是之前快进后要等好几秒的原因）。
 *  4. 两个解码器各记各的读位置；删除只删**两方都读过、且再留一段余量**之后的数据，
 *     慢的一方（mpv 常常是慢的）不会被删掉还没读完的内容。
 *  5. 快进停在后面：它之前的数据全部删掉；后退到更早位置、切片、切集、换片：清空重来。
 *
 * 结构：按 [SEG] 大小切段，每段一个文件，写入是追加式；[avail] 记每段已写到多少字节，
 *   读侧只按 avail 给数据，因此不需要等整段写完。
 */
object SharedReadCache {

    private const val TAG = "B0BEmbyVR-Relay"
    private const val PORT = 8898
    private const val CAPACITY_BYTES = 400L * 1024 * 1024
    private const val UA = "B0BEmbyRelay/1.0"
    /** 两条拉流长连接（父亲定：两路） */
    private const val PULLERS = 2
    /** 删除余量：留几段（可调 relay_margin_segs，默认 8 段 ≈ 32MB ≈ 18 秒内容） */
    private val MARGIN_SEGS: Long get() = com.xxxx.emby_vr.vr.VrTuning.relayMarginSegs.toLong()

    private val SEG: Long get() = com.xxxx.emby_vr.vr.VrTuning.relayBlockMb.toLong() * 1024 * 1024

    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null
    private var cacheDir: File? = null

    /** 段号 → 已写入字节数（只增） */
    private val avail = LinkedHashMap<Long, Long>()
    private val lock = Any()
    private var used = 0L

    @Volatile private var curUrl: String? = null
    @Volatile private var writeNext = 0L        // 下一个要拉取的段号
    @Volatile private var baseIdx = 0L          // 缓存里保留的第一段
    @Volatile private var gen = 0L              // 换片/跳转代数：拉流线程据此重开连接

    /** 每个客户端（HTTP 连接）当前读到的段号，用于算"最慢那个" */
    private val connPos = ConcurrentHashMap<Int, Long>()
    private val connSeq = AtomicInteger(0)

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val upstreamBytes = AtomicLong()

    fun enabled(): Boolean = com.xxxx.emby_vr.vr.VrTuning.relay == 1

    fun start(ctx: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            cacheDir = File(ctx.cacheDir, "relay").apply { mkdirs() }
            /* 启动清空：旧版本的段与当前规则不兼容，宁可全丢 */
            runCatching { cacheDir?.listFiles()?.forEach { it.delete() } }
            try {
                server = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
                started = true
                thread(name = "relay-accept", isDaemon = true) { acceptLoop() }
                for (i in 0 until PULLERS) thread(name = "relay-pull-$i", isDaemon = true) { pullLoop() }
                thread(name = "relay-gc", isDaemon = true) { gcLoop() }
                Log.i(TAG, "共享读缓存已启动 127.0.0.1:$PORT 上限 ${CAPACITY_BYTES / 1048576}MB 拉流 ${PULLERS} 路")
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

    /** 跳转提示：把这个目标当成新起点（往前跳=丢前面的，往回=全清） */
    fun warm(url: String, targetSec: Double, durationSec: Double) {
        if (!enabled() || !started) return
        if (durationSec <= 0.0 || targetSec < 0.0) return
        val inner = unwrap(url) ?: return
        thread(isDaemon = true) {
            val total = totalSize(inner)
            if (total <= 0L) return@thread
            val idx = ((targetSec / durationSec).coerceIn(0.0, 1.0) * total / SEG).toLong()
            seekTo(inner, idx, "跳转到 ${"%.0f".format(targetSec)}s")
        }
    }

    /** 换片/换集/切片：整块清空 */
    fun clearAll(why: String) {
        synchronized(lock) {
            val dir = cacheDir
            if (dir != null) for (k in avail.keys.toList()) File(dir, "$k.seg").delete()
            avail.clear()
            used = 0
        }
        gen++
        curUrl = null
        writeNext = 0
        baseIdx = 0
        connPos.clear()
        Log.i(TAG, "缓存已清空：$why")
    }

    fun stats(): String =
        "命中=${hits.get()} 回源=${misses.get()} 缓存=${used / 1048576}MB 上游=${upstreamBytes.get() / 1048576}MB"

    // ---------------- HTTP 服务 ----------------

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
        val connId = connSeq.incrementAndGet()
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
                serveStream(upstream, rangeHeader, connId, out)
            }
        } catch (_: Throwable) {
        } finally {
            connPos.remove(connId)
        }
    }

    private fun serveStream(upstream: String, rangeHeader: String?, connId: Int, out: OutputStream) {
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

        val wantIdx = start / SEG
        if (curUrl != upstream) {
            /* 换了内容：清空重来 */
            clearAll("换内容")
            curUrl = upstream
            writeNext = wantIdx
            baseIdx = wantIdx
        } else {
            val lastKeep = baseIdx + maxOf(0L, avail.size.toLong()) - 1
            when {
                wantIdx < baseIdx -> seekTo(upstream, wantIdx, "后退到更早位置")
                wantIdx > lastKeep + 8 -> seekTo(upstream, wantIdx, "快进到后面")
            }
        }
        connPos[connId] = wantIdx

        writeHeaders(out, 206, "Partial Content", "application/octet-stream", end - start + 1,
            "bytes $start-$end/$total")

        var pos = start
        /* 单次搬运量可调（relay_chunk_kb，默认 256KB）：太小则系统调用多，太大占用瞬时内存 */
        val buf = ByteArray(com.xxxx.emby_vr.vr.VrTuning.relayChunkKb * 1024)
        var lastProgress = System.currentTimeMillis()
        var lastLogAt = 0L
        /* 一段只打开一次文件：原来每 64K 都重开一次（和"64K 败笔"同一个毛病，只是发生在服务端） */
        var openIdx = -1L
        var raf: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val idx = pos / SEG
                val off = pos % SEG
                val have = synchronized(lock) { avail[idx] ?: 0L }
                if (have > off) {
                    val dir = cacheDir ?: break
                    if (raf == null || openIdx != idx) {
                        raf?.close()
                        raf = null
                        val f = File(dir, "$idx.seg")
                        if (!f.exists()) {
                            /* 被回收了：等它重拉 */
                            Thread.sleep(20)
                            continue
                        }
                        raf = runCatching { RandomAccessFile(f, "r") }.getOrNull() ?: continue
                        openIdx = idx
                    }
                    val n = minOf(have - off, end - pos + 1, buf.size.toLong()).toInt()
                    raf?.seek(off)
                    val got = raf?.read(buf, 0, n) ?: -1
                    if (got <= 0) {
                        raf?.close()
                        raf = null
                        openIdx = -1L
                        Thread.sleep(20)
                        continue
                    }
                    out.write(buf, 0, got)
                    pos += got
                    lastProgress = System.currentTimeMillis()
                    connPos[connId] = pos / SEG
                } else {
                /* 数据还没到：等拉流线程写进来（超时即断开，让客户端重试） */
                if (System.currentTimeMillis() - lastProgress > 30000) break
                if (System.currentTimeMillis() - lastLogAt > 2000) {
                    lastLogAt = System.currentTimeMillis()
                    Log.i(TAG, "等数据|段=$idx|该段已写=${have}B|拉流起点=${writeNext}|缓存=${used / 1048576}MB")
                }
                Thread.sleep(15)
                }
            }
        } finally {
            runCatching { raf?.close() }
        }
        out.flush()
    }

    // ---------------- 拉流（唯一的网盘读取者） ----------------

    /**
     * 拉流：一条连接**连续读**，跨段不断流。
     *
     * 之前是"一段开一次连接"，每次握手约 1.6 秒（服务器要重新打开网盘文件），
     * 实测缓存只攒到十兆左右、只有四五秒余量。改成一条连接从当前段一直读到缓存满，
     * 段只作为落盘的分界，不再触发重连。
     */
    private fun pullLoop() {
        while (true) {
            try {
                val url = curUrl
                if (url == null) {
                    Thread.sleep(50)
                    continue
                }
                val seg = SEG
                val total = totalSize(url)
                if (total <= 0L) {
                    Thread.sleep(200)
                    continue
                }
                /*
                 * 领一个起始段：**必须原子领取并立刻占位**。
                 * 原来只读 writeNext 不占位，两条拉流线程会领到同一段、读同一份数据，
                 * 实际吞吐只有一路（实测约 2MB/s，播放刚好追平 → 一会儿就停住）。
                 */
                val startIdx: Long = synchronized(this) {
                    if (writeNext * seg >= total) -1L else {
                        val i = writeNext
                        writeNext = i + 1     /* 立刻占位，另一条线程领下一段 */
                        i
                    }
                }
                if (startIdx < 0L) {
                    Thread.sleep(200)
                    continue
                }
                val myGen = gen
                var curIdx = startIdx
                /* 从该段已写入的位置续传（断点续传） */
                var writtenInSeg = synchronized(lock) { avail[curIdx] ?: 0L }
                var conn: HttpURLConnection? = null
                try {
                    val fromByte = curIdx * seg + writtenInSeg
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=$fromByte-")
                        setRequestProperty("User-Agent", UA)
                        connectTimeout = 15000
                        /* 超时短一点：卡住就重连续传，而不是让播放器等一分钟 */
                        readTimeout = 15000
                    }
                    if (c.responseCode !in 200..206) {
                        c.disconnect()
                        Thread.sleep(300)
                        continue
                    }
                    conn = c
                    misses.incrementAndGet()
                    c.inputStream.use { ins ->
                        var raf: RandomAccessFile? = null
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (gen != myGen) break
                            val full = synchronized(lock) { used >= CAPACITY_BYTES * 95 / 100 }
                            if (full) {
                                Thread.sleep(100)
                                continue
                            }
                            val segLen = minOf(seg, total - curIdx * seg)
                            if (segLen <= 0L) break
                            if (writtenInSeg >= segLen) {
                                /* 这一段写满：交给下一段 */
                                raf?.close()
                                raf = null
                                curIdx += 1
                                writtenInSeg = 0
                                synchronized(this) { if (writeNext < curIdx) writeNext = curIdx }
                                break
                            }
                            if (raf == null) {
                                val f = File(cacheDir, "$curIdx.seg")
                                raf = RandomAccessFile(f, "rw")
                                /* 只有从头写才截断；续传保留已有内容 */
                                if (writtenInSeg == 0L) raf.setLength(0)
                            }
                            val want = minOf(buf.size.toLong(), segLen - writtenInSeg)
                            val n = ins.read(buf, 0, want.toInt())
                            if (n <= 0) break
                            raf?.seek(writtenInSeg)
                            raf?.write(buf, 0, n)
                            writtenInSeg += n
                            synchronized(lock) {
                                used += n
                                avail[curIdx] = writtenInSeg
                            }
                            upstreamBytes.addAndGet(n.toLong())
                        }
                        raf?.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "拉流中断，从第 $curIdx 段 ${writtenInSeg}B 处续传：${t.javaClass.simpleName}")
                } finally {
                    runCatching { conn?.disconnect() }
                }
                /* 收尾：把这个段的写入进度登记为"下次从这里继续" */
                synchronized(this) {
                    if (gen == myGen && writeNext > curIdx) writeNext = curIdx
                }
                Thread.sleep(20)
            } catch (t: Throwable) {
                runCatching { Thread.sleep(200) }
            }
        }
    }

    // ---------------- 回收 ----------------

    /**
     * 回收：只删"两个解码器都读过、并留一段余量"之前的段。
     * mpv 与自管解码各占一个连接，取它们读位置里**较小**的那个当安全线。
     */
    private fun gcLoop() {
        while (true) {
            try {
                Thread.sleep(1000)
                val slow = connPos.values.minOrNull()
                val base = baseIdx
                val safe = when {
                    slow == null -> -1L
                    slow - MARGIN_SEGS > base -> slow - MARGIN_SEGS
                    else -> -1L
                }
                if (safe > base) {
                    var freed = 0L
                    val doomed = ArrayList<Long>()
                    /* 锁内只改记账（微秒级），删文件放到锁外做，避免挡住读与拉 */
                    synchronized(lock) {
                        val keys = avail.keys.filter { it < safe }.sorted()
                        for (k in keys) {
                            val size = avail.remove(k) ?: continue
                            used -= size
                            freed += size
                            doomed.add(k)
                        }
                    }
                    val dir = cacheDir
                    if (dir != null) for (k in doomed) runCatching { File(dir, "$k.seg").delete() }
                    baseIdx = safe
                    if (freed > 0) Log.i(TAG, "回收：删到第 $safe 段之前（最慢读位置=$slow），释放 ${freed / 1048576}MB")
                }
            } catch (t: Throwable) {
            }
        }
    }

    /** 跳到某个段：往回退=全清；往前跳=丢掉前面的 */
    private fun seekTo(url: String, idx: Long, why: String) {
        gen++
        val backward = idx < baseIdx
        if (backward) {
            clearAll("$why（往回退）")
            curUrl = url
            writeNext = idx
            baseIdx = idx
            Log.i(TAG, "缓存重置：$why → 从第 $idx 段重新拉（起点核对：writeNext=$writeNext baseIdx=$baseIdx gen=$gen）")
        } else {
            val doomed = ArrayList<Long>()
            synchronized(lock) {
                val keys = avail.keys.filter { it < idx }.sorted()
                for (k in keys) {
                    val size = avail.remove(k) ?: continue
                    used -= size
                    doomed.add(k)
                }
            }
            val dir = cacheDir
            if (dir != null) for (k in doomed) runCatching { File(dir, "$k.seg").delete() }
            baseIdx = idx
            writeNext = idx
            Log.i(TAG, "快进：丢掉第 $idx 段之前的数据（$why）")
        }
    }

    // ---------------- 上游工具 ----------------

    private val totalCache = ConcurrentHashMap<String, Long>()

    private fun totalSize(upstream: String): Long {
        totalCache[upstream]?.let { return it }
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

    private fun unwrap(url: String): String? {
        if (!url.startsWith("http://127.0.0.1:")) return url
        val q = url.substringAfter("url=", "")
        return if (q.isEmpty()) null else URLDecoder.decode(q, "UTF-8")
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
