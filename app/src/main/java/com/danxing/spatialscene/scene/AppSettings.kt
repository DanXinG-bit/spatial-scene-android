package com.danxing.spatialscene.scene

import android.content.Context

/**
 * 全局渲染设置（作用于所有场景）。
 * 修改后调用 [SceneStore.bumpVersion]，动态壁纸会立即生效。
 */
class AppSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** 视差强度：0.4（克制）~ 1.8（明显） */
    var parallax: Float
        get() = prefs.getFloat(KEY_PARALLAX, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_PARALLAX, value.coerceIn(0.4f, 1.8f)).apply()
            SceneStore.bumpVersion()
        }

    /** 景深范围：<1 让更多中景参与运动，>1 更集中在近景 */
    var depthGamma: Float
        get() = prefs.getFloat(KEY_GAMMA, 0.9f)
        set(value) {
            prefs.edit().putFloat(KEY_GAMMA, value.coerceIn(0.6f, 1.4f)).apply()
            SceneStore.bumpVersion()
        }

    /** 跟随灵敏度：陀螺仪到相机的放大倍数 */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(0.4f, 1.8f)).apply()
            SceneStore.bumpVersion()
        }

    companion object {
        private const val KEY_PARALLAX = "parallax"
        private const val KEY_GAMMA = "depthGamma"
        private const val KEY_SENSITIVITY = "sensitivity"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
