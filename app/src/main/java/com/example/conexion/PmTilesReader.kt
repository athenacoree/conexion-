package com.example.conexion

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

class PmTilesReader(private val file: File) {
    private val tag = "PmTilesReader"

    private var rootDirectoryOffset: Long = 0
    private var rootDirectoryLength: Long = 0
    private var tileDataOffset: Long = 0
    private var internalCompression: Int = 0
    private var tileCompression: Int = 0

    private val entries = mutableListOf<Entry>()

    data class Entry(
        val tileId: Long,
        val runLength: Int,
        val length: Int,
        val offset: Long
    )

    init {
        readHeaderAndRootDirectory()
    }

    private fun readHeaderAndRootDirectory() {
        if (!file.exists() || file.length() < 127) return

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(127)
                raf.readFully(header)

                val magic = String(header, 0, 7)
                if (!magic.startsWith("PM")) {
                    Log.e(tag, "Invalid PMTiles magic: $magic")
                    return
                }

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                rootDirectoryOffset = buffer.getLong(8)
                rootDirectoryLength = buffer.getLong(16)
                tileDataOffset = buffer.getLong(56)
                internalCompression = header[72].toInt() and 0xFF
                tileCompression = header[73].toInt() and 0xFF

                // Read Root Directory
                raf.seek(rootDirectoryOffset)
                val rootBytesCompressed = ByteArray(rootDirectoryLength.toInt())
                raf.readFully(rootBytesCompressed)

                val rootBytes = if (internalCompression == 2) { // gzip compressed directory
                    decompressGzip(rootBytesCompressed)
                } else {
                    rootBytesCompressed
                }

                parseDirectory(rootBytes)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading PMTiles header/directory", e)
        }
    }

    private fun decompressGzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }

    private fun parseDirectory(bytes: ByteArray) {
        val stream = ByteArrayInputStream(bytes)
        val numEntries = readVarint(stream).toInt()

        val tileIds = mutableListOf<Long>()
        var lastId = 0L
        for (i in 0 until numEntries) {
            lastId += readVarint(stream)
            tileIds.add(lastId)
        }

        val runLengths = mutableListOf<Int>()
        for (i in 0 until numEntries) {
            runLengths.add(readVarint(stream).toInt())
        }

        val lengths = mutableListOf<Int>()
        for (i in 0 until numEntries) {
            lengths.add(readVarint(stream).toInt())
        }

        var lastOff = 0L
        for (i in 0 until numEntries) {
            val o = readVarint(stream)
            if (o == 0L) {
                if (i > 0) {
                    lastOff += lengths[i - 1]
                }
            } else {
                lastOff += o - 1
            }
            entries.add(Entry(tileIds[i], runLengths[i], lengths[i], lastOff))
        }
    }

    private fun readVarint(stream: ByteArrayInputStream): Long {
        var res = 0L
        var shift = 0
        while (true) {
            val b = stream.read()
            if (b == -1) break
            res = res or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return res
    }

    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val targetTileId = zxyToTileId(z, x, y)
        val entry = entries.find { targetTileId >= it.tileId && targetTileId < it.tileId + it.runLength }
            ?: return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(tileDataOffset + entry.offset)
                val data = ByteArray(entry.length)
                raf.readFully(data)
                data
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to read tile $z/$x/$y", e)
            null
        }
    }

    private fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (z == 0) return 0L
        var acc = 0L
        for (tz in 0 until z) {
            acc += (1L shl tz) * (1L shl tz)
        }
        val n = 1 shl z
        var rx: Int
        var ry: Int
        var d = 0L
        var s = n / 2
        var currX = x
        var currY = y

        while (s > 0) {
            rx = if ((currX and s) > 0) 1 else 0
            ry = if ((currY and s) > 0) 1 else 0

            if (ry == 0) {
                if (rx == 1) {
                    currX = s - 1 - currX
                    currY = s - 1 - currY
                }
                val tmp = currX
                currX = currY
                currY = tmp
            }
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            s /= 2
        }
        return acc + d
    }
}
