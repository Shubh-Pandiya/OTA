package com.example.otaapplication

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderNumberTest {
    private val day = LocalDate.of(2026, 11, 7)
    private val varun = OrderNumber.prefix(1, day)
    private val pawan = OrderNumber.prefix(2, day)

    @Test
    fun formatsStaffDigitDateAndUnpaddedSerial() {
        assertEquals("120261107", varun)
        assertEquals("1202611071", OrderNumber.format(varun, 1))
        assertEquals("1202611072", OrderNumber.format(varun, 2))
        assertEquals("12026110712", OrderNumber.format(varun, 12))
        assertEquals("2202611071", OrderNumber.format(pawan, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun staffCodeMustBeOneDigit() {
        OrderNumber.prefix(10, day)
    }

    @Test
    fun firstOrderOfTheDayStartsAtOne() {
        assertEquals(1, OrderNumber.nextSerial(varun, null, 0, emptyList()))
    }

    @Test
    fun serialResetsOnANewDay() {
        assertEquals(1, OrderNumber.nextSerial(varun, "120261106", 9, listOf("1202611069", "OF-77")))
    }

    @Test
    fun eachStaffMemberHasTheirOwnSerial() {
        val used = listOf("1202611071", "1202611072", "1202611073")
        assertEquals(1, OrderNumber.nextSerial(pawan, null, 0, used))
        assertEquals(4, OrderNumber.nextSerial(varun, null, 0, used))
    }

    @Test
    fun deletingTheLatestOrderDoesNotReuseItsNumber() {
        // Counter says 3 were issued; order 3 was deleted from history.
        assertEquals(4, OrderNumber.nextSerial(varun, varun, 3, listOf("1202611071", "1202611072")))
    }

    @Test
    fun savedOrdersWinIfTheCounterWasLost() {
        assertEquals(3, OrderNumber.nextSerial(varun, null, 0, listOf("1202611071", "1202611072", "1202611061")))
    }

    @Test
    fun serialsPastNineAreOrderedNumerically() {
        assertEquals(11, OrderNumber.nextSerial(varun, null, 0, listOf("1202611079", "12026110710", "1202611072")))
    }

    @Test
    fun ignoresOldStyleManualAndOtherDayNumbers() {
        assertNull(OrderNumber.serialOf("OF-77", varun))
        assertNull(OrderNumber.serialOf("120261107", varun))
        assertNull(OrderNumber.serialOf("120261107A1", varun))
        assertNull(OrderNumber.serialOf("1202611061", varun))
        assertNull(OrderNumber.serialOf("2026110701", varun))
        assertEquals(105, OrderNumber.serialOf("120261107105", varun))
    }

    @Test
    fun prefixOfRecognisesOnlyPerStaffNumbers() {
        assertEquals("120261107", OrderNumber.prefixOf("12026110712"))
        assertEquals("320261107", OrderNumber.prefixOf(" 3202611071 "))
        assertNull(OrderNumber.prefixOf("2026110701"))
        assertNull(OrderNumber.prefixOf("0202611071"))
        assertNull(OrderNumber.prefixOf("OF-77"))
    }
}
