package com.example.otaapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    private fun item(qty: String, unit: String = "") = OrderItem(serialNo = "1", quantity = qty, unit = unit)

    @Test
    fun splitsTrailingUnitTypedIntoOldQuantities() {
        val cases = mapOf(
            "100 mtr" to ("100" to "M"), "20m" to ("20" to "M"), "1,250.5 Mtrs." to ("1,250.5" to "M"),
            "5 than" to ("5" to "T"), "12 PCS" to ("12" to "P"), "3 sets" to ("3" to "S"), "2 Bale" to ("2" to "B")
        )
        for ((typed, expected) in cases) {
            val split = Units.splitLegacy(item(typed))
            assertEquals(typed, expected, split.quantity to split.unit)
        }
    }

    @Test
    fun leavesUnknownOrPlainQuantitiesAsTyped() {
        for (typed in listOf("5 kg", "50", "", "about 20 mtr", "10 mtr + 2 than")) {
            val split = Units.splitLegacy(item(typed))
            assertEquals(typed, typed to "", split.quantity to split.unit)
        }
        val already = item("7 mtr", "T")
        assertEquals(already, Units.splitLegacy(already))
    }

    @Test
    fun displayKeepsOldItemsUnchanged() {
        assertEquals("20m", Units.display(item("20m")))
        assertEquals("5 kg", Units.display(item("5 kg")))
        assertEquals("250 Mtr", Units.display(item("250", "M")))
        assertEquals("", Units.display(item(" ", "T")))
    }

    @Test
    fun newRowUsesLastUnitElseMtr() {
        assertEquals("M", Units.forNewRow(emptyList()))
        assertEquals("M", Units.forNewRow(listOf(item("5 kg"))))
        assertEquals("T", Units.forNewRow(listOf(item("1", "B"), item("2", "T"), item("3"))))
        assertEquals("M", OrderFormData().items.single().unit)
    }

    @Test
    fun totalsArePerUnitAndNeverMixed() {
        val items = listOf(item("100", "M"), item("10", "T"), item("150.50", "M"), item("5 kg"), item("abc", "P"), item("1,000", "M"))
        assertEquals("1250.5 Mtr + 10 Than", Units.totals(items))
        assertNull(Units.totals(listOf(item("20m"), item("5 kg"))))
    }
}
