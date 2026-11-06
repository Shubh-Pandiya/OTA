package com.example.otaapplication

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Auto-generated order (Offer No) numbers: yyyyMMdd followed by a per-day serial
 * padded to at least two digits, e.g. 2026110701, 2026110702 … 20261107100.
 * The serial restarts at 01 every day.
 */
object OrderNumber {
    private val prefixFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun prefix(date: LocalDate): String = date.format(prefixFormat)

    fun format(prefix: String, serial: Int): String = prefix + serial.toString().padStart(2, '0')

    /** Returns the serial of [offerNo] if it was generated for [prefix]'s day, otherwise null. */
    fun serialOf(offerNo: String, prefix: String): Int? {
        val value = offerNo.trim()
        if (value.length <= prefix.length || !value.startsWith(prefix)) return null
        val serial = value.substring(prefix.length)
        return if (serial.all { it.isDigit() }) serial.toIntOrNull() else null
    }

    /**
     * Next serial for [prefix]. Takes the persisted counter (only if it belongs to the same day)
     * and the numbers already present in saved orders, so deleting an order or losing the
     * counter can never hand out a number that is still in use.
     */
    fun nextSerial(prefix: String, storedPrefix: String?, storedSerial: Int, existingOfferNos: List<String>): Int {
        val stored = if (storedPrefix == prefix) storedSerial else 0
        val used = existingOfferNos.mapNotNull { serialOf(it, prefix) }.maxOrNull() ?: 0
        return maxOf(stored, used) + 1
    }
}
