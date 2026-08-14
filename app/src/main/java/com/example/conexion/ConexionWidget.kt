package com.example.conexion

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class ConexionWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.example.conexion.ACTION_WIDGET_TOGGLE"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("bg_discovery_enabled", true)
            val newState = !isEnabled

            prefs.edit().putBoolean("bg_discovery_enabled", newState).apply()

            val serviceIntent = Intent(context, BackgroundDiscoveryService::class.java).apply {
                action = if (newState) BackgroundDiscoveryService.ACTION_START else BackgroundDiscoveryService.ACTION_STOP
            }

            if (newState) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, ConexionWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("bg_discovery_enabled", true)

        val views = RemoteViews(context.packageName, R.layout.conexion_widget_layout)
        views.setTextViewText(R.id.widget_title, "Conexión Directa")
        views.setTextViewText(
            R.id.widget_status,
            if (isEnabled) "🟢 Escaneo en segundo plano: ACTIVO" else "🔴 Escaneo en segundo plano: INACTIVO"
        )
        views.setTextViewText(
            R.id.widget_btn_toggle,
            if (isEnabled) "Pausar Sistema" else "Activar Sistema"
        )

        val toggleIntent = Intent(context, ConexionWidget::class.java).apply {
            action = ACTION_WIDGET_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_toggle, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
