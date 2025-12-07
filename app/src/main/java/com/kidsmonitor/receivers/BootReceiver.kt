package com.kidsmonitor.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kidsmonitor.services.MonitorService
import com.kidsmonitor.utils.MonitorActions

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MonitorService::class.java).apply {
                action = MonitorActions.ACTION_START_MONITORING
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
