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
    /*
     * 段缓存上限（父 2026-11-19 18:51 定）：头显剩余 58G，给 20G。
     * 一部片子在播期间不主动删，换片或退出播放时才清；只有超过这 20G 才按
     * 「离播放位置最远的先删」回收。
     */
    private const val CAPACITY_BYTES = 20L * 1024 * 1024 * 1024
    private const val UA = "B0BEmbyRelay/1.0"
    /**
     * 拉流连接数。
     *
     * 2026-11-18 中午试过单路读到尾，实测一字节都不进来（游标一次跳到文件尾、
     * 之后无人补前段，缓存恒 0、黑屏），已回退到两路各领一段的老行为。
     *
     * 老行为的代价：每段一条连接，每段多约 1.6 秒握手，吞吐约 2.5MB/s。
     * 若将来要再提速，先单独验证"一条连接跨段读"在崩溃/换片时的补段逻辑。
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
    /**
     * **唯一状态**：已经连续写进缓存的绝对字节数。
     *
     * 段号、段内偏移、"下一段拉哪一段"全部由它算出来（段号 = 本值 / 段长）。
     * 只有一个变量，就不会出现"一个游标动了、另一个没动"（父亲 2026-11-18 定）。
     */
    @Volatile private var writePos = 0L

    /**
     * 路 2 的闸门（父亲 2026-11-18 定）：网盘只有两路，路 1 固定给拉流铺缓存，
     * 路 2 单条串行服务播放器那些"缓存里没有"的请求（文件头、尾部索引等）。
     *
     * 取多少给多少，不写缓存、不动写位；取完立刻让给下一个排队的人。
     */
    /*
     * 通道2 的两个槽（父 2026-11-19 18:27 定，本地实测通过）：
     * - slotMain 主数据流：只接长请求（声明长度 > 8MB，通常是"到文件尾"那条）。
     *   它不被任何短请求打断，否则播放器要重新连接、重新定位。
     * - slotRand 随机读：索引、短请求、另一个解码器打开容器时的那几块。
     * 两个槽互相独立，谁忙谁等，互不抢占。
     */
    private val slotMain = java.util.concurrent.Semaphore(1, true)
    private val slotRand = java.util.concurrent.Semaphore(1, true)
    private val MAIN_BYTES = 8L * 1024 * 1024
    /** 正在等路 2 的急件数（常件看到 >0 就让位） */
    private val urgentWaiting = AtomicInteger(0)

    /** 连接编号（只用于日志与标识，不再登记读位置） */
    private val connSeq = AtomicInteger(0)
    /** 头数据 / 尾数据各算多少为急件区间（父亲 2026-11-18 定：头 8MB，尾同） */
    private const val EDGE_BYTES = 8L * 1024 * 1024

    /**
     * 播放进度（秒），由播放器每拍上报。
     *
     * 水位只按它算：mpv 会把数据先读进自己的内存，读位置远在正在播的位置之前（更靠右），
     * 拿读位置算会把预读当成已消费，出现"缓存还够却提前停"（2026-11-18 父亲指出）。
     * 自管解码没有内存，读位置≈播放点，但两边口径要统一，所以一律用播放进度。
     */
    @Volatile private var playPosSec = 0.0

    /**
     * 待清段号：清空缓存只把它们记在这里，真正删文件由后台回收线程做。
     * 删之前必须确认该段当前不在账本里（可能已经被新内容覆盖使用）。
     */
    private val orphanSegs = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /**
     * 容器头与尾部索引是否已由通道2 取回（父 2026-11-19 定）。
     * 起播时等这两个都就绪再启动自管解码器，否则解封装器读不到轨道表就判"没有视频轨"。
     */
    @Volatile private var headReady = false
    @Volatile private var tailReady = false

    /**
     * 头 / 尾各一块内存（各 8MB）：路二把原数据与索引取回后放这里，
     * 两个解码器谁来要都从内存给，不够再临时回源（父 2026-11-19 定）。
     */
    private val headBlock = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val tailBlock = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val BLOCK_BYTES = 8L * 1024 * 1024

    /** 命中头/尾块就直接写出去；返回 true 表示已服务 */
    private fun serveFromBlock(start: Long, end: Long, total: Long, out: OutputStream): Boolean {
        val head = headBlock.get()
        if (head != null && start >= 0 && end < head.size) {
            out.write(head, start.toInt(), (end - start + 1).toInt())
            out.flush()
            Log.i(TAG, "路2 命中头块：起点=$start 长=${end - start + 1}B")
            return true
        }
        val tail = tailBlock.get()
        if (tail != null && total > 0 && start >= total - tail.size && end < total) {
            val off = (start - (total - tail.size)).toInt()
            out.write(tail, off, (end - start + 1).toInt())
            out.flush()
            Log.i(TAG, "路2 命中尾块：起点=$start 长=${end - start + 1}B")
            return true
        }
        return false
    }

    /**
     * 等容器头就绪；超时返回 false（调用方照常启动）。
     *
     * 只等文件头：尾部索引不是每部片都会被要（2026-11-19 15:06 实测：第 3 集尾部
     * 一次请求都没有，白等满 8 秒才启动）。真要尾部，解码器会自己来要，走通道2。
     */
    fun awaitHeadTail(timeoutMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (headReady) return true
            Thread.sleep(50)
        }
        return false
    }

    /** 当前这条拉流连接：跳转时直接断开，让它立刻从新写位重连（父 2026-11-19 定） */
    @Volatile private var activePull: java.net.HttpURLConnection? = null

    /** 当前这一部的时长（秒），播放进度换算字节要用 */
    @Volatile private var curDurationSec = 0.0

    /** 已设过的起播锚点（"地址@秒"），同一片同一个位置不重复清空 */
    @Volatile private var anchorKey: String? = null

    /** 拉流线程空闲日志的节流时间戳（每 3 秒最多一条） */
    @Volatile private var lastIdleLogAt = 0L
    @Volatile private var baseIdx = 0L          // 缓存里保留的第一段
    @Volatile private var gen = 0L              // 换片/跳转代数：拉流线程据此重开连接


    /** 每个客户端当前读到第几段：只用于删除保护，不参与水位（水位按播放进度算） */
    private val connPos = java.util.concurrent.ConcurrentHashMap<Int, Long>()

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
                /* 不再有铺流线程（父 2026-11-19 19:34）：缓存只顺手存，不主动往前铺 */
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

    /**
     * 起播锚点（父亲 2026-11-18 定）：清空缓存，把写位放到**起播位置**，从这里往后顺序铺。
     *
     * 起播位置来自续播进度（上次看到哪里），从没看过的就是 0。
     * 不判前后：起播阶段播放器要的文件头、尾部索引一律走 [serveDirect]。
     */
    /** 起播位置：只设读位置起点与缓存写入起点，不碰已有缓存（清空由切集路径负责） */
    /**
     * 起播只记三件事（父 2026-11-19 19:34）：内容是哪个、总长、时长与位置。
     *
     * 不设写位、不设缓存起点、不算锚点、不清缓存 —— 缓存完全由"谁读了哪段"被动形成。
     */
    fun beginBoot(url: String, startSec: Double, durationSec: Double) {
        if (!enabled() || !started) return
        val inner = unwrap(url) ?: return
        thread(isDaemon = true) {
            val total = totalSize(inner)
            if (total <= 0L) {
                Log.w(TAG, "起播：拿不到文件总长")
                return@thread
            }
            if (inner != curUrl) {
                clearAll("起播：换内容")
                curUrl = inner
            }
            curTotal = total
            if (durationSec > 0.0) curDurationSec = durationSec
            if (startSec >= 0.0) playPosSec = startSec
            Log.i(TAG, "起播：总长=${total}B 时长=${"%.1f".format(curDurationSec)}s 位置=${"%.1f".format(playPosSec)}s")
        }
    }

    /** 换片/换集/切片：整块清空 */
    /**
     * 清空缓存（父亲 2026-11-18 23:39 定）：**只改账本，不删文件**。
     *
     * 段文件留着给后续写入直接覆盖（写某段时本来就 setLength(0) 重写），
     * 旧段号进待清队列，由后台回收线程慢慢删 —— 起播路径上一个磁盘操作都不做。
     */
    fun clearAll(why: String) {
        /* 先换代：读侧按代数判定新旧，旧段立刻作废 */
        gen++
        var n = 0
        synchronized(lock) {
            n = avail.size
            orphanSegs.addAll(avail.keys)
            avail.clear()
            used = 0
        }
        curUrl = null
        writePos = 0
        baseIdx = 0
        headReady = false
        tailReady = false
        headBlock.set(null)
        tailBlock.set(null)
        Log.i(TAG, "缓存已清空：$why（标记 ${n} 段待清理，文件不删、留给覆盖写）")
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
     * 起点取**播放进度**（父亲 2026-11-18 定：进度条的位置最准）；
     * 从它所在的段开始连续累加，一遇到没写满的段就停 —— 缺口之后的数据对播放没意义。
     * 返回负数 = 现在还判断不了（没有连接或文件长度未知）。
     */
    fun waterAheadSecs(durationSec: Double): Double {
        val totalBytes = curTotal
        if (totalBytes <= 0L || durationSec <= 0.0) return -1.0
        /* 起点用播放进度，不用读位置（mpv 的读位置含它自己的预读，会假性偏低） */
        val posBytes = (playPosSec / durationSec * totalBytes).toLong().coerceIn(0L, totalBytes - 1)
        val seg = SEG
        var idx = posBytes / seg
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
    /**
     * 只核对数字，**不动任何文件**。
     *
     * 删除的判据永远只有一个：播放进度（见 gcLoop）。账本只回答"账上记了多少"，
     * 用它决定删什么就会删到正在写的段（2026-11-18 黑屏的成因）。
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
        val drift = real - used
        if (drift > 8L * 1048576 || drift < -8L * 1048576) {
            val book = used
            synchronized(lock) { used = real }
            Log.w(
                TAG,
                "缓存记账校正：账面=${book / 1048576}MB → 实际=${real / 1048576}MB（$segCount 段）",
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

    /**
     * 中转的唯一职责（父 2026-11-19 19:34 定）：按客户端要的区间把数据给出去。
     *
     * 命中缓存段 → 从段文件读出来给；没命中 → 去上游取这一段，边给下游边存段。
     * 不再有写位、缓存起点、水位、让位、内存窗 —— 那些机制今天实测会互相覆盖、
     * 把简单的事搞乱，客户端反而拿不到正确数据。
     */
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
        if (curUrl != upstream) {
            clearAll("换内容")
            curUrl = upstream
        }
        val want = end - start + 1
        writeHeaders(
            out, 206, "Partial Content", "application/octet-stream", want,
            "bytes $start-$end/$total",
        )
        val isMain = want > MAIN_BYTES
        val slot = if (isMain) slotMain else slotRand
        var pos = start
        var got = false
        try {
            slot.acquire()
            got = true
            while (pos <= end) {
                val idx = pos / SEG
                val segStart = idx * SEG
                val segEnd = minOf(segStart + SEG - 1, total - 1)
                val segLen = segEnd - segStart + 1
                val have = synchronized(lock) { avail[idx] ?: 0L }
                pos = if (have >= segLen) {
                    sendFromSeg(idx, segStart, segEnd, pos, end, out)
                } else {
                    fetchInto(upstream, idx, segStart, segEnd, segLen, pos, end, out)
                }
                if (pos < 0) return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "给数据中断：起点=$start 已给=${pos - start}B ${t.javaClass.simpleName}")
        } finally {
            if (got) slot.release()
            runCatching { out.flush() }
        }
    }

    /** 从已完整的段文件读给下游；返回新的位置 */
    private fun sendFromSeg(idx: Long, segStart: Long, segEnd: Long, from: Long, end: Long, out: OutputStream): Long {
        val f = segFile(idx)
        if (!f.exists()) return -1L
        var pos = from
        try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(pos - segStart)
                val buf = ByteArray(64 * 1024)
                while (pos <= minOf(segEnd, end)) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), minOf(segEnd, end) - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                    pos += n
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "段读取中断：段=$idx ${t.javaClass.simpleName}")
            return -1L
        }
        return pos
    }

    /**
     * 未命中：去上游取 [from, segEnd]，边给下游边写这一段文件。
     * 段写满（segLen）才标记为完整，没写满就丢掉重取（父 2026-11-19 19:34：缓存只顺手存，不参与判断）。
     */
    private fun fetchInto(
        upstream: String, idx: Long, segStart: Long, segEnd: Long, segLen: Long,
        from: Long, end: Long, out: OutputStream,
    ): Long {
        val to = minOf(segEnd, end)
        var pos = from
        var conn: HttpURLConnection? = null
        var raf: java.io.RandomAccessFile? = null
        try {
            val c = openRange(upstream, pos, to)
            conn = c
            val code = c.responseCode
            if (code !in 200..206) {
                Log.w(TAG, "回源失败：码=$code 起点=${pos}B")
                return -1L
            }
            raf = runCatching {
                java.io.RandomAccessFile(segFile(idx), "rw").also { it.seek(pos - segStart) }
            }.getOrNull()
            c.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (pos <= to) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                    runCatching { raf?.write(buf, 0, n) }
                    pos += n
                }
            }
            /* 这一段的起点到请求结束都写到了，才算这段完整 */
            if (pos > to && from == segStart && to == segEnd) {
                synchronized(lock) { avail[idx] = segLen; used += segLen }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "回源中断：段=$idx ${t.javaClass.simpleName}")
            return -1L
        } finally {
            runCatching { raf?.close() }
            runCatching { conn?.disconnect() }
        }
        return pos
    }

    /** 段文件名沿用 <段号>.seg（回收与对账按这个名字扫盘，不能改） */
    private fun segFile(idx: Long): File = File(cacheDir, "$idx.seg")

    private fun serveDirect(upstream: String, start: Long, end: Long, out: OutputStream, urgent: Boolean) {
        val want = end - start + 1
        val totalNow = curTotal
        /* 块缓存暂停使用（父 2026-11-19 15:56）：每次回源直接取，见下方说明 */
        val saveTo: java.util.concurrent.atomic.AtomicReference<ByteArray?>? = null
        val blockBuf: ByteArray? = null
        val t0 = System.currentTimeMillis()
        var got = false
        var pos = start
        val isMain = want > MAIN_BYTES
        val slot = if (isMain) slotMain else slotRand
        /* 缓冲大小：常件按参数（默认 1MB），急件给 256KB 够用 */
        val bufBytes = (if (urgent) 256 else com.xxxx.emby_vr.vr.VrTuning.relayBufKb) * 1024
        if (urgent) urgentWaiting.incrementAndGet()
        try {
            slot.acquire()
            got = true
            if (urgent) {
                val waited = System.currentTimeMillis() - t0
                if (waited > 100) Log.i(TAG, "路2 急件排队：等了 ${waited}ms（起点=${start}B）")
            }
            writeHeaders(
                out, 206, "Partial Content", "application/octet-stream", want,
                "bytes $start-$end/${if (curTotal > 0L) curTotal else end + 1}",
            )
            val genAt = gen
            var conn: HttpURLConnection? = null
            val buf = ByteArray(bufBytes)
            try {
                while (pos <= end) {
                    if (conn == null) {
                        conn = openRange(upstream, pos, end)
                        val code = conn.responseCode
                        if (code !in 200..206) {
                            Log.w(TAG, "路2 回源失败：码=$code 起点=${pos}B")
                            return
                        }
                        if (pos == start) {
                            val t = curTotal
                            if (start < EDGE_BYTES) headReady = true
                            else if (t > 0L && start >= t - EDGE_BYTES) tailReady = true
                            Log.i(
                                TAG,
                                "路2 首包：码=$code 起点=$start 长度=$want 等=${System.currentTimeMillis() - t0}ms" +
                                    "（${if (urgent) "急件" else "常件"}；头就绪=$headReady 尾就绪=$tailReady）",
                            )
                        }
                    }
                    val n = conn.inputStream.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                    if (n <= 0) break
                    if (gen != genAt) {
                        Log.w(TAG, "路2 中止：代数变了（起点=$pos B）")
                        return
                    }
                    var off = 0
                    while (off < n) {
                        /*
                         * 主数据流不再让位（父 2026-11-19 18:27）：急件有自己的槽，
                         * 两边互不打断，谁先拿到槽谁连续传完。
                         */
                        /*
                         * 单块 64KB，不限时间：与本地测试服务保持一致（父 2026-11-19 19:02）。
                         * 客户端真的断开时 write 会抛异常，靠那个释放槽位；
                         * 不再用"写多久算超时"这种判据 —— 实测它会把正常传输误切断，
                         * 客户端拿到的数据半截或为空。
                         */
                        val w = minOf(64 * 1024, n - off)
                        out.write(buf, off, w)
                        out.flush()
                        if (blockBuf != null) {
                            val at = (pos - start).toInt()
                            if (at >= 0 && at + w <= blockBuf.size) {
                                System.arraycopy(buf, off, blockBuf, at, w)
                            }
                        }
                        off += w
                        pos += w
                    }
                }
                val total = curTotal
                if (pos - start > 0) {
                    if (start < EDGE_BYTES) headReady = true
                    else if (total > 0L && start >= total - EDGE_BYTES) tailReady = true
                }
                if (blockBuf != null && blockBuf.isNotEmpty() && pos - start == blockBuf.size.toLong()) {
                    saveTo?.set(blockBuf)
                    Log.i(TAG, "路2 块已缓存：起点=$start 长=${blockBuf.size}B（以后两路都从内存给）")
                }
                Log.i(
                    TAG,
                    "路2 完成：起点=$start 已给=${pos - start}B 用时=${System.currentTimeMillis() - t0}ms" +
                        "（头就绪=$headReady 尾就绪=$tailReady）",
                )
            } finally {
                runCatching { conn?.disconnect() }
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "路2 中断：起点=$start 请求长=$want 已给=${pos - start}B ${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            if (got) slot.release()
            if (urgent) urgentWaiting.decrementAndGet()
        }
    }

    /** 开一条带 Range 的上游连接（路 2 专用） */
    private fun openRange(upstream: String, from: Long, to: Long): HttpURLConnection =
        (URL(fetchUrl(upstream)).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$from-$to")
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15000
            readTimeout = 30000
        }

    // ---------------- 拉流（唯一的网盘读取者） ----------------

    /**
     * 拉流：一条连接**连续读**，跨段不断流。
     *
     * 之前是"一段开一次连接"，每次握手约 1.6 秒（服务器要重新打开网盘文件），
     * 实测缓存只攒到十兆左右、只有四五秒余量。改成一条连接从当前段一直读到缓存满，
     * 段只作为落盘的分界，不再触发重连。
     */

    private fun gcLoop() {
        var ticks = 0
        while (true) {
            try {
                Thread.sleep(1000)
                /* 每 5 秒校准一次记账与孤儿段（水位判据完全依赖这个数，漂了就不可信） */
                if (++ticks % 5 == 0) reconcileUsed()
                /* 待清段：确认它已经不在账本里（没被新内容复用）才删，锁外删 */
                run {
                    val dir = cacheDir
                    if (dir != null && orphanSegs.isNotEmpty()) {
                        val batch = ArrayList<Long>()
                        while (!orphanSegs.isEmpty() && batch.size < 32) orphanSegs.poll()?.let { batch.add(it) }
                        for (k in batch) {
                            val inUse = synchronized(lock) { avail.containsKey(k) }
                            if (!inUse) runCatching { File(dir, "$k.seg").delete() }
                        }
                    }
                }
                /*
                 * 安全线取"播放进度"与"最慢读位置"里更靠左的那个（父 2026-11-19 定）：
                 * mpv 的读位置在播放点右边，自管解码卡顿时可能落到播放点左边，
                 * 只按其中一个算都会删掉另一个正在用的段。
                 */
                val playIdx = if (curTotal > 0L && curDurationSec > 0.0) {
                    (playPosSec / curDurationSec * curTotal / SEG).toLong()
                } else {
                    -1L
                }
                val readIdx = connPos.values.minOrNull() ?: -1L
                val slow = when {
                    playIdx < 0L -> readIdx
                    readIdx < 0L -> playIdx
                    else -> minOf(playIdx, readIdx)
                }
                val base = baseIdx
                val safe = when {
                    slow < 0L -> -1L
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
                    if (dir != null) {
                        for (k in doomed) runCatching { File(dir, "$k.seg").delete() }
                        /*
                         * 唯一的删除依据是**播放进度**：段号在安全线之前的文件一律删，
                         * 不查账本。账本只用来核数，不能拿来当删除判据 ——
                         * 之前按"盘上有、账上没有"删，把刚建好还没登记的段删掉，
                         * 写入落到已删除的文件上，读取侧永远等不到数据（2026-11-18 黑屏）。
                         */
                        runCatching {
                            for (f in dir.listFiles().orEmpty()) {
                                if (!f.name.endsWith(".seg")) continue
                                val k = f.name.dropLast(4).toLongOrNull() ?: continue
                                if (k < safe) f.delete()
                            }
                        }
                    }
                    baseIdx = safe
                    if (freed > 0) Log.i(TAG, "回收：删到第 $safe 段之前（最慢读位置=$slow），释放 ${freed / 1048576}MB")
                }
            } catch (t: Throwable) {
            }
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
