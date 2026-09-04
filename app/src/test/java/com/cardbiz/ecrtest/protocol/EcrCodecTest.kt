package com.cardbiz.ecrtest.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EcrCodecTest {
    @Test
    fun verifyIdRequestMatchesAppendixD() {
        val expected = hex(
            "02 00 43 36 30 30 30 30 30 30 30 30 30 31 30 30 30 30 30 30 1C " +
                "30 30 00 20 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 1C 03 57",
        )
        val actual = EcrCodec.buildRequest("00", listOf(EcrField("00", "00000000000000000000")))
        assertArrayEquals(expected, actual)
    }

    @Test
    fun pingRequestMatchesAppendixD() {
        val expected = hex("02 00 18 36 30 30 30 30 30 30 30 30 30 31 30 46 46 30 30 30 1C 03 30")
        val actual = EcrCommands.build(
            EcrCommandInput(EcrCommandType.PING, "", "", "", ""),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun purchaseRequestMatchesAppendixDMerchantZero() {
        val expected = hex(
            "02 00 92 36 30 30 30 30 30 30 30 30 30 31 30 32 30 30 30 30 1C " +
                "30 30 00 20 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 1C " +
                "36 36 00 20 30 30 30 32 30 32 33 30 36 32 30 30 39 30 39 31 32 39 37 31 1C " +
                "34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 4D 31 00 02 30 30 1C 03 DA",
        )
        val actual = EcrCommands.build(
            EcrCommandInput(
                EcrCommandType.PURCHASE,
                "00000000000000000000",
                "00020230620090912971",
                "1.00",
                "00",
            ),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun purchaseRequestMatchesAppendixDCardMerchant() {
        val expected = hex(
            "02 00 92 36 30 30 30 30 30 30 30 30 30 31 30 32 30 30 30 30 1C " +
                "30 30 00 20 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 1C " +
                "36 36 00 20 30 30 30 32 30 32 33 30 36 32 30 30 39 30 39 31 32 39 37 31 1C " +
                "34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 4D 31 00 02 30 31 1C 03 DB",
        )
        val actual = EcrCommands.build(
            EcrCommandInput(
                EcrCommandType.PURCHASE,
                "00000000000000000000",
                "00020230620090912971",
                "1.00",
                "01",
            ),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun decodeVerifyRequest() {
        val raw = EcrCodec.buildRequest("00", listOf(EcrField("00", "00000000000000000000")))
        val decoded = EcrCodec.decode(raw)
        assertEquals("6000000000", decoded.transportHeader)
        assertEquals("00", decoded.transactionCode)
        assertEquals("00", decoded.responseCode)
        assertFalse(decoded.moreToFollow)
        assertEquals("00000000000000000000", decoded.fields.single().displayValue())
    }

    @Test
    fun rejectsBadLrc() {
        val raw = EcrCodec.buildRequest("00", listOf(EcrField("00", "00000000000000000000")))
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        assertThrows(EcrFrameException::class.java) { EcrCodec.decode(raw) }
    }

    @Test
    fun amountUsesImpliedTwoDecimalPlaces() {
        assertEquals("000000000100", EcrCommands.amount("1.00"))
        assertEquals("000000000001", EcrCommands.amount("0.01"))
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map {
        it.toInt(16).toByte()
    }.toByteArray()
}
