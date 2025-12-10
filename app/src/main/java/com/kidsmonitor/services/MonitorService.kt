package com.kidsmonitor.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val audioServer = AudioWebSocketServer(8082, this, this)

    private lateinit var cameraStreamer: CameraStreamer
    private var audioStreamer: AudioStreamer? = null

    private var isMicOn = false
    private var activeClients = 0
    private var lastPingTime = 0L

    private val autoStopHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        stopCamera()
        stopMicrophone()
        updateNotification("Idle (no viewer)")
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (activeClients > 0 && System.currentTimeMillis() - lastPingTime > 15000) {
                activeClients = 0
                stopCamera()
                stopMicrophone()
                updateNotification("Idle (no viewer)")
            }
            heartbeatHandler.postDelayed(this, 5000)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (!isWifiConnected()) {
                stopCamera()
                stopMicrophone()
                updateNotification("No Wi-Fi")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraStreamer = CameraStreamer(this, this) { frame ->
            mjpegServer.broadcastFrame(frame)
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        startHeartbeatChecker()
    }

    override fun onDestroy() {
        super.onDestroy()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        stopHeartbeatChecker()
        stopForegroundService()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onClientCountChanged(count: Int) {
        activeClients = count
        if (activeClients == 0) {
            scheduleAutoStop()
        } else {
            cancelAutoStop()
        }
    }

    override fun onCommandReceived(client: WebSocket, message: String) {
        lastPingTime = System.currentTimeMillis()
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
                    startCamera()
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Monitoring started.\" }")
                }
                "stopMonitoring" -> {
                    Log.d("MonitorService", "Executing stopMonitoring command.")
                    stopCamera()
                    stopMicrophone()
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
                    startMicrophone()
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Audio monitoring started.\" }")
                }
                "audioOff" -> {
                    Log.d("MonitorService", "Executing audioOff command.")
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        audioServer.sendMessage(client, "{ \"type\": \"error\", \"message\": \"Record audio permission not granted.\" }")
                        Log.w("MonitorService", "Record audio permission not granted for audioOff.")
                        return
                    }
                    stopMicrophone()
                    audioServer.sendMessage(client, "{ \"type\": \"status\", \"message\": \"Audio monitoring stopped.\" }")
                }
                "ping" -> {
                    // Do nothing, lastPingTime is already updated
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
                startForegroundService()
            }
            MonitorActions.ACTION_STOP_MONITORING -> {
                stopForegroundService()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Servers running"))

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
    }

    private fun stopForegroundService() {
        stopCamera()
        stopMicrophone()
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

        stopForeground(true)
        stopSelf()
        Log.d("MonitorService", "MonitorService stopped.")
    }

    private fun startCamera() {
        if (isWifiConnected() && activeClients > 0) {
            cameraStreamer.startCamera(CameraFacing.BACK)
            updateNotification("Video monitoring active")
        }
    }

    private fun stopCamera() {
        cameraStreamer.stopCamera()
        updateNotification("Servers running")
    }

    private fun startMicrophone() {
        if (isWifiConnected() && activeClients > 0 && !isMicOn) {
            isMicOn = true
            audioStreamer = AudioStreamer { chunk ->
                audioServer.broadcastAudio(chunk)
            }
            audioStreamer?.start()
            updateNotification("Video and audio monitoring active")
        }
    }

    private fun stopMicrophone() {
        if (isMicOn) {
            isMicOn = false
            audioStreamer?.stop()
            audioStreamer = null
            updateNotification("Video monitoring active")
        }
    }

    private fun scheduleAutoStop() {
        autoStopHandler.postDelayed(autoStopRunnable, 10000)
    }

    private fun cancelAutoStop() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
    }

    private fun startHeartbeatChecker() {
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun stopHeartbeatChecker() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kids Monitor")
            .setContentText(text)
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

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "KidsMonitorServiceChannel"
    }
}

data class Command(val type: String, val data: Map<String, String>?)