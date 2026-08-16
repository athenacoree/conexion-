package com.example.conexion

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class LocalAudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val uri: Uri,
    val albumArtUri: Uri?,
    val gradientIndex: Int = 0
)

data class LocalVideoItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val channelOrFolder: String,
    val durationSeconds: Int,
    val uri: Uri,
    val thumbnailUri: Uri?,
    val hdTag: String = "HD",
    val progressRatio: Float = 0f,
    val gradientIndex: Int = 0
)

object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"

    fun loadLocalAudioTracks(context: Context): List<LocalAudioTrack> {
        val tracks = mutableListOf<LocalAudioTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                var index = 0
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawTitle = c.getString(titleCol) ?: "Desconocido"
                    val rawArtist = c.getString(artistCol) ?: "Artista Desconocido"
                    val rawAlbum = c.getString(albumCol) ?: "Álbum Desconocido"
                    val durMs = c.getLong(durCol)
                    val albumId = c.getLong(albumIdCol)

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

                    val artist = if (rawArtist == "<unknown>") "Artista Desconocido" else rawArtist
                    val album = if (rawAlbum == "<unknown>") "Álbum Desconocido" else rawAlbum

                    tracks.add(
                        LocalAudioTrack(
                            id = id,
                            title = rawTitle,
                            artist = artist,
                            album = album,
                            durationSeconds = (durMs / 1000).toInt(),
                            uri = contentUri,
                            albumArtUri = albumArtUri,
                            gradientIndex = index % 8
                        )
                    )
                    index++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local audio tracks from MediaStore", e)
        }
        return tracks
    }

    fun loadLocalVideos(context: Context): List<LocalVideoItem> {
        val videos = mutableListOf<LocalVideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                var index = 0
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawTitle = c.getString(titleCol) ?: "Video sin título"
                    val rawArtist = c.getString(artistCol) ?: ""
                    val durMs = c.getLong(durCol)
                    val bucketName = c.getString(bucketCol) ?: "Galería"
                    val width = c.getInt(widthCol)
                    val height = c.getInt(heightCol)

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    val hdTag = if (width >= 3840 || height >= 2160) "4K"
                    else if (width >= 1920 || height >= 1080) "FHD"
                    else if (width >= 1280 || height >= 720) "HD"
                    else "SD"

                    val subtitle = if (rawArtist.isNotBlank() && rawArtist != "<unknown>") "Video · $rawArtist" else "Video Local"

                    videos.add(
                        LocalVideoItem(
                            id = id,
                            title = rawTitle,
                            subtitle = subtitle,
                            channelOrFolder = bucketName,
                            durationSeconds = (durMs / 1000).toInt(),
                            uri = contentUri,
                            thumbnailUri = contentUri,
                            hdTag = hdTag,
                            progressRatio = 0f,
                            gradientIndex = index % 8
                        )
                    )
                    index++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local videos from MediaStore", e)
        }
        return videos
    }
}
