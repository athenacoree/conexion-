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

    // Progress updates
    private var transferFileName = mutableStateOf("")
    private var transferProgress = mutableStateOf(0f)
    private var isTransferring = mutableStateOf(false)
    private var isTransferCompleted = mutableStateOf(false)

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

    // App Theme State (Feature 2)
    private var currentThemeIndex = mutableStateOf(0)

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
                Log.d(tag, "Received BLE peer sending: $name, token=$token, isAmbiguous=$isAmbiguous, avatarIndex=$avatarIdx")

                val peer = BackgroundDiscoveryService.BlePeer(name, token, 1, avatarIdx)
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

        val savedProf = dbHelper.getProfile()
        if (savedProf != null) {
            myNameState.value = savedProf.first
            myPhoneState.value = savedProf.second
            myAvatarState.value = savedProf.third
        } else {
            val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
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
                transferFileName.value = fileName
                isTransferring.value = !completed
                isTransferCompleted.value = completed
                transferProgress.value = if (total > 0) bytes.toFloat() / total else 0f
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
            AppTheme(themeIndex = currentThemeIndex.value) {
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
            Log.d(tag, "Handled launch intent with BLE match: $name, token=$token, avatarIndex=$avatarIdx")
            val peer = BackgroundDiscoveryService.BlePeer(name, token, state, avatarIdx)
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

    // --- App Themes (Feature 2) ---
    data class ThemePreset(
        val name: String,
        val primary: Color,
        val secondary: Color,
        val background: Color,
        val surface: Color,
        val gradient: List<Color>
    )

    companion object {
        val THEME_PRESETS = listOf(
            ThemePreset("Purple Aurora", Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF0F172A), Color(0xFF1E293B), listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))),
            ThemePreset("Midnight Glass", Color(0xFF3B82F6), Color(0xFF06B6D4), Color(0xFF020617), Color(0xFF0F172A), listOf(Color(0xFF3B82F6), Color(0xFF06B6D4))),
            ThemePreset("Emerald Glow", Color(0xFF10B981), Color(0xFF059669), Color(0xFF064E3B), Color(0xFF047857), listOf(Color(0xFF10B981), Color(0xFF34D399))),
            ThemePreset("Cyber Neon", Color(0xFFF43F5E), Color(0xFF8B5CF6), Color(0xFF18181B), Color(0xFF27272A), listOf(Color(0xFFF43F5E), Color(0xFFA855F7)))
        )

        val AVATAR_DESIGNS = listOf(
            AvatarDesign("🦁", listOf(Color(0xFFFF9800), Color(0xFFFF5722)), Color.White),
            AvatarDesign("🦄", listOf(Color(0xFFE91E63), Color(0xFF9C27B0)), Color.White),
            AvatarDesign("🐨", listOf(Color(0xFF607D8B), Color(0xFF90A4AE)), Color.White),
            AvatarDesign("🐼", listOf(Color(0xFF212121), Color(0xFF757575)), Color.White),
            AvatarDesign("🦊", listOf(Color(0xFFFF5722), Color(0xFFFFC107)), Color.White),
            AvatarDesign("🐙", listOf(Color(0xFF2196F3), Color(0xFF00BCD4)), Color.White),
            AvatarDesign("🦖", listOf(Color(0xFF4CAF50), Color(0xFF8BC34A)), Color.White),
            AvatarDesign("🦉", listOf(Color(0xFF795548), Color(0xFFA1887F)), Color.White)
        )
    }

    data class AvatarDesign(val emoji: String, val bgGradients: List<Color>, val contentColor: Color)

    @Composable
    fun AppTheme(themeIndex: Int, content: @Composable () -> Unit) {
        val preset = THEME_PRESETS.getOrElse(themeIndex) { THEME_PRESETS[0] }
        val colorScheme = darkColorScheme(
            primary = preset.primary,
            secondary = preset.secondary,
            background = preset.background,
            surface = preset.surface,
            surfaceVariant = preset.surface.copy(alpha = 0.8f)
        )
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    @Composable
    fun AvatarBubble(
        avatarIndex: Int,
        size: androidx.compose.ui.unit.Dp,
        modifier: Modifier = Modifier
    ) {
        val design = AVATAR_DESIGNS.getOrElse(avatarIndex) { AVATAR_DESIGNS[0] }
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(design.bgGradients))
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = design.emoji,
                fontSize = (size.value * 0.5f).sp
            )
        }
    }

    // Modern Sonar Radar
    @Composable
    fun ModernSonarRadar(
        myAvatarIndex: Int,
        peers: List<PeerInfo>,
        blePeers: List<BackgroundDiscoveryService.BlePeer>,
        onPeerClick: (PeerInfo) -> Unit,
        onBlePeerClick: (BackgroundDiscoveryService.BlePeer) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val context = LocalContext.current
        val infiniteTransition = rememberInfiniteTransition()

        val ripple1 = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
        val ripple2 = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(2000)
            )
        )
        val sweepAngle = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        val primaryColor = MaterialTheme.colorScheme.primary

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.width.coerceAtMost(size.height) / 2 * 0.85f

                drawCircle(
                    color = primaryColor.copy(alpha = 0.15f * (1f - ripple1.value)),
                    radius = maxRadius * ripple1.value,
                    center = center
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.15f * (1f - ripple2.value)),
                    radius = maxRadius * ripple2.value,
                    center = center
                )

                for (i in 1..4) {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.2f),
                        radius = maxRadius * (i / 4f),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                drawLine(
                    color = primaryColor.copy(alpha = 0.15f),
                    start = Offset(center.x - maxRadius, center.y),
                    end = Offset(center.x + maxRadius, center.y),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = primaryColor.copy(alpha = 0.15f),
                    start = Offset(center.x, center.y - maxRadius),
                    end = Offset(center.x, center.y + maxRadius),
                    strokeWidth = 1.dp.toPx()
                )

                val angleRad = Math.toRadians(sweepAngle.value.toDouble())
                val endX = center.x + maxRadius * Math.cos(angleRad).toFloat()
                val endY = center.y + maxRadius * Math.sin(angleRad).toFloat()
                drawLine(
                    color = primaryColor.copy(alpha = 0.5f),
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(3.dp, primaryColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AvatarBubble(avatarIndex = myAvatarIndex, size = 48.dp)
            }

            val allNodes = remember(peers, blePeers) {
                val list = mutableListOf<SonarNode>()
                peers.forEachIndexed { idx, p ->
                    val angle = 45f + idx * 75f
                    val distanceFactor = 0.45f + (idx % 2) * 0.25f
                    list.add(SonarNode.WifiPeer(p, angle, distanceFactor))
                }
                var bleCount = 0
                blePeers.forEach { bp ->
                    if (peers.none { it.sessionToken == bp.sessionToken }) {
                        val angle = 110f + bleCount * 85f
                        val distanceFactor = 0.55f + (bleCount % 2) * 0.25f
                        list.add(SonarNode.BlePeer(bp, angle, distanceFactor))
                        bleCount++
                    }
                }
                list
            }

            allNodes.forEach { node ->
                val angleRad = Math.toRadians(node.angle.toDouble())
                val distancePx = 100 * node.distanceFactor

                val offsetX = (distancePx * Math.cos(angleRad)).toFloat()
                val offsetY = (distancePx * Math.sin(angleRad)).toFloat()

                Box(
                    modifier = Modifier
                        .offset(x = offsetX.dp, y = offsetY.dp)
                        .clickable {
                            HapticManager.performLightClick(context)
                            when (node) {
                                is SonarNode.WifiPeer -> onPeerClick(node.peer)
                                is SonarNode.BlePeer -> onBlePeerClick(node.blePeer)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF334155))
                                .border(
                                    2.dp,
                                    if (node is SonarNode.WifiPeer) Color(0xFF10B981) else Color(0xFF3B82F6),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarBubble(avatarIndex = node.avatarIndex, size = 36.dp)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = node.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    sealed class SonarNode(val angle: Float, val distanceFactor: Float) {
        abstract val name: String
        abstract val avatarIndex: Int

        class WifiPeer(val peer: PeerInfo, angle: Float, distanceFactor: Float) : SonarNode(angle, distanceFactor) {
            override val name: String = peer.userName
            override val avatarIndex: Int = peer.avatarIndex
        }

        class BlePeer(val blePeer: BackgroundDiscoveryService.BlePeer, angle: Float, distanceFactor: Float) : SonarNode(angle, distanceFactor) {
            override val name: String = blePeer.userName
            override val avatarIndex: Int = blePeer.avatarIndex
        }
    }

    // QR Code Generator Function for Feature 1
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
        var currentTab by remember { mutableStateOf(0) } // 0: Radar, 1: Chats, 2: Transferencias, 3: Transmisión, 4: Ajustes

        // Peer Action dialog states
        var selectedPeerForActions by remember { mutableStateOf<PeerInfo?>(null) }
        var selectedBlePeerForActions by remember { mutableStateOf<BackgroundDiscoveryService.BlePeer?>(null) }

        Column(modifier = Modifier.fillMaxSize()) {
            // Main Content Area based on Selected Bottom Tab
            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    0 -> RadarTabScreen(
                        onPeerSelected = { selectedPeerForActions = it },
                        onBlePeerSelected = { selectedBlePeerForActions = it }
                    )
                    1 -> ChatsTabScreen()
                    2 -> TransfersTabScreen()
                    3 -> TransmissionTabScreen()
                    4 -> SettingsTabScreen()
                }
            }

            // Modern iOS Glassmorphism Bottom Navigation Bar
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
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
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }

        // Connection Prompts & Action Dialogs
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
            AlertDialog(
                onDismissRequest = { selectedPeerForActions = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBubble(avatarIndex = peer.avatarIndex, size = 42.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(peer.userName)
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
                                sessionManager.sendChatRequest(myNameState.value)
                                currentTab = 1 // Switch to chats tab!
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

        // Chat Request Dialog
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

        // Contact Request Dialog
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

    // --- TAB 0: Radar & Escáner ---
    @Composable
    fun RadarTabScreen(
        onPeerSelected: (PeerInfo) -> Unit,
        onBlePeerSelected: (BackgroundDiscoveryService.BlePeer) -> Unit
    ) {
        val context = LocalContext.current
        val isConnected = currentConnectionInfo.value?.groupFormed ?: false

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Escáner Sonal y Búsqueda",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Detección rápida de dispositivos cercanos por Wi-Fi Direct y BLE",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ModernSonarRadar(
                myAvatarIndex = myAvatarState.value,
                peers = peersState.value,
                blePeers = blePeersMap.values.toList(),
                onPeerClick = onPeerSelected,
                onBlePeerClick = onBlePeerSelected
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Proximity Sonar Bump Feature Card (Feature 3)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📡 Acoustic Sonar Bump", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Emite un pulso ultrasónico para choque de teléfonos y sincronización rápida.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Button(
                        onClick = {
                            val token = generateSessionToken()
                            audioBeaconEmitter?.start(token)
                            HapticManager.performIPhoneHaptic(context)
                            Toast.makeText(context, "Emitiendo tono ultrasónico...", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Emitir Pulso")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    wifiP2pHelper.startDiscovery()
                    HapticManager.performLightClick(context)
                    Toast.makeText(context, "Buscando dispositivos cercanos...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Escanear Ahora")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isConnected) "Estado: Conectado" else "Estado: Desconectado",
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                        if (isConnected) {
                            Text("IP: ${currentConnectionInfo.value?.groupOwnerAddress?.hostAddress}", fontSize = 12.sp, color = Color.Gray)
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
        }
    }

    // --- TAB 1: Chats Screen (iPhone Messenger Style) ---
    @Composable
    fun ChatsTabScreen() {
        val context = LocalContext.current
        var messageInput by remember { mutableStateOf("") }
        var chatThreads by remember { mutableStateOf(emptyList<DbChatThread>()) }

        LaunchedEffect(Unit) {
            chatThreads = dbHelper.getAllChatConversations()
        }

        if (activeChatPeerDeviceId.value.isEmpty()) {
            // Chat Conversation List
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Mensajes",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (chatThreads.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("💬", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No hay conversaciones iniciadas.", color = Color.Gray)
                            Text("Selecciona un dispositivo en el Escáner para chatear.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chatThreads) { thread ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeChatPeerDeviceId.value = thread.peerDeviceId
                                        activeChatPeerName.value = thread.peerName
                                        loadChatMessagesForPeer(thread.peerDeviceId)
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarBubble(avatarIndex = thread.avatarIndex, size = 48.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(thread.peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(thread.lastMessage, maxLines = 1, fontSize = 13.sp, color = Color.Gray)
                                    }
                                    Text(
                                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(thread.timestamp)),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Individual Messenger Chat Conversation View
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activeChatPeerDeviceId.value = "" }) {
                        Text("◀", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(activeChatPeerName.value, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        activeChatPeerDeviceId.value = ""
                    }) {
                        Text("❌", fontSize = 16.sp)
                    }
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                // Chat Messages List
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        chatMessages.forEach { msg ->
                            val alignment = if (msg.isMe) Alignment.End else Alignment.Start
                            val bubbleColor = if (msg.isMe) MaterialTheme.colorScheme.primary else Color(0xFF334155)
                            val textColor = Color.White

                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalAlignment = alignment
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = if (msg.isMe) 16.dp else 2.dp,
                                                bottomEnd = if (msg.isMe) 2.dp else 16.dp
                                            )
                                        )
                                        .background(bubbleColor)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(text = msg.message, color = textColor, fontSize = 15.sp)
                                }
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    LaunchedEffect(chatMessages.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }

                // Chat Input Field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe un mensaje estilo iPhone...") },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (messageInput.trim().isNotEmpty()) {
                                val msg = sessionManager.sendChatMessage(
                                    text = messageInput.trim(),
                                    myName = myNameState.value,
                                    peerDeviceId = activeChatPeerDeviceId.value
                                )
                                chatMessages.add(msg)
                                messageInput = ""
                                HapticManager.performLightClick(context)
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (messageInput.trim().isNotEmpty()) {
                                val msg = sessionManager.sendChatMessage(
                                    text = messageInput.trim(),
                                    myName = myNameState.value,
                                    peerDeviceId = activeChatPeerDeviceId.value
                                )
                                chatMessages.add(msg)
                                messageInput = ""
                                HapticManager.performLightClick(context)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Text("⬆", fontSize = 20.sp, color = Color.White)
                    }
                }
            }
        }
    }

    // --- TAB 2: Transferencias Tab ---
    @Composable
    fun TransfersTabScreen() {
        val context = LocalContext.current
        var history by remember { mutableStateOf(emptyList<DbTransferRecord>()) }

        LaunchedEffect(Unit) {
            history = dbHelper.getTransferHistory()
        }

        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { fileUri ->
                val info = currentConnectionInfo.value
                if (info != null && info.groupFormed) {
                    val hostAddress = if (info.isGroupOwner) {
                        fileTransferManager.lastClientIpAddress ?: "192.168.49.2"
                    } else {
                        info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    }
                    lifecycleScope.launch {
                        fileTransferManager.sendFile(hostAddress, fileUri, isAutoPlayAudioEnabled.value)
                    }
                } else {
                    Toast.makeText(context, "Conéctate a un dispositivo primero", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Transferencia de Archivos",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Active Transfer Bar
            AnimatedVisibility(visible = isTransferring.value) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Enviando/Recibiendo: ${transferFileName.value}", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = transferProgress.value,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${(transferProgress.value * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text("📁 Seleccionar y Enviar Archivo")
            }

            Text("Historial de Transferencias", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

            if (history.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No hay historial de transferencias.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (item.isIncoming) "⬇" else "⬆", fontSize = 20.sp, color = if (item.isIncoming) Color(0xFF10B981) else Color(0xFF3B82F6))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.fileName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                    val sizeMb = String.format("%.2f MB", item.fileSize.toDouble() / (1024 * 1024))
                                    Text("$sizeMb • ${item.status}", fontSize = 11.sp, color = Color.Gray)
                                }
                                Text(
                                    text = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.timestamp)),
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- TAB 3: Transmisión Media Screen ---
    @Composable
    fun TransmissionTabScreen() {
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Transmisión en Tiempo Real",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Screen Sharing Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Compartir Pantalla Remota", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Transmite tu pantalla en vivo a otro dispositivo.", fontSize = 12.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isScreenShareEnabled.value,
                            onCheckedChange = { isScreenShareEnabled.value = it }
                        )
                    }

                    if (isScreenShareEnabled.value) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                activeScreenShareSender.value = true
                                sessionManager.sendScreenShareStart(
                                    myNameState.value,
                                    screenShareResolution.value,
                                    screenShareFps.value,
                                    screenShareQuality.value
                                )
                                Toast.makeText(context, "Iniciando transmisión...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7))
                        ) {
                            Text("🖥️ Transmitir Pantalla")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Universal Browser Vault QR Code Card (Feature 1)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌐 Web Vault (AirShare sin App)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Escanea este QR desde cualquier iPhone/PC para transferir archivos directamente.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)

                    Spacer(modifier = Modifier.height(12.dp))

                    val localIp = getLocalIpAddress(context)
                    val url = "http://$localIp:8989"

                    val qrBitmap = remember(url) { generateQrCodeBitmap(url) }
                    qrBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR Vault",
                            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(url, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { toggleAirShareServer() }) {
                        Text(if (isAirShareServerActive.value) "Detener Servidor Web" else "Iniciar Servidor Web")
                    }
                }
            }
        }
    }

    // --- TAB 4: Ajustes & Perfil ---
    @Composable
    fun SettingsTabScreen() {
        val context = LocalContext.current
        var nameInput by remember { mutableStateOf(myNameState.value) }
        var phoneInput by remember { mutableStateOf(myPhoneState.value) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Ajustes y Perfil",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Identity Profile Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tu Identidad Persistente", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("ID Unico: ${myDeviceIdState.value}", fontSize = 11.sp, color = Color.Gray)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            myNameState.value = it
                            dbHelper.saveProfile(it, myPhoneState.value, myAvatarState.value, myDeviceIdState.value)
                        },
                        label = { Text("Nombre para mostrar") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = {
                            phoneInput = it
                            myPhoneState.value = it
                            dbHelper.saveProfile(myNameState.value, it, myAvatarState.value, myDeviceIdState.value)
                        },
                        label = { Text("Teléfono") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Avatar:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AVATAR_DESIGNS.forEachIndexed { idx, design ->
                            val isSelected = myAvatarState.value == idx
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(design.bgGradients))
                                    .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable {
                                        myAvatarState.value = idx
                                        dbHelper.saveProfile(myNameState.value, myPhoneState.value, idx, myDeviceIdState.value)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(design.emoji, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Theme Customizer Card (Feature 2)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Personalización de Tema iOS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        THEME_PRESETS.forEachIndexed { idx, theme ->
                            Button(
                                onClick = {
                                    currentThemeIndex.value = idx
                                    HapticManager.performLightClick(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                            ) {
                                Text(theme.name.take(4), fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Background Discovery Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Búsqueda BLE en Segundo Plano", fontWeight = FontWeight.Bold)
                        Text("Permite detectar dispositivos con pantalla apagada.", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isBgDiscoveryEnabled.value,
                        onCheckedChange = { isEnabled ->
                            isBgDiscoveryEnabled.value = isEnabled
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE).edit().putBoolean("bg_discovery_enabled", isEnabled).apply()
                            if (isEnabled) startBgDiscoveryService() else stopBgDiscoveryService()
                        }
                    )
                }
            }
        }
    }
}
