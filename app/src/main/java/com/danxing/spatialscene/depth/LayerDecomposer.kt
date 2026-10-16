package com.danxing.spatialscene.depth

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把「照片 + 深度」拆成两层：前景主体（刚体）+ 补全后的背景板。
 *
 * 目的：视差由两层刚性平移产生，而不是让一张连续网格在深度悬崖处
 * 被撑开（那会产生波浪纹）。被主体遮住的背景用多尺度 push-pull 金字塔
 * 从周围像素平滑延伸补全。
 */
object LayerDecomposer {

    private const val TAG = "SpatialScene"

    /** 前景（深度较高的像素）占比目标 */
    private const val FOREGROUND_FRACTION = 0.38f

    /** alpha 羽化半径（像素） */
    private const val FEATHER_RADIUS = 2

    data class Layers(
        val backgroundImage: Bitmap,
        val backgroundDepth: Bitmap,
        val foregroundAlpha: Bitmap,
        /** 前景平面深度（0~1，近=1） */
        val foregroundDepth: Float
    )

    fun decompose(
        image: Bitmap,
        depth: FloatArray,
        depthWidth: Int,
        depthHeight: Int
    ): Layers? {
        try {
            val longSide = max(image.width, image.height)
            val scale = min(1f, 1024f / longSide)
            val w = max((image.width * scale).roundToInt(), 8)
            val h = max((image.height * scale).roundToInt(), 8)
            val img = if (w != image.width || h != image.height) {
                Bitmap.createScaledBitmap(image, w, h, true)
            } else {
                image
            }
            val d = bilinear(depth, depthWidth, depthHeight, w, h)
            val n = w * h

            // 1) 深度动态范围检查
            val sorted = d.copyOf()
            sorted.sort()
            val minV = sorted[0]
            val maxV = sorted[n - 1]
            if (maxV - minV < 0.04f) {
                Log.i(TAG, "深度动态范围过小，回退单层渲染")
                return null
            }

            // 2) 阈值 = 深度最高的 FOREGROUND_FRACTION 分位
            val thresholdIndex = ((n * (1f - FOREGROUND_FRACTION)).toInt()).coerceIn(0, n - 1)
            val threshold = sorted[thresholdIndex]

            var mask = ByteArray(n)
            for (i in 0 until n) mask[i] = if (d[i] >= threshold) 1 else 0
            mask = majorityFilter(mask, w, h)
            mask = largestComponent(mask, w, h)

            var fgCount = 0
            for (m in mask) if (m.toInt() == 1) fgCount++
            if (fgCount < n / 40 || fgCount > n * 9 / 10) {
                Log.i(TAG, "前景比例异常($fgCount/$n)，回退单层渲染")
                return null
            }

            // 3) 前景平面深度 = 前景区域深度均值
            var depthSum = 0f
            for (i in 0 until n) if (mask[i].toInt() == 1) depthSum += d[i]
            val foregroundDepth = (depthSum / fgCount).coerceIn(0.05f, 0.98f)

            // 4) alpha（0/1 → 羽化）
            val alpha = FloatArray(n)
            for (i in 0 until n) alpha[i] = mask[i].toFloat()
            val feathered = boxBlur(alpha, w, h, FEATHER_RADIUS)

            // 5) 读取图像像素
            val pixels = IntArray(n)
            img.getPixels(pixels, 0, w, 0, 0, w, h)
            val color = FloatArray(n * 3)
            for (i in 0 until n) {
                val p = pixels[i]
                color[i * 3] = ((p shr 16) and 0xFF) / 255f
                color[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
                color[i * 3 + 2] = (p and 0xFF) / 255f
            }

            // 6) 背景板：把前景区域挖掉后 push-pull 补全
            val validBackground = FloatArray(n) { if (mask[it].toInt() == 1) 0f else 1f }
            val bgColor = pushPull(color, validBackground, w, h)
            val bgDepth = pushPull(FloatArray(n) { d[it] }, validBackground, w, h)

            // 7) 生成位图
            val bgPixels = IntArray(n)
            val bgDepthPixels = IntArray(n)
            val alphaPixels = IntArray(n)
            for (i in 0 until n) {
                bgPixels[i] = argb(bgColor[i * 3], bgColor[i * 3 + 1], bgColor[i * 3 + 2])
                val dv = (bgDepth[i].coerceIn(0f, 1f) * 255f).roundToInt()
                bgDepthPixels[i] = gray(dv)
                val av = (feathered[i].coerceIn(0f, 1f) * 255f).roundToInt()
                alphaPixels[i] = gray(av)
            }

            val bgImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgImage.setPixels(bgPixels, 0, w, 0, 0, w, h)
            val bgDepthBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgDepthBmp.setPixels(bgDepthPixels, 0, w, 0, 0, w, h)
            val fgAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fgAlpha.setPixels(alphaPixels, 0, w, 0, 0, w, h)

            Log.i(TAG, "分层完成: ${w}x$h, 前景 $fgCount px, 前景深度 $foregroundDepth")
            return Layers(bgImage, bgDepthBmp, fgAlpha, foregroundDepth)
        } catch (t: Throwable) {
            Log.e(TAG, "分层失败，回退单层渲染", t)
            return null
        }
    }

    // ------------------------------------------------------------------ 工具

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

    /** 3x3 多数滤波：去掉孤立噪点。 */
    private fun majorityFilter(mask: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        sum += mask[yy * w + xx].toInt()
                        count++
                    }
                }
                out[y * w + x] = if (sum * 2 >= count) 1 else 0
            }
        }
        return out
    }

    /** 只保留最大的连通前景块（4 连通 BFS）。 */
    private fun largestComponent(mask: ByteArray, w: Int, h: Int): ByteArray {
        val visited = ByteArray(mask.size)
        val queue = IntArray(mask.size)
        var bestStart = -1
        var bestSize = 0

        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || visited[start].toInt() == 1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = 1
            var size = 0
            while (head < tail) {
                val index = queue[head++]
                size++
                val x = index % w
                val y = index / w
                if (x > 0) enqueue(mask, visited, queue, tail, index - 1)?.let { tail = it }
                if (x < w - 1) enqueue(mask, visited, queue, tail, index + 1)?.let { tail = it }
                if (y > 0) enqueue(mask, visited, queue, tail, index - w)?.let { tail = it }
                if (y < h - 1) enqueue(mask, visited, queue, tail, index + w)?.let { tail = it }
            }
            if (size > bestSize) {
                bestSize = size
                bestStart = start
            }
        }

        if (bestStart < 0) return mask
        if (bestSize == mask.count { it.toInt() == 1 }) return mask

        // 重新标记最大块
        val out = ByteArray(mask.size)
        var head = 0
        var tail = 0
        queue[tail++] = bestStart
        out[bestStart] = 1
        val seen = ByteArray(mask.size)
        seen[bestStart] = 1
        while (head < tail) {
            val index = queue[head++]
            val x = index % w
            val y = index / w
            fun visit(nb: Int) {
                if (mask[nb].toInt() == 1 && seen[nb].toInt() == 0) {
                    seen[nb] = 1
                    out[nb] = 1
                    queue[tail++] = nb
                }
            }
            if (x > 0) visit(index - 1)
            if (x < w - 1) visit(index + 1)
            if (y > 0) visit(index - w)
            if (y < h - 1) visit(index + w)
        }
        return out
    }

    private inline fun enqueue(
        mask: ByteArray,
        visited: ByteArray,
        queue: IntArray,
        tail: Int,
        index: Int
    ): Int? {
        if (mask[index].toInt() == 1 && visited[index].toInt() == 0) {
            visited[index] = 1
            queue[tail] = index
            return tail + 1
        }
        return null
    }

    /** 均值滤波（积分图），边界按窗口内实际像素数归一。 */
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

    /**
     * 多尺度 push-pull 补全：valid=1 的像素是有效数据，其余为待补全的洞。
     * 逐级降采样（带权重平均）到最粗，再从粗到细把洞用上层插值填上。
     */
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

        // 最粗一级用局部膨胀填满，保证后续插值源完整
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

    /** 用邻近有效像素把最粗一级的空缺填满（少量迭代即可，该层像素很少）。 */
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
