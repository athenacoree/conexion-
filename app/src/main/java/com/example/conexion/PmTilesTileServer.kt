package com.example.conexion

import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

class PmTilesTileServer(private val port: Int = 8090) {
    private val tag = "PmTilesTileServer"
    private var server: NettyApplicationEngine? = null
    var activeReader: PmTilesReader? = null

    fun setMapFile(file: File) {
        if (file.exists()) {
            activeReader = PmTilesReader(file)
            Log.d(tag, "Loaded PMTiles map file: ${file.absolutePath}")
        }
    }

    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(Netty, port = port) {
                routing {
                    get("/tiles/{z}/{x}/{y}.mvt") {
                        val z = call.parameters["z"]?.toIntOrNull()
                        val x = call.parameters["x"]?.toIntOrNull()
                        val y = call.parameters["y"]?.toIntOrNull()

                        val reader = activeReader
                        if (z != null && x != null && y != null && reader != null) {
                            val tileData = reader.getTile(z, x, y)
                            if (tileData != null) {
                                call.response.header(HttpHeaders.ContentEncoding, "gzip")
                                call.respondBytes(tileData, ContentType.parse("application/x-protobuf"))
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }.start(wait = false)
            Log.d(tag, "PMTiles tile server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start PMTiles tile server", e)
        }
    }

    fun stop() {
        try {
            server?.stop(500, 1000)
        } catch (e: Exception) {
            // ignore
        }
        server = null
    }
}
