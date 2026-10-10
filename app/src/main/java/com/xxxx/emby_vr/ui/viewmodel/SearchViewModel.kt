package com.xxxx.emby_vr.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxxx.emby_vr.data.local.PreferencesManager
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.remote.EmbyApi
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.data.session.AccountInfo
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 搜索结果包装类，包含所属账号信息
 */
data class SearchResultModel(
    val item: BaseItemDto,
    val account: AccountInfo
)

/**
 * 搜索 ViewModel
 *
 * 职责：
 * - 管理搜索关键词与**搜索通道**（父亲 2026-10-10：单选，片名 / 演员）
 * - 并发搜索所有已登录账号 (Max 4 requests)
 * - 聚合搜索结果
 * - 支持按账号过滤
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmbyRepository.getInstance(application)
    private val context = application
    private val prefs = PreferencesManager(application)
    private var searchJob: Job? = null

    companion object {
        private const val TAG = "SearchViewModel"

        /** 首屏每个账号取多少条（父亲 2026-10-10：先给一屏够看的量） */
        const val FIRST_PAGE = 60

        /** 「加载更多」每次再放开多少条 */
        const val MORE_STEP = 60

        private const val MAX_CONCURRENT_REQUESTS = 4
    }

    /**
     * 搜索通道（父亲 2026-10-10 定：**单选**，只做了两条 —— 片名 与 演员）。
     *
     * 为什么要通道：Emby 的关键词只匹配片名（实测「刘德华」只出片名带这三个字的片子），
     * 演员必须先用演员名换 Id、再按 Id 取作品。与其做「一个词自动猜」，父亲选了
     * 更可控的做法 —— 搜索框下面两个勾选框，勾哪个就走哪条路。
     */
    enum class Channel { NAME, PERSON }

    /** 当前通道；初值 = 上次用过的（记住用户的选择） */
    var channel by mutableStateOf(
        if (prefs.searchChannel == PreferencesManager.SEARCH_CHANNEL_PERSON) Channel.PERSON
        else Channel.NAME
    )
        private set

    /**
     * 切换通道并记住，下次打开搜索框沿用。
     *
     * 方法名不能叫 setChannel —— `var channel` 会自动生成同名 setter，JVM 签名冲突（编译报
     * Platform declaration clash），所以用 selectChannel。
     */
    fun selectChannel(c: Channel) {
        if (channel == c) return
        channel = c
        prefs.searchChannel = if (c == Channel.PERSON) {
            PreferencesManager.SEARCH_CHANNEL_PERSON
        } else {
            PreferencesManager.SEARCH_CHANNEL_NAME
        }
    }

    /** 上一次真正搜过的通道：缓存判重要连通道一起比，否则切了通道还拿旧结果糊弄 */
    private var lastChannel = Channel.NAME

    /** 本次搜索每个账号的取数上限（「加载更多」会加大） */
    private var perAccountLimit = FIRST_PAGE

    // === 搜索关键词 ===
    var currentQuery by mutableStateOf("")
        private set

    // === 搜索结果 (聚合后) ===
    private var _allSearchResults by mutableStateOf<List<SearchResultModel>?>(null)

    // === 过滤条件 (当前选中的账号) ===
    var filterAccount by mutableStateOf<AccountInfo?>(null)

    // === UI 展示用的结果 (经过过滤) ===
    val searchResults: List<SearchResultModel>?
        get() {
            val all = _allSearchResults ?: return null
            return if (filterAccount != null) {
                all.filter { it.account.id == filterAccount?.id }
            } else {
                all
            }
        }

    // === 状态 ===
    var isSearching by mutableStateOf(false)
        private set
    var searchProgress by mutableStateOf(0 to 0) // (已完成账号数, 总账号数)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    /** 还能不能再加载（上一次有没有账号取满一整页） */
    var hasMore by mutableStateOf(false)
        private set

    /**
     * 给界面看的提示（不是错误）：例如演员通道全都没命中 → "库里没有叫「刘德华」的演员"。
     * 和"没有结果"是两件事：一个是名字没打对，一个是这人库里确实没作品。
     */
    var hint by mutableStateOf<String?>(null)
        private set

    /** 本次搜索涉及的总条数 (统计用) */
    var totalCount by mutableStateOf(0)
        private set

    /**
     * 执行搜索。走哪条路看 [channel]：
     *   · 片名 → 关键词直接搜
     *   · 演员 → 先拿演员 Id，再取他在库里的全部作品
     */
    fun search(query: String) {
        if (query.isBlank()) {
            clearResults()
            return
        }

        // 词和通道都没变、结果还在 → 不重复搜
        if (query == currentQuery && channel == lastChannel && _allSearchResults != null) return

        perAccountLimit = FIRST_PAGE
        runSearch(query)
    }

    /** 滑到底部继续加载：加大每个账号的取数上限后重新聚合 */
    fun loadMore() {
        if (isSearching || currentQuery.isBlank() || !hasMore) return
        perAccountLimit += MORE_STEP
        runSearch(currentQuery)
    }

    private fun runSearch(query: String) {
        currentQuery = query
        lastChannel = channel
        filterAccount = null

        searchJob = viewModelScope.launch {
            isSearching = true
            _allSearchResults = null
            errorMessage = null
            hint = null
            totalCount = 0
            searchProgress = 0 to 0

            try {
                val accounts = repository.savedAccounts
                if (accounts.isEmpty()) {
                    isSearching = false
                    return@launch
                }

                val currentId = repository.currentAccountId
                val sortedAccounts = accounts.sortedByDescending { it.id == currentId }
                val totalAccounts = sortedAccounts.size
                val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
                val completedCount = AtomicInteger(0)
                val resultsMutex = Mutex()
                val fullPageMutex = Mutex()
                var anyFullPage = false

                val jobs = sortedAccounts.map { account ->
                    async {
                        semaphore.acquire()
                        try {
                            if (!coroutineContext.isActive) return@async

                            val (items, count) = searchAccount(account, query)

                            if (!coroutineContext.isActive) return@async

                            resultsMutex.withLock {
                                _allSearchResults = (_allSearchResults ?: emptyList()) + items
                                totalCount += count
                            }
                            fullPageMutex.withLock {
                                if (items.size >= perAccountLimit) anyFullPage = true
                            }
                            searchProgress = completedCount.incrementAndGet() to totalAccounts

                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            completedCount.incrementAndGet()
                            searchProgress = completedCount.get() to totalAccounts
                        } finally {
                            semaphore.release()
                        }
                    }
                }

                jobs.awaitAll()
                hasMore = anyFullPage

                if (channel == Channel.PERSON && _allSearchResults.isNullOrEmpty()) {
                    hint = "库里没有叫「$query」的演员"
                }
                ErrorHandler.logDebug(
                    TAG,
                    "搜索完成($channel)：${_allSearchResults?.size ?: 0} 条结果，每账号上限 $perAccountLimit"
                )

            } catch (e: CancellationException) {
                ErrorHandler.logDebug(TAG, "搜索已取消")
                _allSearchResults = _allSearchResults ?: emptyList()
            } catch (e: Exception) {
                ErrorHandler.logError(TAG, "搜索失败", e)
                _allSearchResults = _allSearchResults ?: emptyList()
                errorMessage = e.message ?: "搜索失败"
            } finally {
                isSearching = false
                searchJob = null
            }
        }
    }

    /**
     * 单个账号搜索
     */
    private suspend fun searchAccount(
        account: AccountInfo,
        query: String,
    ): Pair<List<SearchResultModel>, Int> {
        return try {
            val items: List<BaseItemDto> = when (channel) {
                Channel.NAME -> EmbyApi.searchItems(
                    context = context,
                    serverUrl = account.serverUrl,
                    apiKey = account.apiKey,
                    deviceId = account.deviceId,
                    userId = account.userId,
                    query = query,
                    startIndex = 0,
                    limit = perAccountLimit,
                ).first

                Channel.PERSON -> {
                    // 第一步：演员名 → 演员 Id（取第一个命中的）
                    val personId = withContext(Dispatchers.IO) {
                        EmbyApi.searchPersons(
                            context = context,
                            serverUrl = account.serverUrl,
                            apiKey = account.apiKey,
                            deviceId = account.deviceId,
                            userId = account.userId,
                            query = query,
                        ).firstOrNull()?.id
                    }
                    if (personId.isNullOrBlank()) {
                        emptyList()
                    } else {
                        // 第二步：按 Id 取他的作品（服务端已按上映时间从新到旧排好）
                        EmbyApi.getItemsByPerson(
                            context = context,
                            serverUrl = account.serverUrl,
                            apiKey = account.apiKey,
                            deviceId = account.deviceId,
                            userId = account.userId,
                            personId = personId,
                            startIndex = 0,
                            limit = perAccountLimit,
                        ).first
                    }
                }
            }

            val models = items.map { SearchResultModel(it, account) }
            // 数量一律按实际拿到的条数算：服务端返回的总数实测不可信（给过 0，也给过全库数）
            Pair(models, models.size)
        } catch (e: Exception) {
            ErrorHandler.logError(TAG, "账号 [${account.username}] 搜索失败: ${e.message}")
            // 单个账号失败不影响整体，返回空
            Pair(emptyList(), 0)
        }
    }

    /**
     * 刷新搜索（例如账号列表变化后，或者用户手动刷新）
     */
    fun refreshSearch() {
        if (currentQuery.isNotBlank()) {
            val query = currentQuery
            currentQuery = "" // 强制触发
            runSearch(query)
        }
    }

    /**
     * 设置账号过滤
     */
    fun setAccountFilter(account: AccountInfo?) {
        filterAccount = account
    }

    /**
     * 取消正在进行的搜索
     */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        isSearching = false
    }

    /**
     * 清空
     */
    fun clearResults() {
        currentQuery = ""
        _allSearchResults = null
        filterAccount = null
        totalCount = 0
        errorMessage = null
        hint = null
        hasMore = false
        searchProgress = 0 to 0
    }
}

/**
 * 演员作品的分组（父亲 2026-10-10 定）：
 * 电影 / 电视剧 / 演唱会 / 纪录片 / 其他 —— **没有内容的那一组不显示**。
 *
 * 分组全在本地算：一次请求就把 Type 与 Genres 带回来了，不用按库再查。
 */
enum class PersonWorkGroup(val label: String) {
    MOVIE("电影"),
    TV("电视剧"),
    CONCERT("演唱会"),
    DOCUMENTARY("纪录片"),
    OTHER("其他"),
}

/**
 * 判断一条作品属于哪一组。
 *
 * 依据（2026-10-10 实测）：
 *   · 电视剧 = Type Series；电影 = Type Movie
 *   · 演唱会 = 电影 + 题材「音乐 / Music」（库里就是这么标的：左麟右李演唱会 → Music）
 *   · 纪录片 = 题材含「纪录片 / 纪录 / Documentary」
 */
fun classifyPersonWork(item: BaseItemDto): PersonWorkGroup {
    val genres = item.genres.orEmpty().joinToString(" ").lowercase()
    val isConcert = genres.contains("音乐") || genres.contains("music")
    val isDoc = genres.contains("纪录片") || genres.contains("纪录") ||
        genres.contains("documentary")

    return when {
        item.type.equals("Series", ignoreCase = true) -> PersonWorkGroup.TV
        isConcert -> PersonWorkGroup.CONCERT
        isDoc -> PersonWorkGroup.DOCUMENTARY
        item.type.equals("Movie", ignoreCase = true) -> PersonWorkGroup.MOVIE
        else -> PersonWorkGroup.OTHER
    }
}
