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
     * 手柄/按键输入 → UI 动作（P1/P2 最小平面虚拟屏）。
     *
     * 与 TV 版 B0BEmby 的交互模型对齐：
     *   左右/上下 → 移动焦点（海报墙一行内左右）
     *   确认（A/扳机） → 进入当前选中项（P1 阶段显示标题到虚拟屏）
     *   返回（B） → 返回/取消焦点
     *   摇杆偏转 → 模拟射线跟随（海报悬停高亮）
     */
    private val input = InputRouter { action ->
        when (action) {
            InputRouter.Action.LEFT -> moveFocus(-1)
            InputRouter.Action.RIGHT -> moveFocus(+1)
            InputRouter.Action.UP,
            InputRouter.Action.DOWN -> { /* P1 海报墙只有一行，上下暂忽略 */ }
            InputRouter.Action.CONFIRM -> confirmCurrent()
            InputRouter.Action.BACK -> clearFocus()
            InputRouter.Action.RAY_POS,
            InputRouter.Action.RAY_DIR -> {
                // 读取模拟射线位置，驱动海报焦点。
                // 注意：不能在 lambda 里引用 input 自身（初始化未完成），
                // 改用 InputRouter.currentSimRay() 静态快照。
                val ray = InputRouter.currentSimRay
                if (ray != null) {
                    renderer.simRayX = ray[0]
                    renderer.simRayY = ray[1]
                }
            }
            else -> { /* SEEK/PLAY_PAUSE 等播放中动作 P3 再接 */ }
        }
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
        }
        setContentView(glView)

        // 启动 XR 会话（失败不崩，退化为普通 2D 渲染，便于在没有头显时调试）
        val ok = vrSession.start(this)
        Log.i(TAG, "XR 会话启动: $ok")
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
