package com.example.otaapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchTextTest {

    @Test
    fun normalizeLowercasesAndCollapsesWhitespace() {
        assertEquals("ram textiles indore", SearchText.normalize("  Ram\nTEXTILES \t Indore "))
    }

    @Test
    fun tokensSplitOnWhitespace() {
        assertEquals(listOf("inv", "23"), SearchText.tokens(" INV   23 "))
        assertEquals(emptyList<String>(), SearchText.tokens("   "))
    }

    @Test
    fun likePatternsEscapeWildcards() {
        assertEquals("%50\\%\\_a\\\\b%", SearchText.containsPattern("50%_a\\b"))
        assertEquals("ram%", SearchText.prefixPattern("ram"))
    }

    @Test
    fun billKeyIgnoresCaseAndSpacing() {
        assertEquals(SearchText.billKey("inv  23"), SearchText.billKey(" INV 23 "))
    }

    @Test
    fun dayKeyParsesOrderDates() {
        assertEquals(20261107, SearchText.dayKey("07-11-2026"))
        assertEquals(20261107, SearchText.dayKey(LocalDate.of(2026, 11, 7)))
        assertEquals(0, SearchText.dayKey("N/A"))
    }

    @Test
    fun splitNameAndAddress() {
        assertEquals("Ram Textiles" to "12 MG Road\nIndore", SearchText.splitNameAndAddress("Ram Textiles\n 12 MG Road \nIndore"))
        assertEquals("Ram" to "", SearchText.splitNameAndAddress("Ram"))
    }

    @Test
    fun partyFieldTextMatchesHowUsersTypeIt() {
        val party = Party(type = PartyType.BUYER, name = " Ram Textiles ", address = "12 MG Road", city = "Indore", phone = "98765", gstin = "23ABCDE1234F1Z5")
        assertEquals("Ram Textiles\n12 MG Road\nIndore", party.toFieldText())
        assertEquals("Ram Textiles\n12 MG Road, Indore", party.copy(address = "12 MG Road, Indore").toFieldText())
        assertEquals("Ram Textiles", Party(type = PartyType.SUPPLIER, name = "Ram Textiles").toFieldText())
    }

    @Test
    fun bankDetailsValidation() {
        assertTrue(BankDetails.isValidIfsc(""))
        assertTrue(BankDetails.isValidIfsc("hdfc0001234"))
        assertFalse(BankDetails.isValidIfsc("HDFC1001234"))
        assertFalse(BankDetails.isValidIfsc("HDFC000123"))
        assertTrue(BankDetails.isValidAccountNo("5010 0123 4567 89"))
        assertFalse(BankDetails.isValidAccountNo("12-34"))
        assertTrue(BankDetails.isValidUpi("ram.textiles@okhdfc"))
        assertFalse(BankDetails.isValidUpi("ram textiles"))
    }

    @Test
    fun bankDetailsCleanAndShare() {
        val bank = BankDetails(bankName = " HDFC Bank ", accountNo = "5010 0123", ifsc = "hdfc0001234").cleaned()
        assertEquals(BankDetails(bankName = "HDFC Bank", accountNo = "50100123", ifsc = "HDFC0001234"), bank)
        assertTrue(BankDetails(" ", "", "").isEmpty)
        assertFalse(bank.isEmpty)
        assertEquals(
            "Account name: Ram Textiles\nBank: HDFC Bank\nA/c no: 50100123\nIFSC: HDFC0001234",
            bank.toShareText("Ram Textiles")
        )
    }
}
