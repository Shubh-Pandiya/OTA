package com.example.otaapplication

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Order (Offer No) numbers.
 *
 * Current format, one agency-wide series per financial year (1 April – 31 March):
 * {FY start YY}-{FY end YY}{4-digit serial}, e.g. 26-270001 is the first order of FY 2026-27.
 * The cloud hands the numbers out (rpc ota_allocate_order_no), so they are unique across every
 * phone and the desktop. An order saved offline has a blank number ("Pending number") until the
 * next sync gives it one.
 *
 * Older orders keep their earlier numbers, which are never renumbered. The previous format was
 * unique per staff member:
 * {staff digit}{yyyyMMdd}{serial}, e.g. 120261107001, 120261107002 … for staff 1.
 * The serial is padded to 3 digits (it simply grows past 999) and restarts at 1 every day.
 * Early unpadded numbers (e.g. 1202611071) are still read, so they keep counting towards the serial.
 */
object OrderNumber {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Staff-and-day part of the number. [staffCode] is the staff member's digit (1-9). */
    fun prefix(staffCode: Int, date: LocalDate): String {
        require(staffCode in 1..9) { "Staff code must be 1-9" }
        return staffCode.toString() + date.format(dateFormat)
    }

    fun format(prefix: String, serial: Int): String = prefix + serial.toString().padStart(SERIAL_DIGITS, '0')

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

    /** Blank: the order was saved offline and gets its number from the cloud on the next sync. */
    const val PENDING = ""
    const val PENDING_LABEL = "Pending number"
    /** Highest serial in one financial year. */
    const val FY_MAX_SERIAL = 9999

    fun isPending(offerNo: String): Boolean = offerNo.isBlank()

    /** What to show for [offerNo]: the number, or "Pending number". */
    fun display(offerNo: String): String = offerNo.ifBlank { PENDING_LABEL }

    /** Start year of the financial year [date] falls in (FY 2026-27 starts on 1 April 2026). */
    fun fyStartYear(date: LocalDate): Int = if (date.monthValue >= 4) date.year else date.year - 1

    /** Financial-year part of the number, e.g. "26-27" for any date from 1 Apr 2026 to 31 Mar 2027. */
    fun fyPrefix(date: LocalDate): String {
        val start = fyStartYear(date)
        return String.format(java.util.Locale.ROOT, "%02d-%02d", start % 100, (start + 1) % 100)
    }

    fun formatFy(date: LocalDate, serial: Int): String {
        require(serial in 1..FY_MAX_SERIAL) { "Serial must be 1-$FY_MAX_SERIAL" }
        return fyPrefix(date) + serial.toString().padStart(FY_SERIAL_DIGITS, '0')
    }

    fun isFy(offerNo: String): Boolean = FY.matches(offerNo.trim())

    /** Serial of a financial-year number, or null for any other number. */
    fun fySerialOf(offerNo: String): Int? = offerNo.trim().takeIf { FY.matches(it) }?.takeLast(FY_SERIAL_DIGITS)?.toInt()

    /** Order date as typed in the form (dd-MM-yyyy); null if it can't be read. */
    fun parseOrderDate(text: String): LocalDate? =
        runCatching { LocalDate.parse(text.trim(), orderDateFormat) }.getOrNull()

    private val orderDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private const val FY_SERIAL_DIGITS = 4
    // Same pattern as the cloud's unique index on financial-year numbers.
    private val FY = Regex("^[0-9]{2}-[0-9]{6}$")
    private const val PREFIX_LENGTH = 9
    private const val SERIAL_DIGITS = 3
    // Same pattern as the cloud's unique index on order_no.
    private val PER_STAFF = Regex("^[1-9]20[0-9]{6}[0-9]+$")
}
