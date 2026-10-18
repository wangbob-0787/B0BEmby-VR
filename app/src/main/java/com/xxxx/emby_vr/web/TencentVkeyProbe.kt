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
 * 官方客户端就三步（证据：`TVKCGIVkeyRequest` + `TVKConfigUrl` +
 * `com.tencent.qqlive.ck.CKeyFacade.init`）：
 *   ① `https://vv6.video.qq.com/checktime` 拿服务器时间；
 *   ② 先初始化签名库，再按 (guid, 时间, vid, appVer, platform, sdtfrom) 算 ckey；
 *      官方初始化顺序（照抄官方类的 init 方法）：
 *        CkeyMoudleInit(应用私有目录, guid, "", ctx)
 *        SetIpPort("rlog.video.qq.com", 8080, "bkrlog.video.qq.com", 8080)
 *        Registration(<设备标识>, <版本>, <SDK版本>, <平台号>, <设备标识>, ctx)
 *   ③ 表单 POST `https://vv6.video.qq.com/getvkey`，响应是 XML，
 *      `url_info` 里每段带 url/pt/hk —— 拼出来就是能快进能跳的地址。
 *
 * 首轮实测（r686）：库能加载（自报版本 ckey.5104.5.1.002），但没初始化，
 * GenCKey 返回空 → 这一版补上初始化，并把几组参数组合一次跑完（一轮编译覆盖）。
 */
object TencentVkeyProbe {

    private const val TAG = "B0BEmbyVR-Vkey"

    private val SDTFROMS = listOf("v3011", "v2010", "v3020")
    private val PLATFORMS = listOf(2, 11)
    private val FORMATS = listOf("fhd", "shd", "uhd")

    /** 初始化组合：官方三个字符串参数的几种可能含义，逐个试。 */
    private fun initVariants(guid: String): List<Triple<String, String, String>> = listOf(
        Triple("", "", ""),           // 只给目录：最保守
        Triple(guid, "", "1"),
        Triple("1", guid, "1"),
        Triple(guid, "1", "1"),
    )

    fun run(ctx: Context, vid: String, cookie: String?) {
        Thread {
            try {
                probe(ctx.applicationContext, vid, cookie)
            } catch (t: Throwable) {
                Log.e(TAG, "验证流程异常：${t.message}", t)
            }
        }.start()
    }

    private fun probe(ctx: Context, vid: String, cookie: String?) {
        Log.i(TAG, "开始验证：vid=$vid cookie=${if (cookie.isNullOrBlank()) "无" else "有(${cookie.length} 字符)"}")

        val soVer = runCatching { CKeyFacade.getCKeyVersion() }.getOrElse { "加载失败:${it.message}" }
        Log.i(TAG, "签名库自报版本：$soVer")

        val serverTime = checkTime()
        Log.i(TAG, "服务器时间：$serverTime")

        val guid = "b0b" + (android.os.Build.MODEL.hashCode().toString().replace("-", ""))
        val appVer = "8.13.0.28536"

        /* ① 把库初始化起来：官方 init 方法里的三步，字符串参数按几种可能逐个试 */
        var inited = false
        for ((a, b, c) in initVariants(guid)) {
            val ok = runCatching {
                val fac = CKeyFacade()
                fac.CkeyMoudleInit(ctx.filesDir.path, if (a.isEmpty()) guid else a, b, ctx)
                CKeyFacade.SetIpPort("rlog.video.qq.com", 8080, "bkrlog.video.qq.com", 8080)
                fac.Registration(c.ifEmpty { guid }, appVer, "1.0.0", 2, guid, ctx)
                true
            }.getOrElse {
                Log.w(TAG, "初始化抛异常：${it.message}")
                false
            }
            val test = runCatching { CKeyFacade.ckey(guid, serverTime, vid, 2, appVer, "v3011") }.getOrElse { "" }
            Log.i(TAG, "初始化组合(a=$a b=$b c=$c) → ${if (ok) "调用完成" else "异常"}，试算 ckey ${if (test.isBlank()) "空" else "${test.length} 字符"}")
            if (test.isNotBlank()) { inited = true; break }
        }
        Log.i(TAG, if (inited) "签名库已可用" else "签名库仍算不出 ckey（下面各组合都会失败）")

        /*
         * 顺手验一条更省事的路（2026-10-18 下午在 .18 上实测通了）：
         * 腾讯老接口 getinfo 不要签名、不要登录就能给整片地址，且支持分段请求。
         * 这里用设备上的真实身份（网页里那份票）再验一次，看档位能不能上去。
         */
        val whole = com.xxxx.emby_vr.web.TencentWholeUrl.fetch(vid, cookie, deep = true)
        if (whole == null) {
            Log.w(TAG, "整片地址：没拿到")
        } else {
            Log.i(TAG, "整片地址：✅ ${whole.quality}（${whole.defn}）→ ${whole.url.take(120)}…")
        }

        var found = 0
        for (sdtfrom in SDTFROMS) {
            for (platform in PLATFORMS) {
                for (fmt in FORMATS) {
                    if (tryOnce(vid, guid, serverTime, platform, sdtfrom, fmt, appVer, cookie) != null) found++
                }
            }
        }
        Log.i(TAG, "验证结束：${found} 组参数拿到了地址")
    }

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
            out.add("$u$pt" + (if (hk.isBlank()) "" else "&hlskey=$hk") + "&sdtfrom=$sdtfrom")
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
        /* 实测返回 QZOutputJson={"s":"o","t":1792312762,...} —— 键是 t，不是 s（s 是状态码） */
        Regex("\"t\"\\s*:\\s*\"?(\\d{10})").find(body)?.let { return it.groupValues[1].toLong() }
        Regex(">(\\d{10})<").find(body)?.let { return it.groupValues[1].toLong() }
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
