package com.xxxx.emby_vr.web

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机播放列表服务（2026-10-20 凌晨）。
 *
 * 【为什么要有它】
 * 腾讯网页的播放信息响应里一次就带着**整集全部分片**（头显实测 4K 共 285 段，每段自带签名）。
 * 扩展把这份清单拼成标准 m3u8 交给客户端，客户端要把它塞给 mpv —— 但 mpv 对 `file://` 的
 * 支持要看构建参数（我们这版没开 `--allow-share`），交给它一个本机 http 地址最稳。
 *
 * 【它只做一件事】
 * 把内存里那份 m3u8 原样吐给内核。清单里的分片地址都是腾讯的绝对地址（自带 token），
 * 内核按 mpv 的 `http-header-fields`（Referer / UA / Cookie，起播时已下）自己去取 ——
 * 所以这里不转发分片、不缓存、不碰字节流。段是 HTTPS，走 TLS，这条路只服务一个文本。
 *
 * 【端口】
 * 绑 127.0.0.1，端口由系统分配（0），只在本机可见，不对外暴露。
 */
class PlaylistServer {

    companion object {
        private const val TAG = "B0BEmbyVR-Hls"
    }

    private val body = AtomicReference<String>("")
    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    /** 起服务并把初始清单装进去；返回可直接交给内核的地址。 */
    fun start(m3u8: String): String? {
        body.set(m3u8)
        if (server == null) {
            val s = runCatching {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            }.getOrElse {
                Log.w(TAG, "本机播放列表服务起不来：" + it.message)
                return null
            }
            server = s
            val t = Thread({
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching { serve(c) }
                }
            }, "hls-list")
            t.isDaemon = true
            t.start()
            thread = t
            Log.i(TAG, "本机播放列表服务已起：127.0.0.1:" + s.localPort)
        }
        val p = server?.localPort ?: return null
        return "http://127.0.0.1:$p/list.m3u8"
    }

    /** 换一集时只换内容，端口不变。 */
    fun update(m3u8: String) {
        body.set(m3u8)
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 4000
            val input = c.getInputStream()
            /*
             * 只读请求行，别把后续的媒体流读进来 —— 内核可能对同一个地址发 Range 请求，
             * 我们不看它，直接整份返回（播放列表就几 KB，整份给最快）。
             */
            val sb = StringBuilder()
            var prev = 0
            while (sb.length < 512) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code && prev == '\r'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                prev = b
            }
            val line = sb.toString()
            val out = BufferedOutputStream(c.getOutputStream())
            if (line.startsWith("GET ")) {
                val payload = body.get().toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(payload)
            } else {
                out.write(
                    ("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
            }
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "回包失败：" + t.message)
        } finally {
            runCatching { c.close() }
        }
    }

    /** 调试用：把落盘的文件与内存里的内容对照（父亲要看"清单到底是不是给的这份"）。 */
    fun dumpTo(f: File): Boolean = runCatching {
        f.writeText(body.get())
        true
    }.getOrDefault(false)
}
