package com.example.conexion

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackgroundDiscoveryService : Service() {

    private val tag = "BgDiscoveryService"

    enum class BeaconState { IDLE, SENDING }

    companion object {
        const val ACTION_START = "com.example.conexion.ACTION_START"
        const val ACTION_STOP = "com.example.conexion.ACTION_STOP"

        const val ACTION_SET_SENDING = "com.example.conexion.ACTION_SET_SENDING"
        const val ACTION_CLEAR_SENDING = "com.example.conexion.ACTION_CLEAR_SENDING"

        const val ACTION_PEER_SENDING = "com.example.conexion.ACTION_PEER_SENDING"
        const val ACTION_BEACON_TOKEN_DECODED = "com.example.conexion.ACTION_BEACON_TOKEN_DECODED"

        const val ACTION_STREAM_ACCEPT = "com.example.conexion.ACTION_STREAM_ACCEPT"
        const val ACTION_STREAM_REJECT = "com.example.conexion.ACTION_STREAM_REJECT"

        const val EXTRA_USER_NAME = "EXTRA_USER_NAME"
        const val EXTRA_WIFI_MAC = "EXTRA_WIFI_MAC"
        const val EXTRA_PEER_NAME = "EXTRA_PEER_NAME"
        const val EXTRA_PEER_MAC = "EXTRA_PEER_MAC"
        const val EXTRA_PEER_TOKEN = "EXTRA_PEER_TOKEN"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "conexion_bg_discovery"
        private const val MATCH_NOTIFICATION_ID = 1002

        private const val MANUFACTURER_ID = 0xFEFE
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var currentUserName = "Mi Dispositivo"
    private var currentWifiMac = "00:00:00:00:00:00"
    private var currentSessionToken = "000000000000"
    private var currentAvatarIndex = 0

    private var currentBeaconState = BeaconState.IDLE
    private var currentSendingToken = "000000000000"

    // GPS location tracking for BLE advertising
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var hasGpsLocation: Boolean = false
    private var lastAdvertisedLat: Double = 0.0
    private var lastAdvertisedLon: Double = 0.0
    private var lastAdvertisingTime: Long = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var sendingTimeoutJob: Job? = null

    // Tracks peers to avoid showing duplicate notifications in a short window
    private val recentlyNotifiedPeers = mutableMapOf<String, Long>()
    private val lastProximityVibrations = mutableMapOf<String, Long>()

    private fun checkProximityHaptic(sessionToken: String) {
        val now = System.currentTimeMillis()
        val last = lastProximityVibrations[sessionToken] ?: 0L
        if (now - last > 3000) {
            lastProximityVibrations[sessionToken] = now
            HapticManager.performProximityHaptic(this)
        }
    }

    private var audioBeaconListener: AudioBeaconListener? = null
    private val recentlyDetectedPeerNames = mutableMapOf<String, String>()
    private val recentlyDetectedPeerAvatars = mutableMapOf<String, Int>()

    // Tracks active sending peers in the last 15 seconds
    private val activeSendingPeers = mutableMapOf<String, Long>()

    // Background instances of WifiP2p and FileTransfer for background connections
    private lateinit var wifiP2pHelper: WifiP2pHelper
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var remoteAudioPlayer: RemoteAudioPlayer

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val token = intent.getStringExtra(EXTRA_PEER_TOKEN) ?: ""
            Log.d(tag, "Service receiver got action: $action, token: $token")
            if (action == "com.example.conexion.ACTION_NOTIFICATION_ACCEPT") {
                val peer = wifiP2pHelper.findPeerByToken(token)
                if (peer != null) {
                    wifiP2pHelper.connectToPeer(peer)
                    showToast("Conectando con ${peer.userName}...")
                } else {
                    wifiP2pHelper.startDiscoveryForToken(token, "Dispositivo")
                    showToast("Buscando red para conectar...")
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(MATCH_NOTIFICATION_ID)
            } else if (action == "com.example.conexion.ACTION_NOTIFICATION_REJECT") {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(MATCH_NOTIFICATION_ID)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service onCreate")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner

        dbHelper = DatabaseHelper(this)

        wifiP2pHelper = WifiP2pHelper(
            context = this,
            onConnectionChanged = { info ->
                if (info != null && info.groupFormed) {
                    Log.d(tag, "Background P2P connected. Starting file server.")
                    serviceScope.launch {
                        fileTransferManager.startServer()
                    }
                } else {
                    fileTransferManager.stopServer()
                }
            },
            onPeersDiscovered = { peers ->
                peers.forEach { p ->
                    dbHelper.saveOrUpdatePeer(p.userName, p.sessionToken, p.phoneNumber, p.avatarIndex)
                }
            },
            onConnectionRequestReceived = { peer ->
                // Auto accept or trigger
            },
            onError = { err ->
                Log.e(tag, "Background WifiP2pHelper error: $err")
            }
        )

        remoteAudioPlayer = RemoteAudioPlayer(this)

        fileTransferManager = FileTransferManager(
            context = this,
            dbHelper = dbHelper,
            onIncomingFileRequest = { fileName, fileSize, onAccept, _ ->
                Log.d(tag, "Background incoming file request: $fileName ($fileSize bytes)")
                onAccept()
            },
            onError = { err ->
                Log.e(tag, "Background file transfer error: $err")
            },
            onProgress = { fileName, bytes, total, completed ->
                showFileProgressNotification(fileName, bytes, total, completed)
            }
        )

        fileTransferManager.onAudioPlayRequested = { uri, fileName ->
            Log.d(tag, "Background remote audio play requested: $fileName")
            remoteAudioPlayer.play(uri, fileName)
            showToast("Reproduciendo audio remoto: $fileName")
        }

        audioBeaconListener = AudioBeaconListener(
            onTokenDecoded = { token ->
                Log.d(tag, "Beacon decoded token: $token")
                val name = recentlyDetectedPeerNames[token] ?: "Dispositivo ultrasónico"

                HapticManager.performIPhoneHaptic(this)
                showConnectionNotification(name, token)

                val intent = Intent(ACTION_BEACON_TOKEN_DECODED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PEER_TOKEN, token)
                    putExtra(EXTRA_PEER_NAME, name)
                    putExtra("EXTRA_DECODE_SUCCESS", true)
                }
                sendBroadcast(intent)
            },
            onFinished = { success ->
                Log.d(tag, "Beacon listener finished. Success: $success")
                if (!success) {
                    val intent = Intent(ACTION_BEACON_TOKEN_DECODED).apply {
                        setPackage(packageName)
                        putExtra("EXTRA_DECODE_SUCCESS", false)
                    }
                    sendBroadcast(intent)
                }
            }
        )

        val filter = IntentFilter().apply {
            addAction("com.example.conexion.ACTION_NOTIFICATION_ACCEPT")
            addAction("com.example.conexion.ACTION_NOTIFICATION_REJECT")
            addAction(ACTION_STREAM_ACCEPT)
            addAction(ACTION_STREAM_REJECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        createNotificationChannel()
        setupLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        if (locationManager == null) {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }
        if (locationListener == null) {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                    hasGpsLocation = true
                    checkAndUpdateAdvertisingLocation()
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
        }
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    2f,
                    locationListener!!
                )
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    2f,
                    locationListener!!
                )
                val lastLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastLoc?.let {
                    currentLat = it.latitude
                    currentLon = it.longitude
                    hasGpsLocation = true
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup location updates in service", e)
        }
    }

    private fun checkAndUpdateAdvertisingLocation() {
        val now = System.currentTimeMillis()
        if (now - lastAdvertisingTime >= 5000) {
            val distMoved = FloatArray(1)
            Location.distanceBetween(lastAdvertisedLat, lastAdvertisedLon, currentLat, currentLon, distMoved)
            if (distMoved[0] > 1.0f || !hasGpsLocation) {
                Log.d(tag, "GPS location updated (moved ${distMoved[0]}m). Restarting BLE advertising...")
                lastAdvertisedLat = currentLat
                lastAdvertisedLon = currentLon
                lastAdvertisingTime = now
                startAdvertisingAndScanning()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(tag, "onStartCommand action: $action")

        val name = intent?.getStringExtra(EXTRA_USER_NAME)
        if (name != null) currentUserName = name

        val mac = intent?.getStringExtra(EXTRA_WIFI_MAC)
        if (mac != null) currentWifiMac = mac

        val incomingToken = intent?.getStringExtra(EXTRA_PEER_TOKEN)
        if (incomingToken != null) {
            currentSessionToken = incomingToken
        }

        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        currentAvatarIndex = prefs.getInt("avatar_index", 0)

        when (action) {
            ACTION_START -> {
                startForegroundCompat()
                startAdvertisingAndScanning()
            }
            ACTION_SET_SENDING -> {
                sendingTimeoutJob?.cancel()
                currentBeaconState = BeaconState.SENDING
                val token = intent?.getStringExtra(EXTRA_PEER_TOKEN) ?: "000000000000"
                currentSendingToken = token
                Log.d(tag, "Setting state to SENDING with token: $currentSendingToken")
                startForegroundCompat()
                startAdvertisingAndScanning()

                sendingTimeoutJob = serviceScope.launch {
                    delay(15_000)
                    Log.d(tag, "Sending timeout reached, reverting to IDLE")
                    revertToIdle()
                }
            }
            ACTION_CLEAR_SENDING -> {
                sendingTimeoutJob?.cancel()
                revertToIdle()
            }
            ACTION_STOP -> {
                sendingTimeoutJob?.cancel()
                stopAdvertisingAndScanning()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun revertToIdle() {
        currentBeaconState = BeaconState.IDLE
        currentSendingToken = "000000000000"
        startForegroundCompat()
        startAdvertisingAndScanning()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Búsqueda en Segundo Plano",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mantiene activa la búsqueda pasiva por Bluetooth de baja energía (BLE)"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (currentBeaconState == BeaconState.SENDING) {
            "Transmitiendo baliza ultrasónica y BLE..."
        } else {
            "Búsqueda pasiva activada. BLE en segundo plano."
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Búsqueda en Segundo Plano")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingAndScanning() {
        try {
            if (bluetoothAdapter == null) {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetoothAdapter = bluetoothManager?.adapter
            }
            if (bluetoothAdapter?.isEnabled != true) {
                Log.e(tag, "Bluetooth is disabled. Cannot start BLE advertising or scanning.")
                showToast("Bluetooth desactivado. Por favor, actívalo para usar búsqueda BLE.")
                return
            }
            if (advertiser == null) advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (scanner == null) scanner = bluetoothAdapter?.bluetoothLeScanner

            if (advertiser == null || scanner == null) {
                Log.e(tag, "BLE is not supported on this device.")
                showToast("BLE no es soportado o no está listo en este dispositivo.")
                return
            }

            stopAdvertisingAndScanningSilently()

            val advMode = if (currentBeaconState == BeaconState.SENDING) {
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            } else {
                AdvertiseSettings.ADVERTISE_MODE_BALANCED
            }

            val advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(advMode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            val payload = buildManufacturerData(
                state = if (currentBeaconState == BeaconState.SENDING) 1 else 0,
                sessionToken = if (currentBeaconState == BeaconState.SENDING) currentSendingToken else currentSessionToken,
                avatarIndex = currentAvatarIndex,
                userName = currentUserName,
                latitude = if (hasGpsLocation) currentLat else null,
                longitude = if (hasGpsLocation) currentLon else null
            )
            val advData = AdvertiseData.Builder()
                .addManufacturerData(MANUFACTURER_ID, payload)
                .build()

            advertiser?.startAdvertising(advSettings, advData, advertiseCallback)
            Log.d(tag, "Started BLE Advertising in state $currentBeaconState with payload size: ${payload.size} bytes (GPS: lat=$currentLat, lon=$currentLon)")

            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, byteArrayOf(0), byteArrayOf(0))
                .build()

            val scanMode = if (currentBeaconState == BeaconState.SENDING) {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            } else {
                ScanSettings.SCAN_MODE_BALANCED
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(scanMode)
                .build()

            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(tag, "Started BLE Scanning in mode $currentBeaconState")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start advertising or scanning", e)
            showToast("Fallo al iniciar búsqueda BLE: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingAndScanningSilently() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // ignore
        }
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun stopAdvertisingAndScanning() {
        Log.d(tag, "Stopping advertising and scanning")
        stopAdvertisingAndScanningSilently()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioBeaconListener?.stop()
        stopAdvertisingAndScanning()
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {}
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {}
        wifiP2pHelper.unregister()
        fileTransferManager.stopServer()
        remoteAudioPlayer.stop()
        Log.d(tag, "Service onDestroy")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(tag, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "Advertising failed with error code: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result == null) return

            val record = result.scanRecord ?: return
            val rawData = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return

            val rssi = result.rssi
            val distance = Math.pow(10.0, (-59.0 - rssi) / (10.0 * 2.0))

            val peer = parseManufacturerData(rawData, rssi, distance) ?: return
            val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
            val maxRange = prefs.getFloat("vibration_range_meters", 1.0f).toDouble()
            if (distance <= maxRange || rssi >= -50) {
                checkProximityHaptic(peer.sessionToken)
            }
            handleDiscoveredPeer(peer)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { result ->
                val record = result.scanRecord ?: return@forEach
                val rawData = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return@forEach

                val rssi = result.rssi
                val distance = Math.pow(10.0, (-59.0 - rssi) / (10.0 * 2.0))

                val peer = parseManufacturerData(rawData, rssi, distance) ?: return@forEach
                val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
                val maxRange = prefs.getFloat("vibration_range_meters", 1.0f).toDouble()
                if (distance <= maxRange || rssi >= -50) {
                    checkProximityHaptic(peer.sessionToken)
                }
                handleDiscoveredPeer(peer)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(tag, "Scan failed with error code: $errorCode")
        }
    }

    private fun handleDiscoveredPeer(peer: BlePeer) {
        val now = System.currentTimeMillis()

        if (peer.sessionToken == currentSessionToken && currentSessionToken != "000000000000") {
            return
        }
        if (peer.sessionToken == currentSendingToken && currentSendingToken != "000000000000") {
            return
        }

        recentlyDetectedPeerNames[peer.sessionToken] = peer.userName
        recentlyDetectedPeerAvatars[peer.sessionToken] = peer.avatarIndex
        Log.d(tag, "Discovered BLE peer: name=${peer.userName}, token=${peer.sessionToken}, state=${peer.state}, lat=${peer.latitude}, lon=${peer.longitude}")

        if (peer.state == 1) {
            activeSendingPeers[peer.sessionToken] = now

            val iterator = activeSendingPeers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 15_000) {
                    iterator.remove()
                }
            }

            if (activeSendingPeers.size >= 2) {
                Log.d(tag, "AMBIGUITY GUARD: Multiple active sending peers detected. Stopping AudioBeaconListener.")
                audioBeaconListener?.stop()

                val lastNotified = recentlyNotifiedPeers[peer.sessionToken] ?: 0L
                if (now - lastNotified > 10_000) {
                    recentlyNotifiedPeers[peer.sessionToken] = now
                    val intent = Intent(ACTION_PEER_SENDING).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PEER_NAME, peer.userName)
                        putExtra(EXTRA_PEER_TOKEN, peer.sessionToken)
                        putExtra("EXTRA_PEER_AVATAR", peer.avatarIndex)
                        putExtra("EXTRA_PEER_LAT", peer.latitude ?: 0.0)
                        putExtra("EXTRA_PEER_LON", peer.longitude ?: 0.0)
                        putExtra("EXTRA_IS_AMBIGUOUS", true)
                    }
                    sendBroadcast(intent)
                }
            } else {
                val lastNotified = recentlyNotifiedPeers[peer.sessionToken] ?: 0L
                if (now - lastNotified > 10_000) {
                    recentlyNotifiedPeers[peer.sessionToken] = now
                    Log.d(tag, "PEER SENDING DETECTED: ${peer.userName} (${peer.sessionToken})")

                    HapticManager.performIPhoneHaptic(this)

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(tag, "RECORD_AUDIO permission granted. Starting AudioBeaconListener for token: ${peer.sessionToken}")
                        audioBeaconListener?.start(peer.sessionToken)
                    } else {
                        Log.e(tag, "RECORD_AUDIO permission not granted. Cannot start AudioBeaconListener.")
                        showToast("Permiso de micrófono no otorgado. No se puede escuchar la baliza.")
                    }

                    val intent = Intent(ACTION_PEER_SENDING).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PEER_NAME, peer.userName)
                        putExtra(EXTRA_PEER_TOKEN, peer.sessionToken)
                        putExtra("EXTRA_PEER_AVATAR", peer.avatarIndex)
                        putExtra("EXTRA_PEER_LAT", peer.latitude ?: 0.0)
                        putExtra("EXTRA_PEER_LON", peer.longitude ?: 0.0)
                        putExtra("EXTRA_IS_AMBIGUOUS", false)
                    }
                    sendBroadcast(intent)

                    showMatchNotification(peer)
                }
            }
        }
    }

    private fun showMatchNotification(peer: BlePeer) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER_NAME, peer.userName)
            putExtra(EXTRA_PEER_TOKEN, peer.sessionToken)
            putExtra("EXTRA_PEER_AVATAR", peer.avatarIndex)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("¡Dispositivo Cercano Compartiendo!")
            .setContentText("Toca para recibir de forma segura de ${peer.userName}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(MATCH_NOTIFICATION_ID, notification)
    }

    fun showStreamConfirmationNotification(peerName: String, streamType: String) {
        val acceptIntent = Intent(ACTION_STREAM_ACCEPT).apply {
            putExtra("EXTRA_STREAM_TYPE", streamType)
            setPackage(packageName)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            this, 201, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(ACTION_STREAM_REJECT).apply {
            putExtra("EXTRA_STREAM_TYPE", streamType)
            setPackage(packageName)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, 202, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (streamType == "SCREEN") "Transmisión de Pantalla Solicitada" else "Transmisión de Audio Solicitada"
        val body = "$peerName quiere compartir ${if (streamType == "SCREEN") "su pantalla" else "su audio"} en vivo contigo."

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(android.R.drawable.ic_menu_add, "ACEPTAR", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "RECHAZAR", rejectPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1003, notification)
    }

    private fun showConnectionNotification(userName: String, token: String) {
        val acceptIntent = Intent("com.example.conexion.ACTION_NOTIFICATION_ACCEPT").apply {
            putExtra(EXTRA_PEER_TOKEN, token)
            setPackage(packageName)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            this, 101, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent("com.example.conexion.ACTION_NOTIFICATION_REJECT").apply {
            putExtra(EXTRA_PEER_TOKEN, token)
            setPackage(packageName)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, 102, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PEER_NAME, userName)
            putExtra(EXTRA_PEER_TOKEN, token)
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 103, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("¡Tono ultrasónico detectado!")
            .setContentText("¿Quieres conectarte con $userName?")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(mainPendingIntent, true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_add, "ACEPTAR", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "RECHAZAR", rejectPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(MATCH_NOTIFICATION_ID, notification)
    }

    private fun showFileProgressNotification(fileName: String, bytes: Long, total: Long, completed: Boolean) {
        val progressText = if (completed) {
            "Archivo recibido con éxito: $fileName"
        } else {
            val pct = if (total > 0) (bytes * 100 / total).toInt() else 0
            "Recibiendo $fileName: $pct%"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transferencia de archivos")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(!completed)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1005, notification)
    }

    private fun showToast(msg: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun buildManufacturerData(
        state: Int,
        sessionToken: String,
        avatarIndex: Int,
        userName: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        val dos = DataOutputStream(stream)
        dos.writeByte(state)

        val tokenBytes = ByteArray(6)
        try {
            for (i in 0 until 6) {
                val byteStr = sessionToken.substring(i * 2, i * 2 + 2)
                tokenBytes[i] = byteStr.toInt(16).toByte()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse session token to bytes", e)
        }
        dos.write(tokenBytes)

        dos.writeByte(avatarIndex)

        val latInt = if (latitude != null) (latitude * 1_000_000.0).toInt() else 0
        val lonInt = if (longitude != null) (longitude * 1_000_000.0).toInt() else 0
        dos.writeInt(latInt)
        dos.writeInt(lonInt)

        val nameBytes = userName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size.coerceAtMost(10)
        dos.write(nameBytes, 0, nameLength)

        return stream.toByteArray()
    }

    fun parseManufacturerData(data: ByteArray, rssi: Int = -60, distanceMeters: Double = 1.0): BlePeer? {
        if (data.size < 16) return null
        return try {
            val buffer = ByteBuffer.wrap(data)
            val state = buffer.get().toInt()

            val tokenBytes = ByteArray(6)
            buffer.get(tokenBytes)
            val sessionToken = tokenBytes.joinToString("") { String.format("%02X", it) }

            val avatarIndex = buffer.get().toInt()

            val latInt = buffer.getInt()
            val lonInt = buffer.getInt()
            val latitude = if (latInt != 0 || lonInt != 0) latInt / 1_000_000.0 else null
            val longitude = if (latInt != 0 || lonInt != 0) lonInt / 1_000_000.0 else null

            val nameBytes = ByteArray(data.size - 16)
            buffer.get(nameBytes)
            val userName = String(nameBytes, Charsets.UTF_8).trim()

            BlePeer(
                userName = if (userName.isEmpty()) "Usuario BLE" else userName,
                sessionToken = sessionToken,
                state = state,
                avatarIndex = avatarIndex,
                rssi = rssi,
                distanceMeters = distanceMeters,
                latitude = latitude,
                longitude = longitude
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse manufacturer data", e)
            null
        }
    }

    data class BlePeer(
        val userName: String,
        val sessionToken: String,
        val state: Int,
        val avatarIndex: Int = 0,
        val rssi: Int = -60,
        val distanceMeters: Double = 1.0,
        val latitude: Double? = null,
        val longitude: Double? = null
    ) {
        val formattedDistance: String
            get() = if (distanceMeters < 1.0) {
                "${(distanceMeters * 100).toInt()} cm"
            } else {
                String.format(java.util.Locale.US, "%.1f m", distanceMeters)
            }
    }
}
