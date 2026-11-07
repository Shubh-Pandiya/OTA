package com.example.otaapplication

import java.time.LocalDate

/**
 * Payment advice numbers: "PA" + the order-number scheme, i.e. PA{staff digit}{yyyyMMdd}{serial},
 * e.g. PA120261108001. Advices have their own daily counter, separate from orders.
 */
object AdviceNumber {
    const val PREFIX = "PA"

    fun prefix(staffCode: Int, date: LocalDate): String = PREFIX + OrderNumber.prefix(staffCode, date)

    fun format(prefix: String, serial: Int): String = OrderNumber.format(prefix, serial)

    fun serialOf(adviceNo: String, prefix: String): Int? = OrderNumber.serialOf(adviceNo, prefix)

    /** The staff-and-day prefix of an advice number, or null for any other text. */
    fun prefixOf(adviceNo: String): String? =
        adviceNo.trim().takeIf { PATTERN.matches(it) }?.take(PREFIX_LENGTH)

    fun nextSerial(prefix: String, storedPrefix: String?, storedSerial: Int, existing: List<String>): Int =
        OrderNumber.nextSerial(prefix, storedPrefix, storedSerial, existing)

    private const val PREFIX_LENGTH = 11
    // Same pattern as the cloud's unique index on advice_no.
    private val PATTERN = Regex("^PA[1-9]20[0-9]{6}[0-9]+$")
}
