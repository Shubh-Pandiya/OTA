package com.example.otaapplication

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdviceNumberTest {
    private val day = LocalDate.of(2026, 11, 8)
    private val varun = AdviceNumber.prefix(1, day)

    @Test
    fun formatsPaStaffDigitDateAndThreeDigitSerial() {
        assertEquals("PA120261108", varun)
        assertEquals("PA120261108001", AdviceNumber.format(varun, 1))
        assertEquals("PA320261108012", AdviceNumber.format(AdviceNumber.prefix(3, day), 12))
        assertEquals("PA1202611081000", AdviceNumber.format(varun, 1000))
    }

    @Test
    fun readsPrefixAndSerialBack() {
        assertEquals("PA120261108", AdviceNumber.prefixOf("PA120261108007"))
        assertEquals(7, AdviceNumber.serialOf("PA120261108007", varun))
        assertNull(AdviceNumber.prefixOf("120261108007")) // an order number
        assertNull(AdviceNumber.prefixOf("PA020261108001")) // no staff digit 0
        assertNull(AdviceNumber.prefixOf("manual 12"))
    }

    @Test
    fun nextSerialNeverReusesANumber() {
        assertEquals(1, AdviceNumber.nextSerial(varun, null, 0, emptyList()))
        assertEquals(6, AdviceNumber.nextSerial(varun, null, 0, listOf("PA120261108002", "PA120261108005")))
        // The stored counter wins when it is ahead (e.g. the highest one was deleted).
        assertEquals(9, AdviceNumber.nextSerial(varun, varun, 8, listOf("PA120261108002")))
        // Yesterday's counter does not carry over.
        assertEquals(1, AdviceNumber.nextSerial(varun, "PA120261107", 40, emptyList()))
    }
}
