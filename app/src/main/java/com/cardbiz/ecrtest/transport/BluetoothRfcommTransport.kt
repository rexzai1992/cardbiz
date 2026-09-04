package com.cardbiz.ecrtest.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.cardbiz.ecrtest.session.EcrTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothRfcommTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val serviceUuid: UUID,
) : EcrTransport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val displayName: String
        get() = "Bluetooth ${device.name ?: device.address} ($serviceUuid)"

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override fun connect() {
        disconnect()
        adapter.cancelDiscovery()
        var firstError: IOException? = null
        try {
            open(device.createRfcommSocketToServiceRecord(serviceUuid))
            return
        } catch (error: IOException) {
            firstError = error
            disconnect()
        }
        try {
            open(device.createInsecureRfcommSocketToServiceRecord(serviceUuid))
        } catch (error: IOException) {
            disconnect()
            error.addSuppressed(firstError ?: return)
            throw error
        }
    }

    private fun open(candidate: BluetoothSocket) {
        socket = candidate
        candidate.connect()
        input = candidate.inputStream
        output = candidate.outputStream
    }

    override fun disconnect() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    override fun write(bytes: ByteArray) {
        val stream = output ?: throw IOException("Bluetooth socket is not connected")
        stream.write(bytes)
        stream.flush()
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val stream = input ?: throw IOException("Bluetooth socket is not connected")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Bluetooth read cancelled")
            val available = stream.available()
            if (available > 0) {
                return ByteArray(minOf(available, 4096)).also { buffer ->
                    val count = stream.read(buffer)
                    if (count < 0) throw IOException("Bluetooth connection closed")
                    if (count != buffer.size) return buffer.copyOf(count)
                }
            }
            Thread.sleep(20)
        }
        return null
    }
}
