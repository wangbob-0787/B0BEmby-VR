package com.xxxx.emby_vr.web

import android.util.Log
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 爱奇艺整集清单的本机转发服务（2026-10-24 定案）。
 *
 * 【为什么必须转发，不能把清单直接交给内核】
 * 实测（`docs/ledger/3站取地址测试及槽内各格内容获取方法.md` §3.5）：
 *  - 单条分片：`curl -L` 跟 302 后 200、1.19MB；ffmpeg 单条解码正常（h264 1920×1080 + aac）。
 *  - 整集清单交给 mpv：全部段 `Failed to open segment N` → `avformat_open_input() failed`。
 *    原因是清单里每一段都会 302 跳到不同 CDN 节点，ffmpeg 复用长连接后打不开
 *    （`Cannot reuse HTTP connection for different host: … != pcw-data.video.iqiyi.com`）。
 *    ffmpeg 命令行加 `-http_persistent 0` 就能播，但 mpv 没有可用的开关。
 *  - 所以由本机服务接清单：把每段改成本机地址，取回时逐条跟着跳转，再原样回给内核。
 *
 * 【腾讯那份清单不需要它】腾讯的分片地址自带 token、内核直接能取，所以仍走 PlaylistServer。
 */
class IqProxyServer {

    companion object {
        private const val TAG = "B0BEmbyVR-Iq"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }

    /** 每份清单一个 id，段表与原文分开存，旧地址永远返回旧内容（与 PlaylistServer 同一套防串集做法） */
    private val segsOf = ConcurrentHashMap<String, List<String>>()
    /** 每份清单的改写结果（保留原 #EXTINF，只换分片地址） */
    private val bodies = ConcurrentHashMap<String, String>()
    private val order = ConcurrentLinkedQueue<String>()
    private val seq = AtomicLong(0)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var port: Int = 0

    /** 起服务并把这份清单装进去，返回可直接交给内核的本机地址 */
    fun start(m3u8: String): String? {
        if (!ensureServer()) return null
        val id = "q" + seq.incrementAndGet()
        val segs = ArrayList<String>()
        val out = StringBuilder()
        for (raw in m3u8.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("http")) {
                val idx = segs.size
                segs.add(line)
                out.append("http://127.0.0.1:").append(port)
                    .append("/s").append(id).append('/').append(idx).append('\n')
            } else if (line.isNotEmpty()) {
                /* 原有标签（#EXTINF 时长、#EXT-X-* ）原样保留，只换分片地址 */
                out.append(line).append('\n')
            }
        }
        if (segs.isEmpty()) {
            Log.w(TAG, "爱奇艺转发：清单里没有分片地址，放弃")
            return null
        }
        bodies[id] = out.toString()
        segsOf[id] = segs
        order.add(id)
        while (order.size > 4) {
            val old = order.poll() ?: break
            segsOf.remove(old)
            bodies.remove(old)
        }
        Log.i(TAG, "爱奇艺转发就绪：" + segs.size + " 段 → http://127.0.0.1:$port/p$id.m3u8")
        return "http://127.0.0.1:$port/p$id.m3u8?sig=$id-${segs.size}"
    }

    private fun ensureServer(): Boolean {
        if (server != null) return true
        synchronized(this) {
            if (server != null) return true
            val ss = runCatching { ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull()
                ?: run {
                    Log.w(TAG, "爱奇艺转发：本机端口起不来")
                    return false
                }
            port = ss.localPort
            server = ss
            val t = Thread {
                while (!ss.isClosed) {
                    val c = runCatching { ss.accept() }.getOrNull() ?: break
                    Thread { runCatching { handle(c) } }.start()
                }
            }
            t.isDaemon = true
            t.name = "iq-hls-proxy"
            t.start()
            thread = t
            return true
        }
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 30000
            val input = sock.getInputStream().bufferedReader()
            val reqLine = input.readLine() ?: return
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
            }
            val path = reqLine.split(' ').getOrNull(1) ?: return
            val os = BufferedOutputStream(sock.getOutputStream())
            fun fail(code: Int, msg: String) {
                val b = msg.toByteArray()
                os.write(("HTTP/1.1 $code $msg\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
                os.write(b)
                os.flush()
            }
            if (path.startsWith("/p") && path.contains(".m3u8")) {
                val id = path.substringAfter('/').substringBefore(".m3u8").removePrefix("p")
                val text = bodies[id]
                if (text == null) { fail(404, "no playlist"); return }
                val body = text.toByteArray()
                os.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                os.write(body)
                os.flush()
                return
            }
            if (path.startsWith("/s")) {
                val rest = path.substringAfter("/s")
                val id = rest.substringBefore('/')
                val idx = rest.substringAfter('/').substringBefore('?').toIntOrNull() ?: -1
                val segs = segsOf[id]
                if (segs == null || idx < 0 || idx >= segs.size) { fail(404, "no segment"); return }
                val bytes = fetch(segs[idx])
                if (bytes == null) { fail(502, "upstream failed"); return }
                os.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                os.write(bytes)
                os.flush()
                return
            }
            fail(404, "not found")
        }
    }

    /** 取一段：跟随 302 到 CDN 节点，带 Referer 与浏览器 UA（实测不带 cookie 也能取到） */
    private fun fetch(u: String): ByteArray? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://www.iqiyi.com/")
        c.inputStream.use { it.readBytes() }
    }.onFailure { Log.w(TAG, "爱奇艺转发：取分片失败 " + it.javaClass.simpleName + " " + it.message) }.getOrNull()

    fun stop() {
        runCatching { server?.close() }
        server = null
        segsOf.clear()
        bodies.clear()
        order.clear()
    }
}
