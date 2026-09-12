package com.cardbiz.ecrtest.transport

import com.cardbiz.ecrtest.session.EcrTransport
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class TcpTransport(
    private val host: String,
    private val port: Int,
) : EcrTransport {
    private var socket: Socket? = null

    override val displayName: String
        get() = "WiFi TCP $host:$port"

    override val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override fun connect() {
        disconnect()
        socket = Socket().also { it.connect(InetSocketAddress(host, port), 5_000) }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun write(bytes: ByteArray) {
        val connected = socket ?: throw IOException("TCP socket is not connected")
        connected.getOutputStream().write(bytes)
        connected.getOutputStream().flush()
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val connected = socket ?: throw IOException("TCP socket is not connected")
        connected.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            val buffer = ByteArray(4096)
            val count = connected.getInputStream().read(buffer)
            if (count < 0) throw IOException("TCP connection closed")
            buffer.copyOf(count)
        } catch (_: SocketTimeoutException) {
            null
        }
    }
}
