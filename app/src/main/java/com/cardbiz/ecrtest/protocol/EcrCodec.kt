package com.cardbiz.ecrtest.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class EcrField(val code: String, val data: ByteArray) {
    constructor(code: String, text: String) : this(code, text.toByteArray(StandardCharsets.US_ASCII))

    init {
        require(code.length == 2 && code.all { it.code in 0x20..0x7E }) {
            "Field code must contain two printable ASCII characters"
        }
        require(data.size <= 9999) { "Field data exceeds the four-digit BCD length" }
    }

    fun displayValue(): String = data.toString(StandardCharsets.US_ASCII)
        .replace('\u0000', ' ')
        .trimEnd()
}

data class DecodedEcrMessage(
    val raw: ByteArray,
    val transportHeader: String,
    val formatVersion: Char,
    val responseIndicator: Char,
    val transactionCode: String,
    val responseCode: String,
    val moreToFollow: Boolean,
    val fields: List<EcrField>,
) {
    fun fieldValue(code: String): String? = fields.lastOrNull { it.code == code }?.displayValue()

    fun isApprovedResponseFor(expectedTransactionCode: String): Boolean =
        responseIndicator == '1' && transactionCode == expectedTransactionCode && responseCode == "00"

    fun summary(): String = buildString {
        append("Transaction: ").append(transactionCode)
        append("  Response: ").append(responseCode)
        append("  More: ").append(if (moreToFollow) "yes" else "no")
        fields.forEach { field ->
            append('\n').append(field.code).append(" = ").append(field.displayValue())
        }
    }
}

class EcrFrameException(message: String) : IllegalArgumentException(message)

object EcrCodec {
    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val ACK: Byte = 0x06
    const val NACK: Byte = 0x15
    const val FS: Byte = 0x1C
    const val TRANSPORT_HEADER = "6000000000"

    fun buildRequest(transactionCode: String, fields: List<EcrField>): ByteArray {
        require(transactionCode.length == 2) { "Transaction code must be two characters" }

        val message = ByteArrayOutputStream()
        message.write(TRANSPORT_HEADER.toByteArray(StandardCharsets.US_ASCII))
        message.write("10${transactionCode}000".toByteArray(StandardCharsets.US_ASCII))
        message.write(FS.toInt())
        fields.forEach { field ->
            message.write(field.code.toByteArray(StandardCharsets.US_ASCII))
            message.write(Bcd.encodeLength(field.data.size))
            message.write(field.data)
            message.write(FS.toInt())
        }

        val messageBytes = message.toByteArray()
        val output = ByteArrayOutputStream(messageBytes.size + 5)
        output.write(STX.toInt())
        output.write(Bcd.encodeLength(messageBytes.size))
        output.write(messageBytes)
        output.write(ETX.toInt())
        val withoutLrc = output.toByteArray()
        output.write(computeLrc(withoutLrc).toInt() and 0xFF)
        return output.toByteArray()
    }

    fun decode(frame: ByteArray): DecodedEcrMessage {
        if (frame.size < 23) throw EcrFrameException("Frame is shorter than the required headers")
        if (frame.first() != STX) throw EcrFrameException("Missing STX")

        val messageLength = try {
            Bcd.decodeLength(frame[1], frame[2])
        } catch (error: IllegalArgumentException) {
            throw EcrFrameException(error.message ?: "Invalid frame length")
        }
        if (frame.size != messageLength + 5) {
            throw EcrFrameException("Length says $messageLength message bytes, frame has ${frame.size - 5}")
        }
        if (frame[frame.lastIndex - 1] != ETX) throw EcrFrameException("Missing ETX")
        val expectedLrc = computeLrc(frame.copyOfRange(0, frame.lastIndex))
        if (frame.last() != expectedLrc) {
            throw EcrFrameException(
                "LRC mismatch: expected %02X, received %02X".format(
                    expectedLrc.toInt() and 0xFF,
                    frame.last().toInt() and 0xFF,
                ),
            )
        }

        val data = frame.copyOfRange(3, 3 + messageLength)
        if (data.size < 18) throw EcrFrameException("Message data has incomplete headers")
        val ascii = StandardCharsets.US_ASCII
        val transportHeader = data.copyOfRange(0, 10).toString(ascii)
        if (data[17] != FS) throw EcrFrameException("Presentation header has no field separator")

        val fields = mutableListOf<EcrField>()
        var cursor = 18
        while (cursor < data.size) {
            if (cursor + 5 > data.size) throw EcrFrameException("Truncated field header at offset $cursor")
            val code = data.copyOfRange(cursor, cursor + 2).toString(ascii)
            val fieldLength = try {
                Bcd.decodeLength(data[cursor + 2], data[cursor + 3])
            } catch (error: IllegalArgumentException) {
                throw EcrFrameException("Invalid BCD length for field $code")
            }
            val valueStart = cursor + 4
            val separatorIndex = valueStart + fieldLength
            if (separatorIndex >= data.size) throw EcrFrameException("Field $code exceeds message length")
            if (data[separatorIndex] != FS) throw EcrFrameException("Field $code has no separator")
            fields += EcrField(code, data.copyOfRange(valueStart, separatorIndex))
            cursor = separatorIndex + 1
        }

        return DecodedEcrMessage(
            raw = frame,
            transportHeader = transportHeader,
            formatVersion = data[10].toInt().toChar(),
            responseIndicator = data[11].toInt().toChar(),
            transactionCode = data.copyOfRange(12, 14).toString(ascii),
            responseCode = data.copyOfRange(14, 16).toString(ascii),
            moreToFollow = data[16].toInt().toChar() == '1',
            fields = fields,
        )
    }

    /** XOR of every byte after STX through ETX, matching Appendix D sample vectors. */
    fun computeLrc(frameThroughEtx: ByteArray): Byte {
        require(frameThroughEtx.isNotEmpty() && frameThroughEtx[0] == STX)
        var lrc = 0
        for (index in 1 until frameThroughEtx.size) {
            lrc = lrc xor (frameThroughEtx[index].toInt() and 0xFF)
        }
        return lrc.toByte()
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
