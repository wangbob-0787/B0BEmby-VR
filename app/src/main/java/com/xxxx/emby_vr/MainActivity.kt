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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xxxx.emby_vr.data.EmbyContent
import com.xxxx.emby_vr.data.model.BaseItemDto
import com.xxxx.emby_vr.data.remote.EmbyApi
import com.xxxx.emby_vr.data.remote.HttpClient as EmbyHttpClient
import com.xxxx.emby_vr.vr.InputRouter
import com.xxxx.emby_vr.vr.VrRenderer
import com.xxxx.emby_vr.vr.VrSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            InputRouter.Action.LEFT -> {
                if (renderer.videoActive) seekBy(-10_000) else moveFocus(-1)
            }
            InputRouter.Action.RIGHT -> {
                if (renderer.videoActive) seekBy(+10_000) else moveFocus(+1)
            }
            InputRouter.Action.UP,
            InputRouter.Action.DOWN -> { /* 海报墙只有一行，上下暂忽略 */ }
            InputRouter.Action.CONFIRM -> {
                if (renderer.videoActive) togglePlayPause() else confirmCurrent()
            }
            InputRouter.Action.BACK -> {
                if (renderer.videoActive) stopPlayback() else clearFocus()
            }
            InputRouter.Action.PLAY_PAUSE -> if (renderer.videoActive) togglePlayPause()
            InputRouter.Action.SEEK_BACK -> if (renderer.videoActive) seekBy(-10_000)
            InputRouter.Action.SEEK_FORWARD -> if (renderer.videoActive) seekBy(+10_000)
            InputRouter.Action.RAY_POS,
            InputRouter.Action.RAY_DIR -> applyRayFocus()
            else -> { /* 余下动作后续接 */ }
        }
    }

    /** 播放期状态反馈：写到视频画面上的状态条（屏幕大字被视频盖住，看不见） */
    private fun hud(text: String) = renderer.setHudText(text)

    /**
     * 指针位置变化的统一入口（触摸通道 `applyRay` 和按键通道 `applyRayFocus`
     * 都走这里）。
     *
     * 播放中：PICO 摇杆走的是**指针通道**（方向动作根本不产生，只有指针坐标
     * 在动），所以横向位移累计到阈值触发一次快进/快退，方向按位移方向；
     * 海报焦点此时由渲染器冻结，不会跟着光标跑。
     * 非播放中：只同步坐标，渲染器据此给海报换焦点。
     */
    private var lastRayX: Float? = null
    private var seekAccumX = 0f
    private var lastSeekAt = 0L

    private fun applyRay(ray: FloatArray) {
        renderer.simRayX = ray[0]
        renderer.simRayY = ray[1]

        if (!renderer.videoActive) return

        // 播放中：横移累计 → 快进/快退
        val prev = lastRayX
        lastRayX = ray[0]
        if (prev != null) seekAccumX += ray[0] - prev
        val now = System.currentTimeMillis()
        if (now - lastSeekAt < 800) return          // 两次 seek 之间冷却，防刷屏
        if (seekAccumX > SEEK_STEP) {
            // 指针坐标与视觉方向在实机上是反的（父亲 2026-10-04 实测：
            // 向右拨要快退、向左拨要快进），这里按实测结果配对
            seekBy(-10_000)
            seekAccumX = 0f
            lastSeekAt = now
        } else if (seekAccumX < -SEEK_STEP) {
            seekBy(+10_000)
            seekAccumX = 0f
            lastSeekAt = now
        }
    }

    /** 按键通道的射线同步（方向键分支走这里），复用同一套 seek 逻辑 */
    private fun applyRayFocus() {
        val ray = InputRouter.currentSimRay ?: return
        applyRay(ray)
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

    /**
     * 确认：取播放地址并起播（P3）。
     *
     * 链路（与 TV 版一致）：`getPlaybackInfo` → `mediaSources.first()`
     * 的 `directStreamUrl`（没有就用 `transcodingUrl`）→ `${server}/emby<path}` →
     * ExoPlayer 解码 → Surface → 渲染器 OES 纹理贴到虚拟屏。
     *
     * **播放中先拦截**：扳机走的是触摸通道（onConfirm 直调本函数，不经过
     * input 分发），不拦就会在播放中重新走起播流程 —— 父亲 2026-10-04 实测
     * 「扣扳机/拨摇杆都让视频从头开始播放」就是这个原因。
     */
    private fun confirmCurrent() {
        if (renderer.videoActive) {
            togglePlayPause()
            return
        }
        val i = renderer.focusedPosterIndex
        if (i < 0 || i >= movies.size) {
            renderer.setScreenText("未选中任何海报")
            return
        }
        val item = movies[i]
        val id = item.id
        if (id == null) {
            renderer.setScreenText("条目无 id，无法播放")
            return
        }
        renderer.setScreenText("正在获取播放地址…")
        scope.launch {
            val media = EmbyApi.getPlaybackInfo(
                context = this@MainActivity,
                serverUrl = BuildConfig.EMBY_SERVER,
                apiKey = BuildConfig.EMBY_API_KEY,
                deviceId = EmbyContent.DEVICE_ID,
                userId = BuildConfig.EMBY_USER_ID,
                mediaId = id,
                startTimeTicks = 0L,
            )
            val source = media.mediaSources?.firstOrNull()
            var path = source?.directStreamUrl ?: source?.transcodingUrl
            if (path == null) {
                renderer.setScreenText("取不到播放地址（服务器未返回直链）")
                return@launch
            }
            // 直链缺 api_key 时补上（Emby 视频直链默认不带 token）
            if (!path.contains("api_key=")) {
                path += (if (path.contains("?")) "&" else "?") + "api_key=${BuildConfig.EMBY_API_KEY}"
            }
            val url = "${BuildConfig.EMBY_SERVER}/emby$path"
            withContext(Dispatchers.Main) {
                startPlayer(url, item.name ?: "")
            }
        }
    }

    /** 起播 */
    private fun startPlayer(url: String, title: String) {
        val surface = renderer.videoSurface
        if (surface == null) {
            renderer.setScreenText("视频纹理未就绪（GL 还没建好）")
            return
        }
        try {
            stopPlaybackInternal()
            player = ExoPlayer.Builder(this).build().also { p ->
                p.setMediaItem(MediaItem.fromUri(url))
                p.setVideoSurface(surface)
                p.prepare()
                p.playWhenReady = true
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        hud("播放出错：${friendlyError(error)}")
                        renderer.videoActive = false
                    }
                })
            }
            renderer.videoActive = true
            // 进播放时重置指针位移累计，避免入场第一拨就触发一次跳转
            seekAccumX = 0f
            lastRayX = null
            lastSeekAt = 0L
            hud("播放中：左右拨动摇杆=快进快退 · 扳机=暂停 · 返回=退出")
            renderer.setScreenText(title)
            Log.i(TAG, "开始播放: $title url=${url.take(160)}")
        } catch (e: Exception) {
            Log.e(TAG, "起播失败", e)
            hud("起播失败：${friendlyError(e)}")
            renderer.videoActive = false
        }
    }

    /**
     * 把技术错误翻成一句人话（给父亲看的屏上提示，不出现英文异常名）。
     * 只翻译能判断的常见情况，翻不出就给「播放失败，原因未知」并把详情留给日志。
     */
    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            t is java.net.UnknownHostException -> "连不上服务器"
            t is java.net.SocketTimeoutException || msg.contains("timeout", true) -> "连接超时"
            t is java.net.ConnectException -> "连不上服务器"
            msg.contains("404", false) -> "地址不存在"
            msg.contains("401", false) || msg.contains("403", false) -> "认证失败"
            msg.contains("HTTP 5", false) -> "服务器内部错误"
            msg.contains("解码", false) || msg.contains("图片", false) -> "图片解码失败"
            msg.contains("HTTP", false) -> "请求失败"
            else -> "播放失败（详情见日志）"
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        hud(if (p.playWhenReady) "继续播放" else "已暂停")
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        val sec = p.currentPosition / 1000
        Log.i(TAG, "seek ${deltaMs / 1000}s → ${sec / 60}:${"%02d".format(sec % 60)}")
        hud("${if (deltaMs < 0) "快退" else "快进"} 10 秒 → ${sec / 60}:${"%02d".format(sec % 60)}")
    }

    /** 停止播放并回到海报墙 */
    private fun stopPlayback() {
        stopPlaybackInternal()
        renderer.videoActive = false
        renderer.setHudText("")
        renderer.setScreenText(movies.firstOrNull()?.name ?: "B0BEmby VR")
        Log.i(TAG, "停止播放，回到海报墙")
    }

    private fun stopPlaybackInternal() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
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

    // ---- Emby 内容加载（P2）----

    /** 已加载的影片列表，海报墙按它的顺序排 */
    private var movies: List<BaseItemDto> = emptyList()

    /** 播放器：点海报起播，返回键释放 */
    private var player: ExoPlayer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 拉取影片 + 下载海报。
     *
     * 流程：拉列表 → 更新海报墙可见数量 → 逐张下载海报位图 → 投递给渲染器。
     * 海报下载用 OkHttp 直接取字节（不用 Coil 的 ImageLoader，因为它面向
     * Compose 的 AsyncImage；这里要的是原始 Bitmap 交给 GL，直接取更直接）。
     *
     * ## 为什么把错误直接写到虚拟屏上
     *
     * 2026-10-03 实机排查：PICO 的 adb 端口连不上，拿不到 logcat，
     * 只能靠「屏上显示什么」来定位。所以这里把失败原因（异常类型 + 消息 +
     * 服务器地址）直接渲染到虚拟屏，不用 adb 也能看到卡在哪一步。
     */
    private fun loadLibrary() {
        val apiKey = BuildConfig.EMBY_API_KEY
        val server = BuildConfig.EMBY_SERVER
        Log.i(TAG, "Emby 配置: server=$server keyLen=${apiKey.length} userId=${BuildConfig.EMBY_USER_ID}")
        if (apiKey.isBlank()) {
            renderer.setScreenText("未配置 Emby Key")
            Log.w(TAG, "BuildConfig.EMBY_API_KEY 为空，跳过内容加载")
            return
        }
        renderer.setScreenText("正在连接 $server")

        scope.launch {
            val result = EmbyContent.loadMoviesDetailed(
                context = this@MainActivity,
                apiKey = apiKey,
                serverUrl = server,
                userId = BuildConfig.EMBY_USER_ID,
                limit = POSTER_SLOTS,
            )
            when (result) {
                is EmbyContent.Result.Ok -> {
                    val list = result.items
                    movies = list
                    Log.i(TAG, "影片加载完成: ${list.size} 条")
                    if (list.isEmpty()) {
                        renderer.setScreenText("库返回 0 条影片")
                        return@launch
                    }
                    renderer.clearPosters()
                    renderer.activePosterCount = list.size
                    renderer.setScreenText("${list.first().name ?: ""}")
                    downloadPosters(list, apiKey)
                }
                is EmbyContent.Result.Err -> {
                    // 屏上直接显示失败原因，避免「黑盒」排查；详情留给日志
                    Log.e(TAG, "拉取影片失败: ${result.reason}")
                    renderer.setScreenText("连不上服务器（详情见日志）")
                }
            }
        }
    }

    /**
     * 逐张下载海报并投递给渲染器（顺序下载，避免一次性打开过多连接）。
     *
     * 把成功/失败数量回报到虚拟屏上：2026-10-03 实机排查时 adb 不通、
     * 拿不到 logcat，只能靠屏上文字判断海报到底下没下来。
     */
    private fun downloadPosters(list: List<BaseItemDto>, apiKey: String) {
        scope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            var lastReason = ""
            list.forEachIndexed { index, item ->
                val id = item.id
                if (id == null) {
                    fail++
                    lastReason = "条目无 id"
                    return@forEachIndexed
                }
                val tag = item.imageTags?.get("Primary")
                val url = EmbyContent.posterUrl(
                    serverUrl = BuildConfig.EMBY_SERVER,
                    itemId = id,
                    imageTag = tag,
                    apiKey = apiKey,
                )
                val r = runCatching { downloadBitmap(url) }
                val bmp = r.getOrNull()
                if (bmp != null) {
                    renderer.setPosterBitmap(index, bmp)
                    ok++
                } else {
                    fail++
                    lastReason = r.exceptionOrNull()?.let { friendlyError(it) } ?: "图片为空"
                    Log.w(TAG, "海报下载失败 #$index ${item.name}: ${lastReason}", r.exceptionOrNull())
                }
            }
            Log.i(TAG, "海报下载完成: 成功 $ok / 失败 $fail")
            if (fail > 0) {
                // 屏上给出失败概况 + 最后一条原因，便于无 adb 时定位
                renderer.setScreenText("海报 $ok 张成功、$fail 张失败：$lastReason")
            }
        }
    }

    /** 下载图片为 Bitmap；失败时抛出便于上层记录原因 */
    private fun downloadBitmap(url: String): Bitmap? {
        val client = EmbyHttpClient.getClient(this)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("响应体为空")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("图片解码失败(${bytes.size}字节)")
            return bmp
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
        stopPlaybackInternal()
        renderer.releaseVideoPipeline()
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

        /**
         * 播放中指针横移触发一次快进/快退的阈值（射线坐标单位，满偏约 ±2）。
         * 0.5 相当于推摇杆三分之一多一点，符合「拨一下快进 10 秒」的手感。
         */
        private const val SEEK_STEP = 0.5f
    }
}
