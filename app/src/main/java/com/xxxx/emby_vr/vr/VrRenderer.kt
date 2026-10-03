package com.xxxx.emby_vr.vr

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.xxxx.emby_vr.scene.Quad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * VR 场景渲染器（P0）。
 *
 * 场景构成（父亲 2026-10-03 定的「最小可用平面虚拟屏」）：
 *   1. 正前方 3m 处一块 16:9 虚拟屏（宽 3.0m），画面/占位色贴上去
 *   2. 屏幕左前方一片海报墙（P2 接真实海报，P0 先画占位卡）
 *   3. 底部一条控制条底板（P5 接控制逻辑）
 *
 * 渲染用 OpenGL ES 3.0。P0 单眼渲染；P1 起改为每眼一次（接入 XR 的投影/视图矩阵）。
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
    private lateinit var posterQuads: List<Quad>
    private lateinit var barQuad: Quad

    private var viewW = 1
    private var viewH = 1

    // 虚拟屏参数（米）
    private val screenWidth = 3.0f
    private val screenHeight = screenWidth * 9f / 16f
    private val screenDistance = 3.0f

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

        // 海报墙：一块 5 列 2 行的占位卡阵（真实海报 P2 接）
        val cardW = 0.42f
        val cardH = cardW * 3f / 2f   // 2:3 海报比例，与 TV 版一致
        posterQuads = List(10) { Quad(cardW, cardH, textured = false) }

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.03f, 0.03f, 0.04f, 1f)   // 近黑空间（与 TV 版底色一致）
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged ${width}x$height")
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val aspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        // 观察者在原点，朝 -Z 看；屏幕放在 -Z 方向 3m 处
        val proj = M.perspective(70f, aspect, 0.05f, 100f)
        val view = M.lookAt(
            0f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val vp = M.mul(proj, view)

        // 1) 虚拟屏
        drawQuad(screenQuad, M.translate(0f, 0f, -screenDistance), vp, 0.10f)

        // 2) 海报墙：摆到屏幕左侧，两行
        val cardW = 0.42f
        val gapX = 0.08f
        val gapY = 0.12f
        posterQuads.forEachIndexed { i, q ->
            val col = i % 5
            val row = i / 5
            val totalW = 5 * cardW + 4 * gapX
            val x = -totalW / 2f + col * (cardW + gapX) + cardW / 2f
            val y = -0.15f - row * (cardW * 1.5f + gapY)
            drawQuad(
                q,
                M.mul(M.translate(x, y, -screenDistance + 0.02f)),
                vp,
                0.35f,
            )
        }

        // 3) 底部控制条底板
        drawQuad(
            barQuad,
            M.translate(0f, -screenHeight / 2f - 0.30f, -screenDistance + 0.01f),
            vp,
            0.25f,
        )
    }

    private fun drawQuad(quad: Quad, model: FloatArray, viewProj: FloatArray, brightness: Float) {
        val mvp = M.mul(viewProj, model)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES30.glUniform1i(useTexHandle, 0)
        GLES30.glUniform4f(colorHandle, brightness, brightness * 1.02f, brightness, 1f)
        quad.bindAttribs(posHandle, uvHandle)
        quad.draw()
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
                fragColor = uColor;
            }
        """
    }
}
