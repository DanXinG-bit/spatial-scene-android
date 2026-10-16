package com.danxing.spatialscene.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 通过系统的 ImageDecoder 读取照片（自动处理 EXIF 方向），
 * 并把最长边限制在 [maxLongSide] 以内，避免 12MP 大图占用过多内存。
 */
object ImageLoader {

    fun load(context: Context, uri: Uri, maxLongSide: Int = 2048): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longSide = max(width, height)
            if (longSide > maxLongSide) {
                val scale = maxLongSide.toFloat() / longSide
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }
}
