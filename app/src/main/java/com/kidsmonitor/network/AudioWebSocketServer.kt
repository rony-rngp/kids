package com.kidsmonitor.network

import android.content.Context
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.kidsmonitor.network.NetworkUtils // Ensure NetworkUtils is imported

interface CommandListener {
    fun onCommandReceived(client: WebSocket, message: String)
    fun onClientCountChanged(count: Int)
}

class AudioWebSocketServer(port: Int, private val context: Context, private val commandListener: CommandListener?) : WebSocketServer(InetSocketAddress(port)) {

    private val clients = Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())
    private var _isRunning: Boolean = false // Internal flag to track running state
    val isOpen: Boolean
        get() = clients.isNotEmpty()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            clients.add(it)
            commandListener?.onClientCountChanged(clients.size)
            // Send IP address to newly connected client
            val ip = "192.168.0.101"
            sendMessage(it, "{ \"type\": \"ipAddress\", \"ip\": \"$ip\" }")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let { 
            clients.remove(it)
            commandListener?.onClientCountChanged(clients.size)
        }
        _isRunning = false // Update running state
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
        _isRunning = false // Update running state
    }

    override fun onStart() {
        _isRunning = true // Server has started
        // Also call super.onStart() if needed, but WebSocketServer handles starting its thread
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
        get() = _isRunning // Return the internal flag for running state
}
