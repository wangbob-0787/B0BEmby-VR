package com.xxxx.emby_vr.web

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 爱奇艺 dash 自取（2026-10-21 15:10 定案，父亲实测 1080P 比上午那份清楚得多）。
 *
 * 三步：
 *  1. 页面给出：片号 tvid、登录身份（cookie P00003 / QC005 / dfp）——只读，不改请求层；
 *  2. 本地自己签 dash 请求：`vf = md5("/dash?" + 参数串 + 固定盐)`（盐由页面 `$j()` 生成，32 字符常量）；
 *  3. 返回里 `program.video[i].m3u8` 就是整集片单（702 段 / 45 分钟 / pcw-data 主机，长期有效）。
 *
 * 档位：`bid=600` 是 1080P（vsize 约 684MB）；`bid=500` 是 720P。
 * 实测能力位（k_ft1 等）必须与页面当前浏览器一致，否则会被服务器压到 720P —— 这里用 2026-10-21 15:05 抓到的整套常量。
 */
object IqiyiDash2 {

    private const val TAG = "B0BEmbyVR"

    /** 固定盐：页面里 $j() 用公式生成的 32 字符 */
    private const val SALT = "ulc2h7tka0mdrf2lkb1n6m6mulc2htbn"

    /** 页面请求里那套常量参数（2026-10-21 15:05 抓自当前 Chrome，含 1080P 能力位） */
    private val CONST = linkedMapOf(
        "src" to "01010031010000000000",
        "ori" to "pcw",
        "ps" to "0",
        "pt" to "0",
        "d" to "0",
        "s" to "",
        "lid" to "undefined",
        "cf" to "0",
        "ct" to "0",
        "k_tag" to "1",
        "locale" to "zh_cn",
        "up" to "",
        "qd_v" to "a1",
        "ppt" to "0",
        "k_ft1" to "704237197590532",
        "k_ft4" to "1170017878024196",
        "k_ft5" to "17592337039361",
        "k_ft7" to "26422940794880",
        "k_ftx2" to "g",
        "fr_300" to "120_120_120_120_120_120",
        "fr_500" to "120_120_120_120_120_120",
        "fr_600" to "120_120_120_120_120_120",
        "fr_800" to "120_120_120_120_120_120",
        "bop" to "%7B%22version%22%3A%2210.0%22%2C%22dfp%22%3A%22a084c6039f4f1d4c7da12402b0c503665e7087da9b29304c06de5fdddb79fd744c%22%2C%22b_ft1%22%3A60%7D",
        "sr" to "1",
        "ost" to "0",
        "ut" to "58",
        "vt" to "0",
        "rs" to "1",
    )

    data class Result(val m3u8: String, val segs: Int, val durationSec: Int, val bid: Int)

    /**
     * 这一集的**结构化**信息（2026-10-21 21:55，父亲定「文案完全按结构数据构造」）。
     *
     * 来源：`https://pcw-api.iqiyi.com/video/video/playervideoinfo?tvid=<tvid>`
     * 实测（tvid=5598014642465900）：
     *   an = "蒸蒸日上"（剧名）· pd = 2（第几集）· es = 22（总集数）
     *   vn = "蒸蒸日上第2集" · info = 剧情简介
     * 集名（这一集叫什么）不在这里，由页面侧 `QiyiPlayerProphetData.videoInfo.title` 提供。
     */
    data class EpInfo(val series: String, val episode: Int, val epName: String, val total: Int)

    fun episodeInfo(tvid: String, cookie: String?, userAgent: String?): EpInfo? {
        if (tvid.isBlank()) return null
        val body = runCatching {
            get(
                "https://pcw-api.iqiyi.com/video/video/playervideoinfo?tvid=" + tvid,
                cookie,
                userAgent,
            )
        }.onFailure {
            /* 2026-10-22：头显上这条请求没成过（日志里只有"结构化=空"），把原因打出来 */
            Log.w(TAG, "爱奇艺结构化：请求失败 " + it.javaClass.simpleName + " " + it.message)
        }.getOrNull() ?: return null
        return runCatching {
            val d = org.json.JSONObject(body).optJSONObject("data") ?: return null
            val series = d.optString("an").ifBlank { d.optString("vn") }
            val ep = d.optInt("pd", 0)
            val total = d.optInt("es", 0)
            if (series.isBlank() && ep == 0) return null
            Log.i(TAG, "爱奇艺结构化：剧名=" + series + " 第" + ep + "集（共 " + total + " 集）")
            EpInfo(series, ep, "", total)
        }.getOrNull()
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 构造并请求 dash。
     *
     * @param tvid 片号（页面里读）
     * @param uid  登录用户号（cookie P00003）
     * @param kuid 设备号（cookie QC005）
     * @param dfp  设备指纹（localStorage __dfp，取 @ 之前那段）
     * @param bid  "600"=1080P，"500"=720P
     */
    fun fetch(
        tvid: String,
        uid: String,
        kuid: String,
        dfp: String,
        bid: String = "600",
        cookie: String? = null,
        userAgent: String? = null,
    ): Result? {
        if (tvid.isBlank()) { Log.w(TAG, "爱奇艺自取：没有片号"); return null }
        val sb = StringBuilder()
        for ((k, v) in CONST) sb.append(k).append('=').append(v).append('&')
        sb.append("tvid=").append(tvid).append('&')
        sb.append("bid=").append(bid).append('&')
        sb.append("vid=&")
        sb.append("uid=").append(uid).append('&')
        sb.append("k_uid=").append(kuid).append('&')
        sb.append("dfp=").append(dfp).append('&')
        sb.append("authKey=&")
        sb.append("tm=").append(System.currentTimeMillis())
        val path = "/dash?" + sb.toString()
        val url = "https://cache.video.iqiyi.com" + path + "&vf=" + md5(path + SALT)
        val body = runCatching { get(url, cookie, userAgent) }.getOrNull()
        if (body == null) { Log.w(TAG, "爱奇艺自取：请求失败"); return null }
        return runCatching { parse(body) }.getOrNull()
    }

    private fun get(url: String, cookie: String?, ua: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        c.setRequestProperty("Referer", "https://www.iqiyi.com/")
        c.setRequestProperty("Accept", "*/*")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun parse(body: String): Result? {
        val root = org.json.JSONObject(body)
        val data = root.optJSONObject("data") ?: return null
        val boss = data.optJSONObject("boss_ts")
        if (boss != null) {
            val code = boss.optString("code")
            if (code.isNotEmpty() && code != "A00000") {
                Log.w(TAG, "爱奇艺自取：鉴权失败 " + code + " " + boss.optString("msg"))
                return null
            }
        }
        val program = data.optJSONObject("program") ?: return null
        val videos = program.optJSONArray("video") ?: return null
        var best: org.json.JSONObject? = null
        var bestSegs = 0
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val pl = v.optString("m3u8", "")
            if (pl.isBlank()) continue
            val n = pl.split("#EXTINF").size - 1
            if (n > bestSegs) { bestSegs = n; best = v }
        }
        val pick = best ?: return null
        var pl = pick.optString("m3u8", "")
        if (!pl.contains("#EXT-X-PLAYLIST-TYPE")) {
            pl = pl.replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD")
        }
        if (!pl.contains("#EXT-X-ENDLIST")) pl = pl.trimEnd() + "\n#EXT-X-ENDLIST\n"
        val bid = pick.optInt("bid", 0)
        Log.i(TAG, "爱奇艺自取成功：bid=" + bid + "、段数 " + bestSegs + "、时长 " + pick.optInt("duration", 0) + "s")
        return Result(pl, bestSegs, pick.optInt("duration", 0), bid)
    }

    /** 把片单落盘（内核按本地文件当 VOD 播） */
    fun writeTo(dir: java.io.File, pl: String): java.io.File? = runCatching {
        val d = java.io.File(dir, "vr-hls").apply { mkdirs() }
        java.io.File(d, "iq-" + System.currentTimeMillis() + ".m3u8").also { it.writeText(pl) }
    }.getOrNull()
}
