package com.danxing.spatialscene.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * 场景中的一层（LDI 切片）：颜色 + 羽化 alpha + 平面深度。
 */
data class SceneLayer(
    val imagePath: String,
    val alphaPath: String,
    val depth: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("imagePath", imagePath)
        put("alphaPath", alphaPath)
        put("depth", depth.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): SceneLayer? = try {
            SceneLayer(
                imagePath = json.getString("imagePath"),
                alphaPath = json.getString("alphaPath"),
                depth = json.optDouble("depth", 0.5).toFloat()
            )
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * 一个保存下来的空间场景：原图 + 深度图 + 缩略图 + 变换参数 + 分层切片。
 */
data class Scene(
    val id: String,
    val name: String,
    val createdAt: Long,
    val imagePath: String,
    val depthPath: String,
    val thumbPath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
    /** LDI 分层（index 0 = 最近层）。为空表示单层场景（旧版本保存的）。 */
    val layers: List<SceneLayer> = emptyList()
) {
    val layered: Boolean
        get() = layers.size >= 2

    val imageAspect: Float
        get() = if (imageHeight == 0) 1f else imageWidth.toFloat() / imageHeight

    fun withTransform(zoom: Float, offsetX: Float, offsetY: Float): Scene =
        copy(zoom = zoom, offsetX = offsetX, offsetY = offsetY)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("imagePath", imagePath)
        put("depthPath", depthPath)
        put("thumbPath", thumbPath)
        put("imageWidth", imageWidth)
        put("imageHeight", imageHeight)
        put("zoom", zoom.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
        put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Scene? = try {
            val layerArray = json.optJSONArray("layers")
            val layers = ArrayList<SceneLayer>()
            if (layerArray != null) {
                for (i in 0 until layerArray.length()) {
                    SceneLayer.fromJson(layerArray.getJSONObject(i))?.let { layers.add(it) }
                }
            }
            Scene(
                id = json.getString("id"),
                name = json.optString("name", "未命名"),
                createdAt = json.optLong("createdAt", 0L),
                imagePath = json.getString("imagePath"),
                depthPath = json.getString("depthPath"),
                thumbPath = json.optString("thumbPath", ""),
                imageWidth = json.getInt("imageWidth"),
                imageHeight = json.getInt("imageHeight"),
                zoom = json.optDouble("zoom", 1.12).toFloat(),
                offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                layers = layers
            )
        } catch (t: Throwable) {
            null
        }
    }
}
