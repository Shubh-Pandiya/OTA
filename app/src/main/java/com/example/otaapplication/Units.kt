package com.example.otaapplication

import java.math.BigDecimal

/**
 * Units an order item's quantity is measured in. Stored per item as the one-letter [code]
 * (an empty code means "no unit", as in orders saved before units existed).
 */
enum class QtyUnit(val code: String, val label: String, private val aliases: Set<String>) {
    MTR("M", "Mtr", setOf("m", "mt", "mts", "mtr", "mtrs", "meter", "meters", "metre", "metres")),
    THAN("T", "Than", setOf("than", "thans", "thaan", "thaans", "thn")),
    PCS("P", "Pcs", setOf("pc", "pcs", "piece", "pieces")),
    SET("S", "Set", setOf("set", "sets")),
    BALE("B", "Bale", setOf("bale", "bales"));

    companion object {
        val DEFAULT = MTR

        fun ofCode(code: String): QtyUnit? = entries.firstOrNull { it.code == code }

        fun ofAlias(word: String): QtyUnit? = word.lowercase().let { w -> entries.firstOrNull { w in it.aliases } }
    }
}

object Units {
    private val legacyQty = Regex("""^\s*(\d[\d,]*(?:\.\d+)?)\s*([A-Za-z]+)\.?\s*$""")

    /** Quantity as printed: "20 Mtr", or the typed text unchanged when the item has no unit. */
    fun display(item: OrderItem): String {
        val unit = QtyUnit.ofCode(item.unit) ?: return item.quantity
        return if (item.quantity.isBlank()) "" else "${item.quantity.trim()} ${unit.label}"
    }

    /**
     * For an item saved before units existed, splits a trailing unit typed into the quantity
     * ("100 mtr", "5 than", "20m") into quantity + unit. Anything else (e.g. "5 kg") stays as typed.
     */
    fun splitLegacy(item: OrderItem): OrderItem {
        if (item.unit.isNotEmpty()) return item
        val match = legacyQty.matchEntire(item.quantity) ?: return item
        val unit = QtyUnit.ofAlias(match.groupValues[2]) ?: return item
        return item.copy(quantity = match.groupValues[1], unit = unit.code)
    }

    fun splitLegacy(data: OrderFormData): OrderFormData = data.copy(items = data.items.map(::splitLegacy))

    /** Unit for a new row: the one last used in this order, else Mtr. */
    fun forNewRow(items: List<OrderItem>): String =
        items.lastOrNull { QtyUnit.ofCode(it.unit) != null }?.unit ?: QtyUnit.DEFAULT.code

    /** Quantity totals per unit, e.g. "250 Mtr + 10 Than"; units are never mixed. Null if none. */
    fun totals(items: List<OrderItem>): String? {
        val sums = linkedMapOf<QtyUnit, BigDecimal>()
        for (item in items) {
            val unit = QtyUnit.ofCode(item.unit) ?: continue
            val qty = item.quantity.replace(",", "").trim().toBigDecimalOrNull() ?: continue
            sums[unit] = (sums[unit] ?: BigDecimal.ZERO) + qty
        }
        if (sums.isEmpty()) return null
        return QtyUnit.entries.filter { it in sums }
            .joinToString(" + ") { "${sums.getValue(it).stripTrailingZeros().toPlainString()} ${it.label}" }
    }
}
