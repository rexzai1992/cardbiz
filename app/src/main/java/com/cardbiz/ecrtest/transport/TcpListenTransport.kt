package com.cardbiz.ecrtest.transport

import com.cardbiz.ecrtest.session.EcrTransport
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * WiFi mode where the terminal connects to this device. The app listens on [port] and accepts the
 * first inbound connection. v1.59 defines no TCP role, so this exists to test whichever direction
 * CardBiz configures on the terminal.
 */
class TcpListenTransport(
    private val port: Int,
    private val acceptTimeoutMs: Int = 120_000,
) : EcrTransport {
    private var server: ServerSocket? = null
    private var socket: Socket? = null

    override val displayName: String
        get() = socket?.let { "WiFi terminal ${it.inetAddress.hostAddress} -> port $port" } ?: "WiFi listening on port $port"

    override val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override fun connect() {
        disconnect()
        val listener = ServerSocket(port).also {
            it.reuseAddress = true
            it.soTimeout = acceptTimeoutMs
        }
        server = listener
        socket = try {
            listener.accept()
        } catch (_: SocketTimeoutException) {
            disconnect()
            throw IOException("No terminal connected to port $port within ${acceptTimeoutMs / 1000}s")
        }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
    }

    override fun write(bytes: ByteArray) {
        val connected = socket ?: throw IOException("No terminal is connected over WiFi")
        connected.getOutputStream().write(bytes)
        connected.getOutputStream().flush()
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val connected = socket ?: throw IOException("No terminal is connected over WiFi")
        connected.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            val buffer = ByteArray(4096)
            val count = connected.getInputStream().read(buffer)
            if (count < 0) throw IOException("WiFi connection closed by terminal")
            buffer.copyOf(count)
        } catch (_: SocketTimeoutException) {
            null
        }
    }
}
