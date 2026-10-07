package com.xxxx.emby_vr.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.xxxx.emby_vr.panel.osdTimeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        typeface = Typeface.DEFAULT
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
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
        typeface = Typeface.DEFAULT
    }
    private val subtitleOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
    }

    /** 最近一帧实际画出的弹幕条数（诊断用） */
    var drawnThisFrame: Int = 0
        private set

    // ── 快进快退进度条（2026-10-07 父亲：照电视版 SeekHud 复刻，画在这一层上）──
    //
    // 电视版的这条进度条是**悬在画面底部的浮层**，与控制条互斥。弹幕层正好就是压在
    // 银幕上的一层浮层（层序、位置都对得上），而且这一层是**常开**的：没有弹幕的片子、
    // 弹幕开关关掉时它照样逐帧绘制（片名 logo、字幕、转圈、等待提示都画在这儿）。
    // 所以 HUD 的绘制**与弹幕轨无关** —— 见 onDraw 里它是独立一步，不在 track 分支里。
    @Volatile private var hudVisible = false
    @Volatile private var hudPosMs = 0L
    @Volatile private var hudDurMs = 0L
    @Volatile private var hudBufMs = 0L

    /** 快进快退进度条的显隐与数据（位置 / 总长 / 缓冲），由播放侧驱动 */
    fun setSeekHud(visible: Boolean, posMs: Long, durMs: Long, bufMs: Long) {
        if (visible == hudVisible && posMs == hudPosMs &&
            durMs == hudDurMs && bufMs == hudBufMs
        ) {
            return
        }
        hudVisible = visible
        hudPosMs = posMs
        hudDurMs = durMs
        hudBufMs = bufMs
        invalidate()
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT
    }
    private val hudDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xD0, 0xD0, 0xD0)
        typeface = Typeface.DEFAULT
    }

    /** 轨道色、缓冲段、已播段（电视版实测：深灰轨道 + 白 22% 缓冲 + 绿已播 + 绿竖线游标） */
    private val hudTrackPaint = Paint().apply { color = Color.rgb(0x26, 0x26, 0x26) }
    private val hudBufPaint = Paint().apply { color = Color.argb(0x38, 0xFF, 0xFF, 0xFF) }
    private val hudFillPaint = Paint().apply { color = Color.rgb(0x2F, 0xD5, 0x7C) }
    private val hudCursorPaint = Paint().apply { color = Color.rgb(0x2F, 0xD5, 0x7C) }

    /** 当前轨道的弹幕条数（诊断用） */
    fun trackItemCount(): Int = track?.items?.size ?: 0

    /** 当前没有字幕文字（诊断用） */
    fun subtitleEmpty(): Boolean = subtitle.isEmpty()

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

    /*
     * 弹幕整体速度倍率（父亲 2026-10-07）。
     *
     * 弹幕层贴到银幕上之后观感速度慢了，一条弹幕占轨道的时间变长，后面的排不进
     * 来就被丢掉 —— 表现成"弹幕变少"。这里把每条弹幕的"在屏时长"按倍率压缩
     * （出现时刻不变），速度就提上去了。
     * 1.4 是父亲这轮定的试值。
     */
    private val kSpeedFactor = 1.0f

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
        val spanMs = ((item.endMs - item.startMs) / kSpeedFactor).toLong().coerceAtLeast(1L)
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

    /**
     * 面板给多大就用多大。
     *
     * 自绘 View 的内容尺寸是 0，交给 Compose 的 AndroidView 量的话会被量成 0×0，
     * onDraw 永远不执行 —— 弹幕数据再多也一帧画不出来（父亲 2026-10-06 晚实测）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = track
        // 诊断（父亲 2026-10-06 晚：勾了弹幕不显示）：确认这块画布到底有没有在画
        drawnThisFrame = 0
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
                drawnThisFrame++
            }
        }
        // 字幕不依赖弹幕轨：这一集没有弹幕轨时，字幕照样要显示
        drawSubtitle(canvas)
        /*
         * 快进快退进度条（HUD）：**独立一步**，不看 track ——
         * 没有弹幕的片子、弹幕开关关掉时，这一层照样在画（logo / 字幕都在这儿），
         * 所以 HUD 一定出得来（父亲 2026-10-07 提的边界情况）。
         */
        drawSeekHud(canvas)
        if (running) postInvalidateOnAnimation()
    }

    /**
     * 画快进快退进度条：一行 = 左「已播时间」 + 中间细进度条（轨道 / 缓冲 / 已播 / 绿竖线游标）
     * + 右「-剩余时间 / 结束时刻」。
     *
     * 文案与排布照电视版 `SeekHud`（PlayerControls.kt），尺寸按这块画布换算；
     * 电视版是「悬在画面底部」，这里同样放在画面底部（底边留 14% 高）。
     */
    private fun drawSeekHud(canvas: Canvas) {
        if (!hudVisible || width <= 0 || height <= 0) return
        // 画布高度随影片比例变，字号一律按宽度算（16:9 时与原来等价）
        // 字号放大一档（父亲 2026-10-07：「字号再大一些」，与控制条同一量级）
        val textSize = max(16f, width * 0.032f * 9f / 16f)
        hudTextPaint.textSize = textSize
        hudDimPaint.textSize = textSize

        val padX = width * 0.028f          // 电视版左右各 54dp / 1920 ≈ 2.8%
        val rowCy = height - width * 0.14f * 9f / 16f   // 画面底部往上 14%（按宽度算）
        val barH = max(3f, width * 0.0042f * 9f / 16f)
        val cursorW = max(3f, width * 0.0042f * 9f / 16f)
        val cursorH = width * 0.028f * 9f / 16f

        val remain = (hudDurMs - hudPosMs).coerceAtLeast(0L)
        val leftText = osdTimeText(hudPosMs)
        // 文案与控制条完全一致：左「已播」，右「-剩余 / 总时长」（见 panel/PlayerOsd.kt）
        val rightText = "-" + osdTimeText(remain) + " / " + osdTimeText(hudDurMs)

        val baseline = rowCy + textSize * 0.35f
        canvas.drawText(leftText, padX, baseline, hudTextPaint)
        val rightW = hudDimPaint.measureText(rightText)
        canvas.drawText(rightText, width - padX - rightW, baseline, hudDimPaint)

        val gap = width * 0.006f
        val barLeft = padX + hudTextPaint.measureText(leftText) + gap
        val barRight = width - padX - rightW - gap
        if (barRight <= barLeft) return
        val barW = barRight - barLeft

        canvas.drawRect(barLeft, rowCy - barH / 2f, barRight, rowCy + barH / 2f, hudTrackPaint)
        if (hudDurMs > 0L) {
            val frac = (hudPosMs.toFloat() / hudDurMs.toFloat()).coerceIn(0f, 1f)
            val bufFrac = (hudBufMs.toFloat() / hudDurMs.toFloat()).coerceIn(0f, 1f)
            if (bufFrac > 0f) {
                canvas.drawRect(
                    barLeft, rowCy - barH / 2f,
                    barLeft + barW * bufFrac, rowCy + barH / 2f, hudBufPaint,
                )
            }
            if (frac > 0f) {
                canvas.drawRect(
                    barLeft, rowCy - barH / 2f,
                    barLeft + barW * frac, rowCy + barH / 2f, hudFillPaint,
                )
            }
            val cx = barLeft + barW * frac
            canvas.drawRect(
                cx - cursorW / 2f, rowCy - cursorH / 2f,
                cx + cursorW / 2f, rowCy + cursorH / 2f, hudCursorPaint,
            )
        }
    }

    /**
     * 画视频字幕：最下方居中，多行时往上叠，带黑描边保证任何画面上都看得清。
     */
    private fun drawSubtitle(canvas: Canvas) {
        if (subtitle.isEmpty() || height <= 0) return
        val size = max(12f, width * 0.05f * 9f / 16f)
        subtitlePaint.textSize = size
        subtitleOutline.textSize = size
        subtitleOutline.strokeWidth = max(2f, size * 0.09f)
        val lines = subtitle.split("\n")
        /*
         * 快进快退进度条也在画面底部，两条会撞在一起（多行字幕时尤其明显）：
         * HUD 显示期间把字幕整体上抬一档，让出底部那条。
         */
        var y = height - width * 0.07f * 9f / 16f -
            (if (hudVisible) width * 0.085f * 9f / 16f else 0f)
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
        val span = ((item.endMs - item.startMs) / kSpeedFactor).toLong().coerceAtLeast(1L)
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
