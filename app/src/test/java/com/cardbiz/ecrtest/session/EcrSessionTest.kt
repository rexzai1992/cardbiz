package com.cardbiz.ecrtest.session

import com.cardbiz.ecrtest.protocol.EcrCodec
import com.cardbiz.ecrtest.protocol.EcrField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcrSessionTest {
    @Test
    fun acknowledgesValidResponse() {
        val response = responseFrame("00")
        val transport = FakeTransport(mutableListOf(byteArrayOf(EcrCodec.ACK), response))

        val result = EcrSession(transport) {}.execute(requestFrame())

        assertEquals("00", result.single().transactionCode)
        assertTrue(transport.writes.last().contentEquals(byteArrayOf(EcrCodec.ACK)))
    }

    @Test
    fun resendsRequestAfterNack() {
        val request = requestFrame()
        val transport = FakeTransport(
            mutableListOf(byteArrayOf(EcrCodec.NACK), byteArrayOf(EcrCodec.ACK), responseFrame("00")),
        )

        EcrSession(transport) {}.execute(request)

        assertTrue(transport.writes[0].contentEquals(request))
        assertTrue(transport.writes[1].contentEquals(request))
        assertTrue(transport.writes.last().contentEquals(byteArrayOf(EcrCodec.ACK)))
    }

    @Test
    fun nacksCorruptResponseThenAcceptsResend() {
        val valid = responseFrame("00")
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        val transport = FakeTransport(
            mutableListOf(byteArrayOf(EcrCodec.ACK), corrupt, valid),
        )

        val result = EcrSession(transport) {}.execute(requestFrame())

        assertEquals(1, result.size)
        assertTrue(transport.writes.any { it.contentEquals(byteArrayOf(EcrCodec.NACK)) })
        assertTrue(transport.writes.last().contentEquals(byteArrayOf(EcrCodec.ACK)))
    }

    private fun requestFrame() = EcrCodec.buildRequest("00", listOf(EcrField("00", "00000000000000000000")))

    private fun responseFrame(code: String): ByteArray {
        val frame = EcrCodec.buildRequest(code, listOf(EcrField("02", "APPROVAL")))
        frame[14] = '1'.code.toByte() // response indicator within Message Data
        frame[frame.lastIndex] = EcrCodec.computeLrc(frame.copyOfRange(0, frame.lastIndex))
        return frame
    }

    private class FakeTransport(private val reads: MutableList<ByteArray>) : EcrTransport {
        val writes = mutableListOf<ByteArray>()
        override val displayName = "fake"
        override val isConnected = true
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override fun read(timeoutMs: Int): ByteArray? = if (reads.isEmpty()) null else reads.removeAt(0)
    }
}
