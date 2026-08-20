package com.alditalk.panther.lock

import android.content.Context
import com.alditalk.panther.PantherApp
import java.util.UUID

object MonitorDeviceIdentity {
    fun get(context: Context): String {
        val prefs = (context.applicationContext as PantherApp).securePreferences()
        val existing = prefs.getString("monitor_device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("monitor_device_id", created).commit()
        return created
    }
}
