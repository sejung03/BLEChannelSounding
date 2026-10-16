package com.example.blecsranger.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.blecsranger.model.ImuSample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class ImuSampler(
    context: Context,
    private val onSample: (ImuSample) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable, SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val valueLock = Any()

    private var acceleration: FloatArray? = null
    private var gyroscope: FloatArray? = null
    private var gameRotationVector: FloatArray? = null
    private var magneticField: FloatArray? = null

    @Volatile
    private var running = false
    private var generation = 0L
    private var sensorThread: HandlerThread? = null
    private var samplingExecutor: ScheduledExecutorService? = null

    fun missingSensorNames(): List<String> = requiredSensors()
        .filter { it.sensor == null }
        .map { it.displayName }

    @Synchronized
    fun start() {
        stop()
        val required = requiredSensors()
        val missing = required.filter { it.sensor == null }.map { it.displayName }
        check(missing.isEmpty()) { "필수 센서 없음: ${missing.joinToString()}" }

        synchronized(valueLock) {
            acceleration = null
            gyroscope = null
            gameRotationVector = null
            magneticField = null
        }

        val thread = HandlerThread("imu-sensor-events").apply { start() }
        val handler = Handler(thread.looper)
        val registered = required.all { requiredSensor ->
            sensorManager.registerListener(
                this,
                checkNotNull(requiredSensor.sensor),
                SENSOR_SAMPLING_PERIOD_US,
                0,
                handler,
            )
        }
        if (!registered) {
            sensorManager.unregisterListener(this)
            thread.quitSafely()
            error("IMU 센서 리스너를 등록할 수 없습니다")
        }

        sensorThread = thread
        running = true
        generation += 1
        val activeGeneration = generation
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "imu-50hz-sampler")
        }
        samplingExecutor = executor

        var firstSampleEpochMs: Long? = null
        var sampleIndex = 0L
        executor.scheduleAtFixedRate(
            {
                if (!running || generation != activeGeneration) return@scheduleAtFixedRate
                try {
                    val values = snapshotCompleteValues() ?: return@scheduleAtFixedRate
                    val baseEpochMs = firstSampleEpochMs ?: System.currentTimeMillis().also {
                        firstSampleEpochMs = it
                    }
                    val sample = ImuSample(
                        absoluteTimeEpochMs = baseEpochMs + sampleIndex * SAMPLE_PERIOD_MS,
                        accelerationX = values.acceleration[0].toDouble(),
                        accelerationY = values.acceleration[1].toDouble(),
                        accelerationZ = values.acceleration[2].toDouble(),
                        gyroscopeX = values.gyroscope[0].toDouble(),
                        gyroscopeY = values.gyroscope[1].toDouble(),
                        gyroscopeZ = values.gyroscope[2].toDouble(),
                        rotationW = values.gameRotationVector[3].toDouble(),
                        rotationX = values.gameRotationVector[0].toDouble(),
                        rotationY = values.gameRotationVector[1].toDouble(),
                        rotationZ = values.gameRotationVector[2].toDouble(),
                        magneticX = values.magneticField[0].toDouble(),
                        magneticY = values.magneticField[1].toDouble(),
                        magneticZ = values.magneticField[2].toDouble(),
                    )
                    synchronized(this) {
                        if (!running || generation != activeGeneration) return@scheduleAtFixedRate
                        sampleIndex += 1
                        onSample(sample)
                    }
                } catch (error: Throwable) {
                    onError(error)
                }
            },
            0L,
            SAMPLE_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    fun stop() {
        running = false
        generation += 1
        samplingExecutor?.shutdownNow()
        samplingExecutor = null
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        synchronized(valueLock) {
            acceleration = null
            gyroscope = null
            gameRotationVector = null
            magneticField = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(valueLock) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> acceleration = event.values.copyOf(3)
                Sensor.TYPE_GYROSCOPE -> gyroscope = event.values.copyOf(3)
                Sensor.TYPE_GAME_ROTATION_VECTOR -> gameRotationVector = rotationQuaternion(event.values)
                Sensor.TYPE_MAGNETIC_FIELD -> magneticField = event.values.copyOf(3)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() = stop()

    private fun snapshotCompleteValues(): SensorValues? = synchronized(valueLock) {
        val accelerationCopy = acceleration?.copyOf() ?: return@synchronized null
        val gyroscopeCopy = gyroscope?.copyOf() ?: return@synchronized null
        val rotationCopy = gameRotationVector?.copyOf() ?: return@synchronized null
        val magneticCopy = magneticField?.copyOf() ?: return@synchronized null
        SensorValues(accelerationCopy, gyroscopeCopy, rotationCopy, magneticCopy)
    }

    private fun requiredSensors(): List<RequiredSensor> = listOf(
        RequiredSensor("3축 가속도", sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)),
        RequiredSensor("3축 자이로스코프", sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)),
        RequiredSensor("Game Rotation Vector", sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)),
        RequiredSensor("3축 지자기", sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)),
    )

    private fun rotationQuaternion(values: FloatArray): FloatArray {
        val x = values.getOrElse(0) { 0f }
        val y = values.getOrElse(1) { 0f }
        val z = values.getOrElse(2) { 0f }
        val w = if (values.size >= 4) {
            values[3]
        } else {
            sqrt(max(0f, 1f - x * x - y * y - z * z))
        }
        return floatArrayOf(x, y, z, w)
    }

    private data class RequiredSensor(
        val displayName: String,
        val sensor: Sensor?,
    )

    private data class SensorValues(
        val acceleration: FloatArray,
        val gyroscope: FloatArray,
        val gameRotationVector: FloatArray,
        val magneticField: FloatArray,
    )

    companion object {
        private const val SENSOR_SAMPLING_PERIOD_US = 10_000
        private const val SAMPLE_PERIOD_MS = 20L
    }
}
