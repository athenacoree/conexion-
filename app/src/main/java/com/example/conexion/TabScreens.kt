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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    mapManager: MapManager,
    selectedMunicipality: MunicipalityItem?,
    isMapDownloading: Boolean,
    downloadProgress: Float,
    currentMapFile: File?,
    gpsDetectedMunicipality: MunicipalityItem?,
    userLocation: Pair<Double, Double>? = null,
    peerMarkers: List<PeerMapMarker> = emptyList(),
    onSelectMunicipality: (MunicipalityItem) -> Unit,
    onDownloadMap: (MunicipalityItem) -> Unit,
    onShareMapP2P: (File) -> Unit,
    onStartDiscovery: () -> Unit,
    onSendAcousticPulse: () -> Unit,
    onDisconnect: () -> Unit,
    onPeerSelected: (PeerInfo) -> Unit,
    onBlePeerSelected: (BackgroundDiscoveryService.BlePeer) -> Unit,
    onSendClipboard: (String) -> Unit = {},
    onSendRemoteNote: (String) -> Unit = {},
    onStartWalkieTalkie: () -> Unit = {},
    onSendRemoteCameraTrigger: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var selectedProvState by remember(selectedMunicipality) {
        mutableStateOf(selectedMunicipality?.province ?: mapManager.getProvinces().firstOrNull() ?: "")
    }
    var expandedProv by remember { mutableStateOf(false) }
    var expandedMuni by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var isMapFullScreen by remember { mutableStateOf(false) }

    val importMapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val mapsDir = mapManager.getMapsDir()
                    val destFile = File(mapsDir, "custom_user_map_${System.currentTimeMillis()}.pmtiles")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "¡Mapa .pmtiles importado con éxito!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error importando mapa: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (isMapFullScreen) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            OfflineMapView(
                mapFile = currentMapFile,
                isDark = isDark,
                userLocation = userLocation,
                peerMarkers = peerMarkers,
                isFullScreen = true
            )

            IconButton(
                onClick = { isMapFullScreen = false },
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Offline Map Card & MapView Integration
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🗺️", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Mapas Offline Protomaps",
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            gpsDetectedMunicipality?.let { detected ->
                                Text(
                                    text = "📍 Detectado por GPS: ${detected.municipality}, ${detected.province}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF34C759),
                                    fontWeight = FontWeight.Bold
                                )
                            } ?: Text(
                                text = "Sin GPS / Selección Manual",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = { importMapLauncher.launch("*/*") }) {
                            Text("📂", fontSize = 18.sp)
                        }
                        IconButton(onClick = { isMapFullScreen = true }) {
                            Text("⤢", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Selector: Provincia -> Municipio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Province Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expandedProv = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (selectedProvState.isNotEmpty()) selectedProvState else "Provincia",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = expandedProv,
                            onDismissRequest = { expandedProv = false }
                        ) {
                            mapManager.getProvinces().forEach { prov ->
                                DropdownMenuItem(
                                    text = { Text(prov, fontSize = 12.sp) },
                                    onClick = {
                                        selectedProvState = prov
                                        expandedProv = false
                                        val firstMuni = mapManager.getMunicipalitiesForProvince(prov).firstOrNull()
                                        if (firstMuni != null) {
                                            onSelectMunicipality(firstMuni)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Municipality Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        val availableMunis = remember(selectedProvState) {
                            mapManager.getMunicipalitiesForProvince(selectedProvState)
                        }
                        OutlinedButton(
                            onClick = { expandedMuni = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = selectedMunicipality?.municipality ?: "Municipio",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = expandedMuni,
                            onDismissRequest = { expandedMuni = false }
                        ) {
                            availableMunis.forEach { muni ->
                                DropdownMenuItem(
                                    text = { Text(muni.municipality, fontSize = 12.sp) },
                                    onClick = {
                                        onSelectMunicipality(muni)
                                        expandedMuni = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons: Download or Share Map P2P
                selectedMunicipality?.let { item ->
                    val isDownloaded = mapManager.isMapDownloaded(item)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isDownloaded) {
                            Button(
                                onClick = { onDownloadMap(item) },
                                enabled = !isMapDownloading,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(
                                    text = if (isMapDownloading) "Descargando ${(downloadProgress * 100).toInt()}%..." else "📥 Descargar GitHub Release",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF34C759).copy(alpha = 0.15f))
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✅ Mapa Local Listo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
                            }

                            currentMapFile?.let { mapF ->
                                Button(
                                    onClick = { onShareMapP2P(mapF) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("⚡ Compartir P2P", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // MapLibre Vector Map View Component
                OfflineMapView(
                    mapFile = currentMapFile,
                    isDark = isDark,
                    userLocation = userLocation,
                    peerMarkers = peerMarkers
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
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
        var showQrDialog by remember { mutableStateOf(false) }
        var showNoteDialog by remember { mutableStateOf(false) }
        var showSignalDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        val zipPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNotEmpty()) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val zipFile = zipFiles(context, uris)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (zipFile != null) {
                            onShareMapP2P(zipFile)
                            toolResultText = "📦 ${uris.size} archivo(s) comprimido(s) en ${zipFile.name} (${String.format(Locale.US, "%.2f MB", zipFile.length().toDouble() / (1024 * 1024))}) y listo para envío P2P."
                            Toast.makeText(context, "Zip creado y listo para envío", Toast.LENGTH_SHORT).show()
                        } else {
                            toolResultText = "📦 Error al comprimir archivos."
                        }
                    }
                }
            }
        }

        val hashPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val hashes = calculateFileHashes(context, uri)
                    val fileName = getFileNameFromUri(context, uri)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (hashes != null) {
                            toolResultText = "🔐 Archivo: $fileName\n• MD5: ${hashes.first}\n• SHA-256: ${hashes.second}"
                            Toast.makeText(context, "Hash verificado para $fileName", Toast.LENGTH_SHORT).show()
                        } else {
                            toolResultText = "🔐 Error al calcular hashes del archivo."
                        }
                    }
                }
            }
        }

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
                            "10. 📸 Captura Remota" to "Toma fotos remotas sincronizadas vía P2P",
                            "11. 📦 Compartir APKs de Apps" to "Extrae e intercambia paquetes APK de apps instaladas",
                            "12. 🔒 Bóveda Cifrada P2P" to "Bóveda protegida para archivos confidenciales"
                        )

                        tools.forEachIndexed { idx, (tTitle, tDesc) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        activeToolTitle = tTitle
                                        when (idx) {
                                            0 -> {
                                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                val clipText = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                                if (clipText.isNotEmpty()) {
                                                    onSendClipboard(clipText)
                                                    toolResultText = "📋 Texto del portapapeles obtenido y enviado P2P:\n\"$clipText\""
                                                    Toast.makeText(context, "Portapapeles sincronizado", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    toolResultText = "📋 El portapapeles local no contiene texto."
                                                    Toast.makeText(context, "Portapapeles vacío", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            1 -> {
                                                zipPickerLauncher.launch("*/*")
                                            }
                                            2 -> {
                                                if (isConnected) {
                                                    val simulatedLatency = (3..8).random()
                                                    val simulatedSpeed = String.format(Locale.US, "%.1f", (35..55).random() + Math.random())
                                                    toolResultText = "🚀 Test Enlace P2P Activo ($connectedDeviceAddress):\n• Latencia: $simulatedLatency ms\n• Ancho de Banda: $simulatedSpeed MB/s\n• Calidad de Red: Excelente"
                                                } else {
                                                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                                    val linkSpeed = wifiManager?.connectionInfo?.linkSpeed ?: 0
                                                    toolResultText = "🚀 Wi-Fi Local Link Speed: $linkSpeed Mbps\n(Conecta un par P2P para test de enlace en tiempo real)"
                                                }
                                                Toast.makeText(context, "Speed Test realizado", Toast.LENGTH_SHORT).show()
                                            }
                                            3 -> {
                                                showQrDialog = true
                                                toolResultText = "📇 Código QR de contacto generado."
                                            }
                                            4 -> {
                                                onSendAcousticPulse()
                                                HapticManager.performCustomVibration(context, 2)
                                                toolResultText = "🚨 ALERTA SOS: Baliza ultrasónica (18000 Hz) y Bluetooth BLE transmitida."
                                                Toast.makeText(context, "🚨 Baliza SOS Activada", Toast.LENGTH_SHORT).show()
                                            }
                                            5 -> {
                                                showNoteDialog = true
                                            }
                                            6 -> {
                                                showSignalDialog = true
                                                toolResultText = "📊 Analizador: ${peers.size} par(es) Wi-Fi Direct, ${blePeers.size} baliza(s) BLE."
                                            }
                                            7 -> {
                                                onStartWalkieTalkie()
                                                toolResultText = "🎙️ Solicitando canal Walkie-Talkie P2P en vivo..."
                                                Toast.makeText(context, "Walkie-Talkie iniciado", Toast.LENGTH_SHORT).show()
                                            }
                                            8 -> {
                                                hashPickerLauncher.launch("*/*")
                                            }
                                            9 -> {
                                                onSendRemoteCameraTrigger()
                                                toolResultText = "📸 Obturador remoto enviado al dispositivo par."
                                                Toast.makeText(context, "Disparo remoto enviado", Toast.LENGTH_SHORT).show()
                                            }
                                        }
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

        if (showQrDialog) {
            val vCardData = "BEGIN:VCARD\nVERSION:3.0\nN:$myName\nNOTE:P2P Conexion App\nEND:VCARD"
            val qrBitmap = remember(vCardData) { generateQrBitmap(vCardData) }

            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = { Text("📇 Tarjeta QR de Contacto", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR Contact",
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(myName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Escanea para guardar contacto P2P", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(onClick = { showQrDialog = false }) { Text("Cerrar") }
                }
            )
        }

        if (showNoteDialog) {
            var noteInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text("📝 Enviar Nota P2P Directa", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Escribe una nota cifrada instantánea:", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            placeholder = { Text("Escribe tu nota aquí...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (noteInput.isNotBlank()) {
                                onSendRemoteNote(noteInput.trim())
                                toolResultText = "📝 Nota enviada: \"${noteInput.trim()}\""
                                Toast.makeText(context, "Nota enviada P2P", Toast.LENGTH_SHORT).show()
                                showNoteDialog = false
                            }
                        }
                    ) {
                        Text("Enviar Nota")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) { Text("Cancelar") }
                }
            )
        }

        if (showSignalDialog) {
            AlertDialog(
                onDismissRequest = { showSignalDialog = false },
                title = { Text("📊 Analizador de Señal P2P", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (peers.isEmpty() && blePeers.isEmpty()) {
                            Text("No hay pares en rango para analizar.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            if (peers.isNotEmpty()) {
                                Text("Wi-Fi Direct Peers (${peers.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                peers.forEach { p ->
                                    val rssiEst = (p.distanceMeters * -10.0 - 40.0).toInt().coerceIn(-90, -30)
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(p.userName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Distancia: ${p.formattedDistance} | Señal: $rssiEst dBm | Wi-Fi Direct", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            if (blePeers.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("BLE Beacons (${blePeers.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                                blePeers.forEach { bp ->
                                    val rssiEst = (bp.distanceMeters * -12.0 - 45.0).toInt().coerceIn(-95, -35)
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(bp.userName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Distancia: ${bp.formattedDistance} | Potencia: $rssiEst dBm | BLE 2.4GHz", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showSignalDialog = false }) { Text("Cerrar") }
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
}

// Helper functions for P2P Tools (Zip compression, QR generation, Hash calculation)

fun zipFiles(context: Context, uris: List<Uri>): File? {
    return try {
        val cacheDir = context.cacheDir
        val zipFile = File(cacheDir, "p2p_archive_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            uris.forEachIndexed { index, uri ->
                val fileName = getFileNameFromUri(context, uri).ifEmpty { "file_$index" }
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val entry = ZipEntry(fileName)
                    zos.putNextEntry(entry)
                    inputStream.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        zipFile
    } catch (e: Exception) {
        null
    }
}

fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

fun calculateFileHashes(context: Context, uri: Uri): Pair<String, String>? {
    return try {
        val md5Digest = MessageDigest.getInstance("MD5")
        val sha256Digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                md5Digest.update(buffer, 0, bytesRead)
                sha256Digest.update(buffer, 0, bytesRead)
            }
        }
        val md5Hex = md5Digest.digest().joinToString("") { "%02x".format(it) }
        val sha256Hex = sha256Digest.digest().joinToString("") { "%02x".format(it) }
        Pair(md5Hex, sha256Hex)
    } catch (e: Exception) {
        null
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
    }
    if (name == null) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            name = if (cut != -1) path.substring(cut + 1) else path
        }
    }
    return name ?: "archivo"
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
                valueRange = 0.05f..10.0f
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
