package com.xxxx.emby_vr.ui.components

import androidx.compose.ui.graphics.Color

/**
 * 控制条（OSD）那套按钮反馈色 —— **海报墙所有页面的按钮统一用它**
 * （父亲 2026-10-10：「海报墙所有页面的按钮被激光扫到的状态都改成和控制条一样」）。
 *
 * 取值与 `PlayerOsd.OsdButtonView` 一字不差：
 *   · 被光柱扫到 / 聚焦 = 半透明白（14%）
 *   · 选中（该档当前生效）= 半透明绿（#2FD57C 24%）
 *
 * 注意：**不要再用 `MaterialTheme.colorScheme.primary` 当焦点色** ——
 * 这个主题里 primary 是纯白，写错档就会出现"一聚焦变白底"（2026-10-10 踩过两次）。
 */
val VrHoverBg = Color(0x24FFFFFF)
val VrSelectedBg = Color(0x3D2FD57C)
