package com.example.conexion

import android.net.Uri
import androidx.compose.animation.*
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AirDropBottomSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    myName: String,
    myAvatarIndex: Int,
    discoveredPeers: List<PeerInfo>,
    blePeers: List<BackgroundDiscoveryService.BlePeer>,
    onPeerSelected: (PeerInfo) -> Unit,
    onBlePeerSelected: (BackgroundDiscoveryService.BlePeer) -> Unit,
    isSearching: Boolean,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier,
    isAudioShared: Boolean = false,
    isAudioPlayEnabled: Boolean = false,
    onAudioPlayToggleChanged: (Boolean) -> Unit = {}
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E1E2E).copy(alpha = 0.95f),
                            Color(0xFF11111B).copy(alpha = 0.98f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .padding(bottom = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(100))
                        .background(Color.White.copy(alpha = 0.3f))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Header AirDrop style
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AirDrop",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = (-0.5).sp
                            )
                        )
                        Text(
                            text = "Compartir con dispositivos cercanos",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        )
                    }

                    // Done / Dismiss Button
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(100),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Hecho", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Pulsing Scan Animation / Active status
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSearching) {
                        PulsingRadarAnimation(primaryColor = Color(0xFF007AFF))
                    }

                    // Core user avatar
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AvatarBubble(
                            avatarIndex = myAvatarIndex,
                            size = 66.dp,
                            showGlowRing = true,
                            isFloating = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = myName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = if (isSearching) "Buscando..." else "Búsqueda en pausa",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isSearching) Color(0xFF34C759) else Color.White.copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // List of peers
                Text(
                    text = "Dispositivos Encontrados",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(12.dp))

                val combinedList = remember(discoveredPeers, blePeers) {
                    val list = mutableListOf<AirDropItem>()
                    discoveredPeers.forEach { peer ->
                        list.add(AirDropItem.Wifi(peer))
                    }
                    blePeers.forEach { ble ->
                        if (discoveredPeers.none { it.sessionToken == ble.sessionToken }) {
                            list.add(AirDropItem.Ble(ble))
                        }
                    }
                    list
                }

                if (combinedList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No se encontraron dispositivos cercanos.\nAsegúrate de que el receptor tenga la app abierta.",
                            color = Color.White.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(combinedList) { item ->
                            WiggleBox(
                                onClick = {
                                    when (item) {
                                        is AirDropItem.Wifi -> onPeerSelected(item.peer)
                                        is AirDropItem.Ble -> onBlePeerSelected(item.ble)
                                    }
                                }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(76.dp)
                                ) {
                                    AvatarBubble(
                                        avatarIndex = item.avatarIndex,
                                        size = 54.dp,
                                        showGlowRing = true,
                                        isFloating = true
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = item.userName,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = item.formattedDistance,
                                            color = Color(0xFF30D158),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAudioShared) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text("🎵", fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Modo Transmisión de Audio",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Reproducir automáticamente en el receptor",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isAudioPlayEnabled,
                                onCheckedChange = onAudioPlayToggleChanged,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF007AFF),
                                    checkedTrackColor = Color(0xFF007AFF).copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Start/Stop Scan Button
                Button(
                    onClick = onToggleSearch,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSearching) Color(0xFFFF3B30) else Color(0xFF007AFF),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isSearching) "Detener Transmisión" else "Transmitir y Buscar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

sealed class AirDropItem {
    abstract val userName: String
    abstract val avatarIndex: Int
    abstract val sessionToken: String
    abstract val formattedDistance: String

    data class Wifi(val peer: PeerInfo) : AirDropItem() {
        override val userName: String = peer.userName
        override val avatarIndex: Int = peer.avatarIndex
        override val sessionToken: String = peer.sessionToken
        override val formattedDistance: String = peer.formattedDistance
    }

    data class Ble(val ble: BackgroundDiscoveryService.BlePeer) : AirDropItem() {
        override val userName: String = ble.userName
        override val avatarIndex: Int = ble.avatarIndex
        override val sessionToken: String = ble.sessionToken
        override val formattedDistance: String = ble.formattedDistance
    }
}

@Composable
fun PulsingRadarAnimation(primaryColor: Color) {
    val transition = rememberInfiniteTransition()

    val scale1 = transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val alpha1 = transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val scale2 = transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1500)
        )
    )
    val alpha2 = transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1500)
        )
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = 40.dp.toPx()

        drawCircle(
            color = primaryColor,
            radius = baseRadius * scale1.value,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
            alpha = alpha1.value
        )

        drawCircle(
            color = primaryColor,
            radius = baseRadius * scale2.value,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
            alpha = alpha2.value
        )
    }
}

/**
 * Beautiful iPhone style glowing dynamic island / active beacon animation.
 */
@Composable
fun BeautifulBeaconActivationAnimation(
    isActive: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition()
            val glowScale = infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Card(
                modifier = Modifier
                    .width(310.dp)
                    .wrapContentHeight()
                    .scale(glowScale.value)
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFF2D55),
                                Color(0xFF5856D6),
                                Color(0xFF007AFF)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Pulsing animated outer ring
                    Box(
                        modifier = Modifier
                            .size(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = Color(0xFF007AFF).copy(alpha = 0.2f),
                                radius = size.width / 2f
                            )
                            drawCircle(
                                color = Color(0xFF007AFF).copy(alpha = 0.5f),
                                radius = size.width / 2.2f,
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }

                        // Inner revolving beacon symbol
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFFFF2D55), Color(0xFF5856D6))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "📶",
                                fontSize = 24.sp,
                                modifier = Modifier.scale(if (glowScale.value > 1.01f) 1.15f else 0.95f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Baliza de Conexión Activa",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Transmitiendo señal ultrasónica y buscando de forma segura...",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.7f)
                        ),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Minimizar Animación", color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Modern iOS-style miniature top banner alert (for quick acceptance notifications).
 */
@Composable
fun MiniIOSBannerAlert(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF007AFF)),
                contentAlignment = Alignment.Center
            ) {
                Text("📥", fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                shape = RoundedCornerShape(100),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(actionText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onDismiss) {
                Text("✕", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
    }
}
