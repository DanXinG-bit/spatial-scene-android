package com.danxing.spatialscene.depth

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SLIDE 式软分层（Google, ICCV 2021: "Single Image 3D Photography with
 * Soft Layering and Depth-aware Inpainting" 的核心思想，纯几何实现）。
 *
 * 1. 前景可见度 A = exp(-β·‖∇D‖²)：平坦区域 A≈1，深度悬崖处 A≈0；
 * 2. 悬崖窄带（A 小处）用 push-pull 从周围补全，得到背景板（颜色 + 深度）；
 * 3. 渲染：先画背景板（连续深度），再叠加原图（连续深度） × A。
 *
 * 相比硬分层（把图切成刚性卡片）：
 * - 主体内部保留连续深度 → 有内部视差，不会被"挖下来平移"；
 * - 悬崖处前景透明 → 网格拉伸的三角形看不见 → 没有波浪纹；
 * - 不会出现分层接缝/黑边。
 */
object SoftLayerDecomposer {

    private const val TAG = "SpatialScene"
    private const val PACK_LONG_SIDE = 1024

    /** 可见度衰减系数：深度梯度越大越透明 */
    private const val BETA = 45f

    /** 低于该可见度的像素视为"待补全的洞" */
    private const val HOLE_ALPHA = 0.18f

    data class Result(
        val backgroundImage: Bitmap,
        val backgroundDepth: Bitmap,
        val foregroundAlpha: Bitmap
    )

    fun decompose(
        image: Bitmap,
        depth: FloatArray,
        depthWidth: Int,
        depthHeight: Int
    ): Result? {
        try {
            val longSide = max(image.width, image.height)
            val scale = min(1f, PACK_LONG_SIDE.toFloat() / longSide)
            val w = max((image.width * scale).roundToInt(), 8)
            val h = max((image.height * scale).roundToInt(), 8)
            val img = if (w != image.width || h != image.height) {
                Bitmap.createScaledBitmap(image, w, h, true)
            } else {
                image
            }
            val n = w * h

            // 轻微平滑，抑制噪声形成的假悬崖
            val d = boxBlur(bilinear(depth, depthWidth, depthHeight, w, h), w, h, 1)
            val minV = d.minOrNull() ?: 0f
            val maxV = d.maxOrNull() ?: 1f
            if (maxV - minV < 0.04f) {
                Log.i(TAG, "深度动态范围过小，回退单层渲染")
                return null
            }

            // 1) 软可见度 A = exp(-β‖∇D‖²)
            val alpha = FloatArray(n)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val gx = sobelX(d, w, h, x, y)
                    val gy = sobelY(d, w, h, x, y)
                    val g2 = gx * gx + gy * gy
                    alpha[y * w + x] = exp(-BETA * g2)
                }
            }
            // 略微加宽透明带（min 滤波），让跨悬崖的三角形大部分透明
            val visibility = minFilter(alpha, w, h, 1)

            // 2) 洞 = 可见度低处；有效像素 = 其余
            val valid = FloatArray(n) { if (visibility[it] > HOLE_ALPHA) 1f else 0f }
            var validCount = 0
            for (v in valid) if (v > 0f) validCount++
            if (validCount < n / 10) {
                Log.i(TAG, "有效背景像素过少($validCount/$n)，回退单层渲染")
                return null
            }

            val color = readColors(img, w, h)
            val bgColor = pushPull(color, valid, w, h)
            val bgDepth = pushPull(FloatArray(n) { d[it] }, valid, w, h)

            // 3) 生成位图
            val bgPixels = IntArray(n)
            val bgDepthPixels = IntArray(n)
            val alphaPixels = IntArray(n)
            for (i in 0 until n) {
                bgPixels[i] = argb(bgColor[i * 3], bgColor[i * 3 + 1], bgColor[i * 3 + 2])
                val dv = (bgDepth[i].coerceIn(0f, 1f) * 255f).roundToInt()
                bgDepthPixels[i] = gray(dv)
                val av = (visibility[i].coerceIn(0f, 1f) * 255f).roundToInt()
                alphaPixels[i] = gray(av)
            }

            val bgImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgImage.setPixels(bgPixels, 0, w, 0, 0, w, h)
            val bgDepthBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgDepthBmp.setPixels(bgDepthPixels, 0, w, 0, 0, w, h)
            val fgAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fgAlpha.setPixels(alphaPixels, 0, w, 0, 0, w, h)

            Log.i(TAG, "软分层完成: ${w}x$h, 补全像素 ${n - validCount} (${(n - validCount) * 100 / n}%)")
            return Result(bgImage, bgDepthBmp, fgAlpha)
        } catch (t: Throwable) {
            Log.e(TAG, "软分层失败，回退单层渲染", t)
            return null
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun sobelX(d: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xm = (x - 1).coerceAtLeast(0)
        val xp = (x + 1).coerceAtMost(w - 1)
        val ym = (y - 1).coerceAtLeast(0)
        val yp = (y + 1).coerceAtMost(h - 1)
        val a = d[ym * w + xm]
        val b = d[ym * w + x]
        val c = d[ym * w + xp]
        val dd = d[y * w + xm]
        val f = d[y * w + xp]
        val g = d[yp * w + xm]
        val hh = d[yp * w + x]
        val i = d[yp * w + xp]
        return (c + 2f * f + i) - (a + 2f * dd + g)
    }

    private fun sobelY(d: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xm = (x - 1).coerceAtLeast(0)
        val xp = (x + 1).coerceAtMost(w - 1)
        val ym = (y - 1).coerceAtLeast(0)
        val yp = (y + 1).coerceAtMost(h - 1)
        val a = d[ym * w + xm]
        val b = d[ym * w + x]
        val c = d[ym * w + xp]
        val g = d[yp * w + xm]
        val hh = d[yp * w + x]
        val i = d[yp * w + xp]
        return (g + 2f * hh + i) - (a + 2f * b + c)
    }

    private fun minFilter(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var value = Float.MAX_VALUE
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val v = src[yy * w + xx]
                        if (v < value) value = v
                    }
                }
                out[y * w + x] = value
            }
        }
        return out
    }

    private fun readColors(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val color = FloatArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            color[i * 3] = ((p shr 16) and 0xFF) / 255f
            color[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            color[i * 3 + 2] = (p and 0xFF) / 255f
        }
        return color
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun bilinear(
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

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val stride = w + 1
        val integral = DoubleArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            val rowBase = (y + 1) * stride
            val prevBase = y * stride
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                integral[rowBase + x + 1] = integral[prevBase + x + 1] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = min(h - 1, y + radius)
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                val sum = integral[(y1 + 1) * stride + (x1 + 1)] -
                    integral[y0 * stride + (x1 + 1)] -
                    integral[(y1 + 1) * stride + x0] +
                    integral[y0 * stride + x0]
                val count = (y1 - y0 + 1) * (x1 - x0 + 1)
                out[y * w + x] = (sum / count).toFloat()
            }
        }
        return out
    }

    private class Level(
        val width: Int,
        val height: Int,
        val color: FloatArray,
        val weight: FloatArray
    )

    /** 多尺度 push-pull 补全：valid=1 为有效像素，其余为洞。 */
    private fun pushPull(color: FloatArray, valid: FloatArray, w: Int, h: Int): FloatArray {
        val levels = ArrayList<Level>()
        levels.add(Level(w, h, color.copyOf(), valid.copyOf()))

        var cw = w
        var ch = h
        var currentColor = levels[0].color
        var currentWeight = levels[0].weight
        while (cw > 1 || ch > 1) {
            val nw = max(1, (cw + 1) / 2)
            val nh = max(1, (ch + 1) / 2)
            val nc = FloatArray(nw * nh * 3)
            val nwt = FloatArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    var weight = 0f
                    for (dy in 0..1) {
                        val sy = y * 2 + dy
                        if (sy >= ch) continue
                        for (dx in 0..1) {
                            val sx = x * 2 + dx
                            if (sx >= cw) continue
                            val idx = sy * cw + sx
                            val wt = currentWeight[idx]
                            if (wt <= 0f) continue
                            r += currentColor[idx * 3] * wt
                            g += currentColor[idx * 3 + 1] * wt
                            b += currentColor[idx * 3 + 2] * wt
                            weight += wt
                        }
                    }
                    val out = y * nw + x
                    if (weight > 0f) {
                        nc[out * 3] = r / weight
                        nc[out * 3 + 1] = g / weight
                        nc[out * 3 + 2] = b / weight
                        nwt[out] = weight
                    }
                }
            }
            levels.add(Level(nw, nh, nc, nwt))
            currentColor = nc
            currentWeight = nwt
            cw = nw
            ch = nh
        }

        val coarsest = levels.last()
        if (coarsest.weight.all { it <= 0f }) return color
        dilateFill(coarsest)

        for (i in levels.size - 2 downTo 0) {
            val fine = levels[i]
            val coarse = levels[i + 1]
            for (y in 0 until fine.height) {
                for (x in 0 until fine.width) {
                    val idx = y * fine.width + x
                    val weight = fine.weight[idx]
                    if (weight >= 1f) continue
                    val fx = (x + 0.5f) * coarse.width / fine.width - 0.5f
                    val fy = (y + 0.5f) * coarse.height / fine.height - 0.5f
                    val sample = sampleLevel(coarse, fx, fy)
                    val t = weight.coerceIn(0f, 1f)
                    fine.color[idx * 3] = fine.color[idx * 3] * t + sample[0] * (1f - t)
                    fine.color[idx * 3 + 1] = fine.color[idx * 3 + 1] * t + sample[1] * (1f - t)
                    fine.color[idx * 3 + 2] = fine.color[idx * 3 + 2] * t + sample[2] * (1f - t)
                    fine.weight[idx] = 1f
                }
            }
        }
        return levels[0].color
    }

    private fun dilateFill(level: Level) {
        var remaining = level.weight.count { it <= 0f }
        var guard = 0
        while (remaining > 0 && guard < 64) {
            guard++
            val snapshotColor = level.color.copyOf()
            val snapshotWeight = level.weight.copyOf()
            var filled = 0
            for (y in 0 until level.height) {
                for (x in 0 until level.width) {
                    val idx = y * level.width + x
                    if (snapshotWeight[idx] > 0f) continue
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    var count = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= level.height) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= level.width) continue
                            val nIdx = yy * level.width + xx
                            if (snapshotWeight[nIdx] > 0f) {
                                r += snapshotColor[nIdx * 3]
                                g += snapshotColor[nIdx * 3 + 1]
                                b += snapshotColor[nIdx * 3 + 2]
                                count++
                            }
                        }
                    }
                    if (count > 0) {
                        level.color[idx * 3] = r / count
                        level.color[idx * 3 + 1] = g / count
                        level.color[idx * 3 + 2] = b / count
                        level.weight[idx] = 1f
                        filled++
                    }
                }
            }
            if (filled == 0) break
            remaining -= filled
        }
    }

    private fun sampleLevel(level: Level, fx: Float, fy: Float): FloatArray {
        val x0 = fx.toInt().coerceIn(0, level.width - 1)
        val y0 = fy.toInt().coerceIn(0, level.height - 1)
        val x1 = (x0 + 1).coerceIn(0, level.width - 1)
        val y1 = (y0 + 1).coerceIn(0, level.height - 1)
        val wx = (fx - x0).coerceIn(0f, 1f)
        val wy = (fy - y0).coerceIn(0f, 1f)
        val out = FloatArray(3)
        for (c in 0..2) {
            val v00 = level.color[(y0 * level.width + x0) * 3 + c]
            val v01 = level.color[(y0 * level.width + x1) * 3 + c]
            val v10 = level.color[(y1 * level.width + x0) * 3 + c]
            val v11 = level.color[(y1 * level.width + x1) * 3 + c]
            val top = v00 + (v01 - v00) * wx
            val bottom = v10 + (v11 - v10) * wx
            out[c] = top + (bottom - top) * wy
        }
        return out
    }
}
