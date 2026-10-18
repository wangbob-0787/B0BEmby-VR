package com.xxxx.emby_vr.web

import android.content.Context
import android.util.Log
import com.tencent.qqlive.ck.CKeyFacade
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 腾讯视频「要地址」验证入口（2026-10-18 转签名路线）。
 *
 * 官方客户端就三步（证据：`TVKCGIVkeyRequest` + `TVKConfigUrl`）：
 *   ① `https://vv6.video.qq.com/checktime` 拿服务器时间；
 *   ② 用 libckeygenerator.so 按 (guid, 时间, vid, appVer, platform, sdtfrom) 算 ckey；
 *   ③ 把表单 POST 到 `https://vv6.video.qq.com/getvkey`，响应是 XML，
 *      里面有 `url_info`（每段带 url/pt/hk）——拼出来就是能快进能跳的地址。
 *
 * 这一版**只验证能不能拿到地址**：把每种参数组合的结果打进日志，谁通就用谁。
 * 不用父亲戴头显操作，用 adb 触发（Intent 里带 `b0b_vkey_vid`）。
 */
object TencentVkeyProbe {

    private const val TAG = "B0BEmbyVR-Vkey"

    /** 试这几组参数：站台标识 × 平台号 × 清晰度。 */
    private val SDTFROMS = listOf("v3011", "v2010", "v3020")
    private val PLATFORMS = listOf(2, 11)
    private val FORMATS = listOf("fhd", "shd", "uhd")

    fun run(ctx: Context, vid: String, cookie: String?) {
        Thread {
            try {
                probe(vid, cookie)
            } catch (t: Throwable) {
                Log.e(TAG, "验证流程异常：${t.message}", t)
            }
        }.start()
    }

    private fun probe(vid: String, cookie: String?) {
        Log.i(TAG, "开始验证：vid=$vid cookie=${if (cookie.isNullOrBlank()) "无" else "有(${cookie.length} 字符)"}")

        /* 库加载与自报版本——加载不起来后面全是白搭，先探这一下 */
        val soVer = runCatching { CKeyFacade.getCKeyVersion() }.getOrElse { "加载失败:${it.message}" }
        Log.i(TAG, "签名库自报版本：$soVer")

        val serverTime = checkTime()
        Log.i(TAG, "服务器时间：$serverTime")

        /* guid 用一个稳定值即可（官方用的是设备标识） */
        val guid = "b0b" + android.os.Build.MODEL.hashCode().toString().replace("-", "")
        val appVer = "8.13.0.28536"

        var found = 0
        for (sdtfrom in SDTFROMS) {
            for (platform in PLATFORMS) {
                for (fmt in FORMATS) {
                    val r = tryOnce(vid, guid, serverTime, platform, sdtfrom, fmt, appVer, cookie)
                    if (r != null) found++
                }
            }
        }
        Log.i(TAG, "验证结束：${found} 组参数拿到了地址")
    }

    /** 一次完整尝试；拿到地址就打印，返回是否成功。 */
    private fun tryOnce(
        vid: String, guid: String, time: Long, platform: Int,
        sdtfrom: String, fmt: String, appVer: String, cookie: String?,
    ): String? {
        val tag = "sdtfrom=$sdtfrom platform=$platform fmt=$fmt"
        val ckey = runCatching { CKeyFacade.ckey(guid, time, vid, platform, appVer, sdtfrom) }
            .getOrElse { "" }
        if (ckey.isBlank()) {
            Log.w(TAG, "$tag → ckey 算不出来（空）")
            return null
        }
        Log.i(TAG, "$tag → ckey=${ckey.take(24)}…（${ckey.length} 字符）")

        val fields = linkedMapOf(
            "vid" to vid,
            "vt" to "3",
            "platform" to platform.toString(),
            "appVer" to appVer,
            "sdtfrom" to sdtfrom,
            "format" to fmt,
            "newnettype" to "1",
            "encryptVer" to "4.1",
            "cKey" to ckey,
            "lnk" to "vqq",
            "linkver" to "2",
        )
        val xml = post("https://vv6.video.qq.com/getvkey", fields, cookie) ?: return null
        val head = xml.take(160).replace("\n", " ")
        if (!xml.contains("<url")) {
            Log.w(TAG, "$tag → 没地址：$head")
            return null
        }
        val urls = pickUrls(xml, sdtfrom)
        if (urls.isEmpty()) {
            Log.w(TAG, "$tag → XML 里有 url 但拼不出来：$head")
            return null
        }
        Log.i(TAG, "$tag → ✅ 拿到 ${urls.size} 段，第一段：${urls[0]}")
        return urls[0]
    }

    /** 把 XML 里的 url/pt/hk 拼成可播地址（照官方 builder 的拼法）。 */
    private fun pickUrls(xml: String, sdtfrom: String): List<String> {
        val out = ArrayList<String>()
        for (block in xml.split("<url_info").drop(1)) {
            val u = between(block, "<url>", "</url>") ?: continue
            val pt = between(block, "<pt>", "</pt>") ?: ""
            val hk = between(block, "<hk>", "</hk>") ?: ""
            val full = when {
                u.contains(".m3u8") || u.contains(".mp4") ->
                    "$u$pt" + (if (hk.isBlank()) "" else "&hlskey=$hk") + "&sdtfrom=$sdtfrom"
                else -> "$u$pt" + (if (hk.isBlank()) "" else "&hlskey=$hk") + "&sdtfrom=$sdtfrom"
            }
            out.add(full)
        }
        return out
    }

    private fun between(s: String, a: String, b: String): String? {
        val i = s.indexOf(a)
        if (i < 0) return null
        val j = s.indexOf(b, i + a.length)
        if (j < 0) return null
        return s.substring(i + a.length, j)
    }

    private fun checkTime(): Long {
        val body = get("https://vv6.video.qq.com/checktime?otype=json") ?: return System.currentTimeMillis() / 1000
        /* 形如 {"s":"1700000000"} 或 XML；两种都兜一下 */
        val m = Regex("\"s\"\\s*:\\s*\"?(\\d{10})").find(body)
        if (m != null) return m.groupValues[1].toLong()
        val m2 = Regex(">(\\d{10})<").find(body)
        if (m2 != null) return m2.groupValues[1].toLong()
        Log.w(TAG, "服务器时间没解析出来，用本机时间兜底：${body.take(120)}")
        return System.currentTimeMillis() / 1000
    }

    private fun post(url: String, fields: Map<String, String>, cookie: String?): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "qqlive")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val body = fields.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Log.i(TAG, "POST $url → HTTP $code，${text.length} 字节")
            if (code !in 200..299) null else text
        } catch (t: Throwable) {
            Log.w(TAG, "POST $url 失败：${t.message}")
            null
        }
    }

    private fun get(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "qqlive")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url 失败：${t.message}")
            null
        }
    }
}
