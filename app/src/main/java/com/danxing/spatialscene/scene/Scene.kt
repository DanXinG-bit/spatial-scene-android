package com.danxing.spatialscene.scene

import org.json.JSONObject

/**
 * 一个保存下来的空间场景。
 *
 * 软分层（SLIDE 式）：背景板（补全窄带后的图 + 深度）+ 前景可见度 alpha；
 * 前景层直接用原图 + 原始连续深度。没有软分层字段的场景按单层渲染。
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
    val bgImagePath: String = "",
    val bgDepthPath: String = "",
    val fgAlphaPath: String = "",
    val fgDepthPath: String = "",
    val aiFilled: Boolean = false
) {
    val softLayered: Boolean
        get() = bgImagePath.isNotEmpty() && bgDepthPath.isNotEmpty() && fgAlphaPath.isNotEmpty()

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
        put("bgImagePath", bgImagePath)
        put("bgDepthPath", bgDepthPath)
        put("fgAlphaPath", fgAlphaPath)
        put("fgDepthPath", fgDepthPath)
        put("aiFilled", aiFilled)
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
                offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                bgImagePath = json.optString("bgImagePath", ""),
                bgDepthPath = json.optString("bgDepthPath", ""),
                fgAlphaPath = json.optString("fgAlphaPath", ""),
                fgDepthPath = json.optString("fgDepthPath", ""),
                aiFilled = json.optBoolean("aiFilled", false)
            )
        } catch (t: Throwable) {
            null
        }
    }
}
