package com.example.conexion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class AudioBeaconEmitter {
    private val tag = "AudioBeaconEmitter"
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private var playThread: Thread? = null

    private val sampleRate = 44100
    private val volumeFactor = 0.05f // Deliberately low volume (5% of max) as requested

    fun start(sessionToken: String) {
        if (isPlaying.getAndSet(true)) {
            Log.d(tag, "Emitter is already running")
            return
        }

        val hash = (sessionToken.hashCode() % 10000).let { if (it < 0) it + 10000 else it }
        val digits = hash.toString().padStart(4, '0').map { it.toString().toInt() }
        Log.d(tag, "Starting Audio Beacon Emitter for token: $sessionToken, hash digits: $digits")

        playThread = Thread {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            try {
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(tag, "Failed to start AudioTrack play", e)
                isPlaying.set(false)
                return@Thread
            }

            val startTime = System.currentTimeMillis()
            while (isPlaying.get() && (System.currentTimeMillis() - startTime) < 15_000) {
                // Play sync tone (18500 Hz) for 300 ms
                playTone(18500.0, 300)
                if (!isPlaying.get()) break

                // Play digits (17500 + digit * 100 Hz) for 200 ms each
                for (digit in digits) {
                    val freq = 17500.0 + (digit * 100.0)
                    playTone(freq, 200)
                    if (!isPlaying.get()) break
                    playSilence(100)
                    if (!isPlaying.get()) break
                }

                // Brief spacing silence for 100 ms
                playSilence(100)
            }

            stopInternal()
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun playTone(frequency: Double, durationMs: Int) {
        val track = audioTrack ?: return
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * i / (sampleRate / frequency)
            samples[i] = (sin(angle) * Short.MAX_VALUE * volumeFactor).toInt().toShort()
        }
        var written = 0
        while (isPlaying.get() && written < numSamples) {
            val res = track.write(samples, written, numSamples - written)
            if (res <= 0) break
            written += res
        }
    }

    private fun playSilence(durationMs: Int) {
        val track = audioTrack ?: return
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val samples = ShortArray(numSamples) // zeros
        var written = 0
        while (isPlaying.get() && written < numSamples) {
            val res = track.write(samples, written, numSamples - written)
            if (res <= 0) break
            written += res
        }
    }

    fun stop() {
        Log.d(tag, "Stopping Audio Beacon Emitter explicitly")
        isPlaying.set(false)
        playThread?.interrupt()
    }

    private fun stopInternal() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        isPlaying.set(false)
        Log.d(tag, "Audio Beacon Emitter stopped internal")
    }
}
