package com.simpleservice.smsgateway

data class SmsItem(
    val id: String,
    val to: String,
    val text: String
)

data class SmsSendResult(
    val ok: Boolean,
    val error: String? = null
)
