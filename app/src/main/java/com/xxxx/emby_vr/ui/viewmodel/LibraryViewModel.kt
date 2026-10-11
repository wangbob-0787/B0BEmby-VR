package com.xxxx.emby_vr.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.data.local.PreferencesManager
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmbyRepository.getInstance(application)
    private val prefsManager = PreferencesManager(application)

    companion object {
        private const val TAG = "LibraryViewModel"
        const val PAGE_SIZE = 36

        val SORT_OPTIONS = listOf(
            "SortName" to R.string.sort_name,
            "ProductionYear,PremiereDate,SortName" to R.string.sort_premiere_date,
            "DateCreated,SortName" to R.string.sort_date_added,
            "DateModified,SortName" to R.string.sort_date_modified,
            "DateLastSaved,SortName" to R.string.sort_date_last_saved,
            "CommunityRating,SortName" to R.string.sort_community_rating,
            "CriticRating,SortName" to R.string.sort_rating,
            "Runtime,SortName" to R.string.sort_runtime,
            "DatePlayed,SortName" to R.string.sort_date_played,
            "ProductionYear,SortName" to R.string.sort_year,
            "OfficialRating,SortName" to R.string.sort_official_rating,
            "Studio,SortName" to R.string.sort_studio,
            "Resolution,SortName" to R.string.sort_resolution,
            "Framerate,SortName" to R.string.sort_framerate,
            "Random" to R.string.sort_random
        )

        val FILTER_OPTIONS = listOf(
            null to R.string.filter_all,
            "IsPlayed" to R.string.filter_watched,
            "IsUnplayed" to R.string.filter_unwatched,
            "IsFavorite" to R.string.filter_favorite
        )
    }

    var libraryItems by mutableStateOf<List<BaseItemDto>?>(null)
        private set

    var totalCount by mutableIntStateOf(0)
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var hasMoreData by mutableStateOf(true)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var currentParentId by mutableStateOf("")
        private set
    var currentType by mutableStateOf("")
        private set
    /*
     * 特殊来源（父亲 2026-10-11）：首页「继续观看 / 收藏」两行的标题点进来要一个列表页，
     * 而这两样在 Emby 那边**不是媒体库** —— 没有库编号，是用户维度的接口。
     * "" = 普通媒体库（按 parentId 取）；"resume" = 继续观看；"favorites" = 收藏。
     */
    var currentSpecial by mutableStateOf("")
        private set

    var currentSortBy by mutableStateOf(prefsManager.librarySortBy)
    var currentSortOrder by mutableStateOf(prefsManager.librarySortOrder)
    var currentFilter by mutableStateOf<String?>(null)

    fun loadItems(parentId: String, type: String, special: String = "") {
        if (parentId == currentParentId && type == currentType &&
            special == currentSpecial && libraryItems != null
        ) {
            return
        }

        currentParentId = parentId
        currentType = type
        currentSpecial = special
        currentPage = 0
        hasMoreData = true

        fetchItems()
    }

    fun updateSortAndFilter(sortBy: String, sortOrder: String, filter: String?) {
        currentSortBy = sortBy
        currentSortOrder = sortOrder
        currentFilter = filter
        prefsManager.librarySortBy = sortBy
        prefsManager.librarySortOrder = sortOrder
        currentPage = 0
        hasMoreData = true

        fetchItems()
    }

    private fun fetchItems() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            libraryItems = null

            try {
                val (items, total) = withContext(Dispatchers.IO) {
                    when (currentSpecial) {
                        /*
                         * 继续观看 / 收藏：走用户维度的接口，一次要够整页（不分页）。
                         * 300 条对家庭库足够，且这两个接口本身也不支持 startIndex 翻页。
                         */
                        "resume" -> repository.getResumeItems(limit = 300).let { it to it.size }
                        "favorites" -> repository.getFavoriteItems(limit = 300).let { it to it.size }
                        else -> repository.getLibraryList(
                            currentParentId, currentType, 0, PAGE_SIZE,
                            currentSortBy, currentSortOrder, currentFilter
                        )
                    }
                }
                libraryItems = items
                totalCount = total
                currentPage = 1
                // 特殊来源一次取完，不再翻页
                hasMoreData = currentSpecial.isEmpty() && items.size < total

                ErrorHandler.logDebug(TAG, "加载完成: ${items.size}/$total 条数据")
            } catch (e: Exception) {
                ErrorHandler.logError(TAG, "加载媒体库失败", e)
                errorMessage = ErrorHandler.getFriendlyMessage(e)
                libraryItems = emptyList()
                hasMoreData = false
            } finally {
                isLoading = false
            }
        }
    }

    fun loadMore() {
        // 继续观看 / 收藏一次取完，不翻页
        if (currentSpecial.isNotEmpty()) return
        if (isLoadingMore || !hasMoreData || isLoading) {
            return
        }

        viewModelScope.launch {
            isLoadingMore = true

            try {
                val startIndex = currentPage * PAGE_SIZE
                val (newItems, total) = withContext(Dispatchers.IO) {
                    repository.getLibraryList(
                        currentParentId, currentType, startIndex, PAGE_SIZE,
                        currentSortBy, currentSortOrder, currentFilter
                    )
                }

                if (newItems.isNotEmpty()) {
                    libraryItems = (libraryItems ?: emptyList()) + newItems
                    totalCount = total
                    currentPage++
                    hasMoreData = (libraryItems?.size ?: 0) < total

                    ErrorHandler.logDebug(TAG, "加载更多完成: ${libraryItems?.size}/$total 条数据")
                } else {
                    hasMoreData = false
                }
            } catch (e: Exception) {
                ErrorHandler.logError(TAG, "加载更多失败", e)
            } finally {
                isLoadingMore = false
            }
        }
    }

    fun refresh() {
        val parentId = currentParentId
        val type = currentType
        val special = currentSpecial
        currentParentId = ""
        currentType = ""
        currentSpecial = ""
        loadItems(parentId, type, special)
    }

    fun clear() {
        libraryItems = null
        currentParentId = ""
        currentType = ""
        currentSpecial = ""
        currentPage = 0
        totalCount = 0
        hasMoreData = true
    }
}
