package com.kidsmonitor.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.kidsmonitor.services.MonitorService
import com.kidsmonitor.utils.MonitorActions

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "com.kidsmonitor.action.RESTART_SERVICE"
        ) {
            // Always try to start service in foreground
            val serviceIntent = Intent(context, MonitorService::class.java).apply {
                action = MonitorActions.ACTION_START_MONITORING
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}