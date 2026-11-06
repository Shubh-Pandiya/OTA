package com.example.otaapplication

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderNumberTest {
    private val today = OrderNumber.prefix(LocalDate.of(2026, 11, 7))

    @Test
    fun formatsDateAndPaddedSerial() {
        assertEquals("20261107", today)
        assertEquals("2026110701", OrderNumber.format(today, 1))
        assertEquals("2026110712", OrderNumber.format(today, 12))
        assertEquals("20261107100", OrderNumber.format(today, 100))
    }

    @Test
    fun firstOrderOfTheDayStartsAtOne() {
        assertEquals(1, OrderNumber.nextSerial(today, null, 0, emptyList()))
    }

    @Test
    fun serialResetsOnANewDay() {
        assertEquals(1, OrderNumber.nextSerial(today, "20261106", 9, listOf("2026110609", "OF-77")))
    }

    @Test
    fun deletingTheLatestOrderDoesNotReuseItsNumber() {
        // Counter says 3 were issued; order 03 was deleted from history.
        assertEquals(4, OrderNumber.nextSerial(today, today, 3, listOf("2026110701", "2026110702")))
    }

    @Test
    fun savedOrdersWinIfTheCounterWasLost() {
        assertEquals(3, OrderNumber.nextSerial(today, null, 0, listOf("2026110701", "2026110702", "2026110601")))
    }

    @Test
    fun ignoresManualAndOtherDayNumbers() {
        assertNull(OrderNumber.serialOf("OF-77", today))
        assertNull(OrderNumber.serialOf("20261107", today))
        assertNull(OrderNumber.serialOf("20261107A1", today))
        assertNull(OrderNumber.serialOf("2026110601", today))
        assertEquals(105, OrderNumber.serialOf("20261107105", today))
    }
}
