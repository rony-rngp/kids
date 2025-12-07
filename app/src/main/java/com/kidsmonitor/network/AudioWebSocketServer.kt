package com.kidsmonitor.network

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class AudioWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private val clients = ConcurrentHashMap.newKeySet<WebSocket>()
    val isOpen: Boolean
        get() = clients.isNotEmpty()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let { clients.add(it) }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let { clients.remove(it) }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // Not used for this server
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        // Log errors if needed
    }

    override fun onStart() {
        // Server started
    }

    fun broadcastAudio(chunk: ByteArray) {
        clients.forEach { client ->
            if (client.isOpen) {
                client.send(ByteBuffer.wrap(chunk))
            }
        }
    }
}
