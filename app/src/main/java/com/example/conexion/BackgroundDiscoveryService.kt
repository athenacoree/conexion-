package com.example.conexion

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class BackgroundDiscoveryService : Service() {

    private val tag = "BgDiscoveryService"

    companion object {
        const val ACTION_START = "com.example.conexion.ACTION_START"
        const val ACTION_UPDATE_SHAKE = "com.example.conexion.ACTION_UPDATE_SHAKE"
        const val ACTION_STOP = "com.example.conexion.ACTION_STOP"

        const val EXTRA_USER_NAME = "EXTRA_USER_NAME"
        const val EXTRA_WIFI_MAC = "EXTRA_WIFI_MAC"
        const val EXTRA_SHAKE_TIMESTAMP = "EXTRA_SHAKE_TIMESTAMP"

        const val ACTION_PEER_FOUND = "com.example.conexion.ACTION_PEER_FOUND"
        const val EXTRA_PEER_NAME = "EXTRA_PEER_NAME"
        const val EXTRA_PEER_MAC = "EXTRA_PEER_MAC"
        const val EXTRA_PEER_SHAKE = "EXTRA_PEER_SHAKE"

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
    private var currentShakeTime = 0L

    // Tracks peers to avoid showing duplicate notifications in a short window
    private val recentlyNotifiedPeers = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service onCreate")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner

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

        when (action) {
            ACTION_START -> {
                startForegroundCompat()
                startAdvertisingAndScanning()
            }
            ACTION_UPDATE_SHAKE -> {
                currentShakeTime = intent?.getLongExtra(EXTRA_SHAKE_TIMESTAMP, 0L) ?: 0L
                Log.d(tag, "Updating shake timestamp to: $currentShakeTime")
                startForegroundCompat()
                // Restart advertising with the updated payload containing the shake timestamp
                startAdvertisingAndScanning()
            }
            ACTION_STOP -> {
                stopAdvertisingAndScanning()
                stopSelf()
            }
        }

        return START_STICKY
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

        val statusText = if (currentShakeTime > 0) {
            "¡Teléfono agitado recientemente! Buscando coincidencias..."
        } else {
            "Búsqueda pasiva activada. Agita para conectar de inmediato."
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
            if (advertiser == null) advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (scanner == null) scanner = bluetoothAdapter?.bluetoothLeScanner

            stopAdvertisingAndScanningSilently()

            // 1. Start BLE Advertising
            val advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            val payload = buildManufacturerData(currentShakeTime, currentWifiMac, currentUserName)
            val advData = AdvertiseData.Builder()
                .addManufacturerData(MANUFACTURER_ID, payload)
                .build()

            advertiser?.startAdvertising(advSettings, advData, advertiseCallback)
            Log.d(tag, "Started BLE Advertising with payload size: ${payload.size} bytes")

            // 2. Start BLE Scanning
            // Match any payload from our manufacturer ID using setManufacturerData with non-null masks
            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, byteArrayOf(0), byteArrayOf(0))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(tag, "Started BLE Scanning")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start advertising or scanning", e)
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
        if (peer.wifiMac == currentWifiMac && currentWifiMac != "00:00:00:00:00:00") {
            return
        }

        // Check if both devices have a recent shake within 15 seconds of each other
        val timeDiff = Math.abs(currentShakeTime - peer.shakeTimestamp)
        Log.d(tag, "Discovered BLE peer: name=${peer.userName}, mac=${peer.wifiMac}, shakeTime=${peer.shakeTimestamp}, diff=${timeDiff / 1000}s")

        if (currentShakeTime > 0 && peer.shakeTimestamp > 0 && timeDiff < 15_000) {
            // We have a match! Let's notify and connect
            val lastNotified = recentlyNotifiedPeers[peer.wifiMac] ?: 0L
            if (now - lastNotified > 10_000) { // Throttle duplicate notifications (10 seconds)
                recentlyNotifiedPeers[peer.wifiMac] = now
                Log.d(tag, "MATCH DETECTED with peer: ${peer.userName} (${peer.wifiMac})")

                // 1. Broadcast the match to MainActivity
                val intent = Intent(ACTION_PEER_FOUND).apply {
                    putExtra(EXTRA_PEER_NAME, peer.userName)
                    putExtra(EXTRA_PEER_MAC, peer.wifiMac)
                    putExtra(EXTRA_PEER_SHAKE, peer.shakeTimestamp)
                }
                sendBroadcast(intent)

                // 2. Show a high priority heads-up notification so they can connect even outside the app
                showMatchNotification(peer)
            }
        }
    }

    private fun showMatchNotification(peer: BlePeer) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER_NAME, peer.userName)
            putExtra(EXTRA_PEER_MAC, peer.wifiMac)
            putExtra(EXTRA_PEER_SHAKE, peer.shakeTimestamp)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("¡Dispositivo Cercano Encontrado!")
            .setContentText("Toca para conectarte de forma segura con ${peer.userName}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(MATCH_NOTIFICATION_ID, notification)
    }

    private fun buildManufacturerData(shakeTime: Long, wifiMac: String, userName: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val dos = DataOutputStream(stream)
        dos.writeLong(shakeTime) // 8 bytes

        // Write MAC Address
        val macBytes = ByteArray(6)
        val parts = wifiMac.split(":")
        if (parts.size == 6) {
            try {
                for (i in 0 until 6) {
                    macBytes[i] = parts[i].toInt(16).toByte()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse MAC address bytes", e)
            }
        }
        dos.write(macBytes) // 6 bytes

        // Write truncated User Name
        val nameBytes = userName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size.coerceAtMost(10)
        dos.write(nameBytes, 0, nameLength)

        return stream.toByteArray()
    }

    private fun parseManufacturerData(data: ByteArray): BlePeer? {
        if (data.size < 14) return null
        return try {
            val buffer = ByteBuffer.wrap(data)
            val shakeTime = buffer.long

            val macBytes = ByteArray(6)
            buffer.get(macBytes)
            val wifiMac = macBytes.joinToString(":") { String.format("%02X", it) }

            val nameBytes = ByteArray(data.size - 14)
            buffer.get(nameBytes)
            val userName = String(nameBytes, Charsets.UTF_8).trim()

            BlePeer(
                userName = if (userName.isEmpty()) "Usuario BLE" else userName,
                wifiMac = wifiMac,
                shakeTimestamp = shakeTime
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse manufacturer data", e)
            null
        }
    }

    data class BlePeer(
        val userName: String,
        val wifiMac: String,
        val shakeTimestamp: Long
    )
}
