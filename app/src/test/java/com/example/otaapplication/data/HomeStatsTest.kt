package com.example.otaapplication.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HomeStatsTest {
    private val today = LocalDate.of(2026, 11, 20)

    private fun bill(id: String, amount: Double?, settled: Double, date: LocalDate?) =
        BillListItem(Bill(id = id, billNo = id, billDate = date, amount = amount, settled = settled), "Buyer", "Supplier")

    private fun advice(id: String, date: LocalDate?, paid: Double) =
        AdviceSummary(id, id, date, "B", "S", PaymentMode.CASH, "", paid, emptyList())

    @Test
    fun outstandingIsBucketedByBillAge() {
        val stats = HomeStats.compute(
            today = today,
            ordersToday = 2, ordersThisMonth = 9, ordersAwaitingBill = 4,
            recentOrders = emptyList(),
            bills = listOf(
                bill("fresh", 10_000.0, 0.0, today.minusDays(30)),
                bill("partly", 5_000.0, 2_000.0, today.minusDays(31)),
                bill("old", 1_000.0, 0.0, today.minusDays(120)),
                bill("undated", 700.0, 0.0, null),
                bill("paid", 3_000.0, 3_000.0, today.minusDays(200)),
                bill("noAmount", null, 0.0, today)
            ),
            advices = emptyList()
        )
        assertEquals(14_700.0, stats.outstanding, 0.0)
        assertEquals(4, stats.billsDue)
        assertEquals(1, stats.billsWithoutAmount)
        assertEquals(listOf(10_000.0, 3_000.0, 0.0, 1_000.0, 700.0), stats.ageing.map { it.amount })
        assertEquals(listOf(1, 1, 0, 1, 1), stats.ageing.map { it.count })
        // Oldest dated bill first, undated last.
        assertEquals(listOf("old", "partly", "fresh"), stats.oldestDue.map { it.bill.id })
    }

    @Test
    fun receivedThisMonthCountsOnlyThisMonthsAdvices() {
        val stats = HomeStats.compute(
            today = today, ordersToday = 0, ordersThisMonth = 0, ordersAwaitingBill = 0, recentOrders = emptyList(), bills = emptyList(),
            advices = listOf(
                advice("a", today, 1_000.0),
                advice("b", today.withDayOfMonth(1), 2_500.5),
                advice("c", today.withDayOfMonth(1).minusDays(1), 9_999.0),
                advice("d", null, 50.0)
            )
        )
        assertEquals(3_500.5, stats.receivedThisMonth, 0.0)
        assertEquals(2, stats.advicesThisMonth)
        assertEquals(listOf("a", "b", "c"), stats.recentAdvices.map { it.id })
        assertEquals(0.0, stats.outstanding, 0.0)
    }
}
