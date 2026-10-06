package com.xxxx.emby_vr.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * 独立的弹幕绘制层。
 *
 * 设计要点(2026-09-19 父亲要求):
 *  - **单独一层**:不复用字幕 View,弹幕与普通字幕互不干扰;
 *  - **不抖**:每帧用播放器当前时间重新计算坐标(绝对时间 → 位置),
 *    不做"每帧位移累加",因此掉帧只会跳一下、不会越走越偏;
 *  - **与 vsync 对齐**:绘制完用 postInvalidateOnAnimation 请求下一帧。
 */
class DanmakuView(context: Context) : View(context) {

    private var track: DanmakuTrack? = null
    private var positionProvider: (() -> Long)? = null
    private var running = false

    /** 弹幕整体缩放倍率(用户在设置里可调,默认 1.0) */
    var userScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /*
     * 视频字幕（父亲 2026-10-06 晚）：画在这块画布最下方居中。
     * 弹幕在上面滚、字幕在底部不动，两者各画各的，互不干扰。
     */
    private var subtitle: String = ""

    /** 绘制日志节流计数（诊断用） */
    private var drawLogTick = 0

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subtitleOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
    }

    /** 播放器报来当前该显示的字幕文字（空串 = 这一帧没有字幕） */
    fun setSubtitle(text: String) {
        if (text == subtitle) return
        subtitle = text
        invalidate()
    }

    fun setTrack(t: DanmakuTrack?) {
        track = t
        invalidate()
    }

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun start() {
        if (!running) {
            running = true
            postInvalidateOnAnimation()
        }
    }

    fun stop() {
        running = false
    }

    /**
     * 滚动弹幕的实际消失时间。
     *
     * 源 ASS 的 \move 终点是固定坐标(如 -810),长弹幕会在还没完全出屏时就消失(画面里"突然不见")。
     * 这里按文字实测宽度推算"完全滚出屏幕"所需时间,取两者较大值 → 弹幕完整地从屏幕外滚入、再完整滚出。
     */
    private fun effectiveEndMs(item: DanmakuItem, scale: Float): Long {
        if (!item.isMove) return item.endMs
        val spanMs = (item.endMs - item.startMs).coerceAtLeast(1L)
        val dx = item.x1 - item.x2
        if (dx <= 0f) return item.endMs
        fillPaint.textSize = max(10f, item.style.fontSize * scale)
        val textWidthInPlayRes = fillPaint.measureText(item.text) / scale.coerceAtLeast(0.01f)
        val speed = dx / spanMs                       // PlayRes 单位 / 毫秒
        if (speed <= 0f) return item.endMs
        val fullExitMs = ((item.x1 + textWidthInPlayRes) / speed).toLong()
        return item.startMs + max(spanMs, fullExitMs)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = track
        // 诊断（父亲 2026-10-06 晚：勾了弹幕不显示）：确认这块画布到底有没有在画
        if ((drawLogTick++ % 120) == 0) {
            android.util.Log.i(
                "B0BEmbyVR",
                "弹幕层绘制：轨道 ${t?.items?.size ?: 0} 条 画布 ${width}x${height}" +
                    " 运行中=$running 字幕=${if (subtitle.isEmpty()) "无" else "有"}",
            )
        }
        if (t != null && t.items.isNotEmpty()) {
            val nowMs = positionProvider?.invoke() ?: 0L
            // 弹幕画布(ASS 的 PlayRes) → 控件宽度的缩放
            val scale = if (t.playResX > 0f) (width / t.playResX) * userScale else userScale
            for (item in t.items) {
                if (item.startMs > nowMs) break          // 已按开始时间排序,后面都还没到
                if (effectiveEndMs(item, scale) < nowMs) continue   // 已完全滚出屏幕
                drawItem(canvas, item, nowMs, scale)
            }
        }
        // 字幕不依赖弹幕轨：这一集没有弹幕轨时，字幕照样要显示
        drawSubtitle(canvas)
        if (running) postInvalidateOnAnimation()
    }

    /**
     * 画视频字幕：最下方居中，多行时往上叠，带黑描边保证任何画面上都看得清。
     */
    private fun drawSubtitle(canvas: Canvas) {
        if (subtitle.isEmpty() || height <= 0) return
        val size = max(12f, height * 0.05f)
        subtitlePaint.textSize = size
        subtitleOutline.textSize = size
        subtitleOutline.strokeWidth = max(2f, size * 0.09f)
        val lines = subtitle.split("\n")
        var y = height - height * 0.07f
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (line.isNotEmpty()) {
                canvas.drawText(line, width / 2f, y, subtitleOutline)
                canvas.drawText(line, width / 2f, y, subtitlePaint)
            }
            y -= size * 1.25f
        }
    }

    private fun drawItem(canvas: Canvas, item: DanmakuItem, nowMs: Long, scale: Float) {
        val span = (item.endMs - item.startMs).coerceAtLeast(1L)
        val progress = ((nowMs - item.startMs).toFloat() / span).coerceIn(0f, 1f)

        val x: Float
        val y: Float
        if (item.isMove) {
            x = (item.x1 + (item.x2 - item.x1) * progress) * scale
            y = (item.y1 + (item.y2 - item.y1) * progress) * scale
        } else {
            x = item.posX * scale
            y = item.posY * scale
        }
        if (x > width || x < -width) return          // 完全在画面外

        val textSize = max(10f, item.style.fontSize * scale)
        fillPaint.textSize = textSize
        outlinePaint.textSize = textSize
        outlinePaint.strokeWidth = max(1.5f, item.style.outline * scale)
        fillPaint.color = item.style.primaryColor
        outlinePaint.color = if (item.style.outlineColor == Color.TRANSPARENT) {
            Color.argb(160, 0, 0, 0)
        } else {
            item.style.outlineColor
        }

        // Alignment 7(左上)语义:给出的坐标是文本左上角;drawText 需要基线
        val baseline = y + textSize
        canvas.drawText(item.text, x, baseline, outlinePaint)
        canvas.drawText(item.text, x, baseline, fillPaint)
    }
}
