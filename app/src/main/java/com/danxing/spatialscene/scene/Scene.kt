package com.danxing.spatialscene.scene

import org.json.JSONObject

/**
 * 一个保存下来的空间场景：原图 + 深度图 + 缩略图 + 变换参数。
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
    val offsetY: Float
) {
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
    }

    companion object {
        fun fromJson(json: JSONObject): Scene? = try {
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
                offsetY = json.optDouble("offsetY", 0.0).toFloat()
            )
        } catch (t: Throwable) {
            null
        }
    }
}
