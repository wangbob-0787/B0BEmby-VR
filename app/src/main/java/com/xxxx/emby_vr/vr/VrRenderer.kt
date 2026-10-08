package com.xxxx.emby_vr.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.xxxx.emby_vr.scene.Quad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * VR 场景渲染器（P1：最小可用平面虚拟屏）。
 *
 * 场景构成（父亲 2026-10-03 定的「最小可用平面虚拟屏」）：
 *   1. 正前方 3m 处一块 16:9 虚拟屏（宽 3.0m），画面/占位色贴上去
 *   2. 屏幕前方一排海报（P2 接真实海报；P1 先用程序生成的占位卡渲染，先验证贴图链路）
 *   3. 底部一条控制条底板
 *
 * 渲染用 OpenGL ES 3.0。P1 单眼渲染；P3 接播放、P5 接立体视图矩阵时再扩展。
 *
 * 视觉规范与 TV 版 B0BEmby 对齐（2026-10-03 主题色定稿）：
 *   - 底色：近黑 #08080B（TV 版暗色主题 background 0.03,0.03,0.04）
 *   - 强调：纯绿 #4CD137（primary）/ #7ED97A（secondary，焦点底色）
 *   - 海报卡圆角 8dp 视觉 → 这里用圆角矩形裁剪实现
 */
class VrRenderer(
    private val context: Context,
    private val vrSession: VrSession,
) : GLSurfaceView.Renderer {

    private var program = 0
    private var posHandle = 0
    private var uvHandle = 0
    private var mvpHandle = 0
    private var texHandle = 0
    private var useTexHandle = 0
    private var colorHandle = 0

    // ---- 视频播放（P3）：ExoPlayer 输出 → SurfaceTexture(OES) → 贴到虚拟屏 ----
    private var videoProgram = 0
    private var videoUvHandle = 0
    private var videoMvpHandle = 0
    private var videoTexHandle = 0

    /** GL 外部纹理（GL_TEXTURE_EXTERNAL_OES），专供视频帧采样 */
    private var videoTex = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null

    /**
     * 播放器用的 Surface。
     *
     * 在 onSurfaceCreated 里创建（SurfaceTexture 需要在 GL 线程绑定纹理），
     * 主线程拿去交给 ExoPlayer；@Volatile 保证跨线程可见。
     */
    @Volatile
    var videoSurface: android.view.Surface? = null
        private set

    /*
     * 面板 / 播放画面 / 控制条这三张纹理，2026-10-05 晚修后统一由**原生渲染线程**
     * 在自己的 GL 上下文里创建（openxr_renderer.cpp 的 createOesSources），
     * 再经 VrNative.TextureSink 推给界面层。本类不再创建面板/控制条纹理：
     * 跨 GL 上下文用纹理会拿到同编号的另一张，三块屏互相串画面就是这么来的。
     */

    /**
     * VR 原生播放画面（2026-10-05）：输出 surface 交给 ExoPlayer。
     *
     * 与播放画面一样由渲染线程提供（见 [VrNative.TextureSink]），
     * 播放时原生渲染线程贴这张纹理、收起面板；非 VR 模式下为 null，
     * 播放继续走老的 2D 画面管线（videoSurface）。
     */
    @Volatile
    var vrVideoSurface: android.view.Surface? = null
        private set
    private var vrVideoSurfaceTexture: android.graphics.SurfaceTexture? = null

    /**
     * 画面缓冲尺寸（父亲 2026-10-08）。
     *
     * 这块纹理的缓冲尺寸**从来没有人设置过**：硬解时解码器会自己按视频尺寸设，
     * 所以一直没暴露问题；换成内核自己渲染（杜比视界 Profile 5）后，内核按默认
     * 尺寸画，屏幕上就成了整屏拉伸的纯色块（父亲实测：一会灰、一会粉红、一会蓝色）。
     * 起播前由播放侧按片源尺寸设一次。
     */
    private var pendingVideoW = 0
    private var pendingVideoH = 0

    /** 起播前设画面缓冲尺寸（宽高来自片源探测结果） */
    fun setVideoBufferSize(w: Int, h: Int) {
        if (w < 64 || h < 64) return
        pendingVideoW = w
        pendingVideoH = h
        runCatching { vrVideoSurfaceTexture?.setDefaultBufferSize(w, h) }
        Log.i(TAG, "画面缓冲尺寸 → ${w}x$h")
    }

    /** 最近一帧的虚拟屏布局（面板像素换算用，见 panelPixelAt） */
    private var lastScreenCenterY = 0f
    private var lastScreenHalfH = 0f
    private var lastScreenHalfW = 0f

    /** 释放视频管线（Activity onDestroy 时调，GL 资源仍由 GL 线程上下文管理） */
    fun releaseVideoPipeline() {
        runCatching { videoSurface?.release() }
        videoSurface = null
        runCatching { vrVideoSurface?.release() }
        vrVideoSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        videoActive = false
    }

    /** 是否正在播放视频：true 时虚拟屏改采样视频纹理 */
    @Volatile
    var videoActive = false

    private lateinit var screenQuad: Quad

    private var viewW = 1
    private var viewH = 1

    // ---- 纹理资源（GL 线程上创建）----

    // 视频状态条（HUD）纹理与几何
    private var hudTex = 0
    private lateinit var hudQuad: Quad
    private var lastRenderedHudText: String = ""

    // ---- 视频状态条（HUD）----
    //
    // 播放中屏幕显示的是视频画面，别的状态提示会被盖住 —— 父亲反馈
    // 「左右快退快进没有反馈 / 返回没反应」，实际可能执行了但看不见。
    // HUD 独立成一条贴在视频画面上方（后画、z 靠前），显示最近收到的动作、
    // 播放位置、完整错误，作为播放期唯一的观测手段。

    /** HUD 文字（主线程写，GL 线程检测变化重建纹理） */
    private var pendingHudText: String = ""

    /** 更新视频状态条文字 */
    fun setHudText(text: String) {
        synchronized(this) { pendingHudText = text }
    }

    // 基准几何：这些 Quad 的绝对尺寸不重要（渲染时一律缩放到视口），
    // 只用来定义宽高比 —— 屏幕 16:9（与 TV 版一致）。
    private val screenWidth = 16f
    private val screenHeight = 9f

    /** 屏幕 16:9 的半宽/半高（供缩放换算用） */
    private val halfWOfScreen = screenWidth / 2f
    private val halfHOfScreen = screenHeight / 2f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "onSurfaceCreated")
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        posHandle = GLES30.glGetAttribLocation(program, "aPos")
        uvHandle = GLES30.glGetAttribLocation(program, "aUV")
        mvpHandle = GLES30.glGetUniformLocation(program, "uMVP")
        texHandle = GLES30.glGetUniformLocation(program, "uTex")
        useTexHandle = GLES30.glGetUniformLocation(program, "uUseTex")
        colorHandle = GLES30.glGetUniformLocation(program, "uColor")

        screenQuad = Quad(screenWidth, screenHeight)

        createHudResources()
        createVideoPipeline()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.03f, 0.03f, 0.04f, 1f)   // 近黑空间（与 TV 版底色一致）
        // 半透明 HUD / 描边需要 alpha 混合（HUD 底色 argb(160,...)）
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * 视频渲染管线：OES 纹理 + SurfaceTexture + Surface。
     *
     * ExoPlayer 把解码帧写进 Surface → SurfaceTexture 挂在 OES 纹理上 →
     * onDrawFrame 里 updateTexImage 取最新帧 → 用外部纹理着色器贴到虚拟屏。
     *
     * 必须在 GL 线程（onSurfaceCreated）建：SurfaceTexture 要绑定已存在的 GL 纹理。
     */
    private fun createVideoPipeline() {
        videoProgram = buildProgram(VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        videoUvHandle = GLES30.glGetAttribLocation(videoProgram, "aUV")
        videoMvpHandle = GLES30.glGetUniformLocation(videoProgram, "uMVP")
        videoTexHandle = GLES30.glGetUniformLocation(videoProgram, "uTex")

        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        videoTex = texArr[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture?.release()
        surfaceTexture = android.graphics.SurfaceTexture(videoTex)
        videoSurface?.release()
        videoSurface = android.view.Surface(surfaceTexture)
        videoActive = false
        Log.i(TAG, "视频管线就绪: videoTex=$videoTex")
    }

    /** 视频帧到达后取最新帧（播放中每帧调） */
    private fun updateVideoFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage 失败: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged ${width}x$height")
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    /** HUD 资源：GL 线程一次性建好（GLSurfaceView 在 onSurfaceCreated 已绑定 EGL 上下文） */
    private fun createHudResources() {
        hudQuad = Quad(screenWidth, 1f)
        hudTex = newTexture()
        bindTexture(hudTex, buildHudBitmap(""))

        Log.i(TAG, "HUD 资源就绪")
    }

    /**
     * 生成一个 GL 纹理名。
     *
     * 注意：Android 的 `GLES30.glGenTextures()` 在 Java 层只有
     * `glGenTextures(int n, int[] textures, int offset)` 与 `glGenTextures(int, IntBuffer)`
     * 两个重载，**没有无参版本**（无参那是 C 接口的写法），写成
     * `GLES30.glGenTextures()` 会编译不过。
     */
    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 着色器不可用（编译/链接失败）时直接返回：清屏后什么都不画，
        // 日志里已有明确错误，避免用 0 号程序做无效绘制。
        if (!programReady) return

        GLES30.glUseProgram(program)

        // HUD 文字变化 → 重建状态条纹理
        val hudWant = synchronized(this) { pendingHudText }
        if (hudWant != lastRenderedHudText) {
            lastRenderedHudText = hudWant
            val hudBmp = buildHudBitmap(hudWant)
            bindTexture(hudTex, hudBmp)
            hudBmp.recycle()
        }

        val aspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)

        /*
         * 投影方式：**正交，且坐标系直接按视口宽高定义**。
         *
         * 为什么不透视：PICO 上应用画面会被系统贴成空间面板，视口分辨率由
         * 面板决定（实测 4320x2160）。若用固定 FOV 的透视投影，几何尺寸会
         * 随分辨率和宽高比漂移 —— 3m 外一块 3m 宽的屏在 70° FOV 下只占视野
         * 一小块，四周大片空黑，这正是 build-6/build-7 「看起来是黑屏」的
         * 直接观感原因。
         *
         * 改用「视口即坐标系」的正交投影后：屏幕宽度恒等于视口宽度的 96%，
         * 无论面板多少分辨率、什么宽高比，画面都铺满且比例正确。
         * 坐标单位仍是「半高 = 1」的归一化尺寸。
         */
        val vp = M.ortho(-aspect, aspect, -1f, 1f, -10f, 10f)

        /*
         * 布局（归一化坐标，纵向可用范围就是 [-1, 1]，**超出即不可见**）。
         *
         * 父亲 2026-10-04 定：删掉海报墙后，空间全部让给虚拟屏 ——
         * **虚拟屏占满整个视口**（半高 = 1、中心在 0），不留下半部分空白。
         * 屏幕本身保持 16:9 从高度反推宽度：PICO 面板视口是 1600x900（16:9），
         * 半宽 = 16/9 恰好铺满整个视口宽度。
         */
        val screenHalfH = 1f                // 半高 = 1 → 占满视口全高
        val screenHalfW = screenHalfH * 16f / 9f
        val screenCenterY = 0f              // 垂直居中

        // 记录布局：面板像素换算（panelPixelAt）要用，保证「看到的」和「点到的」一致
        lastScreenCenterY = screenCenterY
        lastScreenHalfH = screenHalfH
        lastScreenHalfW = screenHalfW

        // 1) 虚拟屏：播放中采样视频帧，否则显示状态文字
        val screenModel = M.mul(
            M.translate(0f, screenCenterY, 0f),
            M.scale(screenHalfW / halfWOfScreen, screenHalfH / halfHOfScreen, 1f),
        )
        /*
         * 面板画面（复用的电视版界面）现在由 VR 渲染线程自己贴（2026-10-05）：
         * 本渲染器的纹理与 VR 线程的上下文不互通，这里不再参与面板显示。
         * 2D 面板模式下 PICO 直接把 Presentation 显示出来，不经过这里。
         */
        if (videoActive) {
            updateVideoFrame()
            drawOesQuad(screenQuad, screenModel, vp, videoTex)
        } else {
            drawQuad(screenQuad, screenModel, vp, 0.10f)
        }

        // 1.5) 视频状态条（HUD）：贴在视频画面上层（z 更靠前，越过深度测试）
        val hudTextNow = lastRenderedHudText
        if (hudTextNow.isNotEmpty()) {
            val hudHalfW = screenHalfW * 0.82f
            val hudHalfH = hudHalfW / (screenWidth / 1f)   // hudQuad 是 16:1 比例
            val hudModel = M.mul(
                M.translate(0f, screenCenterY - screenHalfH + 0.14f, 0.01f),
                M.scale(hudHalfW / (hudQuad.width / 2f), hudHalfH / (hudQuad.height / 2f), 1f),
            )
            drawQuad(hudQuad, hudModel, vp, 1f, useTex = true, tex = hudTex)
        }

        // 首帧日志：实机排查「画面到底出来没有」时，这行是最直接的证据。
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            val err = GLES30.glGetError()
            Log.i(
                TAG,
                "首帧已渲染: 视口=${viewW}x$viewH aspect=$aspect " +
                    "屏幕半宽=$screenHalfW 屏幕半高=$screenHalfH glError=$err",
            )
        }
    }

    /**
     * VR 播放画面（2026-10-05 晚修后改由原生推送）。
     *
     * 纹理不再在这里创建：原生渲染线程在自己的 GL 上下文里建好三张画面，
     * 通过 VrNative.TextureSink.onVideoTexture 推过来。这里只把 SurfaceTexture
     * 包成 Surface，交给 ExoPlayer 输出视频帧。
     */
    fun setVrVideoSurface(st: android.graphics.SurfaceTexture) {
        vrVideoSurfaceTexture?.release()
        vrVideoSurfaceTexture = st
        /*
         * 缓冲尺寸要在播放器连上来之前设好（见 pendingVideoW 的注释）：
         * 硬解的解码器会自己设，内核不会 —— 不设就是默认尺寸，画面变纯色块。
         */
        if (pendingVideoW > 0) {
            runCatching { st.setDefaultBufferSize(pendingVideoW, pendingVideoH) }
        }
        vrVideoSurface?.release()
        vrVideoSurface = android.view.Surface(st)
        Log.i(TAG, "VR 播放画面就绪（纹理由渲染线程创建）")
    }

    /**
     * 视口坐标 → 面板像素坐标。
     *
     * 入参是 GL 归一化坐标（x 右为正、y **上**为正，范围约
     * [-aspect, aspect] × [-1, 1]），与 onDrawFrame 里的正交投影同一坐标系；
     * 返回 [x, y] 面板像素（0..PANEL_W, 0..PANEL_H）。
     *
     * 光标不在虚拟屏范围内返回 null —— 调用方据此把事件留给别的层
     * （父亲定的分派规则：光标在播放屏幕/选片墙上才有动作，其他位置无反应）。
     */
    fun panelPixelAt(nx: Float, ny: Float): FloatArray? {
        val halfW = lastScreenHalfW
        val halfH = lastScreenHalfH
        if (halfW <= 0f || halfH <= 0f) return null
        val left = -halfW
        val right = halfW
        val bottom = lastScreenCenterY - halfH
        val top = lastScreenCenterY + halfH
        if (nx < left || nx > right || ny < bottom || ny > top) return null
        val u = (nx - left) / (right - left)
        val v = (top - ny) / (top - bottom)
        return floatArrayOf(u * PANEL_W, v * PANEL_H)
    }

    private var loggedFirstFrame = false

    /**
     * 用视频程序画一个四边形（采样 OES 外部纹理）。
     *
     * 与 [drawQuad] 分开是因为它绑定的是另一个 program 和纹理目标，
     * 共用会导致普通纹理采样器读 OES 纹理（黑帧）。
     */
    private fun drawOesQuad(quad: Quad, model: FloatArray, viewProj: FloatArray, tex: Int) {
        if (videoProgram == 0 || tex == 0) return
        val mvp = M.mul(viewProj, model)
        GLES30.glUseProgram(videoProgram)
        GLES30.glUniformMatrix4fv(videoMvpHandle, 1, false, mvp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES30.glUniform1i(videoTexHandle, 0)
        quad.bindAttribs(posHandle, videoUvHandle)
        quad.draw()
        // 恢复默认程序，避免后续 drawQuad 拿错 program 状态
        GLES30.glUseProgram(program)
    }

    private fun drawQuad(
        quad: Quad,
        model: FloatArray,
        viewProj: FloatArray,
        brightness: Float,
        useTex: Boolean = false,
        tex: Int = 0,
        color: FloatArray? = null,
    ) {
        val mvp = M.mul(viewProj, model)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES30.glUniform1i(useTexHandle, if (useTex) 1 else 0)
        if (useTex) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        }
        val c = color ?: floatArrayOf(brightness, brightness * 1.02f, brightness, 1f)
        GLES30.glUniform4f(colorHandle, c[0], c[1], c[2], c[3])
        quad.bindAttribs(posHandle, uvHandle)
        quad.draw()
    }

    // ---- 位图构建（主线程或 GL 线程皆可，只做 CPU 绘图）----

    /**
     * 构建视频状态条（HUD）位图：半透明黑底 + 单行文字（超长自动缩小）。
     *
     * 播放期唯一可见的状态输出 —— 屏幕大字被视频盖住，HUD 贴在视频上层。
     */
    private fun buildHudBitmap(text: String): Bitmap {
        val w = 1024
        val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(160, 8, 10, 14))
        if (text.isNotEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0x7E, 0xD9, 0x7A)
                textAlign = Paint.Align.CENTER
            }
            p.textSize = h * 0.62f
            if (p.measureText(text) > w * 0.95f) {
                p.textSize = h * 0.36f
            }
            var y = h * 0.66f
            val lines = if (p.measureText(text) <= w * 0.95f) listOf(text)
            else wrapText(text, p, w * 0.95f, maxLines = 1)
            for (line in lines) c.drawText(line, w / 2f, y, p)
        }
        return bmp
    }

    /**
     * 按像素宽度把文本折成多行。
     *
     * 中英文混排：逐字符累加测量宽度，超宽就换行。
     * 不做「按词换行」是因为中文本没有空格，按词切会切不开。
     */
    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxLines: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (paint.measureText(sb.toString()) > maxWidth) {
                // 超宽：把最后一个字符留到下一行
                sb.deleteCharAt(sb.length - 1)
                if (sb.isNotEmpty()) out.add(sb.toString())
                sb.setLength(0)
                sb.append(ch)
                if (out.size == maxLines) break
            }
        }
        if (out.size < maxLines && sb.isNotEmpty()) out.add(sb.toString())
        // 超出 maxLines：把最后一行替换为省略号结尾
        if (out.size >= maxLines && sb.isNotEmpty()) {
            var last = out[maxLines - 1]
            while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) {
                last = last.dropLast(1)
            }
            out[maxLines - 1] = "$last…"
        }
        return out.ifEmpty { listOf(text) }
    }

    /** 位图 → GL 纹理（GL 线程调用） */
    private fun bindTexture(texId: Int, bmp: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    // ---- 着色器 ----

    /** 着色器程序是否可用；false 时 onDrawFrame 只清屏，不画几何 */
    private var programReady = false

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        programReady = status[0] != 0
        if (!programReady) {
            // 失败时把程序置 0：GLES 规定 glUseProgram(0) 是「无程序」，
            // 比拿着半成品程序继续调用更安全，也避免静默黑屏查不出原因。
            Log.e(TAG, "程序链接失败: ${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return 0
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "着色器编译失败: ${GLES30.glGetShaderInfoLog(s)}\n源码:\n$src")
        }
        return s
    }

    companion object {
        private const val TAG = "B0BEmbyVR"

        /** 面板像素尺寸（与 PanelLayer 保持一致：1080p 电视版布局直接可用） */
        private const val PANEL_W = 1920f
        private const val PANEL_H = 1080f

        /**
         * 顶点着色器。
         *
         * 注意：必须 `.trimIndent()` —— Kotlin 三引号字符串会给每行带上源码缩进，
         * `#version` 指令前只要有空格，GLSL 编译器就不认它（版本声明必须是
         * 文件第一条非空白内容），于是按 ES 1.0 解析，`vec4(aPos, 1.0)` 会报
         * `'constructor' : too many arguments`。
         */
        private val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            uniform mat4 uMVP;
            out vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        /**
         * 片元着色器。
         *
         * `texture2D` 是 GLSL ES 1.0 的函数，在 `#version 300 es` 里已移除，
         * 必须用 `texture`。
         */
        /**
         * 视频片元着色器（GL 外部纹理）。
         *
         * 视频帧走 GL_TEXTURE_EXTERNAL_OES，采样必须用 samplerExternalOES，
         * 普通 sampler2D 采不到（拿到的是黑帧或报错）。extension 指令要在
         * #version 之后、源码首条语句之前。
         */
        private val VIDEO_FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vUV;
            uniform samplerExternalOES uTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUV);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform int uUseTex;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                if (uUseTex == 1) {
                    fragColor = texture(uTex, vUV) * uColor;
                } else {
                    fragColor = uColor;
                }
            }
        """.trimIndent()
    }
}
