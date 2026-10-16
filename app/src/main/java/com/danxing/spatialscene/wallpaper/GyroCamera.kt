package com.danxing.spatialscene.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 姿态 → 虚拟相机偏移。
 *
 * 使用**重力向量**（TYPE_GRAVITY，融合传感器，无漂移）而不是陀螺仪积分：
 * - 水平 = 重力 x 分量相对参考姿态的变化（左右侧倾）
 * - 垂直 = 重力 z 分量相对参考姿态的变化（上下俯仰）
 * 这样任何方向的倾斜都会立刻响应，且不会因为陀螺仪积分而"卡住/漂移"。
 *
 * 静止回中：检测到姿态稳定一段时间后，把**参考姿态**缓慢移向当前姿态，
 * 于是目标偏移自然衰减到 0，画面回正（不会与传感器打架）。
 */
class GyroCamera(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val usesRawAccelerometer: Boolean
        get() = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER

    /** 灵敏度：重力分量变化 1.0（约 90°）对应的 NDC 偏移 */
    @Volatile
    var sensitivity = 0.6f

    private val maxOffset = 0.30f

    /** 二阶临界阻尼弹簧 */
    private val stiffness = 150f
    private val damping = 2f * sqrt(stiffness)

    /** 静止判定：当前姿态与慢速跟随值的偏差 */
    private val motionThreshold = 0.035f
    private val recenterDelay = 0.45f
    private val recenterRate = 0.28f

    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f

    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f

    @Volatile private var stillTime = 0f

    private val refGravity = FloatArray(3)
    private val curGravity = FloatArray(3)
    private val filtered = FloatArray(3)
    private var hasReference = false
    private var filterInitialized = false

    private var slowX = 0f
    private var slowY = 0f

    val cameraX: Float get() = x
    val cameraY: Float get() = y

    fun setSensorActive(active: Boolean) {
        sensorManager.unregisterListener(this)
        if (!active) {
            hasReference = false
            return
        }
        val sensor = gravitySensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
        hasReference = false
        stillTime = 0f
        slowX = 0f
        slowY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY &&
            event.sensor.type != Sensor.TYPE_ACCELEROMETER
        ) return

        val raw = event.values
        val magnitude = sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2])
        if (magnitude < 0.1f) return

        if (usesRawAccelerometer) {
            // 加速度计含运动分量，做低通滤波近似重力
            val a = if (!filterInitialized) 1f else 0.15f
            if (!filterInitialized) filterInitialized = true
            for (i in 0..2) filtered[i] += (raw[i] / magnitude - filtered[i]) * a
            for (i in 0..2) curGravity[i] = filtered[i]
        } else {
            for (i in 0..2) curGravity[i] = raw[i] / magnitude
        }

        if (!hasReference) {
            System.arraycopy(curGravity, 0, refGravity, 0, 3)
            hasReference = true
            return
        }

        // 相对参考姿态的重力变化 → 倾斜量
        val tiltX = -(curGravity[0] - refGravity[0])
        val tiltY = -(curGravity[2] - refGravity[2])

        // 运动检测：与慢速跟随值的偏差
        slowX += (tiltX - slowX) * 0.03f
        slowY += (tiltY - slowY) * 0.03f
        val motion = abs(tiltX - slowX) + abs(tiltY - slowY)
        if (motion > motionThreshold) {
            stillTime = 0f
        }

        // 静止回中：把参考姿态缓慢移向当前姿态
        if (stillTime > recenterDelay) {
            for (i in 0..2) {
                refGravity[i] += (curGravity[i] - refGravity[i]) * recenterRate
            }
        }

        targetX = (tiltX * sensitivity).coerceIn(-maxOffset, maxOffset)
        targetY = (tiltY * sensitivity).coerceIn(-maxOffset, maxOffset)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 推进弹簧与静止计时，[dt] 为秒。每个渲染帧调用一次。 */
    fun step(dt: Float) {
        val d = dt.coerceIn(0f, 0.05f)
        stillTime += d

        vx += ((targetX - x) * stiffness - damping * vx) * d
        vy += ((targetY - y) * stiffness - damping * vy) * d
        x = (x + vx * d).coerceIn(-maxOffset, maxOffset)
        y = (y + vy * d).coerceIn(-maxOffset, maxOffset)
    }
}
