package com.danxing.spatialscene.wallpaper

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * 空间场景动态壁纸服务。
 *
 * M0：程序化场景 + 陀螺仪视差，用来验证机型兼容性与手感。
 * M2：换成照片纹理 + 模型深度图的网格渲染。
 */
class SpatialWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SpatialEngine()

    private inner class SpatialEngine : Engine() {

        private val camera = GyroCamera(this@SpatialWallpaperService)
        private var renderThread: GlRenderThread? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (renderThread == null) {
                renderThread = GlRenderThread(holder, camera).also { it.start() }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            renderThread?.setVisible(visible)
            camera.setSensorActive(visible)
            if (!visible) {
                camera.reset()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderThread?.shutdown()
            renderThread = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            renderThread?.shutdown()
            renderThread = null
            super.onDestroy()
        }
    }
}
