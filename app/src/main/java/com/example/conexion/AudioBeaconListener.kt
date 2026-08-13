package com.example.conexion

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

class AudioBeaconListener(
    private val onTokenDecoded: (String) -> Unit,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private val tag = "AudioBeaconListener"
    private val isListening = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val sampleRate = 44100

    @SuppressLint("MissingPermission")
    fun start(targetToken: String) {
        if (isListening.getAndSet(true)) {
            Log.d(tag, "Already listening")
            return
        }

        Log.d(tag, "Starting Audio Beacon Listener searching for presence of 18000 Hz for target: $targetToken")

        recordThread = Thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                Log.e(tag, "Permission RECORD_AUDIO not granted to AudioRecord", e)
                isListening.set(false)
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord could not be initialized")
                isListening.set(false)
                return@Thread
            }

            try {
                audioRecord.startRecording()
            } catch (e: Exception) {
                Log.e(tag, "Failed to start recording", e)
                isListening.set(false)
                return@Thread
            }

            val startTime = System.currentTimeMillis()
            val shortBuffer = ShortArray(1024)

            var consecutiveDetections = 0
            val requiredConsecutive = 5 // ~116ms of sustained tone
            var decodedSuccessfully = false

            while (isListening.get() && (System.currentTimeMillis() - startTime) < 15_000) {
                val readShorts = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readShorts <= 0) continue

                val targetEnergy = goertzel(shortBuffer, readShorts, 18000.0, sampleRate)

                // Calculate signal power (sum of squares) as a base for noise floor
                var sumSq = 0.0
                for (i in 0 until readShorts) {
                    val sample = shortBuffer[i] / 32768.0
                    sumSq += sample * sample
                }

                // Under white noise, Goertzel energy is approx sumSq.
                // Under pure tone of 18000 Hz, Goertzel energy is approx (readShorts / 2) * sumSq = 512 * sumSq.
                val avgEnergy = sumSq

                if (targetEnergy > 10.0 && targetEnergy > avgEnergy * 5.0) {
                    consecutiveDetections++
                    Log.d(tag, "18000 Hz tone detected: Energy: $targetEnergy, AvgEnergy (sumSq): $avgEnergy, Consecutive: $consecutiveDetections")
                    if (consecutiveDetections >= requiredConsecutive) {
                        Log.d(tag, "SUCCESS! Sustained presence of 18000 Hz detected.")
                        decodedSuccessfully = true
                        onTokenDecoded(targetToken)
                        isListening.set(false)
                        break
                    }
                } else {
                    consecutiveDetections = 0
                }
            }

            try {
                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                // ignore
            }
            isListening.set(false)
            onFinished?.invoke(decodedSuccessfully)
            Log.d(tag, "Audio Beacon Listener stopped")
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        Log.d(tag, "Stopping Audio Beacon Listener explicitly")
        isListening.set(false)
        recordThread?.interrupt()
    }

    private fun goertzel(samples: ShortArray, numSamples: Int, targetFreq: Double, sr: Int): Double {
        val k = (0.5 + (numSamples * targetFreq / sr)).toInt()
        val omega = (2.0 * Math.PI * k) / numSamples
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0: Double
        var q1 = 0.0
        var q2 = 0.0

        for (i in 0 until numSamples) {
            val normalizedSample = samples[i] / 32768.0
            q0 = normalizedSample + coeff * q1 - q2
            q2 = q1
            q1 = q0
        }

        return q1 * q1 + q2 * q2 - coeff * q1 * q2
    }
}
