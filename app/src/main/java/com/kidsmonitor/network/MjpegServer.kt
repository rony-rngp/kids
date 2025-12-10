package com.kidsmonitor.network

import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MjpegServer(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<DataOutputStream, Boolean>())
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    @Volatile var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        serverExecutor.execute {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    clientExecutor.execute {
                        try {
                            val outputStream = DataOutputStream(clientSocket.getOutputStream())
                            outputStream.writeBytes("HTTP/1.1 200 OK\r\n")
                            outputStream.writeBytes("Content-Type: multipart/x-mixed-replace; boundary=--BOUNDARY\r\n")
                            outputStream.writeBytes("\r\n")
                            outputStream.flush()
                            clients.add(outputStream)
                        } catch (e: Exception) {
                           // Could not write to client, just close the socket
                           try { clientSocket.close() } catch (ex: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                // Server socket closed, expected on stop()
            }
        }
    }

    fun broadcastFrame(jpegBytes: ByteArray) {
        if (!isRunning) return
        val iterator = clients.iterator()
        while(iterator.hasNext()){
            val outputStream = iterator.next()
            try {
                outputStream.writeBytes("--BOUNDARY\r\n")
                outputStream.writeBytes("Content-Type: image/jpeg\r\n")
                outputStream.writeBytes("Content-Length: ${jpegBytes.size}\r\n")
                outputStream.writeBytes("\r\n")
                outputStream.write(jpegBytes)
                outputStream.writeBytes("\r\n")
                outputStream.flush()
            } catch (e: Exception) {
                // Client disconnected, remove them from the list
                iterator.remove()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        clients.clear()
    }
}
