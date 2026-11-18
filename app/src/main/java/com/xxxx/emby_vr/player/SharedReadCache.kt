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
    /**
     * 拉流连接数（父亲 2026-11-18 定：先按"单路读到尾"试）。
     *
     * 单路的理由：两条连接共用一个"下一条拉哪段"的游标，谁到段边界都得抢下一段，
     * 抢不到就得断开重连 —— 那正是把吞吐锁在 2.5MB/s 的原因。单路一条连接读到文件尾，
     * 只有出错才重连。代价是没有并行度，实测不够 5.5MB/s 时再改回两路各领一串。
     */
    private const val PULLERS = 1
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
    /** 当前这一部片的总字节数（水位换算要用），由拉流线程探到后登记 */
    @Volatile private var curTotal = 0L
    @Volatile private var writeNext = 0L        // 下一个要拉取的段号
    @Volatile private var baseIdx = 0L          // 缓存里保留的第一段
    @Volatile private var gen = 0L              // 换片/跳转代数：拉流线程据此重开连接

    /** 每个客户端（HTTP 连接）当前读到的段号，用于算"最慢那个" */
    private val connPos = ConcurrentHashMap<Int, Long>()
    /** 拉到一半断掉的段：优先续传，不能丢（否则播放器永远等这一段） */
    private val pendingSegs = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
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
        "命中=${hits.get()} 回源=${misses.get()} 缓存=${used / 1048576}MB 上游=${upstreamBytes.get() / 1048576}MB" +
            " 上游源=${if (com.xxxx.emby_vr.vr.VrTuning.relayDirect == 1) "直连(" + hostOfUrl(cdnUrl ?: alistUrl) + ")" else "Emby"}" +
            " 签名=${directResolves.get()} 直连失败=$directFails"

    // ---------------- 直连网盘（relay_direct=1） ----------------

    /** 播放器给的那条地址（Emby），用来认"这次直连的是哪一个文件" */
    @Volatile private var originKey: String? = null
    /** 小雅 alist 的 /d/ 地址：对它发一次 Range 请求只会拿到 302，不搬数据 */
    @Volatile private var alistUrl: String? = null
    /** 302 之后的 CDN 地址（带签名，有效期约两小时） */
    @Volatile private var cdnUrl: String? = null
    @Volatile private var cdnAt = 0L
    /** 直连连续被拒次数；超过上限本次播放退回 Emby，绝不让画面停在这种事上 */
    @Volatile private var directFails = 0
    private val directResolves = AtomicLong()

    /** 签名地址复用时长：网盘给的有效期约两小时，留一半余量提前重签 */
    private const val CDN_TTL_MS = 90L * 60 * 1000
    private const val DIRECT_MAX_FAILS = 3

    /**
     * 登记这一部片的两个地址。
     *
     * [embyUrl] 播放器实际用的地址；[alistUrl] 小雅 /d/ 地址（取不到就给 null，等于这一部片不直连）。
     * 换片/换集必须重新登记，否则会拿上一部的签名去取这一部的内容。
     */
    fun setPlaybackUrls(embyUrl: String, alistUrl: String?) {
        originKey = embyUrl
        this.alistUrl = alistUrl
        cdnUrl = null
        cdnAt = 0L
        directFails = 0
        Log.i(
            TAG,
            "中转上游登记：直连开关=${com.xxxx.emby_vr.vr.VrTuning.relayDirect}" +
                " 小雅侧=${if (alistUrl == null) "无（走 Emby）" else hostOfUrl(alistUrl)}",
        )
    }

    /**
     * 拉流真正要连的地址。
     *
     * 直连开着且这一部片有小雅地址时，返回 CDN 签名地址；任何一步不成立都原样返回 Emby 地址——
     * 直连是加速手段，**不允许它成为新的失败点**。
     */
    private fun fetchUrl(url: String): String {
        if (com.xxxx.emby_vr.vr.VrTuning.relayDirect != 1) return url
        if (url != originKey) return url
        if (directFails >= DIRECT_MAX_FAILS) return url
        val fresh = cdnUrl
        if (fresh != null && System.currentTimeMillis() - cdnAt < CDN_TTL_MS) return fresh
        return resolveCdn() ?: url
    }

    /** 向小雅要一次 302，拿到 CDN 签名地址（只取响应头，不下载内容） */
    private fun resolveCdn(): String? {
        val alist = alistUrl ?: return null
        return try {
            val c = (URL(alist).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            val out = when {
                code in 300..399 && !loc.isNullOrBlank() -> loc
                /* 小雅自己就地代理：这条地址本身就是数据源，不必再找 CDN */
                code == 200 || code == 206 -> alist
                else -> null
            }
            if (out != null) {
                cdnUrl = out
                cdnAt = System.currentTimeMillis()
                directResolves.incrementAndGet()
                Log.i(TAG, "直连网盘：取到地址 主机=${hostOfUrl(out)}（码=$code，${CDN_TTL_MS / 60000} 分钟内复用）")
            } else {
                Log.w(TAG, "直连网盘：签名失败（码=$code）→ 这一轮走 Emby")
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "直连网盘：签名异常 ${t.javaClass.simpleName}: ${t.message} → 这一轮走 Emby")
            null
        }
    }

    private fun hostOfUrl(u: String?): String =
        if (u.isNullOrBlank()) "-" else runCatching { URL(u).host ?: "-" }.getOrDefault("-")

    /**
     * 播放位置之后还有多少秒数据（水位：判据的唯一来源）。
     *
     * 位置取所有客户端连接里**最慢**的那个（慢的那条才是会不会断的瓶颈）；
     * 从它所在的段开始连续累加，一遇到没写满的段就停 —— 缺口之后的数据对播放没意义。
     * 返回负数 = 现在还判断不了（没有连接或文件长度未知）。
     */
    fun waterAheadSecs(durationSec: Double): Double {
        val totalBytes = curTotal
        if (totalBytes <= 0L || durationSec <= 0.0) return -1.0
        val slow = connPos.values.minOrNull() ?: return -1.0
        val seg = SEG
        var idx = slow
        var bytes = 0L
        while (true) {
            val have = synchronized(lock) { avail[idx] ?: -1L }
            if (have < 0L) break
            val segLen = minOf(seg, totalBytes - idx * seg)
            if (segLen <= 0L) break
            bytes += have
            if (have < segLen) break
            idx++
        }
        return bytes.toDouble() / totalBytes * durationSec
    }

    /**
     * 记账校准：以段文件真实字节为准。
     *
     * 现场见过账面 261MB、按段算只有约 48MB 的情况（跳转后旧段既没进回收也没从账上扣）。
     * 水位判据完全靠这个数，所以差超过 8MB 就当场纠正并打日志；
     * 顺带清掉"盘上有、账上没有"的孤儿段文件，否则它们白占空间还看不出来是谁占的。
     */
    private fun reconcileUsed() {
        val dir = cacheDir ?: return
        var real = 0L
        var segCount = 0
        val missing = ArrayList<Long>()
        synchronized(lock) {
            for ((k, v) in avail) {
                if (!File(dir, "$k.seg").exists()) {
                    missing.add(k)
                    continue
                }
                real += v
                segCount++
            }
            for (k in missing) avail.remove(k)
        }
        var orphans = 0
        val now = System.currentTimeMillis()
        runCatching {
            for (f in dir.listFiles().orEmpty()) {
                val name = f.name
                if (!name.endsWith(".seg")) continue
                val k = name.dropLast(4).toLongOrNull() ?: continue
                val known = synchronized(lock) { avail.containsKey(k) }
                if (known) continue
                /*
                 * 只清"放了一会儿还没登记"的段。
                 * 拉流线程是**先建文件、后登记 avail**，中间那一瞬间文件存在但账上没有；
                 * 这里若无条件删，就会把刚开始写的段删掉 —— 写入落到已删除的句柄上，
                 * 读者永远读不到数据（现场表现：转圈后黑屏、进度停在 0）。
                 */
                if (now - f.lastModified() < 60_000L) continue
                if (f.delete()) orphans++
            }
        }
        val drift = real - used
        if (drift > 8L * 1048576 || drift < -8L * 1048576) {
            val book = used
            synchronized(lock) { used = real }
            Log.w(
                TAG,
                "缓存记账校正：账面=${book / 1048576}MB → 实际=${real / 1048576}MB" +
                    "（$segCount 段，孤儿文件=$orphans）",
            )
        }
    }

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
                /*
                 * 本次连接连读几段：`relay_run_segs=0`（默认）= 一直读到文件尾，
                 * 只有出错或换片才重连；设成正数则每读这么多段换一次连接（双路并行时用）。
                 */
                val runRaw = com.xxxx.emby_vr.vr.VrTuning.relayRunSegs.toLong()
                val toEof = runRaw <= 0L
                val runSegs = if (toEof) 0L else runRaw
                val total = totalSize(url)
                if (total > 0L) curTotal = total
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
                    val pend = pendingSegs.firstOrNull()
                    if (pend != null) {
                        pendingSegs.remove(pend)
                        pend
                    } else if (writeNext * seg >= total) -1L else {
                        val i = writeNext
                        /*
                         * 一次领**一串**段：这条连接把这一串连续读完才换连接。
                         * 原来一次只领一段、读满就断线，每段都要重新握手（约 1.6 秒），
                         * 实测吞吐被锁死在 4MB/1.6s ≈ 2.5MB/s。段从此只当落盘分界。
                         */
                        writeNext = if (toEof) total / seg + 1 else i + runSegs
                        i
                    }
                }
                if (startIdx < 0L) {
                    Thread.sleep(200)
                    continue
                }
                val runEnd = if (toEof) total / seg else startIdx + runSegs - 1
                val myGen = gen
                var curIdx = startIdx
                /* 从该段已写入的位置续传（断点续传） */
                var writtenInSeg = synchronized(lock) { avail[curIdx] ?: 0L }
                /* 上游地址：直连开着就走 CDN，否则走 Emby —— 两条路都只有中转一个消费者 */
                val target = fetchUrl(url)
                val viaDirect = target != url
                var conn: HttpURLConnection? = null
                try {
                    val fromByte = curIdx * seg + writtenInSeg
                    val c = (URL(target).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=$fromByte-")
                        setRequestProperty("User-Agent", UA)
                        connectTimeout = 15000
                        /* 超时短一点：卡住就重连续传，而不是让播放器等一分钟 */
                        readTimeout = 15000
                    }
                    if (c.responseCode !in 200..206) {
                        c.disconnect()
                        /* 直连被拒（签名过期/额度用尽）：丢掉签名重签，连续失败就本次退回 Emby */
                        if (viaDirect) {
                            directFails++
                            cdnUrl = null
                            cdnAt = 0L
                            Log.w(
                                TAG,
                                "直连网盘：拉流被拒（码=${c.responseCode}）→ 重签" +
                                    (if (directFails >= DIRECT_MAX_FAILS) "已到上限，本次播放退回 Emby" else ""),
                            )
                        }
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
                                raf?.close()
                                raf = null
                                /*
                                 * 这一段写满了：**不换连接**，接着写下一段。
                                 * 一串读完（或读到文件尾）才退出循环，由外层重新领一串、换一条连接。
                                 */
                                if (curIdx >= runEnd || (curIdx + 1) * seg >= total) break
                                curIdx += 1
                                writtenInSeg = synchronized(lock) { avail[curIdx] ?: 0L }
                                continue
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
                /*
                 * 收尾：只登记"这一段还没写完"，交给待续传队列；
                 * **绝不把共享游标往回拨** —— 那会把另一条线程已推进的进度一起拖回去，
                 * 两条连接于是反复重读同一段，实测吞吐只有单条（约 2.5MB/s）。
                 */
                if (gen == myGen) {
                    val done = synchronized(lock) { avail[curIdx] ?: 0L }
                    val segLen = minOf(seg, total - curIdx * seg)
                    if (done < segLen) pendingSegs.add(curIdx)
                    /*
                     * 本串里**还没开始**的段也要交回队列：领取时已经把它们从游标里划走了，
                     * 不还回去就永远没人写，播放器读到那一段会一直等。
                     */
                    var k = curIdx + 1
                    while (k <= runEnd && k * seg < total) {
                        pendingSegs.add(k)
                        k++
                    }
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
        var ticks = 0
        while (true) {
            try {
                Thread.sleep(1000)
                /* 每 5 秒校准一次记账与孤儿段（水位判据完全依赖这个数，漂了就不可信） */
                if (++ticks % 5 == 0) reconcileUsed()
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
