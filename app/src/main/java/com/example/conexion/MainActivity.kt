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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.Dispatchers
import java.net.NetworkInterface
import java.util.Collections
import java.io.File
import android.os.Environment

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

    // Chat and Contact Share UI state
    private var isChatActive = mutableStateOf(false)
    private var chatMessages = mutableStateListOf<ChatMessage>()
    private var chatRequestPrompt = mutableStateOf<ChatPrompt?>(null)
    private var contactRequestPrompt = mutableStateOf<ContactPrompt?>(null)
    private var activeChatPeerName = mutableStateOf("")

    data class ChatMessage(val text: String, val isMe: Boolean)
    data class ChatPrompt(val peerName: String, val onDecision: (Boolean) -> Unit)
    data class ContactPrompt(val peerName: String, val onDecision: (Boolean) -> Unit)

    // BLE background service state
    private var isBgDiscoveryEnabled = mutableStateOf(false)

    // TAREA B & E: Candidates discovered from background BLE scan
    private val sendingCandidates = mutableStateMapOf<String, BackgroundDiscoveryService.BlePeer>()
    private val blePeersMap = mutableStateMapOf<String, BackgroundDiscoveryService.BlePeer>()
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
                val avatarIdx = intent.getIntExtra("EXTRA_PEER_AVATAR", 0)
                val isAmbiguous = intent.getBooleanExtra("EXTRA_IS_AMBIGUOUS", false)
                Log.d(tag, "Received BLE peer sending from background service: $name, token=$token, isAmbiguous=$isAmbiguous, avatarIndex=$avatarIdx")

                val peer = BackgroundDiscoveryService.BlePeer(name, token, 1, avatarIdx)
                sendingCandidates[token] = peer
                blePeersMap[token] = peer

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

        dbHelper = DatabaseHelper(this)
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
            dbHelper.saveProfile(defaultName, defaultPhone, defaultAvatar)
            myNameState.value = defaultName
            myAvatarState.value = defaultAvatar
            myPhoneState.value = defaultPhone
        }

        sessionManager = SessionManager(
            onMessageReceived = { text ->
                chatMessages.add(ChatMessage(text, false))
            },
            onChatRequestReceived = { peerName, decisionCallback ->
                chatRequestPrompt.value = ChatPrompt(peerName) { accepted ->
                    if (accepted) {
                        isChatActive.value = true
                        activeChatPeerName.value = peerName
                        chatMessages.clear()
                    }
                    decisionCallback(accepted)
                }
            },
            onChatRequestResponse = { accepted ->
                if (accepted) {
                    isChatActive.value = true
                    chatMessages.clear()
                    Toast.makeText(this, "¡Solicitud de chat aceptada!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El usuario rechazó la solicitud de chat.", Toast.LENGTH_LONG).show()
                }
            },
            onContactRequestReceived = { peerName, decisionCallback ->
                contactRequestPrompt.value = ContactPrompt(peerName) { accepted ->
                    decisionCallback(accepted)
                    if (accepted) {
                        // Send my own data to peer immediately
                        sessionManager.sendContactData(myNameState.value, myPhoneState.value)
                        Toast.makeText(this, "Compartiendo contacto...", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onContactRequestResponse = { accepted ->
                if (accepted) {
                    // Send my own data to peer
                    sessionManager.sendContactData(myNameState.value, myPhoneState.value)
                    Toast.makeText(this, "Solicitud aceptada. Compartiendo contacto...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El usuario rechazó el intercambio de contactos.", Toast.LENGTH_LONG).show()
                }
            },
            onContactDataReceived = { name, phone ->
                // Guard contact into the Address Book
                saveContactToAddressBook(name, phone)
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
        )

        remoteAudioPlayer = RemoteAudioPlayer(this)
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
                    // Both Group Owner and Client will run the server so either can accept files.
                    // This resolves GO/Client bidirectional communication constraints.
                    lifecycleScope.launch {
                        fileTransferManager.startServer()
                    }
                    // Start SessionManager servers & clients
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
                    sessionManager.stop()
                    isChatActive.value = false
                }
            },
            onPeersDiscovered = { peers ->
                peersState.value = peers
                // Persist peer info to DB
                peers.forEach { peer ->
                    dbHelper.saveOrUpdatePeer(
                        peer.userName,
                        peer.sessionToken,
                        peer.phoneNumber,
                        peer.avatarIndex
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

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                return String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting WifiManager IP", e)
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
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(100)
                        }
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
            .setContentTitle("Archivo recibido desde iPhone")
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

    // Placeholder function to save contacts
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
            Toast.makeText(this, "¡Contacto $name guardado exitosamente!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(tag, "Failed to save contact", e)
            Toast.makeText(this, "Fallo al guardar el contacto en el sistema: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

    // A list of lovely gradient profiles for users
    data class AvatarDesign(val emoji: String, val bgGradients: List<Color>, val contentColor: Color)

    companion object {
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
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(design.bgGradients)
                )
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = design.emoji,
                fontSize = (size.value * 0.5f).sp
            )
        }
    }

    // Modern Sonar/Radar Scan Screen Component
    @Composable
    fun ModernSonarRadar(
        myAvatarIndex: Int,
        peers: List<PeerInfo>,
        blePeers: List<BackgroundDiscoveryService.BlePeer>,
        onPeerClick: (PeerInfo) -> Unit,
        onBlePeerClick: (BackgroundDiscoveryService.BlePeer) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition()

        // Ripple radius animations
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

        // Rotation animation for the radar sweep beam
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
                .height(300.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Sonar Canvas drawing lines and sweep
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.width.coerceAtMost(size.height) / 2 * 0.85f

                // Draw solid circles of ripples
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

                // Draw background radar concentric circles
                for (i in 1..4) {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.2f),
                        radius = maxRadius * (i / 4f),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw crosshairs axes
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

                // Draw sweep beam line
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

            // Center: ME
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

            // Combine/Deduplicate Peers to represent them as Sonar Nodes
            // Let's lay them out in circular patterns dynamically!
            val allNodes = remember(peers, blePeers) {
                val list = mutableListOf<SonarNode>()
                peers.forEachIndexed { idx, p ->
                    // Lay them out at specific angles
                    val angle = 45f + idx * 75f
                    val distanceFactor = 0.45f + (idx % 2) * 0.25f
                    list.add(SonarNode.WifiPeer(p, angle, distanceFactor))
                }
                var bleCount = 0
                blePeers.forEach { bp ->
                    // Only add if not already in peers
                    if (peers.none { it.sessionToken == bp.sessionToken }) {
                        val angle = 110f + bleCount * 85f
                        val distanceFactor = 0.55f + (bleCount % 2) * 0.25f
                        list.add(SonarNode.BlePeer(bp, angle, distanceFactor))
                        bleCount++
                    }
                }
                list
            }

            // Draw peers as overlapping items on the Sonar
            allNodes.forEach { node ->
                val angleRad = Math.toRadians(node.angle.toDouble())
                // Max radius offset (roughly 110.dp for container height)
                val distancePx = 100 * node.distanceFactor

                val offsetX = (distancePx * Math.cos(angleRad)).toFloat()
                val offsetY = (distancePx * Math.sin(angleRad)).toFloat()

                Box(
                    modifier = Modifier
                        .offset(x = offsetX.dp, y = offsetY.dp)
                        .clickable {
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppScreen() {
        val context = LocalContext.current
        val keyboardController = LocalSoftwareKeyboardController.current

        var tempName by remember { mutableStateOf(myNameState.value) }
        var tempPhone by remember { mutableStateOf(myPhoneState.value) }

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
                        fileTransferManager.sendFile(hostAddress, fileUri, isAutoPlayAudioEnabled.value)
                    }
                } else {
                    Toast.makeText(context, "Por favor, conéctate primero", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // BottomSheet/Dialog states for interactive peer actions
        var selectedPeerForActions by remember { mutableStateOf<PeerInfo?>(null) }
        var selectedBlePeerForActions by remember { mutableStateOf<BackgroundDiscoveryService.BlePeer?>(null) }

        var activeTab by remember { mutableStateOf("direct") }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // iOS Segmented Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf("Conexión Directa", "Compartir con iPhone")
                tabs.forEachIndexed { index, title ->
                    val isSelected = (index == 0 && activeTab == "direct") || (index == 1 && activeTab == "airshare")
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable {
                                activeTab = if (index == 0) "direct" else "airshare"
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (activeTab == "airshare") {
                AirShareScreen(
                    ipAddress = airShareLocalIp.value,
                    serverPort = 8989,
                    isServerActive = isAirShareServerActive.value,
                    onToggleServer = {
                        toggleAirShareServer()
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
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

            // Dynamic Radar/Sonar Scan Screen
            Text(
                text = "Escáner Sonal Moderno",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Start
            )

            ModernSonarRadar(
                myAvatarIndex = myAvatarState.value,
                peers = peersState.value,
                blePeers = blePeersMap.values.toList(),
                onPeerClick = { peer ->
                    selectedPeerForActions = peer
                },
                onBlePeerClick = { blePeer ->
                    selectedBlePeerForActions = blePeer
                },
                modifier = Modifier.padding(bottom = 16.dp)
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

            val recentPeers = remember(peersState.value) { dbHelper.getRecentPeers() }
            if (recentPeers.isNotEmpty()) {
                Text(
                    text = "Dispositivos Cercanos Recientes (Historial)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Start
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        recentPeers.forEach { dbPeer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AvatarBubble(avatarIndex = dbPeer.avatar, size = 32.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dbPeer.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    if (dbPeer.phone.isNotEmpty()) {
                                        Text(
                                            text = "Tel: ${dbPeer.phone}",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Text(
                                    text = "Guardado en BD",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
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
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("user_name", it)
                                .apply()
                            dbHelper.saveProfile(it, myPhoneState.value, myAvatarState.value)
                        },
                        label = { Text("Nombre para mostrar") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPhone,
                        onValueChange = {
                            tempPhone = it
                            myPhoneState.value = it
                            getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("phone_number", it)
                                .apply()
                            dbHelper.saveProfile(myNameState.value, it, myAvatarState.value)
                        },
                        label = { Text("Número de teléfono") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Elige tu avatar de perfil:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AVATAR_DESIGNS.forEachIndexed { idx, design ->
                            val isSelected = myAvatarState.value == idx
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        CircleShape
                                    )
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.linearGradient(design.bgGradients)
                                    )
                                    .clickable {
                                        myAvatarState.value = idx
                                        getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                                            .edit()
                                            .putInt("avatar_index", idx)
                                            .apply()
                                        dbHelper.saveProfile(myNameState.value, myPhoneState.value, idx)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(design.emoji, fontSize = 18.sp)
                            }
                        }
                    }

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

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Configuración de Compartir Pantalla",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Permitir Compartir Pantalla",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Habilita la capacidad de transmitir pantalla a otros dispositivos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isScreenShareEnabled.value,
                            onCheckedChange = { isScreenShareEnabled.value = it }
                        )
                    }

                    if (isScreenShareEnabled.value) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Resolution Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Resolución:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Row {
                                listOf("720p", "1080p", "Original").forEach { res ->
                                    val isSelected = screenShareResolution.value == res
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.2f))
                                            .clickable { screenShareResolution.value = res }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(res, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Quality Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Calidad:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Row {
                                listOf("Baja", "Media", "Alta").forEach { q ->
                                    val isSelected = screenShareQuality.value == q
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.2f))
                                            .clickable { screenShareQuality.value = q }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(q, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // FPS Slider
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("FPS (Fotogramas):", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${screenShareFps.value} FPS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            }
                            Slider(
                                value = screenShareFps.value.toFloat(),
                                onValueChange = { screenShareFps.value = it.toInt() },
                                valueRange = 15f..60f,
                                steps = 2
                            )
                        }
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

            // Chat Interface
            if (isChatActive.value) {
                var messageText by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Chat con " + (if (activeChatPeerName.value.isNotEmpty()) activeChatPeerName.value else "Dispositivo"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(onClick = {
                                isChatActive.value = false
                                Toast.makeText(context, "Chat finalizado", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("❌", fontSize = 16.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                            ) {
                                chatMessages.forEach { msg ->
                                    val alignment = if (msg.isMe) Alignment.End else Alignment.Start
                                    val bgCol = if (msg.isMe) MaterialTheme.colorScheme.primary else Color(0xFFE2E8F0)
                                    val textCol = if (msg.isMe) Color.White else Color.Black
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = alignment
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(bgCol, RoundedCornerShape(12.dp))
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text(text = msg.text, color = textCol, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                            LaunchedEffect(chatMessages.size) {
                                scrollState.animateScrollTo(scrollState.maxValue)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Escribe un mensaje...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (messageText.trim().isNotEmpty()) {
                                        sessionManager.sendChatMessage(messageText.trim())
                                        chatMessages.add(ChatMessage(messageText.trim(), true))
                                        messageText = ""
                                    }
                                })
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (messageText.trim().isNotEmpty()) {
                                        sessionManager.sendChatMessage(messageText.trim())
                                        chatMessages.add(ChatMessage(messageText.trim(), true))
                                        messageText = ""
                                    }
                                }
                            ) {
                                Text("Enviar")
                            }
                        }
                    }
                }
            }

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
                            text = "Compartir Archivos y Funciones",
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

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Reproducir automáticamente si es audio",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = isAutoPlayAudioEnabled.value,
                                onCheckedChange = { isAutoPlayAudioEnabled.value = it }
                            )
                        }

                        if (isScreenShareEnabled.value) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    activeScreenShareSender.value = true
                                    sessionManager.sendScreenShareStart(
                                        myNameState.value,
                                        screenShareResolution.value,
                                        screenShareFps.value,
                                        screenShareQuality.value
                                    )
                                    Toast.makeText(context, "Iniciando transmisión de pantalla...", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7))
                            ) {
                                Text("🖥️ Compartir Pantalla")
                            }
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
                } // End of activeTab == "direct" Column
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

            // Chat prompt dialog
            chatRequestPrompt.value?.let { prompt ->
                AlertDialog(
                    onDismissRequest = {
                        prompt.onDecision(false)
                        chatRequestPrompt.value = null
                    },
                    title = { Text("Solicitud de Chat") },
                    text = {
                        Text(
                            text = "${prompt.peerName} quiere iniciar un chat contigo. ¿Aceptas?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                prompt.onDecision(true)
                                chatRequestPrompt.value = null
                            }
                        ) {
                            Text("Aceptar")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                prompt.onDecision(false)
                                chatRequestPrompt.value = null
                            }
                        ) {
                            Text("Rechazar")
                        }
                    }
                )
            }

            // Contact request dialog
            contactRequestPrompt.value?.let { prompt ->
                AlertDialog(
                    onDismissRequest = {
                        prompt.onDecision(false)
                        contactRequestPrompt.value = null
                    },
                    title = { Text("Intercambiar Contactos") },
                    text = {
                        Text(
                            text = "${prompt.peerName} quiere intercambiar contactos mutuamente (Nombre y Teléfono). Esto guardará su contacto en tu libreta y enviará el tuyo si aceptas. ¿Proceder?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                prompt.onDecision(true)
                                contactRequestPrompt.value = null
                            }
                        ) {
                            Text("Intercambiar")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                prompt.onDecision(false)
                                contactRequestPrompt.value = null
                            }
                        ) {
                            Text("Rechazar")
                        }
                    }
                )
            }

            // Bottom Dialog sheets for tapping on sonar nodes
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
                    text = {
                        Column {
                            Text(
                                text = "Elige qué acción deseas realizar con este dispositivo cercano:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val isP2pConn = currentConnectionInfo.value?.groupFormed ?: false
                                    if (isP2pConn) {
                                        // Request chat!
                                        activeChatPeerName.value = peer.userName
                                        sessionManager.sendChatRequest(myNameState.value)
                                        Toast.makeText(context, "Solicitud de chat enviada...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Conéctate al dispositivo primero para chatear.", Toast.LENGTH_SHORT).show()
                                    }
                                    selectedPeerForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("💬 Iniciar Chat")
                            }
                            Button(
                                onClick = {
                                    val isP2pConn = currentConnectionInfo.value?.groupFormed ?: false
                                    if (isP2pConn) {
                                        sessionManager.sendContactRequest(myNameState.value)
                                        Toast.makeText(context, "Solicitud de intercambio de contacto enviada...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Conéctate al dispositivo primero para intercambiar contactos.", Toast.LENGTH_SHORT).show()
                                    }
                                    selectedPeerForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("📇 Intercambiar Contacto Mutuo")
                            }
                            Button(
                                onClick = {
                                    wifiP2pHelper.connectToPeer(peer)
                                    selectedPeerForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Text("🔗 Conectar / Enviar Archivos")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { selectedPeerForActions = null }) {
                            Text("Cancelar")
                        }
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
                    text = {
                        Text(
                            text = "Este dispositivo ha sido detectado de forma pasiva mediante búsqueda BLE. ¿Deseas intentar conectarte de forma segura?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val p2pPeer = wifiP2pHelper.findPeerByToken(bpeer.sessionToken)
                                if (p2pPeer != null) {
                                    wifiP2pHelper.connectToPeer(p2pPeer)
                                } else {
                                    Toast.makeText(context, "Buscando información de red segura...", Toast.LENGTH_SHORT).show()
                                    wifiP2pHelper.startDiscoveryForToken(bpeer.sessionToken, bpeer.userName)
                                }
                                selectedBlePeerForActions = null
                            }
                        ) {
                            Text("Conectar de forma segura")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { selectedBlePeerForActions = null }) {
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

            // Floating remote music player
            if (remoteAudioPlayer.currentTrackName.value.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            // Pulsing music note icon
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseScale by infiniteTransition.animateFloat(
                                initialValue = 0.9f,
                                targetValue = 1.15f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .scale(if (remoteAudioPlayer.isPlaying.value) pulseScale else 1f)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎵", fontSize = 20.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Reproduciendo Audio Remoto",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = remoteAudioPlayer.currentTrackName.value,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { remoteAudioPlayer.togglePlayPause() }) {
                                Text(if (remoteAudioPlayer.isPlaying.value) "⏸" else "▶", fontSize = 18.sp)
                            }
                            IconButton(onClick = { remoteAudioPlayer.stop() }) {
                                Text("⏹", fontSize = 18.sp)
                            }
                        }
                    }
                }
            }

            // Screen Sharing Sender Overlay
            if (activeScreenShareSender.value) {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Compartiendo Pantalla", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Tu pantalla se está transmitiendo en tiempo real al dispositivo conectado.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.05f))
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🖥️ ✨ 📱", fontSize = 28.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "${screenShareResolution.value} • ${screenShareFps.value} FPS • Calidad ${screenShareQuality.value}",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                activeScreenShareSender.value = false
                                sessionManager.sendScreenShareStop()
                                Toast.makeText(context, "Transmisión detenida.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Detener Transmisión")
                        }
                    }
                )
            }

            // Screen Sharing Receiver Overlay
            activeScreenShareReceiver.value?.let { session ->
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pantalla de ${session.peerName}", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Recibiendo transmisión de pantalla.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // High fidelity simulated screen preview mockup!
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                        // Header of cast mockup
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Conexion Cast", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text("12:00", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                                        }

                                        // Pulse effect on mockup screen
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            val infiniteTransition = rememberInfiniteTransition()
                                            val scale by infiniteTransition.animateFloat(
                                                initialValue = 0.95f,
                                                targetValue = 1.05f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(1200, easing = EaseInOutSine),
                                                    repeatMode = RepeatMode.Reverse
                                                )
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .scale(scale)
                                                    .size(54.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("📱", fontSize = 28.sp)
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Simulando pantalla remota...",
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 9.sp
                                            )
                                        }

                                        // Footer specs
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Res: ${session.resolution}",
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 9.sp
                                            )
                                            Text(
                                                text = "FPS: ${session.fps}",
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 9.sp
                                            )
                                            Text(
                                                text = "Calidad: ${session.quality}",
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                activeScreenShareReceiver.value = null
                                sessionManager.sendScreenShareStop()
                                Toast.makeText(context, "Transmisión detenida.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Cerrar")
                        }
                    }
                )
            }
        }
    }
}
