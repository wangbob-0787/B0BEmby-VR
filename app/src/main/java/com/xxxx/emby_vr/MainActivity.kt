package com.xxxx.emby_vr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.xxxx.emby_vr.data.EmbyContent
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.remote.HttpClient as EmbyHttpClient
import com.xxxx.emby_vr.vr.InputRouter
import com.xxxx.emby_vr.vr.VrRenderer
import com.xxxx.emby_vr.vr.VrSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * B0BEmby VR 版 主 Activity。
 *
 * ## 阶段
 *
 * - **P0/P1（已完成）**：最小可用平面虚拟屏 —— 头显里一块 16:9 平面，
 *   手柄能指向、能确认。实机验收 2026-10-03 通过。
 * - **P2（本版）**：接 Emby —— 拉真实影片列表，海报墙显示真实海报。
 * - **P3（下一步）**：点海报播放影片。
 *
 * 渲染路线：GLSurfaceView + OpenGL ES 3.0。
 * 当前走 PICO 的 2D 面板模式（不声明 pvr.app.type），系统把画面贴成空间面板；
 * 做真双目立体渲染时再接 OpenXR runtime。
 */
class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private val vrSession = VrSession()

    /**
     * 手柄/按键输入 → UI 动作。
     *
     * ## 与 TV 版 B0BEmby 的交互模型对齐 + VR 手柄适配
     *
     * | 来源 | 语义 |
     * |---|---|
     * | 方向键 / 摇杆 | 移动焦点（同 TV 版） |
     * | 手柄指向（PICO 转成虚拟指针） | 焦点跟随指针（VR 特有） |
     * | 扳机（PICO 发 BTN_TOOL_FINGER + 指针坐标） | 确认 |
     * | 返回键 / 手柄 B | 返回上一层 |
     *
     * 实机结论（2026-10-03 抓包）：PICO 手柄走的是**虚拟指针**通道，
     * 不是 BUTTON_A，详见 [InputRouter.onTouchEvent]。
     */
    private val input = InputRouter { action ->
        when (action) {
            InputRouter.Action.LEFT -> moveFocus(-1)
            InputRouter.Action.RIGHT -> moveFocus(+1)
            InputRouter.Action.UP,
            InputRouter.Action.DOWN -> { /* 海报墙只有一行，上下暂忽略 */ }
            InputRouter.Action.CONFIRM -> confirmCurrent()
            InputRouter.Action.BACK -> clearFocus()
            InputRouter.Action.RAY_POS,
            InputRouter.Action.RAY_DIR -> applyRayFocus()
            else -> { /* SEEK/PLAY_PAUSE 等播放中动作 P3 再接 */ }
        }
    }

    /** 把当前射线位置同步给渲染器，由渲染器算出该聚焦哪张卡 */
    private fun applyRayFocus() {
        val ray = InputRouter.currentSimRay ?: return
        renderer.simRayX = ray[0]
        renderer.simRayY = ray[1]
    }

    private fun moveFocus(delta: Int) {
        val maxIdx = renderer.posterQuads.size - 1
        val next = (renderer.focusedPosterIndex + delta).coerceIn(0, maxIdx)
        if (next != renderer.focusedPosterIndex) {
            renderer.focusedPosterIndex = next
            if (next >= 0) {
                renderer.setScreenText("第 ${next + 1} / ${maxIdx + 1} 张海报")
            }
        }
    }

    private fun confirmCurrent() {
        val i = renderer.focusedPosterIndex
        renderer.setScreenText(if (i >= 0) "已选择海报 #${i + 1}" else "未选中任何海报")
    }

    private fun clearFocus() {
        renderer.focusedPosterIndex = -1
        renderer.setScreenText("B0BEmby VR")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: B0BEmby VR 启动")

        // VR 应用需保持屏幕常亮（头显内不存在系统熄屏，但某些盒子/模拟器需要）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        renderer = VrRenderer(this, vrSession)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true

            /*
             * PICO 手柄的指针/扳机走触摸通道（见 InputRouter.onTouchEvent 的抓包结论），
             * 因此必须显式打开点击与悬停两类事件；GLSurfaceView 默认只收 DOWN/UP，
             * 不加 HOVER 就收不到「手柄指向移动」。
             */
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnHoverListener { _, e ->
                input.onTouchEvent(
                    event = e,
                    viewW = width,
                    viewH = height,
                    onPointer = { ray -> applyRay(ray) },
                    onConfirm = { confirmCurrent() },
                )
            }
            setOnTouchListener { _, e ->
                input.onTouchEvent(
                    event = e,
                    viewW = width,
                    viewH = height,
                    onPointer = { ray -> applyRay(ray) },
                    onConfirm = { confirmCurrent() },
                )
            }
        }
        setContentView(glView)

        // 请求焦点：手柄的悬停/按键事件必须先有焦点才会送到本视图
        glView.requestFocus()

        // 启动 XR 会话（失败不崩，退化为普通 2D 渲染，便于在没有头显时调试）
        val ok = vrSession.start(this)
        Log.i(TAG, "XR 会话启动: $ok")

        // P2：接 Emby 拉真实影片与海报
        loadLibrary()
    }

    /** 把指针位置同步给渲染器（渲染器据此决定聚焦哪张卡） */
    private fun applyRay(ray: FloatArray) {
        renderer.simRayX = ray[0]
        renderer.simRayY = ray[1]
    }

    // ---- Emby 内容加载（P2）----

    /** 已加载的影片列表，海报墙按它的顺序排 */
    private var movies: List<BaseItemDto> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 拉取影片 + 下载海报。
     *
     * 流程：拉列表 → 更新海报墙可见数量 → 逐张下载海报位图 → 投递给渲染器。
     * 海报下载用 OkHttp 直接取字节（不用 Coil 的 ImageLoader，因为它面向
     * Compose 的 AsyncImage；这里要的是原始 Bitmap 交给 GL，直接取更直接）。
     */
    private fun loadLibrary() {
        val apiKey = BuildConfig.EMBY_API_KEY
        if (apiKey.isBlank()) {
            renderer.setScreenText("未配置 Emby Key")
            Log.w(TAG, "BuildConfig.EMBY_API_KEY 为空，跳过内容加载")
            return
        }
        renderer.setScreenText("正在连接 Emby...")

        scope.launch {
            val list = EmbyContent.loadMovies(
                context = this@MainActivity,
                apiKey = apiKey,
                serverUrl = BuildConfig.EMBY_SERVER,
                userId = BuildConfig.EMBY_USER_ID,
                limit = POSTER_SLOTS,
            )
            movies = list
            Log.i(TAG, "影片加载完成: ${list.size} 条")
            if (list.isEmpty()) {
                renderer.setScreenText("未取到影片")
                return@launch
            }
            renderer.clearPosters()
            renderer.activePosterCount = list.size
            renderer.setScreenText("${list.first().name ?: ""}")
            downloadPosters(list, apiKey)
        }
    }

    /** 逐张下载海报并投递给渲染器（顺序下载，避免一次性打开过多连接） */
    private fun downloadPosters(list: List<BaseItemDto>, apiKey: String) {
        scope.launch(Dispatchers.IO) {
            list.forEachIndexed { index, item ->
                val id = item.id ?: return@forEachIndexed
                val tag = item.imageTags?.get("Primary")
                val url = EmbyContent.posterUrl(
                    serverUrl = BuildConfig.EMBY_SERVER,
                    itemId = id,
                    imageTag = tag,
                    apiKey = apiKey,
                )
                val bmp = runCatching { downloadBitmap(url) }.getOrNull()
                if (bmp != null) {
                    renderer.setPosterBitmap(index, bmp)
                } else {
                    Log.w(TAG, "海报下载失败: ${item.name}")
                }
            }
            Log.i(TAG, "海报下载完成，共 ${list.size} 张")
        }
    }

    /** 下载图片为 Bitmap */
    private fun downloadBitmap(url: String): Bitmap? {
        val client = EmbyHttpClient.getClient(this)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        vrSession.resume()
    }

    override fun onPause() {
        vrSession.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        vrSession.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 手柄/遥控按键先给 InputRouter；它不认的（如音量键）再交给系统
        if (input.onKeyDown(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (input.onGenericMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    companion object {
        const val TAG = "B0BEmbyVR"

        /**
         * 海报墙槽位数。
         *
         * 与渲染器的 POSTER_COUNT 保持一致（渲染器按一行排布，
         * 每个槽位有固定的横向区间，超出槽位的数据会被丢弃）。
         */
        private const val POSTER_SLOTS = 10
    }
}
