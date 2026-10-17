package com.danxing.spatialscene.depth

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MI-GAN 图像补全（ICCV 2023，MIT 许可，Places2 训练）。
 *
 * 模型自带前/后处理管线：
 * - 输入 image：uint8 RGB，形状 [1,3,H,W]（动态尺寸）
 * - 输入 mask：uint8 灰度，形状 [1,1,H,W]，**255 = 已知区域，0 = 待补全区域**
 * - 输出：uint8 RGB [1,3,H,W]，已知区域原样保留
 *
 * ⚠️ 必须使用 4 参数重载 createTensor(env, buffer, shape, OnnxJavaType.UINT8)：
 * 3 参数版本会把 ByteBuffer 解释成 int8，模型会报 "Unexpected input data type"。
 *
 * 为控制内存与耗时，最长边限制到 [MAX_LONG_SIDE]，结果再放大回原分辨率
 * （补的是新生成的背景内容，轻微放大不影响观感）。
 */
object InpaintEstimator {

    private const val TAG = "SpatialScene"
    private const val ASSET = "models/migan_pipeline_v2.onnx"
    private const val MAX_LONG_SIDE = 1024

    private class Loaded(
        val env: OrtEnvironment,
        val session: OrtSession,
        val imageName: String,
        val maskName: String
    )

    @Volatile private var cached: Loaded? = null
    @Volatile private var failed = false

    fun isAvailable(context: Context): Boolean = ensureLoaded(context) != null

    private fun ensureLoaded(context: Context): Loaded? {
        cached?.let { return it }
        if (failed) return null
        synchronized(this) {
            cached?.let { return it }
            if (failed) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                val options = OrtSession.SessionOptions()
                val session = env.createSession(bytes, options)

                var imageName: String? = null
                var maskName: String? = null
                for (name in session.inputNames) {
                    val info = (session.inputInfo[name]?.info as? TensorInfo)?.shape
                    val channels = info?.getOrNull(1) ?: -1L
                    when {
                        name.contains("image", ignoreCase = true) || channels == 3L -> imageName = name
                        name.contains("mask", ignoreCase = true) || channels == 1L -> maskName = name
                    }
                }
                if (imageName == null || maskName == null) {
                    Log.e(TAG, "补全模型输入无法识别: ${session.inputNames}")
                    failed = true
                    return null
                }
                Log.i(TAG, "补全模型已加载: image=$imageName mask=$maskName")
                Loaded(env, session, imageName, maskName).also { cached = it }
            } catch (t: Throwable) {
                failed = true
                Log.e(TAG, "补全模型加载失败: ${t.message}", t)
                null
            }
        }
    }

    /**
     * 对 [colors]（RGB，每像素 3 个 float，范围 0..1，长度 = width*height*3）中
     * [hole] 为 true 的像素做 AI 补全；返回同尺寸 RGB float 数组，失败返回 null。
     *
     * 建议 [colors] 传已做几何预填补的图（这样网络看到的是背景状上下文，
     * 不会把前景颜色"糊"进被遮挡区域）。
     */
    fun inpaint(
        context: Context,
        colors: FloatArray,
        hole: BooleanArray,
        width: Int,
        height: Int
    ): FloatArray? {
        val loaded = ensureLoaded(context) ?: return null
        if (colors.size < width * height * 3 || hole.size < width * height) return null

        val scale = min(1f, MAX_LONG_SIDE.toFloat() / max(width, height))
        val mw = max(8, (width * scale).roundToInt())
        val mh = max(8, (height * scale).roundToInt())

        return try {
            val imageBuffer = ByteBuffer.allocateDirect(mw * mh * 3).order(ByteOrder.nativeOrder())
            val maskBuffer = ByteBuffer.allocateDirect(mw * mh).order(ByteOrder.nativeOrder())

            // 图像按 NCHW 写入
            for (c in 0..2) {
                for (y in 0 until mh) {
                    val sy = (((y + 0.5f) * height) / mh).toInt().coerceIn(0, height - 1)
                    val rowBase = sy * width
                    for (x in 0 until mw) {
                        val sx = (((x + 0.5f) * width) / mw).toInt().coerceIn(0, width - 1)
                        val value = colors[(rowBase + sx) * 3 + c].coerceIn(0f, 1f)
                        imageBuffer.put((value * 255f).roundToInt().toByte())
                    }
                }
            }
            // 掩码：255 = 已知，0 = 待补全（最近邻采样）
            for (y in 0 until mh) {
                val sy = (((y + 0.5f) * height) / mh).toInt().coerceIn(0, height - 1)
                val rowBase = sy * width
                for (x in 0 until mw) {
                    val sx = (((x + 0.5f) * width) / mw).toInt().coerceIn(0, width - 1)
                    maskBuffer.put(if (hole[rowBase + sx]) 0 else 255.toByte())
                }
            }
            imageBuffer.rewind()
            maskBuffer.rewind()

            val started = System.currentTimeMillis()
            OnnxTensor.createTensor(
                loaded.env, imageBuffer,
                longArrayOf(1, 3, mh.toLong(), mw.toLong()), OnnxJavaType.UINT8
            ).use { imageTensor ->
                OnnxTensor.createTensor(
                    loaded.env, maskBuffer,
                    longArrayOf(1, 1, mh.toLong(), mw.toLong()), OnnxJavaType.UINT8
                ).use { maskTensor ->
                    val inputs = mapOf(loaded.imageName to imageTensor, loaded.maskName to maskTensor)
                    loaded.session.run(inputs).use { result ->
                        val output = result[0] as OnnxTensor
                        val buffer = output.byteBuffer
                        val pixels = mw * mh
                        if (buffer.remaining() < pixels * 3) {
                            Log.w(TAG, "补全输出尺寸异常: remaining=${buffer.remaining()}")
                            return null
                        }
                        // 先取出模型分辨率的 RGB，再双线性放大回原尺寸
                        val small = FloatArray(pixels * 3)
                        for (i in small.indices) {
                            small[i] = (buffer.get().toInt() and 0xFF) / 255f
                        }
                        val out = FloatArray(width * height * 3)
                        for (y in 0 until height) {
                            val fy = (((y + 0.5f) * mh) / height) - 0.5f
                            val y0 = fy.toInt().coerceIn(0, mh - 1)
                            val y1 = (y0 + 1).coerceIn(0, mh - 1)
                            val wy = (fy - y0).coerceIn(0f, 1f)
                            for (x in 0 until width) {
                                val fx = (((x + 0.5f) * mw) / width) - 0.5f
                                val x0 = fx.toInt().coerceIn(0, mw - 1)
                                val x1 = (x0 + 1).coerceIn(0, mw - 1)
                                val wx = (fx - x0).coerceIn(0f, 1f)
                                val dst = (y * width + x) * 3
                                for (c in 0..2) {
                                    val v00 = small[(y0 * mw + x0) * 3 + c]
                                    val v01 = small[(y0 * mw + x1) * 3 + c]
                                    val v10 = small[(y1 * mw + x0) * 3 + c]
                                    val v11 = small[(y1 * mw + x1) * 3 + c]
                                    val top = v00 + (v01 - v00) * wx
                                    val bottom = v10 + (v11 - v10) * wx
                                    out[dst + c] = top + (bottom - top) * wy
                                }
                            }
                        }
                        Log.i(
                            TAG,
                            "AI 补全完成: ${mw}x$mh -> ${width}x$height, " +
                                "${System.currentTimeMillis() - started} ms"
                        )
                        out
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AI 补全失败: ${t.message}", t)
            null
        }
    }
}
