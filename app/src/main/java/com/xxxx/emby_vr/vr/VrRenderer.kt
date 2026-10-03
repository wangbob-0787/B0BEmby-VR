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

    // ---- 真实海报（P2）----

    /** 待上传的海报位图：索引 → 位图。主线程写入，GL 线程消费 */
    private val pendingPosters = HashMap<Int, Bitmap>()

    /** 当前已上传的海报数量（用于日志） */
    @Volatile
    private var uploadedPosterCount = 0

    /**
     * 投递一张真实海报位图。
     *
     * 纹理必须在 GL 线程创建/上传，所以这里只把位图放进待处理队列，
     * 由 onDrawFrame 在 GL 线程取出并绑成纹理。
     *
     * @param index 海报位（0-based，对应海报墙从左到右第几张）
     */
    fun setPosterBitmap(index: Int, bitmap: Bitmap) {
        if (index < 0 || index >= posterCount) {
            // 超出当前海报墙容量的直接丢弃并回收，避免内存泄漏
            bitmap.recycle()
            return
        }
        synchronized(pendingPosters) {
            // 同一位置重复投递时，回收旧的那张（图片可能以大图换小图）
            pendingPosters[index]?.takeIf { it !== bitmap }?.recycle()
            pendingPosters[index] = bitmap
        }
    }

    /**
     * 本次海报墙实际要画的卡片数（数据条数可能少于容量）。
     *
     * 初值直接用伴生常量而不是 `posterCount`：实例属性按声明顺序初始化，
     * 本行在 `posterCount` 声明之前，引用它会编译不过
     * （Kotlin 报 "Variable 'posterCount' must be initialized"）。
     */
    @Volatile
    var activePosterCount: Int = POSTER_COUNT

    /** 清空海报墙（重新加载时调用） */
    fun clearPosters() {
        synchronized(pendingPosters) {
            pendingPosters.values.forEach { it.recycle() }
            pendingPosters.clear()
        }
    }

    /** 在 GL 线程把待处理的海报位图上传成纹理 */
    private fun flushPendingPosters() {
        val ready: List<Pair<Int, Bitmap>>
        synchronized(pendingPosters) {
            if (pendingPosters.isEmpty()) return
            ready = pendingPosters.map { it.key to it.value }
            pendingPosters.clear()
        }
        for ((idx, bmp) in ready) {
            if (posterTexs[idx] == 0) posterTexs[idx] = newTexture()
            bindTexture(posterTexs[idx], bmp)
            bmp.recycle()
        }
        uploadedPosterCount += ready.size
        Log.i(TAG, "海报纹理已更新 ${ready.size} 张（累计 $uploadedPosterCount）")
    }

    /**
     * 根据模拟射线位置更新焦点海报索引。
     *
     * 坐标口径：与渲染一致，用「视口归一化坐标」——半高 = 1，
     * 横向上限 = 宽高比。射线 x 落在哪张海报的横向区间就聚焦哪张；
     * 纵向命中作为附加条件（避免射线飞得太远还锁定卡片）。
     */
    fun updateFocusFromSimRay(screenBottomY: Float) {
        val layout = posterLayout(screenBottomY)
        var hit = -1
        for (i in layout.indices) {
            val (centerX, centerY, halfW, halfH) = layout[i]
            val inX = simRayX in (centerX - halfW - POSTER_HIT_PAD)..(centerX + halfW + POSTER_HIT_PAD)
            val inY = simRayY in (centerY - halfH - POSTER_HIT_PAD)..(centerY + halfH + POSTER_HIT_PAD)
            if (inX && inY) {
                hit = i
                break
            }
        }
        // 纵向没命中时退化为「只看横向」：VR 里手柄经常略微上抬，
        // 严格双轴命中会导致指不到卡片。
        if (hit < 0) {
            for (i in layout.indices) {
                val (centerX, _, halfW, _) = layout[i]
                if (simRayX in (centerX - halfW - POSTER_HIT_PAD)..(centerX + halfW + POSTER_HIT_PAD)) {
                    hit = i
                    break
                }
            }
        }
        if (focusedPosterIndex != hit) focusedPosterIndex = hit
    }

    /**
     * 海报墙布局：返回每张卡的 (centerX, centerY, halfW, halfH)，归一化坐标。
     *
     * 与 drawPosterWall 共用，保证「看到的」和「选中的」一致。
     * 纵向位置由 [screenBottomY]（屏幕下缘）与视口下边界共同决定，
     * 保证整行都落在可视区 [-1, 1] 内 —— 2026-10-03 就是因为没做这个约束，
     * 海报行中心落到 -1.08，整行跑出画面，屏上什么都不显示。
     */
    private fun posterLayout(screenBottomY: Float): List<FloatArray> {
        val aspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        val usableHalfW = 0.96f * aspect
        val gap = POSTER_GAP
        val totalGap = (posterCount - 1) * gap
        val cardHalfW = (usableHalfW * 2f - totalGap) / posterCount / 2f
        val cardHalfH = cardHalfW * 3f / 2f
        val startX = -usableHalfW + cardHalfW
        val y = posterCenterY(screenBottomY, cardHalfH)
        return List(posterCount) { i ->
            floatArrayOf(startX + i * (cardHalfW * 2f + gap), y, cardHalfW, cardHalfH)
        }
    }

    /**
     * 海报行的中心 y：贴在屏幕下缘下方，且整行不越出视口下边界。
     *
     * 若屏幕下缘与视口底部之间放不下完整的一行（卡片过高），
     * 就把海报行**上移**到视口内（宁可压住屏幕一点，也不能整行看不见）。
     */
    private fun posterCenterY(screenBottomY: Float, cardHalfH: Float): Float {
        val ideal = screenBottomY - POSTER_TOP_MARGIN - cardHalfH
        val lowestAllowed = -1f + BOTTOM_MARGIN + cardHalfH   // 底边留安全边距
        return if (ideal < lowestAllowed) lowestAllowed else ideal
    }

    // 基准几何：这些 Quad 的绝对尺寸不重要（渲染时一律缩放到视口），
    // 只用来定义宽高比 —— 屏幕 16:9、海报 2:3（与 TV 版一致）。
    private val screenWidth = 16f
    private val screenHeight = 9f

    /** 屏幕 16:9 的半宽/半高（供缩放换算用） */
    private val halfWOfScreen = screenWidth / 2f
    private val halfHOfScreen = screenHeight / 2f

    /** 视口下边界的安全边距 */
    private val BOTTOM_MARGIN = 0.02f

    // 海报墙参数：一行 10 张，2:3 比例；实际排布在 drawPosterWall 里按视口算
    private val posterCount = POSTER_COUNT
    private val posterW = 2f
    private val posterH = 3f

    /** 海报之间的横向间隙（归一化坐标，半高 = 1） */
    private val POSTER_GAP = 0.024f

    /** 屏幕下缘到海报行顶部的间距（归一化坐标） */
    private val POSTER_TOP_MARGIN = 0.10f

    /** 射线命中判定向外放宽的量（归一化坐标），避免边缘难指中 */
    private val POSTER_HIT_PAD = 0.02f

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

        // 着色器不可用（编译/链接失败）时直接返回：清屏后什么都不画，
        // 日志里已有明确错误，避免用 0 号程序做无效绘制。
        if (!programReady) return

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

        // 主线程投递的真实海报 → 上传成 GL 纹理（必须在 GL 线程做）
        flushPendingPosters()

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
         * 2026-10-03 实机踩坑：改完正交投影后海报一直不显示，原因是布局没按
         * 视口边界算 —— 屏幕中心 y=0.22、半高 0.96，屏幕顶到了 1.18（超出上边
         * 0.18 被切），海报行中心落到 -1.08（整个跑出下边）。视觉上就是
         * 「只有一块被切边的屏，下面什么都没有」。
         *
         * 现在按「上：屏幕 / 下：海报行」分区，两段都完整落在 [-1, 1] 内：
         *   - 屏幕：高占视口 58%，顶边贴 y = 0.96
         *   - 海报：紧贴屏幕下方，底边留 0.06 安全边距
         * 屏幕宽度仍按 16:9 从高度反推，避免拉变形。
         */
        val topMargin = 0.04f              // 屏幕上边距
        val screenHalfH = 0.58f            // 屏幕半高（占视口 58%）
        val screenHalfW = screenHalfH * 16f / 9f
        val screenCenterY = 1f - topMargin - screenHalfH

        // 1) 虚拟屏
        val screenModel = M.mul(
            M.translate(0f, screenCenterY, 0f),
            M.scale(screenHalfW / halfWOfScreen, screenHalfH / halfHOfScreen, 1f),
        )
        val hasScreenTex = screenTex != 0
        drawQuad(
            screenQuad,
            screenModel,
            vp,
            if (hasScreenTex) 1f else 0.10f,
            useTex = hasScreenTex,
            tex = screenTex,
        )

        // 2) 海报墙：一行排开，选中卡片用绿色描边
        val screenBottomY = screenCenterY - screenHalfH
        drawPosterWall(vp, screenBottomY)

        // 3) 底部控制条底板（紧贴海报行下方；空间不足时交给 posterCenterY 上移处理）
        val barHalfW = screenHalfW
        val layoutForBar = posterLayout(screenBottomY)
        val posterRowBottom = layoutForBar.first()[1] - layoutForBar.first()[3]
        val barModel = M.mul(
            M.translate(0f, (posterRowBottom - 0.04f).coerceAtLeast(-1f + BOTTOM_MARGIN), 0f),
            M.scale(barHalfW / (barQuad.width / 2f), 0.03f / (barQuad.height / 2f), 1f),
        )
        drawQuad(barQuad, barModel, vp, 0.25f)

        // 焦点判定要在布局确定之后做（依赖 screenBottomY）
        updateFocusFromSimRay(screenBottomY)

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

    private var loggedFirstFrame = false

    private var lastRenderedScreenText = ""

    private fun drawPosterWall(vp: FloatArray, screenBottomY: Float) {
        // 与 updateFocusFromSimRay 共用 posterLayout()，保证「看到的」和「指到的」一致。
        // 只画有数据的卡片：activePosterCount 由 MainActivity 在加载完成后设置。
        val layout = posterLayout(screenBottomY)
        val visible = activePosterCount.coerceIn(0, posterCount)
        for (i in 0 until visible) {
            val (x, y, halfW, halfH) = layout[i]
            val model = M.mul(
                M.translate(x, y, 0f),
                M.scale(halfW / (posterQuads[i].width / 2f), halfH / (posterQuads[i].height / 2f), 1f),
            )
            val focused = i == focusedPosterIndex
            if (focused) {
                // 焦点底色：纯绿 #7ED97A（secondary，与 TV 版焦点背景一致）
                drawQuad(posterQuads[i], model, vp, 0.0f, useTex = true, tex = whiteTex, color = GREEN_FOCUSED)
                // 卡片本身
                drawQuad(posterQuads[i], model, vp, 1f, useTex = true, tex = posterTexs[i])
                // 绿色描边：放大 1.10 倍画一次描边框（P1 用两层 quads 近似）
                drawQuad(
                    posterQuads[i],
                    M.mul(model, M.scale(1.10f, 1.10f)),
                    vp,
                    0.0f,
                    useTex = true,
                    tex = whiteTex,
                    color = GREEN_OUTLINE,
                )
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

        /*
         * 主标题：**按文字长度自动缩放 + 折行**。
         *
         * 2026-10-03 父亲反馈：错误信息（如「网络错误 [UnknownHostException: ...]」）
         * 用固定 0.13h 字号会超出屏幕宽度被截断，看不到关键内容。
         * 现在长文本自动降字号并在两侧留边距（左右各 6%），一行放不下就折行。
         */
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x4C, 0xD1, 0x37)   // 纯绿 primary，与 TV 版主题一致
            textAlign = Paint.Align.CENTER
        }
        val maxWidth = w * 0.88f                 // 左右各留 6% 边距
        val shortSize = h * 0.13f                // 短文本（片名等）用大字号
        titlePaint.textSize = shortSize
        val lines: List<String> = if (titlePaint.measureText(title) <= maxWidth) {
            listOf(title)
        } else {
            // 太长：先降到小字号，再按宽度折行（最多 3 行，多出的截断加省略号）
            titlePaint.textSize = h * 0.062f
            wrapText(title, titlePaint, maxWidth, maxLines = 3)
        }
        val lineHeight = titlePaint.textSize * 1.28f
        var y = h * 0.42f - (lines.size - 1) * lineHeight / 2f
        for (line in lines) {
            c.drawText(line, w / 2f, y, titlePaint)
            y += lineHeight
        }

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
        private const val POSTER_COUNT = 10

        /** 焦点底色：纯绿 #7ED97A（TV 版 secondary，用于焦点卡底色） */
        private val GREEN_FOCUSED = floatArrayOf(0.494f, 0.851f, 0.478f, 1f)

        /** 焦点描边：纯绿 #4CD137（TV 版 primary，描边比底色亮一档） */
        private val GREEN_OUTLINE = floatArrayOf(0.298f, 0.820f, 0.216f, 1f)

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
