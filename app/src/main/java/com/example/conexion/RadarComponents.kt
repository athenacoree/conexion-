package com.example.conexion

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PeerMapMarker(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isExactGps: Boolean
)

@Composable
fun DynamicIslandBar(
    isDark: Boolean,
    isConnected: Boolean,
    connectedPeerName: String,
    isTransferring: Boolean,
    transferProgress: Float,
    transferSpeed: String,
    onToggleTheme: () -> Unit,
    onOpenAirDrop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App Identity Brand
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpenAirDrop() }
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("⚡", fontSize = 19.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Conexion",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isConnected) "P2P Activo" else "AirDrop & Sonar",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
                )
            }
        }

        // Center Dynamic Island Capsule
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100))
                .background(
                    if (isDark) Color(0xFF1E293B).copy(alpha = 0.92f)
                    else Color(0xFFFFFFFF).copy(alpha = 0.95f)
                )
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f),
                    RoundedCornerShape(100)
                )
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )

                if (isTransferring) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF30D158).copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${(transferProgress * 100).toInt()}% • $transferSpeed",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isConnected) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (connectedPeerName.isNotEmpty()) connectedPeerName else "Conectado",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34C759),
                        maxLines = 1
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AirDrop Activo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Quick Light / Dark Mode Toggle
        IconButton(
            onClick = onToggleTheme,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
                )
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFCBD5E1),
                    CircleShape
                )
        ) {
            Text(
                text = if (isDark) "☀️" else "🌙",
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun OfflineMapView(
    mapFile: java.io.File?,
    isDark: Boolean,
    userLocation: Pair<Double, Double>? = null,
    peerMarkers: List<PeerMapMarker> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val styleJson = remember {
        try {
            context.assets.open("protomaps_style.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    if (mapFile != null && mapFile.exists() && styleJson.isNotEmpty()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(24.dp)),
            factory = { ctx ->
                com.mapbox.mapboxsdk.Mapbox.getInstance(ctx)
                com.mapbox.mapboxsdk.maps.MapView(ctx).apply {
                    onCreate(null)
                    getMapAsync { maplibreMap ->
                        maplibreMap.setStyle(
                            com.mapbox.mapboxsdk.maps.Style.Builder().fromJson(styleJson)
                        ) {
                            userLocation?.let { (uLat, uLon) ->
                                maplibreMap.cameraPosition = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
                                    .target(com.mapbox.mapboxsdk.geometry.LatLng(uLat, uLon))
                                    .zoom(14.0)
                                    .tilt(50.0)
                                    .build()
                            } ?: run {
                                maplibreMap.cameraPosition = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
                                    .tilt(50.0)
                                    .build()
                            }

                            maplibreMap.uiSettings.apply {
                                isRotateGesturesEnabled = true
                                isTiltGesturesEnabled = true
                                isZoomGesturesEnabled = true
                                isScrollGesturesEnabled = true
                            }
                        }
                    }
                }
            },
            update = { mapView ->
                mapView.onResume()
                mapView.getMapAsync { maplibreMap ->
                    maplibreMap.clear()

                    userLocation?.let { (uLat, uLon) ->
                        maplibreMap.addMarker(
                            com.mapbox.mapboxsdk.annotations.MarkerOptions()
                                .position(com.mapbox.mapboxsdk.geometry.LatLng(uLat, uLon))
                                .title("📍 Tú")
                        )
                    }

                    peerMarkers.forEach { marker ->
                        val markerTitle = if (marker.isExactGps) "👤 ${marker.name} (GPS)" else "📡 ${marker.name} (RSSI aprox)"
                        maplibreMap.addMarker(
                            com.mapbox.mapboxsdk.annotations.MarkerOptions()
                                .position(com.mapbox.mapboxsdk.geometry.LatLng(marker.latitude, marker.longitude))
                                .title(markerTitle)
                        )
                    }
                }
            }
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFCBD5E1),
                    RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️", fontSize = 36.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Selecciona y descarga el mapa de tu municipio",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PeerStoriesCarousel(
    myAvatarIndex: Int,
    myName: String,
    peers: List<PeerInfo>,
    blePeers: List<BackgroundDiscoveryService.BlePeer>,
    onPeerClick: (PeerInfo) -> Unit,
    onBlePeerClick: (BackgroundDiscoveryService.BlePeer) -> Unit
) {
    val context = LocalContext.current

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        // My Story Circle
        item {
            WiggleBox {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFE1306C),
                                        Color(0xFFFD1D1D),
                                        Color(0xFFF77737),
                                        Color(0xFFE1306C)
                                    )
                                )
                            )
                            .padding(2.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarBubble(
                                avatarIndex = myAvatarIndex,
                                size = 48.dp,
                                showGlowRing = true,
                                isFloating = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tú",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                }
            }
        }

        // Discovered Wi-Fi Peers
        items(peers) { peer ->
            WiggleBox(
                onClick = {
                    HapticManager.performLightClick(context)
                    onPeerClick(peer)
                }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF34C759),
                                        Color(0xFF007AFF),
                                        Color(0xFF5856D6),
                                        Color(0xFF34C759)
                                    )
                                )
                            )
                            .padding(2.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarBubble(
                                avatarIndex = peer.avatarIndex,
                                size = 48.dp,
                                showGlowRing = true,
                                isFloating = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peer.userName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Discovered BLE Peers (filtered)
        items(blePeers.filter { bp -> peers.none { it.sessionToken == bp.sessionToken } }) { bpeer ->
            WiggleBox(
                onClick = {
                    HapticManager.performLightClick(context)
                    onBlePeerClick(bpeer)
                }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF007AFF),
                                        Color(0xFF38BDF8),
                                        Color(0xFF818CF8),
                                        Color(0xFF007AFF)
                                    )
                                )
                            )
                            .padding(2.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarBubble(
                                avatarIndex = bpeer.avatarIndex,
                                size = 48.dp,
                                showGlowRing = true,
                                isFloating = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bpeer.userName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

sealed class SonarNode(val angle: Float, val distanceFactor: Float) {
    abstract val name: String
    abstract val avatarIndex: Int
    abstract val formattedDistance: String

    class WifiPeer(val peer: PeerInfo, angle: Float, distanceFactor: Float) : SonarNode(angle, distanceFactor) {
        override val name: String = peer.userName
        override val avatarIndex: Int = peer.avatarIndex
        override val formattedDistance: String = peer.formattedDistance
    }

    class BlePeer(val blePeer: BackgroundDiscoveryService.BlePeer, angle: Float, distanceFactor: Float) : SonarNode(angle, distanceFactor) {
        override val name: String = blePeer.userName
        override val avatarIndex: Int = blePeer.avatarIndex
        override val formattedDistance: String = blePeer.formattedDistance
    }
}

@Composable
fun ModernSonarRadar(
    myAvatarIndex: Int,
    peers: List<PeerInfo>,
    blePeers: List<BackgroundDiscoveryService.BlePeer>,
    isDark: Boolean,
    onPeerClick: (PeerInfo) -> Unit,
    onBlePeerClick: (BackgroundDiscoveryService.BlePeer) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "sonar")

    val pulse1 = infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    val alpha1 = infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha1"
    )

    val pulse2 = infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1600)
        ),
        label = "pulse2"
    )
    val alpha2 = infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1600)
        ),
        label = "alpha2"
    )

    val sweepAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                if (isDark) {
                    Brush.verticalGradient(listOf(Color(0xFF131C2E), Color(0xFF0A0F1A)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))
                }
            )
            .border(
                width = 1.5.dp,
                brush = Brush.verticalGradient(
                    if (isDark) listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))
                    else listOf(Color.White.copy(alpha = 0.95f), Color(0xFFCBD5E1))
                ),
                shape = RoundedCornerShape(32.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = 46.dp.toPx()

            // Concentric Rings
            val ringRadii = listOf(65.dp.toPx(), 110.dp.toPx(), 145.dp.toPx())
            for (r in ringRadii) {
                drawCircle(
                    color = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFF64748B).copy(alpha = 0.15f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Expanding Pulse Waves
            drawCircle(
                color = primaryColor,
                radius = baseRadius * pulse1.value,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
                alpha = alpha1.value
            )

            drawCircle(
                color = primaryColor,
                radius = baseRadius * pulse2.value,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
                alpha = alpha2.value
            )

            // High-tech Sonar Sweep Beam
            val sweepRad = Math.toRadians(sweepAngle.value.toDouble())
            val beamEnd = Offset(
                (center.x + 145.dp.toPx() * Math.cos(sweepRad)).toFloat(),
                (center.y + 145.dp.toPx() * Math.sin(sweepRad)).toFloat()
            )
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.8f), primaryColor.copy(alpha = 0.0f)),
                    start = center,
                    end = beamEnd
                ),
                start = center,
                end = beamEnd,
                strokeWidth = 2.dp.toPx()
            )
        }

        // Center User Node with Gradient Halo
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            AvatarBubble(
                avatarIndex = myAvatarIndex,
                size = 56.dp,
                showGlowRing = true,
                isFloating = true
            )
        }

        // Compass Cardinal Direction Visual Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val compassRadius = 138.dp.toPx()
            // North, South, East, West indicator dots
            val directions = listOf(0f, 90f, 180f, 270f)
            for (dir in directions) {
                val rad = Math.toRadians(dir.toDouble())
                val dotOffset = Offset(
                    (center.x + compassRadius * Math.cos(rad)).toFloat(),
                    (center.y + compassRadius * Math.sin(rad)).toFloat()
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.5f),
                    radius = 3.dp.toPx(),
                    center = dotOffset
                )
            }
        }

        // Discovered Peer Nodes Orbiting
        val allNodes = remember(peers, blePeers) {
            val list = mutableListOf<SonarNode>()
            peers.forEachIndexed { idx, p ->
                val angle = 35f + idx * 85f
                val distFactor = (p.distanceMeters / 5.0).coerceIn(0.25, 0.92).toFloat()
                list.add(SonarNode.WifiPeer(p, angle, distFactor))
            }
            var bleCount = 0
            blePeers.forEach { bp ->
                if (peers.none { it.sessionToken == bp.sessionToken }) {
                    val angle = 125f + bleCount * 90f
                    val distFactor = (bp.distanceMeters / 5.0).coerceIn(0.25, 0.92).toFloat()
                    list.add(SonarNode.BlePeer(bp, angle, distFactor))
                    bleCount++
                }
            }
            list
        }

        allNodes.forEach { node ->
            val angleRad = Math.toRadians(node.angle.toDouble())
            val distancePx = 110 * node.distanceFactor

            val offsetX = (distancePx * Math.cos(angleRad)).toFloat()
            val offsetY = (distancePx * Math.sin(angleRad)).toFloat()

            Box(
                modifier = Modifier
                    .offset(x = offsetX.dp, y = offsetY.dp),
                contentAlignment = Alignment.Center
            ) {
                WiggleBox(
                    onClick = {
                        HapticManager.performLightClick(context)
                        when (node) {
                            is SonarNode.WifiPeer -> onPeerClick(node.peer)
                            is SonarNode.BlePeer -> onBlePeerClick(node.blePeer)
                        }
                    }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AvatarBubble(
                            avatarIndex = node.avatarIndex,
                            size = 46.dp,
                            showGlowRing = true,
                            isFloating = true
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isDark) Color(0xFF1E293B).copy(alpha = 0.90f)
                                    else Color.White.copy(alpha = 0.95f)
                                )
                                .border(
                                    0.8.dp,
                                    if (isDark) Color.White.copy(alpha = 0.18f) else Color(0xFFCBD5E1),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = node.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = node.formattedDistance,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (node is SonarNode.WifiPeer) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    val rssiVal = when(node) {
                                        is SonarNode.WifiPeer -> node.peer.rssi
                                        is SonarNode.BlePeer -> node.blePeer.rssi
                                    }
                                    Text(
                                        text = "📶 ${rssiVal}dBm",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF30D158)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
