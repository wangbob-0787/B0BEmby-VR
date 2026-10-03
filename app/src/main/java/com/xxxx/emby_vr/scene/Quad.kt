package com.xxxx.emby_vr.scene

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 一个矩形面片（虚拟屏、海报卡、控制条底板都用它）。
 *
 * 顶点格式：x, y, z, u, v（3 位置 + 2 纹理坐标），两个三角形共 6 个顶点。
 * 平面朝向 +Z（面向观察者），尺寸以米为单位。
 */
class Quad(
    val width: Float,
    val height: Float,
    /** 是否启用纹理；false 时用纯色（便于 P1 阶段先看几何是否摆对） */
    val textured: Boolean = true,
) {
    private val vertexBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer

    val vertexCount: Int = 6

    init {
        val hw = width / 2f
        val hh = height / 2f
        // 两个三角形：左下-右下-右上 / 左下-右上-左上
        val verts = floatArrayOf(
            -hw, -hh, 0f, 0f, 1f,
             hw, -hh, 0f, 1f, 1f,
             hw,  hh, 0f, 1f, 0f,
            -hw, -hh, 0f, 0f, 1f,
             hw,  hh, 0f, 1f, 0f,
            -hw,  hh, 0f, 0f, 0f,
        )
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        // 用 glDrawArrays 就不用索引缓冲，这里留空占位
        indexBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asShortBuffer()
    }

    fun bindAttribs(posHandle: Int, uvHandle: Int) {
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(posHandle, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(posHandle)

        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(uvHandle, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(uvHandle)
    }

    fun draw() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
    }
}
