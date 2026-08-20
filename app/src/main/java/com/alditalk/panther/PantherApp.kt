package com.alditalk.panther

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alditalk.panther.data.AppDatabase

/**
 * Application-Einstiegspunkt.
 *
 * Da Android 8.0 (API 26, Huawei AGS2-L09) keinen systemweiten Dark Mode kennt,
 * erzwingen wir das dunkle Theme programmatisch beim App-Start über
 * [AppCompatDelegate.setDefaultNightMode] mit [AppCompatDelegate.MODE_NIGHT_YES].
 * Das garantiert ueber Neustarts hinweg ein konsistentes Black-Theme-Verhalten.
 */
class PantherApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    private val securePreferences: SharedPreferences by lazy {
        createSecurePreferences(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Black Theme / Dark Mode programmatisch erzwingen (Android 8.0 kompatibel).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        migrateLegacyPreferences()
        // Die Datenbank bleibt lazy und wird erst beim ersten Zugriff geöffnet.
    }

    fun securePreferences(): SharedPreferences = securePreferences

    private fun migrateLegacyPreferences() {
        val legacy = getSharedPreferences(LEGACY_PREFERENCES_NAME, MODE_PRIVATE)
        if (legacy.all.isEmpty() || securePreferences.contains("phone")) return

        securePreferences.edit().apply {
            legacy.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.commit()
        legacy.edit().clear().apply()
    }

    companion object {
        private const val LEGACY_PREFERENCES_NAME = "at_panther_secure"
        private const val SECURE_PREFERENCES_NAME = "at_panther_secure_v2"

        fun securePreferences(context: Context): SharedPreferences =
            (context.applicationContext as PantherApp).securePreferences()

        private fun createSecurePreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFERENCES_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
