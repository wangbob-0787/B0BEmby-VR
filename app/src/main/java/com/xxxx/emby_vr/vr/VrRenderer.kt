package com.xxxx.emby_vr.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.xxxx.emby_vr.scene.Quad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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

    private lateinit var screenQuad: Quad
    lateinit var posterQuads: List<Quad>
    private lateinit var barQuad: Quad

    private var viewW = 1
    private var viewH = 1

    // ---- 纹理资源（GL 线程上创建）----
    private var screenTex = 0
    private var posterTexs = IntArray(POSTER_COUNT)
    private var whiteTex = 0

    // 屏幕纹理内容缓存（GL 线程）
    private var screenBitmap: Bitmap? = null
    private var pendingScreenText: String = ""

    // 海报选中索引（-1 = 无）；焦点视觉用绿色描边渲染
    @Volatile
    var focusedPosterIndex: Int = -1

    /** 模拟射线最近一次在屏幕平面上的 x/y（米），用于算出应聚焦哪张海报 */
    @Volatile
    var simRayX: Float = 0f
    @Volatile
    var simRayY: Float = 0f

    /**
     * 更新屏幕中央大字。
     * 从主线程（输入回调）调用；数据写入后由 GL 线程在下一帧检测并重建位图。
     */
    fun setScreenText(text: String) {
        synchronized(this) { pendingScreenText = text }
    }

    /**
     * 根据模拟射线位置更新焦点海报索引。
     * 海报一行排开，每张占 x 区间 [cardLeft_i, cardLeft_i + posterW]，
     * 射线 x/y 命中哪张的纵向范围就聚焦哪张。
     */
    fun updateFocusFromSimRay() {
        val totalW = posterCount * posterW + (posterCount - 1) * posterGap
        val startX = -totalW / 2f
        for (i in posterQuads.indices) {
            val cardLeft = startX + i * (posterW + posterGap)
            val cardTop = posterY + posterH / 2f
            val cardBottom = posterY - posterH / 2f
            if (simRayX in (cardLeft - posterGap / 2f)..(cardLeft + posterW + posterGap / 2f) &&
                simRayY in cardBottom..cardTop
            ) {
                if (focusedPosterIndex != i) {
                    focusedPosterIndex = i
                }
                return
            }
        }
        // 未命中任何卡片 → 清除焦点
        focusedPosterIndex = -1
    }

    // 虚拟屏参数（米）
    private val screenWidth = 3.0f
    private val screenHeight = screenWidth * 9f / 16f
    private val screenDistance = 3.0f

    // 海报墙参数：一行 10 张，摆在屏幕正前方（z 略浅于屏幕，避免遮挡），高度贴近屏幕下缘
    private val posterCount = POSTER_COUNT
    private val posterW = 0.30f
    private val posterH = posterW * 3f / 2f   // 2:3，与 TV 版海报卡一致
    private val posterGap = 0.06f
    private val posterY = -screenHeight / 2f - 0.35f
    private val posterZ = -screenDistance + 0.25f

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
        barQuad = Quad(screenWidth, 0.22f)
        posterQuads = List(posterCount) { Quad(posterW, posterH, textured = false) }

        createPlaceholderTexture()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.03f, 0.03f, 0.04f, 1f)   // 近黑空间（与 TV 版底色一致）
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged ${width}x$height")
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    /** 占位资源：在 GL 线程一次性建好（GLSurfaceView 在 onSurfaceCreated 已绑定 EGL 上下文） */
    private fun createPlaceholderTexture() {
        // 屏幕位图：深色背景 + 大标题 + 网格参考（验证贴图方向/比例）
        val screenBmp = buildScreenBitmap(1280, 720, "B0BEmby VR")
        screenBitmap = screenBmp
        screenTex = newTexture()
        bindTexture(screenTex, screenBmp)

        // 海报占位卡：每张生成一张不同渐变色 + 序号
        for (i in posterTexs.indices) {
            val bmp = buildPosterBitmap(i, 256, 384)
            posterTexs[i] = newTexture()
            bindTexture(posterTexs[i], bmp)
            bmp.recycle()
        }

        whiteTex = newTexture()
        val whiteBmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        whiteBmp.eraseColor(Color.WHITE)
        bindTexture(whiteTex, whiteBmp)
        whiteBmp.recycle()

        Log.i(TAG, "占位纹理就绪: 屏幕 + ${posterTexs.size} 张海报")
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
        GLES30.glUseProgram(program)

        // 主线程更新了屏幕文字 → 重建位图与纹理
        val want = synchronized(this) { pendingScreenText }
        if (want != lastRenderedScreenText) {
            lastRenderedScreenText = want
            val bmp = buildScreenBitmap(1280, 720, want)
            screenBitmap?.recycle()
            screenBitmap = bmp
            bindTexture(screenTex, bmp)
        }

        // 模拟射线焦点：读取 simRayX/Y 计算应聚焦哪张海报
        updateFocusFromSimRay()

        val aspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        // 观察者在原点，朝 -Z 看；屏幕放在 -Z 方向 3m 处
        val proj = M.perspective(70f, aspect, 0.05f, 100f)
        val view = M.lookAt(
            0f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val vp = M.mul(proj, view)

        // 1) 虚拟屏（贴屏幕纹理；纹理未就绪时退回纯色）
        val hasScreenTex = screenTex != 0
        drawQuad(screenQuad, M.translate(0f, 0f, -screenDistance), vp, if (hasScreenTex) 1f else 0.10f, useTex = hasScreenTex, tex = screenTex)

        // 2) 海报墙：一行排开，选中卡片用绿色描边
        drawPosterWall(vp)

        // 3) 底部控制条底板
        drawQuad(
            barQuad,
            M.translate(0f, -screenHeight / 2f - 0.30f, -screenDistance + 0.01f),
            vp,
            0.25f,
        )
    }

    private var lastRenderedScreenText = ""

    private fun drawPosterWall(vp: FloatArray) {
        val totalW = posterCount * posterW + (posterCount - 1) * posterGap
        for (i in posterQuads.indices) {
            val x = -totalW / 2f + i * (posterW + posterGap) + posterW / 2f
            val model = M.translate(x, posterY, posterZ)
            val focused = i == focusedPosterIndex
            if (focused) {
                // 焦点底色：纯绿 #7ED97A（secondary，与 TV 版焦点背景一致）
                drawQuad(posterQuads[i], model, vp, 0.0f, useTex = true, tex = whiteTex, color = GREEN_FOCUSED)
                // 卡片本身
                drawQuad(posterQuads[i], model, vp, 1f, useTex = true, tex = posterTexs[i])
                // 绿色描边：放大 1.12 倍画一次描边框（P1 用两层 quads 近似）
                drawQuad(posterQuads[i], M.mul(model, M.scale(1.12f, 1.12f)), vp, 0.0f, useTex = true, tex = whiteTex, color = GREEN_OUTLINE)
            } else {
                drawQuad(posterQuads[i], model, vp, 1f, useTex = true, tex = posterTexs[i])
            }
        }
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

    private fun buildScreenBitmap(w: Int, h: Int, title: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(10, 12, 16))
        // 参考网格（验证贴图方向与 16:9 比例）
        val grid = Paint().apply { color = Color.rgb(40, 44, 52); style = Paint.Style.STROKE; strokeWidth = 1f }
        for (i in 1..5) c.drawLine(w * i / 6f, 0f, w * i / 6f, h.toFloat(), grid)
        for (i in 1..3) c.drawLine(0f, h * i / 4f, w.toFloat(), h * i / 4f, grid)
        // 标题
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x4C, 0xD1, 0x37)   // 纯绿 primary，与 TV 版主题一致
            textSize = h * 0.13f
            textAlign = Paint.Align.CENTER
        }
        c.drawText(title, w / 2f, h * 0.42f, tp)
        // 副标题（状态提示）
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(170, 175, 185)
            textSize = h * 0.05f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("平面虚拟屏 · 16:9 · 距离 3m", w / 2f, h * 0.58f, sp)
        // 底部操作提示
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 115, 125)
            textSize = h * 0.035f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("方向键/摇杆：浏览 · A/扳机：确认 · B：返回", w / 2f, h * 0.9f, hp)
        return bmp
    }

    private fun buildPosterBitmap(index: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 占位卡：深色底 + 不同色调区分序号（P2 替换为 Emby 真实海报 URL）
        val hue = (index * 36f % 360f)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.25f, 0.22f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 圆角框线：同色系亮一档，演示 TV 版海报卡的圆角视觉
        // 注意：Color.HSVToColor 只有 (float[]) 与 (float[], float) 两种重载，
        // 后者第二参是 alpha（0..1），不是 S/V 分量。
        val borderColor = Color.HSVToColor(floatArrayOf(hue, 0.4f, 0.5f))
        paint.color = Color.argb(153, Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        c.drawRoundRect(8f, 8f, w - 8f, h - 8f, 12f, 12f, paint)
        // 序号
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = h * 0.32f
        paint.textAlign = Paint.Align.CENTER
        val label = String.format(Locale.US, "#%d", index + 1)
        c.drawText(label, w / 2f, h * 0.58f, paint)
        return bmp
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

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "程序链接失败: ${GLES30.glGetProgramInfoLog(p)}")
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
        private const val POSTER_COUNT = 10

        /** 焦点底色：纯绿 #7ED97A（TV 版 secondary，用于焦点卡底色） */
        private val GREEN_FOCUSED = floatArrayOf(0.494f, 0.851f, 0.478f, 1f)

        /** 焦点描边：纯绿 #4CD137（TV 版 primary，描边比底色亮一档） */
        private val GREEN_OUTLINE = floatArrayOf(0.298f, 0.820f, 0.216f, 1f)

        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            uniform mat4 uMVP;
            out vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vUV;
            uniform sampler2D uTex;
            uniform int uUseTex;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                if (uUseTex == 1) {
                    fragColor = texture2D(uTex, vUV) * uColor;
                } else {
                    fragColor = uColor;
                }
            }
        """
    }
}
