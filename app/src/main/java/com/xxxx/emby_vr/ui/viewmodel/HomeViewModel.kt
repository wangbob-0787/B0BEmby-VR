package com.xxxx.emby_vr.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel
 * 
 * 职责：
 * - 加载继续观看列表
 * - 加载媒体库最新内容
 * - 加载收藏列表
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmbyRepository.getInstance(application)

    // === 首页数据 ===
    var resumeItems by mutableStateOf<List<BaseItemDto>?>(null)
        private set
    var libraryLatestItems by mutableStateOf<List<BaseItemDto>?>(null)
        private set
    var favoriteItems by mutableStateOf<List<BaseItemDto>?>(null)
        private set
    // 直播频道（首页「电视直播」一行）
    var liveChannels by mutableStateOf<List<BaseItemDto>?>(null)
        private set
    // 服务器上的「电视直播」视图（父亲 2026-10-02：媒体库里也要有这个入口，点进去看全部频道）
    var liveTvView by mutableStateOf<BaseItemDto?>(null)
        private set

    // === 加载状态 ===
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    /**
     * 首页最后一处错误（2026-10-05 立）：errorMessage 会被清掉换成 Toast，
     * 而 VR 里看不见系统 Toast —— 父亲遇到「首页暂无数据」时无从判断原因。
     * 这个字段只增不清，直接画在面板上。
     */
    var lastError by mutableStateOf<String?>(null)
        private set

    // === 首屏缓存:上次首页数据先画出来,网络请求在后台刷新(大库首查约 6 秒,不能干等) ===
    private val cacheFile = java.io.File(application.filesDir, "home_cache.json")
    private val gson = com.google.gson.Gson()

    init {
        restoreFromCache()
        loadData()
    }

    private fun restoreFromCache() {
        try {
            if (!cacheFile.exists()) return
            val cache = gson.fromJson(cacheFile.readText(), HomeCache::class.java) ?: return
            resumeItems = cache.resume
            libraryLatestItems = cache.latest
            favoriteItems = cache.favorites
            liveChannels = cache.live
            liveTvView = cache.liveView
        } catch (e: Exception) {
            ErrorHandler.logError("HomeViewModel", "读取首页缓存失败", e)
        }
    }

    private fun saveCache() {
        try {
            if (resumeItems.isNullOrEmpty() && libraryLatestItems.isNullOrEmpty() &&
                favoriteItems.isNullOrEmpty() && liveChannels.isNullOrEmpty()
            ) return
            cacheFile.writeText(
                gson.toJson(
                    HomeCache(
                        resumeItems, libraryLatestItems, favoriteItems,
                        liveChannels, liveTvView,
                    )
                )
            )
        } catch (e: Exception) {
            ErrorHandler.logError("HomeViewModel", "写入首页缓存失败", e)
        }
    }

    /**
     * 加载首页数据
     */
    fun loadData() {
        if (!repository.isLoggedIn) {
            val why = "没有登录会话（服务器=${repository.serverUrl ?: "无"}）"
            lastError = why
            android.util.Log.w("B0BEmbyVR", "首页：$why —— 首页会显示暂无数据")
            resumeItems = emptyList()
            libraryLatestItems = emptyList()
            favoriteItems = emptyList()
            liveChannels = emptyList()
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            try {
                val resumeDeferred = async {
                    try { repository.getResumeItems() }
                    catch (e: Exception) {
                        errorMessage = e.message
                        lastError = "继续观看：${e.message}"
                        android.util.Log.w("B0BEmbyVR", "首页「继续观看」失败：${e.message}")
                        emptyList()
                    }
                }
                val latestDeferred = async {
                    try { repository.getLatestItems() }
                    catch (e: Exception) {
                        errorMessage = e.message
                        lastError = "继续观看：${e.message}"
                        android.util.Log.w("B0BEmbyVR", "首页「继续观看」失败：${e.message}")
                        emptyList()
                    }
                }
                val favDeferred = async {
                    try { repository.getFavoriteItems() }
                    catch (e: Exception) {
                        errorMessage = e.message
                        lastError = "继续观看：${e.message}"
                        android.util.Log.w("B0BEmbyVR", "首页「继续观看」失败：${e.message}")
                        emptyList()
                    }
                }
                // 直播频道：失败不影响首页其余部分（服务器没配直播源时就是空行）
                val liveDeferred = async {
                    try { repository.getLiveTvChannels() }
                    catch (e: Exception) { emptyList() }
                }
                val viewsDeferred = async {
                    try { repository.getViews() }
                    catch (e: Exception) { emptyList() }
                }

                /*
                 * 先把数据**全部等回来**，再一次性写进状态（父亲 2026-10-10）。
                 *
                 * 原来是一条一条 `await()` 跟着赋值：每赋一个值就重画一次，
                 * 首页分四五步长出来 —— 直播行、每库最新行都是后一步才出现的，
                 * 每出现一行下面的内容就往下挪一次，正好在那一刻扣扳机就点错东西。
                 * 中间没有挂起点之后，这些赋值落在同一帧里，Compose 合成一次重画，
                 * 首屏一次成型，位置不再跳。
                 */
                val resume = resumeDeferred.await()
                val latest = latestDeferred.await()
                val fav = favDeferred.await()
                val live = liveDeferred.await()
                val liveView = viewsDeferred.await()
                    .firstOrNull { it.collectionType.equals("livetv", ignoreCase = true) }

                resumeItems = resume
                libraryLatestItems = latest
                favoriteItems = fav
                liveChannels = live
                liveTvView = liveView
            } catch (e: Exception) {
                android.util.Log.w("B0BEmbyVR", "首页加载整体失败：${e.message}")
                if (errorMessage == null) errorMessage = e.message
                if (lastError == null) lastError = "首页加载失败：${e.message}"
                resumeItems = emptyList()
                libraryLatestItems = emptyList()
                favoriteItems = emptyList()
                liveChannels = emptyList()
            } finally {
                isLoading = false
                saveCache()
            }
        }
    }

    /**
     * 刷新数据
     */
    fun refresh() = loadData()

    /**
     * 获取下一集信息
     */
    fun playNextUp(seriesId: String, onResult: (BaseItemDto) -> Unit) {
        viewModelScope.launch {
            try {
                val itemsArray = repository.getShowsNextUp(seriesId)
                val items = itemsArray.ifEmpty {
                    repository.getSeriesList(seriesId)
                }
                if (items.isNotEmpty()) {
                    onResult(items.first())
                }
            } catch (e: Exception) {
                ErrorHandler.logError("HomeViewModel", "加载数据失败", e)
            }
        }
    }

    fun clearError() {
        errorMessage = null
    }
}

/** 首页首屏缓存,字段名即 JSON 键 */
private data class HomeCache(
    val resume: List<BaseItemDto>? = null,
    val latest: List<BaseItemDto>? = null,
    val favorites: List<BaseItemDto>? = null,
    /*
     * 电视直播也进缓存（父亲 2026-10-10 报的"误点直播"）：
     * 直播行与「我的媒体库」里的直播图块都是网络回来后才有的，原先缓存不带它们 ——
     * 首屏先按缓存画完，过一会儿直播才插进来，下面的内容整块往下跳；
     * 父亲正好在那一下点海报，就点到刚冒出来的直播上了。带上它们后首屏位置就稳定。
     */
    val live: List<BaseItemDto>? = null,
    val liveView: BaseItemDto? = null,
)
