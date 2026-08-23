package com.simpleservice.smsgateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SmsSendCoordinator {
    private data class State(
        val expectedParts: Int,
        val successfulParts: MutableSet<Int> = mutableSetOf(),
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var result: SmsSendResult? = null
    )

    private val states = ConcurrentHashMap<String, State>()

    fun sendAndWait(context: Context, item: SmsItem, timeoutSeconds: Long = 120): SmsSendResult {
        val manager = context.getSystemService(SmsManager::class.java)
            ?: return SmsSendResult(false, "SmsManager unavailable")
        val parts = manager.divideMessage(item.text)
        val token = UUID.randomUUID().toString()
        val state = State(expectedParts = parts.size.coerceAtLeast(1))
        states[token] = state

        try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val intent = Intent(context, SmsResultReceiver::class.java).apply {
                    action = SmsResultReceiver.ACTION_SENT
                    putExtra("token", token)
                    putExtra("partIndex", index)
                }
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    token.hashCode() + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            if (parts.size == 1) {
                manager.sendTextMessage(item.to, null, item.text, sentIntents[0], null)
            } else {
                manager.sendMultipartTextMessage(item.to, null, parts, sentIntents, null)
            }
        } catch (error: Exception) {
            states.remove(token)
            return SmsSendResult(false, error.message ?: error.javaClass.simpleName)
        }

        val completed = try {
            state.latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }

        states.remove(token)
        return if (completed) {
            state.result ?: SmsSendResult(false, "Unknown SMS result")
        } else {
            SmsSendResult(false, "Timed out waiting for SmsManager result")
        }
    }

    @Synchronized
    fun onPartResult(token: String, partIndex: Int, ok: Boolean, error: String?) {
        val state = states[token] ?: return
        if (state.result != null) return

        if (!ok) {
            state.result = SmsSendResult(false, error ?: "SmsManager failure")
            state.latch.countDown()
            return
        }

        state.successfulParts += partIndex
        if (state.successfulParts.size >= state.expectedParts) {
            state.result = SmsSendResult(true)
            state.latch.countDown()
        }
    }
}
