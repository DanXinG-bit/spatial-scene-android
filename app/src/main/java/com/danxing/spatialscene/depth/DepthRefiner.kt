package com.danxing.spatialscene.depth

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 深度图精修：把 14 倍下采样的模型输出，用引导滤波（guide = 原图灰度）
 * 提升到与照片贴合的边缘。
 *
 * 目的：让深度悬崖从「几像素的模糊过渡」变回「贴着真实物体边缘的陡崖」，
 * 从而显著减轻网格视差的橡皮膜拉伸感。
 */
object DepthRefiner {

    data class Refined(
        val bitmap: Bitmap,
        val values: FloatArray,
        val width: Int,
        val height: Int
    )

    fun refine(
        depthValues: FloatArray,
        depthWidth: Int,
        depthHeight: Int,
        guide: Bitmap,
        targetLongSide: Int = 1024,
        radius: Int = 8,
        eps: Float = 0.01f
    ): Refined {
        val longSide = max(guide.width, guide.height)
        val scale = if (longSide > targetLongSide) targetLongSide.toFloat() / longSide else 1f
        val gw = max((guide.width * scale).roundToInt(), 2)
        val gh = max((guide.height * scale).roundToInt(), 2)

        val guideScaled =
            if (gw != guide.width || gh != guide.height) {
                Bitmap.createScaledBitmap(guide, gw, gh, true)
            } else {
                guide
            }

        val guideGray = grayValues(guideScaled, gw, gh)
        val depthUpsampled = bilinearResize(depthValues, depthWidth, depthHeight, gw, gh)
        val refined = guidedFilter(guideGray, depthUpsampled, gw, gh, radius, eps)

        val bitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(gw * gh)
        for (i in pixels.indices) {
            val v = (refined[i].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, gw, 0, 0, gw, gh)
        return Refined(bitmap, refined, gw, gh)
    }

    private fun grayValues(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(width * height)
        for (i in out.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return out
    }

    private fun bilinearResize(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): FloatArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
        val out = FloatArray(dstWidth * dstHeight)
        val xRatio = if (dstWidth > 1) (srcWidth - 1).toFloat() / (dstWidth - 1) else 0f
        val yRatio = if (dstHeight > 1) (srcHeight - 1).toFloat() / (dstHeight - 1) else 0f
        for (y in 0 until dstHeight) {
            val fy = y * yRatio
            val y0 = fy.toInt().coerceIn(0, srcHeight - 1)
            val y1 = (y0 + 1).coerceIn(0, srcHeight - 1)
            val wy = fy - y0
            for (x in 0 until dstWidth) {
                val fx = x * xRatio
                val x0 = fx.toInt().coerceIn(0, srcWidth - 1)
                val x1 = (x0 + 1).coerceIn(0, srcWidth - 1)
                val wx = fx - x0
                val v00 = src[y0 * srcWidth + x0]
                val v01 = src[y0 * srcWidth + x1]
                val v10 = src[y1 * srcWidth + x0]
                val v11 = src[y1 * srcWidth + x1]
                val top = v00 + (v01 - v00) * wx
                val bottom = v10 + (v11 - v10) * wx
                out[y * dstWidth + x] = top + (bottom - top) * wy
            }
        }
        return out
    }

    private fun guidedFilter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float
    ): FloatArray {
        val meanI = boxFilter(guide, width, height, radius)
        val meanP = boxFilter(input, width, height, radius)
        val corrI = boxFilter(FloatArray(guide.size) { guide[it] * guide[it] }, width, height, radius)
        val corrIP = boxFilter(FloatArray(guide.size) { guide[it] * input[it] }, width, height, radius)

        val a = FloatArray(guide.size)
        val b = FloatArray(guide.size)
        for (i in a.indices) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covIP / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }

        val meanA = boxFilter(a, width, height, radius)
        val meanB = boxFilter(b, width, height, radius)
        return FloatArray(guide.size) {
            (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f)
        }
    }

    /** 积分图实现的均值滤波，边界按窗口内的实际像素数归一。 */
    private fun boxFilter(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val stride = width + 1
        val integral = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            val rowBase = (y + 1) * stride
            val prevBase = y * stride
            for (x in 0 until width) {
                rowSum += src[y * width + x]
                integral[rowBase + x + 1] = integral[prevBase + x + 1] + rowSum
            }
        }

        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val sum = integral[(y1 + 1) * stride + (x1 + 1)] -
                    integral[y0 * stride + (x1 + 1)] -
                    integral[(y1 + 1) * stride + x0] +
                    integral[y0 * stride + x0]
                val count = (y1 - y0 + 1) * (x1 - x0 + 1)
                out[y * width + x] = (sum / count).toFloat()
            }
        }
        return out
    }
}
