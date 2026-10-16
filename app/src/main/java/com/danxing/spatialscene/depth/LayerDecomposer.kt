package com.danxing.spatialscene.depth

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把「照片 + 深度」拆成 N 层（默认 4 层），每层是一张刚性卡片：
 *
 * - 每层内部深度为常数 → 网格不会在深度悬崖处被撑开 → 不再有波浪纹；
 * - 每层被更近层遮住的区域，用**更远层**的像素做多尺度 push-pull 补全，
 *   所以近层平移时露出的永远是合理的远处内容，而不是拉伸的橡皮膜。
 *
 * 层顺序：返回列表 index 0 = 最近层，依次到最后一层 = 最远层（渲染时先画最远）。
 */
object LayerDecomposer {

    private const val TAG = "SpatialScene"
    private const val PACK_LONG_SIDE = 1024
    private const val FEATHER_RADIUS = 2

    /** 小于该占比的深度簇会被合并到邻居（避免碎层） */
    private const val MIN_CLUSTER_FRACTION = 0.03f

    /** 小于该占比的小碎块会被清除 */
    private const val MIN_COMPONENT_FRACTION = 0.0015f

    data class Layer(
        val image: Bitmap,
        val alpha: Bitmap,
        /** 该层的平面深度（0~1，近=1） */
        val depth: Float
    )

    fun decompose(
        image: Bitmap,
        depth: FloatArray,
        depthWidth: Int,
        depthHeight: Int,
        layerCount: Int = 4
    ): List<Layer>? {
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
            val d = bilinear(depth, depthWidth, depthHeight, w, h)

            val sorted = d.copyOf()
            sorted.sort()
            if (sorted[n - 1] - sorted[0] < 0.04f) {
                Log.i(TAG, "深度动态范围过小，回退单层渲染")
                return null
            }

            val color = readColors(img, w, h)

            // 1) 深度聚类（label 越大越近），并合并过小的簇
            var labels = kMeansLabels(d, layerCount)
            labels = mergeSmallClusters(labels, d, layerCount, n)
            val maxLabel = labels.maxOrNull() ?: 0
            val actualCount = maxLabel + 1

            // 2) 逐层生成（near → far）
            val layers = ArrayList<Layer>(actualCount)
            for (t in 0 until actualCount) {
                val label = actualCount - 1 - t // t=0 最近层
                var mask = ByteArray(n)
                var count = 0
                var depthSum = 0f
                for (i in 0 until n) {
                    if (labels[i] == label) {
                        mask[i] = 1
                        count++
                        depthSum += d[i]
                    }
                }
                if (count == 0) continue

                mask = majorityFilter(mask, w, h)
                mask = filterComponents(mask, w, h, (n * MIN_COMPONENT_FRACTION).toInt())

                // 用自己的深度均值（平面深度）
                var sum = 0f
                var valid = 0
                for (i in 0 until n) {
                    if (mask[i].toInt() == 1) {
                        sum += d[i]
                        valid++
                    }
                }
                if (valid == 0) continue
                val layerDepth = (sum / valid).coerceIn(0.03f, 0.99f)

                // 羽化 alpha
                val alphaValues = FloatArray(n) { mask[it].toFloat() }
                val feathered = boxBlur(alphaValues, w, h, FEATHER_RADIUS)

                // 更远层作为补全来源；若没有更远层，则用自己的周边
                val farther = FloatArray(n)
                var fartherCount = 0
                for (i in 0 until n) {
                    if (labels[i] < label) {
                        farther[i] = 1f
                        fartherCount++
                    }
                }
                val validSource = if (fartherCount > n / 400) farther else FloatArray(n) { mask[it].toFloat() }

                val filled = pushPull(color, validSource, w, h)
                // 自身区域保留原图，被遮区域用更远层的补全结果
                val layerColor = filled
                for (i in 0 until n) {
                    if (mask[i].toInt() == 1) {
                        layerColor[i * 3] = color[i * 3]
                        layerColor[i * 3 + 1] = color[i * 3 + 1]
                        layerColor[i * 3 + 2] = color[i * 3 + 2]
                    }
                }

                val imagePixels = IntArray(n)
                val alphaPixels = IntArray(n)
                for (i in 0 until n) {
                    imagePixels[i] = argb(layerColor[i * 3], layerColor[i * 3 + 1], layerColor[i * 3 + 2])
                    val a = (feathered[i].coerceIn(0f, 1f) * 255f).roundToInt()
                    alphaPixels[i] = gray(a)
                }
                val layerImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                layerImage.setPixels(imagePixels, 0, w, 0, 0, w, h)
                val layerAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                layerAlpha.setPixels(alphaPixels, 0, w, 0, 0, w, h)

                layers.add(Layer(layerImage, layerAlpha, layerDepth))
                Log.i(TAG, "图层 $t: label=$label 像素=$valid 深度=$layerDepth")
            }

            if (layers.size < 2) {
                Log.i(TAG, "有效图层不足，回退单层渲染")
                return null
            }
            return layers
        } catch (t: Throwable) {
            Log.e(TAG, "分层失败，回退单层渲染", t)
            return null
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 1D k-means，返回每个像素的簇编号（0 = 最远，编号越大越近）。 */
    private fun kMeansLabels(depth: FloatArray, k: Int): IntArray {
        val n = depth.size
        val step = max(1, n / 8192)
        val samples = ArrayList<Float>(n / step + 1)
        var i = 0
        while (i < n) {
            samples.add(depth[i])
            i += step
        }
        if (samples.isEmpty()) return IntArray(n)

        val sortedSamples = samples.sorted()
        val centers = FloatArray(k) { idx ->
            val q = ((idx + 0.5f) / k * (sortedSamples.size - 1)).toInt()
            sortedSamples[q.coerceIn(0, sortedSamples.size - 1)]
        }
        // 保证中心互不相同
        for (idx in 1 until k) {
            if (centers[idx] <= centers[idx - 1]) centers[idx] = centers[idx - 1] + 1e-4f
        }

        val sums = FloatArray(k)
        val counts = IntArray(k)
        repeat(15) {
            java.util.Arrays.fill(sums, 0f)
            java.util.Arrays.fill(counts, 0)
            for (s in samples) {
                var best = 0
                var bestDist = Float.MAX_VALUE
                for (c in 0 until k) {
                    val dist = kotlin.math.abs(s - centers[c])
                    if (dist < bestDist) {
                        bestDist = dist
                        best = c
                    }
                }
                sums[best] += s
                counts[best]++
            }
            for (c in 0 until k) {
                if (counts[c] > 0) centers[c] = sums[c] / counts[c]
            }
        }

        // centers 已按初始化时的分位点递增；按中心重排，确保 0 = 最远
        val order = (0 until k).sortedBy { centers[it] }
        val remap = IntArray(k)
        order.forEachIndexed { rank, original -> remap[original] = rank }

        val labels = IntArray(n)
        for (idx in 0 until n) {
            var best = 0
            var bestDist = Float.MAX_VALUE
            for (c in 0 until k) {
                val dist = kotlin.math.abs(depth[idx] - centers[c])
                if (dist < bestDist) {
                    bestDist = dist
                    best = c
                }
            }
            labels[idx] = remap[best]
        }
        return labels
    }

    /** 合并占比过小的簇到相邻簇。 */
    private fun mergeSmallClusters(labels: IntArray, depth: FloatArray, k: Int, n: Int): IntArray {
        val counts = IntArray(k)
        for (l in labels) if (l in 0 until k) counts[l]++
        val minCount = (n * MIN_CLUSTER_FRACTION).toInt()

        // 计算每个簇的平均深度，用于决定并入哪一边
        val sums = DoubleArray(k)
        for (i in labels.indices) sums[labels[i]] += depth[i]
        val means = DoubleArray(k) { if (counts[it] > 0) sums[it] / counts[it] else Double.NaN }

        val parent = IntArray(k) { it }
        fun find(x: Int): Int {
            var v = x
            while (parent[v] != v) v = parent[v]
            return v
        }

        for (c in 0 until k) {
            if (counts[c] >= minCount) continue
            var target = -1
            for (other in 0 until k) {
                if (other == c || counts[other] < minCount) continue
                if (target == -1 || kotlin.math.abs(means[other] - means[c]) < kotlin.math.abs(means[target] - means[c])) {
                    target = other
                }
            }
            if (target >= 0) parent[c] = target
        }

        // 压缩标签编号
        val mapping = IntArray(k) { -1 }
        var next = 0
        val out = IntArray(labels.size)
        for (i in labels.indices) {
            val root = find(labels[i])
            if (mapping[root] == -1) mapping[root] = next++
            out[i] = mapping[root]
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

    /** 清除面积过小的连通块。 */
    private fun filterComponents(mask: ByteArray, w: Int, h: Int, minSize: Int): ByteArray {
        if (minSize <= 1) return mask
        val visited = ByteArray(mask.size)
        val queue = IntArray(mask.size)
        val out = mask.copyOf()
        val component = ArrayList<Int>()

        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || visited[start].toInt() == 1) continue
            component.clear()
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = 1
            while (head < tail) {
                val index = queue[head++]
                component.add(index)
                val x = index % w
                val y = index / w
                if (x > 0 && mask[index - 1].toInt() == 1 && visited[index - 1].toInt() == 0) {
                    visited[index - 1] = 1
                    queue[tail++] = index - 1
                }
                if (x < w - 1 && mask[index + 1].toInt() == 1 && visited[index + 1].toInt() == 0) {
                    visited[index + 1] = 1
                    queue[tail++] = index + 1
                }
                if (y > 0 && mask[index - w].toInt() == 1 && visited[index - w].toInt() == 0) {
                    visited[index - w] = 1
                    queue[tail++] = index - w
                }
                if (y < h - 1 && mask[index + w].toInt() == 1 && visited[index + w].toInt() == 0) {
                    visited[index + w] = 1
                    queue[tail++] = index + w
                }
            }
            if (component.size < minSize) {
                for (index in component) out[index] = 0
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

    /**
     * 多尺度 push-pull 补全：valid=1 为有效像素，其余为洞。
     * 逐级降采样（带权重平均）到最粗，再从粗到细回填。
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
