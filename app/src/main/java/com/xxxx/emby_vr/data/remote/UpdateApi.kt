package com.xxxx.emby_vr.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内网更新源。
 *
 * 为什么不用 GitHub：仓库转私有后匿名接口拿不到数据，而且上游版本会覆盖本机改造
 * （TV 版踩过这条，最后把应用内检查整个关掉）。所以清单和安装包都放在家里 `.15`
 * 的静态目录里 —— 只有我们自己的包，头显经 WireGuard 也够得着。
 */
object UpdateApi {

    const val BASE = "http://192.168.150.15:8791"

    /**
     * 版本清单。
     *
     * [run] 与 `versionName`（`0.1.<run>`）末段同源，比较它就知道有没有新版；
     * [apk] 是文件名，拼在 `/apk/` 后面下载。
     */
    data class Manifest(val run: Int, val apk: String, val note: String) {
        val apkUrl: String get() = "$BASE/apk/$apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 取一次清单；网络不通或内容不对都返回 null，启动路径上静默失败不打扰 */
    suspend fun fetchManifest(): Manifest? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/version.json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val o = JSONObject(text)
                val run = o.optInt("run", 0)
                val apk = o.optString("apk", "")
                if (run <= 0 || apk.isEmpty()) null
                else Manifest(run, apk, o.optString("note", ""))
            }
        } catch (e: Exception) {
            null
        }
    }
}
