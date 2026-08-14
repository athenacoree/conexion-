package com.example.conexion

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.readText
import io.ktor.server.engine.ApplicationEngine
import io.ktor.http.content.*
import kotlinx.coroutines.*
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

class AirShareServer(
    private val context: Context,
    private val myNameProvider: () -> String,
    private val myPhoneProvider: () -> String,
    private val onMessageReceived: (String) -> Unit,
    private val onContactReceived: (String, String) -> Unit,
    private val onFileReceived: (String) -> Unit,
    private val onServerStopped: () -> Unit
) {
    private val tag = "AirShareServer"
    private val port = 8989
    private var server: ApplicationEngine? = null
    var isRunning = false
        private set

    private val chatSessions = Collections.synchronizedList(mutableListOf<DefaultWebSocketSession>())
    private var lastActivityTime = System.currentTimeMillis()
    private var inactivityJob: Job? = null

    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastActivityTime = System.currentTimeMillis()

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets)

            routing {
                get("/") {
                    updateActivity()
                    val htmlContent = try {
                        this@AirShareServer.context.assets.open("airshare.html").bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to load airshare.html", e)
                        "<html><body><h1>Error loading index page: ${e.localizedMessage}</h1></body></html>"
                    }
                    call.respondText(htmlContent, ContentType.Text.Html)
                }

                get("/files") {
                    updateActivity()
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Conexion")
                    val filesList = if (directory.exists() && directory.isDirectory) {
                        directory.listFiles()?.filter { it.isFile } ?: emptyList()
                    } else {
                        emptyList()
                    }

                    val json = filesList.joinToString(prefix = "[", postfix = "]") { file ->
                        val nameEscaped = file.name.replace("\"", "\\\"")
                        val size = file.length()
                        val type = when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "📷 Foto"
                            "mp3", "wav", "m4a", "flac", "ogg" -> "🎵 Audio"
                            "mp4", "mov", "avi", "mkv" -> "🎥 Video"
                            else -> "📄 Documento"
                        }
                        """{"name":"$nameEscaped","size":$size,"type":"$type"}"""
                    }
                    call.respondText(json, ContentType.Application.Json)
                }

                get("/download/{filename}") {
                    updateActivity()
                    val filename = call.parameters["filename"]
                    if (filename == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing filename")
                        return@get
                    }
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Conexion")
                    val file = File(directory, filename)
                    if (file.exists() && file.isFile) {
                        call.response.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
                        call.respondFile(file)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "File not found")
                    }
                }

                post("/upload") {
                    updateActivity()
                    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                    if (contentLength != null && contentLength > 2 * 1024 * 1024 * 1024L) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "File size exceeds 2GB limit")
                        return@post
                    }

                    try {
                        val multipart = call.receiveMultipart()
                        var fileSaved = false
                        var savedName = ""
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val originalName = part.originalFileName ?: "archivo_compartido"
                                val sanitizedName = File(originalName).name
                                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Conexion")
                                if (!directory.exists()) {
                                    directory.mkdirs()
                                }
                                val timestamp = System.currentTimeMillis()
                                val extension = sanitizedName.substringAfterLast('.', "")
                                val baseName = sanitizedName.substringBeforeLast('.')
                                savedName = if (extension.isNotEmpty()) {
                                    "${baseName}_$timestamp.$extension"
                                } else {
                                    "${sanitizedName}_$timestamp"
                                }
                                val file = File(directory, savedName)
                                part.streamProvider().use { input ->
                                    file.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                fileSaved = true
                            }
                            part.dispose()
                        }
                        if (fileSaved) {
                            onFileReceived(savedName)
                            call.respondText("Upload successful")
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "No file uploaded")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Upload error", e)
                        call.respond(HttpStatusCode.InternalServerError, "Error: ${e.localizedMessage}")
                    }
                }

                webSocket("/chat") {
                    updateActivity()
                    chatSessions.add(this)
                    try {
                        for (frame in incoming) {
                            updateActivity()
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Log.d(tag, "Received WS message: $text")
                                onMessageReceived(text)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "WebSocket session error", e)
                    } finally {
                        chatSessions.remove(this)
                    }
                }

                get("/contact") {
                    updateActivity()
                    val myName = myNameProvider()
                    val myPhone = myPhoneProvider()
                    val vCard = """
                        BEGIN:VCARD
                        VERSION:3.0
                        FN:$myName
                        TEL;TYPE=CELL:$myPhone
                        END:VCARD
                    """.trimIndent()
                    call.respondText(vCard, ContentType("text", "vcard"))
                }

                post("/contact") {
                    updateActivity()
                    try {
                        val vCardContent = call.receiveText()
                        val (name, phone) = parseVCard(vCardContent)
                        if (name.isNotEmpty() || phone.isNotEmpty()) {
                            onContactReceived(name, phone)
                            call.respondText("Contact received successfully")
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "Invalid vCard data")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Contact post error", e)
                        call.respond(HttpStatusCode.InternalServerError, "Error: ${e.localizedMessage}")
                    }
                }
            }
        }

        server?.start(wait = false)
        Log.d(tag, "Ktor Netty server started successfully on port $port")

        inactivityJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRunning) {
                delay(15000)
                if (System.currentTimeMillis() - lastActivityTime > 5 * 60 * 1000) {
                    Log.d(tag, "Server automatically stopping due to 5 minutes of inactivity")
                    stop()
                    break
                }
            }
        }
    }

    suspend fun sendChatMessage(message: String) {
        val sessions = synchronized(chatSessions) { chatSessions.toList() }
        for (session in sessions) {
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                Log.e(tag, "Error broadcasting chat message", e)
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        inactivityJob?.cancel()
        inactivityJob = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                server?.stop(1000, 2000)
            } catch (e: Exception) {
                Log.e(tag, "Error stopping Ktor server", e)
            } finally {
                server = null
                onServerStopped()
            }
        }
    }

    private fun parseVCard(vCard: String): Pair<String, String> {
        var name = ""
        var phone = ""
        vCard.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("FN:", ignoreCase = true)) {
                name = trimmed.substring(3).trim()
            } else if (trimmed.startsWith("N:", ignoreCase = true) && name.isEmpty()) {
                val parts = trimmed.substring(2).split(";")
                val last = parts.getOrNull(0)?.trim() ?: ""
                val first = parts.getOrNull(1)?.trim() ?: ""
                name = "$first $last".trim()
            } else if (trimmed.startsWith("TEL", ignoreCase = true)) {
                val phonePart = trimmed.substringAfter(":").trim()
                if (phonePart.isNotEmpty()) {
                    phone = phonePart
                }
            }
        }
        return Pair(name, phone)
    }
}
