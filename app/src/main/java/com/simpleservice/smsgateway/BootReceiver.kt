package com.simpleservice.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = GatewayPreferences.load(context)
        if (!GatewayPreferences.isBackgroundEnabled(context)) return
        if (!config.valid || config.mode != GatewayMode.AUTOMATIC) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GatewayForegroundService::class.java)
            )
        } catch (_: Exception) {
            // Some Android/vendor policies can block boot-started foreground services.
        }
    }
}
