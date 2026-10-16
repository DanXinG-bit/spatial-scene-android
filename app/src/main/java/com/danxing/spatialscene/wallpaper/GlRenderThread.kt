package com.danxing.spatialscene.wallpaper

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 独立的 EGL/GLES 渲染线程，直接渲染到 WallpaperService 的 Surface。
 *
 * M0 渲染一张 160x160 的深度网格（约 5.1 万三角形），
 * 顶点着色器按程序化场景深度位移，相机由 [GyroCamera] 驱动。
 */
class GlRenderThread(
    private val holder: SurfaceHolder,
    private val camera: GyroCamera
) : Thread("SpatialScene-Render") {

    @Volatile
    private var running = true

    @Volatile
    private var visible = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var uZoom = -1
    private var uCam = -1
    private var uParallax = -1
    private var uRoll = -1

    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    fun setVisible(value: Boolean) {
        visible = value
    }

    fun shutdown() {
        running = false
        interrupt()
    }

    override fun run() {
        try {
            if (!initEgl()) return
            if (!initGl()) return

            var last = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now

                if (visible && holder.surface?.isValid == true) {
                    updateViewport()
                    camera.step(dt)
                    draw()
                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                        Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                        sleep(50)
                    }
                    // 约 60fps 上限，避免无谓耗电
                    sleep(5)
                } else {
                    sleep(32)
                }
            }
        } catch (_: InterruptedException) {
            // 正常退出
        } catch (t: Throwable) {
            Log.e(TAG, "渲染线程崩溃", t)
        } finally {
            releaseGl()
            releaseEgl()
        }
    }

    // ------------------------------------------------------------------ EGL

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "无法获取 EGL display")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize 失败: ${EGL14.eglGetError()}")
            return false
        }

        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0
            ) || numConfigs[0] == 0
        ) {
            Log.e(TAG, "没有可用的 EGL 配置")
            return false
        }

        val config = configs[0] ?: return false
        val contextAttrs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext 失败: ${EGL14.eglGetError()}")
            return false
        }

        val surfaceAttrs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, holder.surface, surfaceAttrs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface 失败: ${EGL14.eglGetError()}")
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent 失败: ${EGL14.eglGetError()}")
            return false
        }
        return true
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    // ------------------------------------------------------------------- GL

    private fun initGl(): Boolean {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, SceneShaders.VERTEX)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, SceneShaders.FRAGMENT)
        if (vs == 0 || fs == 0) return false

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "着色器链接失败: ${GLES30.glGetProgramInfoLog(program)}")
            return false
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        uZoom = GLES30.glGetUniformLocation(program, "uZoom")
        uCam = GLES30.glGetUniformLocation(program, "uCam")
        uParallax = GLES30.glGetUniformLocation(program, "uParallax")
        uRoll = GLES30.glGetUniformLocation(program, "uRoll")

        buildGrid(160)
        return true
    }

    private fun buildGrid(segments: Int) {
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

        val indices = ByteBuffer
            .allocateDirect(n * n * 6 * 4)
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
        indexCount = n * n * 6

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        vbo = buffers[0]
        ibo = buffers[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertexCount * 2 * 4, vertices,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, indices,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun draw() {
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        // 1.10 的放大相当于四周各留 5% 安全边，配合相机 clamp 杜绝露边
        GLES30.glUniform1f(uZoom, 1.10f)
        GLES30.glUniform2f(uCam, camera.cameraX, camera.cameraY)
        GLES30.glUniform1f(uParallax, 1.0f)
        GLES30.glUniform1f(uRoll, camera.cameraRoll)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0
        )
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun releaseGl() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (vbo != 0 || ibo != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
            vbo = 0
            ibo = 0
        }
    }

    private fun updateViewport() {
        val frame = holder.surfaceFrame
        val w = frame.width().coerceAtLeast(1)
        val h = frame.height().coerceAtLeast(1)
        if (w != viewportWidth || h != viewportHeight) {
            viewportWidth = w
            viewportHeight = h
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) return 0
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "着色器编译失败: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "SpatialScene"
    }
}
