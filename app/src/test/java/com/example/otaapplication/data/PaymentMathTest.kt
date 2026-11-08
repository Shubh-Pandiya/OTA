package com.example.otaapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentMathTest {
    @Test
    fun lineSettlesPaidPlusAllDeductions() {
        val line = LineAmounts(paid = 9_500.0, cdPercent = 2.0, cdAmount = 200.0, goodsReturn = 250.5, rateDiff = 49.5)
        assertEquals(10_000.0, line.settled, 0.0)
        assertEquals(1_000.0, LineAmounts(paid = 1_000.0).settled, 0.0)
    }

    @Test
    fun balanceAndStatus() {
        assertEquals(PaymentStatus.UNPAID, PaymentMath.status(10_000.0, 0.0))
        assertEquals(PaymentStatus.PARTLY_PAID, PaymentMath.status(10_000.0, 4_000.0))
        assertEquals(PaymentStatus.PAID, PaymentMath.status(10_000.0, 10_000.0))
        assertEquals(PaymentStatus.PAID, PaymentMath.status(10_000.0, 10_000.004)) // rounding noise
        assertEquals(PaymentStatus.PAID, PaymentMath.status(10_000.0, 10_100.0)) // overpaid
        // A number-only bill (no amount) can never be fully paid.
        assertEquals(PaymentStatus.UNPAID, PaymentMath.status(null, 0.0))
        assertEquals(PaymentStatus.PARTLY_PAID, PaymentMath.status(null, 500.0))
        assertEquals(6_000.0, PaymentMath.balance(10_000.0, 4_000.0)!!, 0.0)
        assertEquals(-100.0, PaymentMath.balance(10_000.0, 10_100.0)!!, 0.0)
        assertNull(PaymentMath.balance(null, 500.0))
    }

    @Test
    fun cashDiscountPercentOfBillAmount() {
        assertEquals(250.0, PaymentMath.percentOf(12_500.0, 2.0)!!, 0.0)
        assertEquals(37.04, PaymentMath.percentOf(1_234.56, 3.0)!!, 0.0)
        assertNull(PaymentMath.percentOf(null, 2.0))
        assertNull(PaymentMath.percentOf(12_500.0, null))
    }

    @Test
    fun totalsAddUpTheLines() {
        val totals = AdviceTotals.of(listOf(
            LineAmounts(paid = 9_800.0, cdAmount = 200.0),
            LineAmounts(paid = 3_000.0, goodsReturn = 500.0, rateDiff = 0.1)
        ))
        assertEquals(12_800.0, totals.paid, 0.0)
        assertEquals(200.0, totals.cashDiscount, 0.0)
        assertEquals(500.0, totals.goodsReturn, 0.0)
        assertEquals(0.1, totals.rateDiff, 0.0)
        assertEquals(13_500.1, totals.settled, 0.0)
    }

    @Test
    fun claimDeductionSettlesTheBill() {
        val line = LineAmounts(paid = 9_000.0, cdAmount = 200.0, claim = 800.0, claimNote = "2 than torn")
        assertEquals(10_000.0, line.settled, 0.0)
        val totals = AdviceTotals.of(listOf(line, LineAmounts(paid = 500.0, claim = 25.5)))
        assertEquals(825.5, totals.claim, 0.0)
        assertEquals(10_525.5, totals.settled, 0.0)
        assertEquals(PaymentStatus.PAID, PaymentMath.status(10_000.0, line.settled))
    }

    @Test
    fun billLinesTotal() {
        val items = listOf(
            com.example.otaapplication.OrderItem(quantity = "25.5", rate = "120", unit = "M"),
            com.example.otaapplication.OrderItem(quantity = "2", rate = "1,450.50", unit = "T"),
            com.example.otaapplication.OrderItem(quantity = "5", rate = "", unit = "P") // no rate: skipped
        )
        assertEquals(5_961.0, PaymentMath.linesTotal(items)!!, 0.0)
        assertNull(PaymentMath.linesTotal(listOf(com.example.otaapplication.OrderItem(quantity = "3"))))
        assertNull(PaymentMath.linesTotal(emptyList()))
    }

    @Test
    fun suggestedDrCrBalance() {
        assertEquals(4_500.0 to BalanceSide.DR, PaymentMath.suggestedBalance(4_500.0))
        assertEquals(120.0 to BalanceSide.CR, PaymentMath.suggestedBalance(-120.0))
        assertNull(PaymentMath.suggestedBalance(0.001))
    }

    @Test
    fun parsesAndPrintsTypedAmounts() {
        assertEquals(12_500.5, PaymentMath.parse("12,500.50")!!, 0.0)
        assertEquals(300.0, PaymentMath.parse("₹ 300")!!, 0.0)
        assertNull(PaymentMath.parse(" "))
        assertNull(PaymentMath.parse("12a"))
        assertNull(PaymentMath.parse("NaN"))
        assertEquals("12500.5", PaymentMath.plain(12_500.50))
        assertEquals("300", PaymentMath.plain(300.0))
        assertEquals("", PaymentMath.plain(null))
    }

    @Test
    fun paymentModeAndSideParsing() {
        assertEquals(PaymentMode.CHEQUE, PaymentMode.parse("CHEQUE"))
        assertEquals(PaymentMode.CASH, PaymentMode.parse(null))
        assertEquals(PaymentMode.CASH, PaymentMode.parse("bogus"))
        assertEquals(BalanceSide.CR, BalanceSide.parse("CR"))
        assertNull(BalanceSide.parse(null))
    }
}
