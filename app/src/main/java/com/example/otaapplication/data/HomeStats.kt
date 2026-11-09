package com.example.otaapplication.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Outstanding bills of one age band, by bill date. */
data class AgeBucket(val label: String, val count: Int, val amount: Double)

/** One bar of a monthly chart, e.g. "Nov" → 12 orders. */
data class MonthValue(val label: String, val value: Double)

/** A party and how much is outstanding with it. */
data class PartyAmount(val name: String, val amount: Double, val bills: Int)

/** What Home and Insights show; built by [HomeStats.compute] from data the screens already load. */
data class HomeStats(
    val ordersToday: Int,
    val ordersThisMonth: Int,
    /** Orders with no bill attached yet (the supplier's bill is still to come). */
    val ordersAwaitingBill: Int,
    val outstanding: Double,
    val billsDue: Int,
    /** Bills without an amount, so their balance is unknown. */
    val billsWithoutAmount: Int,
    val ageing: List<AgeBucket>,
    val receivedThisMonth: Double,
    val advicesThisMonth: Int,
    /** Bills with money still due, oldest first. */
    val oldestDue: List<BillListItem>,
    val recentOrders: List<OrderSummary>,
    val recentAdvices: List<AdviceSummary>,
    /** Orders saved offline that are still waiting for their number from the cloud. */
    val pendingNumbers: Int = 0,
    /** Bills older than 90 days with money still due. */
    val overdueBills: Int = 0,
    /** Orders per month, oldest month first (last [MONTHS] months). */
    val ordersByMonth: List<MonthValue> = emptyList(),
    /** Amount paid by advices per month, oldest month first. */
    val paidByMonth: List<MonthValue> = emptyList(),
    val topBuyers: List<PartyAmount> = emptyList(),
    val topSuppliers: List<PartyAmount> = emptyList()
) {
    companion object {
        const val MONTHS = 6
        private val spaces = Regex("\\s+")
        private val monthLabel = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ENGLISH)

        /** First day of each of the last [MONTHS] months, oldest first. */
        fun monthStarts(today: LocalDate): List<LocalDate> =
            (MONTHS - 1 downTo 0).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }

        fun monthLabel(start: LocalDate): String = start.format(monthLabel)

        /** Groups due bills by party (first line, ignoring case and spacing), largest first. */
        fun topParties(due: List<BillListItem>, party: (BillListItem) -> String, limit: Int = 5): List<PartyAmount> =
            due.groupBy { SearchText.firstLine(party(it)).trim().replace(spaces, " ").lowercase() }
                .filterKeys { it.isNotBlank() }
                .map { (_, items) -> PartyAmount(SearchText.firstLine(party(items.first())).trim(), PaymentMath.round2(items.sumOf { it.bill.balance!! }), items.size) }
                .sortedByDescending { it.amount }
                .take(limit)

        val AGE_LABELS = listOf("0–30 days", "31–60 days", "61–90 days", "90+ days", "No bill date")

        fun ageIndex(billDate: LocalDate?, today: LocalDate): Int {
            billDate ?: return 4
            val days = ChronoUnit.DAYS.between(billDate, today)
            return when {
                days <= 30 -> 0
                days <= 60 -> 1
                days <= 90 -> 2
                else -> 3
            }
        }

        fun compute(
            today: LocalDate,
            ordersToday: Int,
            ordersThisMonth: Int,
            ordersAwaitingBill: Int,
            recentOrders: List<OrderSummary>,
            bills: List<BillListItem>,
            advices: List<AdviceSummary>,
            listSize: Int = 3,
            pendingNumbers: Int = 0,
            /** Order counts for [monthStarts], oldest first. */
            ordersPerMonth: List<Int> = emptyList()
        ): HomeStats {
            val due = bills.filter { (it.bill.balance ?: 0.0) > 0.005 }
            val counts = IntArray(AGE_LABELS.size)
            val amounts = DoubleArray(AGE_LABELS.size)
            for (item in due) {
                val i = ageIndex(item.bill.billDate, today)
                counts[i]++
                amounts[i] += item.bill.balance!!
            }
            val monthStart = today.withDayOfMonth(1)
            val thisMonth = advices.filter { a -> a.payDate?.let { !it.isBefore(monthStart) && !it.isAfter(today) } == true }
            return HomeStats(
                ordersToday = ordersToday,
                ordersThisMonth = ordersThisMonth,
                ordersAwaitingBill = ordersAwaitingBill,
                outstanding = PaymentMath.round2(due.sumOf { it.bill.balance!! }),
                billsDue = due.size,
                billsWithoutAmount = bills.count { it.bill.amount == null },
                ageing = AGE_LABELS.indices.map { AgeBucket(AGE_LABELS[it], counts[it], PaymentMath.round2(amounts[it])) },
                receivedThisMonth = PaymentMath.round2(thisMonth.sumOf { it.paid }),
                advicesThisMonth = thisMonth.size,
                oldestDue = due.sortedWith(compareBy<BillListItem> { it.bill.billDate == null }.thenBy { it.bill.billDate }).take(listSize),
                recentOrders = recentOrders.take(listSize),
                recentAdvices = advices.take(listSize),
                pendingNumbers = pendingNumbers,
                overdueBills = counts[3],
                ordersByMonth = monthStarts(today).zip(ordersPerMonth) { m, n -> MonthValue(monthLabel(m), n.toDouble()) },
                paidByMonth = monthStarts(today).map { m ->
                    val end = m.plusMonths(1)
                    MonthValue(monthLabel(m), PaymentMath.round2(advices.filter { a -> a.payDate?.let { !it.isBefore(m) && it.isBefore(end) } == true }.sumOf { it.paid }))
                },
                topBuyers = topParties(due, { it.buyer }),
                topSuppliers = topParties(due, { it.supplier })
            )
        }
    }
}
