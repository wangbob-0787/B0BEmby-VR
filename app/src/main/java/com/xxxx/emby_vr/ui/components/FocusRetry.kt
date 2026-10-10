package com.xxxx.emby_vr.ui.components

import androidx.compose.ui.focus.FocusRequester

/**
 * 要焦点：**带重试**（2026-10-10 系统排查后统一）。
 *
 * 起因：全 App 有十来处写法是「等 100~150 毫秒 → 要一次焦点」，只有海报卡做了三次重试。
 * 面板刚铺开、列表还在组合的时候，`requestFocus()` 会被直接忽略 —— 表现就是
 * 「进页面后按方向键没反应 / 焦点没落在该落的地方」，而且是偶发。
 * 这里统一成「最多试 3 次，每次隔 200 毫秒」，失败也不抛异常。
 *
 * 用法（在 LaunchedEffect 里调）：
 * ```
 * LaunchedEffect(Unit) { focusRequester.requestFocusRetry() }
 * ```
 */
suspend fun FocusRequester.requestFocusRetry(
    attempts: Int = 3,
    firstDelayMs: Long = 120L,
    nextDelayMs: Long = 200L,
): Boolean {
    repeat(attempts) { i ->
        kotlinx.coroutines.delay(if (i == 0) firstDelayMs else nextDelayMs)
        if (runCatching { requestFocus() }.isSuccess) return true
    }
    return false
}
