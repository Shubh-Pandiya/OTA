package com.ota.desktop.export

import com.example.otaapplication.QtyUnit
import com.example.otaapplication.Units
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.SearchText
import com.ota.desktop.dash.Analytics
import com.ota.desktop.dash.PaymentLine
import com.ota.desktop.data.BillListItem
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.OrderRow
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore

/** Sheet builders for every list in the app. The same builders serve "export all" and filtered exports. */
class DataExport(
    private val orders: OrderStore,
    private val payments: PaymentStore,
    private val catalog: CatalogStore,
    private val analytics: Analytics
) {
    /** Every table: orders, order items, bills, advices, advice lines, buyers and suppliers. */
    fun everything(): List<SheetTable> {
        val o = orders.list(limit = 10_000_000)
        val lines = analytics.paymentLines()
        return listOf(
            orders(o), items(o), bills(payments.bills()), advices(payments.search(), lines), lines(lines),
            parties(PartyType.BUYER, catalog.list(PartyType.BUYER)), parties(PartyType.SUPPLIER, catalog.list(PartyType.SUPPLIER))
        )
    }

    fun orders(rows: List<OrderRow>) = SheetTable(
        "Orders",
        listOf("Order no", "Date", "Staff", "Buyer", "Supplier", "Items", "Quantity", "Bills", "Delivery ready", "Transport to",
            "Cash payment", "Less / disc", "Side", "Remarks", "Buyer details", "Supplier details", "Not yet synced"),
        rows.map { r ->
            val d = r.data
            val items = d.items.map(Units::splitLegacy)
            listOf(
                r.orderNo, r.day ?: d.date, Analytics.staffOfOrder(r.orderNo), SearchText.firstLine(d.buyer), SearchText.firstLine(d.supplier),
                items.count { it.particulars.isNotBlank() || it.quantity.isNotBlank() }, Units.totals(items).orEmpty(), r.billNos.joinToString(", "),
                d.deliveryReady, d.transportTo, d.cashPayment, d.lessDisc, d.side, d.remarks, d.buyer, d.supplier, r.pending
            )
        }
    )

    fun items(rows: List<OrderRow>) = SheetTable(
        "Order items",
        listOf("Order no", "Date", "Buyer", "Supplier", "S.No", "Particulars", "Quantity", "Unit", "Quantity as printed", "Rate"),
        rows.flatMap { r ->
            r.data.items.filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() || it.rate.isNotBlank() }.map { raw ->
                val item = Units.splitLegacy(raw)
                val unit = QtyUnit.ofCode(item.unit)
                val qty: Any? = if (unit != null) item.quantity.replace(",", "").trim().toBigDecimalOrNull()?.toDouble() ?: item.quantity else item.quantity
                listOf(
                    r.orderNo, r.day ?: r.data.date, SearchText.firstLine(r.data.buyer), SearchText.firstLine(r.data.supplier),
                    item.serialNo, item.particulars, qty, unit?.label.orEmpty(), Units.display(raw), item.rate
                )
            }
        }
    )

    fun bills(rows: List<BillListItem>) = SheetTable(
        "Bills",
        listOf("Bill no", "Bill date", "Buyer", "Supplier", "Bill amount", "LR no", "C.D. %", "C.D. amount", "Settled", "Balance", "Status", "Orders", "Advices",
            "Bill items", "Item lines"),
        rows.map { (b, buyer, supplier) ->
            val items = b.items.filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() || it.rate.isNotBlank() }
            listOf(b.billNo, b.billDate, buyer, supplier, b.amount, b.lrNo, b.cdPercent, b.cdAmount, b.settled, b.balance, b.status.label, b.orderCount, b.adviceCount,
                Units.totals(items).orEmpty(),
                items.joinToString("; ") { listOf(it.particulars, Units.display(it), it.rate.takeIf(String::isNotBlank)?.let { r -> "@ $r" }).filter { s -> !s.isNullOrBlank() }.joinToString(" ") })
        },
        moneyColumns = setOf(4, 7, 8, 9)
    )

    fun advices(rows: List<AdviceSummary>, lines: List<PaymentLine> = analytics.paymentLines()): SheetTable {
        val byAdvice = lines.groupBy { it.adviceId }
        val details = rows.associate { it.id to payments.advice(it.id) }
        return SheetTable(
            "Payment advices",
            listOf("Advice no", "Pay date", "Staff", "Paid by (buyer)", "Paid to (supplier)", "Mode", "Reference", "Paid", "Cash discount",
                "Goods return", "Rate difference", "Claim / defect", "Total adjusted", "Balance", "Dr/Cr", "Bills", "Remarks"),
            rows.map { a ->
                val l = byAdvice[a.id].orEmpty()
                val full = details[a.id]
                listOf(
                    a.adviceNo, a.payDate, Analytics.staffOfAdvice(a.adviceNo), SearchText.firstLine(a.buyer), SearchText.firstLine(a.supplier),
                    a.mode.label, a.reference, a.paid, l.sumOf { it.cd }, l.sumOf { it.goodsReturn }, l.sumOf { it.rateDiff }, l.sumOf { it.claim }, l.sumOf { it.settled },
                    full?.balanceAmount, full?.balanceSide?.label, a.billNos.joinToString(", "), full?.remarks.orEmpty()
                )
            },
            moneyColumns = setOf(7, 8, 9, 10, 11, 12, 13)
        )
    }

    fun lines(rows: List<PaymentLine>) = SheetTable(
        "Advice lines",
        listOf("Advice no", "Pay date", "Paid by (buyer)", "Paid to (supplier)", "Bill no", "Bill date", "Bill amount", "Paid", "C.D. %", "C.D. amount", "Goods return", "Rate difference", "Claim / defect", "Claim reason", "Total"),
        rows.map {
            listOf(it.adviceNo, it.payDate, SearchText.firstLine(it.buyer), SearchText.firstLine(it.supplier), it.billNo, it.billDate, it.billAmount,
                it.paid, it.cdPercent, it.cd, it.goodsReturn, it.rateDiff, it.claim, it.claimNote, it.settled)
        },
        moneyColumns = setOf(6, 7, 9, 10, 11, 12, 14)
    )

    fun parties(type: PartyType, rows: List<Party>) = SheetTable(
        type.pluralLabel,
        listOf("Name", "Address", "City", "Phone", "GSTIN", "Account name", "Bank", "Account no", "IFSC", "Branch", "UPI"),
        rows.map { p ->
            listOf(p.name, p.address, p.city, p.phone, p.gstin, p.bank.accountName, p.bank.bankName, p.bank.accountNo, p.bank.ifsc, p.bank.branch, p.bank.upi)
        }
    )
}
