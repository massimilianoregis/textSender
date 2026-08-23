package com.simpleservice.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val token = intent.getStringExtra("token") ?: return
        val partIndex = intent.getIntExtra("partIndex", 0)
        val ok = resultCode == Activity.RESULT_OK
        SmsSendCoordinator.onPartResult(
            token = token,
            partIndex = partIndex,
            ok = ok,
            error = if (ok) null else describeResult(resultCode)
        )
    }

    private fun describeResult(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic SMS failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
        else -> "SmsManager resultCode=$code"
    }

    companion object {
        const val ACTION_SENT = "com.simpleservice.smsgateway.SMS_SENT"
    }
}
