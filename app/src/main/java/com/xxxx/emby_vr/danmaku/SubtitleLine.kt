package com.xxxx.emby_vr.danmaku

/**
 * 一行字幕 + 它自己的样式（父亲 2026-10-09：ASS 里的字体、大小、颜色要照着执行）。
 *
 * 背景：内封 ASS 是服务端转成 SRT 给我们的，样式被翻译成 HTML 风格的 font 标签：
 *
 *     <font face="微软雅黑" size="72" color="#ffe680"><b>中文台词</b></font>
 *     <font face="微软雅黑" size="48">  English line</font>
 *
 * 我们原来只是把这些标签删掉（而且长度还限了 40 字符，长标签根本删不掉），
 * 于是「微软雅黑 / size / color」这些字面量被原样画到了屏幕上，字号颜色也全没执行。
 *
 * 现在解析成结构化的行：
 *  · [sizeFactor] 相对"这一集最常见的那档字号"（中文字号大、英文字号小，比例照原片）；
 *  · [color]     行内文字色，取不到就用默认白。
 */
data class SubtitleLine(
    val text: String,
    /** 字号倍数：1.0 = 这一集的主体字号 */
    val sizeFactor: Float = 1f,
    /** 文字颜色（ARGB），null = 用默认白 */
    val color: Int? = null,
)
