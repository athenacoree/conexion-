package com.example.conexion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pInfo
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    private lateinit var wifiP2pHelper: WifiP2pHelper
    private lateinit var fileTransferManager: FileTransferManager

    private var audioBeaconEmitter: AudioBeaconEmitter? = null

    // App state observables
    private var isWifiEnabledState = mutableStateOf(false)
    private var myNameState = mutableStateOf("Mi Dispositivo")
    private var peersState = mutableStateOf<List<PeerInfo>>(emptyList())
    private var currentConnectionInfo = mutableStateOf<WifiP2pInfo?>(null)
    private var connectionPromptPeer = mutableStateOf<PeerInfo?>(null)

    // Progress updates
    private var transferFileName = mutableStateOf("")
    private var transferProgress = mutableStateOf(0f)
    private var isTransferring = mutableStateOf(false)
    private var isTransferCompleted = mutableStateOf(false)

    // BLE background service state
    private var isBgDiscoveryEnabled = mutableStateOf(false)

    // TAREA B & E: Candidates discovered from background BLE scan
    private val sendingCandidates = mutableStateMapOf<String, BackgroundDiscoveryService.BlePeer>()
    private var showSingleCandidateManual = mutableStateOf<String?>(null)

    // Current Session Token
    private var currentSessionToken = "000000000000"

    // Incoming file dialog state
    private var incomingFileRequest = mutableStateOf<IncomingFilePrompt?>(null)

    // Pending Uris from Share Sheet (TAREA 2)
    private var pendingShareUris = mutableStateOf<List<Uri>>(emptyList())

    data class IncomingFilePrompt(
        val fileName: String,
        val fileSize: Long,
        val onAccept: () -> Unit,
        val onReject: () -> Unit
    )

    private val bgServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackgroundDiscoveryService.ACTION_PEER_SENDING) {
                val name = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_NAME) ?: "Dispositivo Emisor"
                val token = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_TOKEN) ?: "000000000000"
                val isAmbiguous = intent.getBooleanExtra("EXTRA_IS_AMBIGUOUS", false)
                Log.d(tag, "Received BLE peer sending from background service: $name, token=$token, isAmbiguous=$isAmbiguous")

                val peer = BackgroundDiscoveryService.BlePeer(name, token, 1)
                sendingCandidates[token] = peer

                // Auto-expiration after 15 seconds to keep candidates list clean
                lifecycleScope.launch {
                    delay(15_000)
                    if (sendingCandidates[token] == peer) {
                        sendingCandidates.remove(token)
                    }
                }
            } else if (intent?.action == BackgroundDiscoveryService.ACTION_BEACON_TOKEN_DECODED) {
                val token = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_TOKEN) ?: "000000000000"
                val name = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_NAME) ?: "Dispositivo ultrasónico"
                val success = intent.getBooleanExtra("EXTRA_DECODE_SUCCESS", false)
                Log.d(tag, "Received Beacon decoded broadcast: token=$token, name=$name, success=$success")
                if (success) {
                    Toast.makeText(this@MainActivity, "¡Tono ultrasónico válido detectado!", Toast.LENGTH_LONG).show()
                    val peer = wifiP2pHelper.findPeerByToken(token)
                    if (peer != null) {
                        wifiP2pHelper.triggerConnectionRequest(peer)
                    } else {
                        Toast.makeText(this@MainActivity, "Buscando información de red para el emisor...", Toast.LENGTH_SHORT).show()
                        wifiP2pHelper.startDiscoveryForToken(token, name)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiP2pHelper = WifiP2pHelper(
            context = this,
            onConnectionChanged = { info ->
                currentConnectionInfo.value = info
                if (info != null && info.groupFormed) {
                    // Both Group Owner and Client will run the server so either can accept files.
                    // This resolves GO/Client bidirectional communication constraints.
                    lifecycleScope.launch {
                        fileTransferManager.startServer()
                    }
                    val uris = pendingShareUris.value
                    if (uris.isNotEmpty()) {
                        lifecycleScope.launch {
                            delay(1500)
                            val hostAddress = if (info.isGroupOwner) {
                                fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                            } else {
                                info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            }
                            for (uri in uris) {
                                var sent = false
                                var attempts = 0
                                while (!sent && attempts < 5) {
                                    attempts++
                                    Log.d(tag, "Attempt $attempts: Sending automatic file: $uri to $hostAddress")
                                    sent = fileTransferManager.sendFile(hostAddress, uri)
                                    if (!sent) {
                                        delay(1500)
                                    }
                                }
                            }
                            pendingShareUris.value = emptyList()
                        }
                    }
                    Toast.makeText(this, "¡Conectado exitosamente!", Toast.LENGTH_SHORT).show()
                } else {
                    fileTransferManager.stopServer()
                }
            },
            onPeersDiscovered = { peers ->
                peersState.value = peers
            },
            onConnectionRequestReceived = { peer ->
                connectionPromptPeer.value = peer
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        )

        audioBeaconEmitter = AudioBeaconEmitter()

        fileTransferManager = FileTransferManager(
            context = this,
            onIncomingFileRequest = { fileName, fileSize, onAccept, onReject ->
                incomingFileRequest.value = IncomingFilePrompt(fileName, fileSize, onAccept, onReject)
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            },
            onProgress = { fileName, bytes, total, completed ->
                transferFileName.value = fileName
                isTransferring.value = !completed
                isTransferCompleted.value = completed
                transferProgress.value = if (total > 0) bytes.toFloat() / total else 0f
                if (completed) {
                    runOnUiThread {
                        Toast.makeText(this, "Transferencia finalizada: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        toggleWifi(true)

        // Register local broadcast receiver for peer sending and beacon decoding events
        val bgFilter = IntentFilter().apply {
            addAction(BackgroundDiscoveryService.ACTION_PEER_SENDING)
            addAction(BackgroundDiscoveryService.ACTION_BEACON_TOKEN_DECODED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bgServiceReceiver, bgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bgServiceReceiver, bgFilter)
        }

        // Handle possible launch intent from a match notification
        handleIntent(intent)
        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }

        requestAllPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.hasExtra(BackgroundDiscoveryService.EXTRA_PEER_NAME)) {
            val name = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_NAME) ?: "Dispositivo"
            val token = intent.getStringExtra(BackgroundDiscoveryService.EXTRA_PEER_TOKEN) ?: "000000000000"
            val state = intent.getIntExtra("EXTRA_PEER_STATE", 0)
            Log.d(tag, "Handled launch intent with BLE match: $name, token=$token")
            val peer = BackgroundDiscoveryService.BlePeer(name, token, state)
            sendingCandidates[token] = peer
            showSingleCandidateManual.value = token
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        Log.d(tag, "handleShareIntent action: $action, type: $type")
        if (Intent.ACTION_SEND == action && type != null) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                pendingShareUris.value = listOf(uri)
                Log.d(tag, "Shared single Uri: $uri")
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                pendingShareUris.value = uris
                Log.d(tag, "Shared multiple Uris: ${uris.size}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sync WiFi state
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.let {
            isWifiEnabledState.value = it.isWifiEnabled
        }
        // Synchronize with background service status if running
        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        isBgDiscoveryEnabled.value = prefs.getBoolean("bg_discovery_enabled", false)
        if (isBgDiscoveryEnabled.value) {
            startBgDiscoveryService()
        }
    }

    override fun onPause() {
        super.onPause() // FIX 1: Corrected lifecycle call from super.onResume() to super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bgServiceReceiver)
        } catch (e: Exception) {
            // ignore
        }
        wifiP2pHelper.unregister()
        fileTransferManager.stopServer()

        if (currentConnectionInfo.value == null) {
            toggleWifi(false)
        }
    }

    private fun toggleWifi(enable: Boolean) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                isWifiEnabledState.value = enable
            } else {
                isWifiEnabledState.value = wifiManager.isWifiEnabled
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to toggle WiFi state", e)
        }
    }

    private fun generateSessionToken(): String {
        val allowedChars = ('0'..'9') + ('A'..'F')
        return (1..12)
            .map { allowedChars.random() }
            .joinToString("")
    }


    private fun startBgDiscoveryService() {
        val serviceIntent = Intent(this, BackgroundDiscoveryService::class.java).apply {
            action = BackgroundDiscoveryService.ACTION_START
            putExtra(BackgroundDiscoveryService.EXTRA_USER_NAME, myNameState.value)
            putExtra(BackgroundDiscoveryService.EXTRA_WIFI_MAC, wifiP2pHelper.myDeviceAddress.ifEmpty { "00:00:00:00:00:00" })
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start background service", e)
        }
    }

    private fun stopBgDiscoveryService() {
        val serviceIntent = Intent(this, BackgroundDiscoveryService::class.java).apply {
            action = BackgroundDiscoveryService.ACTION_STOP
        }
        stopService(serviceIntent)
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val fineLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            if (!fineLocationGranted) {
                Toast.makeText(this, "Se requiere permiso de ubicación para buscar dispositivos", Toast.LENGTH_LONG).show()
            }
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String {
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
        return name ?: "archivo_compartido"
    }

    fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        var size = -1L
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (index != -1) {
                        size = it.getLong(index)
                    }
                }
            }
        }
        return size
    }

    @Composable
    fun FilePreviewItem(uri: Uri) {
        val context = LocalContext.current
        val fileName = remember(uri) { getFileNameFromUri(context, uri) }
        val mimeType = remember(uri) { context.contentResolver.getType(uri) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mimeType?.startsWith("image/") == true) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Miniatura",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                )
            } else if (mimeType?.startsWith("video/") == true) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", fontSize = 24.sp, color = Color.White)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray.copy(alpha = 0.5f))
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val ext = fileName.substringAfterLast('.', "").uppercase().take(4)
                    Text(
                        text = ext.ifEmpty { "DOC" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val size = remember(uri) {
                    val bytes = getFileSizeFromUri(context, uri)
                    if (bytes > 0) String.format("%.2f MB", bytes.toDouble() / (1024 * 1024)) else "Tamaño desconocido"
                }
                Text(
                    text = size,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppScreen() {
        val context = LocalContext.current
        val keyboardController = LocalSoftwareKeyboardController.current

        var tempName by remember { mutableStateOf(myNameState.value) }

        val fileSelectorLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { fileUri ->
                val info = currentConnectionInfo.value
                if (info != null && info.groupFormed) {
                    val hostAddress = if (info.isGroupOwner) {
                        // GO sends to the Client's dynamic IP captured when GO started its server
                        fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                    } else {
                        // Client always sends to the GO's static IP
                        info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    }

                    lifecycleScope.launch {
                        Toast.makeText(context, "Enviando archivo a $hostAddress...", Toast.LENGTH_SHORT).show()
                        fileTransferManager.sendFile(hostAddress, fileUri)
                    }
                } else {
                    Toast.makeText(context, "Por favor, conéctate primero", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Conexión Directa",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = "Alternativa moderna y rápida a Zapya",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Alert banner if WiFi is disabled
            AnimatedVisibility(visible = !isWifiEnabledState.value) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Wi-Fi está desactivado",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Para usar Wi-Fi Direct, por favor activa el Wi-Fi en los ajustes de tu sistema.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No se pudieron abrir los ajustes de Wi-Fi", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Abrir Ajustes", color = Color.White)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tu Identificación",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = {
                            tempName = it
                            myNameState.value = it
                            wifiP2pHelper.setDeviceName(it)
                        },
                        label = { Text("Nombre para mostrar") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Búsqueda BLE en Segundo Plano",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Encuentra dispositivos de forma pasiva con el teléfono bloqueado o cerrado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isBgDiscoveryEnabled.value,
                            onCheckedChange = { isEnabled ->
                                isBgDiscoveryEnabled.value = isEnabled
                                getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("bg_discovery_enabled", isEnabled)
                                    .apply()

                                if (isEnabled) {
                                    startBgDiscoveryService()
                                    Toast.makeText(context, "Búsqueda en segundo plano iniciada", Toast.LENGTH_SHORT).show()
                                } else {
                                    stopBgDiscoveryService()
                                    Toast.makeText(context, "Búsqueda en segundo plano detenida", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // TAREA 4 — Botón de envío manual + integración con el share sheet
            val showShareDialog = pendingShareUris.value.isNotEmpty()
            if (showShareDialog) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Enviar a dispositivo cercano",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(modifier = Modifier.fillMaxWidth()) {
                            pendingShareUris.value.forEach { uri ->
                                FilePreviewItem(uri = uri)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    val token = generateSessionToken()
                                    currentSessionToken = token

                                    // Send Intent to BackgroundDiscoveryService to initiate SENDING mode
                                    val serviceIntent = Intent(context, BackgroundDiscoveryService::class.java).apply {
                                        action = BackgroundDiscoveryService.ACTION_SET_SENDING
                                        putExtra(BackgroundDiscoveryService.EXTRA_PEER_TOKEN, token)
                                        putExtra(BackgroundDiscoveryService.EXTRA_USER_NAME, myNameState.value)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }

                                    // Start Audio Beacon Emitter
                                    audioBeaconEmitter?.start(token)

                                    // Start local Wi-Fi Direct presence advertising and discovery
                                    wifiP2pHelper.startAdvertising(myNameState.value, token)
                                    wifiP2pHelper.startDiscovery()

                                    Toast.makeText(context, "Transmitiendo y buscando receptores...", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Buscar y enviar")
                            }

                            TextButton(
                                onClick = {
                                    pendingShareUris.value = emptyList()
                                }
                            ) {
                                Text("Cancelar", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Manual fallback button "Buscar dispositivos ahora"
            Button(
                onClick = {
                    wifiP2pHelper.startDiscovery()
                    Toast.makeText(context, "Buscando dispositivos Wi-Fi Direct manualmente...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Buscar dispositivos ahora (Manual)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            val isConnected = currentConnectionInfo.value?.groupFormed ?: false
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isConnected) "Estado: Conectado" else "Estado: Desconectado",
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                        if (isConnected) {
                            Text(
                                text = "IP del Servidor: ${currentConnectionInfo.value?.groupOwnerAddress?.hostAddress ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                    }

                    if (isConnected) {
                        Button(
                            onClick = { wifiP2pHelper.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Desconectar")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Compartir Archivos",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { fileSelectorLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Seleccionar y Enviar Archivo")
                        }

                        AnimatedVisibility(visible = isTransferring.value) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Enviando: ${transferFileName.value}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = transferProgress.value,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${(transferProgress.value * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            connectionPromptPeer.value?.let { peer ->
                AlertDialog(
                    onDismissRequest = { connectionPromptPeer.value = null },
                    title = { Text("Conexión Detectada") },
                    text = {
                        Text(
                            text = "¿Deseas aceptar la transferencia y conectarte con ${peer.userName}?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                                if (wifiManager?.isWifiEnabled != true) {
                                    // Wi-Fi is disabled, prompt user with ACTION_INTERNET_CONNECTIVITY panel as required by TAREA 6
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            context.startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                                        } else {
                                            context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Por favor, enciende el Wi-Fi para continuar.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                wifiP2pHelper.connectToPeer(peer)
                                connectionPromptPeer.value = null
                            }
                        ) {
                            Text("Sí, Conectar y Aceptar")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { connectionPromptPeer.value = null }
                        ) {
                            Text("Rechazar")
                        }
                    }
                )
            }

            val singleToken = showSingleCandidateManual.value
            if (singleToken != null && sendingCandidates.containsKey(singleToken)) {
                val peer = sendingCandidates[singleToken]!!
                AlertDialog(
                    onDismissRequest = { showSingleCandidateManual.value = null; sendingCandidates.remove(singleToken) },
                    title = { Text("Dispositivo Compartiendo") },
                    text = {
                        Text(
                            text = "Se ha detectado a ${peer.userName} compartiendo un archivo.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val p2pPeer = wifiP2pHelper.findPeerByToken(peer.sessionToken)
                                if (p2pPeer != null) {
                                    wifiP2pHelper.connectToPeer(p2pPeer)
                                } else {
                                    Toast.makeText(context, "Buscando dirección de red segura...", Toast.LENGTH_SHORT).show()
                                    wifiP2pHelper.startDiscoveryForToken(peer.sessionToken, peer.userName)
                                }
                                showSingleCandidateManual.value = null
                                sendingCandidates.remove(singleToken)
                            }
                        ) {
                            Text("Conectar Ahora")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showSingleCandidateManual.value = null; sendingCandidates.remove(singleToken) }
                        ) {
                            Text("Ignorar")
                        }
                    }
                )
            }

            if (sendingCandidates.size >= 2) {
                AlertDialog(
                    onDismissRequest = { sendingCandidates.clear() },
                    title = { Text("Múltiples Dispositivos Cerca") },
                    text = {
                        Column {
                            Text(
                                text = "Se detectaron varios dispositivos compartiendo cerca, elige con cuál conectar:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            sendingCandidates.values.forEach { peer ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = peer.userName,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val p2pPeer = wifiP2pHelper.findPeerByToken(peer.sessionToken)
                                                if (p2pPeer != null) {
                                                    wifiP2pHelper.connectToPeer(p2pPeer)
                                                } else {
                                                    Toast.makeText(context, "Buscando dirección de red...", Toast.LENGTH_SHORT).show()
                                                    wifiP2pHelper.startDiscoveryForToken(peer.sessionToken, peer.userName)
                                                }
                                                sendingCandidates.clear()
                                            }
                                        ) {
                                            Text("Conectar")
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { sendingCandidates.clear() }
                        ) {
                            Text("Cancelar")
                        }
                    }
                )
            }

            incomingFileRequest.value?.let { request ->
                val sizeInMb = String.format("%.2f MB", request.fileSize.toDouble() / (1024 * 1024))
                AlertDialog(
                    onDismissRequest = {
                        request.onReject()
                        incomingFileRequest.value = null
                    },
                    title = { Text("Confirmación de Recepción") },
                    text = {
                        Text(
                            text = "¿Deseas aceptar el archivo \"${request.fileName}\" ($sizeInMb)?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                request.onAccept()
                                incomingFileRequest.value = null
                            }
                        ) {
                            Text("Aceptar")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                request.onReject()
                                incomingFileRequest.value = null
                            }
                        ) {
                            Text("Rechazar")
                        }
                    }
                )
            }
        }
    }
}
