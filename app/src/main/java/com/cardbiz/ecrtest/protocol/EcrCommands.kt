package com.cardbiz.ecrtest.protocol

import java.math.BigDecimal
import java.math.RoundingMode

enum class EcrCommandType(val code: String, val title: String) {
    PING("FF", "Ping Terminal (FF)"),
    VERIFY_ID("00", "Verify ID (00)"),
    PURCHASE("20", "Purchase (20)"),
    LAST_TRANSACTION("12", "Last Transaction Inquiry (12)"),
    BATCH_DETAIL("51", "Get Batch Detail (51)"),
    SETTLEMENT("50", "Settlement - changes batch (50)"),
    CANCEL("XX", "Cancel Transaction (XX)"),
}

data class EcrCommandInput(
    val type: EcrCommandType,
    val payAccountId: String,
    val transactionId: String,
    val amountText: String,
    val merchantIndex: String,
)

object EcrCommands {
    fun build(input: EcrCommandInput): ByteArray {
        val account = input.payAccountId.trim()
        val transaction = input.transactionId.trim()
        val fields = when (input.type) {
            EcrCommandType.PING -> emptyList()
            EcrCommandType.VERIFY_ID -> listOf(field("00", requiredLength(account, 20, "Pay Account ID")))
            EcrCommandType.PURCHASE -> buildList {
                if (account.isNotEmpty()) add(field("00", requiredLength(account, 20, "Pay Account ID")))
                add(field("66", requiredLength(transaction, 20, "Transaction ID")))
                add(field("40", amount(input.amountText)))
                input.merchantIndex.trim().takeIf { it.isNotEmpty() }?.let {
                    require(it.length in 1..2 && it.all(Char::isDigit)) { "Merchant index must be one or two digits" }
                    add(field("M1", it.padStart(2, '0')))
                }
            }
            EcrCommandType.LAST_TRANSACTION,
            EcrCommandType.BATCH_DETAIL,
            EcrCommandType.CANCEL,
            -> listOf(
                field("00", requiredLength(account, 20, "Pay Account ID")),
                field("66", requiredLength(transaction, 20, "Transaction ID")),
            )
            EcrCommandType.SETTLEMENT -> listOf(
                field("00", requiredLength(account, 20, "Pay Account ID")),
                field("66", requiredLength(transaction, 20, "Transaction ID")),
                field("52", "0"),
            )
        }
        return EcrCodec.buildRequest(input.type.code, fields)
    }

    fun amount(text: String): String {
        val decimal = text.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a valid amount")
        require(decimal > BigDecimal.ZERO) { "Amount must be greater than zero" }
        val minor = try {
            decimal.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Amount must have at most two decimal places")
        }
        require(minor <= 999_999_999_999L) { "Amount is too large for the 12-digit field" }
        return minor.toString().padStart(12, '0')
    }

    private fun requiredLength(value: String, length: Int, label: String): String {
        require(value.length == length) { "$label must be exactly $length ASCII characters" }
        require(value.all { it.code in 0x20..0x7E }) { "$label must contain ASCII characters only" }
        return value
    }

    private fun field(code: String, value: String) = EcrField(code, value)
}
