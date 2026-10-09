package com.xxxx.emby_vr.danmaku

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.View

/**
 * 把弹幕直接画到原生给的那张纹理上。
 *
 * 背景（2026-10-06 父亲实测）：
 * 弹幕原来走「虚拟显示器 + 界面容器」那条路 —— 面板建起来了、纹理也挂上了，
 * 但 DanmakuView 的 onDraw 一次都没执行（日志里「弹幕层绘制」零次）。
 * 原因是那一层容器要把自绘视图量出尺寸，而它的内容尺寸是 0，量出来就是 0×0。
 *
 * 现在换一条更直接的路：自己拿 Surface 开画布，每帧调一次自绘层。
 * 不经过窗口系统、不经过虚拟显示器，尺寸由我们自己定死。
 *
 * 注意：这块画布是**透明背景**，弹幕文字之外的区域必须保持全透明 ——
 * 原生那边是按源透明度把它叠在视频上的，画了不透明底就会把视频盖住。
 */
class DanmakuSurfacePainter(
    // 提成属性：读面板刷新率要用（2026-10-09，电视版节奏对齐做法的 VR 版）
    private val context: Context,
    private val surfaceTexture: SurfaceTexture,
    private var widthPx: Int,
    private var heightPx: Int,
    positionProvider: () -> Long,
    scale: Float,
) {
    /** 位置提供者（诊断用：把它算出的进度打进日志） */
    private val posProvider = positionProvider

    /**
     * 片名 logo 位图的提供者（父亲 2026-10-07 01:47：把 logo 从视频层分离出来试试）。
     *
     * 每帧在画布**右下角**画这张图（父亲 2026-10-07 02:11 定：挪到右下角、屏边距 8%、
     * 尺寸翻倍），高度按图片自身比例自适应，底边以图片下缘对齐。
     *
     * 画布坐标 → 屏幕坐标的换算：这层画布贴在「屏幕的 [DANMAKU_LAYER_SCALE] 倍、
     * 居中、略靠前」的合成层上（native 侧 kDanmakuScale = 0.92），所以画布边缘落在
     * 屏幕 4% 的位置，换算公式 `屏幕比例 = 0.04 + 画布比例 × 0.92`。
     * 反推自「屏右边距 / 底边距 = 8%」：画布右/下缘 ≈ 0.9565，宽 ≈ 画布宽 14.6%
     * （屏幕占比 13.43%，即 4K 屏下 516px 宽，正好是原来 258px 的两倍）。
     */
    var logoBitmapProvider: (() -> android.graphics.Bitmap?)? = null

    /**
     * 换片 / 首播等待期的提示文字（父亲 2026-10-07：「即将播放：片名」）。
     *
     * 非空时画在银幕中部偏下。这段时间弹幕内容与片名 logo 都还没放出来
     * （它们等第一帧才亮），银幕上就只有转圈 + 这行字。
     */
    var hintProvider: (() -> String?)? = null

    /**
     * 等待期模式（父亲 2026-10-07）：返回非空 = 换片 / 首播等待中，字符串就是提示语。
     *
     * 这期间整块画布涂成**不透明黑**当"黑幕"（盖住视频层里残留的上一部画面），
     * 黑幕上只画转圈与提示文字；弹幕 / 字幕 / 片名 logo 一律不画。
     * 黑幕、转圈、提示全部落在这一层里，层序不用动 —— 它本来就压在视频层之上。
     */
    var loadingProvider: (() -> String?)? = null
    /** 自绘层本体。它不在视图树里，只被本类逐帧调用。 */
    val view = DanmakuView(context).apply {
        setPositionProvider(positionProvider)
        userScale = scale
        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, widthPx, heightPx)
        start()
    }

    init {
        /*
         * 必须显式定下缓冲尺寸：SurfaceTexture 的默认缓冲尺寸不可靠，
         * 如果不设，lockCanvas 拿到的画布可能根本不是你想要的尺寸，
         * 弹幕会被画在一块极小的画布上（肉眼等于没有）。
         */
        surfaceTexture.setDefaultBufferSize(widthPx, heightPx)
    }

    /**
     * 换片时按影片比例改画布尺寸（父亲 2026-10-07 定的第二条方案）。
     *
     * 宽度固定 2560，高度 = 2560 ÷ 画面比例 —— 画布比例与银幕一致，
     * 弹幕、片名 logo、字幕、快进快退进度条都不会被拉伸变形。
     * 缓冲尺寸跟着改，原生侧下一帧自己把这块画布重建到同样大小。
     */
    fun resizeTo(w: Int, h: Int) {
        if (w == widthPx && h == heightPx) return
        widthPx = w
        heightPx = h
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        surfaceTexture.setDefaultBufferSize(widthPx, heightPx)
        android.util.Log.i("B0BEmbyVR", "弹幕画布改为 ${widthPx}x$heightPx")
    }

    private val surface = Surface(surfaceTexture)
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var frames = 0L

    /*
     * 每帧间隔：跟着**面板刷新率**走，并且按绝对时间排班（2026-10-09，借鉴电视版弹幕层的做法）。
     *
     * 电视版那边弹幕层是 View，用 postInvalidateOnAnimation 跟 vsync 对齐；VR 这边
     * 弹幕是独立纹理、由自己的线程画，没有 vsync 回调可用。于是借它的**思路**：
     *   ① 间隔 = 1 / 面板刷新率（播放时 72Hz → 13.9ms，界面 90Hz → 11.1ms），
     *      不再写死 11ms —— 画太快是白烧电，画太慢合成器拿到重复画布，
     *      横移弹幕就"停一帧跳一帧"，叠上余晖看着像重影（父亲 2026-10-07 报过）；
     *   ② 用绝对时间排班（nanoTime 累加）而不是"画完 sleep(11ms)"：
     *      后者把绘制耗时也算进去，间隔变成 11ms + 绘制时间，节拍忽长忽短。
     */
    @Volatile
    private var frameGapNs: Long = 11_000_000L

    /** 面板刷新率（Hz）→ 每帧间隔；异常值兜底 90Hz */
    fun setRefreshRate(hz: Float) {
        val h = if (hz.isFinite() && hz >= 30f) hz else 90f
        frameGapNs = (1_000_000_000.0 / h).toLong().coerceIn(8_000_000L, 20_000_000L)
    }

    /** 当前面板刷新率（读不到就按 90Hz） */
    private fun displayHz(): Float = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.refreshRate
    }.getOrDefault(90f)

    fun start() {
        if (running) return
        running = true
        setRefreshRate(displayHz())
        thread = Thread {
            var nextNs = System.nanoTime()
            var rateCheckAt = System.nanoTime()
            while (running) {
                // 每 2 秒回读一次刷新率：播放/界面两档来回切时节奏跟着走
                if (System.nanoTime() - rateCheckAt > 2_000_000_000L) {
                    rateCheckAt = System.nanoTime()
                    setRefreshRate(displayHz())
                }
                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = surface.lockCanvas(null)
                } catch (t: Throwable) {
                    canvas = null
                }
                if (canvas == null) {
                    nextNs += frameGapNs
                    val waitNs = nextNs - System.nanoTime()
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                    else nextNs = System.nanoTime()
                    continue
                }
                try {
                    val loading = loadingProvider?.invoke()
                    if (loading != null) {
                        /*
                         * 等待期（换片 / 首播，父亲 2026-10-07）：
                         * 整块涂黑当"黑幕"—— 视频层里残留的上一部画面被它盖住，
                         * 而这一层本来就压在视频层之上，所以层序不用动。
                         * 黑幕之上只画转圈与「即将播放：片名」。
                         */
                        canvas.drawColor(Color.BLACK)
                        drawLoadingSpinner(canvas)
                        drawHintText(canvas, loading)
                    } else {
                        // 正常播放：透明底 + 弹幕 + 字幕 + 片名 logo
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        view.draw(canvas)
                        hintProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { hint ->
                            drawHintText(canvas, hint)
                        }
                        /*
                         * logo 最后画（父亲 2026-10-07 01:48：logo 不要被弹幕挡住）：
                         * 压在弹幕上面 —— 弹幕从它的透明底穿过，文字部分压住弹幕。
                         */
                        logoBitmapProvider?.invoke()?.let { logo ->
                            /*
                             * 宽高各有一道上限，图按自身比例缩进这个框（Fit，同 TV 版做法）——
                             * 只卡宽度的话，「又高又窄」的 ClearLogo 会竖着占掉半个屏。
                             */
                            val ratio = logo.width.toFloat() / logo.height
                            var lw = widthPx * LOGO_CANVAS_MAX_WIDTH
                            var lh = lw / ratio
                            // 上限按宽度算（画布高度会随影片比例变，按高度算 logo 会忽大忽小）
                            val maxH = widthPx * LOGO_CANVAS_MAX_HEIGHT * CANVAS_H_OVER_W
                            if (lh > maxH) {
                                lh = maxH
                                lw = lh * ratio
                            }
                            val lwInt = lw.toInt()
                            val lhInt = lh.toInt()
                            val lx = (widthPx * LOGO_CANVAS_RIGHT).toInt() - lwInt
                            // 底边距也按宽度算：距底 width×(1-0.9565)×9/16
                            val ly = (heightPx - widthPx * (1f - LOGO_CANVAS_BOTTOM) *
                                CANVAS_H_OVER_W).toInt() - lhInt
                            canvas.drawBitmap(
                                logo, null,
                                android.graphics.Rect(lx, ly, lx + lwInt, ly + lhInt), null,
                            )
                        }
                    }
                    frames++
                    if (frames % 120L == 1L) {
                        android.util.Log.i(
                            TAG,
                            "弹幕画笔：已画 $frames 帧 ${widthPx}x$heightPx" +
                                " 轨道=${view.trackItemCount()} 本帧画了=${view.drawnThisFrame} 条" +
                                " 进度=${posProvider() / 1000}秒" +
                                " 字幕=${if (view.subtitleEmpty()) "无" else "有"}",
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "弹幕画笔：这一帧画失败 ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    runCatching { surface.unlockCanvasAndPost(canvas) }
                }
                nextNs += frameGapNs
                val waitNs = nextNs - System.nanoTime()
                if (waitNs > 0) {
                    Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                } else {
                    // 这一帧画超了（比如刚好在创建/回收纹理）—— 别追债，重新对齐
                    nextNs = System.nanoTime()
                }
            }
        }.also {
            it.name = "danmaku-painter"
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(400) }
        thread = null
        runCatching { surface.release() }
    }

    /** 等待期 / 普通提示用的那行字：居中偏下，带一层淡阴影，压在任何底色上都看得清。 */
    private fun drawHintText(canvas: android.graphics.Canvas, hint: String) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8F8FF.toInt()
            textSize = widthPx * 0.045f * CANVAS_H_OVER_W   // 2560 宽画布 ≈ 65px
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = false
        }
        val shadow = android.graphics.Paint(paint).apply { color = 0xCC000000.toInt() }
        val cx = widthPx / 2f
        // 挂在转圈（画面正中）下方固定距离，按宽度算 —— 画布比例变时两者不会错位
        val cy = heightPx / 2f + widthPx * 0.0675f
        canvas.drawText(hint, cx + 3f, cy + 3f, shadow)
        canvas.drawText(hint, cx, cy, paint)
    }

    /**
     * 等待期的转圈（父亲 2026-10-07）。
     *
     * 与原生 GL 版同一套观感，但那版实测"开关开着却看不见"，所以搬到这一层来画
     * —— 这一层已经验证可见（「即将播放：片名」就画在同一张画布上）：
     *   · 72 段小弧拼环，缺口 45°，顺时针转
     *   · 从尾巴到缺口端：由暗到亮（α 0.45 → 1.0）、由细到粗（0.45× → 1.35×）
     *   · 实心三角箭头落在缺口"前方"端，尖朝顺时针方向
     *
     * 尺寸按银幕换算：银幕宽 3.5 m 对应画布整宽，于是
     * 半径 0.10 m ≈ 画布宽的 1/35、环粗 0.012 m ≈ 1/292。
     */
    private fun drawLoadingSpinner(canvas: android.graphics.Canvas) {
        val pxPerMeter = widthPx / 3.5f
        val radius = 0.10f * pxPerMeter
        val baseThick = 0.012f * pxPerMeter
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val segs = 72
        val gapSegs = 9                       // 缺口 45°
        val sweep = 360f / segs               // 每段 5°
        val drawn = segs - gapSegs
        // Canvas 的正角方向就是顺时针；速度与 GL 版一致：2.6 rad/s ≈ 149°/s
        val baseDeg = ((System.currentTimeMillis() / 1000.0) * 149.0 % 360.0).toFloat()
        val rect = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        for (i in 0 until drawn) {
            val q = i / (drawn - 1f)
            val alpha = 0.45f + 0.55f * q
            stroke.strokeWidth = baseThick * (0.45f + 0.9f * q)
            stroke.color = android.graphics.Color.argb((255 * alpha).toInt(), 33, 219, 240)
            // 每段多画 25%，段与段搭接，看起来是一条连续实线
            canvas.drawArc(rect, baseDeg + i * sweep, sweep * 1.25f, false, stroke)
        }
        // 箭头：中心落在环的缺口端，尖朝顺时针切向（也就是朝缺口）
        val headRad = Math.toRadians((baseDeg + drawn * sweep).toDouble())
        val hx = cx + radius * Math.cos(headRad).toFloat()
        val hy = cy + radius * Math.sin(headRad).toFloat()
        val tx = -Math.sin(headRad).toFloat()   // 顺时针切向
        val ty = Math.cos(headRad).toFloat()
        val nx = Math.cos(headRad).toFloat()    // 法向（底边方向）
        val ny = Math.sin(headRad).toFloat()
        val tipLen = 0.0367f * pxPerMeter
        val backLen = 0.0204f * pxPerMeter
        val halfW = 0.0168f * pxPerMeter
        val path = android.graphics.Path()
        path.moveTo(hx + tx * tipLen, hy + ty * tipLen)
        path.lineTo(hx - tx * backLen + nx * halfW, hy - ty * backLen + ny * halfW)
        path.lineTo(hx - tx * backLen - nx * halfW, hy - ty * backLen - ny * halfW)
        path.close()
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(255, 33, 219, 240)
        }
        canvas.drawPath(path, fill)
    }

    private companion object {
        const val TAG = "B0BEmbyVR"

        /** logo 宽上限 = 画布宽的这个比例（屏幕占比 13.43%，是原来 7.3% 的两倍） */
        /**
         * 16:9 画布下的「高度 ÷ 宽度」。
         *
         * 画布宽度固定 2560、高度随影片比例变，所以凡是"文字大小、边距"
         * 这类必须视觉恒定的量，一律按宽度算（原本按高度算的常量乘上它）。
         */
        const val CANVAS_H_OVER_W = 9f / 16f

        const val LOGO_CANVAS_MAX_WIDTH = 0.146f

        /**
         * logo 高上限 = 画布高的这个比例（屏幕占比 5.6%，对齐 TV 版 60dp/1080 的框高，
         * 除以弹幕层 0.92 的缩放得 0.0609）。宽幅 logo 够不到它，只有又高又窄的图才会顶上来。
         */
        const val LOGO_CANVAS_MAX_HEIGHT = 0.0609f

        /** logo 右缘 / 下缘在画布上的位置：对应屏幕右边距 / 底边距 8% */
        const val LOGO_CANVAS_RIGHT = 0.9565f
        const val LOGO_CANVAS_BOTTOM = 0.9565f
    }
}
