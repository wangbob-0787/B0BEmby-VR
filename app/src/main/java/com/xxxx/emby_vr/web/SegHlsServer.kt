package com.xxxx.emby_vr.web

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.ceil

/**
 * 腾讯网页分片 → 本机播放列表服务（2026-10-19 深夜，父亲定「先实现 1080P 分片逻辑」）。
 *
 * 【为什么要有它】
 * 腾讯网页播放器的分片（`…/NN_gzc_….fXXXXXX.1.ts?index=&start=&end=&token=`）是**一段一签**的：
 * 每段自带 token，改一个参数就 403（2026-10-19 01:15 实测）。而这些分片本身是**明文 MPEG-TS**
 * （720P = h264 1280×720，4K = hevc 3840×2160@60），下载速度 33–44 MB/s，完全不限速。
 *
 * 所以取片的唯一办法是：**趁页面自己在放的时候，把它请求过的分片地址一条条记下来**（扩展的
 * webRequest 通道看得见），再把这些本机拿到的地址按 index 排成一条播放列表交给内核。
 * 内核（mpv）要哪段我们就现取哪段 —— 拖动、整集时长都由内核按播放列表原生处理。
 *
 * 【为什么声明"整集时长"】
 * 分片是一条条陆续到手的，若只把已有分片写进列表，内核会以为片子只有几分钟、无法拖到后面。
 * 播放列表是按**整集时长**预先生成的：已知的分片写真实时长，还没到手的分片用默认时长占位；
 * 内核拖到某段时我们还没收到它 → 返回 404，内核会稍后重试（页面播放进度通常领先内核的取用）。
 */
class SegHlsServer(
    private val headers: Map<String, String>,
    /** 整集时长（秒）。0 表示未知 —— 那就只按已知分片发列表。 */
    private val totalDurSec: Int,
) {
    companion object {
        private const val TAG = "B0BEmbyVR-SegHls"

        /** 单段默认时长（毫秒）。腾讯网页分片实测 5.25 秒左右。 */
        private const val DEFAULT_SEG_MS = 5250

        /** URL 里的 `start=…&end=…` 是毫秒区间，能解析出真实时长。 */
        private val RANGE = Regex("[?&]start=(\\d+)&end=(\\d+)")
        private val INDEX = Regex("[?&]index=(\\d+)")

        fun segIndexOf(url: String): Int = INDEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        fun segDurMs(url: String): Long {
            val m = RANGE.find(url) ?: return DEFAULT_SEG_MS.toLong()
            val s = m.groupValues[1].toLongOrNull() ?: return DEFAULT_SEG_MS.toLong()
            val e = m.groupValues[2].toLongOrNull() ?: return DEFAULT_SEG_MS.toLong()
            val d = e - s
            return if (d in 500..60_000) d else DEFAULT_SEG_MS.toLong()
        }
    }

    /** index → 远端地址（本机已拿到的分片）。 */
    private val segs = ConcurrentSkipListMap<Int, String>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    val count: Int get() = segs.size

    fun put(index: Int, url: String) {
        if (index < 0 || url.isEmpty()) return
        val old = segs.put(index, url)
        if (old == null) Log.i(TAG, "收到分片 #$index（本机已有 ${segs.size} 段）")
    }

    /** 内核播放列表地址。 */
    fun playlistUrl(): String = "http://127.0.0.1:$port/playlist.m3u8"

    fun start(): Boolean {
        if (server != null) return true
        return try {
            val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            Thread({
                while (true) {
                    val s = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread({ serveOne(s) }, "seg-hls-conn").start()
                }
            }, "seg-hls-accept").start()
            Log.i(TAG, "分片播放列表服务已起：${playlistUrl()}（整集 ${totalDurSec}s）")
            true
        } catch (e: Exception) {
            Log.w(TAG, "分片播放列表服务起不来：${e.message}")
            false
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        segs.clear()
    }

    /** 按整集时长生成播放列表：已有分片写真实时长，未到手的写默认时长占位。 */
    private fun playlist(): String {
        val known = segs.keys
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
        val maxSegMs = if (known.isEmpty()) DEFAULT_SEG_MS.toLong()
        else known.maxOf { segDurMs(segs[it] ?: "") }.coerceAtLeast(DEFAULT_SEG_MS.toLong())
        sb.append("#EXT-X-TARGETDURATION:").append(ceil(maxSegMs / 1000.0).toInt()).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")

        // 已知分片的总时长；整集时长未知时就用已知分片拼出的长度
        val knownMs = if (known.isEmpty()) 0L else {
            val first = known.first()
            first * DEFAULT_SEG_MS.toLong() + known.sumOf { segDurMs(segs[it] ?: "") }
        }
        val targetMs = if (totalDurSec > 0) totalDurSec * 1000L else knownMs
        val segCount = if (targetMs <= 0) 0 else ceil(targetMs.toDouble() / DEFAULT_SEG_MS).toInt()

        var acc = 0L
        var i = 0
        while (i < segCount) {
            val url = segs[i]
            val dur = if (url != null) segDurMs(url) else DEFAULT_SEG_MS.toLong()
            sb.append("#EXTINF:").append(String.format("%.3f", dur / 1000.0)).append(",\n")
            sb.append("seg/").append(i).append(".ts\n")
            acc += dur
            i++
        }
        // 已知分片若超出估算的段数，补上，别把它们丢掉
        if (known.isNotEmpty()) {
            var k = segCount
            val last = known.last()
            while (k <= last) {
                val url = segs[k]
                val dur = if (url != null) segDurMs(url) else DEFAULT_SEG_MS.toLong()
                sb.append("#EXTINF:").append(String.format("%.3f", dur / 1000.0)).append(",\n")
                sb.append("seg/").append(k).append(".ts\n")
                acc += dur
                k++
            }
        }
        sb.append("#EXT-X-ENDLIST\n")
        Log.i(TAG, "发出播放列表：$segCount 段（已有 ${segs.size} 段，累计 ${acc / 1000}s）")
        return sb.toString()
    }

    private fun serveOne(s: Socket) {
        try {
            s.soTimeout = 20_000
            val input = BufferedInputStream(s.getInputStream())
            val req = StringBuilder()
            var c = input.read()
            while (c != -1) {
                req.append(c.toChar())
                if (req.endsWith("\r\n\r\n")) break
                c = input.read()
            }
            val line = req.lineSequence().firstOrNull() ?: ""
            val path = line.split(' ').getOrNull(1) ?: "/"
            val out = BufferedOutputStream(s.getOutputStream())
            when {
                path.startsWith("/playlist") -> {
                    val body = playlist().toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                    out.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                }
                path.startsWith("/seg/") -> {
                    val idx = path.removePrefix("/seg/").removeSuffix(".ts").toIntOrNull() ?: -1
                    val remote = if (idx >= 0) segs[idx] else null
                    if (remote == null) {
                        Log.w(TAG, "内核要第 $idx 段，但还没到手（已有 ${segs.size} 段，最大 ${segs.keys.lastOrNull() ?: -1}）")
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        Log.i(TAG, "喂第 $idx 段给内核")
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        copyRemote(remote, out)
                    }
                }
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "处理内核请求出错：${e.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    private fun copyRemote(url: String, out: BufferedOutputStream) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "远端分片返回 $code：${url.take(80)}")
                return
            }
            BufferedInputStream(conn.inputStream).use { ins ->
                val buf = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                Log.i(TAG, "这一段取完：$total 字节")
            }
        } catch (e: Exception) {
            Log.w(TAG, "取远端分片失败：${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
