package com.example.conexion

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SessionManager(
    private val onMessageReceived: (String) -> Unit,
    private val onChatRequestReceived: (String, (Boolean) -> Unit) -> Unit,
    private val onChatRequestResponse: (Boolean) -> Unit,
    private val onContactRequestReceived: (String, (Boolean) -> Unit) -> Unit,
    private val onContactRequestResponse: (Boolean) -> Unit,
    private val onContactDataReceived: (name: String, phone: String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val tag = "SessionManager"
    private val port = 8989

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var outWriter: PrintWriter? = null

    // Screen sharing callbacks
    var onScreenShareStarted: ((peerName: String, resolution: String, fps: Int, quality: String) -> Unit)? = null
    var onScreenShareStopped: (() -> Unit)? = null

    fun startServer() {
        if (isRunning) return
        isRunning = true
        // Recreate CoroutineScope if previous was cancelled
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(tag, "Session server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(tag, "Session client connected: ${socket.inetAddress}")
                    clientSocket = socket
                    handleConnection(socket)
                }
            } catch (e: Exception) {
                Log.e(tag, "Server error", e)
                if (isRunning) onError("Error en el servidor de control: ${e.localizedMessage}")
            }
        }
    }

    fun connectToHost(hostAddress: String) {
        if (isRunning) return
        isRunning = true
        // Recreate CoroutineScope if previous was cancelled
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        scope.launch {
            var attempts = 0
            while (attempts < 5 && isRunning) {
                try {
                    Log.d(tag, "Connecting to control host $hostAddress:$port")
                    val socket = Socket(hostAddress, port)
                    clientSocket = socket
                    handleConnection(socket)
                    break
                } catch (e: Exception) {
                    attempts++
                    Log.e(tag, "Connection attempt $attempts failed", e)
                    delay(1500)
                }
            }
            if (clientSocket == null && isRunning) {
                onError("No se pudo conectar al dispositivo remoto.")
                isRunning = false
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            outWriter = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            while (isRunning) {
                val line = reader.readLine() ?: break
                Log.d(tag, "Control payload received: $line")
                parseControlMessage(line)
            }
        } catch (e: Exception) {
            Log.e(tag, "Connection error", e)
        } finally {
            closeConnection()
        }
    }

    private fun parseControlMessage(message: String) {
        val parts = message.split("|", limit = 3)
        if (parts.isEmpty()) return
        val cmd = parts[0]

        when (cmd) {
            "SCREEN_SHARE_START" -> {
                val subparts = message.split("|")
                val rName = subparts.getOrNull(1) ?: "Dispositivo"
                val rRes = subparts.getOrNull(2) ?: "1080p"
                val rFps = subparts.getOrNull(3)?.toIntOrNull() ?: 30
                val rQuality = subparts.getOrNull(4) ?: "Alta"
                scope.launch(Dispatchers.Main) {
                    onScreenShareStarted?.invoke(rName, rRes, rFps, rQuality)
                }
            }
            "SCREEN_SHARE_STOP" -> {
                scope.launch(Dispatchers.Main) {
                    onScreenShareStopped?.invoke()
                }
            }
            "CHAT_REQ" -> {
                val peerName = parts.getOrNull(1) ?: "Dispositivo"
                scope.launch(Dispatchers.Main) {
                    onChatRequestReceived(peerName) { accepted ->
                        sendRaw("CHAT_RESP|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "CHAT_RESP" -> {
                val status = parts.getOrNull(1) ?: "REJECT"
                scope.launch(Dispatchers.Main) {
                    onChatRequestResponse(status == "ACCEPT")
                }
            }
            "CHAT_MSG" -> {
                val text = parts.getOrNull(1) ?: ""
                scope.launch(Dispatchers.Main) {
                    onMessageReceived(text)
                }
            }
            "CONTACT_REQ" -> {
                val peerName = parts.getOrNull(1) ?: "Dispositivo"
                scope.launch(Dispatchers.Main) {
                    onContactRequestReceived(peerName) { accepted ->
                        sendRaw("CONTACT_RESP|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "CONTACT_RESP" -> {
                val status = parts.getOrNull(1) ?: "REJECT"
                scope.launch(Dispatchers.Main) {
                    onContactRequestResponse(status == "ACCEPT")
                }
            }
            "CONTACT_DATA" -> {
                val name = parts.getOrNull(1) ?: ""
                val phone = parts.getOrNull(2) ?: ""
                scope.launch(Dispatchers.Main) {
                    onContactDataReceived(name, phone)
                }
            }
        }
    }

    fun sendChatRequest(myName: String) {
        sendRaw("CHAT_REQ|$myName")
    }

    fun sendChatMessage(text: String) {
        sendRaw("CHAT_MSG|$text")
    }

    fun sendContactRequest(myName: String) {
        sendRaw("CONTACT_REQ|$myName")
    }

    fun sendContactData(name: String, phone: String) {
        sendRaw("CONTACT_DATA|$name|$phone")
    }

    fun sendScreenShareStart(myName: String, resolution: String, fps: Int, quality: String) {
        sendRaw("SCREEN_SHARE_START|$myName|$resolution|$fps|$quality")
    }

    fun sendScreenShareStop() {
        sendRaw("SCREEN_SHARE_STOP")
    }

    private fun sendRaw(payload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outWriter?.println(payload)
                Log.d(tag, "Sent raw: $payload")
            } catch (e: Exception) {
                Log.e(tag, "Error sending raw payload", e)
            }
        }
    }

    fun closeConnection() {
        isRunning = false
        try {
            outWriter?.close()
        } catch (e: Exception) {}
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        clientSocket = null
        serverSocket = null
        outWriter = null
    }

    fun stop() {
        closeConnection()
        try {
            scope.cancel()
        } catch (e: Exception) {}
    }
}
