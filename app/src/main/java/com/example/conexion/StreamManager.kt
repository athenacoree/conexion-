package com.example.conexion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class StreamManager(private val context: Context) {
    private val tag = "StreamManager"

    private var audioRecordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var screenRecordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio stream sockets & tracks
    private var audioServerSocket: ServerSocket? = null
    private var audioClientSocket: Socket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile var isAudioStreaming = false

    // Screen stream sockets & projection
    private var screenServerSocket: ServerSocket? = null
    private var screenClientSocket: Socket? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile var isScreenStreaming = false

    // Walkie Talkie state
    @Volatile var isWalkieTalkieActive = false
    private var walkieTalkieRecord: AudioRecord? = null
    private var walkieTalkieTrack: AudioTrack? = null
    private var walkieServerSocket: ServerSocket? = null
    private var walkieClientSocket: Socket? = null

    // Callbacks
    var onFrameReceived: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    companion object {
        const val AUDIO_PORT = 8991
        const val SCREEN_PORT = 8992
        const val WALKIE_PORT = 8993
        private const val SAMPLE_RATE = 16000
    }

    // ==========================================
    // AUDIO STREAMING (Sender & Receiver)
    // ==========================================

    fun startAudioServer(port: Int = AUDIO_PORT) {
        if (isAudioStreaming) return
        isAudioStreaming = true
        audioRecordScope.launch {
            try {
                audioServerSocket = ServerSocket(port)
                Log.d(tag, "Audio server listening on port $port")

                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                while (isAudioStreaming) {
                    val socket = audioServerSocket?.accept() ?: break
                    audioClientSocket = socket
                    val dis = DataInputStream(socket.getInputStream())
                    val buffer = ByteArray(1024)

                    while (isAudioStreaming && !socket.isClosed) {
                        val readBytes = dis.read(buffer)
                        if (readBytes == -1) break
                        audioTrack?.write(buffer, 0, readBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Audio server error", e)
            } finally {
                stopAudioStream()
            }
        }
    }

    fun startAudioClient(remoteHost: String, port: Int = AUDIO_PORT) {
        if (isAudioStreaming) return
        isAudioStreaming = true
        audioRecordScope.launch {
            try {
                val minBufSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2
                )

                val socket = Socket(remoteHost, port)
                audioClientSocket = socket
                val dos = DataOutputStream(socket.getOutputStream())
                val buffer = ByteArray(minBufSize)

                audioRecord?.startRecording()
                Log.d(tag, "Audio client connected and recording to $remoteHost:$port")

                while (isAudioStreaming && !socket.isClosed) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        dos.write(buffer, 0, read)
                        dos.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Audio client error", e)
            } finally {
                stopAudioStream()
            }
        }
    }

    fun stopAudioStream() {
        isAudioStreaming = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        try { audioClientSocket?.close() } catch (e: Exception) {}
        try { audioServerSocket?.close() } catch (e: Exception) {}
        audioRecord = null
        audioTrack = null
        audioClientSocket = null
        audioServerSocket = null
    }

    // ==========================================
    // SCREEN STREAMING (Sender & Receiver)
    // ==========================================

    fun startScreenServer(port: Int = SCREEN_PORT) {
        if (isScreenStreaming) return
        isScreenStreaming = true
        screenRecordScope.launch {
            try {
                screenServerSocket = ServerSocket(port)
                Log.d(tag, "Screen receiver server listening on port $port")

                while (isScreenStreaming) {
                    val socket = screenServerSocket?.accept() ?: break
                    screenClientSocket = socket
                    val dis = DataInputStream(socket.getInputStream())

                    while (isScreenStreaming && !socket.isClosed) {
                        val length = dis.readInt()
                        if (length <= 0 || length > 10_000_000) break
                        val frameBytes = ByteArray(length)
                        dis.readFully(frameBytes)

                        val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                        if (bitmap != null) {
                            Handler(Looper.getMainLooper()).post {
                                onFrameReceived?.invoke(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Screen server error", e)
            } finally {
                stopScreenStream()
            }
        }
    }

    fun startScreenSender(
        projection: MediaProjection,
        remoteHost: String,
        port: Int = SCREEN_PORT,
        width: Int = 540,
        height: Int = 960,
        densityDpi: Int = 240
    ) {
        if (isScreenStreaming) return
        isScreenStreaming = true
        mediaProjection = projection

        screenRecordScope.launch {
            try {
                var socket: Socket? = null
                var attempts = 0
                while (isScreenStreaming && socket == null && attempts < 10) {
                    try {
                        attempts++
                        socket = Socket(remoteHost, port)
                    } catch (e: Exception) {
                        delay(500)
                    }
                }
                if (socket == null) {
                    Log.e(tag, "Failed to connect screen sender to $remoteHost:$port after $attempts attempts")
                    stopScreenStream()
                    return@launch
                }
                screenClientSocket = socket
                val dos = DataOutputStream(socket.getOutputStream())

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "ConexionScreenStream",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, null
                )

                var lastFrameTime = 0L

                imageReader?.setOnImageAvailableListener({ reader ->
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < 66) { // ~15 FPS
                        reader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    lastFrameTime = now

                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()

                        val baos = ByteArrayOutputStream()
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                        croppedBitmap.recycle()

                        val bytes = baos.toByteArray()
                        if (isScreenStreaming && !socket.isClosed) {
                            dos.writeInt(bytes.size)
                            dos.write(bytes)
                            dos.flush()
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error processing frame", e)
                    } finally {
                        image.close()
                    }
                }, Handler(Looper.getMainLooper()))

            } catch (e: Exception) {
                Log.e(tag, "Screen sender error", e)
                stopScreenStream()
            }
        }
    }

    fun stopScreenStream() {
        isScreenStreaming = false
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { screenClientSocket?.close() } catch (e: Exception) {}
        try { screenServerSocket?.close() } catch (e: Exception) {}
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
        screenClientSocket = null
        screenServerSocket = null
    }

    // ==========================================
    // WALKIE-TALKIE P2P INTERCOM
    // ==========================================

    fun startWalkieTalkieServer(port: Int = WALKIE_PORT) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                walkieServerSocket = ServerSocket(port)
                Log.d(tag, "Walkie-talkie server started")

                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                walkieTalkieTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                walkieTalkieTrack?.play()

                while (true) {
                    val socket = walkieServerSocket?.accept() ?: break
                    walkieClientSocket = socket
                    val dis = DataInputStream(socket.getInputStream())
                    val buf = ByteArray(1024)

                    while (!socket.isClosed) {
                        val read = dis.read(buf)
                        if (read == -1) break
                        walkieTalkieTrack?.write(buf, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Walkie server error", e)
            }
        }
    }

    fun sendWalkieTalkieChunk(remoteHost: String, pcmData: ByteArray, port: Int = WALKIE_PORT) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (walkieClientSocket == null || walkieClientSocket?.isClosed == true) {
                    walkieClientSocket = Socket(remoteHost, port)
                }
                val dos = DataOutputStream(walkieClientSocket!!.getOutputStream())
                dos.write(pcmData)
                dos.flush()
            } catch (e: Exception) {
                Log.e(tag, "Walkie send error", e)
            }
        }
    }

    fun stopAllStreams() {
        stopAudioStream()
        stopScreenStream()
        try { walkieTalkieTrack?.stop(); walkieTalkieTrack?.release() } catch (e: Exception) {}
        try { walkieClientSocket?.close(); walkieServerSocket?.close() } catch (e: Exception) {}
    }
}
