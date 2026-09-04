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
    fun purchaseRequestMatchesAppendixDWalletMerchant() {
        // Spec 7.6.3: Merchant Index 2 - terminal opens its camera to scan the customer's wallet QR.
        val expected = hex(
            "02 00 92 36 30 30 30 30 30 30 30 30 30 31 30 32 30 30 30 30 1C " +
                "30 30 00 20 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 30 1C " +
                "36 36 00 20 30 30 30 32 30 32 33 30 36 32 30 30 39 33 39 30 32 30 34 39 1C " +
                "34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 4D 31 00 02 30 32 1C 03 D8",
        )
        val actual = EcrCommands.build(
            EcrCommandInput(
                EcrCommandType.PURCHASE,
                "00000000000000000000",
                "00020230620093902049",
                "1.00",
                PaymentRoute.SCAN_CUSTOMER_QR.merchantIndex,
            ),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun purchaseRequestMatchesAppendixDDuitNowMerchant() {
        // Spec 7.6.6: Merchant Index 99 without Pay Account ID - terminal generates a DuitNow QR.
        val expected = hex(
            "02 00 67 36 30 30 30 30 30 30 30 30 30 31 30 32 30 30 30 30 1C " +
                "36 36 00 20 31 32 33 34 35 30 30 30 30 30 31 32 33 34 35 30 30 30 30 30 1C " +
                "34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 4D 31 00 02 39 39 1C 03 18",
        )
        val actual = EcrCommands.build(
            EcrCommandInput(
                EcrCommandType.PURCHASE,
                "",
                "12345000001234500000",
                "1.00",
                PaymentRoute.EWALLET_QR.merchantIndex,
            ),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun decodesWalletCustomDataFromAppendixDResponse() {
        // Spec 7.5.2 wallet response with field 99 custom data (TNG WALLET).
        // The printed sample declares 278 message bytes but actually contains 282, so the length
        // and LRC are recomputed here; the field content is taken verbatim from the PDF.
        val decoded = EcrCodec.decode(
            withValidLengthAndLrc(
                "02 00 00 36 30 30 30 30 30 30 30 30 30 31 30 31 35 30 30 30 1C 36 36 00 20 30 30 30 32 30 " +
                    "32 33 30 36 32 30 30 39 30 39 31 32 39 37 31 1C 30 32 00 40 41 50 50 52 4F 56 45 44 20 20 " +
                    "20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 " +
                    "1C 34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 30 33 00 06 32 33 30 36 32 30 1C 30 " +
                    "34 00 06 30 39 31 30 31 31 1C 39 39 01 50 B9 01 0A 53 42 5F 63 61 72 64 62 69 7A B9 02 0A " +
                    "53 42 43 42 30 30 30 30 30 36 B9 03 19 30 30 30 30 30 30 30 30 30 30 36 32 30 30 39 33 39 " +
                    "31 30 30 30 30 30 30 39 B9 04 10 31 32 33 34 35 36 37 38 39 30 31 32 33 34 35 36 B9 05 10 " +
                    "30 36 32 30 30 39 33 39 31 30 30 30 30 30 30 39 B9 06 08 33 30 36 32 35 30 30 38 B9 08 0C " +
                    "30 30 30 30 30 30 30 30 30 31 30 30 B9 09 0C 30 30 30 30 30 31 36 31 36 33 32 34 B9 10 0A " +
                    "54 4E 47 20 57 41 4C 4C 45 54 B9 11 01 30 1C 03 00",
            ),
        )
        assertEquals("15", decoded.transactionCode)
        assertEquals("APPROVED", decoded.fieldValue("02"))
        val custom = decoded.customData()
        assertEquals("TNG WALLET", custom["B910"])
        assertEquals("30625008", custom["B906"])
        assertEquals("0620093910000009", custom["B905"])
        assertEquals("0", custom["B911"])
        assertEquals("TNG WALLET", decoded.walletProductName())
        assertEquals(true, decoded.isQrTransaction())
    }

    @Test
    fun decodesDuitNowQrPurchaseResponse() {
        // Spec 7.6.6 response: approved DuitNow QR sale carrying N8/N4/N5/N6 tags.
        val decoded = EcrCodec.decode(
            hex(
                "02 02 17 36 30 30 30 30 30 30 30 30 30 31 31 32 30 30 30 30 1C 36 36 00 20 31 32 33 34 35 " +
                    "30 30 30 30 30 31 32 33 34 35 30 30 30 30 30 1C 30 32 00 40 41 50 50 52 4F 56 41 4C 20 20 " +
                    "20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 " +
                    "1C 34 30 00 12 30 30 30 30 30 30 30 30 30 31 30 30 1C 30 33 00 06 32 32 30 39 32 32 1C 30 " +
                    "34 00 06 31 33 32 35 30 32 1C 4E 38 00 15 41 46 46 49 4E 44 4E 51 52 30 30 30 30 30 31 1C " +
                    "4E 34 00 09 4F 75 74 6C 65 74 20 49 44 1C 4E 35 00 25 31 30 30 30 30 30 30 31 32 30 39 30 " +
                    "32 31 35 33 34 35 39 31 30 30 30 30 32 1C 4E 36 00 21 42 69 7A 20 4D 73 67 49 44 20 66 72 " +
                    "6F 6D 20 50 61 79 4E 65 74 1C 03 0F",
            ),
        )
        assertEquals(true, decoded.isApprovedResponseFor("20"))
        assertEquals("12345000001234500000", decoded.fieldValue("66"))
        assertEquals("AFFINDNQR000001", decoded.fieldValue("N8"))
        assertEquals("1000000120902153459100002", decoded.fieldValue("N5"))
        assertEquals("Biz MsgID from PayNet", decoded.fieldValue("N6"))
        assertEquals(true, decoded.isQrTransaction())
        assertEquals(null, decoded.walletProductName())
    }

    @Test
    fun extractsCardTypeAndLastFourDigits() {
        val base = DecodedEcrMessage(
            raw = byteArrayOf(),
            transportHeader = EcrCodec.TRANSPORT_HEADER,
            formatVersion = '1',
            responseIndicator = '1',
            transactionCode = "20",
            responseCode = "00",
            moreToFollow = false,
            fields = listOf(
                EcrField("30", "4057713000888430"),
                EcrField("D4", "VISA CARD           "),
                EcrField("E6", "20"),
            ),
        )
        assertEquals("VISA CARD", base.cardLabel())
        assertEquals("8430", base.cardLastFour())
        assertEquals("Contactless", base.cardEntryMode())

        val masked = base.copy(fields = listOf(EcrField("30", "405771******8430"), EcrField("D2", "VISA")))
        assertEquals("8430", masked.cardLastFour())
        assertEquals("VISA", masked.cardLabel())

        val wallet = base.copy(fields = listOf(EcrField("30", ""), EcrField("D4", "QR"), EcrField("E6", "60")))
        assertEquals(null, wallet.cardLastFour())
        assertEquals("QR", wallet.cardLabel())
        assertEquals("QR", wallet.cardEntryMode())
    }

    @Test
    fun cardResponseIsNotQrTransaction() {
        val card = DecodedEcrMessage(
            raw = byteArrayOf(),
            transportHeader = EcrCodec.TRANSPORT_HEADER,
            formatVersion = '1',
            responseIndicator = '1',
            transactionCode = "20",
            responseCode = "00",
            moreToFollow = false,
            fields = listOf(EcrField("E6", "10"), EcrField("D4", "VISA CARD")),
        )
        assertFalse(card.isQrTransaction())
        assertEquals(emptyMap<String, String>(), card.customData())
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

    @Test
    fun approvalRequiresResponseIndicatorTransactionAndResponseCode() {
        val approved = DecodedEcrMessage(
            raw = byteArrayOf(),
            transportHeader = EcrCodec.TRANSPORT_HEADER,
            formatVersion = '1',
            responseIndicator = '1',
            transactionCode = "20",
            responseCode = "00",
            moreToFollow = false,
            fields = listOf(EcrField("01", "123456")),
        )
        assertEquals("123456", approved.fieldValue("01"))
        assertEquals(true, approved.isApprovedResponseFor("20"))
        assertEquals(false, approved.copy(responseIndicator = '0').isApprovedResponseFor("20"))
        assertEquals(false, approved.copy(transactionCode = "12").isApprovedResponseFor("20"))
        assertEquals(false, approved.copy(responseCode = "NA").isApprovedResponseFor("20"))
    }

    /** Rewrites the BCD length bytes and trailing LRC so only the payload of a sample frame has to be exact. */
    private fun withValidLengthAndLrc(value: String): ByteArray {
        val frame = hex(value)
        val messageLength = frame.size - 5
        frame[1] = ((messageLength / 1000) shl 4 or (messageLength / 100 % 10)).toByte()
        frame[2] = ((messageLength / 10 % 10) shl 4 or (messageLength % 10)).toByte()
        frame[frame.lastIndex] = EcrCodec.computeLrc(frame.copyOfRange(0, frame.lastIndex))
        return frame
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map {
        it.toInt(16).toByte()
    }.toByteArray()
}
