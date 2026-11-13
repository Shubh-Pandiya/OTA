package com.example.otaapplication.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaAccountsTest {
    @After fun reset() { SyncIds.testMode = false }

    @Test fun adminsAreVarunAndShubhamOnly() {
        assertTrue(OtaAccounts.isAdmin("varun@ota.com"))
        assertTrue(OtaAccounts.isAdmin(" Shubham@OTA.com "))
        assertFalse(OtaAccounts.isAdmin("testuser1@ota.com"))
        assertFalse(OtaAccounts.isAdmin("clerk@ota.com"))
        assertFalse(OtaAccounts.isAdmin(null))
    }

    @Test fun onlyTestUserWorksOnTestData() {
        assertEquals(OtaAccounts.MODE_TEST, OtaAccounts.dataMode("TestUser1@ota.com"))
        assertEquals(OtaAccounts.MODE_PRODUCTION, OtaAccounts.dataMode("varun@ota.com"))
        assertEquals(OtaAccounts.MODE_PRODUCTION, OtaAccounts.dataMode(null))
    }

    @Test fun testBillIdsNeverCollideWithRealOnes() {
        val real = SyncIds.bill("acme|46")
        SyncIds.testMode = true
        val test = SyncIds.bill("acme|46")
        assertNotEquals(real, test)
        assertEquals(test, SyncIds.bill("acme|46"))
        SyncIds.testMode = false
        assertEquals(real, SyncIds.bill("acme|46"))
    }
}
