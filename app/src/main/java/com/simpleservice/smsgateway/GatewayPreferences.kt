package com.simpleservice.smsgateway

import android.content.Context

enum class GatewayMode { MANUAL, AUTOMATIC }

data class GatewayConfig(
    val sourceUrl: String,
    val callbackUrl: String,
    val pollSeconds: Int,
    val mode: GatewayMode
) {
    val valid: Boolean
        get() = isHttpUrl(sourceUrl) && isHttpUrl(callbackUrl) && pollSeconds >= 1

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
}

object GatewayPreferences {
    private const val FILE = "gateway"
    private const val SOURCE_URL = "sourceUrl"
    private const val CALLBACK_URL = "callbackUrl"
    private const val POLL_SECONDS = "pollSeconds"
    private const val MODE = "mode"
    private const val BACKGROUND_ENABLED = "backgroundEnabled"

    fun load(context: Context): GatewayConfig {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mode = runCatching {
            GatewayMode.valueOf(p.getString(MODE, GatewayMode.MANUAL.name) ?: GatewayMode.MANUAL.name)
        }.getOrDefault(GatewayMode.MANUAL)

        return GatewayConfig(
            sourceUrl = p.getString(SOURCE_URL, "") ?: "",
            callbackUrl = p.getString(CALLBACK_URL, "") ?: "",
            pollSeconds = p.getInt(POLL_SECONDS, 30).coerceAtLeast(1),
            mode = mode
        )
    }

    fun save(context: Context, config: GatewayConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(SOURCE_URL, config.sourceUrl.trim())
            .putString(CALLBACK_URL, config.callbackUrl.trim())
            .putInt(POLL_SECONDS, config.pollSeconds.coerceAtLeast(1))
            .putString(MODE, config.mode.name)
            .apply()
    }

    fun setBackgroundEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(BACKGROUND_ENABLED, value)
            .apply()
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(BACKGROUND_ENABLED, false)
}
