package com.xxxx.emby_vr.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 更新包的下载与安装。
 *
 * 下载走流式写文件、边写边报进度，用户可以中途取消（只在下完之前）。
 * 包一旦交给系统安装器就不再提供取消：那一步开始系统会立刻结束本进程，
 * 这时再响应点击只会把状态搅乱。
 */
object ApkUpdater {

    private const val TAG = "ApkUpdater"

    private var running: Call? = null

    private fun updateDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    fun apkFile(context: Context): File = File(updateDir(context), "app.apk")

    /** 开始下载。取消时回调 onError("已取消")，调用方据此回到「有新版本」 */
    fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel()
        val dir = updateDir(context)
        val tmp = File(dir, "app.apk.part")
        val dst = File(dir, "app.apk")
        if (tmp.exists()) tmp.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(url).build())
        running = call

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (running === c) running = null
                onError(if (c.isCanceled()) "已取消" else (e.message ?: "下载失败"))
            }

            override fun onResponse(c: Call, resp: Response) {
                resp.use { r ->
                    if (!r.isSuccessful) {
                        if (running === c) running = null
                        onError("下载失败 HTTP ${r.code}")
                        return
                    }
                    val body = r.body
                    if (body == null) {
                        if (running === c) running = null
                        onError("下载失败：服务器没有返回内容")
                        return
                    }
                    val total = body.contentLength()
                    var done = 0L
                    try {
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (c.isCanceled()) break
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) onProgress(((done * 100) / total).toInt())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (running === c) running = null
                        onError(e.message ?: "下载中断")
                        return
                    }
                    if (running === c) running = null
                    if (c.isCanceled()) {
                        tmp.delete()
                        onError("已取消")
                        return
                    }
                    if (total > 0 && done != total) {
                        tmp.delete()
                        onError("下载不完整")
                        return
                    }
                    if (dst.exists()) dst.delete()
                    if (!tmp.renameTo(dst)) {
                        onError("保存安装包失败")
                        return
                    }
                    onProgress(100)
                    onDone(dst)
                }
            }
        })
    }

    fun cancel() {
        running?.let { if (!it.isCanceled()) it.cancel() }
        running = null
    }

    /** 交给系统安装器；调不起来时返回给界面显示的失败文案 */
    fun install(context: Context, file: File): String? = try {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        null
    } catch (e: Exception) {
        Log.w(TAG, "调起安装器失败", e)
        e.message ?: "无法调起安装"
    }
}
