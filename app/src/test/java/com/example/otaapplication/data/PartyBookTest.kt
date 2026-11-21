package com.example.otaapplication.data

import com.example.otaapplication.data.PartyBook.OrderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PartyBookTest {
    private fun order(no: String, buyer: String, supplier: String, vararg bills: String, day: Int = 20260401) =
        PartyBook.Order(no, no, "", day, buyer, supplier, bills.toList())

    private fun bill(no: String, buyer: String, supplier: String, amount: Double?, settled: Double = 0.0) =
        PartyBook.BillItem(Bill(id = "b$no-$supplier", billNo = no, billDate = LocalDate.of(2026, 4, 2), amount = amount, settled = settled), buyer, supplier)

    private fun advice(no: String, buyer: String, supplier: String, paid: Double, vararg bills: String) =
        PartyBook.Advice(no, no, LocalDate.of(2026, 5, 1), buyer, supplier, paid, bills.toList())

    private val orders = listOf(
        order("O1", "Ram Textiles\nIndore", "Raymond Ltd", "B1"),
        order("O2", "Ram Textiles Pvt Ltd", "Arvind", "A7"),
        order("O3", "ram textiles", "Raymond Ltd"),
        order("O4", "Ram Textiles", "Raymond Ltd", "OLD9"),
        order("O5", "Shyam Garments", "Raymond Ltd", "B2")
    )
    private val bills = listOf(
        bill("B1", "Ram Textiles", "Raymond Ltd", 1050.0, settled = 400.0),
        bill("A7", "Ram Textiles", "Arvind", 500.0, settled = 500.0),
        // Same number from another supplier: must not be taken for Arvind's A7.
        bill("A7", "Shyam Garments", "Raymond Ltd", 300.0),
        bill("B2", "Shyam Garments", "Raymond Ltd", 200.0)
    )
    private val advices = listOf(
        advice("P1", "Ram Textiles", "Raymond Ltd", 400.0, "B1"),
        advice("P2", "Ram Textiles", "Arvind", 500.0, "A7"),
        advice("P3", "Shyam Garments", "Raymond Ltd", 50.0, "B2")
    )

    @Test
    fun buyerBookShowsEachOrderWithItsBillStatus() {
        val book = PartyBook.build(PartyType.BUYER, "Ram Textiles", orders, bills, advices)
        val states = book.orders.associate { it.order.orderNo to it.state }
        assertEquals(
            mapOf("O1" to OrderState.PARTLY_PAID, "O2" to OrderState.PAID, "O3" to OrderState.UNBILLED, "O4" to OrderState.BILLED),
            states
        )
        assertEquals(500.0, book.orders.first { it.order.orderNo == "O2" }.bills.single().amount!!, 0.0)
        assertEquals(listOf("P1", "P2"), book.advices.map { it.advice.adviceNo }.sorted())
    }

    @Test
    fun summaryCountsWhatIsStillDue() {
        val book = PartyBook.build(PartyType.BUYER, "Ram Textiles", orders, bills, advices)
        assertEquals(650.0, book.outstanding, 0.001)
        assertEquals(1, book.openBills)
        assertEquals(1, book.unbilledOrders)
        assertEquals(900.0, book.received, 0.001)
    }

    @Test
    fun openOnlyHidesSettledRecords() {
        val open = PartyBook.build(PartyType.BUYER, "Ram Textiles", orders, bills, advices).view(openOnly = true)
        assertEquals(listOf("O1", "O3"), open.orders.map { it.order.orderNo }.sorted())
        assertEquals(listOf("B1"), open.bills.map { it.bill.billNo })
        assertEquals(listOf("P1"), open.advices.map { it.advice.adviceNo })
        // Summary of the whole book is unchanged by the view of bills that are still open.
        assertEquals(650.0, open.outstanding, 0.001)
    }

    @Test
    fun supplierBookGroupsByBuyer() {
        val book = PartyBook.build(PartyType.SUPPLIER, "Raymond", orders, bills, advices)
        assertEquals(listOf("Ram Textiles", "Shyam Garments"), book.counterparties.map { it.name })
        assertEquals(500.0, book.counterparties[1].outstanding, 0.001)
        assertEquals(2, book.counterparties[1].openBills)
        val shyam = book.view(openOnly = false, counterparty = SearchText.partyKey("Shyam Garments"))
        assertEquals(listOf("O5"), shyam.orders.map { it.order.orderNo })
        assertEquals(listOf("A7", "B2"), shyam.bills.map { it.bill.billNo }.sorted())
        assertTrue(shyam.advices.single().open)
        assertEquals(500.0, shyam.outstanding, 0.001)
    }

    @Test
    fun billWithoutAmountIsOpenButAddsNothingDue() {
        val book = PartyBook.build(PartyType.BUYER, "Kiran", listOf(order("O9", "Kiran", "Arvind", "N1")),
            listOf(bill("N1", "Kiran", "Arvind", null)), emptyList())
        assertEquals(OrderState.BILL_DUE, book.orders.single().state)
        assertEquals(1, book.openBills)
        assertEquals(1, book.noAmountBills)
        assertEquals(0.0, book.outstanding, 0.0)
    }

    @Test
    fun searchWordIsTheLongestWordOfTheCoreName() {
        assertEquals("textiles", PartyBook.searchWord("Shree Ram Textiles Pvt Ltd\nIndore"))
        assertEquals("raymond", PartyBook.searchWord("M/s Raymond"))
    }
}
