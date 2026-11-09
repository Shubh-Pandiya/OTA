package com.example.otaapplication.data

import com.example.otaapplication.data.PartyMatch.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyMatchTest {

    @Test
    fun sameNameIgnoringCaseAndSpacingIsExact() {
        assertEquals(Level.EXACT, PartyMatch.level("Raymond  Ltd", "raymond ltd"))
        assertEquals(Level.EXACT, PartyMatch.level("Ram Textiles\nIndore", "ram textiles"))
    }

    @Test
    fun cityTypedAfterTheNameIsClose() {
        assertEquals(Level.CLOSE, PartyMatch.level("Shyam Garments Surat", "Shyam Garments"))
        assertEquals(Level.CLOSE, PartyMatch.level("Arvind", "Arvind Ahmedabad"))
        assertEquals(Level.CLOSE, PartyMatch.level("Raymond Ltd", "Raymond Mumbai"))
        assertEquals(Level.CLOSE, PartyMatch.level("Shyam Garments, Surat", "Shyam Garments"))
    }

    @Test
    fun punctuationHonorificsAndCompanySuffixesAreIgnored() {
        assertEquals(Level.CLOSE, PartyMatch.level("M/s. Raymond Ltd.", "Raymond"))
        assertEquals(Level.CLOSE, PartyMatch.level("Shree Ram Textiles Pvt Ltd", "Shri Ram Textiles"))
        assertEquals(Level.CLOSE, PartyMatch.level("Vimal & Co", "Vimal"))
        assertEquals(Level.CLOSE, PartyMatch.level("Vimal and Sons", "Vimal & Sons"))
        assertEquals(Level.CLOSE, PartyMatch.level("SiyaRam Silk", "Siya Ram Silk"))
    }

    @Test
    fun differentPartiesDoNotMatch() {
        assertEquals(Level.NONE, PartyMatch.level("Raymond", "Arvind"))
        assertEquals(Level.NONE, PartyMatch.level("Shyam Garments", "Shyam Textiles"))
        assertEquals(Level.NONE, PartyMatch.level("", "Raymond"))
        assertEquals(Level.NONE, PartyMatch.level("Ra", "Ra Textiles"))
    }

    @Test
    fun pairMatchesAsWellAsItsWeakerSide() {
        assertEquals(Level.CLOSE, PartyMatch.pairLevel("Shyam Garments", "Arvind", "Shyam Garments", "Arvind Ahmedabad"))
        assertEquals(Level.EXACT, PartyMatch.pairLevel("Shyam Garments", "arvind", "shyam garments", "Arvind"))
        assertEquals(Level.NONE, PartyMatch.pairLevel("Shyam Garments", "Raymond", "Shyam Garments Surat", "Arvind Ahmedabad"))
    }
}
