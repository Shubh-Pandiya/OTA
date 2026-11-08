package com.ota.desktop.dash

import com.example.otaapplication.OrderNumber
import com.example.otaapplication.QtyUnit
import com.example.otaapplication.Units
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.data.BillFilter
import com.ota.desktop.data.BillListItem
import com.ota.desktop.data.OrderFilter
import com.ota.desktop.data.OrderRow
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.date
import com.ota.desktop.db.dbl
import com.ota.desktop.db.id
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.export.SheetTable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class DashFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val buyer: String = "",
    val supplier: String = "",
    val staff: Int? = null
) {
    fun contains(day: LocalDate?): Boolean =
        (from == null || (day != null && !day.isBefore(from))) && (to == null || (day != null && !day.isAfter(to)))
}

data class Point(val label: String, val value: Double)
data class PartyStat(val name: String, val orders: Int, val billed: Double, val outstanding: Double)
data class Bucket(val label: String, val count: Int, val amount: Double)
data class StaffStat(val code: Int, val orders: Int, val advices: Int, val paid: Double)
data class StatusStat(val status: PaymentStatus, val count: Int, val billed: Double, val balance: Double)

/** One advice line with its advice and bill: the unit of payment analytics and the lines export. */
data class PaymentLine(
    val adviceId: String, val adviceNo: String, val payDate: LocalDate?, val buyer: String, val supplier: String,
    val mode: String, val reference: String, val billNo: String, val billDate: LocalDate?, val billAmount: Double?,
    val paid: Double, val cdPercent: Double?, val cd: Double, val goodsReturn: Double, val rateDiff: Double,
    val claim: Double = 0.0, val claimNote: String = ""
) {
    val settled: Double get() = PaymentMath.round2(paid + cd + goodsReturn + rateDiff + claim)
}

data class Dashboard(
    val filter: DashFilter,
    val orderCount: Int,
    val itemCount: Int,
    val billCount: Int,
    val billed: Double,
    val outstanding: Double,
    val adviceCount: Int,
    val paid: Double,
    val cashDiscount: Double,
    val goodsReturn: Double,
    val rateDiff: Double,
    val claim: Double,
    val pendingBills: Int,
    /** "Day", "Week" or "Month": the bucket size of the time series. */
    val period: String,
    val ordersOverTime: List<Point>,
    val paymentsOverTime: List<Point>,
    val qtyByUnit: List<Point>,
    val topBuyers: List<PartyStat>,
    val topSuppliers: List<PartyStat>,
    val ageing: List<Bucket>,
    val status: List<StatusStat>,
    val staff: List<StaffStat>
) {
    val adjustments: List<Point> get() = listOf(Point("Cash discount", cashDiscount), Point("Goods return", goodsReturn), Point("Rate difference", rateDiff), Point("Claim / defect", claim))

    /** Every chart's numbers as sheets, for "Export dashboard". */
    fun tables(): List<SheetTable> = listOf(
        SheetTable(
            "Summary", listOf("Metric", "Value"),
            listOf(
                listOf("Filter", filterLabel(filter)),
                listOf("Orders", orderCount), listOf("Order items", itemCount), listOf("Orders without bill", pendingBills),
                listOf("Bills", billCount), listOf("Billed amount", billed), listOf("Outstanding", outstanding),
                listOf("Payment advices", adviceCount), listOf("Paid", paid),
                listOf("Cash discount", cashDiscount), listOf("Goods return", goodsReturn), listOf("Rate difference", rateDiff), listOf("Claim / defect", claim)
            )
        ),
        SheetTable("Orders over time", listOf(period, "Orders"), ordersOverTime.map { listOf(it.label, it.value.toInt()) }),
        SheetTable("Payments over time", listOf(period, "Paid"), paymentsOverTime.map { listOf(it.label, it.value) }, setOf(1)),
        SheetTable("Quantity per unit", listOf("Unit", "Quantity"), qtyByUnit.map { listOf(it.label, it.value) }),
        SheetTable("Top buyers", listOf("Buyer", "Orders", "Billed", "Outstanding"), topBuyers.map { listOf(it.name, it.orders, it.billed, it.outstanding) }, setOf(2, 3)),
        SheetTable("Top suppliers", listOf("Supplier", "Orders", "Billed", "Outstanding"), topSuppliers.map { listOf(it.name, it.orders, it.billed, it.outstanding) }, setOf(2, 3)),
        SheetTable("Outstanding ageing", listOf("Age", "Bills", "Outstanding"), ageing.map { listOf(it.label, it.count, it.amount) }, setOf(2)),
        SheetTable("Bill status", listOf("Status", "Bills", "Billed", "Balance"), status.map { listOf(it.status.label, it.count, it.billed, it.balance) }, setOf(2, 3)),
        SheetTable("Staff activity", listOf("Staff code", "Orders", "Advices", "Paid"), staff.map { listOf(it.code, it.orders, it.advices, it.paid) }, setOf(3)),
        SheetTable("Adjustments", listOf("Type", "Amount"), adjustments.map { listOf(it.label, it.value) }, setOf(1))
    )

    companion object {
        private val d = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        fun filterLabel(f: DashFilter): String = buildString {
            append(if (f.from == null && f.to == null) "All time" else "${f.from?.format(d) ?: "start"} to ${f.to?.format(d) ?: "today"}")
            if (f.buyer.isNotBlank()) append(", buyer ${SearchText.firstLine(f.buyer)}")
            if (f.supplier.isNotBlank()) append(", supplier ${SearchText.firstLine(f.supplier)}")
            f.staff?.let { append(", staff $it") }
        }
    }
}

/** The Home screen's at-a-glance numbers: today's work, bills to come, money due and recent activity. */
data class Home(
    val today: LocalDate,
    val ordersToday: Int,
    val ordersThisMonth: Int,
    /** Orders with no bill attached yet (the supplier's paper bill is still to come). */
    val awaitingBill: Int,
    /** Orders saved offline that get their FY number on the next sync. */
    val pendingNumbers: Int,
    val outstanding: Double,
    val billsDue: Int,
    val billsWithoutAmount: Int,
    val ageing: List<Bucket>,
    val receivedThisMonth: Double,
    val advicesThisMonth: Int,
    val oldestDue: List<BillListItem>,
    val recentOrders: List<OrderRow>,
    val recentAdvices: List<AdviceSummary>
)

/** Dashboard numbers from the local database. Everything is computed locally; no cloud calls. */
class Analytics(private val db: Db, private val orders: OrderStore, private val payments: PaymentStore) {

    fun home(today: LocalDate = LocalDate.now(), listSize: Int = 6): Home {
        val all = orders.list(limit = 1_000_000)
        val monthStart = today.withDayOfMonth(1)
        val bills = payments.bills()
        val due = bills.filter { (it.bill.balance ?: 0.0) > 0.005 }
        val ageing = AGES.map { (label, range) ->
            val inBucket = due.filter { it.bill.billDate != null && ChronoUnit.DAYS.between(it.bill.billDate, today) in range }
            Bucket(label, inBucket.size, PaymentMath.round2(inBucket.sumOf { it.bill.balance ?: 0.0 }))
        } + due.filter { it.bill.billDate == null }.takeIf { it.isNotEmpty() }
            ?.let { listOf(Bucket("No bill date", it.size, PaymentMath.round2(it.sumOf { b -> b.bill.balance ?: 0.0 }))) }.orEmpty()
        val advices = payments.search()
        val thisMonth = advices.filter { a -> a.payDate?.let { !it.isBefore(monthStart) && !it.isAfter(today) } == true }
        return Home(
            today = today,
            ordersToday = all.count { it.day == today },
            ordersThisMonth = all.count { d -> d.day?.let { !it.isBefore(monthStart) && !it.isAfter(today) } == true },
            awaitingBill = all.count { it.billNos.isEmpty() },
            pendingNumbers = all.count { OrderNumber.isPending(it.orderNo) },
            outstanding = PaymentMath.round2(due.sumOf { it.bill.balance ?: 0.0 }),
            billsDue = due.size,
            billsWithoutAmount = bills.count { it.bill.amount == null },
            ageing = ageing,
            receivedThisMonth = PaymentMath.round2(thisMonth.sumOf { it.paid }),
            advicesThisMonth = thisMonth.size,
            oldestDue = due.sortedWith(compareBy<BillListItem> { it.bill.billDate == null }.thenBy { it.bill.billDate }).take(listSize),
            recentOrders = all.take(listSize),
            recentAdvices = advices.take(listSize)
        )
    }

    fun paymentLines(): List<PaymentLine> = db.read { c ->
        c.query(
            """select a.id as advice_id, a.advice_no, a.pay_date, a.buyer, a.supplier, a.mode, a.reference,
                      b.bill_no, b.bill_date, b.amount, l.paid, l.cd_percent, l.cd_amount, l.goods_return, l.rate_diff,
                      l.claim, l.claim_note
               from advice_lines l join payment_advices a on a.id = l.advice_id join bills b on b.id = l.bill_id
               where not l.deleted and not a.deleted and not b.deleted
               order by a.pay_date nulls last, a.advice_no, b.bill_no"""
        ) {
            PaymentLine(
                it.id("advice_id"), it.str("advice_no"), it.date("pay_date"), it.str("buyer"), it.str("supplier"), it.str("mode"),
                it.str("reference"), it.str("bill_no"), it.date("bill_date"), it.dbl("amount"), it.dbl("paid") ?: 0.0,
                it.dbl("cd_percent"), it.dbl("cd_amount") ?: 0.0, it.dbl("goods_return") ?: 0.0, it.dbl("rate_diff") ?: 0.0,
                it.dbl("claim") ?: 0.0, it.str("claim_note").orEmpty()
            )
        }
    }

    /** Staff digits that appear in order numbers, for the dashboard's staff filter. */
    fun staffCodes(): List<Int> = db.read { c ->
        c.query("select distinct substr(order_no, 1, 1) as d from orders where not deleted and order_no ~ '^[1-9]20[0-9]{6}[0-9]+$' order by 1") {
            it.str("d")?.toIntOrNull()
        }
    }.filterNotNull()

    fun dashboard(filter: DashFilter, today: LocalDate = LocalDate.now()): Dashboard {
        val orderRows = orders.list(OrderFilter(from = filter.from, to = filter.to, buyer = filter.buyer, supplier = filter.supplier, staff = filter.staff), limit = 1_000_000)
        val bk = SearchText.partyKey(filter.buyer)
        val sk = SearchText.partyKey(filter.supplier)
        // Bills dated in range (undated ones count with their orders), for the filtered parties.
        val orderBillKeys = orderRows.flatMap { it.billNos }.map(SearchText::billKey).toSet()
        val bills = payments.bills(BillFilter(buyer = filter.buyer, supplier = filter.supplier)).filter { b ->
            val key = SearchText.billKey(b.bill.billNo)
            val inRange = if (b.bill.billDate != null) filter.contains(b.bill.billDate) else key in orderBillKeys
            inRange && (filter.staff == null || key in orderBillKeys)
        }
        val lines = paymentLines().filter { l ->
            filter.contains(l.payDate) &&
                (bk.isEmpty() || SearchText.partyKey(l.buyer) == bk) &&
                (sk.isEmpty() || SearchText.partyKey(l.supplier) == sk) &&
                (filter.staff == null || staffOfAdvice(l.adviceNo) == filter.staff)
        }

        val days = orderRows.mapNotNull { it.day } + lines.mapNotNull { it.payDate }
        val start = filter.from ?: days.minOrNull() ?: today.minusMonths(11).withDayOfMonth(1)
        val end = filter.to ?: maxOf(today, days.maxOrNull() ?: today)
        val span = ChronoUnit.DAYS.between(start, end)
        val period = when { span <= 45 -> "Day"; span <= 120 -> "Week"; else -> "Month" }
        val buckets = timeBuckets(start, end, period)
        fun bucketOf(day: LocalDate): String = when (period) {
            "Day" -> day.format(DAY)
            "Week" -> day.minusDays((day.dayOfWeek.value - 1).toLong()).format(DAY)
            else -> YearMonth.from(day).format(MONTH)
        }
        val ordersOverTime = orderRows.mapNotNull { it.day }.groupingBy(::bucketOf).eachCount()
        val paidOverTime = lines.filter { it.payDate != null }.groupBy { bucketOf(it.payDate!!) }.mapValues { (_, v) -> v.sumOf { it.paid } }

        val qty = linkedMapOf<String, BigDecimal>()
        var itemCount = 0
        for (o in orderRows) for (raw in o.data.items) {
            val item = Units.splitLegacy(raw)
            if (item.particulars.isBlank() && item.quantity.isBlank()) continue
            itemCount++
            val unit = QtyUnit.ofCode(item.unit)?.label ?: continue
            val q = item.quantity.replace(",", "").trim().toBigDecimalOrNull() ?: continue
            qty[unit] = (qty[unit] ?: BigDecimal.ZERO) + q
        }

        fun parties(of: (OrderRow) -> String, billParty: (BillListItem) -> String): List<PartyStat> {
            val billStats = bills.groupBy { SearchText.partyKey(billParty(it)) }
            return orderRows.groupBy { SearchText.partyKey(of(it)) }.filterKeys { it.isNotEmpty() }.map { (key, rows) ->
                val b = billStats[key].orEmpty()
                PartyStat(
                    SearchText.firstLine(of(rows.first())), rows.size,
                    PaymentMath.round2(b.sumOf { it.bill.amount ?: 0.0 }),
                    PaymentMath.round2(b.sumOf { (it.bill.balance ?: 0.0).coerceAtLeast(0.0) })
                )
            }.sortedWith(compareByDescending<PartyStat> { it.billed }.thenByDescending { it.orders }).take(10)
        }

        val open = bills.filter { (it.bill.balance ?: 0.0) > 0.005 }
        val ageing = AGES.map { (label, range) ->
            val inBucket = open.filter { it.bill.billDate != null && ChronoUnit.DAYS.between(it.bill.billDate, today) in range }
            Bucket(label, inBucket.size, PaymentMath.round2(inBucket.sumOf { it.bill.balance ?: 0.0 }))
        } + open.filter { it.bill.billDate == null }.takeIf { it.isNotEmpty() }
            ?.let { listOf(Bucket("No bill date", it.size, PaymentMath.round2(it.sumOf { b -> b.bill.balance ?: 0.0 }))) }.orEmpty()

        val status = PaymentStatus.entries.map { s ->
            val inS = bills.filter { it.bill.status == s }
            StatusStat(s, inS.size, PaymentMath.round2(inS.sumOf { it.bill.amount ?: 0.0 }), PaymentMath.round2(inS.sumOf { (it.bill.balance ?: 0.0).coerceAtLeast(0.0) }))
        }

        val ordersByStaff = orderRows.mapNotNull { staffOfOrder(it.orderNo) }.groupingBy { it }.eachCount()
        val advicesByStaff = lines.distinctBy { it.adviceId }.mapNotNull { staffOfAdvice(it.adviceNo) }.groupingBy { it }.eachCount()
        val paidByStaff = lines.groupBy { staffOfAdvice(it.adviceNo) }
        val staff = (ordersByStaff.keys + advicesByStaff.keys).sorted().map { code ->
            StaffStat(code, ordersByStaff[code] ?: 0, advicesByStaff[code] ?: 0, PaymentMath.round2(paidByStaff[code].orEmpty().sumOf { it.paid }))
        }

        return Dashboard(
            filter = filter,
            orderCount = orderRows.size,
            itemCount = itemCount,
            billCount = bills.size,
            billed = PaymentMath.round2(bills.sumOf { it.bill.amount ?: 0.0 }),
            outstanding = PaymentMath.round2(open.sumOf { it.bill.balance ?: 0.0 }),
            adviceCount = lines.map { it.adviceId }.distinct().size,
            paid = PaymentMath.round2(lines.sumOf { it.paid }),
            cashDiscount = PaymentMath.round2(lines.sumOf { it.cd }),
            goodsReturn = PaymentMath.round2(lines.sumOf { it.goodsReturn }),
            rateDiff = PaymentMath.round2(lines.sumOf { it.rateDiff }),
            claim = PaymentMath.round2(lines.sumOf { it.claim }),
            pendingBills = orderRows.count { it.billNos.isEmpty() },
            period = period,
            ordersOverTime = buckets.map { Point(it, (ordersOverTime[it] ?: 0).toDouble()) },
            paymentsOverTime = buckets.map { Point(it, PaymentMath.round2(paidOverTime[it] ?: 0.0)) },
            qtyByUnit = QtyUnit.entries.filter { it.label in qty }.map { Point(it.label, qty.getValue(it.label).toDouble()) },
            topBuyers = parties({ it.data.buyer }, { it.buyer }),
            topSuppliers = parties({ it.data.supplier }, { it.supplier }),
            ageing = ageing,
            status = status,
            staff = staff
        )
    }

    private fun timeBuckets(start: LocalDate, end: LocalDate, period: String): List<String> {
        val out = ArrayList<String>()
        when (period) {
            "Day" -> { var d = start; while (!d.isAfter(end)) { out += d.format(DAY); d = d.plusDays(1) } }
            "Week" -> { var d = start.minusDays((start.dayOfWeek.value - 1).toLong()); while (!d.isAfter(end)) { out += d.format(DAY); d = d.plusWeeks(1) } }
            else -> { var m = YearMonth.from(start); val last = YearMonth.from(end); while (!m.isAfter(last)) { out += m.format(MONTH); m = m.plusMonths(1) } }
        }
        return out
    }

    companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yy")
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
        private val AGES = listOf("0–30 days" to 0L..30L, "31–60 days" to 31L..60L, "61–90 days" to 61L..90L, "90+ days" to 91L..Long.MAX_VALUE)
        private val orderNo = Regex("^([1-9])20\\d{6}\\d+$")
        private val adviceNo = Regex("^PA([1-9])20\\d{6}\\d+$")

        fun staffOfOrder(no: String): Int? = orderNo.matchEntire(no)?.groupValues?.get(1)?.toInt()
        fun staffOfAdvice(no: String): Int? = adviceNo.matchEntire(no)?.groupValues?.get(1)?.toInt()
    }
}
