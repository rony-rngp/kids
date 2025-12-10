package com.kidsmonitor.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.kidsmonitor.R
import com.kidsmonitor.audio.AudioStreamer
import com.kidsmonitor.camera.CameraFacing
import com.kidsmonitor.camera.CameraStreamer
import com.kidsmonitor.network.AudioWebSocketServer
import com.kidsmonitor.network.MjpegServer
import com.kidsmonitor.network.CommandListener
import com.kidsmonitor.network.NetworkUtils
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.java_websocket.WebSocket
import com.kidsmonitor.utils.MonitorActions
import android.util.Log


class MonitorService : LifecycleService(), CommandListener {

    private val mjpegServer = MjpegServer(8081)
    private val audioServer = AudioWebSocketServer(8082, this, this) // Pass context and 'this' as CommandListener

    private lateinit var cameraStreamer: CameraStreamer
    private var audioStreamer: AudioStreamer? = null

    private var isMicOn = false

    override fun onCreate() {
        super.onCreate()
        cameraStreamer = CameraStreamer(this, this) { frame ->
            mjpegServer.broadcastFrame(frame)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCommandReceived(client: WebSocket, message: String) {
        Log.d("MonitorService", "Command received: $message")
        try {
            val command = Gson().fromJson(message, Command::class.java)
            Log.d("MonitorService", "Parsed command type: ${command.type}")
            when (command.type) {
                "startMonitoring" -> {
                    Log.d("MonitorService", "Executing startMonitoring command.")
                    if (!hasPermission(Manifest.permission.CAMERA)) {
                        audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Camera permission not granted.\" }")
                        Log.w("MonitorService", "Camera permission not granted for startMonitoring.")
                        return
                    }
                    startMonitoring()
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Monitoring started.\" }")
                }
                "stopMonitoring" -> {
                    Log.d("MonitorService", "Executing stopMonitoring command.")
                    stopMonitoring()
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Monitoring stopped.\" }")
                }
                "switchCamera" -> {
                    Log.d("MonitorService", "Executing switchCamera command.")
                    if (!hasPermission(Manifest.permission.CAMERA)) {
                        audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Camera permission not granted.\" }")
                        Log.w("MonitorService", "Camera permission not granted for switchCamera.")
                        return
                    }
                    val facing = when (command.data?.get("facing")) {
                        "front" -> CameraFacing.FRONT
                        "back" -> CameraFacing.BACK
                        else -> CameraFacing.BACK // Default to back camera
                    }
                    cameraStreamer.switchCamera(facing)
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Camera switched to ${if (facing == CameraFacing.FRONT) "front" else "back"}\" }")
                }
                "audioOn" -> {
                    Log.d("MonitorService", "Executing audioOn command.")
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Record audio permission not granted.\" }")
                        Log.w("MonitorService", "Record audio permission not granted for audioOn.")
                        return
                    }
                    if (!isMicOn) {
                        isMicOn = true
                        audioStreamer = AudioStreamer { chunk ->
                            audioServer.broadcastAudio(chunk)
                        }
                        audioStreamer?.start()
                        updateNotification()
                        audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Audio monitoring started.\" }")
                    }
                }
                "audioOff" -> {
                    Log.d("MonitorService", "Executing audioOff command.")
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Record audio permission not granted.\" }")
                        Log.w("MonitorService", "Record audio permission not granted for audioOff.")
                        return
                    }
                    if (isMicOn) {
                        isMicOn = false
                        audioStreamer?.stop()
                        audioStreamer = null
                        updateNotification()
                        audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Audio monitoring stopped.\" }")
                    }
                }
                "getIp" -> {
                    Log.d("MonitorService", "Executing getIp command.")
                    val ip = NetworkUtils.getLocalIpAddress(this)
                    audioServer.sendMessage(client, "{ \"type\": \"ipAddress\", \"ip\": \"$ip\" }")
                }
                else -> {
                    audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Unknown command: ${command.type}\" }")
                    Log.w("MonitorService", "Unknown command received: ${command.type}")
                }
            }
        } catch (e: JsonSyntaxException) {
            audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Invalid JSON command: ${e.message}\" }")
            Log.e("MonitorService", "Invalid JSON command: ${e.message}", e)
        } catch (e: Exception) {
            audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Error processing command: ${e.message}\" }")
            Log.e("MonitorService", "Error processing command: ${e.message}", e)
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
                if (hasPermission(Manifest.permission.RECORD_AUDIO) && !isMicOn) {
                    isMicOn = true
                    audioStreamer = AudioStreamer { chunk ->
                        audioServer.broadcastAudio(chunk)
                    }
                    audioStreamer?.start()
                    updateNotification()
                }
            }
            MonitorActions.ACTION_AUDIO_OFF -> {
                if (isMicOn) {
                    isMicOn = false
                    audioStreamer?.stop()
                    audioStreamer = null
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (!mjpegServer.isRunning) {
            mjpegServer.start()
            Log.d("MonitorService", "MJPEG Server started.")
        } else {
            Log.d("MonitorService", "MJPEG Server already running.")
        }

        if (!audioServer.isServerRunning) {
            audioServer.start()
            Log.d("MonitorService", "Audio WebSocket Server started.")
        } else {
            Log.d("MonitorService", "Audio WebSocket Server already running.")
        }

        cameraStreamer.startCamera(CameraFacing.BACK)
        Log.d("MonitorService", "Camera streamer started (back camera).")
    }

    private fun stopMonitoring() {
        Log.d("MonitorService", "Stopping monitoring.")
        if (mjpegServer.isRunning) {
            mjpegServer.stop()
            Log.d("MonitorService", "MJPEG Server stopped.")
        } else {
            Log.d("MonitorService", "MJPEG Server not running.")
        }

        if (audioServer.isServerRunning) {
            audioServer.stop()
            Log.d("MonitorService", "Audio WebSocket Server stopped.")
        } else {
            Log.d("MonitorService", "Audio WebSocket Server not running.")
        }

        cameraStreamer.stopCamera()
        audioStreamer?.stop()
        audioStreamer = null
        isMicOn = false
        updateNotification()
        stopForeground(true)
        stopSelf()
        Log.d("MonitorService", "MonitorService stopped.")
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

data class Command(val type: String, val data: Map<String, String>?)