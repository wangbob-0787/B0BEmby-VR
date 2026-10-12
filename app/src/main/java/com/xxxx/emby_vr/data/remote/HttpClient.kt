package com.xxxx.emby_vr.data.remote

import android.content.Context
import android.util.Log
import com.xxxx.emby_vr.data.local.PreferencesManager
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Route
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpClient {
    private const val TAG = "HttpClient"

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var cache: Cache? = null

    private fun getCache(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: Cache(File(context.cacheDir, "http_cache"), 10 * 1024 * 1024).also { cache = it }
        }
    }

    /*
     * 代理设置已整体删除（父亲 2026-10-12）：以前这里按偏好里的代理配置建客户端、
     * 挂 ProxySelector，现在只留"建一次就缓存住"。
     */
    fun getClient(context: Context): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            client = createClient(context)
            return client!!
        }
    }

    private fun createClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cache(getCache(context))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 20
            })
            .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
            // 小雅网盘库很大,Emby 首页查询(继续观看/收藏/最新)单次约 6 秒,
            // 原来的 6 秒超时会把请求自己掐断 → 表现为"首页要等一会"和"无法连接服务器"
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        return builder.build()
    }

    fun rebuildClient(context: Context) {
        try {
            synchronized(this) {
                val oldClient = client
                client = null
                oldClient?.dispatcher?.cancelAll()
            }
            getClient(context)
            Log.i(TAG, "HttpClient 已重建")
        } catch (e: Exception) {
            Log.e(TAG, "重建 HttpClient 失败", e)
            synchronized(this) {
                client = null
            }
        }
    }
}
