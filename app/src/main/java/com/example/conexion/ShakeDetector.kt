package com.example.conexion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.content.Intent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlin.math.sqrt

class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastShakeTime: Long = 0

    // Thresholds to detect shake and prevent false positives
    private val shakeThresholdGravity = 2.2f // ~22 m/s^2 total acceleration
    private val shakeSlopTimeMs = 2000 // 2 seconds between registered shakes

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate gravity-relative acceleration
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        // gForce will be close to 1 when still
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > shakeThresholdGravity) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > shakeSlopTimeMs) {
                lastShakeTime = now

                // Let the background service know we shook, even if the activity is paused!
                val intent = Intent(context, BackgroundDiscoveryService::class.java).apply {
                    action = BackgroundDiscoveryService.ACTION_UPDATE_SHAKE
                    putExtra(BackgroundDiscoveryService.EXTRA_SHAKE_TIMESTAMP, now)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    // Service might not be running or permitted; handle gracefully
                }

                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}
