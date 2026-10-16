package com.danxing.spatialscene.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLES 3.0 通用工具：着色器编译、网格 VBO、纹理上传。
 * 所有方法都必须在已 makeCurrent 的 GL 线程调用。
 */
object GlCore {

    class Grid(val vbo: Int, val ibo: Int, val indexCount: Int)

    fun compile(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        try {
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("着色器链接失败: $log")
            }
            return program
        } finally {
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("着色器编译失败: $log")
        }
        return shader
    }

    /** 创建 [-1,1]² 的规则网格（位置位于 location 0）。 */
    fun createGrid(segments: Int): Grid {
        val n = segments
        val vertexCount = (n + 1) * (n + 1)

        val vertices = ByteBuffer
            .allocateDirect(vertexCount * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (j in 0..n) {
            for (i in 0..n) {
                vertices.put(i.toFloat() / n * 2f - 1f)
                vertices.put(j.toFloat() / n * 2f - 1f)
            }
        }
        vertices.position(0)

        val indexCount = n * n * 6
        val indices = ByteBuffer
            .allocateDirect(indexCount * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        for (j in 0 until n) {
            for (i in 0 until n) {
                val a = j * (n + 1) + i
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                indices.put(a).put(c).put(b)
                indices.put(b).put(c).put(d)
            }
        }
        indices.position(0)

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertexCount * 2 * 4, vertices, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, indices, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return Grid(buffers[0], buffers[1], indexCount)
    }

    fun deleteGrid(grid: Grid?) {
        if (grid != null) {
            GLES30.glDeleteBuffers(2, intArrayOf(grid.vbo, grid.ibo), 0)
        }
    }

    fun loadTexture(bitmap: Bitmap, mipmaps: Boolean): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmaps) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR
            )
        } else {
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
            )
        }
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return textures[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }
}
