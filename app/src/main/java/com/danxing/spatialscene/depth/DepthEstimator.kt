package com.danxing.spatialscene.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 端侧单目深度估计（Depth Anything V2 Small）。
 *
 * 输入：pixel_values [1,3,H,W] float32（ImageNet 归一化，H/W 为 14 的倍数）
 * 输出：predicted_depth [1,H,W] float32（近处数值大）
 *
 * 优先尝试 NNAPI（高通 Hexagon），失败则回退 CPU/XNNPACK。
 */
class DepthEstimator(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        session = createSession(context)
        inputName = session.inputNames.iterator().next()
        Log.i(TAG, "深度模型已加载, input=$inputName, inputs=${session.inputInfo}")
    }

    private fun createSession(context: Context): OrtSession {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }

        if (TRY_NNAPI) {
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi()
                }
                return env.createSession(modelBytes, nnapiOptions).also {
                    Log.i(TAG, "使用 NNAPI 执行提供器")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI 不可用，回退 CPU/XNNPACK", t)
            }
        }

        val cpuOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(modelBytes, cpuOptions).also {
            Log.i(TAG, "使用 CPU/XNNPACK 执行提供器")
        }
    }

    /**
     * 对一张 [bitmap] 估计深度。
     * 注意：调用方应保证在后台线程执行（推理耗时从数百毫秒到数秒）。
     */
    fun estimate(bitmap: Bitmap): DepthResult {
        val totalStart = SystemClock.elapsedRealtime()

        val netWidth = roundDown14((bitmap.width * scaleFor(bitmap)).roundToInt())
        val netHeight = roundDown14((bitmap.height * scaleFor(bitmap)).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, netWidth, netHeight, true)

        val floatBuffer = toNormalizedChwBuffer(scaled)

        val runStart = SystemClock.elapsedRealtime()
        val depth: FloatArray = synchronized(this) {
            OnnxTensor.createTensor(
                env, floatBuffer,
                longArrayOf(1, 3, netWidth.toLong(), netHeight.toLong())
            ).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    val tensor = result[0] as OnnxTensor
                    val raw = tensor.floatBuffer
                    val count = raw.limit()
                    require(count == netWidth * netHeight) {
                        "深度输出尺寸异常: $count != ${netWidth * netHeight}"
                    }
                    FloatArray(count).also { raw.get(it, 0, count) }
                }
            }
        }
        val runMs = SystemClock.elapsedRealtime() - runStart

        val normalized = normalizeRobust(depth, netWidth, netHeight)
        val depthBitmap = toGrayBitmap(normalized, netWidth, netHeight)

        // 显示用：缩放到最长边 1024，避免大图占内存
        val displayBitmap = scaleDown(depthBitmap, 1024)

        return DepthResult(
            depthBitmap = displayBitmap,
            depthValues = normalized,
            width = netWidth,
            height = netHeight,
            runMs = runMs,
            totalMs = SystemClock.elapsedRealtime() - totalStart
        )
    }

    fun close() {
        session.close()
    }

    private fun scaleFor(bitmap: Bitmap): Float {
        val longSide = max(bitmap.width, bitmap.height)
        return if (longSide <= 0) 1f else (TARGET_LONG_SIDE.toFloat() / longSide)
    }

    private fun roundDown14(value: Int): Int =
        (value.coerceAtLeast(14) / 14) * 14

    /** 归一化为 CHW float 缓冲。 */
    private fun toNormalizedChwBuffer(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val plane = width * height
        val buffer = FloatBuffer.allocate(plane * 3)
        buffer.position(0)
        // 先写 R 通道，再 G、B
        for (index in pixels.indices) {
            val p = pixels[index]
            buffer.put(index, (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
            buffer.put(plane + index, (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1])
            buffer.put(2 * plane + index, ((p and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        buffer.position(0)
        return buffer
    }

    /** 1%~99% 分位裁剪的稳健归一化，避免极端值压缩动态范围。 */
    private fun normalizeRobust(values: FloatArray, width: Int, height: Int): FloatArray {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in values) {
            if (v < min) min = v
            if (v > max) max = v
        }
        if (max - min < 1e-6f) return FloatArray(values.size) { 0.5f }

        val bins = 512
        val histogram = IntArray(bins)
        val scale = (bins - 1) / (max - min)
        for (v in values) {
            val bin = ((v - min) * scale).toInt().coerceIn(0, bins - 1)
            histogram[bin]++
        }
        val total = values.size
        val lowTarget = (total * 0.01f).toInt()
        val highTarget = (total * 0.99f).toInt()
        var cumulative = 0
        var lowBin = 0
        var highBin = bins - 1
        for (i in 0 until bins) {
            cumulative += histogram[i]
            if (cumulative >= lowTarget) {
                lowBin = i
                break
            }
        }
        cumulative = 0
        for (i in 0 until bins) {
            cumulative += histogram[i]
            if (cumulative >= highTarget) {
                highBin = i
                break
            }
        }
        val low = min + lowBin / scale
        val high = min + highBin / scale
        val range = (high - low).coerceAtLeast(1e-6f)

        val out = FloatArray(values.size)
        for (i in values.indices) {
            out[i] = ((values[i] - low) / range).coerceIn(0f, 1f)
        }
        return out
    }

    private fun toGrayBitmap(values: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = (values[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun scaleDown(bitmap: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxLongSide) return bitmap
        val scale = maxLongSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    data class DepthResult(
        val depthBitmap: Bitmap,
        val depthValues: FloatArray,
        val width: Int,
        val height: Int,
        val runMs: Long,
        val totalMs: Long
    )

    companion object {
        private const val TAG = "SpatialScene"
        private const val MODEL_ASSET = "models/depth_anything_v2_small_fp16.onnx"

        /**
         * NNAPI 在部分机型上对 fp16 图支持不完整，先默认关闭；
         * M2 做性能对比时再开。
         */
        private const val TRY_NNAPI = false

        /** 最长边目标值，需为 14 的倍数：518 = 37 * 14 */
        private const val TARGET_LONG_SIDE = 518

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
