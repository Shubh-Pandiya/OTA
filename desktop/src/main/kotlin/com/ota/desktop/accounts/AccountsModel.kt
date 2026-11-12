package com.ota.desktop.accounts

import com.ota.desktop.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Account groups. Assets and expenses normally carry a debit balance; the rest a credit balance. */
enum class AccGroup(val label: String) {
    ASSET("Assets"), LIABILITY("Liabilities"), EQUITY("Capital"), INCOME("Income"), EXPENSE("Expenses");

    val debitNature: Boolean get() = this == ASSET || this == EXPENSE
}

enum class VType(val label: String, val prefix: String) {
    JOURNAL("Journal", "JV"), PAYMENT("Payment", "PV"), RECEIPT("Receipt", "RV"), CONTRA("Contra", "CV"),
    COMMISSION("Commission", "CM");

    companion object {
        /** The types a person enters; commission vouchers are posted from bills. */
        val manual = listOf(JOURNAL, PAYMENT, RECEIPT, CONTRA)
    }
}

enum class PartyKind(val label: String) { BUYER("Buyer"), SUPPLIER("Supplier") }

/** Amounts are held in paise so totals and balances are exact. */
object Paise {
    fun of(rupees: Double): Long = BigDecimal.valueOf(rupees).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    fun of(value: BigDecimal?): Long = value?.setScale(2, RoundingMode.HALF_UP)?.movePointRight(2)?.longValueExact() ?: 0L
    fun parse(text: String): Long? = text.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()?.let(::of)
    fun rupees(paise: Long): Double = BigDecimal.valueOf(paise, 2).toDouble()
    fun decimal(paise: Long): BigDecimal = BigDecimal.valueOf(paise, 2)
    fun format(paise: Long): String = Money.format(rupees(paise))

    /** "1,250.00 Dr" for a signed (positive = debit) balance; "0.00" when nil. */
    fun drCr(signed: Long): String = when {
        signed > 0 -> "${format(signed)} Dr"
        signed < 0 -> "${format(-signed)} Cr"
        else -> "0.00"
    }

    /** Typed text for an amount input: blank for zero, plain digits with 2 decimals otherwise. */
    fun input(paise: Long): String = if (paise == 0L) "" else decimal(paise).toPlainString()
}

data class Account(
    val id: String,
    val code: String,
    val name: String,
    val group: AccGroup,
    val subgroup: String = "",
    val cashBank: Boolean = false,
    val party: PartyKind? = null,
    val partyKey: String? = null,
    val commissionPercent: Double = 0.0,
    /** Signed: positive = Dr, negative = Cr. */
    val opening: Long = 0,
    val systemKey: String? = null,
    val active: Boolean = true
) {
    val isParty: Boolean get() = party != null
    val label: String get() = if (code.isBlank()) name else "$code · $name"
    val kindLabel: String get() = party?.label ?: if (cashBank) "Cash & bank" else group.label
}

data class VoucherLine(val accountId: String, val dr: Long, val cr: Long, val note: String = "")

data class Voucher(
    val id: String,
    val no: String,
    val type: VType,
    val date: LocalDate,
    val narration: String,
    val lines: List<VoucherLine>,
    val sourceBillId: String? = null,
    val deleted: Boolean = false
) {
    val total: Long get() = lines.sumOf { it.dr }
    val automatic: Boolean get() = type == VType.COMMISSION
}

/** What the voucher editor saves; [id] null = a new voucher. */
data class VoucherDraft(
    val id: String?,
    val type: VType,
    val date: LocalDate?,
    val narration: String,
    val lines: List<VoucherLine>
)

data class VoucherSummary(
    val id: String,
    val no: String,
    val type: VType,
    val date: LocalDate,
    val narration: String,
    val total: Long,
    val debits: String,
    val credits: String,
    val sourceBillId: String?,
    val deleted: Boolean
)

data class AuditEntry(val id: Long, val at: java.time.OffsetDateTime, val action: String, val voucherId: String?, val voucherNo: String, val summary: String)

/** A buyer or supplier with its ledger account and books balance. */
data class PartyAccount(val account: Account, val balance: Long, val bills: Int, val billed: Long)

data class ReconcileResult(val created: Int = 0, val updated: Int = 0, val removed: Int = 0, val accountsAdded: Int = 0) {
    val changed: Boolean get() = created + updated + removed + accountsAdded > 0
}

class AccountsError(message: String) : IllegalArgumentException(message)

/** Financial year code "2627" for 1 Apr 2026 – 31 Mar 2027. */
fun fyCode(date: LocalDate): String {
    val start = if (date.monthValue >= 4) date.year else date.year - 1
    return "%02d%02d".format(start % 100, (start + 1) % 100)
}

fun fyStart(date: LocalDate): LocalDate = LocalDate.of(if (date.monthValue >= 4) date.year else date.year - 1, 4, 1)
