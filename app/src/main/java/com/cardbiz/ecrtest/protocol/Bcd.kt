package com.cardbiz.ecrtest.protocol

object Bcd {
    fun encodeLength(value: Int): ByteArray {
        require(value in 0..9999) { "BCD length must be between 0 and 9999" }
        return byteArrayOf(
            (((value / 1000) shl 4) or ((value / 100) % 10)).toByte(),
            ((((value / 10) % 10) shl 4) or (value % 10)).toByte(),
        )
    }

    fun decodeLength(high: Byte, low: Byte): Int {
        val digits = intArrayOf(
            (high.toInt() ushr 4) and 0x0F,
            high.toInt() and 0x0F,
            (low.toInt() ushr 4) and 0x0F,
            low.toInt() and 0x0F,
        )
        require(digits.all { it <= 9 }) { "Invalid packed-BCD length" }
        return digits[0] * 1000 + digits[1] * 100 + digits[2] * 10 + digits[3]
    }
}
