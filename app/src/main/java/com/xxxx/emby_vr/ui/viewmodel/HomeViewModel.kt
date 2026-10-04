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
        } catch (e: Exception) {
            ErrorHandler.logError("HomeViewModel", "读取首页缓存失败", e)
        }
    }

    private fun saveCache() {
        try {
            if (resumeItems.isNullOrEmpty() && libraryLatestItems.isNullOrEmpty() && favoriteItems.isNullOrEmpty()) return
            cacheFile.writeText(gson.toJson(HomeCache(resumeItems, libraryLatestItems, favoriteItems)))
        } catch (e: Exception) {
            ErrorHandler.logError("HomeViewModel", "写入首页缓存失败", e)
        }
    }

    /**
     * 加载首页数据
     */
    fun loadData() {
        if (!repository.isLoggedIn) {
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
                    catch (e: Exception) { errorMessage = e.message; emptyList() }
                }
                val latestDeferred = async {
                    try { repository.getLatestItems() }
                    catch (e: Exception) { errorMessage = e.message; emptyList() }
                }
                val favDeferred = async {
                    try { repository.getFavoriteItems() }
                    catch (e: Exception) { errorMessage = e.message; emptyList() }
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

                resumeItems = resumeDeferred.await()
                libraryLatestItems = latestDeferred.await()
                favoriteItems = favDeferred.await()
                liveChannels = liveDeferred.await()
                liveTvView = viewsDeferred.await()
                    .firstOrNull { it.collectionType.equals("livetv", ignoreCase = true) }
            } catch (e: Exception) {
                if (errorMessage == null) errorMessage = e.message
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
    val favorites: List<BaseItemDto>? = null
)
