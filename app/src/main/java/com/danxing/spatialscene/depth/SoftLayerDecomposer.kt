package com.danxing.spatialscene.depth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SLIDE 式软分层 + AI 去遮挡补全。
 *
 * 1. 前景软可见度 `A = exp(-β·‖∇D‖²)`：平坦区域 A≈1，深度悬崖处 A≈0；
 * 2. 去遮挡区域（"主体背后可能露出来的地方"）= 悬崖带 ∩ 主体侧，用 AI（MI-GAN）补全；
 * 3. 渲染：先画背景板（连续深度），再叠加原图（连续深度）× A。
 *
 * 为什么是"主体侧"：相机平移时露出的是**主体背后**原本被挡住的像素，
 * 它们在图上的位置就是主体自己的那一片（不是主体外侧的背景）。
 */
object SoftLayerDecomposer {

    private const val TAG = "SpatialScene"
    private const val PACK_LONG_SIDE = 1024

    /** 可见度衰减系数：深度梯度越大越透明 */
    private const val BETA = 45f

    /** 低于该可见度视为悬崖带 */
    private const val CLIFF_ALPHA = 0.18f

    /** 透明带加宽半径（覆盖网格单元，避免跨悬崖三角形半透明拉伸） */
    private const val ERODE_RADIUS = 2

    /**
     * 补全带宽半径。倾斜时露出的宽度 ≈ 视差 × 深度差，
     * 在本工程参数下最大约 25px（1024 长边），取 24 并留余量。
     */
    private const val BAND_RADIUS = 24

    /** 判定"主体侧"的深度余量 */
    private const val FG_MARGIN = 0.10f

    data class Result(
        val backgroundImage: Bitmap,
        val backgroundDepth: Bitmap,
        val foregroundAlpha: Bitmap,
        /** 是否用到了 AI 补全（false = 几何补全兜底） */
        val aiFilled: Boolean
    )

    fun decompose(
        context: Context,
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

            val d = boxBlur(bilinear(depth, depthWidth, depthHeight, w, h), w, h, 1)
            val minV = d.minOrNull() ?: 0f
            val maxV = d.maxOrNull() ?: 1f
            if (maxV - minV < 0.04f) {
                Log.i(TAG, "深度动态范围过小，回退单层渲染")
                return null
            }

            // 1) 前景软可见度（并加宽透明带）
            val visibility = FloatArray(n)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val gx = sobelX(d, w, h, x, y)
                    val gy = sobelY(d, w, h, x, y)
                    visibility[y * w + x] = exp(-BETA * (gx * gx + gy * gy))
                }
            }
            val alpha = filter(visibility, w, h, ERODE_RADIUS, min = true)

            // 2) 去遮挡区域 = 悬崖带（膨胀）∩ 主体侧（比邻域最远内容更近）
            val cliff = FloatArray(n) { if (alpha[it] < CLIFF_ALPHA) 1f else 0f }
            val band = filter(cliff, w, h, BAND_RADIUS, min = false)
            val localMinDepth = filter(d, w, h, BAND_RADIUS * 2, min = true)
            val hole = BooleanArray(n)
            var holeCount = 0
            for (i in 0 until n) {
                if (band[i] > 0.5f && d[i] > localMinDepth[i] + FG_MARGIN) {
                    hole[i] = true
                    holeCount++
                }
            }
            if (holeCount < n / 200) {
                Log.i(TAG, "去遮挡区域过小($holeCount/$n)，跳过补全")
            }

            // 3) 几何预填补（同时作为 AI 的输入与兜底）
            val valid = FloatArray(n) { if (hole[it]) 0f else 1f }
            val bgDepth = pushPull(FloatArray(n) { d[it] }, valid, w, h)
            var bgColor = pushPull(readColors(img, w, h), valid, w, h)

            // 4) AI 补全（输入用预填补后的图 → 上下文是背景状，抑制前景串色）
            var aiFilled = false
            if (holeCount > 0) {
                val filled = InpaintEstimator.inpaint(context, bgColor, hole, w, h)
                if (filled != null) {
                    bgColor = filled
                    aiFilled = true
                } else {
                    Log.w(TAG, "AI 补全不可用，使用几何补全结果")
                }
            }

            // 5) 输出位图
            val bgPixels = IntArray(n)
            val bgDepthPixels = IntArray(n)
            val alphaPixels = IntArray(n)
            for (i in 0 until n) {
                bgPixels[i] = argb(bgColor[i * 3], bgColor[i * 3 + 1], bgColor[i * 3 + 2])
                bgDepthPixels[i] = gray((bgDepth[i].coerceIn(0f, 1f) * 255f).roundToInt())
                alphaPixels[i] = gray((alpha[i].coerceIn(0f, 1f) * 255f).roundToInt())
            }

            val bgImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgImage.setPixels(bgPixels, 0, w, 0, 0, w, h)
            val bgDepthBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgDepthBitmap.setPixels(bgDepthPixels, 0, w, 0, 0, w, h)
            val fgAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fgAlpha.setPixels(alphaPixels, 0, w, 0, 0, w, h)

            Log.i(
                TAG,
                "软分层完成: ${w}x$h, 补全 ${holeCount * 100 / n}%, " +
                    "AI=${if (aiFilled) "yes" else "no"}"
            )
            return Result(bgImage, bgDepthBitmap, fgAlpha, aiFilled)
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
        return (d[ym * w + xp] + 2f * d[y * w + xp] + d[yp * w + xp]) -
            (d[ym * w + xm] + 2f * d[y * w + xm] + d[yp * w + xm])
    }

    private fun sobelY(d: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xm = (x - 1).coerceAtLeast(0)
        val xp = (x + 1).coerceAtMost(w - 1)
        val ym = (y - 1).coerceAtLeast(0)
        val yp = (y + 1).coerceAtMost(h - 1)
        return (d[yp * w + xm] + 2f * d[yp * w + x] + d[yp * w + xp]) -
            (d[ym * w + xm] + 2f * d[ym * w + x] + d[ym * w + xp])
    }

    /** 可分离的 min/max 滤波（方形窗口）。 */
    private fun filter(src: FloatArray, w: Int, h: Int, radius: Int, min: Boolean): FloatArray {
        if (radius <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var value = if (min) Float.MAX_VALUE else -Float.MAX_VALUE
                val from = (x - radius).coerceAtLeast(0)
                val to = (x + radius).coerceAtMost(w - 1)
                for (i in from..to) {
                    val v = src[base + i]
                    value = if (min) minOf(value, v) else maxOf(value, v)
                }
                tmp[base + x] = value
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val from = (y - radius).coerceAtLeast(0)
            val to = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                var value = if (min) Float.MAX_VALUE else -Float.MAX_VALUE
                for (i in from..to) {
                    val v = tmp[i * w + x]
                    value = if (min) minOf(value, v) else maxOf(value, v)
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
