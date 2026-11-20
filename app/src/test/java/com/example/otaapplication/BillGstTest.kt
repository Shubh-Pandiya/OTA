package com.example.otaapplication

import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.GstSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillGstTest {
    @After
    fun reset() {
        GstSetting.percent = 5.0
    }

    private fun line(qty: String, rate: String) = OrderItem(particulars = "Shirting", quantity = qty, rate = rate)

    @Test
    fun newBillAmountFollowsItemsPlusGst() {
        GstSetting.percent = 5.0
        val state = BillFieldsState()
        state.setItems(listOf(line("100", "50"), line("40", "125")))
        assertEquals("10500", state.amount)
        assertEquals(5.0, state.details().gstPercent!!, 0.0)

        // The agency rate is read when the bill is started.
        GstSetting.percent = 12.0
        val other = BillFieldsState()
        other.setItems(listOf(line("10", "99.99")))
        assertEquals("1119.89", other.amount)

        // Typed over: stays as typed; back to items + GST: follows again.
        state.typeAmount("10700")
        state.addItem()
        assertEquals("10700", state.amount)
        state.typeAmount("10500")
        state.updateItem(0, line("200", "50"))
        assertEquals("15750", state.amount)
    }

    @Test
    fun savedBillsKeepTheirGst() {
        GstSetting.percent = 12.0
        val gstBill = Bill("1", "B1", amount = 10_500.0, items = listOf(line("200", "50")), gstPercent = 5.0)
        val state = BillFieldsState.of(gstBill)
        state.updateItem(0, line("100", "50"))
        assertEquals("5250", state.amount)

        // Entered before GST was recorded: the amount follows the plain lines total, as before.
        val legacy = BillFieldsState.of(Bill("2", "B2", amount = 10_000.0, items = listOf(line("200", "50"))))
        legacy.updateItem(0, line("100", "50"))
        assertEquals("5000", legacy.amount)
        assertNull(legacy.details().gstPercent)
    }
}
