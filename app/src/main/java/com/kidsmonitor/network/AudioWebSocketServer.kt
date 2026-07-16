package com.kidsmonitor.network

import android.content.Context
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

interface CommandListener {
    fun onCommandReceived(client: WebSocket, message: String)
    fun onClientCountChanged(count: Int)
}

class AudioWebSocketServer(port: Int, private val context: Context, private val commandListener: CommandListener?) : WebSocketServer(InetSocketAddress(port)) {

    private val clients = Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())
    private var _isRunning: Boolean = false
    val isOpen: Boolean
        get() = clients.isNotEmpty()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            clients.add(it)
            commandListener?.onClientCountChanged(clients.size)
            // Send IP address to newly connected client
            val ip = NetworkUtils.getLocalIpAddress(context)
            sendMessage(it, "{ \"type\": \"ipAddress\", \"ip\": \"$ip\" }")
            commandListener?.onCommandReceived(it, "{ \"type\": \"get_status\" }")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let { 
            clients.remove(it)
            commandListener?.onClientCountChanged(clients.size)
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        conn?.let { client ->
            message?.let { msg ->
                commandListener?.onCommandReceived(client, msg)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        ex?.printStackTrace()
    }

    override fun onStart() {
        _isRunning = true
    }

    // FIX: Override stop() to reset the _isRunning flag. Previously, once the server was
    // started, isServerRunning always returned true even after stop() was called, causing
    // MonitorService to believe the server was still running and skip re-starting it.
    override fun stop() {
        _isRunning = false
        clients.clear()
        super.stop()
    }

    fun broadcastAudio(chunk: ByteArray) {
        clients.forEach { client ->
            if (client.isOpen) {
                client.send(ByteBuffer.wrap(chunk))
            }
        }
    }

    fun broadcastString(message: String) {
        clients.forEach { client ->
            if (client.isOpen) {
                client.send(message)
            }
        }
    }

    fun sendMessage(client: WebSocket, message: String) {
        if (client.isOpen) {
            client.send(message)
        }
    }

    val isServerRunning: Boolean
        get() = _isRunning
}
