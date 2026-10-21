package com.xxxx.emby_vr.web

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 爱奇艺整集片单拦截（2026-10-21）。
 *
 * 背景（今天上午的实测结论）：
 *  - 爱奇艺正片是「多个约 2–6 分钟的 .ts 文件接力」，只拿一段只能播那几分钟；
 *    整集的完整片单在取流接口的返回里（`cache.video.iqiyi.com/dash?...`）——
 *    `data.program.video[i].m3u8` 就是整集 HLS 文本（实测那份 689 段 / 45 分 25 秒）。
 *  - 但这条请求发在播放器的后台线程里，**页面脚本看不到**（performance 表里 0 条、挂钩 fetch/XHR 也抓不到）。
 *  - 于是改在**应用侧网络层**截：crwebview 的 WebViewClient 支持 `shouldInterceptRequest`，
 *    它能看到这个 WebView 发出的全部请求（含渲染进程/后台线程的）。
 *
 * 这里做的事：截到 dash 请求后**自己带 cookie/UA/Referer 重新拉一遍**（内容一致，原样回给页面），
 * 顺手把整集 m3u8 抠出来存下。父亲按"真正的全屏"时，原生把这份片单交给内核播。
 */
object IqiyiDash {

    private const val TAG = "B0BEmbyVR"

    /** 最新一份整集片单（m3u8 原文） */
    @Volatile
    var playlist: String? = null
        private set

    /** 这份片单的元信息，日志与去重用 */
    @Volatile
    var segCount: Int = 0
        private set
    @Volatile
    var mediaHeight: Int = 0
        private set
    @Volatile
    var capturedAt: Long = 0L
        private set
    @Volatile
    var frameUrl: String = ""
        private set

    /**
     * 整集片单的**直链**（2026-10-21 13:15 定案）。
     *
     * 实测：页面对 `https://meta-cdn.video.iqiyi.com/.../xxx.m3u8?qd_...` 的响应就是整集片单
     * （那次 702 段 / 2726 秒）。这条地址**不带 cookie 也能取**（.18 上 curl 200、19 万字节）。
     * 所以截到地址就够了：直接交给 mpv，让它自己按 HLS 播整集、自己拖动。
     */
    @Volatile
    var m3u8Url: String? = null
        private set
    @Volatile
    var m3u8At: Long = 0L
        private set

    /**
     * 当前这集的片号（tvid）。弹幕接口按它取：
     * `https://cmts.iqiyi.com/bullet/<tvid[-4:-2]>/<tvid[-2:]>/<tvid>_300_<n>.z`
     * 取流接口的查询串里就带 `tvid=`，顺手记下来。
     */
    @Volatile
    var tvid: String = ""
        private set

    fun noteTvid(u: String) {
        val m = Regex("tvid=(\\d{6,})").find(u) ?: return
        val v = m.groupValues[1]
        if (v != tvid) {
            tvid = v
            android.util.Log.i(TAG, "爱奇艺片号：" + v)
        }
    }

    fun isPlaylistUrl(u: String): Boolean {
        if (!u.contains(".m3u8")) return false
        return u.contains("iqiyi.com") || u.contains("71edge.com") || u.contains("qy.net")
    }

    /** 记下整集片单直链（由 shouldInterceptRequest 调用，只记不改请求） */
    fun notePlaylistUrl(u: String) {
        if (!isPlaylistUrl(u)) return
        if (u == m3u8Url) return
        m3u8Url = u
        m3u8At = System.currentTimeMillis()
        android.util.Log.i(TAG, "爱奇艺整集片单直链到手：" + u.take(110))
    }

    fun playlistFresh(maxAgeMs: Long = 30 * 60 * 1000L): Boolean =
        m3u8Url != null && System.currentTimeMillis() - m3u8At in 0..maxAgeMs

    fun isDashUrl(u: String): Boolean =
        u.contains("cache.video.iqiyi.com/dash?") || u.contains("cache.video.iqiyi.com/dash/")

    fun fresh(maxAgeMs: Long = 30 * 60 * 1000L): Boolean =
        playlist != null && System.currentTimeMillis() - capturedAt in 0..maxAgeMs

    /**
     * 截到 dash 请求：自己拉一遍，解析出整集片单，并把原始响应回给页面。
     *
     * 由 `shouldInterceptRequest` 在**子线程**调用，所以这里可以直接阻塞做网络请求。
     */
    fun intercept(url: String, cookie: String?, userAgent: String?): ByteArray? {
        val body = runCatching { fetch(url, cookie, userAgent) }.getOrNull()
        if (body == null) {
            Log.w(TAG, "爱奇艺片单：自己拉取失败（交给页面按原样去请求）")
            return null
        }
        runCatching { parseAndStore(body, url) }
        return body
    }

    private fun fetch(url: String, cookie: String?, userAgent: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            userAgent ?: ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
        )
        c.setRequestProperty("Referer", "https://www.iqiyi.com/")
        c.setRequestProperty("Accept", "*/*")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return c.inputStream.use { it.readBytes() }
    }

    /**
     * 解析 dash 返回，挑"段数最多"的那一档当整集片单。
     *
     * 返回里的 5 个画质档只有**当前正在播的那一档**带完整 `m3u8` 文本（其余为空），
     * 所以这里的挑选其实是稳的；仍按段数取最大，防止出现半份。
     */
    private fun parseAndStore(body: ByteArray, url: String) {
        val text = String(body, Charsets.UTF_8)
        val root = org.json.JSONObject(text)
        val data = root.optJSONObject("data") ?: return
        val program = data.optJSONObject("program") ?: return
        val videos = program.optJSONArray("video") ?: return
        var best: org.json.JSONObject? = null
        var bestSegs = 0
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val m3u8 = v.optString("m3u8", "")
            val segs = countSegs(m3u8)
            if (segs > bestSegs) {
                bestSegs = segs
                best = v
            }
        }
        val pick = best
        if (pick == null || bestSegs < 20) {
            Log.i(TAG, "爱奇艺片单：这份 dash 没有可用整集清单（最多 $bestSegs 段），忽略")
            return
        }
        var pl = pick.optString("m3u8", "")
        /* 播放器当"直播"只播几秒：补 VOD 头与结束标记（实测有效） */
        if (!pl.contains("#EXT-X-PLAYLIST-TYPE")) {
            pl = pl.replaceFirst(
                "#EXTM3U",
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD",
            )
        }
        if (!pl.contains("#EXT-X-ENDLIST")) pl = pl.trimEnd() + "\n#EXT-X-ENDLIST\n"
        playlist = pl
        segCount = bestSegs
        mediaHeight = pick.optInt("height", 0)
        capturedAt = System.currentTimeMillis()
        frameUrl = url
        Log.i(
            TAG,
            "爱奇艺片单到手：$bestSegs 段、时长 ${pick.optInt("duration", 0)}s、" +
                "高 ${pick.optInt("height", 0)}，文本 ${pl.length} 字符",
        )
    }

    private fun countSegs(s: String): Int {
        if (s.isBlank()) return 0
        var n = 0
        var i = s.indexOf("#EXTINF")
        while (i >= 0) {
            n++
            i = s.indexOf("#EXTINF", i + 7)
        }
        return n
    }

    /** 把整集片单包成 `kind:hls` 消息（复用已跑通的整集清单通道） */
    fun toHlsMessage(title: String?): String? {
        val pl = playlist ?: return null
        if (!fresh()) return null
        val o = org.json.JSONObject()
        o.put("kind", "hls")
        o.put("text", pl)
        o.put("tier", "iq1080")
        o.put("segs", segCount)
        o.put("height", if (mediaHeight > 0) mediaHeight else 1066)
        o.put("first", 0)
        o.put("frame", frameUrl)
        o.put("title", title ?: "")
        return o.toString()
    }

    fun responseBytes(): ByteArray? = playlist?.toByteArray(Charsets.UTF_8)

    /** 给页面回一份"最小可用"的空响应（自己拉失败也不让页面卡住） */
    fun emptyResponse(): ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
}
