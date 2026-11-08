package com.example.otaapplication.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Outstanding bills of one age band, by bill date. */
data class AgeBucket(val label: String, val count: Int, val amount: Double)

/** What the Home screen shows; built by [HomeStats.compute] from data the screens already load. */
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
    val recentAdvices: List<AdviceSummary>
) {
    companion object {
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
            listSize: Int = 3
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
                recentAdvices = advices.take(listSize)
            )
        }
    }
}
