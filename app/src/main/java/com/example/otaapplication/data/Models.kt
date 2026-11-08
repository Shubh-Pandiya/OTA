package com.example.otaapplication.data

import com.example.otaapplication.OrderItem
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
    val gstin: String = "",
    val bank: BankDetails = BankDetails()
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

/** Bank account of a buyer or supplier. All fields are optional. */
data class BankDetails(
    val accountName: String = "",
    val bankName: String = "",
    val accountNo: String = "",
    val ifsc: String = "",
    val branch: String = "",
    val upi: String = ""
) {
    val isEmpty: Boolean
        get() = listOf(accountName, bankName, accountNo, ifsc, branch, upi).all { it.isBlank() }

    fun cleaned() = BankDetails(
        accountName = accountName.trim(),
        bankName = bankName.trim(),
        accountNo = accountNo.filterNot { it.isWhitespace() },
        ifsc = ifsc.trim().uppercase(),
        branch = branch.trim(),
        upi = upi.trim()
    )

    /** Multi-line text for copying/sharing, skipping empty fields. */
    fun toShareText(partyName: String): String = listOf(
        "Account name" to accountName.ifBlank { partyName },
        "Bank" to bankName,
        "A/c no" to accountNo,
        "IFSC" to ifsc,
        "Branch" to branch,
        "UPI" to upi
    ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second.trim()}" }

    companion object {
        private val ifscPattern = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
        private val accountNoPattern = Regex("^[0-9A-Z]{6,20}$")
        private val upiPattern = Regex("^[A-Za-z0-9._-]{2,}@[A-Za-z][A-Za-z0-9.-]*$")

        fun isValidIfsc(value: String) = value.isBlank() || ifscPattern.matches(value.trim().uppercase())
        fun isValidAccountNo(value: String) = value.isBlank() || accountNoPattern.matches(value.filterNot { it.isWhitespace() }.uppercase())
        fun isValidUpi(value: String) = value.isBlank() || upiPattern.matches(value.trim())
    }
}

data class Bill(
    val id: String,
    val billNo: String,
    val billDate: LocalDate? = null,
    val amount: Double? = null,
    val orderCount: Int = 0,
    /** LR (bilty) number of the consignment. */
    val lrNo: String = "",
    /** Agreed cash discount, as a percentage and/or in rupees. */
    val cdPercent: Double? = null,
    val cdAmount: Double? = null,
    /** Settled so far by payment advices: paid + cash discount + goods return + rate difference. */
    val settled: Double = 0.0,
    val adviceCount: Int = 0,
    /** What the supplier actually billed (may differ from the orders); empty for header-only bills. */
    val items: List<OrderItem> = emptyList()
) {
    /** Sum of the item lines (qty × rate), or null when no line has both. */
    val linesTotal: Double? get() = PaymentMath.linesTotal(items)

    /** Amount still due; null when the bill amount isn't known. */
    val balance: Double? get() = PaymentMath.balance(amount, settled)
    val status: PaymentStatus get() = PaymentMath.status(amount, settled)
}

/** Editable details of a bill (everything except its number). */
data class BillDetails(
    val billDate: LocalDate? = null,
    val amount: Double? = null,
    val lrNo: String = "",
    val cdPercent: Double? = null,
    val cdAmount: Double? = null,
    /** Item lines as billed; empty = none. */
    val items: List<OrderItem> = emptyList()
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
    val seq: Int,
    /** All printed order fields (see [OrderJson.contentKey]); a change means the order was edited. */
    val content: String = ""
)
