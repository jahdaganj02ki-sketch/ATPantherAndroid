package com.alditalk.panther.api

import android.util.Log
import com.alditalk.panther.auth.AuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID

private const val TAG = "AldiTalkApi"

data class DataStatus(
    val remainingMb: Double,
    val offerId: String,
    val subscriptionId: String,
    val resourceId: String,
    val onDemandAmount: String,
    val refillThreshold: String,
)

data class BookingResult(
    val success: Boolean,
    val isUpdated: Boolean,
    val statusCode: Int,
    val message: String,
)

class AldiTalkApi(private val client: OkHttpClient) {

    private fun bffHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "${AuthConfig.PORTAL}/portal/auth/uebersicht/",
        "X-CORRELATION-ID" to "C_${UUID.randomUUID()}",
        "X-TRANSACTION-ID" to "T_${UUID.randomUUID()}",
    )

    /** Fetch offers and extract remaining data volume. */
    suspend fun getRemainingData(contractId: String): DataStatus? = withContext(Dispatchers.IO) {
        try {
            val url = ("${AuthConfig.PORTAL}/scs/bff/scs-209-selfcare-dashboard-bff"
                    + "/selfcare-dashboard/v1/offers?contractId=$contractId").toHttpUrl()

            val request = Request.Builder()
                .url(url)
                .apply { bffHeaders().forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", AuthConfig.UA)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "getOffers failed: ${response.code}")
                return@withContext null
            }

            val json = JSONObject(response.body!!.string())
            val subscribedOffers = json.getJSONArray("subscribedOffers")
            if (subscribedOffers.length() == 0) {
                Log.e(TAG, "Keine subscribedOffers")
                return@withContext null
            }

            val offer = subscribedOffers.getJSONObject(0)
            val pack = offer.getJSONArray("pack")
            var remainingKb = 0L

            for (i in 0 until pack.length()) {
                val p = pack.getJSONObject(i)
                if (p.optString("balanceAttributeReference") == "dataGrantAmount") {
                    remainingKb = p.optLong("allocated", 0) - p.optLong("used", 0)
                }
            }

            val remainingMb = remainingKb / 1024.0
            Log.d(TAG, "Verbleibend: ${"%.1f".format(remainingMb)} MB")

            DataStatus(
                remainingMb = remainingMb,
                offerId = offer.getString("offerId"),
                subscriptionId = offer.getString("subscriptionId"),
                resourceId = offer.getString("resourceId"),
                onDemandAmount = offer.getString("onDemandAmountValueUid"),
                refillThreshold = offer.getString("refillThresholdValueUid"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei getRemainingData", e)
            null
        }
    }

    /** Book 1 GB additional data. */
    suspend fun book1Gb(status: DataStatus): BookingResult = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("offerId", status.offerId)
                put("subscriptionId", status.subscriptionId)
                put("updateOfferResourceID", status.resourceId)
                put("amount", status.onDemandAmount)
                put("refillThresholdValue", status.refillThreshold)
            }

            val url = ("${AuthConfig.PORTAL}/scs/bff/scs-209-selfcare-dashboard-bff"
                    + "/selfcare-dashboard/v1/offer/updateUnlimited").toHttpUrl()

            val request = Request.Builder()
                .url(url)
                .apply { bffHeaders().forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", AuthConfig.UA)
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            val respJson = JSONObject(respBody)
            val isUpdated = respJson.optBoolean("isUpdated", false)

            Log.d(TAG, "Booking: ${response.code}, isUpdated=$isUpdated")
            BookingResult(
                success = response.isSuccessful && isUpdated,
                isUpdated = isUpdated,
                statusCode = response.code,
                message = respBody,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei book1Gb", e)
            BookingResult(false, false, -1, e.message ?: "Unbekannter Fehler")
        }
    }
}
