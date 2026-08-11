package com.example.conexion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pInfo
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    private lateinit var shakeDetector: ShakeDetector
    private lateinit var wifiP2pHelper: WifiP2pHelper
    private lateinit var fileTransferManager: FileTransferManager

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

    // Shake state
    private var isShakeSearching = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize helper classes
        shakeDetector = ShakeDetector(this) {
            handleShake()
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
            }
        )

        fileTransferManager = FileTransferManager(this) { fileName, bytes, total, completed ->
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

        toggleWifi(true)

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

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun handleShake() {
        if (isShakeSearching.value) return
        val currentShakeTime = System.currentTimeMillis()

        lifecycleScope.launch {
            isShakeSearching.value = true
            Toast.makeText(this@MainActivity, "¡Agitado detectado! Buscando dispositivos cercanos...", Toast.LENGTH_SHORT).show()

            wifiP2pHelper.startAdvertisingShake(myNameState.value, currentShakeTime)
            wifiP2pHelper.startDiscovery()

            delay(15_000)
            wifiP2pHelper.stopDiscovery()
            isShakeSearching.value = false
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
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
                .padding(16.dp),
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
                modifier = Modifier.padding(bottom = 24.dp)
            )

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
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        if (isShakeSearching.value) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .border(
                        2.dp,
                        if (isShakeSearching.value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isShakeSearching.value) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Buscando...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "¡AGITA!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "para conectar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
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
                            text = "¿Te vas a conectar con ${peer.userName}?\n" +
                                    "Ambos agitaron el teléfono cerca en el mismo instante.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                wifiP2pHelper.connectToPeer(peer)
                                connectionPromptPeer.value = null
                            }
                        ) {
                            Text("Sí, Conectar")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { connectionPromptPeer.value = null }
                        ) {
                            Text("Cancelar")
                        }
                    }
                )
            }
        }
    }
}
