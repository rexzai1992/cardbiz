package com.cardbiz.ecrtest.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.cardbiz.ecrtest.session.EcrTransport
import java.io.IOException

class UsbSerialTransport(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : EcrTransport {
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var controlInterface: UsbInterface? = null
    private var inputEndpoint: UsbEndpoint? = null
    private var outputEndpoint: UsbEndpoint? = null

    override val displayName: String
        get() = "USB %04X:%04X".format(device.vendorId, device.productId)

    override val isConnected: Boolean
        get() = connection != null

    override fun connect() {
        disconnect()
        if (!manager.hasPermission(device)) throw SecurityException("USB permission has not been granted")

        var selectedData: UsbInterface? = null
        var selectedIn: UsbEndpoint? = null
        var selectedOut: UsbEndpoint? = null
        var selectedControl: UsbInterface? = null
        for (interfaceIndex in 0 until device.interfaceCount) {
            val candidate = device.getInterface(interfaceIndex)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_COMM) selectedControl = candidate
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (endpointIndex in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) bulkOut = endpoint
            }
            if (bulkIn != null && bulkOut != null) {
                selectedData = candidate
                selectedIn = bulkIn
                selectedOut = bulkOut
                break
            }
        }
        val data = selectedData ?: throw IOException("No USB interface with bulk IN and OUT endpoints was found")
        val opened = manager.openDevice(device) ?: throw IOException("Android could not open the USB device")
        try {
            selectedControl?.let {
                if (!opened.claimInterface(it, true)) throw IOException("Could not claim USB control interface")
            }
            if (!opened.claimInterface(data, true)) throw IOException("Could not claim USB data interface")
            configureCdc9600EightN1(opened, selectedControl ?: data)
        } catch (error: Exception) {
            opened.close()
            throw error
        }
        connection = opened
        dataInterface = data
        controlInterface = selectedControl
        inputEndpoint = selectedIn
        outputEndpoint = selectedOut
    }

    private fun configureCdc9600EightN1(opened: UsbDeviceConnection, control: UsbInterface) {
        val lineCoding = byteArrayOf(
            0x80.toByte(), 0x25, 0x00, 0x00, // 9600 little-endian
            0x00, // one stop bit
            0x00, // no parity
            0x08, // eight data bits
        )
        opened.controlTransfer(0x21, 0x20, 0, control.id, lineCoding, lineCoding.size, 1_000)
        opened.controlTransfer(0x21, 0x22, 0x03, control.id, null, 0, 1_000)
    }

    override fun disconnect() {
        val opened = connection
        dataInterface?.let { runCatching { opened?.releaseInterface(it) } }
        controlInterface?.takeIf { it != dataInterface }?.let { runCatching { opened?.releaseInterface(it) } }
        runCatching { opened?.close() }
        connection = null
        dataInterface = null
        controlInterface = null
        inputEndpoint = null
        outputEndpoint = null
    }

    override fun write(bytes: ByteArray) {
        val opened = connection ?: throw IOException("USB device is not connected")
        val endpoint = outputEndpoint ?: throw IOException("USB OUT endpoint is unavailable")
        val sent = opened.bulkTransfer(endpoint, bytes, bytes.size, 5_000)
        if (sent != bytes.size) throw IOException("USB write sent $sent of ${bytes.size} bytes")
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val opened = connection ?: throw IOException("USB device is not connected")
        val endpoint = inputEndpoint ?: throw IOException("USB IN endpoint is unavailable")
        val buffer = ByteArray(minOf(maxOf(endpoint.maxPacketSize, 64) * 16, 16_384))
        val count = opened.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        return if (count > 0) buffer.copyOf(count) else null
    }
}
