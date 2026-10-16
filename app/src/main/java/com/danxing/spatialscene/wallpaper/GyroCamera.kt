package com.danxing.spatialscene.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 把设备旋转（陀螺仪 / 旋转矢量）转换成微小、平滑的虚拟相机偏移。
 *
 * 壁纸被视为一扇"窗口"：手机倾斜时，虚拟相机平移几格百分点，
 * 近层移动幅度大于远层，从而产生视差。
 */
class GyroCamera(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 灵敏度：每弧度旋转对应的 NDC 偏移量 */
    private val sensitivity = 0.30f

    /** 相机偏移硬上限，确保永远不会露出画面边缘 */
    private val maxOffset = 0.06f

    /** 死区：忽略约 0.5° 以下的微抖动 */
    private val deadZone = 0.009f

    /** 二阶临界阻尼弹簧参数 */
    private val stiffness = 90f
    private val damping = 2f * sqrt(stiffness)

    // 目标值（传感器线程写入）
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    @Volatile private var targetRoll = 0f

    // 平滑值（渲染线程读写）
    private var x = 0f
    private var y = 0f
    private var roll = 0f
    private var vx = 0f
    private var vy = 0f
    private var vRoll = 0f

    private val refMatrix = FloatArray(9)
    private val curMatrix = FloatArray(9)
    private val deltaMatrix = FloatArray(9)
    private var hasReference = false

    val cameraX: Float get() = x
    val cameraY: Float get() = y
    val cameraRoll: Float get() = roll

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
        targetRoll = 0f
        hasReference = false
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

        // 从相对旋转矩阵中提取小角度（delta ≈ I + [w]×）
        val wx = 0.5f * (deltaMatrix[7] - deltaMatrix[5])
        val wy = 0.5f * (deltaMatrix[2] - deltaMatrix[6])
        val wz = 0.5f * (deltaMatrix[3] - deltaMatrix[1])

        targetX = (applyDeadZone(wy) * sensitivity).coerceIn(-maxOffset, maxOffset)
        targetY = (applyDeadZone(wx) * sensitivity).coerceIn(-maxOffset, maxOffset)
        targetRoll = (applyDeadZone(wz) * sensitivity * 0.6f).coerceIn(-0.05f, 0.05f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 推进弹簧模拟，[dt] 为秒。每个渲染帧调用一次。 */
    fun step(dt: Float) {
        val d = dt.coerceIn(0f, 0.05f)
        vx += ((targetX - x) * stiffness - damping * vx) * d
        vy += ((targetY - y) * stiffness - damping * vy) * d
        vRoll += ((targetRoll - roll) * stiffness - damping * vRoll) * d
        x = (x + vx * d).coerceIn(-maxOffset, maxOffset)
        y = (y + vy * d).coerceIn(-maxOffset, maxOffset)
        roll = (roll + vRoll * d).coerceIn(-0.05f, 0.05f)
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
}
