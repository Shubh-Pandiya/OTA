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

    @Test fun testLoginUsesTestTables() {
        val test = "testuser1@ota.com"
        assertEquals("test_orders?select=*&order=updated_at", OtaAccounts.cloudPath("orders?select=*&order=updated_at", test))
        assertEquals("test_ota_deletions?id=gt.1", OtaAccounts.cloudPath("ota_deletions?id=gt.1", test))
        assertEquals("test_payment_advices", OtaAccounts.cloudPath("payment_advices", test))
        assertEquals("rpc/ota_allocate_order_no", OtaAccounts.cloudPath("rpc/ota_allocate_order_no", test))
        assertEquals("staff?select=code", OtaAccounts.cloudPath("staff?select=code", test))
        assertEquals("orders?select=*", OtaAccounts.cloudPath("orders?select=*", "varun@ota.com"))
        assertEquals("orders", OtaAccounts.cloudPath("orders", null))
    }
}
