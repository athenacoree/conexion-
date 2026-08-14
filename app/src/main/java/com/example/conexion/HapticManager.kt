package com.example.conexion

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticManager {

    val VIBRATION_MODES = listOf(
        "iPhone Double Pulse",
        "Continuous Wave",
        "Pulse Burst",
        "Gentle Click",
        "Strong Alarm"
    )

    fun performCustomVibration(context: Context, modeIndex: Int) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (timings, amplitudes) = when (modeIndex) {
                0 -> longArrayOf(0, 40, 60, 40) to intArrayOf(0, 255, 0, 180)
                1 -> longArrayOf(0, 120, 40, 120) to intArrayOf(0, 200, 0, 200)
                2 -> longArrayOf(0, 20, 30, 20, 30, 20) to intArrayOf(0, 255, 0, 255, 0, 255)
                3 -> longArrayOf(0, 15) to intArrayOf(0, 120)
                4 -> longArrayOf(0, 200, 100, 200) to intArrayOf(0, 255, 0, 255)
                else -> longArrayOf(0, 40, 60, 40) to intArrayOf(0, 255, 0, 180)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            val timings = when (modeIndex) {
                0 -> longArrayOf(0, 40, 60, 40)
                1 -> longArrayOf(0, 120, 40, 120)
                2 -> longArrayOf(0, 20, 30, 20, 30, 20)
                3 -> longArrayOf(0, 15)
                4 -> longArrayOf(0, 200, 100, 200)
                else -> longArrayOf(0, 40, 60, 40)
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    /**
     * Triggers an iPhone-like sharp double pulse vibration when ultrasonic beacon is detected
     * or when AirDrop peer is matched.
     */
    fun performIPhoneHaptic(context: Context) {
        val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val selectedMode = prefs.getInt("vibration_mode_index", 0)
        performCustomVibration(context, selectedMode)
    }

    /**
     * Proximity vibration pulse triggered when devices are within configured reach.
     */
    fun performProximityHaptic(context: Context) {
        val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val selectedMode = prefs.getInt("vibration_mode_index", 0)
        performCustomVibration(context, selectedMode)
    }

    /**
     * Light haptic click for button presses / sonar nodes tap
     */
    fun performLightClick(context: Context) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
