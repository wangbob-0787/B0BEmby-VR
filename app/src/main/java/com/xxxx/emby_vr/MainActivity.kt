package com.xxxx.emby_vr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.xxxx.emby_vr.vr.InputRouter
import com.xxxx.emby_vr.vr.VrRenderer
import com.xxxx.emby_vr.vr.VrSession
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * B0BEmby VR 版 主 Activity（P0 骨架）。
 *
 * 目标（父亲 2026-10-03）：最小可用平面虚拟屏 —— 纯色空间 + 正前方 16:9 平面。
 *
 * 渲染路线：GLSurfaceView + OpenGL ES 3.0。
 * XR 会话（PICO OpenXR）走 VrSession 封装，P0 先确保画面能出、手柄事件能收到，
 * 后续阶段再把每眼视图矩阵接进来做真正的立体渲染。
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
    }

    /** 把指针位置同步给渲染器（渲染器据此决定聚焦哪张卡） */
    private fun applyRay(ray: FloatArray) {
        renderer.simRayX = ray[0]
        renderer.simRayY = ray[1]
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
    }
}
