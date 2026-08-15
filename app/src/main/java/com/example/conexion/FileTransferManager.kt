package com.example.conexion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class FileTransferManager(
    private val context: Context,
    private val dbHelper: DatabaseHelper,
    private val onIncomingFileRequest: (fileName: String, fileSize: Long, onAccept: () -> Unit, onReject: () -> Unit) -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: (fileName: String, bytesTransferred: Long, totalBytes: Long, isCompleted: Boolean) -> Unit
) {
    private val tag = "FileTransferManager"
    private val port = 8988

    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    // Keep track of client IP when a connection is received
    var lastClientIpAddress: String? = null

    // URI where the last incoming file was successfully saved
    var lastReceivedFileUri: Uri? = null

    // Callback when remote audio playback is requested
    var onAudioPlayRequested: ((Uri, String) -> Unit)? = null

    /**
     * Starts a TCP Server to listen for incoming file transfers.
     */
    suspend fun startServer() = withContext(Dispatchers.IO) {
        if (isServerRunning) return@withContext
        try {
            serverSocket = ServerSocket(port)
            isServerRunning = true
            Log.d(tag, "Server started on port $port")

            while (isServerRunning) {
                val clientSocket = serverSocket?.accept() ?: break
                Log.d(tag, "Client connected: ${clientSocket.inetAddress}")

                // Store client IP so GO can send files to this client
                lastClientIpAddress = clientSocket.inetAddress.hostAddress

                handleIncomingTransfer(clientSocket)
            }
        } catch (e: Exception) {
            Log.e(tag, "Server error", e)
            onError("Error en el servidor de transferencia de archivos: ${e.localizedMessage}")
        } finally {
            isServerRunning = false
        }
    }

    fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket", e)
        }
    }

    /**
     * Handles receiving a file over a client socket connection.
     */
    private suspend fun handleIncomingTransfer(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val inputStream = socket.getInputStream()
            val dataInputStream = DataInputStream(inputStream)

            // Read Metadata: File Name, File Size, and Start Offset (for Resumable Transfers)
            var rawFileName = dataInputStream.readUTF()
            val fileSize = dataInputStream.readLong()
            val startOffset = try { dataInputStream.readLong() } catch (e: Exception) { 0L }
            Log.d(tag, "Receiving file: $rawFileName, Size: $fileSize bytes, StartOffset: $startOffset")

            var shouldPlayRemotely = false
            var fileName = rawFileName
            if (rawFileName.startsWith("PLAY_REMOTE_AUDIO_")) {
                shouldPlayRemotely = true
                fileName = rawFileName.substring("PLAY_REMOTE_AUDIO_".length)
                Log.d(tag, "Detected remote audio play request for file: $fileName")
            }

            // Request user confirmation on Main Thread
            val confirmationDeferred = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                onIncomingFileRequest(
                    fileName,
                    fileSize,
                    { confirmationDeferred.complete(true) },
                    { confirmationDeferred.complete(false) }
                )
            }

            val isAccepted = confirmationDeferred.await()
            if (!isAccepted) {
                Log.d(tag, "Incoming file rejected by user: $fileName")
                dbHelper.saveTransferRecord(fileName, fileSize, "Remoto", true, "Rechazado")
                onError("Transferencia rechazada: $fileName")
                socket.close()
                return@withContext
            }

            // Create output URI in Downloads using MediaStore
            val outputStream = createDownloadOutputStream(fileName)
            if (outputStream == null) {
                Log.e(tag, "Failed to create output stream for received file")
                dbHelper.saveTransferRecord(fileName, fileSize, "Remoto", true, "Error al guardar")
                onError("Fallo al crear el archivo en Descargas. Verifica el espacio o permisos.")
                socket.close()
                return@withContext
            }

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesRead = 0L

            if (fileSize == -1L) {
                while (dataInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead.toLong()
                    onProgress(fileName, totalBytesRead, -1L, false)
                }
            } else {
                while (totalBytesRead < fileSize) {
                    val remaining = (fileSize - totalBytesRead).coerceAtMost(buffer.size.toLong()).toInt()
                    bytesRead = dataInputStream.read(buffer, 0, remaining)
                    if (bytesRead == -1) break

                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead.toLong()

                    onProgress(fileName, totalBytesRead, fileSize, false)
                }
            }

            outputStream.flush()
            outputStream.close()
            socket.close()

            Log.d(tag, "File received successfully: $fileName")
            dbHelper.saveTransferRecord(fileName, totalBytesRead, "Remoto", true, "Completado")
            onProgress(fileName, fileSize, fileSize, true)

            val savedUri = lastReceivedFileUri
            if (fileName.endsWith(".pmtiles", ignoreCase = true) && savedUri != null) {
                try {
                    val mapsDir = File(context.filesDir, "maps")
                    if (!mapsDir.exists()) mapsDir.mkdirs()
                    val targetFile = File(mapsDir, fileName)
                    context.contentResolver.openInputStream(savedUri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(tag, "Automatically imported received map file to: ${targetFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to import received map file", e)
                }
            }

            if (shouldPlayRemotely && savedUri != null) {
                withContext(Dispatchers.Main) {
                    Log.d(tag, "Triggering automatic remote audio play callback for: $fileName, URI: $savedUri")
                    onAudioPlayRequested?.invoke(savedUri, fileName)
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error during receiving file", e)
            onError("Error al recibir archivo: ${e.localizedMessage}")
        }
    }

    /**
     * Sends a file to the host specified by hostAddress.
     */
    suspend fun sendFile(hostAddress: String, fileUri: Uri, playRemoteAudio: Boolean = false, peerName: String = "Remoto"): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var attempts = 0
        val maxAttempts = 5
        var connected = false

        while (attempts < maxAttempts && !connected) {
            try {
                attempts++
                Log.d(tag, "Attempt $attempts: Connecting to $hostAddress:$port")
                socket = Socket(hostAddress, port)
                connected = true
            } catch (e: Exception) {
                Log.e(tag, "Socket connection attempt $attempts failed: ${e.message}")
                if (attempts < maxAttempts) {
                    kotlinx.coroutines.delay(1000)
                } else {
                    dbHelper.saveTransferRecord(getFileNameFromUri(fileUri) ?: "archivo", getFileSizeFromUri(fileUri), peerName, false, "Fallo de conexión")
                    onError("No se pudo establecer conexión de red con el destinatario después de $maxAttempts intentos.")
                    return@withContext false
                }
            }
        }

        try {
            val outputStream = socket!!.getOutputStream()
            val dataOutputStream = DataOutputStream(outputStream)

            // Resolve file name and size from Uri
            var fileName = getFileNameFromUri(fileUri) ?: "archivo_enviado.dat"
            val displayFileName = fileName
            if (playRemoteAudio) {
                fileName = "PLAY_REMOTE_AUDIO_" + fileName
                Log.d(tag, "Prepend PLAY_REMOTE_AUDIO_ prefix to filename: $fileName")
            }
            val fileSize = getFileSizeFromUri(fileUri)

            Log.d(tag, "Sending metadata: $fileName ($fileSize bytes)")
            dataOutputStream.writeUTF(fileName)
            dataOutputStream.writeLong(fileSize)
            dataOutputStream.writeLong(0L) // startOffset = 0L for fresh transfer

            val inputStream = context.contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                Log.e(tag, "Could not open InputStream for Uri: $fileUri")
                dbHelper.saveTransferRecord(displayFileName, fileSize, peerName, false, "Error de lectura")
                onError("No se pudo abrir el archivo seleccionado.")
                socket.close()
                return@withContext false
            }

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesSent = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                dataOutputStream.write(buffer, 0, bytesRead)
                totalBytesSent += bytesRead.toLong()
                onProgress(displayFileName, totalBytesSent, fileSize, false)
            }

            dataOutputStream.flush()
            inputStream.close()
            socket.close()

            Log.d(tag, "File sent successfully: $fileName")
            dbHelper.saveTransferRecord(displayFileName, fileSize, peerName, false, "Completado")
            onProgress(displayFileName, fileSize, fileSize, true)
            true
        } catch (e: Exception) {
            Log.e(tag, "Error sending file", e)
            onError("Error al enviar archivo: ${e.localizedMessage}")
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun createDownloadOutputStream(fileName: String): OutputStream? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            lastReceivedFileUri = uri
            uri?.let { context.contentResolver.openOutputStream(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to create media store record", e)
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = -1L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (index != -1) {
                    val resolvedSize = it.getLong(index)
                    if (resolvedSize > 0L) {
                        size = resolvedSize
                    }
                }
            }
        }
        return size
    }
}
