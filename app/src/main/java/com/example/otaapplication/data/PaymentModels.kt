package com.example.otaapplication.data

import com.example.otaapplication.OrderItem
import org.json.JSONArray
import org.json.JSONObject
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
    val mixedParties: Boolean,
    /** The bill's own buyer and supplier (name line), as typed on its orders or on the bill. */
    val buyer: String = "",
    val supplier: String = "",
    /** False when the bill's names only roughly match the advice's (e.g. "Arvind Ahmedabad" vs "Arvind"). */
    val exactParties: Boolean = true
) {
    val balanceBefore: Double? get() = PaymentMath.balance(bill.amount, settledElsewhere)

    /** Unknown amount, or something still due before this advice. */
    val due: Boolean get() = balanceBefore.let { it == null || it > 0.004 }
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

    /** GST % for new bills until the cloud setting has been read. */
    const val DEFAULT_GST_PERCENT = 5.0

    /** GST in rupees on [base] at [percent]. */
    fun gstOf(base: Double, percent: Double): Double = round2(base * percent / 100.0)

    /** Bill total including GST: [base] + [gstOf]. */
    fun withGst(base: Double, percent: Double): Double = round2(round2(base) + gstOf(base, percent))

    /**
     * Value before GST (the commission base) of a bill of [amount] that includes [gstPercent]: its
     * item total less the bill's [discounts] when the items are known, else worked back from the
     * amount. Bills without a GST % (entered before GST was recorded) use the amount as it is.
     */
    fun taxableValue(amount: Double?, gstPercent: Double?, linesTotal: Double?, discounts: Double = 0.0): Double? = when {
        gstPercent == null -> amount
        linesTotal != null -> round2(linesTotal - discounts)
        amount == null -> null
        else -> round2(amount * 100.0 / (100.0 + gstPercent))
    }

    /** Sum of a bill's discount lines. */
    fun discountsTotal(discounts: List<BillDiscount>): Double = round2(discounts.sumOf { it.amount })

    /**
     * What a bill's amount follows: items total less [discounts], plus [gstPercent] GST on that
     * (none when null, for bills entered before GST was recorded). Null without an items total.
     */
    fun billTotal(linesTotal: Double?, discounts: Double, gstPercent: Double?): Double? = linesTotal?.let {
        val net = round2(it - discounts)
        if (gstPercent == null) net else withGst(net, gstPercent)
    }

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

enum class DiscountKind(val code: String, val label: String) {
    CASH("cd", "Cash discount"),
    OTHER("o", "Other");

    companion object {
        fun of(code: String?): DiscountKind = if (code == OTHER.code) OTHER else CASH
    }
}

/**
 * A discount on a bill, taken off the items total before GST is added. [percent], when given, is of
 * the items total and [amount] is its rupee value; [remark] says what an [DiscountKind.OTHER]
 * discount was given for.
 */
data class BillDiscount(
    val kind: DiscountKind = DiscountKind.CASH,
    val percent: Double? = null,
    val amount: Double = 0.0,
    val remark: String = ""
) {
    /** "Cash discount 2%", "Other (festival offer)". */
    val label: String
        get() = when (kind) {
            DiscountKind.CASH -> kind.label
            DiscountKind.OTHER -> if (remark.isBlank()) "Other discount" else "Other (${remark.trim()})"
        } + (percent?.let { " ${PaymentMath.plain(it)}%" } ?: "")

    companion object {
        /** Compact JSON stored in bills.discounts, e.g. [{"k":"cd","p":2,"a":200},{"k":"o","a":50,"r":"Festival"}]; null when none. */
        fun toJson(discounts: List<BillDiscount>): JSONArray? = discounts.takeIf { it.isNotEmpty() }?.let { list ->
            JSONArray().apply {
                list.forEach { d ->
                    put(JSONObject().apply {
                        put("k", d.kind.code)
                        d.percent?.let { put("p", it) }
                        put("a", PaymentMath.round2(d.amount))
                        if (d.kind == DiscountKind.OTHER && d.remark.isNotBlank()) put("r", d.remark.trim())
                    })
                }
            }
        }

        fun fromJson(array: JSONArray?): List<BillDiscount> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                BillDiscount(
                    kind = DiscountKind.of(o.optString("k")),
                    percent = if (o.has("p") && !o.isNull("p")) o.optDouble("p").takeIf { it.isFinite() } else null,
                    amount = o.optDouble("a", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    remark = o.optString("r", "")
                )
            }
        }

        fun toText(discounts: List<BillDiscount>): String? = toJson(discounts)?.toString()

        fun fromText(text: String?): List<BillDiscount> =
            if (text.isNullOrBlank()) emptyList() else runCatching { fromJson(JSONArray(text)) }.getOrDefault(emptyList())
    }
}

/** A discount line as typed in a bill editor. A typed % fills the ₹ from the items total. */
data class DiscountDraft(
    val kind: DiscountKind = DiscountKind.CASH,
    val percent: String = "",
    val amount: String = "",
    val remark: String = "",
    val key: Long = nextKey()
) {
    private fun ok(text: String) = text.isBlank() || (PaymentMath.parse(text) ?: -1.0) >= 0
    val valid: Boolean get() = ok(percent) && ok(amount)

    /** Null for an empty line. */
    fun toDiscount(): BillDiscount? {
        val p = PaymentMath.parse(percent)
        val a = PaymentMath.parse(amount)
        if (a == null && p == null) return null
        return BillDiscount(kind, p, PaymentMath.round2(a ?: 0.0), if (kind == DiscountKind.OTHER) remark.trim() else "")
    }

    /** Typing the %: the ₹ follows it on [linesTotal] (left as typed while there is no items total). */
    fun withPercent(text: String, linesTotal: Double?) =
        copy(percent = text, amount = PaymentMath.percentOf(linesTotal, PaymentMath.parse(text))?.let(PaymentMath::plain) ?: amount)

    /** Typing the ₹ clears a % it no longer matches. */
    fun withAmount(text: String) = copy(amount = text, percent = "")

    companion object {
        private var counter = 0L
        @Synchronized private fun nextKey() = ++counter

        fun of(d: BillDiscount) = DiscountDraft(d.kind, PaymentMath.plain(d.percent), PaymentMath.plain(d.amount), d.remark)

        /** Re-prices %-based lines after the items total changed. */
        fun reprice(drafts: List<DiscountDraft>, linesTotal: Double?): List<DiscountDraft> =
            drafts.map { if (PaymentMath.parse(it.percent) != null && linesTotal != null) it.withPercent(it.percent, linesTotal) else it }

        fun total(drafts: List<DiscountDraft>): Double = PaymentMath.discountsTotal(drafts.mapNotNull { it.toDiscount() })
    }
}

/**
 * The agency-wide GST % new bills include (cloud app_settings, read with the storage check and
 * cached on the device). Until it has been read it is [PaymentMath.DEFAULT_GST_PERCENT].
 */
object GstSetting {
    @Volatile
    var percent: Double = PaymentMath.DEFAULT_GST_PERCENT

    /** "5%", "12.5%". */
    fun text(percent: Double): String = PaymentMath.plain(percent) + "%"
}
