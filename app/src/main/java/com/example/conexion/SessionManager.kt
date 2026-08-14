package com.example.conexion

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SessionManager(
    private val dbHelper: DatabaseHelper,
    private val myDeviceId: String,
    private val onMessageReceived: (DbChatMessage) -> Unit,
    private val onChatRequestReceived: (peerDeviceId: String, peerName: String, decisionCallback: (Boolean) -> Unit) -> Unit,
    private val onChatRequestResponse: (accepted: Boolean, peerDeviceId: String) -> Unit,
    private val onContactRequestReceived: (peerDeviceId: String, peerName: String, decisionCallback: (Boolean) -> Unit) -> Unit,
    private val onContactRequestResponse: (accepted: Boolean) -> Unit,
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

    // Connected peer's device ID
    var activePeerDeviceId: String = ""
    var activePeerName: String = ""

    // Stream & Clipboard callbacks
    var onStreamRequestReceived: ((type: String, peerDeviceId: String, peerName: String, decisionCallback: (Boolean) -> Unit) -> Unit)? = null
    var onStreamRequestResponse: ((type: String, accepted: Boolean) -> Unit)? = null
    var onStreamStopped: ((type: String) -> Unit)? = null
    var onClipboardDataReceived: ((text: String) -> Unit)? = null

    // Screen sharing callbacks
    var onScreenShareStarted: ((peerName: String, resolution: String, fps: Int, quality: String) -> Unit)? = null
    var onScreenShareStopped: (() -> Unit)? = null

    fun startServer() {
        if (isRunning) return
        isRunning = true
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

            // Immediately identify ourselves to the connected peer
            sendRaw("IDENTIFY|$myDeviceId")

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
        val parts = message.split("|", limit = 4)
        if (parts.isEmpty()) return
        val cmd = parts[0]

        when (cmd) {
            "IDENTIFY" -> {
                val peerId = parts.getOrNull(1) ?: ""
                if (peerId.isNotEmpty()) {
                    activePeerDeviceId = peerId
                    Log.d(tag, "Peer identified with persistent deviceId: $peerId")
                }
            }
            "STREAM_REQ_AUDIO" -> {
                val peerId = parts.getOrNull(1) ?: ""
                val peerName = parts.getOrNull(2) ?: "Dispositivo"
                if (peerId.isNotEmpty()) activePeerDeviceId = peerId
                activePeerName = peerName

                scope.launch(Dispatchers.Main) {
                    onStreamRequestReceived?.invoke("AUDIO", peerId, peerName) { accepted ->
                        sendRaw("STREAM_RESP|AUDIO|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "STREAM_REQ_SCREEN" -> {
                val peerId = parts.getOrNull(1) ?: ""
                val peerName = parts.getOrNull(2) ?: "Dispositivo"
                if (peerId.isNotEmpty()) activePeerDeviceId = peerId
                activePeerName = peerName

                scope.launch(Dispatchers.Main) {
                    onStreamRequestReceived?.invoke("SCREEN", peerId, peerName) { accepted ->
                        sendRaw("STREAM_RESP|SCREEN|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "STREAM_RESP" -> {
                val type = parts.getOrNull(1) ?: "AUDIO"
                val status = parts.getOrNull(2) ?: "REJECT"
                scope.launch(Dispatchers.Main) {
                    onStreamRequestResponse?.invoke(type, status == "ACCEPT")
                }
            }
            "STREAM_STOP" -> {
                val type = parts.getOrNull(1) ?: "AUDIO"
                scope.launch(Dispatchers.Main) {
                    onStreamStopped?.invoke(type)
                }
            }
            "CLIPBOARD_DATA" -> {
                val clipText = parts.getOrNull(1) ?: ""
                scope.launch(Dispatchers.Main) {
                    onClipboardDataReceived?.invoke(clipText)
                }
            }
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
                val peerId = parts.getOrNull(1) ?: ""
                val peerName = parts.getOrNull(2) ?: "Dispositivo"
                if (peerId.isNotEmpty()) activePeerDeviceId = peerId
                activePeerName = peerName

                scope.launch(Dispatchers.Main) {
                    onChatRequestReceived(activePeerDeviceId, peerName) { accepted ->
                        sendRaw("CHAT_RESP|$myDeviceId|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "CHAT_RESP" -> {
                val peerId = parts.getOrNull(1) ?: ""
                val status = parts.getOrNull(2) ?: "REJECT"
                if (peerId.isNotEmpty()) activePeerDeviceId = peerId

                scope.launch(Dispatchers.Main) {
                    onChatRequestResponse(status == "ACCEPT", activePeerDeviceId)
                }
            }
            "CHAT_MSG" -> {
                val senderPeerId = parts.getOrNull(1) ?: activePeerDeviceId.ifEmpty { "unknown_peer" }
                val senderName = parts.getOrNull(2) ?: activePeerName.ifEmpty { "Dispositivo" }
                val text = parts.getOrNull(3) ?: ""

                // Persist incoming chat message to database!
                val chatMsg = dbHelper.saveChatMessage(
                    peerDeviceId = senderPeerId,
                    senderName = senderName,
                    message = text,
                    isMe = false
                )

                scope.launch(Dispatchers.Main) {
                    onMessageReceived(chatMsg)
                }
            }
            "CONTACT_REQ" -> {
                val peerId = parts.getOrNull(1) ?: ""
                val peerName = parts.getOrNull(2) ?: "Dispositivo"
                if (peerId.isNotEmpty()) activePeerDeviceId = peerId

                scope.launch(Dispatchers.Main) {
                    onContactRequestReceived(activePeerDeviceId, peerName) { accepted ->
                        sendRaw("CONTACT_RESP|$myDeviceId|${if (accepted) "ACCEPT" else "REJECT"}")
                    }
                }
            }
            "CONTACT_RESP" -> {
                val status = parts.getOrNull(2) ?: parts.getOrNull(1) ?: "REJECT"
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
        sendRaw("CHAT_REQ|$myDeviceId|$myName")
    }

    fun sendChatMessage(text: String, myName: String, peerDeviceId: String): DbChatMessage {
        val targetPeerId = if (peerDeviceId.isNotEmpty()) peerDeviceId else activePeerDeviceId.ifEmpty { "unknown_peer" }
        val chatMsg = dbHelper.saveChatMessage(
            peerDeviceId = targetPeerId,
            senderName = myName,
            message = text,
            isMe = true
        )
        sendRaw("CHAT_MSG|$myDeviceId|$myName|$text")
        return chatMsg
    }

    fun sendContactRequest(myName: String) {
        sendRaw("CONTACT_REQ|$myDeviceId|$myName")
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

    fun sendStreamRequest(type: String, myName: String) {
        if (type == "SCREEN") {
            sendRaw("STREAM_REQ_SCREEN|$myDeviceId|$myName")
        } else {
            sendRaw("STREAM_REQ_AUDIO|$myDeviceId|$myName")
        }
    }

    fun sendStreamStop(type: String) {
        sendRaw("STREAM_STOP|$type")
    }

    fun sendClipboardData(text: String) {
        sendRaw("CLIPBOARD_DATA|$text")
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
