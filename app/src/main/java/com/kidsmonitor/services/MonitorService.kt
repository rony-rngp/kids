package com.kidsmonitor.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.kidsmonitor.R
import com.kidsmonitor.audio.AudioStreamer
import com.kidsmonitor.camera.CameraFacing
import com.kidsmonitor.camera.CameraStreamer
import com.kidsmonitor.network.AudioWebSocketServer
import com.kidsmonitor.network.MjpegServer
import com.kidsmonitor.utils.MonitorActions

class MonitorService : LifecycleService() {

    private val mjpegServer = MjpegServer(8081)
    private val audioServer = AudioWebSocketServer(8082)

    private lateinit var cameraStreamer: CameraStreamer
    private var audioStreamer: AudioStreamer? = null

    private var isMicOn = false

    override fun onCreate() {
        super.onCreate()
        cameraStreamer = CameraStreamer(this, this) { frame ->
            mjpegServer.broadcastFrame(frame)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            MonitorActions.ACTION_START_MONITORING -> {
                startMonitoring()
            }
            MonitorActions.ACTION_STOP_MONITORING -> {
                stopMonitoring()
            }
            MonitorActions.ACTION_SWITCH_CAMERA -> {
                val facing = intent.getIntExtra(MonitorActions.EXTRA_FACING, CameraFacing.BACK)
                cameraStreamer.switchCamera(facing)
            }
            MonitorActions.ACTION_AUDIO_ON -> {
                isMicOn = true
                audioStreamer = AudioStreamer { chunk ->
                    audioServer.broadcastAudio(chunk)
                }
                audioStreamer?.start()
                updateNotification()
            }
            MonitorActions.ACTION_AUDIO_OFF -> {
                isMicOn = false
                audioStreamer?.stop()
                audioStreamer = null
                updateNotification()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        mjpegServer.start()
        audioServer.start()
        cameraStreamer.startCamera(CameraFacing.BACK)
    }

    private fun stopMonitoring() {
        cameraStreamer.stopCamera()
        mjpegServer.stop()
        audioServer.stop()
        audioStreamer?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val micStatus = if (isMicOn) "Mic: ON" else "Mic: OFF"
        val notificationText = "Video monitoring is active ($micStatus)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kids Monitor")
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kids Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for the active monitoring service."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        startService(restartServiceIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "KidsMonitorServiceChannel"
    }
}
