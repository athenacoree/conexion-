package com.example.conexion

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf

class RemoteAudioPlayer(private val context: Context) {
    private val tag = "RemoteAudioPlayer"
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying = mutableStateOf(false)
    var currentTrackName = mutableStateOf("")

    fun play(uri: Uri, trackName: String) {
        try {
            stop()
            currentTrackName.value = trackName
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                start()
            }
            isPlaying.value = true
            mediaPlayer?.setOnCompletionListener {
                isPlaying.value = false
                currentTrackName.value = ""
            }
            Log.d(tag, "Started remote audio playback for: $trackName")
        } catch (e: Exception) {
            Log.e(tag, "Error playing remote audio: ${e.localizedMessage}", e)
            isPlaying.value = false
            currentTrackName.value = ""
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
                isPlaying.value = false
            } else {
                player.start()
                isPlaying.value = true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error toggling play/pause: ${e.localizedMessage}", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping media player: ${e.localizedMessage}", e)
        } finally {
            mediaPlayer = null
            isPlaying.value = false
            currentTrackName.value = ""
        }
    }
}
