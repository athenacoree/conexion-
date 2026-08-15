package com.example.conexion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import kotlinx.coroutines.*

data class PeerInfo(
    val device: WifiP2pDevice,
    val userName: String,
    val sessionToken: String = "",
    val avatarIndex: Int = 0,
    val phoneNumber: String = "",
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

class WifiP2pHelper(
    private val context: Context,
    private val onConnectionChanged: (WifiP2pInfo?) -> Unit,
    private val onPeersDiscovered: (List<PeerInfo>) -> Unit,
    private val onConnectionRequestReceived: (PeerInfo) -> Unit,
    private val onError: (String) -> Unit
) {
    private val tag = "WifiP2pHelper"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var isGroupFormed: Boolean = false

    val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    var channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)

    private var receiver: WifiDirectBroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val discoveredServicePeers = mutableMapOf<String, PeerInfo>()
    private val tokenToPeerMap = mutableMapOf<String, PeerInfo>()
    private var pendingTargetToken: String? = null
    private var pendingTargetName: String? = null

    private var localServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    var myDeviceName: String = "Usuario"
    var myDeviceAddress: String = ""

    fun findPeerByToken(token: String): PeerInfo? {
        if (token.isEmpty() || token == "00:00:00:00:00:00") return null
        tokenToPeerMap[token]?.let { return it }
        return discoveredServicePeers.values.find {
            it.sessionToken.equals(token, ignoreCase = true) ||
                    it.device.deviceAddress.equals(token, ignoreCase = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectByAddress(macAddress: String, userName: String = "Dispositivo") {
        if (manager == null || channel == null || macAddress.isEmpty()) return
        val dummyDevice = WifiP2pDevice().apply {
            deviceAddress = macAddress
            this.deviceName = userName
        }
        val peer = PeerInfo(device = dummyDevice, userName = userName, sessionToken = macAddress)
        connectToPeer(peer)
    }

    fun triggerConnectionRequest(peer: PeerInfo) {
        onConnectionRequestReceived(peer)
    }

    fun startDiscoveryForToken(token: String, userName: String) {
        pendingTargetToken = token
        pendingTargetName = userName
        startDiscovery()
    }

    init {
        setupBroadcastReceiver()
    }

    private fun setupBroadcastReceiver() {
        receiver = WifiDirectBroadcastReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
    }

    private inner class WifiDirectBroadcastReceiver : android.content.BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(tag, "Wi-Fi P2P State Changed: $state")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(tag, "Wi-Fi P2P Peers Changed")
                    manager?.requestPeers(channel) { peerList ->
                        val list = peerList.deviceList.toList()
                        Log.d(tag, "Discovered standard P2P peers: ${list.size}")
                        list.forEach { dev ->
                            if (!discoveredServicePeers.containsKey(dev.deviceAddress)) {
                                val peer = PeerInfo(
                                    device = dev,
                                    userName = if (dev.deviceName.isNullOrEmpty()) "Dispositivo P2P" else dev.deviceName,
                                    sessionToken = dev.deviceAddress,
                                    avatarIndex = (dev.deviceAddress.hashCode() and 0x7FFFFFFF) % 6,
                                    phoneNumber = "",
                                    rssi = -55,
                                    distanceMeters = 1.2
                                )
                                discoveredServicePeers[dev.deviceAddress] = peer
                            }
                        }
                        onPeersDiscovered(discoveredServicePeers.values.toList())
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    Log.d(tag, "Connection changed: isConnected = ${networkInfo?.isConnected}")
                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info ->
                            Log.d(tag, "Connection Info Received: Group Owner = ${info.isGroupOwner}, Address = ${info.groupOwnerAddress}")
                            isGroupFormed = info.groupFormed
                            onConnectionChanged(info)
                        }
                    } else {
                        isGroupFormed = false
                        onConnectionChanged(null)
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    device?.let {
                        myDeviceAddress = it.deviceAddress
                        Log.d(tag, "This Device Changed: name = ${it.deviceName}, address = ${it.deviceAddress}")
                    }
                }
            }
        }
    }

    fun unregister() {
        try {
            scope.cancel()
        } catch (e: Exception) {
            Log.e(tag, "Error cancelling coroutine scope", e)
        }
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering receiver", e)
        }
        stopDiscovery()
    }

    fun setDeviceName(name: String) {
        myDeviceName = name
        if (manager == null || channel == null) return
        try {
            val method: Method = manager.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(manager, channel, name, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Successfully changed Wi-Fi Direct device name to: $name")
                }
                override fun onFailure(reason: Int) {
                    Log.e(tag, "Failed to change Wi-Fi Direct device name best-effort. Reason code: $reason. This is expected on Android 9+.")
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "CRITICAL WARNING: Reflection failed to change device name: ${e.message}. This is expected on Android 9+ and will be bypassed using DNS-SD.", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(userName: String, sessionToken: String) {
        if (manager == null || channel == null) return
        setDeviceName(userName)

        val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val avatarIdx = prefs.getInt("avatar_index", 0).toString()
        val phoneNum = prefs.getString("phone_number", "") ?: ""

        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val record = mapOf(
                    "name" to userName,
                    "token" to sessionToken,
                    "avatar" to avatarIdx,
                    "phone" to phoneNum
                )
                localServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                    "_conexion",
                    "_presence._tcp",
                    record
                )
                manager.addLocalService(channel, localServiceInfo, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(tag, "Local service registered: $record")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Failed to add local service: $reason")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.e(tag, "Failed to clear local services: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (manager == null || channel == null) return

        discoveredServicePeers.clear()

        // Also initiate standard P2P peer discovery so devices are always found
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Wi-Fi P2P discoverPeers initiated")
            }
            override fun onFailure(reason: Int) {
                Log.e(tag, "Wi-Fi P2P discoverPeers failed: $reason")
            }
        })

        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                Log.d(tag, "Service discovered: instanceName=$instanceName, type=$registrationType, device=${srcDevice.deviceName}")
            },
            { _, txtRecordMap, srcDevice ->
                Log.d(tag, "TXT record received: $txtRecordMap from ${srcDevice.deviceAddress}")
                val name = txtRecordMap["name"] ?: "Usuario Desconocido"
                val token = txtRecordMap["token"] ?: ""
                val avatarIdx = txtRecordMap["avatar"]?.toIntOrNull() ?: 0
                val phone = txtRecordMap["phone"] ?: ""

                if (token.isNotEmpty()) {
                    val peer = PeerInfo(srcDevice, name, token, avatarIdx, phone)
                    discoveredServicePeers[srcDevice.deviceAddress] = peer
                    tokenToPeerMap[token] = peer
                    onPeersDiscovered(discoveredServicePeers.values.toList())

                    if (pendingTargetToken != null && pendingTargetToken == token) {
                        Log.d(tag, "Discovered service record matching target token: $token")
                        pendingTargetToken = null
                        pendingTargetName = null
                        onConnectionRequestReceived(peer)
                    } else {
                        Log.d(tag, "Discovered service peer (no matching pending target token): name=${peer.userName}, token=${peer.sessionToken}")
                    }
                }
            }
        )

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(tag, "Service discovery initiated successfully.")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Service discovery failed: $reason")
                        onError("Falla al iniciar descubrimiento de servicios (código $reason).")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.e(tag, "Failed to add service request: $reason")
                onError("Falla al agregar solicitud de servicios (código $reason).")
            }
        })
    }

    fun stopDiscovery() {
        if (manager == null || channel == null) return
        if (serviceRequest != null) {
            manager.removeServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(tag, "Service request removed") }
                override fun onFailure(reason: Int) {}
            })
        }
        manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(peer: PeerInfo, force: Boolean = false) {
        if (manager == null || channel == null) return

        Log.d(tag, "Connecting instantly to peer: ${peer.userName} (${peer.device.deviceAddress})")

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.device.deviceAddress
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Initiated connection to ${peer.userName}")
            }
            override fun onFailure(reason: Int) {
                val errorDesc = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct no es soportado en este dispositivo."
                    WifiP2pManager.BUSY -> "El sistema de Wi-Fi Direct está ocupado. Reintentando..."
                    else -> "Fallo al iniciar conexión (código $reason)."
                }
                Log.e(tag, "Connection initiation failed: $reason")
                if (reason == WifiP2pManager.BUSY) {
                    scope.launch {
                        delay(1000)
                        manager.connect(channel, config, null)
                    }
                }
                onError(errorDesc)
            }
        })
    }

    fun disconnect() {
        if (manager == null || channel == null) return
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Successfully disconnected from P2P group.")
            }
            override fun onFailure(reason: Int) {
                Log.e(tag, "Failed to disconnect: $reason")
            }
        })
    }
}
