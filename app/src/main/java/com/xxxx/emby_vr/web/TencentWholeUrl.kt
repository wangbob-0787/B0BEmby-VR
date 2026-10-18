package com.xxxx.emby_vr.web

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 腾讯视频「整片地址」（2026-10-18 父亲拍板走这条路）。
 *
 * 为什么是这条：网页端把片子切成十几秒一段、**一段一签**，只能顺播、不能拖进度；
 * 客户端那条要算签名（得借腾讯的小库）。而腾讯的老接口 `vv.video.qq.com/getinfo`
 * 不需要任何签名，返回的就是**整片一个文件**，并且支持分段请求（HTTP 206）——
 * 也就是说能任意拖进度。2026-10-18 在 .18 上实测：
 *   · 不带登录身份也能拿到 720P（480P/720P 两档）；
 *   · 带上会员票（网页里那份 Cookie）就能拿到更高档；
 *   · 请求 `Range: bytes=0-99` 返回 206，可拖。
 *
 * 用法：`fetch(vid, cookie)` → 拿到地址和档位，交给自己的播放器。
 * 参数 `defn` 是偏好档位（uhd 4K / fhd 1080P / shd 720P / hd 480P），
 * 没权限的档位接口会拒绝，所以从高到低试。
 */
object TencentWholeUrl {

    private const val TAG = "B0BEmbyVR-Vkey"

    /**
     * 参数组合矩阵（2026-10-18 下午实测后的第二版）。
     *
     * 现象：不带这些参数时，接口对会员片只给**试看片段**（实测 3 分 25 秒、
     * 486P~720P），而响应里的 `td` 写着正片 2819 秒 —— 说明"整片"没给。
     * 官方客户端/网页要拿到整集，请求里得带清晰度权益相关的开关（`defnpayver`
     * 试出能多给一档 1080P）、付费标记 `charge` 与站点号。这里把组合一次跑完，
     * 并把每个组合**实际文件大小**（HEAD 的 Content-Length）打出来——
     * 大小才是"给的是整集还是试看"的硬证据。
     */
    private data class Combo(
        val defn: String,
        val platform: Int,
        val charge: Int,
        val extra: String,
        val label: String,
    )

    private val COMBOS = listOf(
        Combo("fhd", 101001, 0, "&defnpayver=1", "网页端+1080P权益"),
        Combo("fhd", 101001, 0, "&defnpayver=1&fhdswitch=1", "网页端+1080P+开关"),
        Combo("fhd", 101001, 1, "&defnpayver=1", "网页端+付费标记1"),
        Combo("shd", 101001, 1, "&defnpayver=1", "网页端720P+付费标记1"),
        Combo("uhd", 101001, 1, "&defnpayver=1&uhdswitch=1", "网页端4K+开关"),
        Combo("fhd", 101001, 1, "&defnpayver=1&spwm=4", "网页端+spwm4"),
        Combo("fhd", 2, 1, "&defnpayver=1", "平台2"),
        Combo("fhd", 11, 1, "&defnpayver=1", "平台11"),
        Combo("fhd", 101001, 0, "", "基准（试看对照）"),
    )

    data class Hit(val url: String, val defn: String, val quality: String, val size: Long = 0L)

    /**
     * 从页面地址里抠出视频号。
     *
     * 腾讯的片子页长这样：`https://v.qq.com/x/cover/<剧集号>/<视频号>.html`，
     * 也有 `?vid=<视频号>` 的形式，两种都认。
     */
    fun vidFromFrame(frame: String): String? {
        if (frame.isBlank()) return null
        if (!frame.contains("qq.com")) return null
        Regex("[?&]vid=([A-Za-z0-9]{8,16})").find(frame)?.let { return it.groupValues[1] }
        Regex("/([A-Za-z0-9]{10,12})\\.html").find(frame)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * 取整片地址；拿不到返回 null。
     *
     * `deep` = true 时把所有参数组合都试一遍（探针用，父亲要看看到底哪组给整集）；
     * false 时只试最可能给整集的前两组（正常播放用，省时间）。
     */
    fun fetch(vid: String, cookie: String?, deep: Boolean = false): Hit? {
        var best: Hit? = null
        val list = if (deep) COMBOS else COMBOS.take(4)
        for (c in list) {
            val hit = tryOnce(vid, c, cookie) ?: continue
            if (!deep) return hit
            if (best == null || hit.size > best.size) best = hit
        }
        return best
    }

    private fun tryOnce(vid: String, c: Combo, cookie: String?): Hit? {
        val u = "https://vv.video.qq.com/getinfo?otype=json&vid=$vid&platform=${c.platform}" +
            "&sdtfrom=v1010&defn=${c.defn}&vt=3&charge=${c.charge}${c.extra}"
        val text = get(u, cookie) ?: return null
        val json = text.removePrefix("QZOutputJson=").trim().trimEnd(';')
        return try {
            val d = JSONObject(json)
            val vi = d.optJSONObject("vl")?.optJSONArray("vi") ?: return null
            if (vi.length() == 0) {
                Log.i(TAG, "整片地址[${c.label}] → 没给（可能没权限）")
                return null
            }
            val v = vi.optJSONObject(0) ?: return null
            val base = v.optJSONObject("ul")?.optJSONArray("ui")
                ?.optJSONObject(0)?.optString("url").orEmpty()
            if (base.isBlank()) return null
            val fn = v.optString("fn")
            val key = v.optString("fvkey").ifBlank { v.optString("vkey") }
            val full = base + fn + (if (key.isBlank()) "" else "?vkey=$key")
            val w = v.optInt("vw", 0)
            val h = v.optInt("vh", 0)
            val quality = if (h > 0) "${h}P" else c.defn
            val size = head(full, cookie)
            /*
             * 关键判定字段（2026-10-18 晚）：`preview` 是"允许看多少秒"——
             * 实测未登录时 preview=180（就是父亲看到的 3 分多钟），
             * `login=0` 说明服务器**没认出我们的登录身份**。这两个字段一变化，
             * 就说明身份生效了、能拿整集了。所以每组都打出来，别只看清晰度。
             */
            Log.i(
                TAG,
                "整片地址[${c.label}] → $quality 文件 ${size / 1048576}MB，" +
                    "preview=${d.optInt("preview", -1)} login=${d.optInt("login", -1)}" +
                    " 码率=${v.optInt("br", 0)} 正片时长=${v.optInt("td", 0)}秒 票=${cookieShape(cookie)}",
            )
            Hit(full, c.defn, quality, size)
        } catch (t: Throwable) {
            Log.w(TAG, "整片地址[${c.label}] 解析失败：${t.message}")
            null
        }
    }

    /** 只看票里有哪些名字（不打印值）：判断"服务器认不认这份身份"要看它带没带关键的几个。 */
    private fun cookieShape(cookie: String?): String {
        if (cookie.isNullOrBlank()) return "无"
        val names = cookie.split(';').mapNotNull { it.substringBefore('=').trim().ifBlank { null } }
        val key = listOf("uin", "p_uin", "vusession", "p_skey", "skey", "vqq_vusession", "luin")
            .filter { names.contains(it) }
        return "${names.size}条[${key.joinToString(",")}]"
    }

    /** 问一下文件多大：大小是"整集还是试看"的硬证据。 */
    private fun head(url: String, cookie: String?): Long {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val n = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            conn.disconnect()
            n
        } catch (t: Throwable) {
            0L
        }
    }

    private fun get(url: String, cookie: String?): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "整片地址：HTTP $code")
                return null
            }
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (t: Throwable) {
            Log.w(TAG, "整片地址：请求失败 ${t.message}")
            null
        }
    }
}
