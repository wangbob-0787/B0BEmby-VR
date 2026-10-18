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

    /** 档位偏好，从高到低试。 */
    private val DEFNS = listOf("uhd", "fhd", "shd", "hd")

    /** 两个平台号都试：101001 是网页端，11 是手机端。 */
    private val PLATFORMS = listOf(101001, 11)

    data class Hit(val url: String, val defn: String, val quality: String)

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

    /** 取整片地址；拿不到返回 null。 */
    fun fetch(vid: String, cookie: String?): Hit? {
        for (platform in PLATFORMS) {
            for (defn in DEFNS) {
                val hit = tryOnce(vid, defn, platform, cookie)
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun tryOnce(vid: String, defn: String, platform: Int, cookie: String?): Hit? {
        val u = "https://vv.video.qq.com/getinfo?otype=json&vid=$vid&platform=$platform" +
            "&sdtfrom=v1010&defn=$defn&vt=3&charge=0"
        val text = get(u, cookie) ?: return null
        val json = text.removePrefix("QZOutputJson=").trim().trimEnd(';')
        return try {
            val d = JSONObject(json)
            val vi = d.optJSONObject("vl")?.optJSONArray("vi") ?: return null
            if (vi.length() == 0) {
                Log.i(TAG, "整片地址：$vid defn=$defn platform=$platform → 没给（可能没权限）")
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
            val quality = if (h > 0) "${h}P" else defn
            Log.i(
                TAG,
                "整片地址：$vid defn=$defn platform=$platform → 拿到 $quality" +
                    "（${if (cookie.isNullOrBlank()) "无" else "有"}登录票，时长 ${v.optInt("td", 0)} 秒）",
            )
            Hit(full, defn, quality)
        } catch (t: Throwable) {
            Log.w(TAG, "整片地址：$vid defn=$defn 解析失败：${t.message}")
            null
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
