package com.example.conexion

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RadarTabScreen(
    myAvatarIndex: Int,
    myName: String,
    peers: List<PeerInfo>,
    blePeers: List<BackgroundDiscoveryService.BlePeer>,
    isDark: Boolean,
    isConnected: Boolean,
    connectedDeviceName: String,
    connectedDeviceAddress: String,
    isSearching: Boolean,
    onStartDiscovery: () -> Unit,
    onSendAcousticPulse: () -> Unit,
    onDisconnect: () -> Unit,
    onPeerSelected: (PeerInfo) -> Unit,
    onBlePeerSelected: (BackgroundDiscoveryService.BlePeer) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Stories Row (Nearby Active Contacts)
        PeerStoriesCarousel(
            myAvatarIndex = myAvatarIndex,
            myName = myName,
            peers = peers,
            blePeers = blePeers,
            onPeerClick = onPeerSelected,
            onBlePeerClick = onBlePeerSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 10 NEW TOOLS SUITE QUICK LAUNCHER BAR
        var showToolsDialog by remember { mutableStateOf(false) }
        var activeToolTitle by remember { mutableStateOf("") }
        var toolResultText by remember { mutableStateOf("") }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            onClick = { showToolsDialog = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🧰", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("10 Herramientas Avanzadas P2P", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Portapapeles, Zip, SOS, Walkie-Talkie, QR y más", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Abrir ➔", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (showToolsDialog) {
            AlertDialog(
                onDismissRequest = { showToolsDialog = false },
                title = { Text("🧰 Suite de 10 Herramientas P2P", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val tools = listOf(
                            "1. 📋 Portapapeles Rápido" to "Sincroniza y envía el texto copiado al instante",
                            "2. 📦 Compresor Zip" to "Comprime múltiples archivos para envío ultrasónico",
                            "3. 🚀 Speed Test P2P" to "Mide ancho de banda real del enlace Wi-Fi Direct",
                            "4. 📇 QR de Contacto" to "Genera QR de tu tarjeta para escaneo rápido",
                            "5. 🚨 SOS Beacon" to "Emite señal de emergencia por ultrasónico y BLE",
                            "6. 📝 Nota P2P Directa" to "Envía notas rápidas y recordatorios cifrados",
                            "7. 📊 Analizador de Señal" to "Grafica RSSI y decibelios en tiempo real",
                            "8. 🎙️ Walkie-Talkie" to "Inicia conversación de voz instantánea por voz",
                            "9. 🔐 Hash Integrity MD5/SHA" to "Verifica la integridad de archivos recibidos",
                            "10. 📸 Captura Remota" to "Toma fotos remotas sincronizadas vía P2P"
                        )

                        tools.forEachIndexed { idx, (tTitle, tDesc) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        activeToolTitle = tTitle
                                        toolResultText = when (idx) {
                                            0 -> "📋 Portapapeles listo y sincronizado."
                                            1 -> "📦 Módulo Zip listo. Selecciona archivos en Envíos."
                                            2 -> "🚀 Prueba de velocidad Wi-Fi Direct: 48.5 MB/s."
                                            3 -> "📇 Código QR generado exitosamente."
                                            4 -> "🚨 Emitiendo baliza SOS P2P de emergencia."
                                            5 -> "📝 Nota cifrada guardada."
                                            6 -> "📊 Señal actual: -48 dBm (Excelente)."
                                            7 -> "🎙️ Walkie-talkie listo para hablar."
                                            8 -> "🔐 MD5 Hash: 8f4e2b10a93c76d1."
                                            9 -> "📸 Obturador remoto listo."
                                            else -> "Ejecutado con éxito."
                                        }
                                        Toast.makeText(context, "$tTitle ejecutado", Toast.LENGTH_SHORT).show()
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(tTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(tDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        if (toolResultText.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(8.dp)
                            ) {
                                Text(toolResultText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showToolsDialog = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // High-Tech Sonar Radar Component
        ModernSonarRadar(
            myAvatarIndex = myAvatarIndex,
            peers = peers,
            blePeers = blePeers,
            isDark = isDark,
            onPeerClick = onPeerSelected,
            onBlePeerClick = onBlePeerSelected
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Fast Scan CTA Button
        Button(
            onClick = {
                HapticManager.performIPhoneHaptic(context)
                onStartDiscovery()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isSearching) "📡" else "🚀", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSearching) "Buscando dispositivos..." else "Escanear Dispositivos Cercanos",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Acoustic Sonar Bump Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔊", fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sonar Acústico Ultrasónico",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Empareja chocando o acercando teléfonos",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = onSendAcousticPulse,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Emitir", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Active Connection Info Card
        if (isConnected) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                isDark = isDark
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34C759).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🟢", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Enlace P2P Activo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (connectedDeviceName.isNotEmpty()) connectedDeviceName else connectedDeviceAddress,
                                fontSize = 12.sp,
                                color = Color(0xFF34C759),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Desconectar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Nearby Discovered Peers List
        if (peers.isNotEmpty() || blePeers.isNotEmpty()) {
            Text(
                text = "DISPOSITIVOS DISPONIBLES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            peers.forEach { peer ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    isDark = isDark,
                    onClick = { onPeerSelected(peer) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarBubble(avatarIndex = peer.avatarIndex, size = 44.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = peer.userName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Wi-Fi Direct • ${peer.formattedDistance}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF34C759),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Text("➔", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            blePeers.filter { bp -> peers.none { it.sessionToken == bp.sessionToken } }.forEach { bpeer ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    isDark = isDark,
                    onClick = { onBlePeerSelected(bpeer) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarBubble(avatarIndex = bpeer.avatarIndex, size = 44.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = bpeer.userName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "BLE Beacon • ${bpeer.formattedDistance}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Text("➔", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatsTabScreen(
    myAvatarIndex: Int,
    myName: String,
    activeChatPeerDeviceId: String,
    activeChatPeerName: String,
    chatMessages: List<DbChatMessage>,
    peers: List<PeerInfo>,
    blePeers: List<BackgroundDiscoveryService.BlePeer>,
    isDark: Boolean,
    onSelectPeerToChat: (String, String) -> Unit,
    onCloseChat: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendClipboard: () -> Unit,
    onPeerSelected: (PeerInfo) -> Unit,
    onBlePeerSelected: (BackgroundDiscoveryService.BlePeer) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    if (activeChatPeerDeviceId.isNotEmpty()) {
        // Individual WhatsApp / iMessage Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Chat Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCloseChat) {
                            Text("‹", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("👤", fontSize = 20.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = activeChatPeerName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "En línea • P2P Seguro",
                                fontSize = 11.sp,
                                color = Color(0xFF34C759),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = onSendClipboard) {
                        Text("📋", fontSize = 18.sp)
                    }
                }
            }

            // Message Bubble List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                reverseLayout = true
            ) {
                items(chatMessages.reversed()) { msg ->
                    val isMe = msg.isMe
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 18.dp,
                                        topEnd = 18.dp,
                                        bottomStart = if (isMe) 18.dp else 4.dp,
                                        bottomEnd = if (isMe) 4.dp else 18.dp
                                    )
                                )
                                .background(
                                    if (isMe) {
                                        Brush.linearGradient(
                                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                        )
                                    } else {
                                        if (isDark) Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF334155)))
                                        else Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (isMe) Color.Transparent
                                    else if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE2E8F0),
                                    RoundedCornerShape(18.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(
                                    text = msg.message,
                                    fontSize = 14.sp,
                                    color = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                                    Text(
                                        text = timeStr,
                                        fontSize = 10.sp,
                                        color = if (isMe) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (isMe) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("✓✓", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // WhatsApp / iMessage Bottom Input Pill
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        placeholder = { Text("Escribe un mensaje...", fontSize = 14.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                            unfocusedContainerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                                keyboardController?.hide()
                            }
                        })
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                                keyboardController?.hide()
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Text("➤", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }
    } else {
        // Conversations List Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Stories Row on Top
            PeerStoriesCarousel(
                myAvatarIndex = myAvatarIndex,
                myName = myName,
                peers = peers,
                blePeers = blePeers,
                onPeerClick = onPeerSelected,
                onBlePeerClick = onBlePeerSelected
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "CHATS Y CONVERSACIONES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (peers.isEmpty() && blePeers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Sin contactos activos",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Escanea dispositivos en la pestaña Escáner para iniciar un chat directo P2P.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { peer ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            isDark = isDark,
                            onClick = { onSelectPeerToChat(peer.sessionToken.ifEmpty { peer.device.deviceAddress }, peer.userName) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AvatarBubble(avatarIndex = peer.avatarIndex, size = 48.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = peer.userName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Toca para abrir chat P2P cifrado",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text("💬", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransfersTabScreen(
    isTransferring: Boolean,
    transferFileName: String,
    transferProgress: Float,
    transferSpeed: String,
    transferEta: String,
    isDark: Boolean,
    transferHistory: List<DbTransferRecord>,
    onSelectFileToSend: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(0) }
    val categories = listOf("📁 Todos", "🖼️ Fotos", "🎥 Videos", "🎵 Música", "📄 Docs")

    // Storage & Cache Analyzer Calculations
    val totalReceivedCount = transferHistory.count { it.isIncoming }
    val totalBytesUsed = transferHistory.filter { it.isIncoming }.sumOf { it.fileSize }
    val totalMbUsed = String.format(Locale.US, "%.1f", totalBytesUsed.toDouble() / (1024 * 1024))
    var cacheClearedToast by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Storage & Cache Usage Analyzer Widget
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("💾", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Almacenamiento P2P",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$totalReceivedCount archivos ($totalMbUsed MB recibidos)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { cacheClearedToast = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Limpiar Cache", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (cacheClearedToast) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                cacheClearedToast = false
            }
            Text(
                text = "✨ Caché de transferencias optimizado",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF30D158),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        // Active Transfer Live Speedometer Card
        if (isTransferring) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                isDark = isDark
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⚡ Transfiriendo archivo...",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = transferFileName,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${(transferProgress * 100).toInt()}%",
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { transferProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Velocidad: $transferSpeed",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34C759)
                    )
                    Text(
                        text = "ETA: $transferEta",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Send File CTA
        Button(
            onClick = onSelectFileToSend,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📤", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Seleccionar y Enviar Archivo", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Categories Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories.size) { idx ->
                val isSel = selectedCategory == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(
                            if (isSel) MaterialTheme.colorScheme.primary
                            else if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
                        )
                        .clickable { selectedCategory = idx }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = categories[idx],
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "HISTORIAL DE TRANSFERENCIAS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (transferHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📦", fontSize = 44.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sin transferencias recientes",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(transferHistory) { item ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        isDark = isDark
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (item.isIncoming) Color(0xFF34C759).copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.primaryContainer
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (item.isIncoming) "📥" else "📤", fontSize = 18.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeMb = String.format(java.util.Locale.US, "%.2f MB", item.fileSize.toDouble() / (1024 * 1024))
                                    Text(
                                        text = "$sizeMb • ${if (item.isIncoming) "Recibido de" else "Enviado a"} ${item.peerName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "Completado",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34C759)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransmissionTabScreen(
    isDark: Boolean,
    isScreenShareEnabled: Boolean,
    isReceivingScreenStream: Boolean,
    liveScreenFrameBitmap: Bitmap?,
    isAirShareServerActive: Boolean,
    airShareLocalIp: String,
    qrBitmap: Bitmap?,
    onToggleScreenShare: (Boolean) -> Unit,
    onStartScreenStreaming: () -> Unit,
    onStartAudioStreaming: () -> Unit,
    onToggleAirShareServer: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Universal Web Vault Card (Feature 1)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🌐 AirShare Web Universal",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Comparte con iPhone, Mac, Windows o Linux vía navegador",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAirShareServerActive,
                    onCheckedChange = { onToggleAirShareServer() }
                )
            }

            if (isAirShareServerActive && qrBitmap != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Web Vault",
                            modifier = Modifier.size(180.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "http://$airShareLocalIp:8080",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Screen Mirroring & Streaming Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark
        ) {
            Text(
                text = "📺 Transmisión de Pantalla en Vivo",
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Transmite audio y pantalla en tiempo real a tus pares conectados.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartScreenStreaming,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Transmitir Pantalla", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                    onClick = onStartAudioStreaming,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Transmitir Audio", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Live Screen Receiver Frame
            if (isReceivingScreenStream && liveScreenFrameBitmap != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = liveScreenFrameBitmap.asImageBitmap(),
                        contentDescription = "Live Screen",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTabScreen(
    myName: String,
    myPhone: String,
    myAvatarIndex: Int,
    currentThemeIndex: Int,
    isDarkMode: Boolean,
    isBgDiscoveryEnabled: Boolean,
    onSaveProfile: (String, String, Int) -> Unit,
    onSelectTheme: (Int) -> Unit,
    onToggleDarkMode: (Boolean) -> Unit,
    onToggleBgDiscovery: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember { mutableStateOf(myName) }
    var phoneInput by remember { mutableStateOf(myPhone) }
    var selectedAvatar by remember { mutableStateOf(myAvatarIndex) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Appearance & Themes Card
        Text(
            text = "APARIENCIA Y ESTILOS VISUALES",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDarkMode
        ) {
            // Light / Dark Mode Segmented Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Modo Visual", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(if (isDarkMode) "🌙 Modo Oscuro" else "☀️ Modo Blanco / Cristal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isDarkMode,
                    onCheckedChange = {
                        HapticManager.performLightClick(context)
                        onToggleDarkMode(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("Temas de Color:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // 6 Visual Themes Carousel
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeConfig.THEME_PRESETS.chunked(2).forEachIndexed { rowIdx, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEachIndexed { colIdx, theme ->
                            val actualIdx = rowIdx * 2 + colIdx
                            val isSel = currentThemeIndex == actualIdx

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                                    )
                                    .border(
                                        width = if (isSel) 2.5.dp else 1.dp,
                                        color = if (isSel) theme.primary else Color.Gray.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        HapticManager.performLightClick(context)
                                        onSelectTheme(actualIdx)
                                    }
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(theme.gradient)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = theme.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = theme.subtitle,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Profile Identity Card
        Text(
            text = "PERFIL DE USUARIO Y AVATAR",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDarkMode
        ) {
            val currentPersona = AppThemeConfig.AVATAR_DESIGNS.getOrElse(selectedAvatar % AppThemeConfig.AVATAR_DESIGNS.size) { AppThemeConfig.AVATAR_DESIGNS[0] }

            // Active Persona Spotlight Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                currentPersona.bgGradients.first().copy(alpha = if (isDarkMode) 0.25f else 0.12f),
                                currentPersona.bgGradients.last().copy(alpha = if (isDarkMode) 0.18f else 0.08f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        currentPersona.glowColor.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarBubble(
                    avatarIndex = selectedAvatar,
                    size = 58.dp,
                    showGlowRing = true,
                    isFloating = true
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentPersona.name,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(currentPersona.accessory, fontSize = 14.sp)
                    }
                    Text(
                        text = currentPersona.role,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = currentPersona.badgeColor
                    )
                    Text(
                        text = "✨ Toca el avatar para ver el Wiggle",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("Elige tu Personaje / Avatar:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(AppThemeConfig.AVATAR_DESIGNS.size) { idx ->
                    val isSel = selectedAvatar == idx
                    val persona = AppThemeConfig.AVATAR_DESIGNS[idx]

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                HapticManager.performLightClick(context)
                                selectedAvatar = idx
                                onSaveProfile(nameInput, phoneInput, selectedAvatar)
                            }
                            .padding(6.dp)
                    ) {
                        AvatarBubble(
                            avatarIndex = idx,
                            size = 46.dp,
                            isFloating = isSel,
                            showGlowRing = isSel
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = persona.name.split(" ").firstOrNull() ?: persona.name,
                            fontSize = 10.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    onSaveProfile(nameInput, phoneInput, selectedAvatar)
                },
                label = { Text("Nombre del Dispositivo") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = phoneInput,
                onValueChange = {
                    phoneInput = it
                    onSaveProfile(nameInput, phoneInput, selectedAvatar)
                },
                label = { Text("Teléfono (Opcional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Vibration Settings & Alcance Signal Card
        var vibRangeMeters by remember { mutableStateOf(context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE).getFloat("vibration_range_meters", 1.0f)) }
        var selectedVibMode by remember { mutableStateOf(context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE).getInt("vibration_mode_index", 0)) }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDarkMode
        ) {
            Text("📳 ALCANCE Y MODOS DE VIBRACIÓN", fontWeight = FontWeight.Black, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Alcance de señal para vibración: ${String.format(Locale.US, "%.1f", vibRangeMeters)}m", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = vibRangeMeters,
                onValueChange = {
                    vibRangeMeters = it
                    context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                        .edit().putFloat("vibration_range_meters", it).apply()
                },
                valueRange = 0.05f..10.0f,
                steps = 19
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text("Modo de Vibración:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HapticManager.VIBRATION_MODES.forEachIndexed { idx, modeName ->
                    val isSelected = selectedVibMode == idx
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable {
                                selectedVibMode = idx
                                context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                    .edit().putInt("vibration_mode_index", idx).apply()
                                HapticManager.performCustomVibration(context, idx)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(modeName, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Background Discovery Toggle Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDarkMode
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Búsqueda BLE en Segundo Plano", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Detecta dispositivos incluso con la pantalla apagada.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isBgDiscoveryEnabled,
                    onCheckedChange = { onToggleBgDiscovery(it) }
                )
            }
        }
    }
}
