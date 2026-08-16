package com.example.conexion

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

val GRADIENTS = listOf(
    listOf(Color(0xFF2F8CFF), Color(0xFF0B1F4B)),
    listOf(Color(0xFFFF2D78), Color(0xFF3A0B2E)),
    listOf(Color(0xFF34C759), Color(0xFF0B3A1E)),
    listOf(Color(0xFFFFD60A), Color(0xFF4A3800)),
    listOf(Color(0xFFB25CFF), Color(0xFF2A0B4A)),
    listOf(Color(0xFFFF7A2F), Color(0xFF4A1A00)),
    listOf(Color(0xFF22D3EE), Color(0xFF053B45)),
    listOf(Color(0xFFFF5C7A), Color(0xFF3A0B18))
)

fun getGradBrush(idx: Int): Brush {
    val pair = GRADIENTS[idx % GRADIENTS.size]
    return Brush.linearGradient(listOf(pair[0], pair[1]))
}

fun formatTime(seconds: Int): String {
    val s = Math.max(0, seconds)
    val m = s / 60
    val r = s % 60
    return "$m:${if (r < 10) "0" else ""}$r"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTabScreen(
    isDark: Boolean,
    onSelectFileToPlay: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Mode: 0 = Audio, 1 = Video
    var selectedMediaTab by remember { mutableStateOf(0) }

    // MediaStore Data
    var audioTracks by remember { mutableStateOf<List<LocalAudioTrack>>(emptyList()) }
    var videoItems by remember { mutableStateOf<List<LocalVideoItem>>(emptyList()) }
    var isLoadingMedia by remember { mutableStateOf(true) }

    fun refreshMedia() {
        isLoadingMedia = true
        coroutineScope.launch {
            val audio = MediaStoreHelper.loadLocalAudioTracks(context)
            val video = MediaStoreHelper.loadLocalVideos(context)
            audioTracks = audio
            videoItems = video
            isLoadingMedia = false
        }
    }

    LaunchedEffect(Unit) {
        refreshMedia()
    }

    // --- AUDIO PLAYER STATE ---
    var currentAudioIndex by remember { mutableStateOf(-1) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var audioElapsedSeconds by remember { mutableStateOf(0) }
    var isAudioPlayerOpen by remember { mutableStateOf(false) }
    var isAudioLiked by remember { mutableStateOf(false) }
    var audioShuffleOn by remember { mutableStateOf(false) }
    var audioRepeatMode by remember { mutableStateOf(0) } // 0 off, 1 all, 2 one
    var showAudioQueueSheet by remember { mutableStateOf(false) }

    var audioMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun playAudio(index: Int, openFull: Boolean = false) {
        if (index !in audioTracks.indices) return
        currentAudioIndex = index
        val track = audioTracks[index]
        audioElapsedSeconds = 0
        isAudioPlaying = true

        try {
            audioMediaPlayer?.stop()
            audioMediaPlayer?.release()
            audioMediaPlayer = MediaPlayer().apply {
                setDataSource(context, track.uri)
                prepare()
                start()
                setOnCompletionListener {
                    if (audioRepeatMode == 2) {
                        playAudio(currentAudioIndex, false)
                    } else {
                        val next = if (audioShuffleOn) (audioTracks.indices).random() else (currentAudioIndex + 1) % audioTracks.size
                        playAudio(next, false)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (openFull) isAudioPlayerOpen = true
    }

    fun toggleAudioPlayPause() {
        if (currentAudioIndex < 0 && audioTracks.isNotEmpty()) {
            playAudio(0, false)
            return
        }
        val player = audioMediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            isAudioPlaying = false
        } else {
            player.start()
            isAudioPlaying = true
        }
    }

    LaunchedEffect(isAudioPlaying, currentAudioIndex) {
        while (isAudioPlaying && currentAudioIndex >= 0) {
            delay(1000)
            val player = audioMediaPlayer
            if (player != null && player.isPlaying) {
                audioElapsedSeconds = player.currentPosition / 1000
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                audioMediaPlayer?.stop()
                audioMediaPlayer?.release()
            } catch (e: Exception) {}
        }
    }

    // --- VIDEO PLAYER STATE ---
    var currentVideoIndex by remember { mutableStateOf(-1) }
    var isVideoPlayerOpen by remember { mutableStateOf(false) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    var videoCurrentTimeMs by remember { mutableStateOf(0L) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var videoPlaybackSpeed by remember { mutableStateOf(1f) }
    var isVideoLiked by remember { mutableStateOf(false) }
    var isVideoLocked by remember { mutableStateOf(false) }
    var showVideoControls by remember { mutableStateOf(true) }
    var showVideoQueueSheet by remember { mutableStateOf(false) }
    var videoBrightness by remember { mutableStateOf(1f) }
    var videoVolumeRatio by remember { mutableStateOf(1f) }

    var rippleSeekLeft by remember { mutableStateOf(false) }
    var rippleSeekRight by remember { mutableStateOf(false) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    fun openVideoPlayer(index: Int) {
        if (index !in videoItems.indices) return
        currentVideoIndex = index
        isVideoPlayerOpen = true
        showVideoControls = true
        isVideoPlaying = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF000000) else Color(0xFFF2F2F7))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Main Library Header Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) Color(0xFF0A0A0C).copy(alpha = 0.85f) else Color(0xFFFFFFFF).copy(alpha = 0.9f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedMediaTab == 0) "Media" else "Video",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isDark) Color.White else Color(0xFF111114)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { refreshMedia() }) {
                                Text("🔄", fontSize = 18.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFF2F8CFF), Color(0xFFFF2D78)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("JR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Main Media Tab Switcher Pills (Audio vs Video)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tabs = listOf("🎵 Audio", "🎬 Video")
                        tabs.forEachIndexed { idx, label ->
                            val isSel = selectedMediaTab == idx
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSel) Color.White else Color(0xFF767680).copy(alpha = 0.2f))
                                    .clickable { selectedMediaTab = idx }
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) Color.Black else Color(0xFF8E8E93)
                                )
                            }
                        }
                    }
                }
            }

            // Main Content View
            Box(modifier = Modifier.weight(1f)) {
                if (isLoadingMedia) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF2F8CFF))
                    }
                } else if (selectedMediaTab == 0) {
                    // --- AUDIO LIBRARY SCREEN ---
                    if (audioTracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎵", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No hay canciones en el teléfono", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isDark) Color.White else Color.Black)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Escanea o selecciona un archivo de audio para reproducir.", fontSize = 12.sp, color = Color(0xFF8E8E93))
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(onClick = onSelectFileToPlay, shape = RoundedCornerShape(12.dp)) {
                                    Text("Abrir Archivo de Audio")
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 140.dp)
                        ) {
                            Text(
                                text = "Escuchado recientemente",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isDark) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )

                            // Hero Scroll Recently Played
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(audioTracks.take(8)) { idx, track ->
                                    Column(
                                        modifier = Modifier
                                            .width(132.dp)
                                            .clickable { playAudio(idx, true) }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(132.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(getGradBrush(track.gradientIndex)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (track.albumArtUri != null) {
                                                AsyncImage(
                                                    model = track.albumArtUri,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Text("🎵", fontSize = 36.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = track.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color.White else Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            fontSize = 11.5.sp,
                                            color = Color(0xFF8E8E93),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Tu biblioteca (${audioTracks.size} canciones)",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isDark) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            // Song Rows
                            audioTracks.forEachIndexed { i, track ->
                                val isCurrent = i == currentAudioIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { playAudio(i, true) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(getGradBrush(track.gradientIndex)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (track.albumArtUri != null) {
                                            AsyncImage(
                                                model = track.albumArtUri,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Text("🎵", fontSize = 18.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isCurrent) Color(0xFF2F8CFF) else if (isDark) Color.White else Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${track.artist} · ${track.album}",
                                            fontSize = 12.5.sp,
                                            color = Color(0xFF8E8E93),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (isCurrent && isAudioPlaying) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.Bottom,
                                            modifier = Modifier.height(14.dp)
                                        ) {
                                            val infiniteTransition = rememberInfiniteTransition(label = "eq")
                                            val h1 by infiniteTransition.animateFloat(0.3f, 1.0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "h1")
                                            val h2 by infiniteTransition.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "h2")
                                            val h3 by infiniteTransition.animateFloat(0.2f, 0.8f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "h3")

                                            Box(modifier = Modifier.width(3.dp).fillMaxHeight(h1).background(Color(0xFF2F8CFF), RoundedCornerShape(2.dp)))
                                            Box(modifier = Modifier.width(3.dp).fillMaxHeight(h2).background(Color(0xFF2F8CFF), RoundedCornerShape(2.dp)))
                                            Box(modifier = Modifier.width(3.dp).fillMaxHeight(h3).background(Color(0xFF2F8CFF), RoundedCornerShape(2.dp)))
                                        }
                                    } else {
                                        Text(
                                            text = formatTime(track.durationSeconds),
                                            fontSize = 12.5.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // --- VIDEO LIBRARY SCREEN ---
                    if (videoItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎬", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No hay videos en el teléfono", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isDark) Color.White else Color.Black)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Todos los videos de tu galería se mostrarán aquí.", fontSize = 12.sp, color = Color(0xFF8E8E93))
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 120.dp)
                        ) {
                            Text(
                                text = "Continuar viendo",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isDark) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )

                            // Continue Watching Horizontal Row
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(videoItems.take(5)) { idx, item ->
                                    Column(
                                        modifier = Modifier
                                            .width(220.dp)
                                            .clickable { openVideoPlayer(idx) }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(220.dp, 124.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(getGradBrush(item.gradientIndex)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("▶", fontSize = 28.sp, color = Color.White)
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(6.dp)
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(Color.Black.copy(alpha = 0.65f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(formatTime(item.durationSeconds), fontSize = 10.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = item.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color.White else Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.channelOrFolder,
                                            fontSize = 11.5.sp,
                                            color = Color(0xFF8E8E93),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Tu biblioteca (${videoItems.size} videos)",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isDark) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            // 2-Column Video Grid
                            val videoChunked = videoItems.chunked(2)
                            videoChunked.forEachIndexed { rowIdx, pair ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    pair.forEachIndexed { colIdx, item ->
                                        val actualIdx = rowIdx * 2 + colIdx
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { openVideoPlayer(actualIdx) }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(16f / 9f)
                                                    .clip(RoundedCornerShape(13.dp))
                                                    .background(getGradBrush(item.gradientIndex)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                // HD Badge Top Left
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(6.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color.White.copy(alpha = 0.2f))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Text(item.hdTag, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                                }

                                                Text("▶", fontSize = 24.sp, color = Color.White)

                                                // Duration Badge Bottom Right
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(6.dp)
                                                        .clip(RoundedCornerShape(5.dp))
                                                        .background(Color.Black.copy(alpha = 0.65f))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Text(formatTime(item.durationSeconds), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = item.title,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isDark) Color.White else Color.Black,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.channelOrFolder,
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF8E8E93),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- DOCKED MINI AUDIO PLAYER ---
        if (currentAudioIndex >= 0 && currentAudioIndex in audioTracks.indices && !isAudioPlayerOpen) {
            val track = audioTracks[currentAudioIndex]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { isAudioPlayerOpen = true },
                    color = Color(0xFF1E1E22).copy(alpha = 0.92f),
                    shadowElevation = 8.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(getGradBrush(track.gradientIndex)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (track.albumArtUri != null) {
                                    AsyncImage(
                                        model = track.albumArtUri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text("🎵", fontSize = 16.sp)
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF8E8E93),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(onClick = {
                                val prev = (currentAudioIndex - 1 + audioTracks.size) % audioTracks.size
                                playAudio(prev, false)
                            }) {
                                Text("⏮", fontSize = 18.sp, color = Color.White)
                            }

                            IconButton(onClick = { toggleAudioPlayPause() }) {
                                Text(if (isAudioPlaying) "⏸" else "▶", fontSize = 20.sp, color = Color.White)
                            }
                        }

                        // Bottom Thin Mini Progress
                        val dur = Math.max(1, track.durationSeconds)
                        val progressRatio = (audioElapsedSeconds.toFloat() / dur).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressRatio)
                                .height(2.dp)
                                .align(Alignment.BottomStart)
                                .background(Brush.horizontalGradient(listOf(Color(0xFF2F8CFF), Color(0xFFFF2D78))))
                        )
                    }
                }
            }
        }

        // --- FULL-SCREEN NOW PLAYING AUDIO PLAYER MODAL ---
        AnimatedVisibility(
            visible = isAudioPlayerOpen && currentAudioIndex in audioTracks.indices,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            if (currentAudioIndex in audioTracks.indices) {
                val track = audioTracks[currentAudioIndex]
                val gradBrush = getGradBrush(track.gradientIndex)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // Blurred Ambient Background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(gradBrush)
                            .blur(60.dp)
                    )

                    // Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp, bottom = 24.dp, start = 18.dp, end = 18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { isAudioPlayerOpen = false },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Text("⌄", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("REPRODUCIENDO DESDE", fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f), letterSpacing = 1.sp)
                                Text(track.album, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }

                            IconButton(
                                onClick = { showAudioQueueSheet = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Text("⋯", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Large Album Artwork Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .padding(horizontal = 20.dp)
                                .scale(if (isAudioPlaying) 1.0f else 0.92f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(gradBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            if (track.albumArtUri != null) {
                                AsyncImage(
                                    model = track.albumArtUri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text("🎵", fontSize = 72.sp)
                            }
                        }

                        // Track Metadata & Like
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    fontSize = 14.5.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(onClick = { isAudioLiked = !isAudioLiked }) {
                                Text(if (isAudioLiked) "🩷" else "🤍", fontSize = 22.sp)
                            }
                        }

                        // Progress Slider
                        val durSeconds = Math.max(1, track.durationSeconds)
                        val sliderRatio = (audioElapsedSeconds.toFloat() / durSeconds).coerceIn(0f, 1f)
                        Column {
                            Slider(
                                value = sliderRatio,
                                onValueChange = { newRatio ->
                                    val newPosSec = (newRatio * durSeconds).toInt()
                                    audioElapsedSeconds = newPosSec
                                    audioMediaPlayer?.seekTo(newPosSec * 1000)
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTime(audioElapsedSeconds), fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                                Text(formatTime(durSeconds), fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                            }
                        }

                        // Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { audioShuffleOn = !audioShuffleOn }) {
                                Text("🔀", fontSize = 20.sp, color = if (audioShuffleOn) Color(0xFF2F8CFF) else Color.White)
                            }

                            IconButton(onClick = {
                                val prev = (currentAudioIndex - 1 + audioTracks.size) % audioTracks.size
                                playAudio(prev, false)
                            }) {
                                Text("⏮", fontSize = 28.sp, color = Color.White)
                            }

                            Box(
                                modifier = Modifier
                                    .size(74.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { toggleAudioPlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (isAudioPlaying) "⏸" else "▶", fontSize = 32.sp, color = Color.Black)
                            }

                            IconButton(onClick = {
                                val next = if (audioShuffleOn) (audioTracks.indices).random() else (currentAudioIndex + 1) % audioTracks.size
                                playAudio(next, false)
                            }) {
                                Text("⏭", fontSize = 28.sp, color = Color.White)
                            }

                            IconButton(onClick = { audioRepeatMode = (audioRepeatMode + 1) % 3 }) {
                                val repIcon = if (audioRepeatMode == 2) "🔂" else "🔁"
                                Text(repIcon, fontSize = 20.sp, color = if (audioRepeatMode != 0) Color(0xFF2F8CFF) else Color.White)
                            }
                        }

                        // Bottom Row Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💬", fontSize = 18.sp, color = Color.White)

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = 13.dp, vertical = 8.dp)
                            ) {
                                Text("🔊 Este Teléfono", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            IconButton(onClick = { showAudioQueueSheet = true }) {
                                Text("≡", fontSize = 22.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // --- AUDIO QUEUE SHEET ---
        if (showAudioQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAudioQueueSheet = false },
                containerColor = Color(0xFF18181B)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("A continuación", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(audioTracks) { idx, track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playAudio(idx, false)
                                        showAudioQueueSheet = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(getGradBrush(track.gradientIndex)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🎵", fontSize = 16.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                    Text(track.artist, fontSize = 12.sp, color = Color(0xFF8E8E93), maxLines = 1)
                                }
                                Text("≡", fontSize = 16.sp, color = Color(0xFF8E8E93))
                            }
                        }
                    }
                }
            }
        }

        // --- FULL-SCREEN VIDEO PLAYER SCREEN ---
        AnimatedVisibility(
            visible = isVideoPlayerOpen && currentVideoIndex in videoItems.indices,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            if (currentVideoIndex in videoItems.indices) {
                val item = videoItems[currentVideoIndex]

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

                    // Video Stage View
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                tag = item.uri
                                setVideoURI(item.uri)
                                setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    videoDurationMs = mp.duration.toLong()
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        try {
                                            mp.playbackParams = mp.playbackParams.setSpeed(videoPlaybackSpeed)
                                        } catch (e: Exception) {}
                                    }
                                    mp.start()
                                    isVideoPlaying = true
                                    videoViewRef = this
                                }
                                setOnCompletionListener {
                                    val next = (currentVideoIndex + 1) % videoItems.size
                                    openVideoPlayer(next)
                                }
                            }
                        },
                        update = { view ->
                            videoViewRef = view
                            val tagUri = view.tag as? Uri
                            if (tagUri != item.uri) {
                                view.tag = item.uri
                                view.setVideoURI(item.uri)
                                view.setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    videoDurationMs = mp.duration.toLong()
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        try {
                                            mp.playbackParams = mp.playbackParams.setSpeed(videoPlaybackSpeed)
                                        } catch (e: Exception) {}
                                    }
                                    mp.start()
                                    isVideoPlaying = true
                                }
                            } else {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    mediaPlayerRef?.let { mp ->
                                        try {
                                            if (mp.playbackParams.speed != videoPlaybackSpeed) {
                                                mp.playbackParams = mp.playbackParams.setSpeed(videoPlaybackSpeed)
                                            }
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Touch Gesture Zone Layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (!isVideoLocked) showVideoControls = !showVideoControls
                                    },
                                    onDoubleTap = { offset ->
                                        if (!isVideoLocked) {
                                            val halfWidth = size.width / 2f
                                            val vv = videoViewRef
                                            if (offset.x < halfWidth) {
                                                // Seek -10s
                                                vv?.let {
                                                    val newPos = Math.max(0, it.currentPosition - 10000)
                                                    it.seekTo(newPos)
                                                    videoCurrentTimeMs = newPos.toLong()
                                                }
                                                rippleSeekLeft = true
                                            } else {
                                                // Seek +10s
                                                vv?.let {
                                                    val newPos = Math.min(it.duration, it.currentPosition + 10000)
                                                    it.seekTo(newPos)
                                                    videoCurrentTimeMs = newPos.toLong()
                                                }
                                                rippleSeekRight = true
                                            }
                                        }
                                    }
                                )
                            }
                    )

                    // Video Timeline Update Loop
                    LaunchedEffect(isVideoPlaying) {
                        while (isVideoPlaying) {
                            delay(500)
                            videoViewRef?.let { vv ->
                                if (vv.isPlaying) {
                                    videoCurrentTimeMs = vv.currentPosition.toLong()
                                }
                            }
                        }
                    }

                    // Seek Ripple Animation Displays
                    LaunchedEffect(rippleSeekLeft) {
                        if (rippleSeekLeft) {
                            delay(600)
                            rippleSeekLeft = false
                        }
                    }
                    LaunchedEffect(rippleSeekRight) {
                        if (rippleSeekRight) {
                            delay(600)
                            rippleSeekRight = false
                        }
                    }

                    if (rippleSeekLeft) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .padding(20.dp)
                        ) {
                            Text("⏪ 10s", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    if (rippleSeekRight) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .padding(20.dp)
                        ) {
                            Text("10s ⏩", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Full Player Controls UI Overlay
                    if (showVideoControls && !isVideoLocked) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(top = 36.dp, bottom = 20.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top Bar Controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { isVideoPlayerOpen = false }) {
                                    Text("‹", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                    Text(item.channelOrFolder, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                                }

                                IconButton(onClick = { isVideoLocked = true }) {
                                    Text("🔒", fontSize = 18.sp, color = Color.White)
                                }
                            }

                            // Center Play / Pause Breathe Button
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF141416).copy(alpha = 0.7f))
                                        .clickable {
                                            val vv = videoViewRef
                                            if (vv != null) {
                                                if (vv.isPlaying) {
                                                    vv.pause()
                                                    isVideoPlaying = false
                                                } else {
                                                    vv.start()
                                                    isVideoPlaying = true
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (isVideoPlaying) "⏸" else "▶", fontSize = 32.sp, color = Color.White)
                                }
                            }

                            // Bottom Controls Bar
                            Column {
                                // Progress Slider
                                val durSec = Math.max(1L, videoDurationMs / 1000)
                                val curSec = (videoCurrentTimeMs / 1000).coerceIn(0L, durSec)
                                val progressRatio = (curSec.toFloat() / durSec).coerceIn(0f, 1f)

                                Slider(
                                    value = progressRatio,
                                    onValueChange = { newRatio ->
                                        val newPosMs = (newRatio * videoDurationMs).toInt()
                                        videoCurrentTimeMs = newPosMs.toLong()
                                        videoViewRef?.seekTo(newPosMs)
                                    },
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color(0xFF2F8CFF),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(curSec.toInt()), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                    Text(formatTime(durSec.toInt()), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Video Actions Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.15f))
                                            .clickable {
                                                val speeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
                                                val nextIdx = (speeds.indexOf(videoPlaybackSpeed) + 1) % speeds.size
                                                videoPlaybackSpeed = speeds[nextIdx]
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("${videoPlaybackSpeed}x", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }

                                    IconButton(onClick = { isVideoLiked = !isVideoLiked }) {
                                        Text(if (isVideoLiked) "🩷" else "🤍", fontSize = 20.sp)
                                    }

                                    IconButton(onClick = { showVideoQueueSheet = true }) {
                                        Text("≡", fontSize = 20.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Lock Overlay Mode
                    if (isVideoLocked) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .clickable {
                                        isVideoLocked = false
                                        showVideoControls = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🔓", fontSize = 20.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // --- VIDEO QUEUE SHEET ---
        if (showVideoQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showVideoQueueSheet = false },
                containerColor = Color(0xFF18181B)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("A continuación", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(videoItems) { idx, vItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        openVideoPlayer(idx)
                                        showVideoQueueSheet = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(88.dp, 50.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(getGradBrush(vItem.gradientIndex)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("▶", fontSize = 16.sp, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(vItem.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                    Text(vItem.channelOrFolder, fontSize = 11.5.sp, color = Color(0xFF8E8E93), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
