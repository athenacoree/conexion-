package com.example.conexion

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ShareActivity : ComponentActivity() {

    private val tag = "ShareActivity"

    private lateinit var wifiP2pHelper: WifiP2pHelper
    private lateinit var fileTransferManager: FileTransferManager
    private var audioBeaconEmitter: AudioBeaconEmitter? = null

    private var myNameState = mutableStateOf("Mi Dispositivo")
    private var myAvatarState = mutableStateOf(0)
    private var peersState = mutableStateOf<List<PeerInfo>>(emptyList())
    private var currentConnectionInfo = mutableStateOf<android.net.wifi.p2p.WifiP2pInfo?>(null)

    // Scanning & Transmission states
    private var isSearching = mutableStateOf(false)
    private var currentSessionToken = "000000000000"

    // Pending Uris from external share
    private var sharedUris = mutableStateOf<List<Uri>>(emptyList())

    // Progress updates
    private var transferFileName = mutableStateOf("")
    private var transferProgress = mutableStateOf(0f)
    private var isTransferring = mutableStateOf(false)
    private var isTransferCompleted = mutableStateOf(false)

    // Audio transmission states
    private var isAudioShared = mutableStateOf(false)
    private var isAudioPlayEnabled = mutableStateOf(false)

    // SQLite DB Helper
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbHelper = DatabaseHelper(this)

        // Read profile from DB if exists
        val dbProfile = dbHelper.getProfile()
        if (dbProfile != null) {
            myNameState.value = dbProfile.first
            myAvatarState.value = dbProfile.third
        } else {
            // Fallback to SharedPreferences
            val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
            myNameState.value = prefs.getString("user_name", "Mi Dispositivo") ?: "Mi Dispositivo"
            myAvatarState.value = prefs.getInt("avatar_index", 0)
        }

        audioBeaconEmitter = AudioBeaconEmitter()

        wifiP2pHelper = WifiP2pHelper(
            context = this,
            onConnectionChanged = { info ->
                currentConnectionInfo.value = info
                if (info != null && info.groupFormed) {
                    lifecycleScope.launch {
                        fileTransferManager.startServer()
                    }
                    sendSharedFilesAuto()
                } else {
                    fileTransferManager.stopServer()
                }
            },
            onPeersDiscovered = { peers ->
                peersState.value = peers
                // Save discovered peers to recent DB list
                peers.forEach { p ->
                    dbHelper.saveOrUpdatePeer(p.userName, p.sessionToken, p.phoneNumber, p.avatarIndex)
                }
            },
            onConnectionRequestReceived = { peer ->
                // Handled automatically or via bottom sheet interaction
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        )

        fileTransferManager = FileTransferManager(
            context = this,
            onIncomingFileRequest = { _, _, _, _ -> },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            },
            onProgress = { fileName, bytes, total, completed ->
                transferFileName.value = fileName
                isTransferring.value = !completed
                isTransferCompleted.value = completed
                transferProgress.value = if (total > 0) bytes.toLong().toFloat() / total else 0f
                if (completed) {
                    runOnUiThread {
                        Toast.makeText(this, "Transferencia finalizada: $fileName", Toast.LENGTH_LONG).show()
                        finishSharingWithSuccess()
                    }
                }
            }
        )

        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                ShareSheetScreen()
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        Log.d(tag, "handleShareIntent action: $action, type: $type")
        if (Intent.ACTION_SEND == action && type != null) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                sharedUris.value = listOf(uri)
                isAudioShared.value = type.startsWith("audio/") || uri.path?.endsWith(".mp3", ignoreCase = true) == true || uri.path?.endsWith(".wav", ignoreCase = true) == true || uri.path?.endsWith(".m4a", ignoreCase = true) == true
                Log.d(tag, "Shared single Uri in ShareActivity: $uri, isAudio=${isAudioShared.value}")
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                sharedUris.value = uris
                isAudioShared.value = type.startsWith("audio/") || uris.any { uri ->
                    val resolvedType = contentResolver.getType(uri)
                    resolvedType?.startsWith("audio/") == true || uri.path?.endsWith(".mp3", ignoreCase = true) == true
                }
                Log.d(tag, "Shared multiple Uris in ShareActivity: ${uris.size}, isAudio=${isAudioShared.value}")
            }
        }
    }

    private fun startAirDropScanning() {
        val token = generateSessionToken()
        currentSessionToken = token

        // Start background advertisement and scanning state
        val serviceIntent = Intent(this, BackgroundDiscoveryService::class.java).apply {
            action = BackgroundDiscoveryService.ACTION_SET_SENDING
            putExtra(BackgroundDiscoveryService.EXTRA_PEER_TOKEN, token)
            putExtra(BackgroundDiscoveryService.EXTRA_USER_NAME, myNameState.value)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Start Audio Beacon
        audioBeaconEmitter?.start(token)

        // Wi-Fi Direct discovery & advertising
        wifiP2pHelper.startAdvertising(myNameState.value, token)
        wifiP2pHelper.startDiscovery()
        isSearching.value = true
    }

    private fun stopAirDropScanning() {
        audioBeaconEmitter?.stop()
        wifiP2pHelper.stopDiscovery()

        val serviceIntent = Intent(this, BackgroundDiscoveryService::class.java).apply {
            action = BackgroundDiscoveryService.ACTION_CLEAR_SENDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isSearching.value = false
    }

    private fun generateSessionToken(): String {
        val allowedChars = ('0'..'9') + ('A'..'F')
        return (1..12).map { allowedChars.random() }.joinToString("")
    }

    private fun sendSharedFilesAuto() {
        val uris = sharedUris.value
        val info = currentConnectionInfo.value
        if (uris.isEmpty() || info == null || !info.groupFormed) return

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
                    Log.d(tag, "ShareActivity Attempt $attempts: Sending shared file: $uri to $hostAddress")
                    sent = fileTransferManager.sendFile(hostAddress, uri, isAudioPlayEnabled.value)
                    if (!sent) delay(1500)
                }
            }
        }
    }

    private fun finishSharingWithSuccess() {
        lifecycleScope.launch {
            delay(1500)
            stopAirDropScanning()
            Toast.makeText(this@ShareActivity, "¡Compartido con éxito!", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAirDropScanning()
        fileTransferManager.stopServer()
        wifiP2pHelper.unregister()
    }

    @Composable
    fun ShareSheetScreen() {
        val context = LocalContext.current
        var isSheetOpen by remember { mutableStateOf(true) }

        // Start scanning automatically when the share screen opens
        LaunchedEffect(Unit) {
            startAirDropScanning()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable {
                    stopAirDropScanning()
                    finish()
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}, // Intercept clicks
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Transfer Progress Card
                AnimatedVisibility(visible = isTransferring.value) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Enviando: ${transferFileName.value}",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = transferProgress.value,
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF007AFF),
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(transferProgress.value * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.7f)),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                AirDropBottomSheet(
                    isOpen = isSheetOpen,
                    onDismiss = {
                        isSheetOpen = false
                        stopAirDropScanning()
                        finish()
                    },
                    myName = myNameState.value,
                    myAvatarIndex = myAvatarState.value,
                    discoveredPeers = peersState.value,
                    blePeers = emptyList(), // BLE peers resolved directly or stored in WifiP2p
                    onPeerSelected = { peer ->
                        wifiP2pHelper.connectToPeer(peer)
                        Toast.makeText(context, "Conectando con ${peer.userName}...", Toast.LENGTH_SHORT).show()
                    },
                    onBlePeerSelected = { ble ->
                        val peer = wifiP2pHelper.findPeerByToken(ble.sessionToken)
                        if (peer != null) {
                            wifiP2pHelper.connectToPeer(peer)
                        } else {
                            wifiP2pHelper.startDiscoveryForToken(ble.sessionToken, ble.userName)
                        }
                    },
                    isSearching = isSearching.value,
                    onToggleSearch = {
                        if (isSearching.value) {
                            stopAirDropScanning()
                        } else {
                            startAirDropScanning()
                        }
                    },
                    isAudioShared = isAudioShared.value,
                    isAudioPlayEnabled = isAudioPlayEnabled.value,
                    onAudioPlayToggleChanged = { isAudioPlayEnabled.value = it }
                )
            }
        }
    }
}
