package com.xxxx.emby_vr.ui

/**
 * 跨页面的焦点记忆（父亲 2026-10-02 要求：返回上一页时焦点回到之前的位置）。
 *
 * 用法：
 *  - 卡片获得焦点时写入 [lastItemId]（`BuildItem` 里做）；
 *  - 页面（首页）重新出现时读它，把焦点送回那张卡，并把它滚进视野；
 *  - 用一次就清空（[consume]），避免以后每次进首页都强抢焦点。
 *
 * 只记条目 id 不记索引：列表顺序/内容会变（各库最新每天都在变），按 id 找最稳。
 */
object FocusMemory {
    var lastItemId: String? = null

    /** 看一眼但不消费（组合期判断"这次是不是要恢复焦点"） */
    fun peek(): String? = lastItemId

    /** 取一次就清空；没记住过返回 null */
    fun consume(): String? {
        val v = lastItemId
        lastItemId = null
        return v
    }
}
