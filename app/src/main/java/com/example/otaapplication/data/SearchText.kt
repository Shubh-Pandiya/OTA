package com.example.otaapplication.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Pure text helpers shared by the repositories (kept Android-free so they are unit-testable). */
object SearchText {
    private val whitespace = Regex("\\s+")
    private val orderDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun normalize(text: String): String = text.lowercase().replace(whitespace, " ").trim()

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    /** LIKE pattern for "contains"; use with `ESCAPE '\'`. */
    fun containsPattern(token: String): String = "%${escapeLike(token)}%"

    fun prefixPattern(token: String): String = "${escapeLike(token)}%"

    private fun escapeLike(token: String): String =
        token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Bill numbers are matched case- and spacing-insensitively. */
    fun billKey(billNo: String): String = billNo.trim().replace(whitespace, " ").uppercase()

    /** "07-11-2026" -> 20261107; 0 when the date can't be parsed (sorts as oldest). */
    fun dayKey(orderDate: String): Int =
        runCatching { dayKey(LocalDate.parse(orderDate.trim(), orderDateFormat)) }.getOrDefault(0)

    fun dayKey(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    fun firstLine(text: String): String = text.lineSequence().firstOrNull().orEmpty().trim()

    /** Splits a typed "Name & Address" field into (name, address). */
    fun splitNameAndAddress(text: String): Pair<String, String> {
        val lines = text.lines()
        val name = lines.firstOrNull().orEmpty().trim()
        val address = lines.drop(1).joinToString("\n") { it.trim() }.trim()
        return name to address
    }
}
