package com.example.otaapplication

import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillDiscount
import com.example.otaapplication.data.DiscountDraft
import com.example.otaapplication.data.DiscountKind
import com.example.otaapplication.data.GstSetting
import com.example.otaapplication.data.PaymentMath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillDiscountTest {
    @After
    fun reset() {
        GstSetting.percent = 5.0
    }

    private fun line(qty: String, rate: String) = OrderItem(particulars = "Shirting", quantity = qty, rate = rate)

    @Test
    fun discountsComeOffBeforeGst() {
        GstSetting.percent = 5.0
        val state = BillFieldsState()
        state.setItems(listOf(line("100", "100")))
        assertEquals("10500", state.amount)

        // 2% cash discount on ₹10,000 -> ₹9,800, + 5% GST = ₹10,290.
        state.addDiscount()
        state.updateDiscount(0, state.discounts[0].withPercent("2", 10_000.0))
        assertEquals("200", state.discounts[0].amount)
        assertEquals("10290", state.amount)

        // An "other" discount in rupees, with its remark.
        state.addDiscount()
        state.updateDiscount(1, state.discounts[1].copy(kind = DiscountKind.OTHER, remark = "Festival offer").withAmount("300"))
        assertEquals("9975", state.amount) // (10,000 - 200 - 300) * 1.05

        // Items change: the % line is repriced, the rupee line stays.
        state.updateItem(0, line("200", "100"))
        assertEquals("400", state.discounts[0].amount)
        assertEquals("20265", state.amount) // (20,000 - 400 - 300) * 1.05

        val details = state.details()
        assertEquals(2, details.discounts.size)
        assertEquals(BillDiscount(DiscountKind.OTHER, null, 300.0, "Festival offer"), details.discounts[1])
        // Already out of the bill amount: no payment-time C.D. to pre-fill the advice.
        assertNull(details.cdPercent)
        assertNull(details.cdAmount)

        state.removeDiscount(1)
        assertEquals("20580", state.amount)
    }

    @Test
    fun commissionBaseIsAfterDiscounts() {
        val bill = Bill(
            "1", "B1", amount = 10_290.0, items = listOf(line("100", "100")), gstPercent = 5.0,
            discounts = listOf(BillDiscount(DiscountKind.CASH, 2.0, 200.0, ""))
        )
        assertEquals(9_800.0, bill.taxableValue!!, 0.001)
        assertEquals(10_290.0, PaymentMath.billTotal(10_000.0, 200.0, 5.0)!!, 0.001)
        assertEquals(9_800.0, PaymentMath.billTotal(10_000.0, 200.0, null)!!, 0.001)
        // Old bills without lines keep backing GST out of the amount.
        assertEquals(10_000.0, PaymentMath.taxableValue(10_500.0, 5.0, null)!!, 0.001)
    }

    @Test
    fun discountsRoundTripCompactJson() {
        val list = listOf(BillDiscount(DiscountKind.CASH, 2.0, 200.0, "ignored"), BillDiscount(DiscountKind.OTHER, null, 150.5, "Rate diff"))
        val text = BillDiscount.toText(list)!!
        assertTrue(text, !text.contains("ignored"))
        assertEquals(listOf(list[0].copy(remark = ""), list[1]), BillDiscount.fromText(text))
        assertNull(BillDiscount.toText(emptyList()))
        assertEquals(emptyList<BillDiscount>(), BillDiscount.fromText(null))
        // Blank lines are dropped, bad numbers are invalid.
        assertNull(DiscountDraft().toDiscount())
        assertTrue(!DiscountDraft(amount = "x").valid)
    }
}
