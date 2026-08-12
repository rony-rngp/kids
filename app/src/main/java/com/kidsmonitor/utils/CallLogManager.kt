package com.kidsmonitor.utils

import android.content.Context
import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallLogItem(
    val id: String,
    val name: String,
    val number: String,
    val type: String,
    val date: String,
    val duration: String
)

class CallLogManager(private val context: Context) {

    fun getCallLogs(limit: Int = 100): List<CallLogItem> {
        val callList = mutableListOf<CallLogItem>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                var count = 0

                while (it.moveToNext() && count < limit) {
                    val id = if (idIdx != -1) it.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx != -1) it.getString(nameIdx) ?: "Unknown" else "Unknown"
                    val number = if (numberIdx != -1) it.getString(numberIdx) ?: "" else ""
                    val typeCode = if (typeIdx != -1) it.getInt(typeIdx) else -1
                    val dateMillis = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                    val durationSec = if (durationIdx != -1) it.getLong(durationIdx) else 0L

                    val typeStr = when (typeCode) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Other"
                    }

                    val dateStr = if (dateMillis > 0) sdf.format(Date(dateMillis)) else ""
                    val durationStr = "${durationSec}s"

                    callList.add(CallLogItem(id, name, number, typeStr, dateStr, durationStr))
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return callList
    }
}
