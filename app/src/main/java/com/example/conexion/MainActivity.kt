package com.example.conexion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    private lateinit var wifiP2pHelper: WifiP2pHelper
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var sessionManager: SessionManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var streamManager: StreamManager

    private lateinit var mapManager: MapManager
    private lateinit var pmTilesTileServer: PmTilesTileServer

    private var selectedMunicipality = mutableStateOf<MunicipalityItem?>(null)
    private var gpsDetectedMunicipality = mutableStateOf<MunicipalityItem?>(null)
    private var userLocationState = mutableStateOf<Pair<Double, Double>?>(null)
    private var isMapDownloading = mutableStateOf(false)
    private var downloadMapProgress = mutableStateOf(0f)
    private var currentMapFile = mutableStateOf<File?>(null)

    // Live Streaming State
    private var liveScreenFrameBitmap = mutableStateOf<Bitmap?>(null)
    private var isReceivingScreenStream = mutableStateOf(false)
    private var isReceivingAudioStream = mutableStateOf(false)
    private var isSendingScreenStream = mutableStateOf(false)
    private var isSendingAudioStream = mutableStateOf(false)
    private var streamConfirmationPrompt = mutableStateOf<StreamPrompt?>(null)

    data class StreamPrompt(
        val streamType: String,
        val peerDeviceId: String,
        val peerName: String,
        val onDecision: (Boolean) -> Unit
    )

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection != null) {
                val info = currentConnectionInfo.value
                val hostAddress = if (info?.isGroupOwner == true) {
                    fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                } else {
                    info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                }
                streamManager.startScreenSender(projection, hostAddress)
                Toast.makeText(this, "Transmisión de pantalla en vivo iniciada", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show()
            isSendingScreenStream.value = false
        }
    }

    private var audioBeaconEmitter: AudioBeaconEmitter? = null

    // Remote audio playback state
    private lateinit var remoteAudioPlayer: RemoteAudioPlayer
    private var isAutoPlayAudioEnabled = mutableStateOf(false)

    // Screen sharing configurations
    private var isScreenShareEnabled = mutableStateOf(false)
    private var screenShareResolution = mutableStateOf("1080p")
    private var screenShareFps = mutableStateOf(30)
    private var screenShareQuality = mutableStateOf("Alta")

    // Active screen sharing states
    private var activeScreenShareSender = mutableStateOf(false)
    private var activeScreenShareReceiver = mutableStateOf<ScreenShareSession?>(null)

    data class ScreenShareSession(
        val peerName: String,
        val resolution: String,
        val fps: Int,
        val quality: String
    )

    // App state observables
    private var isWifiEnabledState = mutableStateOf(false)
    private var myDeviceIdState = mutableStateOf("")
    private var myNameState = mutableStateOf("Mi Dispositivo")
    private var myAvatarState = mutableStateOf(0)
    private var myPhoneState = mutableStateOf("")
    private var peersState = mutableStateOf<List<PeerInfo>>(emptyList())
    private var currentConnectionInfo = mutableStateOf<WifiP2pInfo?>(null)
    private var connectionPromptPeer = mutableStateOf<PeerInfo?>(null)

    // Progress updates & Bandwidth Monitor
    private var transferFileName = mutableStateOf("")
    private var transferProgress = mutableStateOf(0f)
    private var transferSpeedState = mutableStateOf("0.0 MB/s")
    private var transferEtaState = mutableStateOf("Calculando...")
    private var isTransferring = mutableStateOf(false)
    private var isTransferCompleted = mutableStateOf(false)
    private var transferStartTime = 0L

    // Chat UI state
    private var chatMessages = mutableStateListOf<DbChatMessage>()
    private var chatRequestPrompt = mutableStateOf<ChatPrompt?>(null)
    private var contactRequestPrompt = mutableStateOf<ContactPrompt?>(null)
    private var activeChatPeerDeviceId = mutableStateOf("")
    private var activeChatPeerName = mutableStateOf("")

    data class ChatPrompt(val peerDeviceId: String, val peerName: String, val onDecision: (Boolean) -> Unit)
    data class ContactPrompt(val peerDeviceId: String, val peerName: String, val onDecision: (Boolean) -> Unit)

    // BLE background service state
    private var isBgDiscoveryEnabled = mutableStateOf(false)

    // Candidates discovered from background BLE scan
    private val sendingCandidates = mutableStateMapOf<String, BackgroundDiscoveryService.BlePeer>()
    private val blePeersMap = mutableStateMapOf<String, BackgroundDiscoveryService.BlePeer>()
    private var showSingleCandidateManual = mutableStateOf<String?>(null)

    // Current Session Token
    private var currentSessionToken = "000000000000"

    // Incoming file dialog state
    private var incomingFileRequest = mutableStateOf<IncomingFilePrompt?>(null)

    // Pending Uris from Share Sheet
    private var pendingShareUris = mutableStateOf<List<Uri>>(emptyList())

    // App Theme State
    private var currentThemeIndex = mutableStateOf(0)
    private var isDarkMode = mutableStateOf(false)

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
                val avatarIdx = intent.getIntExtra("EXTRA_PEER_AVATAR", 0)
                val isAmbiguous = intent.getBooleanExtra("EXTRA_IS_AMBIGUOUS", false)
                val lat = intent.getDoubleExtra("EXTRA_PEER_LAT", 0.0)
                val lon = intent.getDoubleExtra("EXTRA_PEER_LON", 0.0)
                val latitude = if (lat != 0.0) lat else null
                val longitude = if (lon != 0.0) lon else null
                Log.d(tag, "Received BLE peer sending: $name, token=$token, isAmbiguous=$isAmbiguous, avatarIndex=$avatarIdx, lat=$latitude, lon=$longitude")

                val peer = BackgroundDiscoveryService.BlePeer(
                    userName = name,
                    sessionToken = token,
                    state = 1,
                    avatarIndex = avatarIdx,
                    latitude = latitude,
                    longitude = longitude
                )
                sendingCandidates[token] = peer
                blePeersMap[token] = peer

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
                    HapticManager.performIPhoneHaptic(this@MainActivity)
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

        dbHelper = DatabaseHelper(this)
        myDeviceIdState.value = dbHelper.getOrCreateMyDeviceId(this)

        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        currentThemeIndex.value = prefs.getInt("theme_index", 0)
        isDarkMode.value = prefs.getBoolean("dark_mode", false)

        val savedProf = dbHelper.getProfile()
        if (savedProf != null) {
            myNameState.value = savedProf.first
            myPhoneState.value = savedProf.second
            myAvatarState.value = savedProf.third
        } else {
            val defaultName = prefs.getString("user_name", "Mi Dispositivo") ?: "Mi Dispositivo"
            val defaultAvatar = prefs.getInt("avatar_index", 0)
            val defaultPhone = prefs.getString("phone_number", "") ?: ""
            dbHelper.saveProfile(defaultName, defaultPhone, defaultAvatar, myDeviceIdState.value)
            myNameState.value = defaultName
            myAvatarState.value = defaultAvatar
            myPhoneState.value = defaultPhone
        }

        sessionManager = SessionManager(
            dbHelper = dbHelper,
            myDeviceId = myDeviceIdState.value,
            onMessageReceived = { chatMsg ->
                if (chatMsg.peerDeviceId == activeChatPeerDeviceId.value) {
                    chatMessages.add(chatMsg)
                }
                HapticManager.performLightClick(this)
            },
            onChatRequestReceived = { peerDeviceId, peerName, decisionCallback ->
                chatRequestPrompt.value = ChatPrompt(peerDeviceId, peerName) { accepted ->
                    if (accepted) {
                        activeChatPeerDeviceId.value = peerDeviceId
                        activeChatPeerName.value = peerName
                        loadChatMessagesForPeer(peerDeviceId)
                    }
                    decisionCallback(accepted)
                }
            },
            onChatRequestResponse = { accepted, peerDeviceId ->
                if (accepted) {
                    activeChatPeerDeviceId.value = peerDeviceId
                    loadChatMessagesForPeer(peerDeviceId)
                    Toast.makeText(this, "¡Solicitud de chat aceptada!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El usuario rechazó la solicitud de chat.", Toast.LENGTH_LONG).show()
                }
            },
            onContactRequestReceived = { peerDeviceId, peerName, decisionCallback ->
                contactRequestPrompt.value = ContactPrompt(peerDeviceId, peerName) { accepted ->
                    decisionCallback(accepted)
                    if (accepted) {
                        sessionManager.sendContactData(myNameState.value, myPhoneState.value)
                        Toast.makeText(this, "Compartiendo contacto...", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onContactRequestResponse = { accepted ->
                if (accepted) {
                    sessionManager.sendContactData(myNameState.value, myPhoneState.value)
                    Toast.makeText(this, "Solicitud aceptada. Compartiendo contacto...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El usuario rechazó el intercambio de contactos.", Toast.LENGTH_LONG).show()
                }
            },
            onContactDataReceived = { name, phone ->
                saveContactToAddressBook(name, phone)
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
        )

        remoteAudioPlayer = RemoteAudioPlayer(this)

        fileTransferManager = FileTransferManager(
            context = this,
            dbHelper = dbHelper,
            onIncomingFileRequest = { fileName, fileSize, onAccept, onReject ->
                incomingFileRequest.value = IncomingFilePrompt(fileName, fileSize, onAccept, onReject)
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            },
            onProgress = { fileName, bytes, total, completed ->
                if (transferFileName.value != fileName || !isTransferring.value) {
                    transferFileName.value = fileName
                    transferStartTime = System.currentTimeMillis()
                }
                val elapsed = Math.max(0.1, (System.currentTimeMillis() - transferStartTime) / 1000.0)
                val speedMBs = (bytes / (1024.0 * 1024.0)) / elapsed
                val remainingBytes = Math.max(0L, total - bytes)
                val bytesPerSec = bytes / elapsed
                val etaSec = if (bytesPerSec > 0) (remainingBytes / bytesPerSec).toLong() else 0L

                transferFileName.value = fileName
                isTransferring.value = !completed
                isTransferCompleted.value = completed
                transferProgress.value = if (total > 0) bytes.toFloat() / total else 0f
                transferSpeedState.value = String.format(Locale.US, "%.1f MB/s", speedMBs)
                transferEtaState.value = if (completed) "Completado" else "ETA: ${etaSec}s"

                if (completed) {
                    HapticManager.performIPhoneHaptic(this)
                    runOnUiThread {
                        Toast.makeText(this, "Transferencia finalizada: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        fileTransferManager.onAudioPlayRequested = { uri, fileName ->
            remoteAudioPlayer.play(uri, fileName)
            Toast.makeText(this, "Reproduciendo audio remoto: $fileName", Toast.LENGTH_LONG).show()
        }

        streamManager = StreamManager(this)
        streamManager.onFrameReceived = { bitmap ->
            liveScreenFrameBitmap.value = bitmap
            isReceivingScreenStream.value = true
        }

        sessionManager.onStreamRequestReceived = { type, peerId, peerName, decisionCallback ->
            streamConfirmationPrompt.value = StreamPrompt(type, peerId, peerName) { accepted ->
                decisionCallback(accepted)
                if (accepted) {
                    if (type == "SCREEN") {
                        isReceivingScreenStream.value = true
                        streamManager.startScreenServer()
                    } else {
                        isReceivingAudioStream.value = true
                        streamManager.startAudioServer()
                    }
                }
            }
        }

        sessionManager.onStreamRequestResponse = { type, accepted ->
            if (accepted) {
                val info = currentConnectionInfo.value
                val hostAddress = if (info?.isGroupOwner == true) {
                    fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                } else {
                    info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                }
                if (type == "SCREEN") {
                    isSendingScreenStream.value = true
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                } else {
                    isSendingAudioStream.value = true
                    streamManager.startAudioClient(hostAddress)
                    Toast.makeText(this, "Transmisión de audio iniciada", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "El usuario rechazó la solicitud de transmisión.", Toast.LENGTH_LONG).show()
            }
        }

        sessionManager.onStreamStopped = { type ->
            if (type == "SCREEN") {
                isReceivingScreenStream.value = false
                isSendingScreenStream.value = false
                streamManager.stopScreenStream()
            } else {
                isReceivingAudioStream.value = false
                isSendingAudioStream.value = false
                streamManager.stopAudioStream()
            }
            Toast.makeText(this, "Transmisión $type finalizada", Toast.LENGTH_SHORT).show()
        }

        sessionManager.onClipboardDataReceived = { clipText ->
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ConexionClip", clipText)
                clipboard.setPrimaryClip(clip)
                HapticManager.performIPhoneHaptic(this)
                Toast.makeText(this, "📋 Portapapeles recibido y copiado: $clipText", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(tag, "Failed to copy to clipboard", e)
            }
        }

        sessionManager.onRemoteNoteReceived = { senderName, noteText ->
            HapticManager.performIPhoneHaptic(this)
            Toast.makeText(this, "📝 Nota de $senderName: $noteText", Toast.LENGTH_LONG).show()
        }

        sessionManager.onRemoteCameraTriggered = { senderName ->
            HapticManager.performIPhoneHaptic(this)
            Toast.makeText(this, "📸 Disparo remoto recibido de $senderName", Toast.LENGTH_LONG).show()
        }

        sessionManager.onScreenShareStarted = { peerName, resolution, fps, quality ->
            activeScreenShareReceiver.value = ScreenShareSession(peerName, resolution, fps, quality)
            Toast.makeText(this, "¡$peerName está compartiendo su pantalla!", Toast.LENGTH_SHORT).show()
        }
        sessionManager.onScreenShareStopped = {
            activeScreenShareReceiver.value = null
            activeScreenShareSender.value = false
            Toast.makeText(this, "La transmisión de pantalla ha finalizado.", Toast.LENGTH_SHORT).show()
        }

        wifiP2pHelper = WifiP2pHelper(
            context = this,
            onConnectionChanged = { info ->
                currentConnectionInfo.value = info
                if (info != null && info.groupFormed) {
                    lifecycleScope.launch {
                        fileTransferManager.startServer()
                    }
                    if (info.isGroupOwner) {
                        sessionManager.startServer()
                    } else {
                        val hostAddress = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        sessionManager.connectToHost(hostAddress)
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
                                    sent = fileTransferManager.sendFile(hostAddress, uri, isAutoPlayAudioEnabled.value)
                                    if (!sent) delay(1500)
                                }
                            }
                            pendingShareUris.value = emptyList()
                        }
                    }
                    HapticManager.performIPhoneHaptic(this)
                    Toast.makeText(this, "¡Conectado exitosamente!", Toast.LENGTH_SHORT).show()
                } else {
                    fileTransferManager.stopServer()
                    sessionManager.stop()
                }
            },
            onPeersDiscovered = { peers ->
                peersState.value = peers
                peers.forEach { peer ->
                    dbHelper.saveOrUpdatePeer(
                        name = peer.userName,
                        token = peer.sessionToken,
                        phone = peer.phoneNumber,
                        avatar = peer.avatarIndex
                    )
                }
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

        mapManager = MapManager(this)
        pmTilesTileServer = PmTilesTileServer(8090)
        pmTilesTileServer.start()

        val defaultMuni = mapManager.municipalities.firstOrNull { it.province == "Havana" } ?: mapManager.municipalities.firstOrNull()
        selectedMunicipality.value = defaultMuni
        if (defaultMuni != null && mapManager.isMapDownloaded(defaultMuni)) {
            val f = mapManager.getLocalMapFile(defaultMuni)
            currentMapFile.value = f
            pmTilesTileServer.setMapFile(f)
        }

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val lastLoc = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } else null

            lastLoc?.let { loc ->
                userLocationState.value = Pair(loc.latitude, loc.longitude)
                val detected = mapManager.findMunicipalityForLocation(loc.latitude, loc.longitude)
                if (detected != null) {
                    gpsDetectedMunicipality.value = detected
                    selectedMunicipality.value = detected
                    if (mapManager.isMapDownloaded(detected)) {
                        val f = mapManager.getLocalMapFile(detected)
                        currentMapFile.value = f
                        pmTilesTileServer.setMapFile(f)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "GPS detection error", e)
        }

        toggleWifi(true)

        val bgFilter = IntentFilter().apply {
            addAction(BackgroundDiscoveryService.ACTION_PEER_SENDING)
            addAction(BackgroundDiscoveryService.ACTION_BEACON_TOKEN_DECODED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bgServiceReceiver, bgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bgServiceReceiver, bgFilter)
        }

        handleIntent(intent)
        handleShareIntent(intent)

        setContent {
            AppTheme(themeIndex = currentThemeIndex.value, isDark = isDarkMode.value) {
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

    private fun calculateRssiFallbackLocation(
        userLat: Double,
        userLon: Double,
        distanceMeters: Double,
        token: String
    ): Pair<Double, Double> {
        val bearingDeg = ((token.hashCode() and 0x7FFFFFFF) % 360).toDouble()
        val bearingRad = Math.toRadians(bearingDeg)
        val dLat = (distanceMeters * Math.cos(bearingRad)) / 111000.0
        val dLon = (distanceMeters * Math.sin(bearingRad)) / (111000.0 * Math.cos(Math.toRadians(userLat)))
        return Pair(userLat + dLat, userLon + dLon)
    }

    private fun loadChatMessagesForPeer(peerDeviceId: String) {
        val msgs = dbHelper.getChatMessages(peerDeviceId)
        chatMessages.clear()
        chatMessages.addAll(msgs)
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
            val avatarIdx = intent.getIntExtra("EXTRA_PEER_AVATAR", 0)
            val lat = intent.getDoubleExtra("EXTRA_PEER_LAT", 0.0)
            val lon = intent.getDoubleExtra("EXTRA_PEER_LON", 0.0)
            val latitude = if (lat != 0.0) lat else null
            val longitude = if (lon != 0.0) lon else null
            Log.d(tag, "Handled launch intent with BLE match: $name, token=$token, avatarIndex=$avatarIdx")
            val peer = BackgroundDiscoveryService.BlePeer(
                userName = name,
                sessionToken = token,
                state = state,
                avatarIndex = avatarIdx,
                latitude = latitude,
                longitude = longitude
            )
            sendingCandidates[token] = peer
            blePeersMap[token] = peer
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
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.let {
            isWifiEnabledState.value = it.isWifiEnabled
        }
        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        isBgDiscoveryEnabled.value = prefs.getBoolean("bg_discovery_enabled", false)
        if (isBgDiscoveryEnabled.value) {
            startBgDiscoveryService()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bgServiceReceiver)
        } catch (e: Exception) {}
        wifiP2pHelper.unregister()
        fileTransferManager.stopServer()
        airShareServer?.stop()
        remoteAudioPlayer.stop()
        pmTilesTileServer.stop()

        if (currentConnectionInfo.value == null) {
            toggleWifi(false)
        }
    }

    private var airShareServer: AirShareServer? = null
    private var isAirShareServerActive = mutableStateOf(false)
    private var airShareLocalIp = mutableStateOf("")

    private fun getLocalIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (networkInterface in Collections.list(interfaces)) {
                    val addresses = networkInterface.inetAddresses
                    for (address in Collections.list(addresses)) {
                        if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            val host = address.hostAddress
                            if (host != null && host.indexOf(':') < 0) {
                                val name = networkInterface.name.lowercase()
                                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                                    return host
                                }
                            }
                        }
                    }
                }
                for (networkInterface in Collections.list(interfaces)) {
                    val addresses = networkInterface.inetAddresses
                    for (address in Collections.list(addresses)) {
                        if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            val host = address.hostAddress
                            if (host != null && host.indexOf(':') < 0) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(tag, "Error getting NetworkInterface IP", ex)
        }
        return "127.0.0.1"
    }

    private fun toggleAirShareServer() {
        val serverActive = isAirShareServerActive.value
        if (serverActive) {
            airShareServer?.stop()
            isAirShareServerActive.value = false
        } else {
            airShareLocalIp.value = getLocalIpAddress(this)
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Conexion")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            airShareServer = AirShareServer(
                context = this,
                myNameProvider = { myNameState.value },
                myPhoneProvider = { myPhoneState.value },
                onMessageReceived = { message ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "AirShare Message: $message", Toast.LENGTH_SHORT).show()
                        HapticManager.performIPhoneHaptic(this@MainActivity)
                    }
                },
                onContactReceived = { name, phone ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        saveContactToAddressBook(name, phone)
                    }
                },
                onFileReceived = { savedFileName ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        showTransferNotification(savedFileName)
                        Toast.makeText(this@MainActivity, "AirShare: Archivo recibido: $savedFileName", Toast.LENGTH_LONG).show()
                    }
                },
                onServerStopped = {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isAirShareServerActive.value = false
                    }
                }
            )
            airShareServer?.start()
            isAirShareServerActive.value = true
            Toast.makeText(this, "Servidor AirShare iniciado en http://${airShareLocalIp.value}:8989", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTransferNotification(fileName: String) {
        val channelId = "airshare_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Transferencias AirShare",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Archivo recibido")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(99, notification)
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
        return (1..12).map { allowedChars.random() }.joinToString("")
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

    private fun saveContactToAddressBook(name: String, phone: String) {
        if (phone.isEmpty()) {
            Toast.makeText(this, "El teléfono de $name está vacío, no se guardó.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val contentResolver = contentResolver
            val ops = java.util.ArrayList<android.content.ContentProviderOperation>()

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())

            contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
            HapticManager.performIPhoneHaptic(this)
            Toast.makeText(this, "¡Contacto $name guardado exitosamente!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(tag, "Failed to save contact", e)
            Toast.makeText(this, "Fallo al guardar el contacto: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CONTACTS
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

    // QR Code Generator Function for AirShare Web
    private fun generateQrCodeBitmap(content: String): Bitmap? {
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
            Log.e(tag, "Failed to generate QR code bitmap", e)
            null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppScreen() {
        val context = LocalContext.current
        var currentTab by remember { mutableStateOf(0) }

        var isAirDropSheetOpen by remember { mutableStateOf(false) }

        val selectFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                val info = currentConnectionInfo.value
                val hostAddress = if (info?.isGroupOwner == true) {
                    fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                } else {
                    info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                }
                transferStartTime = System.currentTimeMillis()
                val fName = getFileNameFromUri(context, uri)
                transferFileName.value = fName
                transferProgress.value = 0f
                isTransferring.value = true
                isTransferCompleted.value = false
                lifecycleScope.launch {
                    fileTransferManager.sendFile(hostAddress, uri, isAutoPlayAudioEnabled.value)
                }
                Toast.makeText(context, "Enviando $fName...", Toast.LENGTH_SHORT).show()
            }
        }

        // Peer Action dialog states
        var selectedPeerForActions by remember { mutableStateOf<PeerInfo?>(null) }
        var selectedBlePeerForActions by remember { mutableStateOf<BackgroundDiscoveryService.BlePeer?>(null) }

        val peerMarkers = remember(peersState.value, blePeersMap.values.toList(), userLocationState.value) {
            val markers = mutableListOf<PeerMapMarker>()
            val uLoc = userLocationState.value

            blePeersMap.values.forEach { bpeer ->
                if (bpeer.latitude != null && bpeer.longitude != null) {
                    markers.add(PeerMapMarker(bpeer.sessionToken, bpeer.userName, bpeer.latitude, bpeer.longitude, isExactGps = true))
                } else if (uLoc != null) {
                    val (fLat, fLon) = calculateRssiFallbackLocation(uLoc.first, uLoc.second, bpeer.distanceMeters, bpeer.sessionToken)
                    markers.add(PeerMapMarker(bpeer.sessionToken, bpeer.userName, fLat, fLon, isExactGps = false))
                }
            }

            peersState.value.forEach { peer ->
                if (markers.none { it.id == peer.sessionToken }) {
                    if (peer.latitude != null && peer.longitude != null) {
                        markers.add(PeerMapMarker(peer.sessionToken, peer.userName, peer.latitude, peer.longitude, isExactGps = true))
                    } else if (uLoc != null) {
                        val (fLat, fLon) = calculateRssiFallbackLocation(uLoc.first, uLoc.second, peer.distanceMeters, peer.sessionToken)
                        markers.add(PeerMapMarker(peer.sessionToken, peer.userName, fLat, fLon, isExactGps = false))
                    }
                }
            }

            markers
        }

        Column(modifier = Modifier.fillMaxSize()) {
            DynamicIslandBar(
                isDark = isDarkMode.value,
                isConnected = currentConnectionInfo.value?.groupFormed ?: false,
                connectedPeerName = currentConnectionInfo.value?.groupOwnerAddress?.hostAddress ?: "",
                isTransferring = isTransferring.value,
                transferProgress = transferProgress.value,
                transferSpeed = transferSpeedState.value,
                onToggleTheme = {
                    isDarkMode.value = !isDarkMode.value
                    getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("dark_mode", isDarkMode.value)
                        .apply()
                },
                onOpenAirDrop = {
                    isAirDropSheetOpen = true
                }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    0 -> RadarTabScreen(
                        myAvatarIndex = myAvatarState.value,
                        myName = myNameState.value,
                        peers = peersState.value,
                        blePeers = blePeersMap.values.toList(),
                        isDark = isDarkMode.value,
                        isConnected = currentConnectionInfo.value?.groupFormed ?: false,
                        connectedDeviceName = "",
                        connectedDeviceAddress = currentConnectionInfo.value?.groupOwnerAddress?.hostAddress ?: "",
                        isSearching = false,
                        mapManager = mapManager,
                        selectedMunicipality = selectedMunicipality.value,
                        isMapDownloading = isMapDownloading.value,
                        downloadProgress = downloadMapProgress.value,
                        currentMapFile = currentMapFile.value,
                        gpsDetectedMunicipality = gpsDetectedMunicipality.value,
                        userLocation = userLocationState.value,
                        peerMarkers = peerMarkers,
                        onSelectMunicipality = { item ->
                            selectedMunicipality.value = item
                            if (mapManager.isMapDownloaded(item)) {
                                val f = mapManager.getLocalMapFile(item)
                                currentMapFile.value = f
                                pmTilesTileServer.setMapFile(f)
                            } else {
                                currentMapFile.value = null
                            }
                        },
                        onDownloadMap = { item ->
                            isMapDownloading.value = true
                            downloadMapProgress.value = 0f
                            lifecycleScope.launch {
                                mapManager.downloadMap(
                                    item = item,
                                    onProgress = { p -> downloadMapProgress.value = p },
                                    onSuccess = { f ->
                                        isMapDownloading.value = false
                                        currentMapFile.value = f
                                        pmTilesTileServer.setMapFile(f)
                                        Toast.makeText(this@MainActivity, "Mapa de ${item.municipality} descargado con éxito", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        isMapDownloading.value = false
                                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        onShareMapP2P = { mapF ->
                            val info = currentConnectionInfo.value
                            if (info != null && info.groupFormed) {
                                val hostAddress = if (info.isGroupOwner) {
                                    fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                                } else {
                                    info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                }
                                lifecycleScope.launch {
                                    Toast.makeText(this@MainActivity, "Enviando mapa .pmtiles vía P2P...", Toast.LENGTH_SHORT).show()
                                    fileTransferManager.sendFile(hostAddress, Uri.fromFile(mapF))
                                }
                            } else {
                                Toast.makeText(this@MainActivity, "Conecta primero un dispositivo P2P para enviar el mapa", Toast.LENGTH_LONG).show()
                            }
                        },
                        onStartDiscovery = {
                            wifiP2pHelper.startAdvertising(myNameState.value, myDeviceIdState.value)
                            wifiP2pHelper.startDiscovery()
                            Toast.makeText(context, "Buscando dispositivos y anunciando presencia...", Toast.LENGTH_SHORT).show()
                        },
                        onSendAcousticPulse = {
                            val token = generateSessionToken()
                            audioBeaconEmitter?.start(token)
                            HapticManager.performIPhoneHaptic(context)
                            Toast.makeText(context, "Emitiendo pulso ultrasónico...", Toast.LENGTH_SHORT).show()
                        },
                        onDisconnect = {
                            wifiP2pHelper.disconnect()
                        },
                        onPeerSelected = { selectedPeerForActions = it },
                        onBlePeerSelected = { selectedBlePeerForActions = it },
                        onSendClipboard = { text ->
                            sessionManager.sendClipboardData(text)
                        },
                        onSendRemoteNote = { noteText ->
                            sessionManager.sendRemoteNote(myNameState.value, noteText)
                        },
                        onStartWalkieTalkie = {
                            sessionManager.sendStreamRequest("AUDIO", myNameState.value)
                        },
                        onSendRemoteCameraTrigger = {
                            sessionManager.sendRemoteCameraTrigger(myNameState.value)
                        }
                    )
                    1 -> ChatsTabScreen(
                        myAvatarIndex = myAvatarState.value,
                        myName = myNameState.value,
                        activeChatPeerDeviceId = activeChatPeerDeviceId.value,
                        activeChatPeerName = activeChatPeerName.value,
                        chatMessages = chatMessages.toList(),
                        peers = peersState.value,
                        blePeers = blePeersMap.values.toList(),
                        isDark = isDarkMode.value,
                        onSelectPeerToChat = { devId, name ->
                            activeChatPeerDeviceId.value = devId
                            activeChatPeerName.value = name
                            loadChatMessagesForPeer(devId)
                        },
                        onCloseChat = {
                            activeChatPeerDeviceId.value = ""
                        },
                        onSendMessage = { text ->
                            val m = sessionManager.sendChatMessage(text, myNameState.value, activeChatPeerDeviceId.value)
                            chatMessages.add(m)
                        },
                        onSendClipboard = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString() ?: ""
                                if (text.isNotEmpty()) {
                                    sessionManager.sendClipboardData(text)
                                    HapticManager.performLightClick(this@MainActivity)
                                    Toast.makeText(this@MainActivity, "📋 Portapapeles enviado", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onPeerSelected = { selectedPeerForActions = it },
                        onBlePeerSelected = { selectedBlePeerForActions = it }
                    )
                    2 -> TransfersTabScreen(
                        isTransferring = isTransferring.value,
                        transferFileName = transferFileName.value,
                        transferProgress = transferProgress.value,
                        transferSpeed = transferSpeedState.value,
                        transferEta = transferEtaState.value,
                        isDark = isDarkMode.value,
                        transferHistory = dbHelper.getTransferHistory(),
                        onSelectFileToSend = {
                            selectFileLauncher.launch("*/*")
                        }
                    )
                    3 -> TransmissionTabScreen(
                        isDark = isDarkMode.value,
                        isScreenShareEnabled = isScreenShareEnabled.value,
                        isReceivingScreenStream = isReceivingScreenStream.value,
                        liveScreenFrameBitmap = liveScreenFrameBitmap.value,
                        isAirShareServerActive = isAirShareServerActive.value,
                        airShareLocalIp = airShareLocalIp.value,
                        qrBitmap = if (isAirShareServerActive.value) generateQrCodeBitmap("http://${airShareLocalIp.value}:8989") else null,
                        onToggleScreenShare = { isScreenShareEnabled.value = it },
                        onStartScreenStreaming = {
                            sessionManager.sendStreamRequest("SCREEN", myNameState.value)
                            Toast.makeText(this@MainActivity, "Solicitando inicio de compartir pantalla...", Toast.LENGTH_SHORT).show()
                        },
                        onStartAudioStreaming = {
                            sessionManager.sendStreamRequest("AUDIO", myNameState.value)
                            Toast.makeText(this@MainActivity, "Solicitando inicio de transmisión de audio...", Toast.LENGTH_SHORT).show()
                        },
                        onToggleAirShareServer = {
                            toggleAirShareServer()
                        }
                    )
                    4 -> SettingsTabScreen(
                        myName = myNameState.value,
                        myPhone = myPhoneState.value,
                        myAvatarIndex = myAvatarState.value,
                        currentThemeIndex = currentThemeIndex.value,
                        isDarkMode = isDarkMode.value,
                        isBgDiscoveryEnabled = isBgDiscoveryEnabled.value,
                        mapManager = mapManager,
                        selectedMunicipality = selectedMunicipality.value,
                        isMapDownloading = isMapDownloading.value,
                        downloadProgress = downloadMapProgress.value,
                        currentMapFile = currentMapFile.value,
                        gpsDetectedMunicipality = gpsDetectedMunicipality.value,
                        onSelectMunicipality = { item ->
                            selectedMunicipality.value = item
                            if (mapManager.isMapDownloaded(item)) {
                                val f = mapManager.getLocalMapFile(item)
                                currentMapFile.value = f
                                pmTilesTileServer.setMapFile(f)
                            } else {
                                currentMapFile.value = null
                            }
                        },
                        onDownloadMap = { item ->
                            isMapDownloading.value = true
                            downloadMapProgress.value = 0f
                            lifecycleScope.launch {
                                mapManager.downloadMap(
                                    item = item,
                                    onProgress = { p -> downloadMapProgress.value = p },
                                    onSuccess = { f ->
                                        isMapDownloading.value = false
                                        currentMapFile.value = f
                                        pmTilesTileServer.setMapFile(f)
                                        Toast.makeText(this@MainActivity, "Mapa de ${item.municipality} descargado con éxito", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        isMapDownloading.value = false
                                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        onShareMapP2P = { mapFile ->
                            val currentConnected = currentConnectionInfo.value?.groupOwnerAddress?.hostAddress
                            if (!currentConnected.isNullOrEmpty()) {
                                lifecycleScope.launch {
                                    fileTransferManager.sendFile(currentConnected, Uri.fromFile(mapFile))
                                }
                                Toast.makeText(this@MainActivity, "Enviando mapa P2P...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Conéctate a un dispositivo P2P para enviar el mapa", Toast.LENGTH_LONG).show()
                            }
                        },
                        onSaveProfile = { name, phone, avatar ->
                            myNameState.value = name
                            myPhoneState.value = phone
                            myAvatarState.value = avatar
                            dbHelper.saveProfile(name, phone, avatar, myDeviceIdState.value)
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("user_name", name)
                                .putString("phone_number", phone)
                                .putInt("avatar_index", avatar)
                                .apply()
                        },
                        onSelectTheme = { idx ->
                            currentThemeIndex.value = idx
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("theme_index", idx)
                                .apply()
                        },
                        onToggleDarkMode = { dark ->
                            isDarkMode.value = dark
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("dark_mode", dark)
                                .apply()
                        },
                        onToggleBgDiscovery = { enabled ->
                            isBgDiscoveryEnabled.value = enabled
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("bg_discovery_enabled", enabled)
                                .apply()
                            if (enabled) startBgDiscoveryService() else stopBgDiscoveryService()
                        }
                    )
                }
            }

            NavigationBar(
                containerColor = if (isDarkMode.value) Color(0xFF161F2E).copy(alpha = 0.95f) else Color(0xFFFFFFFF).copy(alpha = 0.95f),
                tonalElevation = 8.dp
            ) {
                val navItems = listOf(
                    Triple("Escáner", "📡", 0),
                    Triple("Chats", "💬", 1),
                    Triple("Envíos", "⚡", 2),
                    Triple("Media", "📺", 3),
                    Triple("Ajustes", "⚙️", 4)
                )

                navItems.forEach { (label, icon, tabIdx) ->
                    val isSelected = currentTab == tabIdx
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            HapticManager.performLightClick(context)
                            currentTab = tabIdx
                        },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        )
                    )
                }
            }
        }

        AirDropBottomSheet(
            isOpen = isAirDropSheetOpen,
            onDismiss = { isAirDropSheetOpen = false },
            myName = myNameState.value,
            myAvatarIndex = myAvatarState.value,
            discoveredPeers = peersState.value,
            blePeers = blePeersMap.values.toList(),
            onPeerSelected = { peer ->
                isAirDropSheetOpen = false
                selectedPeerForActions = peer
            },
            onBlePeerSelected = { bpeer ->
                isAirDropSheetOpen = false
                selectedBlePeerForActions = bpeer
            },
            isSearching = false,
            onToggleSearch = { wifiP2pHelper.startDiscovery() }
        )

        connectionPromptPeer.value?.let { peer ->
            AlertDialog(
                onDismissRequest = { connectionPromptPeer.value = null },
                title = { Text("Conexión Detectada") },
                text = { Text("¿Deseas conectar con ${peer.userName}?", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = {
                        wifiP2pHelper.connectToPeer(peer)
                        connectionPromptPeer.value = null
                    }) {
                        Text("Sí, Conectar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { connectionPromptPeer.value = null }) { Text("Rechazar") }
                }
            )
        }

        selectedPeerForActions?.let { peer ->
            val isStarred = dbHelper.isDeviceStarred(peer.sessionToken.ifEmpty { peer.device.deviceAddress })
            AlertDialog(
                onDismissRequest = { selectedPeerForActions = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBubble(avatarIndex = peer.avatarIndex, size = 42.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(peer.userName)
                            if (isStarred) {
                                Text("⭐ Usuario Recordado", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                text = { Text("Elige qué acción realizar con este dispositivo:", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                activeChatPeerDeviceId.value = peer.sessionToken
                                activeChatPeerName.value = peer.userName
                                loadChatMessagesForPeer(peer.sessionToken)
                                if (currentConnectionInfo.value?.groupFormed == true) {
                                    sessionManager.sendChatRequest(myNameState.value)
                                } else {
                                    wifiP2pHelper.connectToPeer(peer)
                                    Toast.makeText(context, "Conectando P2P para iniciar chat con ${peer.userName}...", Toast.LENGTH_SHORT).show()
                                }
                                currentTab = 1
                                selectedPeerForActions = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("💬 Iniciar Chat")
                        }
                        Button(
                            onClick = {
                                sessionManager.sendContactRequest(myNameState.value)
                                Toast.makeText(context, "Solicitando intercambio de contacto...", Toast.LENGTH_SHORT).show()
                                selectedPeerForActions = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("📇 Intercambiar Contacto")
                        }
                        Button(
                            onClick = {
                                wifiP2pHelper.connectToPeer(peer)
                                selectedPeerForActions = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("🔗 Conectar y Enviar Archivos")
                        }
                        OutlinedButton(
                            onClick = {
                                val devId = peer.sessionToken.ifEmpty { peer.device.deviceAddress }
                                val newStarState = dbHelper.toggleStarDevice(devId)
                                val msg = if (newStarState) "⭐ Usuario ${peer.userName} recordado para conexión automática" else "Usuario ${peer.userName} olvidado"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                selectedPeerForActions = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(if (isStarred) "⭐ Olvidar Usuario" else "⭐ Recordar este Usuario")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedPeerForActions = null }) { Text("Cancelar") }
                }
            )
        }

        selectedBlePeerForActions?.let { bpeer ->
            AlertDialog(
                onDismissRequest = { selectedBlePeerForActions = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBubble(avatarIndex = bpeer.avatarIndex, size = 42.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(bpeer.userName)
                    }
                },
                text = { Text("Dispositivo detectado de forma pasiva BLE. ¿Intentar conexión?", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = {
                        val p2pPeer = wifiP2pHelper.findPeerByToken(bpeer.sessionToken)
                        if (p2pPeer != null) {
                            wifiP2pHelper.connectToPeer(p2pPeer)
                        } else {
                            wifiP2pHelper.startDiscoveryForToken(bpeer.sessionToken, bpeer.userName)
                        }
                        selectedBlePeerForActions = null
                    }) {
                        Text("Conectar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedBlePeerForActions = null }) { Text("Cancelar") }
                }
            )
        }

        chatRequestPrompt.value?.let { prompt ->
            AlertDialog(
                onDismissRequest = {
                    prompt.onDecision(false)
                    chatRequestPrompt.value = null
                },
                title = { Text("Solicitud de Chat") },
                text = { Text("${prompt.peerName} quiere iniciar un chat contigo. ¿Aceptas?") },
                confirmButton = {
                    Button(onClick = {
                        prompt.onDecision(true)
                        chatRequestPrompt.value = null
                        currentTab = 1
                    }) {
                        Text("Aceptar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        prompt.onDecision(false)
                        chatRequestPrompt.value = null
                    }) { Text("Rechazar") }
                }
            )
        }

        contactRequestPrompt.value?.let { prompt ->
            AlertDialog(
                onDismissRequest = {
                    prompt.onDecision(false)
                    contactRequestPrompt.value = null
                },
                title = { Text("Intercambiar Contactos") },
                text = { Text("${prompt.peerName} quiere intercambiar contactos mutuamente. ¿Proceder?") },
                confirmButton = {
                    Button(onClick = {
                        prompt.onDecision(true)
                        contactRequestPrompt.value = null
                    }) {
                        Text("Intercambiar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        prompt.onDecision(false)
                        contactRequestPrompt.value = null
                    }) { Text("Rechazar") }
                }
            )
        }

        streamConfirmationPrompt.value?.let { prompt ->
            AlertDialog(
                onDismissRequest = {
                    prompt.onDecision(false)
                    streamConfirmationPrompt.value = null
                },
                title = { Text(if (prompt.streamType == "SCREEN") "📺 Transmisión de Pantalla" else "🎙️ Transmisión de Audio") },
                text = { Text("${prompt.peerName} desea transmitir ${if (prompt.streamType == "SCREEN") "su pantalla" else "su micrófono"} en vivo contigo. ¿Aceptas?") },
                confirmButton = {
                    Button(onClick = {
                        prompt.onDecision(true)
                        streamConfirmationPrompt.value = null
                    }) {
                        Text("Aceptar Transmisión")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        prompt.onDecision(false)
                        streamConfirmationPrompt.value = null
                    }) { Text("Rechazar") }
                }
            )
        }

        if (isReceivingScreenStream.value) {
            AlertDialog(
                onDismissRequest = {
                    isReceivingScreenStream.value = false
                    streamManager.stopScreenStream()
                    sessionManager.sendStreamStop("SCREEN")
                },
                title = { Text("📺 Pantalla Remota en Vivo", fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val frame = liveScreenFrameBitmap.value
                        if (frame != null) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "Pantalla Remota",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(380.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Esperando fotogramas...", color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isReceivingScreenStream.value = false
                            streamManager.stopScreenStream()
                            sessionManager.sendStreamStop("SCREEN")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cerrar Transmisión")
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
                text = { Text("¿Deseas aceptar \"${request.fileName}\" ($sizeInMb)?") },
                confirmButton = {
                    Button(onClick = {
                        request.onAccept()
                        incomingFileRequest.value = null
                    }) {
                        Text("Aceptar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        request.onReject()
                        incomingFileRequest.value = null
                    }) { Text("Rechazar") }
                }
            )
        }
    }
}
