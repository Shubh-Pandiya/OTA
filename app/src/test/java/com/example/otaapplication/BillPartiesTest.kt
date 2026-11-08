package com.example.otaapplication

import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.OrderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillPartiesTest {
    private fun order(buyer: String, supplier: String) =
        OrderSummary("o", "u", "26270001", "01/04/2026", buyer, supplier, "", null, emptyList())

    @Test fun storedPartiesWin() {
        val bill = Bill("b", "77", buyer = "Raj Textiles", supplier = "Arvind Mills")
        assertEquals("Raj Textiles" to "Arvind Mills", billParties(bill, listOf(order("Other", "Mill"))))
    }

    @Test fun orderPartiesUsedWhenNoneStored() {
        assertEquals("Raj" to "Arvind", billParties(Bill("b", "77"), listOf(order("Raj\nSurat", "Arvind\nAhmedabad"))))
    }

    @Test fun unknownWithoutPartiesOrOrders() {
        assertNull(billParties(Bill("b", "77"), emptyList()))
    }
}
