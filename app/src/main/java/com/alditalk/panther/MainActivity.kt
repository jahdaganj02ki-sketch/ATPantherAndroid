package com.alditalk.panther

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alditalk.panther.data.LogDao
import com.alditalk.panther.data.LogEntry
import com.alditalk.panther.service.MonitorService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etPhone: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etThreshold: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var etContractId: TextInputEditText
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var rvLog: RecyclerView

    private var isServiceRunning = false

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
        etContractId = findViewById(R.id.etContractId)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggleService)
        btnSave = findViewById(R.id.btnSaveCredentials)
        rvLog = findViewById(R.id.rvLog)

        rvLog.layoutManager = LinearLayoutManager(this)

        // Load saved credentials
        loadCredentials()

        // Save credentials button
        btnSave.setOnClickListener {
            saveCredentials()
            Toast.makeText(this, "Login-Daten gespeichert", Toast.LENGTH_SHORT).show()
        }

        // Toggle service button
        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopMonitor()
            } else {
                startMonitor()
            }
        }

        // Observe log entries
        val logDao = (application as PantherApp).database.logDao()
        val adapter = LogAdapter()
        rvLog.adapter = adapter
        lifecycleScope.launch {
            logDao.getAll().collectLatest { entries ->
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

    // ── Encrypted SharedPreferences ──

    private fun getEncryptedPrefs() = getSharedPreferences("at_panther_secure", MODE_PRIVATE)

    private fun saveCredentials() {
        val prefs = getEncryptedPrefs()
        prefs.edit()
            .putString("phone", etPhone.text.toString().trim())
            .putString("password", etPassword.text.toString().trim())
            .apply()
    }

    private fun loadCredentials() {
        val prefs = getEncryptedPrefs()
        etPhone.setText(prefs.getString("phone", ""))
        etPassword.setText(prefs.getString("password", ""))
    }

    // ── Service control ──

    private fun startMonitor() {
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Bitte Rufnummer und Passwort eingeben", Toast.LENGTH_LONG).show()
            return
        }

        val threshold = etThreshold.text.toString().trim().toFloatOrNull() ?: 250f
        val interval = etInterval.text.toString().trim().toIntOrNull() ?: 60
        val contractId = etContractId.text.toString().trim()

        val intent = Intent(this, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_PHONE, phone)
            putExtra(MonitorService.EXTRA_PASSWORD, password)
            putExtra(MonitorService.EXTRA_CONTRACT_ID, contractId)
            putExtra(MonitorService.EXTRA_THRESHOLD_MB, threshold)
            putExtra(MonitorService.EXTRA_INTERVAL_SEC, interval)
        }

        startForegroundService(intent)
        isServiceRunning = true
        btnToggle.text = "Monitor stoppen"
        tvStatus.text = "Starte..."
        tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
    }

    private fun stopMonitor() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
        }
        startService(intent)  // Send stop action
        isServiceRunning = false
        btnToggle.text = "Monitor starten"
        tvStatus.text = "Gestoppt"
        tvStatus.setTextColor(getColor(android.R.color.darker_gray))
    }

    // ── Log RecyclerView Adapter ──

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private val entries = mutableListOf<LogEntry>()
        private val sdf = SimpleDateFormat("dd.MM HH:mm:ss", Locale.GERMAN)

        fun submitList(list: List<LogEntry>) {
            entries.clear()
            entries.addAll(list)
            notifyDataSetChanged()
        }

        inner class ViewHolder(val view: android.widget.TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_log, parent, false) as android.widget.TextView
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            val time = sdf.format(Date(entry.timestamp))
            val typeIcon = if (entry.type == "BOOKING") "📦" else "📡"
            holder.view.text = "$time  $typeIcon  ${entry.message}"
        }

        override fun getItemCount(): Int = entries.size
    }
}
