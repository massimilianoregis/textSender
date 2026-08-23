package com.simpleservice.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class HttpSmsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetch(url: String, jwt: String): List<SmsItem> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${jwt.trim()}")
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
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Callback returned HTTP ${response.code}")
            }
        }
    }
}
