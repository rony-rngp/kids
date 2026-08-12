package com.kidsmonitor.utils

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SmsItem(
    val id: String,
    val address: String,
    val body: String,
    val type: String,
    val date: String
)

class SmsHistoryManager(private val context: Context) {

    fun getSmsLogs(limit: Int = 100): List<SmsItem> {
        val smsList = mutableListOf<SmsItem>()
        val uri: Uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE
        )

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                var count = 0

                while (it.moveToNext() && count < limit) {
                    val id = if (idIdx != -1) it.getString(idIdx) ?: "" else ""
                    val address = if (addressIdx != -1) it.getString(addressIdx) ?: "" else ""
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) ?: "" else ""
                    val typeCode = if (typeIdx != -1) it.getInt(typeIdx) else -1
                    val dateMillis = if (dateIdx != -1) it.getLong(dateIdx) else 0L

                    val typeStr = when (typeCode) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "Inbox"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Outbox"
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
                        else -> "Other"
                    }

                    val dateStr = if (dateMillis > 0) sdf.format(Date(dateMillis)) else ""

                    smsList.add(SmsItem(id, address, body, typeStr, dateStr))
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return smsList
    }
}
