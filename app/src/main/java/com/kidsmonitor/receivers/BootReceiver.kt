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
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val sharedPrefs: SharedPreferences = context.getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
            val isMonitoringEnabled = sharedPrefs.getBoolean("monitoring_enabled", false)
            val isAutoStartEnabled = sharedPrefs.getBoolean("auto_start_enabled", false)

            if (isMonitoringEnabled && isAutoStartEnabled) {
                val serviceIntent = Intent(context, MonitorService::class.java).apply {
                    action = MonitorActions.ACTION_START_MONITORING
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}