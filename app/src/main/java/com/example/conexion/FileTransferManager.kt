package com.example.conexion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class FileTransferManager(
    private val context: Context,
    private val onProgress: (fileName: String, bytesTransferred: Long, totalBytes: Long, isCompleted: Boolean) -> Unit
) {
    private val tag = "FileTransferManager"
    private val port = 8988

    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    // Keep track of client IP when a connection is received
    var lastClientIpAddress: String? = null

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
     * Handles receiving a file over a client socket connections.
     */
    private suspend fun handleIncomingTransfer(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val inputStream = socket.getInputStream()
            val dataInputStream = DataInputStream(inputStream)

            // Read Metadata: File Name and File Size
            val fileName = dataInputStream.readUTF()
            val fileSize = dataInputStream.readLong()
            Log.d(tag, "Receiving file: $fileName, Size: $fileSize bytes")

            // Create output URI in Downloads using MediaStore
            val outputStream = createDownloadOutputStream(fileName)
            if (outputStream == null) {
                Log.e(tag, "Failed to create output stream for received file")
                socket.close()
                return@withContext
            }

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (totalBytesRead < fileSize) {
                val remaining = (fileSize - totalBytesRead).coerceAtMost(buffer.size.toLong()).toInt()
                bytesRead = dataInputStream.read(buffer, 0, remaining)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead.toLong()

                onProgress(fileName, totalBytesRead, fileSize, false)
            }

            outputStream.flush()
            outputStream.close()
            socket.close()

            Log.d(tag, "File received successfully: $fileName")
            onProgress(fileName, fileSize, fileSize, true)

        } catch (e: Exception) {
            Log.e(tag, "Error during receiving file", e)
        }
    }

    /**
     * Sends a file to the host specified by hostAddress.
     */
    suspend fun sendFile(hostAddress: String, fileUri: Uri) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            Log.d(tag, "Attempting to send file to $hostAddress:$port")
            socket = Socket(hostAddress, port)

            val outputStream = socket.getOutputStream()
            val dataOutputStream = DataOutputStream(outputStream)

            // Resolve file name and size from Uri
            val fileName = getFileNameFromUri(fileUri) ?: "archivo_enviado.dat"
            val fileSize = getFileSizeFromUri(fileUri)

            Log.d(tag, "Sending metadata: $fileName ($fileSize bytes)")
            dataOutputStream.writeUTF(fileName)
            dataOutputStream.writeLong(fileSize)

            val inputStream = context.contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                Log.e(tag, "Could not open InputStream for Uri: $fileUri")
                socket.close()
                return@withContext
            }

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesSent = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                dataOutputStream.write(buffer, 0, bytesRead)
                totalBytesSent += bytesRead.toLong()
                onProgress(fileName, totalBytesSent, fileSize, false)
            }

            dataOutputStream.flush()
            inputStream.close()
            socket.close()

            Log.d(tag, "File sent successfully: $fileName")
            onProgress(fileName, fileSize, fileSize, true)

        } catch (e: Exception) {
            Log.e(tag, "Error sending file", e)
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
        var size = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (index != -1) {
                    size = it.getLong(index)
                }
            }
        }
        return size
    }
}
