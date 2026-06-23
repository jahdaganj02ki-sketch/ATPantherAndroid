package com.alditalk.panther.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.alditalk.panther.R
import com.alditalk.panther.api.AldiTalkApi
import com.alditalk.panther.auth.AuthService
import com.alditalk.panther.data.AppDatabase
import com.alditalk.panther.data.LogEntry
import kotlinx.coroutines.*

/**
 * Foreground service that monitors ALDI Talk data volume and auto-books 1 GB
 * when remaining data drops below the threshold.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "at_panther_monitor"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_PHONE = "phone"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_CONTRACT_ID = "contract_id"
        const val EXTRA_THRESHOLD_MB = "threshold_mb"
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        const val ACTION_STOP = "com.alditalk.panther.STOP"

        /** Broadcast action sent on status update. */
        const val ACTION_STATUS_UPDATE = "com.alditalk.panther.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_REMAINING_MB = "remaining_mb"
    }

    private var serviceJob: Job? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val phone = intent?.getStringExtra(EXTRA_PHONE) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        val contractId = intent?.getStringExtra(EXTRA_CONTRACT_ID) ?: "31376559"
        val thresholdMb = intent?.getFloatExtra(EXTRA_THRESHOLD_MB, 250f) ?: 250f
        val intervalSec = intent?.getIntExtra(EXTRA_INTERVAL_SEC, 60) ?: 60

        if (phone.isEmpty() || password.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starte Monitor..."))

        if (serviceJob?.isActive == true) {
            return START_STICKY
        }

        isRunning = true
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serviceJob = scope.launch {
            monitorLoop(phone, password, contractId, thresholdMb, intervalSec)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceJob?.cancel()
        Log.d(TAG, "MonitorService gestoppt")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorLoop(
        phone: String,
        password: String,
        contractId: String,
        thresholdMb: Float,
        intervalSec: Int,
    ) {
        val logDao = AppDatabase.getDatabase(this).logDao()

        // Login once
        updateNotification("Anmelde...")
        broadcastStatus("Anmelden...", -1f)

        val authService = AuthService()
        val loginResult = authService.login(phone, password)
        if (!loginResult.success || loginResult.client == null) {
            val msg = "Login fehlgeschlagen: ${loginResult.error}"
            Log.e(TAG, msg)
            logDao.insert(LogEntry(type = "CHECK", message = msg))
            updateNotification(msg)
            broadcastStatus(msg, -1f)
            stopSelf()
            return
        }

        logDao.insert(LogEntry(type = "CHECK", message = "Login erfolgreich"))
        val api = AldiTalkApi(loginResult.client)

        while (isRunning && isActive) {
            try {
                // Clean old entries (keep last 7 days)
                val sevenDays = System.currentTimeMillis() - 7 * 24 * 3600_000L
                logDao.deleteOlderThan(sevenDays)

                // Fetch data status
                val status = api.getRemainingData(contractId)
                if (status == null) {
                    val msg = "Datenvolumen konnte nicht abgefragt werden"
                    Log.w(TAG, msg)
                    logDao.insert(LogEntry(type = "CHECK", remainingMb = -1f, message = msg))
                    updateNotification(msg)
                    broadcastStatus(msg, -1f)
                    delay(intervalSec * 1000L)
                    continue
                }

                val remainingStr = "%.1f".format(status.remainingMb)
                val msg = "Verbleibend: $remainingStr MB"

                if (status.remainingMb < thresholdMb) {
                    Log.w(TAG, "$msg — unter Schwelle ($thresholdMb MB), buche 1 GB")
                    logDao.insert(LogEntry(type = "CHECK", remainingMb = status.remainingMb.toFloat(), message = msg))

                    // Book 1 GB
                    updateNotification("Buche 1 GB...")
                    broadcastStatus("Buche 1 GB...", status.remainingMb.toFloat())

                    val booking = api.book1Gb(status)
                    val bookMsg = if (booking.success) {
                        "✅ 1 GB erfolgreich gebucht"
                    } else {
                        "❌ Buchung fehlgeschlagen (${booking.statusCode}): ${booking.message.take(100)}"
                    }
                    logDao.insert(LogEntry(type = "BOOKING", remainingMb = status.remainingMb.toFloat(), message = bookMsg))
                    updateNotification(bookMsg)
                    broadcastStatus(bookMsg, status.remainingMb.toFloat())
                } else {
                    Log.d(TAG, msg)
                    logDao.insert(LogEntry(type = "CHECK", remainingMb = status.remainingMb.toFloat(), message = msg))
                    updateNotification(msg)
                    broadcastStatus(msg, status.remainingMb.toFloat())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Monitor-Fehler", e)
                val errMsg = "Fehler: ${e.message?.take(80)}"
                logDao.insert(LogEntry(type = "CHECK", message = errMsg))
                updateNotification(errMsg)
                broadcastStatus(errMsg, -1f)
            }

            delay(intervalSec * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AT Panther")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastStatus(statusText: String, remainingMb: Float) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_REMAINING_MB, remainingMb)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
