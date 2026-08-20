package com.alditalk.panther

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alditalk.panther.data.LogDao
import com.alditalk.panther.data.LogEntry
import com.alditalk.panther.lock.MonitorDeviceIdentity
import com.alditalk.panther.lock.MonitorLockClient
import com.alditalk.panther.lock.MonitorLockConfig
import com.alditalk.panther.service.MonitorService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogEntry>() {
    override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
        oldItem == newItem
}

class MainActivity : AppCompatActivity() {

    // Default-Werte
    private val defaultThresholdMb = 850f   // Anforderung 2: Standardwert 850 MB
    private val defaultIntervalSec = 60
    private val minimumIntervalSec = 60

    private lateinit var etPhone: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etThreshold: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var rgMonitorPlatform: android.widget.RadioGroup
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnClearCache: MaterialButton
    private lateinit var btnExportLog: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton
    private lateinit var btnAutoStart: MaterialButton
    private lateinit var rvLog: RecyclerView
    private lateinit var logDao: LogDao

    private var isServiceRunning = false

    /**
     * SAF Launcher für ACTION_CREATE_DOCUMENT – oeffnet den System-Dateidialog,
     * damit der Nutzer den Speicherort der .txt-Datei frei waehlen kann.
     */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            exportLogToUri(uri)
        } else {
            Toast.makeText(this, "Export abgebrochen", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Benachrichtigungen sind deaktiviert; der Monitor läuft trotzdem weiter.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(MonitorService.EXTRA_STATUS_TEXT) ?: "—"
            val remaining = intent?.getFloatExtra(MonitorService.EXTRA_REMAINING_MB, -1f) ?: -1f
            tvStatus.text = if (remaining >= 0) "$status  (${"%.1f".format(remaining)} MB)" else status
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // View binding
        etPhone = findViewById(R.id.etPhone)
        etPassword = findViewById(R.id.etPassword)
        etThreshold = findViewById(R.id.etThreshold)
        etInterval = findViewById(R.id.etInterval)
        rgMonitorPlatform = findViewById(R.id.rgMonitorPlatform)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggleService)
        btnSave = findViewById(R.id.btnSaveCredentials)
        btnClearCache = findViewById(R.id.btnClearCache)
        btnExportLog = findViewById(R.id.btnExportLog)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnAutoStart = findViewById(R.id.btnAutoStart)
        rvLog = findViewById(R.id.rvLog)

        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.setHasFixedSize(true)

        // Android 13+: Die Berechtigung ist für die sichtbare Monitor-Notification nötig.
        // Auf Android 12 des Zielgeräts wird dieser Block übersprungen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Anforderung 1: Gespeicherte Login-Daten UND Einstellungen laden
        loadCredentials()

        // Save credentials button – speichert auch die aktive Monitor-Plattform.
        btnSave.setOnClickListener {
            saveCredentials()
            lifecycleScope.launch { selectRemotePlatform(showMessage = false) }
            Toast.makeText(this, "Login-Daten und Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
        }

        // Toggle service button
        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopMonitor()
            } else {
                startMonitor()
            }
        }

        // Anforderung 3: App-Cache leeren
        btnClearCache.setOnClickListener {
            clearAppCache()
            Toast.makeText(this, "Cache geleert", Toast.LENGTH_SHORT).show()
        }

        // Anforderung 4: Log exportieren – oeffnet SAF-Dateidialog
        btnExportLog.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMAN).format(Date())
            createDocumentLauncher.launch("at_panther_log_$timestamp.txt")
        }

        // Anforderung 5: Direkt zum Batterie-Optimierungs-Dialog (EMUI/Huawei)
        btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        // Hersteller-spezifische Hintergrund-/Autostart-Einstellungen öffnen
        btnAutoStart.setOnClickListener {
            openAutoStartSettings()
        }

        // Observe log entries
        logDao = (application as PantherApp).database.logDao()
        val adapter = LogAdapter()
        rvLog.adapter = adapter
        lifecycleScope.launch {
            logDao.getRecent().collectLatest { entries ->
                adapter.submitList(entries)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MonitorService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ── SharedPreferences (Login-Daten + Einstellungen) ──

    private fun getEncryptedPrefs() =
        (application as PantherApp).securePreferences()

    /**
     * Anforderung 1: Login-Daten plus Einstellungen (Schwelle/Intervall) speichern.
     * Schwelle/Intervall werden als String gespeichert, damit das inputType=number-
     * Feld beim Laden exakt den vom Nutzer getippten Wert zurück erhält.
     */
    private fun saveCredentials() {
        val prefs = getEncryptedPrefs()
        prefs.edit()
            .putString("phone", etPhone.text.toString().trim())
            .putString("password", etPassword.text.toString().trim())
            .putString("threshold_mb", etThreshold.text.toString().trim())
            .putString("interval_sec", etInterval.text.toString().trim())
            .putString("monitor_platform", selectedPlatform())
            .apply()
    }

    /**
     * Gespeicherte Login-Daten und Einstellungen laden. Default-Schwelle = 850 MB.
     */
    private fun loadCredentials() {
        val prefs = getEncryptedPrefs()
        etPhone.setText(prefs.getString("phone", ""))
        etPassword.setText(prefs.getString("password", ""))
        // Anforderung 2: Standardwert 850 MB beim ersten App-Start (vorher 250)
        etThreshold.setText(prefs.getString("threshold_mb", defaultThresholdMb.toInt().toString()))
        etInterval.setText(prefs.getString("interval_sec", defaultIntervalSec.toString()))
        rgMonitorPlatform.check(
            if (prefs.getString("monitor_platform", "android") == "windows") {
                R.id.rbWindows
            } else {
                R.id.rbAndroid
            }
        )
    }

    private fun selectedPlatform(): String =
        if (rgMonitorPlatform.checkedRadioButtonId == R.id.rbWindows) "windows" else "android"

    private suspend fun selectRemotePlatform(showMessage: Boolean): Boolean {
        if (!MonitorLockConfig.isConfigured) {
            if (showMessage) {
                Toast.makeText(this, "Lock-Backend noch nicht eingerichtet", Toast.LENGTH_LONG).show()
            }
            return false
        }
        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty()) return false
        val selected = MonitorLockClient().selectPlatform(
            phone,
            MonitorDeviceIdentity.get(this),
            selectedPlatform(),
        )
        if (!selected && showMessage) {
            Toast.makeText(this, "Aktive Monitor-Plattform konnte nicht gespeichert werden", Toast.LENGTH_LONG).show()
        }
        return selected
    }

    private fun parseThreshold(): Float =
        etThreshold.text.toString().trim().toFloatOrNull() ?: defaultThresholdMb

    private fun parseInterval(): Int =
        (etInterval.text.toString().trim().toIntOrNull() ?: defaultIntervalSec)
            .coerceAtLeast(minimumIntervalSec)

    // ── Cache leeren ──

    /**
     * Anforderung 3: Loescht den lokalen App-Cache sowie (soweit moeglich)
     * die Code-Caches des Prozesses. Nach dem Loeschen wird der Status
     * vom Aufrufer per Toast bestaetigt.
     */
    private fun clearAppCache() {
        try {
            // Cache-Verzeichnis der App loeschen
            cacheDir?.let { it.deleteRecursively() }
            // Wennture eine sekundaere Cache-Dir vorhanden ist, ebenfalls leeren
            externalCacheDir?.let { it.deleteRecursively() }

            // Code-Caches (API 23+) leeren, ohne die App zu killen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                codeCacheDir?.let { it.deleteRecursively() }
            }
        } catch (e: Exception) {
            // Einzelfehler beim Cache-Loeschen nicht crashen lassen – nur loggen
            android.util.Log.w("MainActivity", "Cache leeren teilweise fehlgeschlagen", e)
        }
    }

    // ── Log-Export via SAF ──

    /**
     * Anforderung 4: Schreibt den gesamten Log-Verlauf als Text in die per SAF
     * ausgewaehlte Datei (Uri). Header mit Erstellungszeit, danach alle
     * Log-Einträge chronologisch (aehlteste zuerst).
     */
    private fun exportLogToUri(uri: Uri) {
        lifecycleScope.launch {
            // Die Anzeige ist auf die letzten 300 Einträge begrenzt; der Export
            // liest weiterhin den vollständigen Verlauf direkt aus Room.
            val entries = withContext(Dispatchers.IO) { logDao.getAllForExport() }
            if (entries.isEmpty()) {
                Toast.makeText(this@MainActivity, "Kein Log-Verlauf vorhanden", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN)
            val sb = StringBuilder()
            sb.appendLine("AT Panther – Log-Export")
            sb.appendLine("Erstellt am: ${sdf.format(Date())}")
            sb.appendLine("Anzahl Einträge: ${entries.size}")
            sb.appendLine("────────────────────────────────────────")
            entries.forEach { e ->
                val time = sdf.format(Date(e.timestamp))
                val typeIcon = if (e.type == "BOOKING") "📦" else "📡"
                val remaining = if (e.remainingMb >= 0) "  [${"%.1f".format(e.remainingMb)} MB]" else ""
                sb.appendLine("$time  $typeIcon  ${e.message}$remaining")
            }

            val ok = withContext(Dispatchers.IO) {
                writeTextToUri(uri, sb.toString())
            }
            if (ok) {
                Toast.makeText(
                    this@MainActivity,
                    "Log exportiert (${entries.size} Einträge)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MainActivity, "Export fehlgeschlagen", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeTextToUri(uri: Uri, text: String): Boolean {
        return try {
            contentResolver.openOutputStream(uri, "w")?.use { os: OutputStream ->
                os.write(text.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "writeTextToUri failed", e)
            false
        }
    }

    // ── Batterie-Optimierung / EMUI Whitelist ──

    /**
     * Anforderung 5: Oeffnet direkt den Systemdialog, um die App von der
     * Batterie-Optimierung auszunehmen (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
     * Auf dem Ulefone Power Armor X11 Pro ist dies die wichtigste Einstellung
     * gegen herstellerspezifisches Beenden des Foreground-Service.
     */
    /**
     * Öffnet Hersteller-Einstellungen, sofern bekannt, sonst die App-Detailseite.
     * Auf dem Ulefone ist die App-Detailseite der sichere Android-12-Fallback.
     */
    private fun openAutoStartSettings() {
        val manufacturer = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        if (manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco")
        ) {
            try {
                startActivity(Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                })
                return
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Hersteller-Autostart nicht verfügbar", e)
            }
        }

        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Autostart bitte manuell in den App-Einstellungen aktivieren", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            // Falls die App bereits auf der Whitelist steht -> nur Hinweis
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "App ist bereits auf der Whitelist", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: allgemeine Batterie-Optimierungs-Einstellungen oeffnen
            android.util.Log.w("MainActivity", "Whitelist-Dialog nicht verfügbar", e)
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Bitte manuell unter Einstellungen > Batterie hinzufügen",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Service control ──

    private fun startMonitor() {
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Bitte Rufnummer und Passwort eingeben", Toast.LENGTH_LONG).show()
            return
        }

        val threshold = parseThreshold()
        val interval = parseInterval()
        if (etInterval.text.toString().trim().toIntOrNull() != interval) {
            etInterval.setText(interval.toString())
        }

        // Für einen Boot-Neustart die zuletzt gestarteten Werte sichern.
        saveCredentials()
        lifecycleScope.launch {
            if (!selectRemotePlatform(showMessage = true)) return@launch

            val intent = Intent(this@MainActivity, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_PHONE, phone)
            putExtra(MonitorService.EXTRA_PASSWORD, password)
            putExtra(MonitorService.EXTRA_THRESHOLD_MB, threshold)
                putExtra(MonitorService.EXTRA_INTERVAL_SEC, interval)
            }

            // Kompatibel ab API 24; auf Android 11 wird der Foreground-Service
            // unmittelbar aus der sichtbaren Activity gestartet.
            ContextCompat.startForegroundService(this@MainActivity, intent)
            getEncryptedPrefs().edit()
                .putBoolean("monitor_enabled", true)
                .apply()
            isServiceRunning = true
            btnToggle.text = "Monitor stoppen"
            tvStatus.text = "Starte..."
            tvStatus.setTextColor(getColor(R.color.status_warn))
        }
    }

    private fun stopMonitor() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
        }
        startService(intent)  // Send stop action
        getEncryptedPrefs().edit()
            .putBoolean("monitor_enabled", false)
            .apply()
        isServiceRunning = false
        btnToggle.text = "Monitor starten"
        tvStatus.text = "Gestoppt"
        tvStatus.setTextColor(getColor(R.color.text_secondary))
    }

    // ── Log RecyclerView Adapter ──

    inner class LogAdapter : ListAdapter<LogEntry, LogAdapter.ViewHolder>(LOG_DIFF_CALLBACK) {
        private val sdf = SimpleDateFormat("dd.MM HH:mm:ss", Locale.GERMAN)

        inner class ViewHolder(val view: android.widget.TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_log, parent, false) as android.widget.TextView
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = getItem(position)
            val time = sdf.format(Date(entry.timestamp))
            val typeIcon = if (entry.type == "BOOKING") "📦" else "📡"
            holder.view.text = "$time  $typeIcon  ${entry.message}"
        }

    }
}
