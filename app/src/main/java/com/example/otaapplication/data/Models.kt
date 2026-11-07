package com.example.otaapplication.data

import java.time.LocalDate

enum class PartyType(val table: String, val label: String, val pluralLabel: String) {
    BUYER("buyers", "Buyer", "Buyers"),
    SUPPLIER("suppliers", "Supplier", "Suppliers")
}

/** A catalog entry (buyer or supplier). */
data class Party(
    val id: String = "",
    val type: PartyType,
    val name: String,
    val address: String = "",
    val city: String = "",
    val phone: String = "",
    val gstin: String = ""
) {
    /**
     * Text placed in the order form's "Name & Address" field when this entry is picked:
     * the same "name, then address lines" a user would type by hand.
     */
    fun toFieldText(): String {
        val lines = mutableListOf(name.trim())
        if (address.isNotBlank()) lines += address.trim()
        if (city.isNotBlank() && !address.contains(city.trim(), ignoreCase = true)) lines += city.trim()
        return lines.joinToString("\n")
    }
}

data class Bill(
    val id: String,
    val billNo: String,
    val billDate: LocalDate? = null,
    val amount: Double? = null,
    val orderCount: Int = 0
)

enum class BillStatusFilter { ALL, PENDING, RECEIVED }

data class OrderQuery(
    val text: String = "",
    val status: BillStatusFilter = BillStatusFilter.ALL,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val newestFirst: Boolean = true
)

/** One row of the order search index (built from the saved order history). */
data class OrderSummary(
    val id: String,
    val uuid: String?,
    val offerNo: String,
    val date: String,
    val buyer: String,
    val supplier: String,
    val jsonPath: String,
    val pdfFileName: String?,
    val billNos: List<String>
) {
    val billReceived: Boolean get() = billNos.isNotEmpty()
}

data class OrderCounts(val total: Int, val pending: Int)

/** An order as read from the history file, used to (re)build the search index. */
data class HistoryIndexEntry(
    val id: String,
    val uuid: String?,
    val offerNo: String,
    val date: String,
    val buyer: String,
    val supplier: String,
    val particulars: String,
    val jsonPath: String,
    val pdfFileName: String?,
    val seq: Int
)
