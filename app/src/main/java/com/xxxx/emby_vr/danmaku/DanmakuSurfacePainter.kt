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
     * 每帧在画布左上角画这张图：宽占画布 7.3%、距左 2.5%、距顶 2.8%（与电视版比例一致），
     * 高度按图片自身比例自适应。弹幕层是实测能正常显示的独立层，logo 借它的画布，
     * 不新增合成层数。
     */
    var logoBitmapProvider: (() -> android.graphics.Bitmap?)? = null
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
                     * logo 最后画（父亲 2026-10-07 01:48：logo 不要被弹幕挡住）：
                     * 压在弹幕上面 —— 弹幕从它的透明底穿过，文字部分压住弹幕。
                     */
                    logoBitmapProvider?.invoke()?.let { logo ->
                        val lw = (widthPx * 0.073f).toInt()
                        val lh = (lw.toFloat() * logo.height / logo.width).toInt()
                        val lx = (widthPx * 0.025f).toInt()
                        val ly = (heightPx * 0.028f).toInt()
                        canvas.drawBitmap(
                            logo, null,
                            android.graphics.Rect(lx, ly, lx + lw, ly + lh), null,
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
    }
}
