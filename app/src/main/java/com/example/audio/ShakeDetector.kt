package com.example.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.data.preferences.ShakeSensitivity
import kotlin.math.sqrt

/**
 * Hardware accelerometer shake detector for resetting the sleep timer and resuming audiobook playback.
 * Supports three sensitivity levels configured by the user:
 * - [ShakeSensitivity.LOW] ("Слабый")
 * - [ShakeSensitivity.MEDIUM] ("Умеренно")
 * - [ShakeSensitivity.HIGH] ("Сильно")
 */
class ShakeDetector(
    context: Context,
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var sensitivity: ShakeSensitivity = ShakeSensitivity.MEDIUM

    private var isRegistered: Boolean = false
    private var lastShakeTimestampMs: Long = 0L
    private var firstPeakTimestampMs: Long = 0L
    private var peakCount: Int = 0

    fun startListening() {
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        if (isRegistered) return
        runCatching {
            isRegistered = manager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    fun stopListening() {
        val manager = sensorManager ?: return
        if (!isRegistered) return
        runCatching {
            manager.unregisterListener(this)
        }
        isRegistered = false
        peakCount = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        val threshold = sensitivity.thresholdG

        if (gForce >= threshold) {
            val now = System.currentTimeMillis()
            // Cooldown after a full shake event
            if (now - lastShakeTimestampMs < COOLDOWN_MS) return

            if (now - firstPeakTimestampMs > SHAKE_WINDOW_MS) {
                firstPeakTimestampMs = now
                peakCount = 1
            } else if (now - firstPeakTimestampMs >= MIN_PEAK_SEPARATION_MS) {
                peakCount++
            }

            if (peakCount >= REQUIRED_PEAKS) {
                lastShakeTimestampMs = now
                peakCount = 0
                onShakeDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SHAKE_WINDOW_MS = 950L
        private const val MIN_PEAK_SEPARATION_MS = 90L
        private const val COOLDOWN_MS = 1_600L
        private const val REQUIRED_PEAKS = 2
    }
}
