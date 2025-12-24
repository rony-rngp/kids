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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kidsmonitor.R
import com.kidsmonitor.audio.AudioStreamer
import com.kidsmonitor.camera.CameraFacing
import com.kidsmonitor.camera.CameraStreamer
import com.kidsmonitor.utils.MonitorActions
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID

data class JsonCommand(val type: String, val data: Map<String, String>?)
data class Status(val isCameraOn: Boolean, val isAudioOn: Boolean)
data class Command(val type: String, val data: Map<String, String>?)

class MonitorService : LifecycleService() {

    private lateinit var wsClient: WebSocketClient
    private lateinit var deviceId: String
    private val REMOTE_SERVER_URL = "wss://mkl-monitor.onrender.com/"

    private lateinit var cameraStreamer: CameraStreamer
    private var audioStreamer: AudioStreamer? = null
    private var isMicOn = false
    private var progress = 0
    private var lastPingTime = 0L

    private val progressHandler = Handler(Looper.getMainLooper())
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if ((cameraStreamer.isStreaming() || isMicOn) && System.currentTimeMillis() - lastPingTime > 15000) {
                Log.d("MonitorService", "Heartbeat timeout. Stopping monitoring.")
                stopCamera()
                stopMicrophone()
                sendStatus("Monitoring stopped (Timeout)")
            }
            heartbeatHandler.postDelayed(this, 5000)
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            progress += 1
            broadcastStatus(true, progress)
            if (progress < 100) {
                progressHandler.postDelayed(this, 100)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (!isNetworkConnected()) {
                stopCamera()
                stopMicrophone()
                updateNotification("No Internet Connection")
                // Close socket to trigger cleanup/reconnect logic
                if (::wsClient.isInitialized && wsClient.isOpen) {
                    try { wsClient.close() } catch (e: Exception) {}
                }
            }
        }
        
        override fun onAvailable(network: Network) {
             // Reconnect to server when network returns
             Handler(Looper.getMainLooper()).postDelayed({
                 if (!::wsClient.isInitialized || !wsClient.isOpen) {
                     Log.d("MonitorService", "Network available, reconnecting...")
                     // Always re-init to ensure a fresh state, as reusing closed clients is problematic
                     initWebSocket()
                     try { wsClient.connect() } catch (e: Exception) { 
                         Log.e("MonitorService", "Reconnect failed: ${e.message}")
                     }
                 }
             }, 2000) // Small delay to let network settle
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        lastPingTime = System.currentTimeMillis() // Initialize
        heartbeatHandler.post(heartbeatRunnable) // Start heartbeat check

        val sharedPrefs = getSharedPreferences("MKLMonitorPrefs", Context.MODE_PRIVATE)
        val existingId = sharedPrefs.getString("deviceId", null)
        if (existingId == null || existingId.length > 6) {
            deviceId = (100000..999999).random().toString()
            sharedPrefs.edit().putString("deviceId", deviceId).apply()
        } else {
            deviceId = existingId
        }
        Log.d("MonitorService", "Device ID: $deviceId")

        cameraStreamer = CameraStreamer(this, this, { frame ->
            if (::wsClient.isInitialized && wsClient.isOpen) {
                wsClient.send(frame)
            }
        }, { error ->
            sendStatus("Error: $error")
        })

        initWebSocket()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun initWebSocket() {
        wsClient = object : WebSocketClient(URI(REMOTE_SERVER_URL)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("MonitorService", "Connected to relay server.")
                sendRegistration()
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("MonitorService", "Disconnected: $reason")
                updateNotification("Disconnected (Reconnecting...)")
                Handler(Looper.getMainLooper()).postDelayed({
                    // Only reconnect if this instance is still the active one
                    if (::wsClient.isInitialized && wsClient === this && !isOpen) {
                        reconnect()
                    }
                }, 5000)
            }

            override fun onError(ex: Exception?) {
                Log.e("MonitorService", "WS Error: ${ex?.message}")
            }

            override fun onMessage(message: String?) {
                message?.let {
                    // sendStatus("Debug: Raw Message Received") // Too noisy
                    try {
                        val jsonElement = JsonParser.parseString(message)
                        if (jsonElement.isJsonObject) {
                            val jsonObject = jsonElement.asJsonObject
                            val type = jsonObject.get("type").asString
                            
                            if (type == "command") {
                                sendStatus("Debug: Processing Command Wrapper")
                                val dataObj = jsonObject.getAsJsonObject("data")
                                val innerType = dataObj.get("type").asString
                                
                                // Safely get inner data string
                                val innerDataElement = dataObj.get("data")
                                val innerDataStr = if (innerDataElement != null && !innerDataElement.isJsonNull) {
                                    innerDataElement.asString
                                } else { "{}" }
                                
                                val innerData: Map<String, String> = try {
                                    val typeToken = object : TypeToken<Map<String, String>>() {}.type
                                    Gson().fromJson(innerDataStr, typeToken)
                                } catch (e: Exception) { emptyMap() }
                                
                                handleRemoteCommand(Command(innerType, innerData))
                            } else {
                                // Direct command (if any)
                                val command = Gson().fromJson(message, Command::class.java)
                                handleRemoteCommand(command)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MonitorService", "JSON Error: ${e.message}")
                        sendStatus("Debug: JSON Error - ${e.message}")
                    }
                }
            }
            
            override fun onMessage(bytes: ByteBuffer?) {}
        }
    }

    private fun handleRemoteCommand(command: Command) {
        lastPingTime = System.currentTimeMillis() // Update heartbeat on ANY command
        
        when (command.type) {
            "ping" -> {
                // Heartbeat received, time already updated above
            }
            "startMonitoring" -> {
                Log.d("MonitorService", "Command: Start Monitoring")
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    sendStatus("Error: Camera Permission Missing")
                    return
                }
                startCamera()
                sendStatus("Monitoring started")
            }
            "stopMonitoring" -> {
                stopCamera()
                stopMicrophone()
                sendStatus("Monitoring stopped")
            }
            "switchCamera" -> {
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    sendStatus("Error: Camera Permission Missing")
                    return
                }
                val facing = if (command.data?.get("facing") == "front") CameraFacing.FRONT else CameraFacing.BACK
                cameraStreamer.switchCamera(facing)
                sendStatus("Camera switched")
            }
            "audioOn" -> {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    sendStatus("Error: Audio Permission Missing")
                    return
                }
                startMicrophone()
                sendStatus("Audio started")
            }
            "audioOff" -> {
                stopMicrophone()
                sendStatus("Audio stopped")
            }
            "get_status" -> {
                val status = Status(cameraStreamer.isStreaming(), isMicOn)
                val statusMsg = JsonCommand("status_update", mapOf(
                    "isCameraOn" to status.isCameraOn.toString(),
                    "isAudioOn" to status.isAudioOn.toString()
                ))
                if (::wsClient.isInitialized && wsClient.isOpen) wsClient.send(Gson().toJson(statusMsg))
            }
        }
    }
    
    private fun sendStatus(msg: String) {
        if (::wsClient.isInitialized && wsClient.isOpen) {
            wsClient.send(Gson().toJson(JsonCommand("status", mapOf("message" to msg))))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            MonitorActions.ACTION_START_MONITORING -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                if (!::wsClient.isInitialized || !wsClient.isOpen) {
                    initWebSocket()
                    wsClient.connect()
                }
                progressHandler.post(progressRunnable)
            }
            MonitorActions.ACTION_STOP_MONITORING -> {
                stopForegroundService()
            }
            MonitorActions.ACTION_UPDATE_CONFIG -> {
                if (::wsClient.isInitialized && wsClient.isOpen) {
                    sendRegistration()
                }
            }
        }
        return START_STICKY
    }

    private fun sendRegistration() {
        val sharedPrefs = getSharedPreferences("MKLMonitorPrefs", Context.MODE_PRIVATE)
        val deviceName = sharedPrefs.getString("deviceName", "My Phone") ?: "My Phone"
        
        val registerMap = mapOf("type" to "register_camera", "deviceId" to deviceId, "deviceName" to deviceName)
        wsClient.send(Gson().toJson(registerMap))
        updateNotification("Online. ID: $deviceId ($deviceName)")
    }

    private fun stopForegroundService() {
        progressHandler.removeCallbacks(progressRunnable)
        stopCamera()
        stopMicrophone()
        if (::wsClient.isInitialized) wsClient.close()
        stopForeground(true)
        stopSelf()
        broadcastStatus(false)
    }

    private fun startCamera() {
        if (::wsClient.isInitialized && wsClient.isOpen) {
            cameraStreamer.startCamera(CameraFacing.BACK)
            updateNotification("Monitoring Active. ID: $deviceId")
        }
    }

    private fun stopCamera() {
        cameraStreamer.stopCamera()
        updateNotification("Online. ID: $deviceId")
    }

    private fun startMicrophone() {
        if (::wsClient.isInitialized && wsClient.isOpen && !isMicOn) {
            isMicOn = true
            audioStreamer = AudioStreamer { chunk ->
                if (::wsClient.isInitialized && wsClient.isOpen) wsClient.send(chunk)
            }
            audioStreamer?.start()
        }
    }

    private fun stopMicrophone() {
        isMicOn = false
        audioStreamer?.stop()
        audioStreamer = null
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MKL Monitor")
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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        restartServiceIntent.action = MonitorActions.ACTION_START_MONITORING
        startService(restartServiceIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun broadcastStatus(isRunning: Boolean, progress: Int = 0) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_PROGRESS, progress)
        }
        sendBroadcast(intent)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(network) ?: return false
            return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        return false
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "KidsMonitorServiceChannel"
        const val ACTION_STATUS_UPDATE = "com.kidsmonitor.services.STATUS_UPDATE"
        const val EXTRA_IS_RUNNING = "com.kidsmonitor.services.IS_RUNNING"
        const val EXTRA_PROGRESS = "com.kidsmonitor.services.PROGRESS"
    }
}
