package com.danxing.spatialscene.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 姿态 → 虚拟相机偏移。
 *
 * 数学基础：把「屏幕法线」变换回参考姿态，取它的 x / y 分量：
 *
 *     n = R_refᵀ · z_cur
 *     camX = +gain · n.x
 *     camY = +gain · n.y
 *
 * 为什么这样映射：
 * - 旧实现用重力 (gx, gz)：**gz 的偏差是倾斜角的二阶小量（∝θ²）**，所以"上下"
 *   方向天生迟钝；而 n.x / n.y 对两个轴都是**一阶**（∝θ），手感一致，
 *   "角度转移"（手机像颠锅一样绕圈倾斜）时不会出现某些角度无感。
 * - 符号：实测旧实现四个方向全反。正确规则是**画面朝被压下去（远离眼睛）的那一侧移动**：
 *     下压（下端朝下）→ 画面下移 → 看到上方更多；
 *     上压 → 画面上移 → 看到下方更多；
 *     左压 → 画面左移 → 看到左侧更多；
 *     右压 → 画面右移 → 看到右侧更多。
 *
 * 静止回中：静止 0.35s 后，用 0.4s 做**一次连续平滑的回正**；回正期间忽略传感器
 * 目标值（避免和传感器打架），结束时把参考姿态对齐到当前姿态，所以不会跳变，
 * 也不会出现"回一点、停一下、再回一点"。
 */
class GyroCamera(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 灵敏度：倾斜约 20°（n≈0.34）≈ 0.19 NDC，再由渲染器按安全边距 clamp */
    @Volatile var sensitivity = 0.55f

    private val maxOffset = 0.30f

    /** 二阶临界阻尼弹簧 */
    private val stiffness = 150f
    private val damping = 2f * sqrt(stiffness)

    private val rotMatrix = FloatArray(9)
    private val prevMatrix = FloatArray(9)
    private val refMatrix = FloatArray(9)
    private var hasReference = false
    private var hasPrev = false

    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f

    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f

    // 静止检测 + 回中状态机
    private val stillDelay = 0.35f
    private val settleDuration = 0.40f
    private val motionThreshold = 0.010f
    private var stillTime = 0f
    private var slowX = 0f
    private var slowY = 0f
    private var settling = false
    private var settleTime = 0f
    private var settleFromX = 0f
    private var settleFromY = 0f

    val cameraX: Float get() = x
    val cameraY: Float get() = y

    fun setSensorActive(active: Boolean) {
        sensorManager.unregisterListener(this)
        if (!active) {
            hasReference = false
            hasPrev = false
            settling = false
            stillTime = 0f
            return
        }
        val s = sensor ?: return
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
        hasReference = false
        hasPrev = false
        settling = false
        stillTime = 0f
        slowX = 0f
        slowY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) {
            return
        }
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        if (!hasReference) {
            System.arraycopy(rotMatrix, 0, refMatrix, 0, 9)
            System.arraycopy(rotMatrix, 0, prevMatrix, 0, 9)
            hasReference = true
            hasPrev = true
            return
        }
        System.arraycopy(rotMatrix, 0, prevMatrix, 0, 9)
        hasPrev = true

        if (settling) return // 回正期间由 step() 接管，避免"回一点被推回去"

        // 屏幕法线在参考系中的方向：n = R_refᵀ · z_cur
        val zx = rotMatrix[2]
        val zy = rotMatrix[5]
        val zz = rotMatrix[8]
        val nx = refMatrix[0] * zx + refMatrix[3] * zy + refMatrix[6] * zz
        val ny = refMatrix[1] * zx + refMatrix[4] * zy + refMatrix[7] * zz

        targetX = (nx * sensitivity).coerceIn(-maxOffset, maxOffset)
        targetY = (ny * sensitivity).coerceIn(-maxOffset, maxOffset)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 推进弹簧、静止检测与回中动画，[dt] 为秒。每个渲染帧调用一次。 */
    fun step(dt: Float) {
        val d = dt.coerceIn(0f, 0.05f)

        // 静止检测：目标值与其慢速跟随值之差（对传感器噪声稳健，对缓慢漂移敏感）
        val follow = min(1f, d / 0.35f)
        slowX += (targetX - slowX) * follow
        slowY += (targetY - slowY) * follow
        val motion = abs(targetX - slowX) + abs(targetY - slowY)

        if (settling) {
            settleTime += d
            val t = (settleTime / settleDuration).coerceIn(0f, 1f)
            val k = t * t * (3f - 2f * t) // smoothstep
            targetX = settleFromX * (1f - k)
            targetY = settleFromY * (1f - k)
            if (t >= 1f) {
                // 回正完成：参考姿态对齐当前姿态，目标归零，不会跳变
                System.arraycopy(rotMatrix, 0, refMatrix, 0, 9)
                settling = false
                stillTime = 0f
                targetX = 0f
                targetY = 0f
                slowX = 0f
                slowY = 0f
            }
        } else {
            if (motion > motionThreshold) {
                stillTime = 0f
            } else {
                stillTime += d
                if (stillTime > stillDelay && (abs(targetX) > 0.003f || abs(targetY) > 0.003f)) {
                    settling = true
                    settleTime = 0f
                    settleFromX = targetX
                    settleFromY = targetY
                }
            }
        }

        vx += ((targetX - x) * stiffness - damping * vx) * d
        vy += ((targetY - y) * stiffness - damping * vy) * d
        x = (x + vx * d).coerceIn(-maxOffset, maxOffset)
        y = (y + vy * d).coerceIn(-maxOffset, maxOffset)
    }
}
