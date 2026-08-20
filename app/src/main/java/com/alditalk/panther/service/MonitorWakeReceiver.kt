package com.alditalk.panther.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.alditalk.panther.PantherApp

/**
 * Empfangs-Receiver für AlarmManager-Fallback und (optional) Boot-Completed.
 *
 * Anforderung 5 – Hintergrundprozess fuer Huawei AGS2-L09 (EMUI / Android 8):
 * Falls das System den [MonitorService] killt, plant der Service vorab einen
 * AlarmManager-Ping – dieser Receiver startet den Service dann neu ab. Eingebundene
 * Aktionen:
 *   - [ACTION_RESTART_MONITOR]            : expliziter Neustart durch AlarmManager
 *   - Intent.ACTION_BOOT_COMPLETED        : Start nach Reboot, falls der Nutzer vorher
 *                                          aktiv war (Werte kommen aus Prefs)
 */
class MonitorWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "onReceive: action=${intent?.action}")
        when (intent?.action) {
            ACTION_RESTART_MONITOR,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> restartMonitor(context, intent)
        }
    }

    /**
     * Startet den [MonitorService] als Foreground Service neu. Vorhandene Extras
     * (phone/password/threshold/interval) werden weitergereicht; fehlen diese,
     *liest der Service beim naechsten Nutzer-Klick neu ein.
     */
    private fun restartMonitor(context: Context, source: Intent?) {
        val prefs = PantherApp.securePreferences(context)
        val isBoot = source?.action == Intent.ACTION_BOOT_COMPLETED ||
            source?.action == "android.intent.action.QUICKBOOT_POWERON"

        // Nach einem Neustart nur wiederherstellen, wenn der Nutzer den Monitor
        // vorher aktiv gestartet hatte. So startet die App nicht ungefragt.
        if (isBoot && !prefs.getBoolean("monitor_enabled", false)) return

        val phone = source?.getStringExtra(MonitorService.EXTRA_PHONE)
            ?: prefs.getString("phone", null)
        val password = source?.getStringExtra(MonitorService.EXTRA_PASSWORD)
            ?: prefs.getString("password", null)
        if (phone.isNullOrBlank() || password.isNullOrBlank()) {
            Log.i(TAG, "Monitor-Neustart übersprungen: keine gespeicherten Login-Daten")
            return
        }

        val threshold = source?.getFloatExtra(
            MonitorService.EXTRA_THRESHOLD_MB,
            prefs.getString("threshold_mb", "850")?.toFloatOrNull() ?: 850f
        ) ?: 850f
        val interval = source?.getIntExtra(
            MonitorService.EXTRA_INTERVAL_SEC,
            prefs.getString("interval_sec", "60")?.toIntOrNull() ?: 60
        ) ?: 60

        val svcIntent = Intent(context, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_PHONE, phone)
            putExtra(MonitorService.EXTRA_PASSWORD, password)
            putExtra(MonitorService.EXTRA_THRESHOLD_MB, threshold)
            // Unter 60 Sekunden würden unnötig viele Mobilfunkanfragen und Wakeups
            // entstehen; der Monitor verwendet denselben Mindestwert wie die Activity.
            putExtra(MonitorService.EXTRA_INTERVAL_SEC, interval.coerceAtLeast(60))
        }

        try {
            ContextCompat.startForegroundService(context, svcIntent)
        } catch (e: Exception) {
            // Hintergrundstart-Restriktionen o.a. – Controller-Fehler melden, nicht crashen.
            Log.w(TAG, "Restart des MonitorService fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "MonitorWakeReceiver"
        const val ACTION_RESTART_MONITOR = "com.alditalk.panther.RESTART_MONITOR"
    }
}
