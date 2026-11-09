package com.example.otaapplication

import java.time.LocalDate

/**
 * Payment advice numbers.
 *
 * New advices are numbered by the cloud like orders, from their own counter:
 * {FY start YY}{FY end YY}{4-digit serial}, e.g. 26270001. They are stored as the bare digits and
 * shown and printed as "PA-26270001". An advice saved offline has a blank number until it syncs.
 *
 * Older advices keep their per-staff numbers, PA{staff digit}{yyyyMMdd}{serial}, e.g. PA120261108001,
 * shown as stored.
 */
object AdviceNumber {
    const val PREFIX = "PA"
    const val PENDING = ""

    fun isPending(adviceNo: String): Boolean = adviceNo.isBlank()

    /** A financial-year number from the cloud, e.g. 26270001. */
    fun isFy(adviceNo: String): Boolean = OrderNumber.isFy(adviceNo)

    /** How an advice number is shown and printed: "PA-26270001", a legacy number as stored, or "Pending number". */
    fun display(adviceNo: String): String {
        val no = adviceNo.trim()
        return when {
            no.isEmpty() -> OrderNumber.PENDING_LABEL
            isFy(no) -> "$PREFIX-$no"
            else -> no
        }
    }

    /** PDF file name of a numbered advice, e.g. "PA-26270001.pdf". */
    fun fileName(adviceNo: String): String = display(adviceNo).replace(UNSAFE, "_") + ".pdf"

    /** Words the advice can be found by: the stored number and how it is shown. */
    fun searchTerms(adviceNo: String): String = if (isFy(adviceNo)) "${adviceNo.trim()} ${display(adviceNo)}" else adviceNo.trim()

    fun prefix(staffCode: Int, date: LocalDate): String = PREFIX + OrderNumber.prefix(staffCode, date)

    fun format(prefix: String, serial: Int): String = OrderNumber.format(prefix, serial)

    fun serialOf(adviceNo: String, prefix: String): Int? = OrderNumber.serialOf(adviceNo, prefix)

    /** The staff-and-day prefix of an advice number, or null for any other text. */
    fun prefixOf(adviceNo: String): String? =
        adviceNo.trim().takeIf { PATTERN.matches(it) }?.take(PREFIX_LENGTH)

    fun nextSerial(prefix: String, storedPrefix: String?, storedSerial: Int, existing: List<String>): Int =
        OrderNumber.nextSerial(prefix, storedPrefix, storedSerial, existing)

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private const val PREFIX_LENGTH = 11
    // Same pattern as the cloud's unique index on advice_no.
    private val PATTERN = Regex("^PA[1-9]20[0-9]{6}[0-9]+$")
}
