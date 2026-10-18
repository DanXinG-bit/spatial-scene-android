package com.danxing.spatialscene.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 主体/背景两层软分层：**抠出主体 → AI 补全整块背景 → 把主体放回**。
 *
 * 与之前"深度梯度透明带"方案的区别（也是修正的关键）：
 * 1. 前景 alpha = **主体遮罩**（硬边 + 1px 羽化），不再在悬崖处透明 → 主体边缘不会被"吞掉"；
 * 2. 背景板在**主体整个投影范围**内都被 AI 补全（不只是窄带）→ 主体平移让开时，
 *    露出的是完整的、合理生成的内容（"另一面"），而不是拉伸的橡皮膜；
 * 3. 两层都用**无悬崖深度**：前景层在遮罩外延用主体边缘深度，背景层在遮罩内用补全深度
 *    → 两个网格内部都没有陡崖 → 不会产生拉伸条纹。
 *
 * 静止时：合成 = 原图（遮罩内是原图主体，遮罩外是原图背景），所以静态画质无损。
 */
object SoftLayerDecomposer {

    private const val TAG = "SpatialScene"
    private const val PACK_LONG_SIDE = 1024
    private const val MIN_MASK_FRACTION = 0.03f
    private const val MAX_MASK_FRACTION = 0.90f

    /** 最近一次失败的原因（直接显示在界面上，便于定位） */
    @Volatile
    var lastFailure: String? = null
        private set

    data class Result(
        val backgroundImage: Bitmap,
        val backgroundDepth: Bitmap,
        val foregroundAlpha: Bitmap,
        /** 前景层几何深度（遮罩外延，无悬崖） */
        val foregroundDepth: Bitmap,
        val aiFilled: Boolean,
        /** 诊断信息，直接显示在界面上 */
        val note: String
    )

    fun decompose(
        context: Context,
        image: Bitmap,
        depth: FloatArray,
        depthWidth: Int,
        depthHeight: Int
    ): Result? {
        var stage = "init"
        try {
            lastFailure = null
            stage = "scale"
            val longSide = max(image.width, image.height)
            val scale = min(1f, PACK_LONG_SIDE.toFloat() / longSide)
            val w = max((image.width * scale).roundToInt(), 8)
            val h = max((image.height * scale).roundToInt(), 8)
            // 始终画到确定尺寸的软件位图（避免 config/尺寸不一致导致 getPixels 越界）
            val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(img).drawBitmap(
                image,
                Rect(0, 0, image.width, image.height),
                Rect(0, 0, w, h),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            val n = w * h

            stage = "depth"
            val d = boxBlur(bilinear(depth, depthWidth, depthHeight, w, h), w, h, 1)
            val depthRange = (d.maxOrNull() ?: 1f) - (d.minOrNull() ?: 0f)

            // 1) 主体遮罩
            stage = "mask"
            var mask = if (depthRange < 0.04f) {
                BooleanArray(n)
            } else {
                val t = otsuThreshold(d)
                BooleanArray(n) { d[it] > t }
            }
            mask = largestComponents(mask, w, h, (n * 0.002f).toInt())
            var maskCount = mask.count { it }
            if (maskCount < (n * MIN_MASK_FRACTION).toInt() ||
                maskCount > (n * MAX_MASK_FRACTION).toInt()
            ) {
                val sorted = d.copyOf()
                sorted.sort()
                val cut = sorted[(n * 0.70f).toInt().coerceIn(0, n - 1)]
                val fallback = BooleanArray(n) { d[it] > cut }
                if (fallback.count { it } > (n * 0.01f).toInt()) {
                    mask = largestComponents(fallback, w, h, (n * 0.002f).toInt())
                    maskCount = mask.count { it }
                }
            }
            val maskFloat = FloatArray(n) { if (mask[it]) 1f else 0f }

            // 2) 环带：AI 只补"主体让开后会露出来"的那一圈。
            //    细长主体（带宽 > 厚度）时 erode 为空 → 自动退化为整块补全。
            stage = "band"
            val bandPx = (0.06f * max(w, h)).roundToInt().coerceIn(24, 96)
            val eroded = filter(maskFloat, w, h, bandPx, min = true)
            var band = BooleanArray(n) { mask[it] && eroded[it] < 0.5f }
            if (band.count { it } < (n * 0.002f).toInt()) {
                band = mask.copyOf()
            }
            val bandCount = band.count { it }
            val bandPercent = bandPx * 100 / max(w, h)

            // 前景 alpha：**不羽化**。放大时的双线性采样自带 ~1px 抗锯齿；
            // 羽化带会把两层不同位移的内容混合 → "液态玻璃"重影。
            val alpha = maskFloat

            // 3) 整块主体脚印做几何预填补：给模型"背景状"上下文，
            //    避免它把主体纹理当成参考继续画下去
            stage = "bgFill"
            val colors = readColors(img, w, h)
            val footprint = filter(maskFloat, w, h, 2, min = false)
            val validFoot = FloatArray(n) { if (footprint[it] > 0.5f) 0f else 1f }
            var fill = pushPull(colors.copyOf(), validFoot, w, h)

            // 4) AI 只补环带（洞小 → 内容更实）
            stage = "ai"
            var aiFilled = false
            val aiStart = System.currentTimeMillis()
            if (bandCount > 0) {
                val ai = InpaintEstimator.inpaint(context, fill, band, w, h)
                if (ai != null) {
                    fill = ai
                    aiFilled = true
                }
            }
            val aiMs = System.currentTimeMillis() - aiStart

            // 5) 补全区常态柔化（只在脚印内；脚印外永远是原图，静态画面不受影响）
            stage = "soften"
            val softened = boxBlur(fill, w, h, 2)
            val bgColor = fill.copyOf()
            for (i in 0 until n) {
                val o = i * 3
                if (footprint[i] > 0.5f) {
                    bgColor[o] = fill[o] * 0.35f + softened[o] * 0.65f
                    bgColor[o + 1] = fill[o + 1] * 0.35f + softened[o + 1] * 0.65f
                    bgColor[o + 2] = fill[o + 2] * 0.35f + softened[o + 2] * 0.65f
                } else {
                    bgColor[o] = colors[o]
                    bgColor[o + 1] = colors[o + 1]
                    bgColor[o + 2] = colors[o + 2]
                }
            }

            // 6) 背景层深度：由外部向内的"最近值"平坦外延。
            //    push-pull 会在脚印内形成碗形 → 背景被透镜式弯折、直线变弧。
            stage = "bgDepth"
            val validBg = FloatArray(n) { if (footprint[it] > 0.5f) 0f else 1f }
            val bgDepth = nearestFill(d, validBg, w, h)

            // 7) 前景层深度：遮罩内 = 原始深度，遮罩外 = 边缘外延（无悬崖）
            stage = "fgDepth"
            val fgDepth = nearestFill(d, maskFloat, w, h)

            // 8) 输出
            stage = "output"
            val bgPixels = IntArray(n)
            val bgDepthPixels = IntArray(n)
            val fgDepthPixels = IntArray(n)
            val alphaPixels = IntArray(n)
            for (i in 0 until n) {
                bgPixels[i] = argb(bgColor[i * 3], bgColor[i * 3 + 1], bgColor[i * 3 + 2])
                bgDepthPixels[i] = gray((bgDepth[i].coerceIn(0f, 1f) * 255f).roundToInt())
                fgDepthPixels[i] = gray((fgDepth[i].coerceIn(0f, 1f) * 255f).roundToInt())
                alphaPixels[i] = gray((alpha[i].coerceIn(0f, 1f) * 255f).roundToInt())
            }

            val bgImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgImage.setPixels(bgPixels, 0, w, 0, 0, w, h)
            val bgDepthBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bgDepthBitmap.setPixels(bgDepthPixels, 0, w, 0, 0, w, h)
            val fgDepthBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fgDepthBitmap.setPixels(fgDepthPixels, 0, w, 0, 0, w, h)
            val fgAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fgAlpha.setPixels(alphaPixels, 0, w, 0, 0, w, h)

            val coverage = maskCount * 100 / n
            val note = when {
                depthRange < 0.04f -> "\u6df1\u5ea6\u5bf9\u6bd4\u5ea6\u4e0d\u8db3\uff08${(depthRange * 100).roundToInt()}%\uff09\uff0c\u6309\u5355\u5c42\u6e32\u67d3"
                aiFilled -> "\u4e3b\u4f53 $coverage% \u00b7 \u73af\u5e26 ${bandPx}px(${bandPercent}%) \u00b7 AI ${aiMs}ms"
                else -> "\u4e3b\u4f53 $coverage% \u00b7 \u73af\u5e26 ${bandPx}px \u00b7 AI \u4e0d\u53ef\u7528\uff0c\u5df2\u7528\u51e0\u4f55\u8865\u5168"
            }
            Log.i(TAG, "\u8f6f\u5206\u5c42\u5b8c\u6210: ${w}x$h, $note")

            return Result(bgImage, bgDepthBitmap, fgAlpha, fgDepthBitmap, aiFilled, note)
        } catch (t: Throwable) {
            val frames = t.stackTrace.take(4).joinToString(" < ") {
                "${it.fileName ?: "?"}:${it.lineNumber}"
            }
            lastFailure = "$stage: ${t.javaClass.simpleName}: ${t.message} @ $frames"
            Log.e(TAG, "\u8f6f\u5206\u5c42\u5931\u8d25 @ $stage", t)
            return null
        }
    }

    /**
     * 最近有效值填充：以有效像素为源做 BFS，洞内每个像素取"最近有效像素"的值。
     * 相比 push-pull 更"硬"：洞内深度 = 周边背景深度（平坦外延），
     * 不形成碗形梯度 → 背景不会被透镜式弯折。
     */
    private fun nearestFill(src: FloatArray, valid: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val out = src.copyOf()
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) {
            if (valid[i] > 0.5f) {
                visited[i] = true
                queue[tail++] = i
            }
        }
        if (tail == 0) return out
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            val v = out[idx]
            if (x > 0 && !visited[idx - 1]) {
                visited[idx - 1] = true; out[idx - 1] = v; queue[tail++] = idx - 1
            }
            if (x < w - 1 && !visited[idx + 1]) {
                visited[idx + 1] = true; out[idx + 1] = v; queue[tail++] = idx + 1
            }
            if (y > 0 && !visited[idx - w]) {
                visited[idx - w] = true; out[idx - w] = v; queue[tail++] = idx - w
            }
            if (y < h - 1 && !visited[idx + w]) {
                visited[idx + w] = true; out[idx + w] = v; queue[tail++] = idx + w
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 工具

    private fun otsuThreshold(values: FloatArray): Float {
        val bins = 256
        val minV = values.minOrNull() ?: 0f
        val maxV = values.maxOrNull() ?: 1f
        val range = (maxV - minV).coerceAtLeast(1e-6f)
        val hist = IntArray(bins)
        for (v in values) {
            val b = (((v - minV) / range) * (bins - 1)).toInt().coerceIn(0, bins - 1)
            hist[b]++
        }
        val total = values.size
        var sum = 0.0
        for (i in 0 until bins) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var weightB = 0
        var best = -1.0
        var bestBin = bins / 2
        for (i in 0 until bins) {
            weightB += hist[i]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += i.toDouble() * hist[i]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > best) {
                best = between
                bestBin = i
            }
        }
        return minV + (bestBin + 0.5f) / bins * range
    }

    /** 只保留面积不小于 [minSize] 的连通块。 */
    private fun largestComponents(mask: BooleanArray, w: Int, h: Int, minSize: Int): BooleanArray {
        if (minSize <= 1) return mask
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val out = mask.copyOf()
        val component = ArrayList<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            component.clear()
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val idx = queue[head++]
                component.add(idx)
                val x = idx % w
                val y = idx / w
                if (x > 0 && mask[idx - 1] && !visited[idx - 1]) {
                    visited[idx - 1] = true; queue[tail++] = idx - 1
                }
                if (x < w - 1 && mask[idx + 1] && !visited[idx + 1]) {
                    visited[idx + 1] = true; queue[tail++] = idx + 1
                }
                if (y > 0 && mask[idx - w] && !visited[idx - w]) {
                    visited[idx - w] = true; queue[tail++] = idx - w
                }
                if (y < h - 1 && mask[idx + w] && !visited[idx + w]) {
                    visited[idx + w] = true; queue[tail++] = idx + w
                }
            }
            if (component.size < minSize) {
                for (idx in component) out[idx] = false
            }
        }
        return out
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
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw != w || bh != h) {
            throw IllegalStateException("位图尺寸 ${bw}x$bh 与目标 ${w}x$h 不一致")
        }
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
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
                val top = src[y0 * srcWidth + x0] + (src[y0 * srcWidth + x1] - src[y0 * srcWidth + x0]) * wx
                val bottom = src[y1 * srcWidth + x0] + (src[y1 * srcWidth + x1] - src[y1 * srcWidth + x0]) * wx
                out[y * dstWidth + x] = top + (bottom - top) * wy
            }
        }
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
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

    /**
     * 单通道（深度）版本的 push-pull：打包成 RGB 三通道再解包。
     * 注意 pushPull 内部按 `idx * 3` 寻址，直接传单通道数组会越界。
     */
    private fun pushPullGray(src: FloatArray, valid: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val packed = FloatArray(n * 3)
        for (i in 0 until n) {
            val v = src[i]
            packed[i * 3] = v
            packed[i * 3 + 1] = v
            packed[i * 3 + 2] = v
        }
        val filled = pushPull(packed, valid, w, h)
        val gray = FloatArray(n)
        for (i in 0 until n) gray[i] = filled[i * 3]
        return gray
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
