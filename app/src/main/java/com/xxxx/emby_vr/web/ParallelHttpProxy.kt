package com.xxxx.emby_vr.web

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.math.abs
import kotlin.math.min

/**
 * 多路取流代理（2026-10-18 晚，父亲「坚持不懈自己算签名」之后的第二步）。
 *
 * 为什么要它：腾讯的整集文件是普通 mp4（单文件、可 Range），但**单条连接被限速到约 1KB/s**。
 * 实测（.18，同一地址 14 秒采样）：1 连接 15KB；2 连接 168MB；4 连接 253MB。
 * 播放器只发一条连接 → 连文件头都读不下来 → 大屏就是黑的、总时长显示 0。
 *
 * 做法：本机起一个小 HTTP 服务，播放器连它；它自己在后台开 N 条 Range 连接把同一份文件
 * 分块拉下来写进一个临时文件（稀疏写 ✓），播放器要哪一段就从临时文件里喂哪一段。
 * 拖动进度条时，优先补播放器当前位置附近的分块。
 *
 * 关键约束：
 *  - 只监听 127.0.0.1，端口由系统分配，不对外暴露。
 *  - 临时文件放 cacheDir，一次播放一份，换片即停旧代理并删旧文件。
 *  - 支持 Range 请求（206），播放器 seek 时按新区间重新喂。
 */
class ParallelHttpProxy(
    private val remoteUrl: String,
    private val headers: Map<String, String>,
    private val cacheDir: File,
    private val connections: Int = 4,
    private val chunkSize: Long = 4L * 1024 * 1024,
) {
    companion object {
        private const val TAG = "B0BEmbyVR-Proxy"
        private const val STATE_IDLE = 0
        private const val STATE_BUSY = 1
        private const val STATE_DONE = 2

        @Volatile
        private var current: ParallelHttpProxy? = null

        /** 起一个代理并把播放器该用的地址返回；旧代理自动停掉。 */
        fun start(
            remoteUrl: String,
            headers: Map<String, String>,
            cacheDir: File,
            connections: Int = 4,
        ): String? {
            stop()
            val p = ParallelHttpProxy(remoteUrl, headers, cacheDir, connections)
            return if (p.open()) {
                current = p
                "http://127.0.0.1:${p.port}/stream"
            } else {
                p.close()
                null
            }
        }

        /** 停掉当前代理（换片 / 退出播放时调）。 */
        fun stop() {
            val p = current ?: return
            current = null
            p.close()
        }
    }

    private var server: ServerSocket? = null
    private var file: RandomAccessFile? = null
    private var partFile: File? = null
    @Volatile
    private var total: Long = -1
    @Volatile
    private var running = true
    @Volatile
    private var readerPos: Long = 0
    private var chunkCount = 0
    private val state = ArrayList<Int>()
    private val lock = Object()
    var port: Int = 0
        private set

    private val workers = ArrayList<Thread>()

    /** 起服务 + 问一次总长度；成功返回 true。 */
    private fun open(): Boolean {
        total = probeLength()
        if (total <= 0) {
            Log.w(TAG, "拿不到文件长度，代理不上（地址可能已经过期）")
            return false
        }
        chunkCount = ((total + chunkSize - 1) / chunkSize).toInt()
        repeat(chunkCount) { state.add(STATE_IDLE) }
        val f = File(cacheDir, "webcache-${System.currentTimeMillis()}.part")
        partFile = f
        f.parentFile?.mkdirs()
        val raf = RandomAccessFile(f, "rw")
        raf.setLength(total)
        file = raf
        server = try {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.w(TAG, "本地端口起不来：$e")
            return false
        }
        port = server!!.localPort
        Log.i(
            TAG,
            "代理就绪：本地端口 $port，远端文件 ${total / 1024 / 1024}MB，" +
                "分成 $chunkCount 块（每块 ${chunkSize / 1024 / 1024}MB），$connections 条连接并行拉",
        )
        Thread({ acceptLoop() }, "proxy-accept").start()
        /* 内核最先要的是文件头（moov 在前面），先把开头几块拉起来，省掉首帧那几秒等待 */
        Thread({
            for (i in 0 until min(3, chunkCount)) {
                if (state.getOrNull(i) == STATE_IDLE) {
                    synchronized(lock) { state[i] = STATE_BUSY }
                    pullChunk(i)
                }
            }
        }, "proxy-warmup").start()
        repeat(connections) { idx ->
            val t = Thread({ workerLoop() }, "proxy-pull-$idx")
            t.isDaemon = true
            workers.add(t)
            t.start()
        }
        return true
    }

    /** 用 Range 只请求第 0 字节，从 Content-Range 里读总长度。 */
    private fun probeLength(): Long {
        try {
            val conn = URL(remoteUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Range", "bytes=0-0")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val cr = conn.getHeaderField("Content-Range")
            conn.disconnect()
            if (cr != null && cr.contains("/")) {
                val t = cr.substringAfterLast("/").trim().toLongOrNull() ?: -1
                if (t > 0) {
                    Log.i(TAG, "远端总长度 $t 字节（HTTP $code）")
                    return t
                }
            }
            val len = conn.contentLengthLong
            if (len > 0) return len
        } catch (e: Exception) {
            Log.w(TAG, "问长度失败：$e")
        }
        return -1
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (running) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            Thread({ serveOne(sock) }, "proxy-serve").start()
        }
    }

    /** 处理播放器的一条 HTTP 请求（GET/HEAD，可能带 Range）。 */
    private fun serveOne(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = sock.getInputStream().bufferedReader()
            val head = StringBuilder()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                head.append(line).append('\n')
            }
            val lines = head.toString().split('\n')
            if (lines.isEmpty()) return
            Log.i(TAG, "收到内核请求：" + lines[0])
            val req = lines[0].split(' ')
            if (req.size < 2) return
            val isHead = req[0].equals("HEAD", ignoreCase = true)
            var start = 0L
            var end = total - 1
            var hadRange = false
            for (l in lines) {
                if (l.startsWith("Range:", ignoreCase = true)) {
                    hadRange = true
                    val spec = l.substringAfter("bytes=").trim()
                    val a = spec.substringBefore('-').trim().toLongOrNull()
                    val b = spec.substringAfter('-').trim().toLongOrNull()
                    if (a != null) start = a
                    if (b != null && b > 0) end = min(b, total - 1)
                }
            }
            if (start < 0) start = 0
            if (start > end || start >= total) {
                val out = sock.getOutputStream()
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$total\r\n\r\n").toByteArray(),
                )
                out.flush()
                return
            }
            readerPos = start
            val len = end - start + 1
            val out = sock.getOutputStream()
            /*
             * 没带 Range 的请求按规矩必须回 200 —— 回 206 会被一部分客户端（含内核）
             * 判成"服务端乱来"直接放弃，表现就是"代理起来了但内核一条轨都没有"。
             */
            val header = if (hadRange) {
                "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Length: $len\r\n" +
                    "Content-Range: bytes $start-$end/$total\r\n" +
                    "Connection: close\r\n\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Length: $len\r\n" +
                    "Connection: close\r\n\r\n"
            }
            out.write(header.toByteArray())
            out.flush()
            if (isHead) return
            Log.i(TAG, "开始喂数据：从 $start 到 $end（共 ${len / 1024 / 1024}MB）")
            var pos = start
            val buf = ByteArray(256 * 1024)
            while (pos <= end && running) {
                val chunk = (pos / chunkSize).toInt()
                if (!awaitChunk(chunk)) break
                val raf = file ?: break
                val chunkEnd = min(end, (chunk + 1) * chunkSize - 1)
                synchronized(raf) {
                    raf.seek(pos)
                    var remaining = chunkEnd - pos + 1
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                        pos += n
                        readerPos = pos
                    }
                }
                out.flush()
            }
        } catch (e: Exception) {
            // 播放器切段/关闭连接是常态，不当作错误刷日志
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }

    /** 等某一块下完（播放器读到这里就必须有数据）。 */
    private fun awaitChunk(chunk: Int): Boolean {
        synchronized(lock) {
            var waited = 0L
            while (running && state.getOrNull(chunk) != STATE_DONE) {
                try {
                    lock.wait(300)
                } catch (e: InterruptedException) {
                    return false
                }
                waited += 300
                if (waited > 120000) {
                    Log.w(TAG, "第 $chunk 块等了两分钟还没到，放弃这一段")
                    return false
                }
            }
        }
        return running
    }

    /** 拉块线程：优先补播放器当前位置附近的块。 */
    private fun workerLoop() {
        while (running) {
            val chunk = nextChunk()
            if (chunk == null) {
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                }
                continue
            }
            pullChunk(chunk)
        }
    }

    private fun nextChunk(): Int? {
        synchronized(lock) {
            var best = -1
            var bestDist = Long.MAX_VALUE
            val here = (readerPos / chunkSize).toInt()
            for (i in 0 until chunkCount) {
                if (state[i] != STATE_IDLE) continue
                val d = abs(i - here).toLong()
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            if (best >= 0) state[best] = STATE_BUSY
            return if (best >= 0) best else null
        }
    }

    private fun pullChunk(chunk: Int) {
        val from = chunk.toLong() * chunkSize
        val to = min(from + chunkSize, total) - 1
        var ok = false
        try {
            val conn = URL(remoteUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Range", "bytes=$from-$to")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                Log.w(TAG, "第 $chunk 块 HTTP $code")
                conn.disconnect()
            } else {
                val ins = conn.inputStream
                val raf = file
                if (raf == null) {
                    conn.disconnect()
                    return
                }
                val buf = ByteArray(128 * 1024)
                var pos = from
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    synchronized(raf) {
                        raf.seek(pos)
                        raf.write(buf, 0, n)
                    }
                    pos += n
                }
                ins.close()
                conn.disconnect()
                ok = pos > from
            }
        } catch (e: Exception) {
            Log.w(TAG, "第 $chunk 块拉失败：$e")
        }
        synchronized(lock) {
            state[chunk] = if (ok) STATE_DONE else STATE_IDLE
            lock.notifyAll()
        }
        if (ok) {
            val done = synchronized(lock) { state.count { it == STATE_DONE } }
            if (done <= 6 || done % 20 == 0 || done == chunkCount) {
                Log.i(TAG, "已拉到 $done/$chunkCount 块")
            }
        }
    }

    fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        try {
            server?.close()
        } catch (e: Exception) {
        }
        for (t in workers) t.interrupt()
        try {
            file?.close()
        } catch (e: Exception) {
        }
        partFile?.delete()
        Log.i(TAG, "代理已停，临时文件已删")
    }
}
