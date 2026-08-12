package com.kidsmonitor.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationListener", "Listener connected - launching MonitorService")
        startMonitorService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMonitorService()
        return START_STICKY
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(applicationContext, MonitorService::class.java).apply {
                action = com.kidsmonitor.utils.MonitorActions.ACTION_START_MONITORING
            }
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        startMonitorService()
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

        // Save persistently to SQLite database
        try {
            val dbHelper = com.kidsmonitor.utils.NotificationDatabaseHelper(applicationContext)
            val item = com.kidsmonitor.utils.NotificationItem(
                packageName = packageName,
                appName = appLabel,
                title = title,
                text = text,
                timestamp = timestamp
            )
            dbHelper.insertNotification(item)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Log.d("NotificationListener", "Notification saved to DB from $appLabel ($packageName): $title - $text")

        val serviceIntent = Intent(applicationContext, MonitorService::class.java).apply {
            action = "com.kidsmonitor.action.PUSH_LIVE_NOTIFICATION"
            putExtra("packageName", packageName)
            putExtra("appName", appLabel)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("timestamp", timestamp)
        }
        androidx.core.content.ContextCompat.startForegroundService(applicationContext, serviceIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
