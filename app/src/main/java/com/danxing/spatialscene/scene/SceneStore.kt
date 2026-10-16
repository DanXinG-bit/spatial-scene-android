package com.danxing.spatialscene.scene

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danxing.spatialscene.depth.LayerDecomposer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 场景库的持久化存储（App 私有目录）。
 *
 * [version] 是一个跨线程可见的版本号：Activity 修改后自增，
 * 动态壁纸渲染线程发现版本变化后会重新加载场景/设置。
 * 因为 WallpaperService 与 Activity 在同一进程，静态变量可以直接通信。
 */
class SceneStore private constructor(private val context: Context) {

    private val root: File = File(context.filesDir, "scenes").apply { mkdirs() }
    private val metaFile = File(root, "scenes.json")
    private val prefs = context.getSharedPreferences("scene_store", Context.MODE_PRIVATE)

    fun list(): List<Scene> {
        if (!metaFile.exists()) return emptyList()
        return try {
            val array = JSONObject(metaFile.readText()).optJSONArray(KEY_SCENES)
                ?: return emptyList()
            (0 until array.length())
                .mapNotNull { Scene.fromJson(array.getJSONObject(it)) }
                .sortedByDescending { it.createdAt }
        } catch (t: Throwable) {
            Log.e(TAG, "读取场景列表失败", t)
            emptyList()
        }
    }

    fun save(
        name: String,
        image: Bitmap,
        depth: Bitmap,
        thumb: Bitmap,
        layers: List<LayerDecomposer.Layer>? = null
    ): Scene {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val imageFile = File(dir, "image.jpg")
        val depthFile = File(dir, "depth.png")
        val thumbFile = File(dir, "thumb.jpg")

        imageFile.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        depthFile.outputStream().use { depth.compress(Bitmap.CompressFormat.PNG, 100, it) }
        thumbFile.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }

        val sceneLayers = ArrayList<SceneLayer>()
        layers?.forEachIndexed { index, layer ->
            val layerImage = File(dir, "layer_${index}.jpg")
            val layerAlpha = File(dir, "layer_${index}_alpha.png")
            layerImage.outputStream().use {
                layer.image.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            layerAlpha.outputStream().use {
                layer.alpha.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            sceneLayers.add(
                SceneLayer(
                    imagePath = layerImage.absolutePath,
                    alphaPath = layerAlpha.absolutePath,
                    depth = layer.depth
                )
            )
        }

        val scene = Scene(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            imagePath = imageFile.absolutePath,
            depthPath = depthFile.absolutePath,
            thumbPath = thumbFile.absolutePath,
            imageWidth = image.width,
            imageHeight = image.height,
            zoom = 1.12f,
            offsetX = 0f,
            offsetY = 0f,
            layers = sceneLayers
        )
        writeList(list() + scene)
        setActive(scene.id)
        return scene
    }

    fun delete(id: String) {
        val scenes = list()
        scenes.find { it.id == id }?.let { scene ->
            File(scene.imagePath).parentFile?.deleteRecursively()
        }
        writeList(scenes.filterNot { it.id == id })
        if (activeId() == id) {
            setActive(list().firstOrNull()?.id)
        } else {
            bumpVersion()
        }
    }

    fun updateTransform(scene: Scene) {
        writeList(list().map { if (it.id == scene.id) scene else it })
        bumpVersion()
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        bumpVersion()
    }

    fun activeScene(): Scene? {
        val id = activeId() ?: return null
        return list().find { it.id == id }
    }

    private fun writeList(scenes: List<Scene>) {
        val array = JSONArray()
        scenes.forEach { array.put(it.toJson()) }
        metaFile.writeText(JSONObject().put(KEY_SCENES, array).toString())
    }

    companion object {
        private const val TAG = "SpatialScene"
        private const val KEY_SCENES = "scenes"
        private const val KEY_ACTIVE = "activeId"

        @Volatile
        private var instance: SceneStore? = null

        /** 场景/设置变更版本号；渲染线程据此感知刷新。 */
        @Volatile
        var version: Long = 0L
            private set

        fun get(context: Context): SceneStore =
            instance ?: synchronized(this) {
                instance ?: SceneStore(context.applicationContext).also { instance = it }
            }

        fun bumpVersion() {
            version += 1
        }
    }
}
