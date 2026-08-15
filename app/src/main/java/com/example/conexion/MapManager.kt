package com.example.conexion

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class MunicipalityItem(
    val id: Int,
    val province: String,
    val municipality: String,
    val relativePath: String,
    val assetFilename: String,
    val downloadUrl: String,
    val polygonRings: List<List<Pair<Double, Double>>> // [lon, lat] pairs
)

class MapManager(private val context: Context) {
    private val tag = "MapManager"
    val municipalities = mutableListOf<MunicipalityItem>()

    init {
        loadMunicipalitiesFromAssets()
    }

    private fun loadMunicipalitiesFromAssets() {
        try {
            val jsonString = context.assets.open("cuba_municipios.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val features = root.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feat = features.getJSONObject(i)
                val props = feat.getJSONObject("properties")
                val geom = feat.getJSONObject("geometry")

                val type = geom.getString("type")
                val ringsList = mutableListOf<List<Pair<Double, Double>>>()

                if (type == "Polygon") {
                    val coords = geom.getJSONArray("coordinates")
                    for (r in 0 until coords.length()) {
                        val ring = coords.getJSONArray(r)
                        val points = mutableListOf<Pair<Double, Double>>()
                        for (p in 0 until ring.length()) {
                            val pt = ring.getJSONArray(p)
                            points.add(Pair(pt.getDouble(0), pt.getDouble(1))) // [lon, lat]
                        }
                        ringsList.add(points)
                    }
                } else if (type == "MultiPolygon") {
                    val polys = geom.getJSONArray("coordinates")
                    for (p in 0 until polys.length()) {
                        val coords = polys.getJSONArray(p)
                        for (r in 0 until coords.length()) {
                            val ring = coords.getJSONArray(r)
                            val points = mutableListOf<Pair<Double, Double>>()
                            for (ptIdx in 0 until ring.length()) {
                                val pt = ring.getJSONArray(ptIdx)
                                points.add(Pair(pt.getDouble(0), pt.getDouble(1))) // [lon, lat]
                            }
                            ringsList.add(points)
                        }
                    }
                }

                val rawDownloadUrl = props.optString("downloadUrl", "")
                val assetFilename = props.optString("assetFilename", "")
                val fixedDownloadUrl = if (rawDownloadUrl.isNotEmpty()) rawDownloadUrl else "https://github.com/carlos-m12/conexion/releases/download/v1.0.0-maps/$assetFilename"

                municipalities.add(
                    MunicipalityItem(
                        id = props.getInt("id"),
                        province = props.getString("province"),
                        municipality = props.getString("municipality"),
                        relativePath = props.optString("relativePath", ""),
                        assetFilename = assetFilename,
                        downloadUrl = fixedDownloadUrl,
                        polygonRings = ringsList
                    )
                )
            }
            Log.d(tag, "Loaded ${municipalities.size} municipalities from assets")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load cuba_municipios.json", e)
        }
    }

    fun getProvinces(): List<String> {
        return municipalities.map { it.province }.distinct().sorted()
    }

    fun getMunicipalitiesForProvince(province: String): List<MunicipalityItem> {
        return municipalities.filter { it.province.equals(province, ignoreCase = true) }.sortedBy { it.municipality }
    }

    /**
     * Ray-casting point-in-polygon algorithm to detect municipality from lat/lon offline.
     */
    fun findMunicipalityForLocation(lat: Double, lon: Double): MunicipalityItem? {
        for (item in municipalities) {
            for (ring in item.polygonRings) {
                if (isPointInPolygon(lon, lat, ring)) {
                    return item
                }
            }
        }
        return null
    }

    private fun isPointInPolygon(x: Double, y: Double, polygon: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].first
            val yi = polygon[i].second
            val xj = polygon[j].first
            val yj = polygon[j].second

            val intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun getMapsDir(): File {
        val dir = File(context.filesDir, "maps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLocalMapFile(item: MunicipalityItem): File {
        return File(getMapsDir(), "${item.province}_${item.municipality}.pmtiles".replace(" ", "_"))
    }

    fun isMapDownloaded(item: MunicipalityItem): Boolean {
        val file = getLocalMapFile(item)
        return file.exists() && file.length() > 0
    }

    suspend fun downloadMap(
        item: MunicipalityItem,
        onProgress: (Float) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outputFile = getLocalMapFile(item)

        // If local map file already exists and is non-empty
        if (outputFile.exists() && outputFile.length() > 0) {
            onProgress(1.0f)
            withContext(Dispatchers.Main) { onSuccess(outputFile) }
            return@withContext
        }

        try {
            val url = URL(item.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                withContext(Dispatchers.Main) {
                    onError("HTTP ${connection.responseCode}: ${connection.responseMessage}")
                }
                return@withContext
            }

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int

            while (inputStream.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    onProgress(total.toFloat() / fileLength)
                }
                outputStream.write(data, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            withContext(Dispatchers.Main) {
                onSuccess(outputFile)
            }
        } catch (e: Exception) {
            Log.e(tag, "Download failed", e)
            withContext(Dispatchers.Main) {
                onError("Fallo la descarga: ${e.localizedMessage}")
            }
        }
    }
}
