package com.ota.desktop

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rupee amounts with Indian digit grouping ("1,25,000.00"), as the phone's ICU en-IN formatter
 * prints them. java.text.DecimalFormat can't do the 3-then-2 grouping, so it's done by hand.
 */
object Money {
    fun format(value: Double, decimals: Int = 2): String {
        val bd = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN)
        val negative = bd.signum() < 0
        val plain = bd.abs().toPlainString()
        val int = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = if (int.length <= 3) int else {
            val head = int.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + int.takeLast(3)
        }
        return (if (negative) "-" else "") + grouped + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    fun rupees(value: Double, decimals: Int = 2) = "₹ ${format(value, decimals)}"
}
