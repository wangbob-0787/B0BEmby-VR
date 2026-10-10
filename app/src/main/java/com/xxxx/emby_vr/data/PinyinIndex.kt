package com.xxxx.emby_vr.data

import android.content.Context
import android.util.Log
import com.github.promeg.pinyinhelper.Pinyin
import com.xxxx.emby_vr.data.repository.EmbyRepository
import java.io.File

/**
 * 本地拼音索引（父亲 2026-10-10 定，方案 B）。
 *
 * ## 干什么
 * 内置键盘只能打出英文字母。为了能用字母搜中文片名/剧名，
 * 这里把**全库的片名与剧名**转成拼音（全拼 + 首字母），
 * 用户打 `qyn` 就出候选「庆余年」，选中后用真名走正常搜索。
 *
 * ## 数据从哪来
 * Emby 的 `/Users/{uid}/Items` 分页拉（每页 10000 条，实测 4.6 MB / 1.7 秒）。
 * 库里约 4.5 万条电影+剧集，拉完约 5 页。
 *
 * ## 缓存
 * 结果落在 `filesDir/pinyin_index.txt`（每行 `id\t名字`），有效期 7 天；
 * 过期的下次进键盘时自动重建。拼音是本地现算的（不存盘，省空间）。
 *
 * 索引只做**片名与剧名**；演员名走原来的「演员通道」（服务端 /Persons 查）。
 */
object PinyinIndex {

    private const val TAG = "B0BEmbyVR"
    private const val FILE_NAME = "pinyin_index.txt"
    private const val PAGE_SIZE = 10000
    private const val MAX_ITEMS = 200_000
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    data class Entry(val id: String, val name: String, val initials: String, val full: String)

    private val lock = Any()
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var building = false

    @Volatile
    private var ready = false

    /** 索引是否已经可用（键盘那边用它决定显示"正在建立…"还是候选） */
    fun isReady(): Boolean = ready && entries.isNotEmpty()

    /** 索引里有多少条（诊断用） */
    fun size(): Int = entries.size

    /**
     * 保证索引可用：有新鲜缓存就用缓存，否则拉全库重建。
     *
     * 可以重复调用（例如每次打开键盘时调一次），正在建的时候直接返回。
     */
    suspend fun ensure(context: Context, repository: EmbyRepository, force: Boolean = false) {
        synchronized(lock) {
            if (building) return
            val f = File(context.filesDir, FILE_NAME)
            if (!force && f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) {
                if (entries.isEmpty() && loadFrom(f)) {
                    ready = true
                    Log.i(TAG, "拼音索引：用缓存，${entries.size} 条")
                    return
                }
                if (entries.isNotEmpty()) return
            }
            building = true
        }

        try {
            val all = ArrayList<Entry>(4096)
            var start = 0
            while (all.size < MAX_ITEMS) {
                val page = runCatching { repository.getItemNamesPage(start, PAGE_SIZE) }
                    .getOrElse { emptyList() }
                if (page.isEmpty()) break
                all += page.mapNotNull { item ->
                    val name = item.name?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    val id = item.id.orEmpty()
                    val (full, initials) = pinyinOf(name)
                    if (initials.isEmpty()) return@mapNotNull null
                    Entry(id, name, initials, full)
                }
                start += page.size
                if (page.size < PAGE_SIZE) break
            }

            // 同名只留一条（同一部片有 4K/1080p/不同封装多个版本，名字是一样的）
            val dedup = LinkedHashMap<String, Entry>()
            for (e in all) dedup.putIfAbsent(e.name, e)

            entries = dedup.values.toList()
            ready = entries.isNotEmpty()
            Log.i(TAG, "拼音索引：建好 ${entries.size} 条（原始 ${all.size} 条，已去重）")
            saveTo(File(context.filesDir, FILE_NAME))
        } finally {
            building = false
        }
    }

    /**
     * 输入字母 → 候选片名。
     *
     * 先匹配**首字母**（qyn → 庆余年），再匹配全拼（qingy → 庆余年）。
     * 两个都命中的按"名字短的在前面"（短名通常更贴近用户想找的）。
     */
    fun candidates(typed: String, limit: Int = 8): List<Entry> {
        val q = typed.lowercase().filter { it.isLetterOrDigit() }
        if (q.isEmpty() || entries.isEmpty()) return emptyList()

        val list = entries
        val initialHits = list.filter { it.initials.startsWith(q) }
        val fullHits = if (initialHits.size >= limit) {
            emptyList()
        } else {
            val taken = initialHits.map { it.name }.toHashSet()
            list.filter { it.name !in taken && it.full.startsWith(q) }
        }
        return (initialHits + fullHits)
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .take(limit)
    }

    /** 汉字 → 全拼 + 首字母（非汉字按原样收进去，英文片名照样能打） */
    private fun pinyinOf(name: String): Pair<String, String> {
        val full = StringBuilder(name.length * 4)
        val initials = StringBuilder(name.length)
        for (ch in name) {
            when {
                Pinyin.isChinese(ch) -> {
                    val py = runCatching { Pinyin.toPinyin(ch) }.getOrDefault("")
                    if (py.isNotEmpty()) {
                        full.append(py.lowercase())
                        initials.append(py.first().lowercaseChar())
                    }
                }
                ch.isLetterOrDigit() -> {
                    full.append(ch.lowercaseChar())
                    initials.append(ch.lowercaseChar())
                }
                // 空格 / 标点跳过：不影响匹配，还能让首字母连起来
                else -> Unit
            }
        }
        return full.toString() to initials.toString()
    }

    private fun loadFrom(f: File): Boolean {
        return runCatching {
            val list = ArrayList<Entry>(45000)
            f.forEachLine { line ->
                val t = line.indexOf('\t')
                if (t > 0) {
                    val id = line.substring(0, t)
                    val name = line.substring(t + 1)
                    if (name.isNotBlank()) {
                        val (full, initials) = pinyinOf(name)
                        if (initials.isNotEmpty()) list.add(Entry(id, name, initials, full))
                    }
                }
            }
            entries = list
            entries.isNotEmpty()
        }.getOrElse {
            Log.w(TAG, "拼音索引：读缓存失败：${it.message}")
            false
        }
    }

    private fun saveTo(f: File) {
        runCatching {
            f.bufferedWriter().use { w ->
                entries.forEach { e -> w.write("${e.id}\t${e.name}\n") }
            }
            Log.i(TAG, "拼音索引：缓存已写 ${entries.size} 条")
        }.onFailure { Log.w(TAG, "拼音索引：写缓存失败：${it.message}") }
    }
}
