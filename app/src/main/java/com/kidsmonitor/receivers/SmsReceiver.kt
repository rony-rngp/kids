package com.kidsmonitor.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.kidsmonitor.services.MonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!messages.isNullOrEmpty()) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timestamp = sdf.format(Date())

                for (sms in messages) {
                    val sender = sms.displayOriginatingAddress ?: "Unknown"
                    val body = sms.displayMessageBody ?: ""

                    Log.d("SmsReceiver", "New SMS from $sender: $body")

                    val serviceIntent = Intent(context, MonitorService::class.java).apply {
                        action = "com.kidsmonitor.action.PUSH_LIVE_SMS"
                        putExtra("sender", sender)
                        putExtra("body", body)
                        putExtra("timestamp", timestamp)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }
}
