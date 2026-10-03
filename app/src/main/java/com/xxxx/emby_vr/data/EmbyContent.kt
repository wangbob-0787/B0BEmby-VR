package com.xxxx.emby_vr.data

import android.content.Context
import android.util.Log
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.remote.EmbyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VR 端的 Emby 内容控制器（P2）。
 *
 * ## 与 TV 版的差异
 *
 * TV 版有完整的登录页（地址/用户名/密码）、多账号管理、完整 ViewModel 层。
 * VR 版当前阶段目标是「**头显里能看到自己的海报墙**」，因此先走最短路：
 * 用固定的内网地址 + API Key 直连，跳过交互式登录。
 * 需要切账号时再把 TV 版登录页逻辑搬过来。
 *
 * ## 为什么用 API Key 而不是用户名密码
 *
 * 家庭自用、单用户（wangbob），服务器固定在内网。
 * 用 Key 能省掉「在 VR 里用手柄敲键盘输密码」这个糟糕体验 ——
 * VR 里打字非常难用，能不用就不用。
 *
 * ## 端口与路径（2026-10-03 实测确认，重要）
 *
 * - `http://192.168.150.15:6908` —— **是 http，不是 https**。
 *   同一台机上 8920 是 https，别搞混（curl 对 6908 发 https 会报
 *   `wrong version number`）。
 * - `EmbyApi.httpStream` 会自动补 `/emby` 前缀和一组 `X-Emby-*` 参数，
 *   因此这里传的 path 要以 `/Users/...` 开头，**不带** `/emby`。
 * - **6908 前面有一层代理**：`/emby/Users`（用户列表）返回 404，
 *   但 `/emby/Users/{id}/Items`（按用户拉片）是通的，所以 userId 写死，
 *   不要做「先查用户列表再拉片」的两步调用。
 * - `api_key` 需要调用方自己拼进 URL（httpStream 不加）。
 */
object EmbyContent {

    private const val TAG = "B0BEmbyVR"

    /** 内网 Emby 直连地址（6908 = Emby 本地直连端口，http 明文） */
    const val DEFAULT_SERVER = "http://192.168.150.15:6908"

    /** 默认用户（父亲账号 wangbob） */
    const val DEFAULT_USER_ID = "40a02f8503ce4de49d58331a282dcea1"
    const val DEFAULT_USER_NAME = "wangbob"

    /** 设备标识：同一台 PICO 固定，服务端按它区分播放会话（播放链路也用它） */
    const val DEVICE_ID = "b0bemby-vr-pico4"

    /**
     * 带错误详情的结果。
     *
     * 为什么不用「失败就返回空列表」：空列表无法区分
     * 「网络不通 / 认证失败 / 库里真没片」这三种情况，实机排查时是黑盒。
     * 这里把失败原因一并带出去，调用方可以直接显示到屏幕上。
     */
    sealed interface Result {
        data class Ok(val items: List<BaseItemDto>) : Result
        data class Err(val reason: String) : Result
    }

    /**
     * 拉取影片，返回带错误详情的结果。
     */
    suspend fun loadMoviesDetailed(
        context: Context,
        apiKey: String,
        serverUrl: String = DEFAULT_SERVER,
        userId: String = DEFAULT_USER_ID,
        limit: Int = 12,
    ): Result = loadItemsDetailed(context, apiKey, serverUrl, userId, "Movie", limit)

    /**
     * 拉取影片（按入库时间倒序）。失败返回空列表，仅用于不需要区分失败原因的场合。
     *
     * @param limit 拉取条数，海报墙一行放得下的量即可
     */
    suspend fun loadMovies(
        context: Context,
        apiKey: String,
        serverUrl: String = DEFAULT_SERVER,
        userId: String = DEFAULT_USER_ID,
        limit: Int = 12,
    ): List<BaseItemDto> = when (
        val r = loadMoviesDetailed(context, apiKey, serverUrl, userId, limit)
    ) {
        is Result.Ok -> r.items
        is Result.Err -> emptyList()
    }

    /** 剧集列表 */
    suspend fun loadSeries(
        context: Context,
        apiKey: String,
        serverUrl: String = DEFAULT_SERVER,
        userId: String = DEFAULT_USER_ID,
        limit: Int = 12,
    ): List<BaseItemDto> = when (
        val r = loadItemsDetailed(context, apiKey, serverUrl, userId, "Series", limit)
    ) {
        is Result.Ok -> r.items
        is Result.Err -> emptyList()
    }

    private suspend fun loadItemsDetailed(
        context: Context,
        apiKey: String,
        serverUrl: String,
        userId: String,
        itemTypes: String,
        limit: Int,
    ): Result = withContext(Dispatchers.IO) {
        val path = buildString {
            append("/Users/$userId/Items")
            append("?Recursive=true")
            append("&IncludeItemTypes=$itemTypes")
            append("&SortBy=DateCreated")
            append("&SortOrder=Descending")
            append("&Limit=$limit")
            append("&Fields=PrimaryImageAspectRatio,ImageTags,ProductionYear,RunTimeTicks")
            append("&api_key=$apiKey")
        }
        try {
            val items = EmbyApi.getItemsByPath(context, serverUrl, apiKey, DEVICE_ID, path)
            Log.i(TAG, "拉取 $itemTypes 成功: ${items.size} 条")
            Result.Ok(items)
        } catch (t: Throwable) {
            // 异常类型 + 消息足以区分「连不上 / 超时 / 认证失败 / 解析失败」
            val reason = "${t.javaClass.simpleName}: ${t.message ?: "无消息"}"
            Log.e(TAG, "拉取 $itemTypes 失败: $reason", t)
            Result.Err(reason.take(120))
        }
    }

    /**
     * 海报 URL。
     *
     * Emby 图片端点：`/emby/Items/{id}/Images/Primary?maxWidth=&tag=&api_key=`
     * 带 `tag` 可让 Emby 直接回缓存版本，避免每次重新生成。
     */
    fun posterUrl(
        serverUrl: String,
        itemId: String,
        imageTag: String?,
        apiKey: String,
        maxWidth: Int = 400,
    ): String = buildString {
        append(serverUrl.trimEnd('/'))
        append("/emby/Items/$itemId/Images/Primary")
        append("?maxWidth=$maxWidth")
        append("&quality=90")
        if (!imageTag.isNullOrEmpty()) append("&tag=$imageTag")
        append("&api_key=$apiKey")
    }

    /** 背板（横图）URL —— 详情页背景用 */
    fun backdropUrl(
        serverUrl: String,
        itemId: String,
        tag: String?,
        apiKey: String,
        maxWidth: Int = 1280,
    ): String = buildString {
        append(serverUrl.trimEnd('/'))
        append("/emby/Items/$itemId/Images/Backdrop")
        append("?maxWidth=$maxWidth")
        append("&quality=90")
        if (!tag.isNullOrEmpty()) append("&tag=$tag")
        append("&api_key=$apiKey")
    }
}
