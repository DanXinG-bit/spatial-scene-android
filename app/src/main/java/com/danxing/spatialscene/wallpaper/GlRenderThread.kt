package com.danxing.spatialscene.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.SurfaceHolder
import com.danxing.spatialscene.gl.GlCore
import com.danxing.spatialscene.gl.PhotoProgram
import com.danxing.spatialscene.gl.ProceduralProgram
import com.danxing.spatialscene.scene.AppSettings
import com.danxing.spatialscene.scene.Scene
import com.danxing.spatialscene.scene.SceneStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 独立的 EGL/GLES 3.0 渲染线程，直接渲染到 WallpaperService 的 Surface。
 *
 * - 有活动场景时：照片纹理 + 深度图网格视差（[PhotoProgram]）
 * - 没有场景时：M0 的程序化山谷兜底（[ProceduralProgram]）
 *
 * 省电策略：只在「可见 + 相机仍在运动（或场景/设置刚变化）」时绘制，
 * 静止时跳过绘制，仅保持传感器监听。
 */
class GlRenderThread(
    private val context: Context,
    private val holder: SurfaceHolder,
    private val camera: GyroCamera
) : Thread("SpatialScene-Render") {

    @Volatile
    private var running = true

    @Volatile
    private var visible = false

    @Volatile
    private var needsDraw = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var grid: GlCore.Grid? = null
    private var photoProgram: PhotoProgram? = null
    private var proceduralProgram: ProceduralProgram? = null

    private var imageTexture = 0
    private var depthTexture = 0
    private var bgImageTexture = 0
    private var bgDepthTexture = 0
    private var fgAlphaTexture = 0
    private var fgDepthTexture = 0
    private var scene: Scene? = null
    private var loadedSceneId: String? = null

    private var loadedVersion = -1L
    private var parallax = 1f
    private var depthGamma = 0.9f

    private var viewportWidth = 0
    private var viewportHeight = 0

    private var lastCamX = Float.NaN
    private var lastCamY = Float.NaN

    fun setVisible(value: Boolean) {
        visible = value
        needsDraw = true
        if (value) camera.reset()
    }

    fun requestRedraw() {
        needsDraw = true
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
                    refreshIfNeeded()
                    camera.step(dt)

                    val moved = cameraMoved()
                    if (needsDraw || moved) {
                        draw()
                        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                            Log.w(TAG, "eglSwapBuffers 失败: ${EGL14.eglGetError()}")
                            sleep(50)
                        }
                        lastCamX = camera.cameraX
                        lastCamY = camera.cameraY
                        needsDraw = false
                        sleep(5)
                    } else {
                        // 静止：不绘制，省电
                        sleep(16)
                    }
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
        return try {
            photoProgram = PhotoProgram()
            proceduralProgram = ProceduralProgram()
            grid = GlCore.createGrid(SEGMENTS)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "GL 初始化失败", t)
            false
        }
    }

    private fun refreshIfNeeded() {
        if (loadedVersion == SceneStore.version) return
        loadedVersion = SceneStore.version

        val settings = AppSettings.get(context)
        parallax = settings.parallax
        depthGamma = settings.depthGamma
        camera.sensitivity = BASE_SENSITIVITY * settings.sensitivity

        val active = SceneStore.get(context).activeScene()
        if (active?.id != loadedSceneId) {
            loadScene(active)
            loadedSceneId = active?.id
        } else if (active != null) {
            scene = active
        }
        needsDraw = true
    }

    private fun loadScene(target: Scene?) {
        releaseTextures()
        scene = target
        if (target == null) return
        try {
            val image = BitmapFactory.decodeFile(target.imagePath)
            val depth = BitmapFactory.decodeFile(target.depthPath)
            if (image == null || depth == null) {
                Log.e(TAG, "场景图片加载失败: ${target.id}")
                scene = null
                return
            }
            imageTexture = GlCore.loadTexture(image, mipmaps = true)
            depthTexture = GlCore.loadTexture(depth, mipmaps = false)
            image.recycle()
            depth.recycle()

            if (target.softLayered) {
                val bg = BitmapFactory.decodeFile(target.bgImagePath)
                val bgDepth = BitmapFactory.decodeFile(target.bgDepthPath)
                val fgAlpha = BitmapFactory.decodeFile(target.fgAlphaPath)
                if (bg != null && bgDepth != null && fgAlpha != null) {
                    bgImageTexture = GlCore.loadTexture(bg, mipmaps = true)
                    bgDepthTexture = GlCore.loadTexture(bgDepth, mipmaps = false)
                    fgAlphaTexture = GlCore.loadTexture(fgAlpha, mipmaps = false)
                    bg.recycle()
                    bgDepth.recycle()
                    fgAlpha.recycle()
                    val fgDepth = if (target.fgDepthPath.isNotEmpty()) {
                        BitmapFactory.decodeFile(target.fgDepthPath)
                    } else {
                        null
                    }
                    if (fgDepth != null) {
                        fgDepthTexture = GlCore.loadTexture(fgDepth, mipmaps = false)
                        fgDepth.recycle()
                    }
                } else {
                    Log.w(TAG, "软分层贴图加载失败，回退单层渲染")
                }
            }

            Log.i(
                TAG,
                "场景已加载: ${target.name} (${target.imageWidth}x${target.imageHeight}) " +
                    "softLayered=${target.softLayered && bgImageTexture != 0}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "加载场景失败", t)
            releaseTextures()
            scene = null
        }
    }

    private fun releaseTextures() {
        if (imageTexture != 0) {
            GlCore.deleteTexture(imageTexture)
            imageTexture = 0
        }
        if (depthTexture != 0) {
            GlCore.deleteTexture(depthTexture)
            depthTexture = 0
        }
        if (bgImageTexture != 0) {
            GlCore.deleteTexture(bgImageTexture)
            bgImageTexture = 0
        }
        if (bgDepthTexture != 0) {
            GlCore.deleteTexture(bgDepthTexture)
            bgDepthTexture = 0
        }
        if (fgAlphaTexture != 0) {
            GlCore.deleteTexture(fgAlphaTexture)
            fgAlphaTexture = 0
        }
        if (fgDepthTexture != 0) {
            GlCore.deleteTexture(fgDepthTexture)
            fgDepthTexture = 0
        }
    }

    private fun cameraMoved(): Boolean {
        if (lastCamX.isNaN()) return true
        return abs(camera.cameraX - lastCamX) > MOTION_EPSILON ||
            abs(camera.cameraY - lastCamY) > MOTION_EPSILON
    }

    private fun draw() {
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val mesh = grid ?: return
        val current = scene
        if (current == null || imageTexture == 0) {
            proceduralProgram?.draw(mesh, camera.cameraX, camera.cameraY, 0f)
            return
        }

        // cover 填充 + 用户缩放
        val screenAspect = viewportWidth.toFloat() / max(viewportHeight, 1).toFloat()
        val imageAspect = current.imageAspect
        var scaleX = 1f
        var scaleY = 1f
        if (imageAspect >= screenAspect) {
            scaleX = imageAspect / screenAspect
        } else {
            scaleY = screenAspect / imageAspect
        }
        scaleX *= current.zoom
        scaleY *= current.zoom

        // 防露边 + 手感一致：
        // 1. 两个轴的"位移自由度"用**像素**统一（否则照片比例不同会导致某一轴更弱）；
        // 2. 纵向 NDC 乘 W/H，使同样的倾角在横竖方向产生相同的**像素**位移。
        val wPx = max(viewportWidth, 1).toFloat()
        val hPx = max(viewportHeight, 1).toFloat()
        val marginX = (scaleX - 1f - abs(current.offsetX)).coerceAtLeast(0f)
        val marginY = (scaleY - 1f - abs(current.offsetY)).coerceAtLeast(0f)
        val slackXpx = marginX * wPx * 0.5f
        val slackYpx = marginY * hPx * 0.5f
        // 绝对上限：屏幕宽度的 2.5%（2640px ≈ 66px）。
        // 之前只按"可用边距"限制，横图配竖屏时边距巨大 → 位移可达 250px+，
        // 整个画面会"游泳"，背景被深度梯度揉弯，边缘出现折射感。
        val maxShiftPx = 0.025f * wPx / max(parallax, 0.05f)
        val limitPx = min(0.9f * min(slackXpx, slackYpx), maxShiftPx)
        val clipX = limitPx / (wPx * 0.5f)
        val clipY = limitPx / (hPx * 0.5f)
        val camX = camera.cameraX.coerceIn(-clipX, clipX)
        val camY = (camera.cameraY * (wPx / hPx)).coerceIn(-clipY, clipY)

        if (current.softLayered && bgImageTexture != 0 && bgDepthTexture != 0 && fgAlphaTexture != 0) {
            // 背景板：补全后的图 + 连续深度（不透明）
            photoProgram?.draw(
                mesh, bgImageTexture, bgDepthTexture, 0,
                scaleX, scaleY, current.offsetX, current.offsetY,
                camX, camY, parallax, depthGamma
            )
            // 前景：原图 + 连续深度 + 软可见度 alpha（悬崖处透明，露出背景）
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            photoProgram?.draw(
                mesh, imageTexture,
                if (fgDepthTexture != 0) fgDepthTexture else depthTexture,
                fgAlphaTexture,
                scaleX, scaleY, current.offsetX, current.offsetY,
                camX, camY, parallax, depthGamma,
                useAlpha = true
            )
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            if (depthTexture == 0) return
            photoProgram?.draw(
                mesh, imageTexture, depthTexture, 0,
                scaleX, scaleY, current.offsetX, current.offsetY,
                camX, camY, parallax, depthGamma
            )
        }
    }

    private fun releaseGl() {
        releaseTextures()
        photoProgram?.release()
        photoProgram = null
        proceduralProgram?.release()
        proceduralProgram = null
        GlCore.deleteGrid(grid)
        grid = null
    }

    private fun updateViewport() {
        val frame = holder.surfaceFrame
        val w = frame.width().coerceAtLeast(1)
        val h = frame.height().coerceAtLeast(1)
        if (w != viewportWidth || h != viewportHeight) {
            viewportWidth = w
            viewportHeight = h
            needsDraw = true
        }
    }

    companion object {
        private const val TAG = "SpatialScene"

        /** 网格细分（193x193 顶点，约 7.4 万三角形） */
        private const val SEGMENTS = 192

        /** 陀螺仪基础灵敏度（NDC/弧度），再乘以用户设置 */
        private const val BASE_SENSITIVITY = 0.45f

        /** 相机静止判定阈值 */
        private const val MOTION_EPSILON = 2e-5f
    }
}
