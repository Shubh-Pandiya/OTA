package com.example.otaapplication.data

import com.example.otaapplication.OrderItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class PaymentStatus(val label: String) { UNPAID("Unpaid"), PARTLY_PAID("Partly paid"), PAID("Paid") }

enum class PaymentMode(val label: String, val referenceLabel: String) {
    CHEQUE("Cheque", "Cheque number"),
    ONLINE("Online", "Transaction ID"),
    CASH("Cash", "Receipt / note (optional)");

    companion object {
        fun parse(value: String?): PaymentMode = entries.firstOrNull { it.name == value } ?: CASH
    }
}

/** Dr: the buyer still owes the supplier. Cr: the buyer has paid more than the bills due. */
enum class BalanceSide(val label: String) {
    DR("Dr"), CR("Cr");

    companion object {
        fun parse(value: String?): BalanceSide? = entries.firstOrNull { it.name == value }
    }
}

/** What one payment advice settles against one bill. Only [paid] is required. */
data class LineAmounts(
    val paid: Double = 0.0,
    val cdPercent: Double? = null,
    val cdAmount: Double? = null,
    val goodsReturn: Double? = null,
    val rateDiff: Double? = null,
    /** Claim / defect deduction: what the buyer cut for defective goods, with [claimNote] as the reason. */
    val claim: Double? = null,
    val claimNote: String = ""
) {
    /** Paid + cash discount + goods return + rate difference + claim: what comes off the bill's balance. */
    val settled: Double
        get() = PaymentMath.round2(paid + (cdAmount ?: 0.0) + (goodsReturn ?: 0.0) + (rateDiff ?: 0.0) + (claim ?: 0.0))
}

/** A bill on a payment advice, with the bill's own details for display and the PDF. */
data class AdviceLine(
    val id: String,
    val bill: Bill,
    val amounts: LineAmounts
)

data class PaymentAdvice(
    val id: String,
    val adviceNo: String,
    val payDate: LocalDate,
    /** Paid by. */
    val buyer: String,
    /** Paid to. */
    val supplier: String,
    val mode: PaymentMode,
    val reference: String,
    val balanceAmount: Double?,
    val balanceSide: BalanceSide?,
    val remarks: String,
    val lines: List<AdviceLine>,
    /** Ids of the attached cheque / transaction images, in page order. */
    val images: List<String> = emptyList()
) {
    val totals: AdviceTotals get() = AdviceTotals.of(lines.map { it.amounts })
}

data class AdviceTotals(
    val paid: Double,
    val cashDiscount: Double,
    val goodsReturn: Double,
    val rateDiff: Double,
    val claim: Double = 0.0
) {
    val settled: Double get() = PaymentMath.round2(paid + cashDiscount + goodsReturn + rateDiff + claim)

    companion object {
        fun of(lines: List<LineAmounts>) = AdviceTotals(
            paid = PaymentMath.round2(lines.sumOf { it.paid }),
            cashDiscount = PaymentMath.round2(lines.sumOf { it.cdAmount ?: 0.0 }),
            goodsReturn = PaymentMath.round2(lines.sumOf { it.goodsReturn ?: 0.0 }),
            rateDiff = PaymentMath.round2(lines.sumOf { it.rateDiff ?: 0.0 }),
            claim = PaymentMath.round2(lines.sumOf { it.claim ?: 0.0 })
        )
    }
}

/** One row of the advice list. */
data class AdviceSummary(
    val id: String,
    val adviceNo: String,
    val payDate: LocalDate?,
    val buyer: String,
    val supplier: String,
    val mode: PaymentMode,
    val reference: String,
    val paid: Double,
    val billNos: List<String>
)

/** A payment advice as entered in the editor; [id] is null for a new one. */
data class AdviceDraft(
    val id: String? = null,
    val payDate: LocalDate,
    val buyer: String,
    val supplier: String,
    val mode: PaymentMode,
    val reference: String,
    val balanceAmount: Double?,
    val balanceSide: BalanceSide?,
    val remarks: String,
    /** Bill id to amounts, in display order. */
    val lines: List<Pair<String, LineAmounts>>,
    /** Image ids in page order; the files must already be in the image store. */
    val images: List<String> = emptyList()
)

/** A bill that can go on an advice for a buyer/supplier pair. */
data class PairBill(
    val bill: Bill,
    /** Settled by advices other than the one being edited. */
    val settledElsewhere: Double,
    /** The bill's orders are for more than one buyer/supplier pair. */
    val mixedParties: Boolean
) {
    val balanceBefore: Double? get() = PaymentMath.balance(bill.amount, settledElsewhere)
}

/** An advice that pays (part of) a bill, as listed on the bill. */
data class BillPayment(val adviceId: String, val adviceNo: String, val payDate: LocalDate?, val amounts: LineAmounts)

data class BillQuery(val text: String = "", val status: PaymentStatus? = null)

data class AdviceValue(
    val adviceNo: String,
    val payDate: String?,
    val buyer: String,
    val supplier: String,
    val mode: String,
    val reference: String,
    val balanceAmount: Double?,
    val balanceSide: String?,
    val remarks: String
)

data class AdviceLineValue(val adviceId: String, val billId: String, val amounts: LineAmounts)

/** Pure payment arithmetic (Android-free, unit-tested). Amounts are rupees with 2 decimals. */
object PaymentMath {
    /** Smaller differences than this (half a paisa) count as zero. */
    private const val EPSILON = 0.005

    fun round2(value: Double): Double = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    fun balance(amount: Double?, settled: Double): Double? = amount?.let { round2(it - settled) }

    fun status(amount: Double?, settled: Double): PaymentStatus = when {
        settled < EPSILON -> PaymentStatus.UNPAID
        amount != null && amount - settled < EPSILON -> PaymentStatus.PAID
        else -> PaymentStatus.PARTLY_PAID
    }

    /** Rupee value of a cash discount given as a percentage of [base]. */
    fun percentOf(base: Double?, percent: Double?): Double? =
        if (base == null || percent == null) null else round2(base * percent / 100.0)

    /**
     * Suggested Dr/Cr balance after a payment, from the pair's [outstanding] total: positive
     * means the buyer still owes (Dr), negative means paid in excess (Cr). Null when settled.
     */
    fun suggestedBalance(outstanding: Double): Pair<Double, BalanceSide>? {
        val rounded = round2(outstanding)
        return when {
            rounded >= EPSILON -> rounded to BalanceSide.DR
            rounded <= -EPSILON -> -rounded to BalanceSide.CR
            else -> null
        }
    }

    /** Sum of qty × rate over [items] (rows missing either are skipped); null when nothing adds up. */
    fun linesTotal(items: List<OrderItem>): Double? {
        var total = BigDecimal.ZERO
        var any = false
        for (item in items) {
            val qty = parse(item.quantity) ?: continue
            val rate = parse(item.rate) ?: continue
            total += BigDecimal.valueOf(qty).multiply(BigDecimal.valueOf(rate))
            any = true
        }
        return if (any) round2(total.toDouble()) else null
    }

    /** Reads a typed amount ("12,500.50", "₹ 300"); null for blank or invalid text. */
    fun parse(text: String): Double? =
        text.replace(",", "").replace("₹", "").trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Amount text for an input field: no grouping, no trailing ".0". */
    fun plain(value: Double?): String = value?.let {
        BigDecimal.valueOf(round2(it)).stripTrailingZeros().toPlainString()
    }.orEmpty()
}
