package com.example.otaapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatesTest {
    private fun money(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

    @Test
    fun plainAmountsParse() {
        assertEquals(60.0, Rates.parse("60")!!, 0.0)
        assertEquals(60.5, Rates.parse(" 60.5 ")!!, 0.0)
        assertEquals(1250.0, Rates.parse("1,250")!!, 0.0)
        assertEquals(125000.0, Rates.parse("1,25,000.00")!!, 0.0)
        assertEquals(60.0, Rates.parse("₹60")!!, 0.0)
        assertEquals(60.0, Rates.parse("Rs. 60/-")!!, 0.0)
        assertEquals(0.5, Rates.parse(".5")!!, 0.0)
    }

    @Test
    fun otherTextIsKept() {
        assertNull(Rates.parse(""))
        assertNull(Rates.parse("60 + GST"))
        assertNull(Rates.parse("as per sample"))
        assertNull(Rates.parse("60/80"))
        assertEquals("60 + GST", Rates.print(" 60 + GST ", ::money))
        assertEquals("60.00", Rates.print("60", ::money))
        assertEquals("", Rates.print("", ::money))
    }
}
