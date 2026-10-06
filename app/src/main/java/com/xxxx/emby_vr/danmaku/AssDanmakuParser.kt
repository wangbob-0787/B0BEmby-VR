package com.xxxx.emby_vr.danmaku

import android.graphics.Color

/** 单条弹幕的样式(取自 ASS 的 [V4+ Styles]) */
data class DanmakuStyle(
    val fontSize: Float,
    val primaryColor: Int,
    val outlineColor: Int,
    val outline: Float,
    val alignment: Int,
)

/** 单条弹幕 */
data class DanmakuItem(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isMove: Boolean,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val posX: Float,
    val posY: Float,
    val style: DanmakuStyle,
)

data class DanmakuTrack(
    val playResX: Float,
    val playResY: Float,
    val items: List<DanmakuItem>,
)

/**
 * 极简 ASS 解析器:只服务于"弹幕 ASS"这一种文件。
 *
 * 为什么要自己解析:Media3 内置的 SSA 解析器**不支持 \move 标签**,
 * 滚动弹幕会被当成普通字幕堆在画面底部。这里把 \move / \pos 原始坐标读出来,
 * 交给 DanmakuView 按时间轴逐帧绘制,滚动效果才能还原。
 */
object AssDanmakuParser {

    private val MOVE_RE = Regex("""\\move\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)""")
    private val POS_RE = Regex("""\\pos\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)""")
    // 右花括号必须转义:裸 } 在部分 JDK 的 Pattern 里会抛 PatternSyntaxException(曾导致播放页闪退)
    private val TAG_RE = Regex("""\{[^}]*\}""")
    private val TIME_RE = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,2})""")

    fun parse(raw: String): DanmakuTrack {
        val text = raw.removePrefix("\uFEFF")
        var playResX = 1920f
        var playResY = 1080f
        val styles = HashMap<String, DanmakuStyle>()
        val items = ArrayList<DanmakuItem>()
        var section = ""

        for (lineRaw in text.split('\n')) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                section = line.lowercase()
                continue
            }
            when {
                section.startsWith("[script info") -> {
                    when {
                        line.startsWith("PlayResX", true) ->
                            playResX = line.substringAfter(':').trim().toFloatOrNull() ?: playResX
                        line.startsWith("PlayResY", true) ->
                            playResY = line.substringAfter(':').trim().toFloatOrNull() ?: playResY
                    }
                }

                section.startsWith("[v4+ styles") || section.startsWith("[v4 styles") -> {
                    if (line.startsWith("Style:", true)) {
                        val parts = line.substringAfter(':').split(',')
                        if (parts.size >= 23) {
                            styles[parts[0].trim()] = DanmakuStyle(
                                fontSize = parts[2].trim().toFloatOrNull() ?: 48f,
                                primaryColor = parseAssColor(parts[3].trim()),
                                outlineColor = parseAssColor(parts[5].trim()),
                                outline = parts[16].trim().toFloatOrNull() ?: 1.5f,
                                alignment = parts[18].trim().toIntOrNull() ?: 2,
                            )
                        }
                    }
                }

                section.startsWith("[events") -> {
                    if (line.startsWith("Dialogue:", true)) {
                        val parts = splitDialogue(line.substringAfter(':'))
                        if (parts.size >= 10) {
                            val start = parseTime(parts[1]) ?: continue
                            val end = parseTime(parts[2]) ?: continue
                            val style = styles[parts[3].trim()]
                                ?: DanmakuStyle(48f, Color.WHITE, Color.BLACK, 1.5f, 7)
                            val rawText = parts[9]
                            val content = TAG_RE.replace(rawText, "").trim()
                            if (content.isEmpty()) continue
                            val move = MOVE_RE.find(rawText)
                            val pos = POS_RE.find(rawText)
                            items.add(
                                DanmakuItem(
                                    startMs = start,
                                    endMs = end,
                                    text = content,
                                    isMove = move != null,
                                    x1 = move?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                                    y1 = move?.groupValues?.get(2)?.toFloatOrNull() ?: 0f,
                                    x2 = move?.groupValues?.get(3)?.toFloatOrNull() ?: 0f,
                                    y2 = move?.groupValues?.get(4)?.toFloatOrNull() ?: 0f,
                                    posX = pos?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                                    posY = pos?.groupValues?.get(2)?.toFloatOrNull() ?: 0f,
                                    style = style,
                                )
                            )
                        }
                    }
                }
            }
        }
        items.sortBy { it.startMs }
        return DanmakuTrack(playResX, playResY, items)
    }

    /** Dialogue 行前 9 个逗号是字段分隔,第 10 段(Text)里可能含逗号 */
    private fun splitDialogue(s: String): List<String> {
        val out = ArrayList<String>(10)
        var idx = 0
        var count = 0
        while (count < 9) {
            val c = s.indexOf(',', idx)
            if (c < 0) break
            out.add(s.substring(idx, c))
            idx = c + 1
            count++
        }
        out.add(s.substring(idx))
        return out
    }

    private fun parseTime(s: String): Long? {
        val m = TIME_RE.find(s.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val mi = m.groupValues[2].toLongOrNull() ?: 0L
        val sec = m.groupValues[3].toLongOrNull() ?: 0L
        val cs = (m.groupValues[4] + "0").substring(0, 2).toLongOrNull() ?: 0L
        return (h * 3600 + mi * 60 + sec) * 1000 + cs * 10
    }

    /** ASS 颜色为 &HAABBGGRR,且 alpha 00 表示不透明 */
    private fun parseAssColor(v: String): Int {
        val hex = v.removePrefix("&H").removePrefix("&h").trim()
        val value = hex.toLongOrNull(16) ?: return Color.WHITE
        val aRaw = ((value shr 24) and 0xFF).toInt()
        val alpha = if (aRaw == 0) 0xFF else 0xFF - aRaw
        val b = ((value shr 16) and 0xFF).toInt()
        val g = ((value shr 8) and 0xFF).toInt()
        val r = (value and 0xFF).toInt()
        return Color.argb(alpha, r, g, b)
    }
}
