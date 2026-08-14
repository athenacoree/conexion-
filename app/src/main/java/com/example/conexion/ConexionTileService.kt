package com.example.conexion

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class ConexionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val isCurrentlyActive = prefs.getBoolean("bg_discovery_enabled", true)
        val newState = !isCurrentlyActive

        prefs.edit().putBoolean("bg_discovery_enabled", newState).apply()

        val serviceIntent = Intent(this, BackgroundDiscoveryService::class.java).apply {
            action = if (newState) BackgroundDiscoveryService.ACTION_START else BackgroundDiscoveryService.ACTION_STOP
        }

        if (newState) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("bg_discovery_enabled", true)

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Conexión Activa" else "Conexión Pausada"
        tile.updateTile()
    }
}
