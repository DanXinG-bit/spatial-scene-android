package com.danxing.spatialscene.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 把设备姿态（旋转矢量）转换成平滑的虚拟相机偏移。
 *
 * 轴映射（M5 修正）：
 * - 左右：使用「侧倾 roll」而不是 yaw —— 用户左右倾斜手机时一眼就能看到反应；
 *   yaw 仅贡献很小一部分，避免旋转手机时画面乱窜。
 * - 上下：使用「俯仰 pitch」。
 * 横向/纵向的像素级均衡由渲染器按屏幕宽高比补偿（竖屏时纵向 NDC 位移更显眼）。
 *
 * 静止回中：一段时间没有姿态变化后，目标值缓慢衰减到 0，画面自动回正。
 */
class GyroCamera(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 灵敏度：每弧度旋转对应的 NDC 偏移量（由渲染器按用户设置调整） */
    @Volatile
    var sensitivity = 0.55f

    /** 相机偏移的宽松上限（真正的安全 clamp 在渲染器里按画布边距计算） */
    private val maxOffset = 0.30f

    /** 死区：忽略约 0.35° 以下的微抖动 */
    private val deadZone = 0.006f

    /** 二阶临界阻尼弹簧：比 M0 更快跟手 */
    private val stiffness = 150f
    private val damping = 2f * sqrt(stiffness)

    /** 静止判定与回中 */
    private val stillThreshold = 0.004f
    private val stillDelay = 1.1f
    private val recenterTau = 0.5f

    // 目标值（传感器线程写入）
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f

    // 平滑值（渲染线程读写）
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f

    private var stillTime = 0f

    private val refMatrix = FloatArray(9)
    private val curMatrix = FloatArray(9)
    private val deltaMatrix = FloatArray(9)
    private var hasReference = false

    val cameraX: Float get() = x
    val cameraY: Float get() = y

    fun setSensorActive(active: Boolean) {
        sensorManager.unregisterListener(this)
        if (!active) {
            hasReference = false
            return
        }
        val sensor = rotationSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** 回到中位（切回屏幕时避免上一次姿态造成的跳变） */
    fun reset() {
        targetX = 0f
        targetY = 0f
        hasReference = false
        stillTime = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        SensorManager.getRotationMatrixFromVector(curMatrix, event.values)

        if (!hasReference) {
            System.arraycopy(curMatrix, 0, refMatrix, 0, 9)
            hasReference = true
            return
        }

        multiplyTranspose(refMatrix, curMatrix, deltaMatrix)

        // 从相对旋转矩阵中提取小角度（设备坐标系，delta ≈ I + [w]×）
        val pitch = applyDeadZone(0.5f * (deltaMatrix[7] - deltaMatrix[5])) // 绕设备 x 轴：抬头/低头
        val yaw = applyDeadZone(0.5f * (deltaMatrix[2] - deltaMatrix[6]))   // 绕设备 y 轴：左右转动
        val roll = applyDeadZone(0.5f * (deltaMatrix[3] - deltaMatrix[1]))  // 绕设备 z 轴：左右侧倾

        // 水平 = 侧倾为主 + 少量 yaw；垂直 = 俯仰
        targetX = ((roll + yaw * YAW_WEIGHT) * sensitivity).coerceIn(-maxOffset, maxOffset)
        targetY = (pitch * sensitivity).coerceIn(-maxOffset, maxOffset)

        if (abs(pitch) > stillThreshold || abs(roll) > stillThreshold) {
            stillTime = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 推进弹簧模拟与静止回中，[dt] 为秒。每个渲染帧调用一次。 */
    fun step(dt: Float) {
        val d = dt.coerceIn(0f, 0.05f)

        stillTime += d
        if (stillTime > stillDelay) {
            // 静止一段时间后缓慢回中（很多动态壁纸都这样做）
            val decay = exp(-d / recenterTau)
            targetX *= decay
            targetY *= decay
        }

        vx += ((targetX - x) * stiffness - damping * vx) * d
        vy += ((targetY - y) * stiffness - damping * vy) * d
        x = (x + vx * d).coerceIn(-maxOffset, maxOffset)
        y = (y + vy * d).coerceIn(-maxOffset, maxOffset)
    }

    private fun multiplyTranspose(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0..2) {
            for (c in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += a[k * 3 + r] * b[k * 3 + c]
                }
                out[r * 3 + c] = sum
            }
        }
    }

    private fun applyDeadZone(value: Float): Float =
        if (abs(value) < deadZone) 0f else value - deadZone * sign(value)

    companion object {
        /** yaw 的权重（仅作为侧倾的补充，避免旋转手机时画面乱窜） */
        private const val YAW_WEIGHT = 0.12f
    }
}
