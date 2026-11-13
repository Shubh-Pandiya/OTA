package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.cloud.OrderLimitReached
import com.ota.desktop.data.LocalSyncTable
import com.example.otaapplication.cloud.CloudException
import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.SessionStore
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import com.example.otaapplication.data.BillDetails
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.Kv
import com.ota.desktop.db.one
import com.ota.desktop.sync.CloudSettings
import com.ota.desktop.sync.SyncEngine
import com.ota.desktop.db.Migrations
import com.example.otaapplication.data.OtaAccounts
import com.example.otaapplication.data.SyncIds
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private lateinit var cloud: FakeCloud
    private lateinit var db: Db
    private lateinit var kv: Kv
    private lateinit var auth: SupabaseAuth
    private lateinit var config: SupabaseConfig
    private lateinit var catalog: CatalogStore
    private lateinit var orders: OrderStore
    private lateinit var payments: PaymentStore
    private lateinit var engine: SyncEngine
    private val day = LocalDate.of(2026, 11, 10)
    private var serial = 0
    private fun numbered(data: OrderFormData) = orders.create(data, OrderNumber.formatFy(day, ++serial))

    @BeforeTest
    fun setUp() = runBlocking {
        cloud = FakeCloud()
        db = TestDb.fresh()
        kv = Kv(db)
        config = SupabaseConfig(cloud.url, "publishable")
        auth = SupabaseAuth(config, SessionStore(kv))
        auth.signIn("shubham@ota.com", "x")
        val numbers = Numbers(db, kv) { day }
        catalog = CatalogStore(db)
        orders = OrderStore(db)
        payments = PaymentStore(db)
        engine = SyncEngine(db, kv, auth, PostgrestClient(config, auth), catalog, orders, payments, numbers)
    }

    @AfterTest
    fun tearDown() { SyncIds.testMode = false; cloud.close() }

    private fun count(sql: String) = db.read { c -> c.one(sql) { it.getInt(1) } }!!

    private fun order(buyer: String = "Ram Traders") = OrderFormData(
        date = "10-11-2026", buyer = buyer, supplier = "Shiv Mills", items = listOf(OrderItem("1", "Shirting", "100", "120", "M"))
    )

    private fun cloudOrder(id: String, no: String) = JSONObject().put("id", id).put("order_no", no).put("order_date", "10-11-2026")
        .put("buyer", "Phone Buyer").put("supplier", "Shiv Mills").put("items", JSONArray().put(JSONArray().put("1").put("Suiting").put("5").put("900").put("T")))
        .put("remarks", "").put("delivery_ready", "").put("transport_to", "").put("cash_payment", "").put("less_disc", "").put("side", "")
        .put("deleted", false)

    @Test
    fun roundPullsPushesAndNumbersOfflineOrders() = runBlocking {
        cloud.put("buyers", JSONObject().put("id", "11111111-1111-4111-8111-111111111111").put("name", "Ram Traders").put("city", "Surat").put("deleted", false))
        // An old-format order from a phone keeps its number.
        cloud.put("orders", cloudOrder("22222222-2222-4222-8222-222222222222", "120261107001"))
        val online = orders.create(order(), engine.allocateOrderNo(day)!!)
        assertEquals("26270001", online.orderNo)
        // Saved while offline: no number yet, and never uploaded without one.
        val offline = orders.create(order(), "")
        val gone = orders.create(order(), "")
        orders.delete(gone.id)
        assertTrue(OrderNumber.isPending(offline.orderNo))
        assertEquals(listOf(online.id), LocalSyncTable.ORDERS.pending(db, 10).map { it.id })

        val status = engine.sync()
        assertNull(status.error)
        assertEquals(0, status.pending)
        assertEquals(3, engine.cachedStaff()!!.code)
        assertEquals(4.0, status.usage!!.percent)
        assertEquals(1, catalog.count(com.example.otaapplication.data.PartyType.BUYER))
        assertEquals("26270002", orders.get(offline.id)!!.orderNo)
        assertTrue(status.notices.any { it.contains("now order 26270002") })
        val numbers = cloud.table("orders").values.map { it.getString("order_no") }.sorted()
        assertEquals(listOf("120261107001", "26270001", "26270002"), numbers)
        assertTrue(cloud.table("orders").values.none { it.getString("id") == gone.id }, "a pending order deleted before numbering never uploads")
        // Units travel in the compact item arrays.
        assertEquals("T", orders.get("22222222-2222-4222-8222-222222222222")!!.data.items.single().unit)

        // A second round with nothing new sends nothing.
        val before = cloud.requests.count { it.startsWith("POST /rest/v1/orders") }
        engine.sync()
        assertEquals(before, cloud.requests.count { it.startsWith("POST /rest/v1/orders") })
    }

    @Test
    fun yearLimitKeepsOfflineOrdersPending() = runBlocking {
        cloud.orderLimitReached = true
        assertFailsWith<OrderLimitReached> { engine.allocateOrderNo(day) }
        val offline = orders.create(order(), "")
        val status = engine.sync()
        assertTrue(status.notices.any { it.contains("9999") })
        assertTrue(OrderNumber.isPending(orders.get(offline.id)!!.orderNo))
        assertTrue(cloud.table("orders").isEmpty())
        assertEquals(1, status.pending)

        // Signed out: the editor saves the order as pending.
        auth.signOut()
        assertNull(engine.allocateOrderNo(day))
    }

    @Test
    fun standaloneBillsAndClaimsSync() = runBlocking {
        val bill = orders.saveBill(
            null, "SB-1",
            BillDetails(amount = 1000.0, buyer = "Ram Traders\nSurat", supplier = "Shiv Mills", items = listOf(OrderItem("1", "Shirting", "25", "40", "M")), transport = "VRL Logistics"),
            emptyList()
        )
        orders.saveBill(null, "SB-2", BillDetails(amount = 50.0, buyer = "Ram Traders", supplier = "Shiv Mills"), emptyList())
        payments.save(
            com.example.otaapplication.data.AdviceDraft(
                payDate = day, buyer = "Ram Traders", supplier = "Shiv Mills", mode = com.example.otaapplication.data.PaymentMode.ONLINE, reference = "",
                balanceAmount = null, balanceSide = null, remarks = "",
                lines = listOf(bill.id to com.example.otaapplication.data.LineAmounts(paid = 900.0, claim = 100.0, claimNote = "2 than defective"))
            ),
            "26270001"
        )
        assertNull(engine.sync().error)
        val row = cloud.table("bills").values.single { it.getString("bill_no") == "SB-1" }
        assertEquals("VRL Logistics", row.getString("transport"))
        assertTrue(cloud.table("bills").values.single { it.getString("bill_no") == "SB-2" }.isNull("transport"))
        assertEquals("Ram Traders\nSurat", row.getString("buyer"))
        assertEquals("Shiv Mills", row.getString("supplier"))
        assertEquals(1, row.getJSONArray("items").length())
        val line = cloud.table("advice_lines").values.single()
        assertEquals(100.0, line.getDouble("claim"))
        assertEquals("2 than defective", line.getString("claim_note"))
        assertEquals(com.example.otaapplication.data.PaymentStatus.PAID, orders.bill(bill.id)!!.status)
    }

    @Test
    fun offlineAdvicesGetTheirFyNumberBeforeUploading() = runBlocking {
        val bill = orders.saveBill(null, "PB-1", BillDetails(amount = 1000.0, buyer = "Ram Traders", supplier = "Shiv Mills"), emptyList())
        fun draft(paid: Double) = com.example.otaapplication.data.AdviceDraft(
            payDate = day, buyer = "Ram Traders", supplier = "Shiv Mills", mode = com.example.otaapplication.data.PaymentMode.CASH, reference = "",
            balanceAmount = null, balanceSide = null, remarks = "", lines = listOf(bill.id to com.example.otaapplication.data.LineAmounts(paid = paid))
        )
        val online = payments.save(draft(100.0), engine.allocateAdviceNo(day)!!)
        assertEquals("26270001", online.adviceNo)
        val offline = payments.save(draft(200.0))
        val gone = payments.save(draft(50.0))
        payments.delete(gone.id)
        assertTrue(com.example.otaapplication.AdviceNumber.isPending(offline.adviceNo))
        // Neither the pending advice nor its line is pushable until it has a number.
        assertEquals(listOf(online.id), LocalSyncTable.ADVICES.pending(db, 10).map { it.id })
        assertTrue(LocalSyncTable.ADVICE_LINES.pending(db, 10).none { it.value.adviceId == offline.id })

        val status = engine.sync()
        assertNull(status.error)
        assertEquals(0, status.pending)
        assertEquals("26270002", payments.advice(offline.id)!!.adviceNo)
        assertTrue(status.notices.any { it.contains("now PA-26270002") })
        assertEquals(listOf("26270001", "26270002"), cloud.table("payment_advices").values.map { it.getString("advice_no") }.sorted())
        assertTrue(cloud.table("payment_advices").values.none { it.getString("id") == gone.id })
        assertEquals(2, cloud.table("advice_lines").size)
        // Orders keep their own counter.
        assertTrue(cloud.orderSerials.isEmpty())
    }

    @Test
    fun maintenanceArchivesThenPurgesButKeepsEverythingLocally() = runBlocking {
        val o = numbered(order())
        orders.attachBill(listOf(o.id), "B-1", BillDetails(amount = 500.0))
        cloud.put("orders", cloudOrder("22222222-2222-4222-8222-222222222222", "120261110001"))

        engine.sync(maintenance = true)
        val marks = cloud.requests.filter { it.contains("ota_mark_archived") }
        assertTrue(marks.isNotEmpty())
        assertTrue(cloud.requests.any { it.contains("ota_purge_archived_orders") })
        assertTrue(cloud.table("orders").isEmpty(), "archived orders were purged from the cloud")
        assertTrue(cloud.table("bills").isEmpty())
        assertEquals(2, orders.list().size, "the laptop keeps every order")
        assertEquals(1, orders.billsForOrder(o.id).size)
        assertTrue(engine.status.value.lastPurge!!.contains("2 orders"))

        // Purged rows just stop arriving; nothing is deleted locally on the next round.
        engine.sync()
        assertEquals(2, orders.list().size)
    }

    @Test
    fun nonAdminNeverArchivesOrPurges() = runBlocking {
        // Admin is decided by the sign-in email; a staff row flagged is_admin doesn't make someone admin.
        auth.signIn("staff@ota.com", "x")
        assertEquals(false, engine.isAdmin())
        numbered(order())
        engine.sync(maintenance = true)
        assertTrue(cloud.requests.none { it.contains("ota_mark_archived") || it.contains("ota_purge") })
        assertEquals(1, cloud.table("orders").size)
    }

    @Test
    fun fullCloudKeepsChangesLocalAndRetries() = runBlocking {
        cloud.failWrites = 402 to null
        numbered(order())
        val failed = engine.sync()
        assertTrue(failed.storageFull)
        assertEquals(SyncEngine.STORAGE_FULL, failed.error)
        assertEquals(1, failed.pending)
        assertEquals(1, orders.list().size)

        cloud.failWrites = null
        val ok = engine.sync()
        assertNull(ok.error)
        assertEquals(0, ok.pending)
        assertEquals(1, cloud.table("orders").size)
    }

    @Test
    fun onlyAdminsChangeRetention() = runBlocking {
        val settings = CloudSettings(config, auth)
        settings.update(15, 500, 80)
        assertEquals(15, cloud.settings.getInt("retention_days"))
        cloud.staff = JSONObject().put("code", 4).put("is_admin", false)
        assertFailsWith<CloudException> { settings.update(7, 500, 80) }
        assertEquals(15, cloud.settings.getInt("retention_days"))
    }

    @Test
    fun adminFollowsTheSignInEmail() = runBlocking {
        assertTrue(engine.isAdmin())
        auth.signIn("varun@ota.com", "x"); assertTrue(engine.isAdmin())
        auth.signIn("testuser1@ota.com", "x"); assertEquals(false, engine.isAdmin())
    }

    @Test
    fun testLoginKeepsRealDataSideBySideAndHidden() = runBlocking {
        val real = numbered(order())
        assertNull(engine.sync().error)
        engine.sync() // pulls the pushed row back, which sets the cursors
        val chart = count("select count(*) from acc_accounts")
        val cursors = count("select count(*) from kv where key like 'cursor.%'")
        assertTrue(cursors > 0, "cursors")

        auth.signIn("testuser1@ota.com", "x")
        engine.adoptDataMode("testuser1@ota.com")
        assertEquals(OtaAccounts.MODE_TEST, engine.dataMode())
        assertTrue(SyncIds.testMode && db.schema == Db.TEST)
        assertTrue(orders.list().isEmpty(), "real orders are hidden from the test login")
        assertEquals(0, count("select count(*) from kv where key like 'cursor.%'"), "the test set syncs from scratch")
        assertEquals(chart, count("select count(*) from acc_accounts"), "the test books get their own default chart")
        // The cloud only shows the test login its test rows.
        cloud.table("orders").clear()
        cloud.put("orders", cloudOrder("33333333-3333-4333-8333-333333333333", "26279001"))
        engine.sync()
        val pendingTest = numbered(order("Test Buyer"))
        engine.refreshPending()
        assertEquals(2, orders.list().size)
        assertEquals(1, engine.status.value.pending, "the test change waits for the next test login")

        auth.signIn("shubham@ota.com", "x")
        engine.adoptDataMode("shubham@ota.com")
        assertEquals(false, SyncIds.testMode)
        assertEquals(listOf(real.id), orders.list().map { it.id }, "real data is untouched and back")
        assertEquals(cursors, count("select count(*) from kv where key like 'cursor.%'"), "real cursors kept")
        assertEquals(0, engine.status.value.pending)
        assertEquals(1, count("select count(*) from ota_test.orders where id = '${pendingTest.id}'"), "test data kept, hidden")
    }

    @Test
    fun aTestLaptopFromBefore170MovesItsDataIntoTheTestSet() {
        numbered(order())
        db.read { c -> c.createStatement().use { it.execute("drop schema ota_test cascade") } }
        Kv(db)["data.mode"] = OtaAccounts.MODE_TEST
        Kv(db)["cursor.orders"] = "42"
        Migrations.run(db)
        assertEquals(0, count("select count(*) from public.orders"))
        assertEquals(1, count("select count(*) from ota_test.orders"))
        assertEquals("42", db.read { c -> c.one("select value from ota_test.kv where key = 'cursor.orders'") { it.getString(1) } })
        assertEquals(0, count("select count(*) from public.kv where key = 'cursor.orders'"))
        assertTrue(count("select count(*) from ota_test.acc_accounts") > 0)
    }

    @Test
    fun onlyTheTestAccountCanClearAndItEmptiesCloudAndLaptop() = runBlocking {
        assertFailsWith<IllegalStateException> { engine.clearTestData() }
        assertEquals(0, cloud.clearCalls)

        auth.signIn("testuser1@ota.com", "x")
        engine.adoptDataMode("testuser1@ota.com")
        val o = numbered(order())
        orders.attachBill(listOf(o.id), "T-1", BillDetails(amount = 100.0))
        engine.sync()
        assertEquals(1, cloud.table("orders").size)

        val counts = engine.clearTestData()
        assertEquals(1, cloud.clearCalls)
        assertEquals(1, counts["orders"])
        assertTrue(cloud.table("orders").isEmpty() && cloud.table("bills").isEmpty())
        assertTrue(orders.list().isEmpty())
        assertEquals(0, count("select count(*) from bills"))
        assertEquals(0, engine.status.value.pending)
        assertEquals(OtaAccounts.MODE_TEST, engine.dataMode(), "still the test laptop")
    }

    /** A second device (another laptop) on the same cloud. */
    private inner class Device {
        val db = TestDb.fresh()
        val kv = Kv(db)
        val auth = SupabaseAuth(config, SessionStore(kv)).also { runBlocking { it.signIn("varun@ota.com", "x") } }
        val catalog = CatalogStore(db)
        val orders = OrderStore(db)
        val payments = PaymentStore(db)
        val engine = SyncEngine(db, kv, auth, PostgrestClient(config, auth), catalog, orders, payments, Numbers(db, kv) { day })
    }

    @Test
    fun hardDeletesReachOtherDevicesAndNeverComeBack() = runBlocking {
        val buyer = catalog.save(com.example.otaapplication.data.Party(type = com.example.otaapplication.data.PartyType.BUYER, name = "Ram Traders"))
        val kept = orders.create(order(), engine.allocateOrderNo(day)!!)
        val doomed = orders.create(order(), engine.allocateOrderNo(day)!!)
        val bill = orders.attachBill(listOf(doomed.id), "B-77")
        assertNull(engine.sync().error)
        val other = Device()
        assertNull(other.engine.sync().error)
        assertNotNull(other.orders.get(doomed.id))
        assertNotNull(other.orders.bill(bill.id))

        // The other laptop edits the order offline, before this laptop deletes it: the later delete wins.
        other.orders.update(doomed.id, order(buyer = "Edited offline"))
        Thread.sleep(5)
        orders.delete(doomed.id)
        catalog.delete(com.example.otaapplication.data.PartyType.BUYER, buyer.id)
        assertNull(orders.bill(bill.id), "a bill left with no order and no parties goes with it")
        assertEquals(0, count("select count(*) from order_bills"))
        engine.refreshPending()
        assertEquals(3, engine.status.value.pending, "order, its bill and the buyer (the cloud removes the link with them)")
        assertNull(engine.sync().error)
        assertEquals(0, engine.status.value.pending)
        // Gone from the cloud, with only a small tombstone each.
        assertNull(cloud.table("orders")[doomed.id])
        assertNull(cloud.table("bills")[bill.id])
        assertNull(cloud.table("buyers")[buyer.id])
        assertTrue(cloud.table("order_bills").isEmpty())
        assertEquals(setOf(doomed.id, bill.id, buyer.id, SyncIds.link(doomed.id, bill.id)), cloud.table("ota_deletions").keys)

        // The other laptop's next sync removes them there too, and its stale edit is not uploaded.
        assertNull(other.engine.sync().error)
        assertNull(other.orders.get(doomed.id))
        assertNull(other.orders.bill(bill.id))
        assertEquals(0, other.catalog.count(com.example.otaapplication.data.PartyType.BUYER))
        assertNotNull(other.orders.get(kept.id))
        assertNull(cloud.table("orders")[doomed.id], "no resurrection")
        assertEquals(0, other.engine.status.value.pending)

        // An old app pushing its stale copy (edited before the delete) is turned away by the cloud.
        val staleRow = cloudOrder(doomed.id, doomed.orderNo).put("client_updated_at", 1L)
        val code = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("${cloud.url}/rest/v1/orders?on_conflict=id"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSONArray().put(staleRow).toString())).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        ).statusCode()
        assertEquals(201, code)
        assertNull(cloud.table("orders")[doomed.id])

        // Re-created after the delete (same bill number = same id): the newer row wins everywhere.
        Thread.sleep(5)
        val again = orders.attachBill(listOf(kept.id), "B-77")
        assertEquals(bill.id, again.id)
        assertNull(engine.sync().error)
        assertNotNull(cloud.table("bills")[bill.id])
        assertNull(cloud.table("ota_deletions")[bill.id])
        assertNull(other.engine.sync().error)
        assertEquals(listOf("B-77"), other.orders.billsForOrder(kept.id).map { it.billNo })
    }

    @Test
    fun deletesWaitUntilTheCloudHasHardDeletes() = runBlocking {
        val o = orders.create(order(), engine.allocateOrderNo(day)!!)
        assertNull(engine.sync().error)
        cloud.hardDeletes = false
        orders.delete(o.id)
        val status = engine.sync()
        assertNull(status.error, "an older cloud is not an error")
        assertEquals(1, status.pending)
        assertNotNull(cloud.table("orders")[o.id])
        cloud.hardDeletes = true
        assertEquals(0, engine.sync().pending)
        assertNull(cloud.table("orders")[o.id])
    }

    @Test
    fun anEditMadeAfterADeleteIsKept() = runBlocking {
        val o = orders.create(order(), engine.allocateOrderNo(day)!!)
        assertNull(engine.sync().error)
        // Another device edits the order in the cloud after this laptop's delete time.
        orders.delete(o.id)
        val newer = JSONObject(cloud.table("orders")[o.id]!!.toString()).put("buyer", "Edited later").put("client_updated_at", System.currentTimeMillis() + 60_000)
        cloud.put("orders", newer)
        assertNull(engine.sync().error)
        assertEquals("Edited later", orders.get(o.id)!!.data.buyer, "the cloud kept the newer edit and this laptop pulled it back")
        assertTrue(cloud.table("ota_deletions").isEmpty())
    }

}
