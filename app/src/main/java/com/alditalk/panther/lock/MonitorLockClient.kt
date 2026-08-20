package com.alditalk.panther.lock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

class MonitorLockClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun acquire(phone: String, deviceId: String): Boolean =
        send("acquire", phone, deviceId)

    suspend fun heartbeat(phone: String, deviceId: String): Boolean =
        send("heartbeat", phone, deviceId)

    suspend fun release(phone: String, deviceId: String) {
        try {
            send("release", phone, deviceId)
        } catch (_: Exception) {
            // Die TTL gibt die Sperre auch nach einem Netzwerkfehler frei.
        }
    }

    private suspend fun send(operation: String, phone: String, deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!MonitorLockConfig.isConfigured) return@withContext false
            val body = JSONObject()
                .put("operation", operation)
                .put("phoneHash", hashPhone(phone))
                .put("deviceId", deviceId)
                .put("secret", MonitorLockConfig.SHARED_SECRET)
            val execution = JSONObject()
                .put("async", false)
                .put("body", body.toString())
                .put("method", "POST")
                .put("path", "/")
            val request = Request.Builder()
                .url(MonitorLockConfig.FUNCTION_EXECUTION_URL)
                .header("X-Appwrite-Project", MonitorLockConfig.PROJECT_ID)
                .header("Content-Type", "application/json")
                .post(execution.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val outer = JSONObject(response.body?.string() ?: return@withContext false)
                val responseBody = outer.optString("responseBody", outer.toString())
                JSONObject(responseBody).optBoolean("granted", false)
            }
        }

    private fun hashPhone(phone: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(phone.trim().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
