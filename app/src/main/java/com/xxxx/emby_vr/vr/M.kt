package com.xxxx.emby_vr.vr

import android.opengl.Matrix

/**
 * 极简 4x4 矩阵工具（列主序，与 OpenGL 一致）。
 *
 * 不引第三方数学库 —— 我们只需要 MVP 与几个变换，自己写更可控，
 * 也避免云端 CI 多一个依赖来源。
 */
object M {

    fun identity(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** 透视投影 */
    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray =
        FloatArray(16).also { Matrix.perspectiveM(it, 0, fovYDeg, aspect, near, far) }

    /** 相机：eye 位置、center 看向点、up 上方向 */
    fun lookAt(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        cX: Float, cY: Float, cZ: Float,
        upX: Float, upY: Float, upZ: Float,
    ): FloatArray = FloatArray(16).also {
        Matrix.setLookAtM(it, 0, eyeX, eyeY, eyeZ, cX, cY, cZ, upX, upY, upZ)
    }

    /** 平移 */
    fun translate(x: Float, y: Float, z: Float): FloatArray =
        FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, x, y, z) }

    /** 绕 Y 轴旋转（度）；用于把海报墙摆到侧面 */
    fun rotateY(deg: Float): FloatArray = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.rotateM(it, 0, deg, 0f, 1f, 0f)
    }

    /** 缩放 */
    fun scale(sx: Float, sy: Float, sz: Float = 1f): FloatArray = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.scaleM(it, 0, sx, sy, sz)
    }

    /** out = a * b（先应用 b 再应用 a） */
    fun mul(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(16).also { Matrix.multiplyMM(it, 0, a, 0, b, 0) }

    fun mul(vararg ms: FloatArray): FloatArray =
        ms.reduce { acc, m -> mul(acc, m) }

    /** 4x4 矩阵变换一个点 (w=1)，返回 xyz */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, m, 0, floatArrayOf(x, y, z, 1f), 0)
        return out
    }
}
