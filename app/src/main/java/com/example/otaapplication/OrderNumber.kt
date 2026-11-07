package com.example.otaapplication

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Auto-generated order (Offer No) numbers, unique per staff member:
 * {staff digit}{yyyyMMdd}{serial}, e.g. 1202611071, 1202611072 … 12026110710 for staff 1.
 * The serial is not padded and restarts at 1 every day.
 */
object OrderNumber {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Staff-and-day part of the number. [staffCode] is the staff member's digit (1-9). */
    fun prefix(staffCode: Int, date: LocalDate): String {
        require(staffCode in 1..9) { "Staff code must be 1-9" }
        return staffCode.toString() + date.format(dateFormat)
    }

    fun format(prefix: String, serial: Int): String = prefix + serial

    /** Returns the serial of [offerNo] if it was generated for [prefix], otherwise null. */
    fun serialOf(offerNo: String, prefix: String): Int? {
        val value = offerNo.trim()
        if (value.length <= prefix.length || !value.startsWith(prefix)) return null
        val serial = value.substring(prefix.length)
        return if (serial.all { it.isDigit() }) serial.toIntOrNull() else null
    }

    /** The staff-and-day prefix of a number in the per-staff format, or null for any other number. */
    fun prefixOf(offerNo: String): String? =
        offerNo.trim().takeIf { PER_STAFF.matches(it) }?.take(PREFIX_LENGTH)

    /**
     * Next serial for [prefix]. Takes the persisted counter (only if it belongs to the same prefix)
     * and the numbers already in use, so deleting an order or losing the counter can never hand
     * out a number that is still taken.
     */
    fun nextSerial(prefix: String, storedPrefix: String?, storedSerial: Int, existingOfferNos: List<String>): Int {
        val stored = if (storedPrefix == prefix) storedSerial else 0
        val used = existingOfferNos.mapNotNull { serialOf(it, prefix) }.maxOrNull() ?: 0
        return maxOf(stored, used) + 1
    }

    private const val PREFIX_LENGTH = 9
    // Same pattern as the cloud's unique index on order_no.
    private val PER_STAFF = Regex("^[1-9]20[0-9]{6}[0-9]+$")
}
