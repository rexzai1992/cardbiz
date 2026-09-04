package com.cardbiz.ecrtest.session

import com.cardbiz.ecrtest.protocol.DecodedEcrMessage
import com.cardbiz.ecrtest.protocol.EcrCodec
import com.cardbiz.ecrtest.protocol.EcrFrameException
import java.io.IOException
import kotlin.math.min

class EcrSession(
    private val transport: EcrTransport,
    private val logger: (String) -> Unit,
) {
    private val decoder = FrameStreamDecoder()

    fun execute(request: ByteArray): List<DecodedEcrMessage> {
        check(transport.isConnected) { "Transport is not connected" }
        decoder.clear()

        var acknowledged = false
        for (attempt in 1..MAX_SENDS) {
            ensureNotInterrupted()
            transport.write(request)
            logger("TX attempt $attempt/$MAX_SENDS: ${EcrCodec.toHex(request)}")

            when (awaitControl(ACK_TIMEOUT_MS)) {
                StreamToken.Ack -> {
                    logger("RX ACK (06)")
                    acknowledged = true
                    break
                }
                StreamToken.Nack -> logger("RX NACK (15); resending immediately")
                null -> logger("No ACK within 2 seconds; resending")
                else -> Unit
            }
        }
        if (!acknowledged) throw IOException("Terminal did not ACK after $MAX_SENDS sends")

        val messages = mutableListOf<DecodedEcrMessage>()
        val deadline = System.nanoTime() + RESPONSE_TIMEOUT_MS * 1_000_000L
        var corruptResponses = 0
        while (true) {
            ensureNotInterrupted()
            val token = awaitToken(deadline) ?: throw IOException("No response within 2 minutes after ACK")
            when (token) {
                is StreamToken.Frame -> {
                    logger("RX frame: ${EcrCodec.toHex(token.bytes)}")
                    val decoded = try {
                        EcrCodec.decode(token.bytes)
                    } catch (error: EcrFrameException) {
                        transport.write(byteArrayOf(EcrCodec.NACK))
                        logger("Invalid response (${error.message}); TX NACK (15)")
                        corruptResponses++
                        if (corruptResponses >= MAX_SENDS) {
                            throw IOException("Response remained corrupt after two requested resends", error)
                        }
                        continue
                    }

                    transport.write(byteArrayOf(EcrCodec.ACK))
                    logger("Valid LRC; TX ACK (06)")
                    messages += decoded
                    logger(decoded.summary())
                    if (!decoded.moreToFollow) return messages
                    logger("More-to-follow is 1; waiting for the next response frame")
                }
                StreamToken.Nack -> logger("Unexpected NACK while waiting for response")
                StreamToken.Ack -> logger("Ignoring duplicate ACK while waiting for response")
                is StreamToken.Noise -> logger("Ignoring noise byte %02X".format(token.byte.toInt() and 0xFF))
            }
        }
    }

    private fun awaitControl(timeoutMs: Int): StreamToken? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val token = awaitToken(deadline) ?: return null
            when (token) {
                StreamToken.Ack, StreamToken.Nack -> return token
                is StreamToken.Noise -> logger("Ignoring pre-ACK noise byte %02X".format(token.byte.toInt() and 0xFF))
                is StreamToken.Frame -> logger("Response frame arrived before ACK; ignored as a protocol violation")
            }
        }
    }

    private fun awaitToken(deadlineNanos: Long): StreamToken? {
        while (true) {
            ensureNotInterrupted()
            decoder.poll()?.let { return it }
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return null
            transport.read(min(remainingMs, READ_SLICE_MS.toLong()).toInt())?.let(decoder::feed)
        }
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Operation cancelled")
    }

    companion object {
        const val ACK_TIMEOUT_MS = 2_000
        const val RESPONSE_TIMEOUT_MS = 120_000
        private const val READ_SLICE_MS = 250
        private const val MAX_SENDS = 3
    }
}
