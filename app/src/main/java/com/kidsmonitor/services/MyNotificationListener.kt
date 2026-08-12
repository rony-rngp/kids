package com.kidsmonitor.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""
        // Filter out self notifications
        if (packageName == applicationContext.packageName) return

        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString()
            ?: extras?.getCharSequence("android.conversationTitle")?.toString()
            ?: ""

        var text = extras?.getCharSequence("android.text")?.toString() ?: ""
        if (text.isEmpty() || text == "Sent a message.") {
            val bigText = extras?.getCharSequence("android.bigText")?.toString()
            if (!bigText.isNullOrEmpty()) {
                text = bigText
            } else {
                val textLines = extras?.getCharSequenceArray("android.textLines")
                if (!textLines.isNullOrEmpty()) {
                    text = textLines.joinToString("\n") { it.toString() }
                }
            }
        }

        if (title.isEmpty() && text.isEmpty()) return

        val appLabel = try {
            val pm = applicationContext.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date(sbn.postTime))

        Log.d("NotificationListener", "Notification from $appLabel ($packageName): $title - $text")

        val serviceIntent = Intent(applicationContext, MonitorService::class.java).apply {
            action = "com.kidsmonitor.action.PUSH_LIVE_NOTIFICATION"
            putExtra("packageName", packageName)
            putExtra("appName", appLabel)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("timestamp", timestamp)
        }
        startService(serviceIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
