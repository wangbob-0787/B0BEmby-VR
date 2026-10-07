package com.xxxx.emby_vr.data.remote

import android.content.Context
import android.graphics.Point
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.xxxx.emby_vr.BuildConfig
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.data.model.AuthenticationResultDto
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.model.EmbyResponseDto
import com.xxxx.emby_vr.data.model.MediaDto
import com.xxxx.emby_vr.data.model.SessionDto
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Emby API 接口定义
 * 无状态设计，所有参数显式传入
 */
object EmbyApi {
    private const val TAG = "EmbyApi"
    const val CLIENT = "B0BEmby"
    val CLIENT_VERSION: String = BuildConfig.VERSION_NAME
    val DEVICE_NAME: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()

    // ==================== 认证相关 ====================

    /**
     * 用户认证
     */
    suspend fun authenticate(
        context: Context,
        serverUrl: String,
        deviceId: String,
        username: String,
        password: String
    ): AuthenticationResultDto = withContext(Dispatchers.IO) {
        val body = mapOf("Username" to username, "Pw" to password)
        val result = httpAsJsonObject(
            context = context,
            serverUrl = serverUrl,
            apiKey = "",
            deviceId = deviceId,
            url = "/Users/authenticatebyname",
            method = "POST",
            body = body
        )
        gson.fromJson(result, AuthenticationResultDto::class.java)
    }

    /**
     * 验证 API Key 有效性
     */
    suspend fun testKey(
        context: Context,
        serverUrl: String,
        userId: String,
        apiKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/Users/$userId?X-Emby-Token=$apiKey"
            val request = Request.Builder().url(url).get().build()
            HttpClient.getClient(context).newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 媒体库相关 ====================

    /**
     * 获取视图列表
     */
    suspend fun getViews(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String
    ): List<BaseItemDto> {
        return httpAsBaseItemDtoList(
            context, serverUrl, apiKey, deviceId,
            "/Users/$userId/Views?X-Emby-Token=$apiKey"
        )
    }

    /**
     * 获取媒体库列表（支持分页）
     * 
     * @param startIndex 起始索引
     * @param limit 每页数量
     * @return Pair<List<BaseItemDto>, Int> 数据列表和总数
     */
    suspend fun getLibraryList(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        parentId: String,
        type: String,
        startIndex: Int = 0,
        limit: Int = 20,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        filters: String? = null
    ): Pair<List<BaseItemDto>, Int> {
        val url = "/Users/$userId/Items?IncludeItemTypes=$type" +
                "&Fields=BasicSyncInfo,PrimaryImageAspectRatio,ProductionYear,Status,EndDate" +
                "&StartIndex=$startIndex&SortBy=$sortBy&SortOrder=$sortOrder&ParentId=$parentId" +
                (filters?.let { "&Filters=$it" } ?: "") +
                "&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1&Recursive=true&Limit=$limit" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoListWithTotal(context, serverUrl, apiKey, deviceId, url)
    }
    
    /**
     * HTTP 请求并解析为 BaseItemDto 列表（带总数）
     */
    private suspend fun httpAsBaseItemDtoListWithTotal(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String
    ): Pair<List<BaseItemDto>, Int> {
        return httpStream(context, serverUrl, apiKey, deviceId, url) { reader ->
            val type = object : com.google.gson.reflect.TypeToken<EmbyResponseDto<BaseItemDto>>() {}.type
            val response: EmbyResponseDto<BaseItemDto>? = gson.fromJson(reader, type)
            val items = response?.items ?: emptyList()
            val totalCount = response?.totalRecordCount?.takeIf { it > 0 } ?: items.size
            Pair(items, totalCount)
        } ?: Pair(emptyList(), 0)
    }

    /**
     * 搜索媒体项
     * 
     * @param query 搜索关键词
     * @param startIndex 起始索引
     * @param limit 每页数量
     * @return Pair<List<BaseItemDto>, Int> 数据列表和总数
     */
    suspend fun searchItems(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        query: String,
        startIndex: Int = 0,
        limit: Int = 20
    ): Pair<List<BaseItemDto>, Int> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "/Users/$userId/Items?SearchTerm=$encodedQuery" +
                "&IncludeItemTypes=Movie,Series,BoxSet,MusicAlbum,Audio,Video" +
                "&Fields=BasicSyncInfo,PrimaryImageAspectRatio,ProductionYear,Status,EndDate" +
                "&StartIndex=$startIndex&SortBy=SortName&SortOrder=Ascending" +
                "&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1&Recursive=true&Limit=$limit" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoListWithTotal(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取继续观看列表
     */
    suspend fun getResumeItems(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        seriesId: String = ""
    ): List<BaseItemDto> {
        val limit = if (seriesId.isEmpty()) 15 else 1
        // Fields 必须显式要：Resume 接口默认只回基础字段。
        // 首页大片头要显示的 评分/类型/分级/简介、以及横版剧照(Backdrop) 都得在这里点出来，
        // 否则海报上只剩一个年份（父亲 2026-09-30 实测发现"元数据缺内容"）
        val url = "/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&ParentId=$seriesId" +
                "&Fields=PrimaryImageAspectRatio,ProductionYear,CommunityRating,OfficialRating,Genres,Overview," +
                "BackdropImageTags,ParentBackdropImageTags,ParentBackdropItemId,SeriesId,SeriesName" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 按 id 批量取条目（首页大片头用它补剧集级元数据：单集没有评分/类型/分级）
     */
    suspend fun getItemsByIds(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        ids: List<String>
    ): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val url = "/Users/$userId/Items?Ids=${ids.joinToString(",")}" +
                "&Fields=CommunityRating,OfficialRating,Genres,ProductionYear,Overview" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取视图下的最新项目
     */
    suspend fun getLatestItemsByViews(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        parentId: String
    ): List<BaseItemDto> {
        // 注意：本端点不支持 IncludeItemTypes（2026-09-27 实测传了也照样返回专辑），
        // 音乐库要取歌曲得走 getLibraryList 那条 /Users/{uid}/Items 的路径。
        val url = "/Users/$userId/Items/Latest?Limit=20&ParentId=$parentId" +
                "&Fields=PrimaryImageAspectRatio,ProductionYear&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoListDirect(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取所有视图的最新项目
     */
    suspend fun getLatestItems(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String
    ): List<BaseItemDto> {
        val views = getViews(context, serverUrl, apiKey, deviceId, userId)
        // 首页数据修正（2026-09-27 父亲反馈"主界面内容对不上"）：
        // 1) Live TV 视图（CollectionType=livetv）不是真正的媒体库，
        //    /Users/{uid}/Items/Latest?ParentId=<livetv> 会被 Emby 忽略 → 返回全库最新，
        //    表现为「电视直播」一行里全是电视剧每日更新的剧集。该行直接不展示。
        // 2) 服务端存在同名重复库（例如两个「网易云音乐」），同名只保留第一个，避免首页两行内容一样。
        val seenNames = HashSet<String>()
        return views
            .filter { !it.collectionType.equals("livetv", ignoreCase = true) }
            .filter { view -> view.name == null || seenNames.add(view.name) }
            .map { view ->
                val id = view.id ?: ""
                if (id.isNotEmpty()) {
                    // 音乐库：/Items/Latest 只给「专辑」，而专辑没有封面 → 首页一排灰块。
                    // 改取歌曲(Audio)——597 首全有封面图（2026-09-27 实测）。
                    val items = if (view.collectionType.equals("music", ignoreCase = true)) {
                        getLibraryList(
                            context, serverUrl, apiKey, deviceId, userId,
                            parentId = id, type = "Audio",
                            startIndex = 0, limit = 20,
                            sortBy = "DateCreated", sortOrder = "Descending"
                        ).first
                    } else {
                        getLatestItemsByViews(context, serverUrl, apiKey, deviceId, userId, id)
                    }
                    view.copy(latestItems = items)
                } else {
                    view
                }
            }
    }

    // ==================== 直播电视（简单版） ====================

    /**
     * 直播频道列表。
     *
     * 用途：首页「电视直播」一行 —— 列出频道，选中直接进播放页。
     * Fields=CurrentProgram 让每条带"正在播出"的节目名，行里直接显示。
     * 播放走既有通道：`/Items/{频道id}/PlaybackInfo`（已带 AutoOpenLiveStream=true）会返回带
     * api_key 的 HLS 转码地址（2026-10-02 实测 71 频道，CCTV1 返回 master.m3u8）。
     */
    suspend fun getLiveTvChannels(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        limit: Int = 200
    ): List<BaseItemDto> {
        val url = "/LiveTv/Channels?UserId=$userId&Limit=$limit" +
                "&Fields=CurrentProgram" +
                "&EnableImageTypes=Primary&ImageTypeLimit=1" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoListWithTotal(context, serverUrl, apiKey, deviceId, url).first
    }

    // ==================== 详情与剧集 ====================

    /**
     * 获取媒体详情
     */
    suspend fun getMediaInfo(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        mediaId: String
    ): BaseItemDto {
        return httpAsBaseItemDto(
            context, serverUrl, apiKey, deviceId,
            "/Users/$userId/Items/$mediaId?fields=ShareLevel&ExcludeFields=VideoChapters,VideoMediaSources,MediaStreams&X-Emby-Token=$apiKey"
        )
    }

    /**
     * 获取剧集列表
     */
    suspend fun getSeriesList(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        parentId: String
    ): List<BaseItemDto> {
        val url = "/Users/$userId/Items?UserId=$userId" +
                "&Fields=BasicSyncInfo%2CCanDelete%2CPrimaryImageAspectRatio%2COverview%2CPremiereDate%2CProductionYear%2CRunTimeTicks%2CSpecialEpisodeNumbers" +
                "&Recursive=true&IsFolder=false&ParentId=$parentId&Limit=1000&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取下一集
     */
    suspend fun getShowsNextUp(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        seriesId: String
    ): List<BaseItemDto> {
        val url = "/Shows/NextUp?SeriesId=$seriesId&UserId=$userId" +
                "&EnableTotalRecordCount=false&ExcludeLocationTypes=Virtual" +
                "&Fields=ProductionYear,PremiereDate,Container,PrimaryImageAspectRatio&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取季列表
     */
    suspend fun getSeasonList(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        seriesId: String
    ): List<BaseItemDto> {
        val url = "/Shows/$seriesId/Seasons?UserId=$userId" +
                "&Fields=PrimaryImageAspectRatio&Limit=100&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    /**
     * 获取某一季的剧集列表（播放页「选集」菜单用，2026-10-06）。
     *
     * seasonId 为空时返回该剧全部剧集（跨季）。
     */
    suspend fun getEpisodes(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        seriesId: String,
        seasonId: String?
    ): List<BaseItemDto> {
        val url = "/Shows/$seriesId/Episodes?UserId=$userId" +
                (if (!seasonId.isNullOrBlank()) "&SeasonId=$seasonId" else "") +
                "&Fields=Overview,UserData&Limit=500&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    // ==================== 播放相关 ====================

    /**
     * 获取播放信息
     */
    suspend fun getPlaybackInfo(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        mediaId: String,
        startTimeTicks: Long,
        selectedAudioIndex: Int? = null,
        selectedSubtitleIndex: Int? = null,
        disableHevc: Boolean = false,
        maxStreamingBitrate: Int = 200_000_000
    ): MediaDto = withContext(Dispatchers.IO) {
        var targetId = mediaId
        try {
            val body = buildPlaybackInfoBody(context, disableHevc, maxStreamingBitrate)

            // 专辑/艺人本身不可播放：Emby 对 MusicAlbum 的 PlaybackInfo 直接 500
            // （"Unable to cast object of type 'MusicAlbum' to type 'IHasMediaSources'"，2026-09-27 实测）。
            // 首页音乐行现在给的是歌曲，但库里点开一张专辑仍会走到这里 —— 先把容器解析成它的第一首歌。
            try {
                val item = httpAsBaseItemDto(
                    context, serverUrl, apiKey, deviceId,
                    "/Users/$userId/Items/$mediaId?X-Emby-Token=$apiKey"
                )
                val t = item.type
                if (t.equals("MusicAlbum", true) || t.equals("MusicArtist", true)) {
                    val songs = getLibraryList(
                        context, serverUrl, apiKey, deviceId, userId,
                        parentId = mediaId, type = "Audio",
                        startIndex = 0, limit = 1,
                        sortBy = "SortName", sortOrder = "Ascending"
                    ).first
                    songs.firstOrNull()?.id?.let {
                        targetId = it
                        Log.i(TAG, "音频容器 $mediaId($t) → 改播第一首 $it")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析音频容器失败，按原 id 播放: ${e.message}")
            }

            val url = "/Items/$targetId/PlaybackInfo?UserId=$userId" +
                    "&StartTimeTicks=$startTimeTicks" +
                    "&IsPlayback=true" +
                    "&AutoOpenLiveStream=true" +
                    "&MaxStreamingBitrate=$maxStreamingBitrate" +
                    // 禁止直连原始文件:直连(拉网盘原始 mp4/mkv)时播放器拿不到 HDR 色彩信息,
                    // 屏幕收到的是无色彩标记的画面 → 发灰。改由服务端转封装成 HLS/TS 后再播。
                    "&EnableDirectPlay=false" +
                    "&X-Emby-Token=$apiKey" +
                    "&X-Emby-Language=zh-cn" +
                    "&reqformat=json" +
                    (selectedAudioIndex?.let { "&AudioStreamIndex=$it" } ?: "") +
                    (selectedSubtitleIndex?.let { "&SubtitleStreamIndex=$it" } ?: "")

            val result = httpAsJsonObject(context, serverUrl, apiKey, deviceId, url, "POST", body)
            val dto = gson.fromJson(result, MediaDto::class.java)
            com.xxxx.emby_vr.util.DiagLog.w(context, "playbackInfo",
                "id=$targetId sources=${dto.mediaSources?.size ?: 0} " +
                "first=${dto.mediaSources?.firstOrNull()?.let { it.directStreamUrl ?: it.transcodingUrl }}")
            dto
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playback info: ${e.message}")
            com.xxxx.emby_vr.util.DiagLog.w(context, "playbackInfo失败",
                "id=$targetId ${e.javaClass.simpleName}: ${e.message}")
            MediaDto()
        }
    }

    /**
     * 上报播放进度
     */
    suspend fun reportPlaybackProgress(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        body: Any
    ) {
        try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Sessions/Playing/Progress?X-Emby-Token=$apiKey", "POST", body
            )
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
        }
    }

    /**
     * 上报开始播放
     */
    suspend fun playing(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        body: Any
    ) {
        try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Sessions/Playing?reqformat=json&X-Emby-Token=$apiKey", "POST", body
            )
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
        }
    }

    /**
     * 上报停止播放
     */
    suspend fun stopped(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        body: Any
    ) {
        try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Sessions/Playing/Stopped?reqformat=json&X-Emby-Token=$apiKey", "POST", body
            )
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
        }
    }

    /**
     * 报「客户端能力」（Emby 建立/更新设备会话的标准动作）。
     *
     * 为什么必须有这一步：`/Sessions/Playing` 三件套是按**会话**匹配的，
     * 会话由「设备 + 客户端 + 用户」三者决定。不先报一次能力，服务端可能没有
     * 对应会话可用 —— 轻则进度落不到用户数据里，重则直接 503。
     * 电视版走官方 SDK，SDK 内部会做这一步；VR 版是手写 HTTP，得自己补。
     */
    suspend fun reportCapabilities(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String
    ) {
        val body = mapOf(
            "PlayableMediaTypes" to listOf("Video", "Audio"),
            "SupportedCommands" to listOf(
                "Play", "Pause", "Stop", "Seek",
                "VolumeUp", "VolumeDown", "Mute", "Unmute"
            ),
            "SupportsMediaControl" to true,
            "SupportsPersistentIdentifier" to true,
            "SupportsContentUploading" to false
        )
        try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Sessions/Capabilities/Full?X-Emby-Token=$apiKey", "POST", body
            )
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "报客户端能力失败", e)
        }
    }

    /**
     * 停止活动编码
     */
    suspend fun stopActiveEncodings(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        playSessionId: String?
    ) = withContext(Dispatchers.IO) {
        try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Videos/ActiveEncodings/Delete?PlaySessionId=$playSessionId&X-Emby-Token=$apiKey",
                "POST", null
            )
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
        }
    }

    /**
     * 获取播放会话列表
     */
    suspend fun getPlayingSessions(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String
    ): List<SessionDto> {
        return httpAsSessionDtoList(
            context, serverUrl, apiKey, deviceId,
            "/Sessions?X-Emby-Token=$apiKey"
        )
    }

    // ==================== 收藏相关 ====================

    /**
     * 添加收藏
     */
    suspend fun addToFavorites(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        itemId: String
    ): Boolean {
        return try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Users/$userId/FavoriteItems/$itemId?X-Emby-Token=$apiKey", "POST", null
            )
            true
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
            false
        }
    }

    /**
     * 取消收藏
     */
    suspend fun removeFromFavorites(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String,
        itemId: String
    ): Boolean {
        return try {
            httpAsJsonObject(
                context, serverUrl, apiKey, deviceId,
                "/Users/$userId/FavoriteItems/$itemId/Delete?X-Emby-Token=$apiKey", "POST", null
            )
            true
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
            false
        }
    }

    /**
     * 获取收藏列表
     */
    suspend fun getFavoriteItems(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        userId: String
    ): List<BaseItemDto> {
        val url = "/Users/$userId/Items?SortBy=SeriesSortName,ParentIndexNumber,IndexNumber,SortName" +
                "&SortOrder=Ascending&Filters=IsFavorite" +
                "&Fields=BasicSyncInfo,CanDelete,CanDownload,PrimaryImageAspectRatio,ProductionYear" +
                "&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&Recursive=true&Limit=20" +
                "&X-Emby-Token=$apiKey"
        return httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, url)
    }

    // ==================== 版本更新 ====================

    /**
     * 获取最新版本信息
     */
    /**
     * 获取最新版本信息 —— 【已关闭】应用内更新检查（个人自用改造版，2026-09-29）
     *
     * 关闭原因:
     *   1) 本仓库为私有仓库,未鉴权访问 releases/latest 必然返回 404,留着只会白发请求;
     *   2) 该功能原本会提示安装上游 release,装上即覆盖本机的弹幕层/直通/播放页改造。
     *
     * 如需恢复:在此实现 GET https://api.github.com/repos/wangbob-0787/B0BEmby/releases/latest
     * (URL 已指向本仓库),并保留请求失败时静默返回空对象的行为。
     */
    suspend fun getNewVersion(context: Context): JsonObject = withContext(Dispatchers.IO) {
        JsonObject()
    }

    // ==================== HTTP 辅助方法 ====================

    private fun proxyHintException(context: Context, e: Exception): Exception {
        val prefs = com.xxxx.emby_vr.data.local.PreferencesManager(context)
        return if (prefs.proxyEnabled) {
            Exception(context.getString(R.string.error_proxy_connection), e)
        } else {
            Exception(context.getString(R.string.error_server_unreachable), e)
        }
    }

    private fun isConnectionError(e: Throwable): Boolean {
        return e is ConnectException || e is UnknownHostException ||
                e is java.net.SocketException ||
                e.message?.let { msg ->
                    msg.contains("SOCKS", ignoreCase = true) ||
                    msg.contains("Connection refused", ignoreCase = true) ||
                    msg.contains("Connection reset", ignoreCase = true) ||
                    msg.contains("Malformed reply", ignoreCase = true) ||
                    msg.contains("Proxy", ignoreCase = true) ||
                    msg.contains("proxy", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true)
                } == true
    }

    private suspend fun <T> httpStream(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String,
        method: String = "GET",
        body: Any? = null,
        parser: (JsonReader) -> T
    ): T = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val connector = if (url.contains("?")) "&" else "?"

        /*
         * 查询参数必须逐个 URL 编码（2026-10-03 实机踩坑）。
         *
         * 设备名是 `"${Build.MANUFACTURER} ${Build.MODEL}"`，在 PICO 4 上等于
         * **"PICO 4"（含空格）**。原实现把它直接拼进 URL，OkHttp 校验 URL 时
         * 遇到未转义的空格直接抛异常，请求根本没发出去 ——
         * 表现就是虚拟屏上「未取到影片」，而服务端日志里连一条访问记录都没有。
         *
         * TV 版没暴露这个问题，是因为电视设备的 MANUFACTURER/MODEL 通常不含空格。
         */
        fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")

        val params = "${connector}deviceId=${enc(deviceId)}" +
                "&X-Emby-Client=${enc(CLIENT)}" +
                "&X-Emby-Client-Version=${enc(CLIENT_VERSION)}" +
                "&X-Emby-Device-Name=${enc(DEVICE_NAME)}" +
                "&X-Emby-Device-Id=${enc(deviceId)}"
        val fullUrl = "$serverUrl/emby$url$params"

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .addHeader("Accept", "application/json")

        if (method == "POST") {
            val jsonBody = if (body != null) gson.toJson(body) else "{}"
            requestBuilder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }

        try {
            HttpClient.getClient(context).newCall(requestBuilder.build()).execute().use { response ->
                val responseTime = System.currentTimeMillis()
                val networkDuration = responseTime - startTime

                if (!response.isSuccessful) {
                    if (response.code == 407) {
                        throw Exception(context.getString(R.string.error_proxy_connection))
                    }
                    throw Exception("HTTP Error: ${response.code}")
                }

                val bodySource = response.body ?: throw Exception("Empty response body")
                
                try {
                    val result = parser(JsonReader(bodySource.charStream()))

                    val endTime = System.currentTimeMillis()
                    Log.i(TAG, """
                        🏁 请求完成: $url
                        ├─ 网络协议: ${response.protocol}
                        ├─ RTT: ${networkDuration}ms
                        ├─ JSON解析: ${endTime - responseTime}ms
                        └─ 总耗时: ${endTime - startTime}ms
                    """.trimIndent())

                    result
                } catch (e: Exception) {
                    Log.e(TAG, "JSON 解析失败: $url", e)
                    throw Exception(context.getString(R.string.error_network_error), e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "网络请求失败: $url", e)
            val prefs = com.xxxx.emby_vr.data.local.PreferencesManager(context)
            val proxyOn = prefs.proxyEnabled && prefs.proxyHost.isNotEmpty()
            when {
                e.message?.contains(context.getString(R.string.error_proxy_connection)) == true -> {
                    throw e as Exception
                }
                isConnectionError(e) || isConnectionError(e.cause ?: Exception()) -> {
                    throw proxyHintException(context, e as Exception)
                }
                proxyOn && (e is SocketTimeoutException || e.cause is SocketTimeoutException ||
                    e.message?.contains("timeout", ignoreCase = true) == true) -> {
                    throw proxyHintException(context, e as Exception)
                }
                e is SocketTimeoutException || e.cause is SocketTimeoutException ||
                e.message?.contains("timeout", ignoreCase = true) == true -> {
                    throw Exception(context.getString(R.string.error_connection_timeout), e)
                }
                e.message?.contains("HTTP Error") == true -> {
                    throw e as Exception
                }
                proxyOn -> {
                    throw proxyHintException(context, e as Exception)
                }
                else -> {
                    /*
                     * 兜底分支：不再用笼统的「网络错误」，而是把原始异常类型与消息
                     * 带出去。2026-10-03 实机排查教训 —— 屏上只显示「网络错误」时
                     * 完全无法定位（可能是 URL 非法、SSL、DNS、被拒、解析失败…），
                     * 而 PICO 的 adb 又连不上、拿不到 logcat，等于黑盒。
                     */
                    val root = generateSequence(e as Throwable) { it.cause }.last()
                    val detail = "${root.javaClass.simpleName}: ${root.message ?: "无消息"}"
                    Log.e(TAG, "请求失败(原始原因) url=$url detail=$detail", e)
                    throw Exception(
                        context.getString(R.string.error_network_error) + " [" + detail.take(90) + "]",
                        e,
                    )
                }
            }
        }
    }

    private suspend fun httpAsJsonObject(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String,
        method: String = "GET",
        body: Any? = null
    ): JsonObject {
        return httpStream(context, serverUrl, apiKey, deviceId, url, method, body) { reader ->
            gson.fromJson(reader, JsonObject::class.java) ?: JsonObject()
        }
    }

    private suspend fun httpAsBaseItemDto(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String,
        method: String = "GET",
        body: Any? = null
    ): BaseItemDto {
        return httpStream(context, serverUrl, apiKey, deviceId, url, method, body) { reader ->
            gson.fromJson(reader, BaseItemDto::class.java) ?: BaseItemDto()
        }
    }

    /**
     * 按完整路径拉取影片列表（VR 版新增的公开入口）。
     *
     * 为什么需要它：TV 版各个 getXxx 方法各自拼 URL、参数固定；
     * VR 版海报墙要按不同条件（最新影片 / 最新剧集 / 继续观看）拉取，
     * 与其为每种组合再加一个方法，不如暴露一个「给路径就拉」的入口。
     *
     * @param path 形如 `/Users/{userId}/Items?Recursive=true&...`，
     *             **不含**服务器地址前缀，也不含 api_key（由 httpStream 统一附加）
     */
    suspend fun getItemsByPath(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        path: String,
    ): List<BaseItemDto> = httpAsBaseItemDtoList(context, serverUrl, apiKey, deviceId, path)

    private suspend fun httpAsBaseItemDtoList(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String
    ): List<BaseItemDto> {
        return httpStream(context, serverUrl, apiKey, deviceId, url) { reader ->
            val type = object : TypeToken<EmbyResponseDto<BaseItemDto>>() {}.type
            val response = gson.fromJson<EmbyResponseDto<BaseItemDto>>(reader, type)
            response?.items ?: emptyList()
        }
    }

    private suspend fun httpAsBaseItemDtoListDirect(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String
    ): List<BaseItemDto> {
        return httpStream(context, serverUrl, apiKey, deviceId, url) { reader ->
            val type = object : TypeToken<List<BaseItemDto>>() {}.type
            gson.fromJson<List<BaseItemDto>>(reader, type) ?: emptyList()
        }
    }

    private suspend fun httpAsSessionDtoList(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        url: String
    ): List<SessionDto> {
        return httpStream(context, serverUrl, apiKey, deviceId, url) { reader ->
            val type = object : TypeToken<List<SessionDto>>() {}.type
            gson.fromJson<List<SessionDto>>(reader, type) ?: emptyList()
        }
    }

    // ==================== 设备能力探测 ====================

    /**
     * 构建播放信息请求体
     */
    private fun buildPlaybackInfoBody(
        context: Context,
        disableHevc: Boolean = false,
        maxStreamingBitrate: Int = 200_000_000
    ): JsonObject {
        try {
            val capabilities = getDeviceCapabilities(context)

            val videoCodecs = capabilities.videoCodecs.toMutableList()
            /*
             * 2026-10-06（VR 版）第二改：**恢复声明 HEVC**。
             *
             * 上一版为了治「发灰」把 hevc 拿掉、逼服务端转 H.264 —— 结果仍灰，
             * 而父亲给了关键经验：投影上「Emby 转码就发灰、走 direct 客户端自己处理就正常」。
             * 也就是灰出在服务端转码链路（QSV 的 vpp_qsv 色彩转换），不在解码。
             * 所以改回让服务端**视频原样 copy**（DirectStream），画面交给头显自己解。
             * 无声那条另修（转码输出只留 aac/mp3，服务端会把 EAC3 转成 AAC）。
             */
            Log.i("EmbyApi", "VR 版声明 HEVC 直通（避免服务端转码导致发灰）")
            val audioCodecs = capabilities.audioCodecs.toMutableList()
            val videoProfiles = capabilities.videoProfiles
            val hardwareSupportsHevc = videoCodecs.any { codec ->
                listOf("hevc", "h265", "hevc10").any { it.equals(codec, ignoreCase = true) }
            }
            // ErrorHandler.logError("audioCodecs",audioCodecs.joinToString(","))
            var actualDisableHevc = disableHevc
            if (!hardwareSupportsHevc) {
                actualDisableHevc = true
            }

            val rawLevel = findMaxLevel(videoProfiles, "h264", 51)
            val finalLevel = if (actualDisableHevc) 51 else if (rawLevel > 62) 62 else rawLevel

            val supportedVideo = videoCodecs.joinToString(",")
            val supportedAudio = audioCodecs.joinToString(",")
            /*
             * 父亲 2026-10-07：把设备**实际探测到**的音轨能力打进日志。
             * 之前判断"某个音轨能不能解"是拿系统 xml 名单推的，那只是静态声明；
             * 以后以这条运行时探测结果为准（MediaCodecList 查到什么就是什么）。
             */
            Log.i("EmbyApi", "设备音轨能力探测 → $supportedAudio")

            val deviceProfile = JsonObject().apply {
                addProperty("MaxStaticBitrate", maxStreamingBitrate)
                addProperty("MaxStreamingBitrate", maxStreamingBitrate)
                addProperty("MusicStreamingTranscodingBitrate", 192000)
                addProperty("MaxCanvasWidth", capabilities.maxCanvasWidth)
                addProperty("MaxCanvasHeight", capabilities.maxCanvasHeight)

                add("DirectPlayProfiles", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("Type", "Video")
                        // addProperty("VideoCodec", null as String?)
                        // addProperty("Container", null as String?)
                        // addProperty("AudioCodec", supportedAudio)
                       addProperty("VideoCodec", if (actualDisableHevc) "h264" else supportedVideo)
                       addProperty("Container", "mp4,m4v,mkv,mov")
                    //    addProperty("AudioCodec", null as String?)
                      addProperty("AudioCodec", supportedAudio)
                    })
                    add(JsonObject().apply {
                        addProperty("Type", "Audio")
                       addProperty("Container", null as String?)
                       addProperty("AudioCodec", null as String?)
                    })
                })

                add("TranscodingProfiles", JsonArray().apply {
                    add(createTranscodingProfile("aac", "Audio", "aac", "hls", "8"))
                    add(createTranscodingProfile("aac", "Audio", "aac", "http", "8"))
                    add(createTranscodingProfile("mp3", "Audio", "mp3", "http", "8"))
                    add(createTranscodingProfile("opus", "Audio", "opus", "http", "8"))
                    add(createTranscodingProfile("wav", "Audio", "wav", "http", "8"))
                    add(createTranscodingProfile("opus", "Audio", "opus", "http", "8", "Static"))
                    add(createTranscodingProfile("mp3", "Audio", "mp3", "http", "8", "Static"))
                    add(createTranscodingProfile("aac", "Audio", "aac", "http", "8", "Static"))
                    add(createTranscodingProfile("wav", "Audio", "wav", "http", "8", "Static"))

                    add(JsonObject().apply {
                        addProperty("Container", "mkv")
                        addProperty("Type", "Video")
                        addProperty("AudioCodec", supportedAudio)
                        // 不再声明 HEVC 直连:HEVC/HDR 内容改走服务端转封装(HLS/TS),
                        // 与能正常输出 HDR 的客户端路径一致;直连原始文件时播放器拿不到 HDR 信息
                        addProperty("VideoCodec", "h264")
                        addProperty("Context", "Static")
                        addProperty("MaxAudioChannels", "8")
                        addProperty("CopyTimestamps", true)
                    })

                    add(JsonObject().apply {
                        /*
                         * ① 封装回滚成 TS（父亲 2026-10-07 19:00）。
                         *    上一版为了救《律界战争》把 HLS 换成 fMP4 封装，结果把原本
                         *    流畅的《无可替代》拖成卡顿 + 音画不同步 —— 那部片的网盘源
                         *    在服务端侧直接报 DirectPlayError，只能走「边转边发」这条路，
                         *    而 fMP4 这条路的服务端吞吐明显不如 TS。回滚。
                         *
                         * ② 《律界战争》真正的毛病在别处（TS 里的 HEVC 让 Media3 的
                         *    H265Reader 在 SampleQueue.commitSample 抛 IllegalArgumentException），
                         *    改用客户端侧「崩了就用 H264 重来一次」的兜底，不再动全局封装。
                         */
                        addProperty("Container", "ts")
                        addProperty("Type", "Video")
                        // 转码输出能力（父亲 2026-10-06 报「无声」的根因就在这里）：
                        // 原来写死 aac,ac3,eac3,mp3 —— 源片是 EAC3 时服务端认为「输出 eac3
                        // 客户端也认」，于是音频原样 copy 过来，头显解不了就是静音。
                        // 只留 AAC/MP3，服务端就会把 EAC3/DTS 转成 AAC。
                        // 父亲 2026-10-07 实验：同上去掉「音频必须转码」这个前提
                        addProperty("AudioCodec", "aac,mp3,ac3,eac3")
                        addProperty("VideoCodec", if (actualDisableHevc) "h264" else supportedVideo)
                        addProperty("Context", "Streaming")
                        addProperty("Protocol", "hls")
                        addProperty("MaxAudioChannels", "8")
                        addProperty("MinSegments", "1")
                        addProperty("BreakOnNonKeyFrames", false)
                        addProperty("ManifestSubtitles", "vtt")
                    })

                    add(JsonObject().apply {
                        addProperty("Container", "webm")
                        addProperty("Type", "Video")
                        addProperty("AudioCodec", "vorbis")
                        addProperty("VideoCodec", "vpx")
                        addProperty("Context", "Streaming")
                        addProperty("Protocol", "http")
                        addProperty("MaxAudioChannels", "8")
                    })

                    add(JsonObject().apply {
                        addProperty("Container", "mp4")
                        addProperty("Type", "Video")
                        /*
                         * 父亲 2026-10-07 实验：连 AC3/EAC3 一起声明为支持。
                         * 目的是让服务端别为了"声音"去转码 —— 转码就会把画面一起
                         * 拖进「边转边发」的路，卡顿和音画不同步都从那儿来。
                         * 风险：头显系统的音轨名单里没有这两种解码器（media_codecs
                         * 实测），声明支持后很可能变成「画面流畅但没声音」。
                         */
                        addProperty("AudioCodec", supportedAudio + ",ac3,eac3")
                        addProperty("VideoCodec", "h264")
                        addProperty("Context", "Static")
                        addProperty("Protocol", "http")
                    })
                })

                add("ContainerProfiles", JsonArray())

                add("CodecProfiles", JsonArray().apply {
                    // 1. 声明高级音频支持，并解除声道限制（针对 7.1 声道原盘）
                    add(createCodecProfileAudio("truehd", maxChannels = 8))
                    add(createCodecProfileAudio("mlp", maxChannels = 8))
                    add(createCodecProfileAudio("dca", maxChannels = 8)) // DTS / DTS-HD / DTS:X
                    add(createCodecProfileAudio("dts", maxChannels = 8))
                    add(createCodecProfileAudio("ac3", maxChannels = 6))
                    add(createCodecProfileAudio("eac3", maxChannels = 8))

                    // 2. 基础音频支持
                    add(createCodecProfileAudio("aac"))
                    add(createCodecProfileAudio("flac"))
                    add(createCodecProfileAudio("vorbis"))
                    add(createCodecProfileAudio("mp3"))
                    add(createCodecProfileAudio("alac"))
                    add(createCodecProfileAudio("ape"))

                    add(JsonObject().apply {
                        addProperty("Type", "Video")
                        addProperty("Codec", "h264")
                        add("Conditions", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("Condition", "EqualsAny")
                                addProperty("Property", "VideoProfile")
                                addProperty("Value", "high|main|baseline|constrained baseline|high 10")
                                addProperty("IsRequired", false)
                            })
                            add(JsonObject().apply {
                                addProperty("Condition", "LessThanEqual")
                                addProperty("Property", "VideoLevel")
                                addProperty("Value", finalLevel)
                                addProperty("IsRequired", false)
                            })
                        })
                    })

                    if (!actualDisableHevc && (videoCodecs.contains("hevc") || videoCodecs.contains("h265"))) {
                        add(JsonObject().apply {
                            addProperty("Type", "Video")
                            addProperty("Codec", "hevc")
                            add("Conditions", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("Condition", "EqualsAny")
                                    addProperty("Property", "VideoCodecTag")
                                    addProperty("Value", "hvc1|hev1|hevc|hdmv")
                                    addProperty("IsRequired", false)
                                })
                            })
                        })
                    }
                })

                add("SubtitleProfiles", JsonArray().apply {
                    add(createSubtitleProfile("vtt", "Hls"))
                    add(createSubtitleProfile("eia_608", "VideoSideData", "hls"))
                    add(createSubtitleProfile("eia_708", "VideoSideData", "hls"))
                    add(createSubtitleProfile("vtt", "External"))
                    add(createSubtitleProfile("ass", "External"))
                    add(createSubtitleProfile("ssa", "External"))
                    add(createSubtitleProfile("srt", "External"))
                    add(createSubtitleProfile("subrip", "Embed"))
                })

                add("ResponseProfiles", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("Type", "Video")
                        addProperty("Container", "m4v")
                        addProperty("MimeType", "video/mp4")
                    })
                })
            }

            return JsonObject().apply {
                add("DeviceProfile", deviceProfile)
            }
        } catch (e: Exception) {
            ErrorHandler.logError("EmbyApi", "API请求失败", e)
            return JsonObject()
        }
    }

    private fun createTranscodingProfile(
        container: String,
        type: String,
        audioCodec: String,
        protocol: String,
        maxAudioChannels: String,
        context: String = "Streaming"
    ): JsonObject {
        return JsonObject().apply {
            addProperty("Container", container)
            addProperty("Type", type)
            addProperty("AudioCodec", audioCodec)
            addProperty("Context", context)
            addProperty("Protocol", protocol)
            addProperty("MaxAudioChannels", maxAudioChannels)
            if (context == "Streaming") {
                addProperty("MinSegments", "1")
                addProperty("BreakOnNonKeyFrames", false)
                if (protocol == "hls") {
                    addProperty("ManifestSubtitles", "vtt")
                }
            }
        }
    }

    private fun createCodecProfileAudio(codec: String?, maxChannels: Int = 8): JsonObject {
        return JsonObject().apply {
            // 1. 修改为 "Audio"，这是 Emby 处理音频能力的标准字段
            addProperty("Type", "Audio")

            if (codec != null) {
                addProperty("Codec", codec)
            }

            add("Conditions", JsonArray().apply {
                // 2. 核心：声明支持的最大声道数（解决原盘 7.1 降混问题）
                add(JsonObject().apply {
                    addProperty("Condition", "LessThanEqual")
                    addProperty("Property", "AudioChannels")
                    addProperty("Value", maxChannels.toString())
                    addProperty("IsRequired", "false")
                })

                // 3. 这里原来有一条 Property=IsSecondaryAudio 的条件，**已删除**（2026-09-27）：
                //    它只对「视频里的次要音轨」有意义，一旦服务端拿它去评估纯音频文件（音乐），
                //    Emby 的 MediaInfoService 会直接抛
                //      ArgumentException: Unexpected condition on audio file: IsSecondaryAudio
                //    → PlaybackInfo 500 → 客户端「获取播放信息失败」/ 播放页一直转圈。
                //    音频只保留上面的声道上限条件即可。
            })
        }
    }


    private fun createSubtitleProfile(
        format: String,
        method: String,
        protocol: String? = null
    ): JsonObject {
        return JsonObject().apply {
            addProperty("Format", format)
            addProperty("Method", method)
            if (protocol != null) {
                addProperty("Protocol", protocol)
            }
        }
    }

    private fun findMaxLevel(profiles: List<VideoProfile>, codec: String, defaultValue: Int): Int {
        return try {
            val profile = profiles.find { it.codec.equals(codec, ignoreCase = true) }
            profile?.maxLevel ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    private fun getDeviceCapabilities(context: Context): DeviceCapabilities {
        val videoCodecs = mutableSetOf<String>()
        val audioCodecs = mutableSetOf<String>()
        val videoProfiles = mutableListOf<VideoProfile>()

        //添加ffmpeg支持的类型
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecListAll = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecListAll.codecInfos) {
            if (info.isEncoder) continue
            
            for (type in info.supportedTypes) {
                try {
                    val caps = info.getCapabilitiesForType(type)

                    when {
                        type.equals("video/avc", ignoreCase = true) -> {
                            videoCodecs.add("h264")
                            val maxLevel = caps.profileLevels?.maxOfOrNull { mapAvcLevel(it.level) } ?: 41
                            videoProfiles.add(VideoProfile("h264", maxLevel))
                        }
                        type.equals("video/hevc", ignoreCase = true) -> {
                            videoCodecs.add("hevc")
                            videoCodecs.add("h265")
                        }
                        type.equals("video/av01", ignoreCase = true) -> videoCodecs.add("av1")
                        type.equals("video/x-vnd.on2.vp8", ignoreCase = true) -> videoCodecs.add("vp8")
                        type.equals("video/x-vnd.on2.vp9", ignoreCase = true) -> videoCodecs.add("vp9")
                        // type.equals("audio/mp4a-latm", ignoreCase = true) -> audioCodecs.add("aac")
//                        type.equals("audio/ac3", ignoreCase = true) -> audioCodecs.add("ac3")
//                        type.equals("audio/eac3", ignoreCase = true) -> audioCodecs.add("eac3")
                        // type.equals("audio/mpeg", ignoreCase = true) -> audioCodecs.add("mp3")
                        // type.equals("audio/flac", ignoreCase = true) -> audioCodecs.add("flac")
                        type.equals("audio/opus", ignoreCase = true) -> audioCodecs.add("opus")
                        type.equals("audio/vnd.dts", true) -> audioCodecs.add("dts")
                        type.equals("audio/vnd.dts.hd", true) -> {
                            audioCodecs.add("dts")
                            audioCodecs.add("dtshd")
                        }
//                        type.equals("audio/true-hd", true) -> audioCodecs.add("truehd")
                        // type.equals("audio/eac3-joc", true) -> audioCodecs.add("eac3")
                        type.equals("audio/ac4", true) -> audioCodecs.add("ac4")
                    }
                    /*
                     * 2026-10-06 父亲报「有些片子没有声音」：这里原来把
                     * ac3 / eac3 / dca / mlp / truehd 全声明成支持，服务端于是照原样直通
                     * 这些音轨；而头显只有 AAC / MP3 / FLAC / Opus 这类解码器，解不了就是静音。
                     * 改成只报真正解得动的，服务端会把 AC3 / DTS 这类转成 AAC。
                     */
                    audioCodecs.addAll(listOf("flac", "alac", "pcm_mulaw", "pcm_alaw", "mp3", "aac", "opus", "vorbis"))
                    // audioCodecs.addAll(listOf("truehd","mlp","dca","ac3","eac3","ape","alac"))

//                     audioCodecs.add("truehd")
                } catch (e: Exception) {
                    continue
                }
            }
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val rect = metrics.bounds
            rect.width() to rect.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            size.x to size.y
        }

        val canHandle4K = videoCodecs.contains("hevc") || videoCodecs.contains("av1")
        val maxDecodeWidth = if (canHandle4K) maxOf(screenWidth, 3840) else screenWidth
        val maxDecodeHeight = if (canHandle4K) maxOf(screenHeight, 2160) else screenHeight

        return DeviceCapabilities(
            videoCodecs = videoCodecs.toList(),
            audioCodecs = audioCodecs.toList(),
            videoProfiles = videoProfiles,
            maxCanvasWidth = maxDecodeWidth,
            maxCanvasHeight = maxDecodeHeight
        )
    }

    private fun mapAvcLevel(androidLevel: Int): Int {
        return when (androidLevel) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> 10
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> 11
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> 12
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> 13
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> 20
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> 21
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> 22
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> 30
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> 31
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> 32
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> 40
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> 41
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> 42
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> 50
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> 51
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> 52
            MediaCodecInfo.CodecProfileLevel.AVCLevel6 -> 60
            MediaCodecInfo.CodecProfileLevel.AVCLevel61 -> 61
            MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> 62
            else -> 41
        }
    }
}

// ==================== 数据类 ====================

data class VideoProfile(
    val codec: String,
    val maxLevel: Int,
    val profiles: List<String> = emptyList()
)

data class DeviceCapabilities(
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val videoProfiles: List<VideoProfile>,
    val maxCanvasWidth: Int = 3840,
    val maxCanvasHeight: Int = 2160
)
