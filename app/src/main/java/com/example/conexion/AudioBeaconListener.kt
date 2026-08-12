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

        val targetHash = (targetToken.hashCode() % 10000).let { if (it < 0) it + 10000 else it }
        val targetDigits = targetHash.toString().padStart(4, '0').map { it.toString().toInt() }
        Log.d(tag, "Starting Audio Beacon Listener searching for target: $targetToken (digits: $targetDigits)")

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

            // Let's analyze buffers to detect sequence of frequencies.
            // A simple implementation of Goertzel algorithm to detect energy at:
            // 18500 Hz (Sync tone), and 17500..18400 Hz (digits 0..9).
            val freqsToDetect = DoubleArray(11)
            for (i in 0..9) {
                freqsToDetect[i] = 17500.0 + (i * 100.0)
            }
            freqsToDetect[10] = 18500.0 // Sync freq is index 10

            var lastSyncDetectedTime = 0L
            val detectedDigits = mutableListOf<Int>()
            var decodedSuccessfully = false

            var lastSeenTime = 0L

            while (isListening.get() && (System.currentTimeMillis() - startTime) < 15_000) {
                val readShorts = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readShorts <= 0) continue

                // Check energies
                val energies = DoubleArray(11)
                var maxEnergyIndex = -1
                var maxEnergy = 0.0

                for (idx in freqsToDetect.indices) {
                    val energy = goertzel(shortBuffer, readShorts, freqsToDetect[idx], sampleRate)
                    energies[idx] = energy
                    if (energy > maxEnergy) {
                        maxEnergy = energy
                        maxEnergyIndex = idx
                    }
                }

                // Noise floor vs detection threshold
                // Goertzel amplitude detection is relative. We look for a clear peak.
                val avgEnergy = energies.average()
                if (maxEnergy > 10.0 && maxEnergy > avgEnergy * 3.0) {
                    val now = System.currentTimeMillis()
                    if (maxEnergyIndex == 10) {
                        // Sync tone detected
                        if (now - lastSyncDetectedTime > 1000) {
                            Log.d(tag, "SYNC TONE detected! Resetting sequence.")
                            lastSyncDetectedTime = now
                            detectedDigits.clear()
                            lastSeenTime = 0L
                        }
                    } else if (lastSyncDetectedTime > 0 && now - lastSyncDetectedTime < 4000) {
                        // Digit tone detected
                        val digit = maxEnergyIndex
                        if (detectedDigits.isEmpty() || detectedDigits.last() != digit || now - lastSeenTime > 80) {
                            Log.d(tag, "Digit tone detected: $digit")
                            detectedDigits.add(digit)

                            if (detectedDigits.size == 4) {
                                // We have 4 digits!
                                val sequenceStr = detectedDigits.joinToString("")
                                val targetStr = targetDigits.joinToString("")
                                Log.d(tag, "Decoded sequence: $sequenceStr, target is: $targetStr")
                                if (sequenceStr == targetStr) {
                                    Log.d(tag, "SUCCESS! Target token matched.")
                                    decodedSuccessfully = true
                                    onTokenDecoded(targetToken)
                                    isListening.set(false)
                                    break
                                }
                            }
                        }
                        lastSeenTime = now
                    }
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
        val sine = sin(omega)
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0 = 0.0
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
