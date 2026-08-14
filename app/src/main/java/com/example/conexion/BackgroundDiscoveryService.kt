package com.example.conexion

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

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

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var sendingTimeoutJob: Job? = null

    // Tracks peers to avoid showing duplicate notifications in a short window
    private val recentlyNotifiedPeers = mutableMapOf<String, Long>()

    private var audioBeaconListener: AudioBeaconListener? = null
    private val recentlyDetectedPeerNames = mutableMapOf<String, String>()
    private val recentlyDetectedPeerAvatars = mutableMapOf<String, Int>()

    // TAREA B: Tracks active sending peers in the last 15 seconds
    private val activeSendingPeers = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service onCreate")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner

        audioBeaconListener = AudioBeaconListener(
            onTokenDecoded = { token ->
                Log.d(tag, "Beacon decoded token: $token")
                val intent = Intent(ACTION_BEACON_TOKEN_DECODED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PEER_TOKEN, token)
                    putExtra(EXTRA_PEER_NAME, recentlyDetectedPeerNames[token] ?: "Dispositivo ultrasónico")
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

        createNotificationChannel()
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
                NotificationManager.IMPORTANCE_LOW
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
            // FIX 3: Combine both foreground service types with bitwise OR (connectedDevice | microphone)
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
            // Setup advertiser & scanner if not already fetched
            if (bluetoothAdapter == null) {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetoothAdapter = bluetoothManager?.adapter
            }
            if (bluetoothAdapter?.isEnabled != true) {
                Log.e(tag, "Bluetooth is disabled. Cannot start BLE advertising or scanning.")
                showErrorToast("Bluetooth desactivado. Por favor, actívalo para usar búsqueda BLE.")
                return
            }
            if (advertiser == null) advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (scanner == null) scanner = bluetoothAdapter?.bluetoothLeScanner

            if (advertiser == null || scanner == null) {
                Log.e(tag, "BLE is not supported on this device.")
                showErrorToast("BLE no es soportado o no está listo en este dispositivo.")
                return
            }

            stopAdvertisingAndScanningSilently()

            // 1. Start BLE Advertising
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
                userName = currentUserName
            )
            val advData = AdvertiseData.Builder()
                .addManufacturerData(MANUFACTURER_ID, payload)
                .build()

            advertiser?.startAdvertising(advSettings, advData, advertiseCallback)
            Log.d(tag, "Started BLE Advertising in state $currentBeaconState with payload size: ${payload.size} bytes")

            // 2. Start BLE Scanning
            // Match any payload from our manufacturer ID using setManufacturerData with non-null masks
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
            showErrorToast("Fallo al iniciar búsqueda BLE: ${e.localizedMessage}")
        }
    }

    private fun showErrorToast(msg: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
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

            val peer = parseManufacturerData(rawData) ?: return
            handleDiscoveredPeer(peer)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { result ->
                val record = result.scanRecord ?: return@forEach
                val rawData = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return@forEach
                val peer = parseManufacturerData(rawData) ?: return@forEach
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

        // Ignore our own advertisement if reflected
        if (peer.sessionToken == currentSessionToken && currentSessionToken != "000000000000") {
            return
        }
        if (peer.sessionToken == currentSendingToken && currentSendingToken != "000000000000") {
            return
        }

        recentlyDetectedPeerNames[peer.sessionToken] = peer.userName
        recentlyDetectedPeerAvatars[peer.sessionToken] = peer.avatarIndex
        Log.d(tag, "Discovered BLE peer: name=${peer.userName}, token=${peer.sessionToken}, state=${peer.state}, avatarIndex=${peer.avatarIndex}")

        if (peer.state == 1) { // Peer is SENDING
            // Record active sending peer
            activeSendingPeers[peer.sessionToken] = now

            // Clean up stale sending peers (older than 15 seconds)
            val iterator = activeSendingPeers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 15_000) {
                    iterator.remove()
                }
            }

            if (activeSendingPeers.size >= 2) {
                // TAREA B: Ambiguity guard. Stop any active audio beacon listener and don't start new ones.
                Log.d(tag, "AMBIGUITY GUARD: Multiple active sending peers detected (${activeSendingPeers.keys}). Stopping AudioBeaconListener.")
                audioBeaconListener?.stop()

                val lastNotified = recentlyNotifiedPeers[peer.sessionToken] ?: 0L
                if (now - lastNotified > 10_000) {
                    recentlyNotifiedPeers[peer.sessionToken] = now
                    // Broadcast ACTION_PEER_SENDING with ambiguity flag to show the choice list in MainActivity
                    val intent = Intent(ACTION_PEER_SENDING).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PEER_NAME, peer.userName)
                        putExtra(EXTRA_PEER_TOKEN, peer.sessionToken)
                        putExtra("EXTRA_PEER_AVATAR", peer.avatarIndex)
                        putExtra("EXTRA_IS_AMBIGUOUS", true)
                    }
                    sendBroadcast(intent)
                }
            } else {
                // Exactly one active sending peer. Normal flow.
                val lastNotified = recentlyNotifiedPeers[peer.sessionToken] ?: 0L
                if (now - lastNotified > 10_000) { // Throttle duplicate notifications (10 seconds)
                    recentlyNotifiedPeers[peer.sessionToken] = now
                    Log.d(tag, "PEER SENDING DETECTED: ${peer.userName} (${peer.sessionToken})")

                    // Start listening to the beacon (FIX 4)
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(tag, "RECORD_AUDIO permission granted. Starting AudioBeaconListener for token: ${peer.sessionToken}")
                        audioBeaconListener?.start(peer.sessionToken)
                    } else {
                        Log.e(tag, "RECORD_AUDIO permission not granted. Cannot start AudioBeaconListener.")
                        showErrorToast("Permiso de micrófono no otorgado. No se puede escuchar la baliza.")
                    }

                    // Broadcast the PEER_SENDING to MainActivity (using explicit intent)
                    val intent = Intent(ACTION_PEER_SENDING).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PEER_NAME, peer.userName)
                        putExtra(EXTRA_PEER_TOKEN, peer.sessionToken)
                        putExtra("EXTRA_PEER_AVATAR", peer.avatarIndex)
                        putExtra("EXTRA_IS_AMBIGUOUS", false)
                    }
                    sendBroadcast(intent)

                    // Show a notification
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

    private fun buildManufacturerData(state: Int, sessionToken: String, avatarIndex: Int, userName: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val dos = DataOutputStream(stream)
        dos.writeByte(state) // 1 byte state (0 = IDLE, 1 = SENDING)

        // Write Session Token (6 bytes hex represented by 12 chars string, convert to 6 bytes payload)
        val tokenBytes = ByteArray(6)
        try {
            for (i in 0 until 6) {
                val byteStr = sessionToken.substring(i * 2, i * 2 + 2)
                tokenBytes[i] = byteStr.toInt(16).toByte()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse session token to bytes", e)
        }
        dos.write(tokenBytes) // 6 bytes

        dos.writeByte(avatarIndex) // 1 byte avatar index

        // Write truncated User Name
        val nameBytes = userName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size.coerceAtMost(10)
        dos.write(nameBytes, 0, nameLength)

        return stream.toByteArray()
    }

    private fun parseManufacturerData(data: ByteArray): BlePeer? {
        if (data.size < 8) return null // 1 byte state + 6 bytes token + 1 byte avatar = 8 bytes minimum check
        return try {
            val buffer = ByteBuffer.wrap(data)
            val state = buffer.get().toInt()

            val tokenBytes = ByteArray(6)
            buffer.get(tokenBytes)
            val sessionToken = tokenBytes.joinToString("") { String.format("%02X", it) }

            val avatarIndex = buffer.get().toInt()

            val nameBytes = ByteArray(data.size - 8)
            buffer.get(nameBytes)
            val userName = String(nameBytes, Charsets.UTF_8).trim()

            BlePeer(
                userName = if (userName.isEmpty()) "Usuario BLE" else userName,
                sessionToken = sessionToken,
                state = state,
                avatarIndex = avatarIndex
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
        val avatarIndex: Int = 0
    )
}
