package com.xxxx.emby_vr.web

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar

/**
 * 腾讯接口的签名 —— **我们自己算的**（2026-10-18 晚，父亲要求"坚持不懈自己算签名"）。
 *
 * 结论来自读它网页播放器里的实现（`Xh.$xx`）：
 *   cKey = md5( 盐 + 片号 + 服务器时间(秒) + "*#06#" + 平台号 )
 * 盐按"密钥版本"取，版本 = "7." 加当天星期（周日算 7），也就是每天换一次盐。
 * 平台号是 10201（网页端那套）。三个输入一个都不能少，否则接口直接报错（em=85/93）。
 *
 * 服务器时间由 checktime 接口给（`https://vv6.video.qq.com/checktime`），
 * 它同时回一个随机串；实测签名只用时间、不用随机串。
 *
 * 这个实现不依赖腾讯任何二进制、也不从网页里捞地址 —— 就是一段我们自己写的哈希。
 */
object TencentSign {

    const val TAG = "B0BEmbyVR-TencentSign"

    /** 平台号：实测 10201 这一套签名才被接受。 */
    const val PLATFORM = "10201"

    private val SALTS = mapOf(
        "1" to "06fc1464",
        "2" to "4244ce1b",
        "3" to "77de31c5",
        "4" to "e0149fa2",
        "5" to "60394ced",
        "6" to "2da639f0",
        "7" to "c2f0cf9f",
    )

    /** 今天的密钥版本，形如 `7.7`（周日）…`7.1`。 */
    fun encryptVer(): String {
        val jsDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 // 周日=0，和网页一致
        return "7." + if (jsDay == 0) 7 else jsDay
    }

    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 片号(或专辑号) + 服务器时间秒 + 平台号 → 签名。 */
    fun ckey(ckeyId: String, tm: String, platform: String = PLATFORM): String {
        val ver = encryptVer()
        val salt = SALTS[ver.substringAfter(".")] ?: SALTS["7"]!!
        return md5(salt + ckeyId + tm + "*#06#" + platform)
    }

    private fun get(url: String, cookie: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * 问一次服务器时间。接口回 XML：`<s>o</s><t>秒</t><rand>随机串</rand>`。
     * 返回时间戳（字符串，取 10 位秒），失败返回 null。
     */
    fun serverTime(cookie: String? = null): String? {
        return try {
            val xml = get("https://vv6.video.qq.com/checktime?otype=xml&rand=${System.currentTimeMillis()}", cookie)
            val sec = Regex("<t>(\\d+)</t>").find(xml)?.groupValues?.get(1) ?: return null
            sec.take(10)
        } catch (e: Throwable) {
            Log.w(TAG, "问服务器时间失败：$e")
            null
        }
    }
}
