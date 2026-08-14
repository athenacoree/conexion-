package com.example.conexion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("bg_discovery_enabled", true)
            if (isEnabled) {
                val serviceIntent = Intent(context, BackgroundDiscoveryService::class.java).apply {
                    this.action = BackgroundDiscoveryService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("BootReceiver", "Started BackgroundDiscoveryService on boot")
            }
        }
    }
}
