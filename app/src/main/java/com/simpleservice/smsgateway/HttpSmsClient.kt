package com.simpleservice.smsgateway

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

class HttpSmsClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Refresh seven days before expiry. Renewal is authorized by the backend listener.
    fun activeJwt(config: GatewayConfig): String {
        val parts = config.jwt.split(".")
        if (parts.size != 3) throw IOException("Invalid JWT; scan a new configuration QR")
        val claims = try {
            JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        } catch (_: Exception) {
            throw IOException("Invalid JWT; scan a new configuration QR")
        }
        val expiresAt = claims.optLong("exp", 0)
        if (expiresAt <= 0) throw IOException("JWT has no expiry; scan a new configuration QR")
        val now = Instant.now().epochSecond
        if (expiresAt - now > 7L * 24 * 3600) return config.jwt

        val renewUrl = config.sourceUrl.substringBeforeLast("/") + "/renew"
        try {
            val request = Request.Builder()
                .url(renewUrl)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${config.jwt.trim()}")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("JWT renewal HTTP ${response.code}")
                val updated = JSONObject(response.body?.string() ?: "").getString("jwt").trim()
                if (updated.split(".").size != 3) throw IOException("Invalid renewed JWT")
                GatewayPreferences.updateJwt(context, config.jwt, updated)
                return updated
            }
        } catch (error: Exception) {
            // A transient renewal failure must not interrupt a still-valid token.
            if (expiresAt > now) return config.jwt
            throw IOException("JWT expired and renewal failed: ${error.message}", error)
        }
    }

    fun fetch(url: String, jwt: String): List<SmsItem> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${jwt.trim()}")
            .header("X-TextSender-Device-Id", GatewayPreferences.deviceId(context))
            .header("X-TextSender-Device-Model", (Build.MANUFACTURER + " " + Build.MODEL).take(120))
            .header("X-TextSender-App-Version", "2.2")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: "[]"
            val array = JSONArray(body)
            val result = ArrayList<SmsItem>(array.length())
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                val id = value.optString("id").trim()
                val to = value.optString("to").trim()
                val text = value.optString("text")
                if (id.isEmpty() || to.isEmpty() || text.isEmpty()) {
                    throw IOException("Item $i must contain non-empty id, to and text")
                }
                result += SmsItem(id, to, text)
            }
            return result
        }
    }

    fun report(callbackUrl: String, jwt: String, item: SmsItem, result: SmsSendResult) {
        val payload = JSONObject()
            .put("id", item.id)
            .put("status", if (result.ok) "OK" else "KO")
            .put("to", item.to)
            .put("at", Instant.now().toString())
        if (!result.ok && result.error != null) payload.put("error", result.error)

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(callbackUrl)
            .post(body)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${jwt.trim()}")
            .header("X-TextSender-Device-Id", GatewayPreferences.deviceId(context))
            .header("X-TextSender-Device-Model", (Build.MANUFACTURER + " " + Build.MODEL).take(120))
            .header("X-TextSender-App-Version", "2.2")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Callback returned HTTP ${response.code}")
            }
        }
    }
}
