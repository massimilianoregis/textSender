package com.simpleservice.smsgateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

class GatewayForegroundService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val http = HttpSmsClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = GatewayPreferences.load(this)
        if (!config.valid || config.mode != GatewayMode.AUTOMATIC) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(config.pollSeconds)
        if (running.compareAndSet(false, true)) {
            worker = Thread { pollingLoop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollingLoop() {
        while (running.get()) {
            val config = GatewayPreferences.load(this)
            if (!config.valid || config.mode != GatewayMode.AUTOMATIC || !GatewayPreferences.isBackgroundEnabled(this)) {
                break
            }

            updateNotification("Checking for SMS…")
            try {
                val items = http.fetch(config.sourceUrl, config.jwt)
                if (items.isEmpty()) {
                    updateNotification("No SMS. Next check in ${config.pollSeconds}s")
                } else {
                    for ((index, item) in items.withIndex()) {
                        if (!running.get()) break
                        updateNotification("Sending ${index + 1}/${items.size} · ${item.id}")
                        val result = SmsSendCoordinator.sendAndWait(this, item)
                        try {
                            http.report(config.callbackUrl, config.jwt, item, result)
                        } catch (_: Exception) {
                            // Stateless by design: callback failures are not persisted or retried locally.
                        }
                    }
                    updateNotification("Batch complete. Next check in ${config.pollSeconds}s")
                }
            } catch (error: Exception) {
                updateNotification("Poll failed: ${error.message ?: error.javaClass.simpleName}")
            }

            try {
                Thread.sleep(config.pollSeconds.coerceAtLeast(1) * 1000L)
            } catch (_: InterruptedException) {
                break
            }
        }
        running.set(false)
        stopSelf()
    }

    private fun startAsForeground(pollSeconds: Int) {
        val notification = buildNotification("Automatic mode · every ${pollSeconds}s")
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS JSON Gateway")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "SMS background polling", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "sms-json-gateway"
        private const val NOTIFICATION_ID = 7301
    }
}
