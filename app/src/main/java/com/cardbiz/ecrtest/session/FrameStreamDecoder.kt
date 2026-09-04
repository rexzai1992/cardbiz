package com.cardbiz.ecrtest.session

import com.cardbiz.ecrtest.protocol.Bcd
import com.cardbiz.ecrtest.protocol.EcrCodec

sealed interface StreamToken {
    data object Ack : StreamToken
    data object Nack : StreamToken
    data class Frame(val bytes: ByteArray) : StreamToken
    data class Noise(val byte: Byte) : StreamToken
}

class FrameStreamDecoder {
    private val buffer = ArrayDeque<Byte>()

    fun clear() = buffer.clear()

    fun feed(bytes: ByteArray) {
        bytes.forEach(buffer::addLast)
    }

    fun poll(): StreamToken? {
        val first = buffer.firstOrNull() ?: return null
        if (first == EcrCodec.ACK) {
            buffer.removeFirst()
            return StreamToken.Ack
        }
        if (first == EcrCodec.NACK) {
            buffer.removeFirst()
            return StreamToken.Nack
        }
        if (first != EcrCodec.STX) {
            buffer.removeFirst()
            return StreamToken.Noise(first)
        }
        if (buffer.size < 3) return null

        val snapshot = buffer.toList()
        val messageLength = try {
            Bcd.decodeLength(snapshot[1], snapshot[2])
        } catch (_: IllegalArgumentException) {
            buffer.removeFirst()
            return StreamToken.Noise(first)
        }
        val totalLength = messageLength + 5
        if (buffer.size < totalLength) return null

        return StreamToken.Frame(ByteArray(totalLength) { buffer.removeFirst() })
    }
}
