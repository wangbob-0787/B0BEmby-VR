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
    context: Context,
    surfaceTexture: SurfaceTexture,
    private val widthPx: Int,
    private val heightPx: Int,
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

    private val surface = Surface(surfaceTexture)
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var frames = 0L

    /** 每帧间隔（毫秒）：60fps */
    private val frameGapMs = 16L

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = surface.lockCanvas(null)
                } catch (t: Throwable) {
                    canvas = null
                }
                if (canvas == null) {
                    Thread.sleep(frameGapMs)
                    continue
                }
                try {
                    // 先擦成全透明，再画 logo 与弹幕：透明区必须真的是透明的
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    view.draw(canvas)
                    /*
                     * 等待期提示（父亲 2026-10-07：「即将播放：片名」）：
                     * 画在银幕中部偏下，字号按银幕高度取 4.5%（1440 画布 ≈ 65px），
                     * 带一层淡阴影，压在任何底色上都看得清。
                     */
                    hintProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { hint ->
                        val paint = android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG,
                        ).apply {
                            color = 0xFFE8F8FF.toInt()
                            textSize = heightPx * 0.045f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        val shadow = android.graphics.Paint(paint).apply {
                            color = 0xCC000000.toInt()
                        }
                        val cx = widthPx / 2f
                        val cy = heightPx * 0.62f
                        canvas.drawText(hint, cx + 3f, cy + 3f, shadow)
                        canvas.drawText(hint, cx, cy, paint)
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
                        val maxH = heightPx * LOGO_CANVAS_MAX_HEIGHT
                        if (lh > maxH) {
                            lh = maxH
                            lw = lh * ratio
                        }
                        val lwInt = lw.toInt()
                        val lhInt = lh.toInt()
                        val lx = (widthPx * LOGO_CANVAS_RIGHT).toInt() - lwInt
                        val ly = (heightPx * LOGO_CANVAS_BOTTOM).toInt() - lhInt
                        canvas.drawBitmap(
                            logo, null,
                            android.graphics.Rect(lx, ly, lx + lwInt, ly + lhInt), null,
                        )
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
                Thread.sleep(frameGapMs)
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

    private companion object {
        const val TAG = "B0BEmbyVR"

        /** logo 宽上限 = 画布宽的这个比例（屏幕占比 13.43%，是原来 7.3% 的两倍） */
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
