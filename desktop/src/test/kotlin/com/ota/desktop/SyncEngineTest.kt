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
import com.ota.desktop.sync.CloudSettings
import com.ota.desktop.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        auth.signIn("test@ota.com", "x")
        val numbers = Numbers(db, kv) { day }
        catalog = CatalogStore(db)
        orders = OrderStore(db)
        payments = PaymentStore(db, numbers)
        engine = SyncEngine(db, kv, auth, PostgrestClient(config, auth), catalog, orders, payments, numbers)
    }

    @AfterTest
    fun tearDown() = cloud.close()

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
            BillDetails(amount = 1000.0, buyer = "Ram Traders\nSurat", supplier = "Shiv Mills", items = listOf(OrderItem("1", "Shirting", "25", "40", "M"))),
            emptyList()
        )
        payments.save(
            com.example.otaapplication.data.AdviceDraft(
                payDate = day, buyer = "Ram Traders", supplier = "Shiv Mills", mode = com.example.otaapplication.data.PaymentMode.ONLINE, reference = "",
                balanceAmount = null, balanceSide = null, remarks = "",
                lines = listOf(bill.id to com.example.otaapplication.data.LineAmounts(paid = 900.0, claim = 100.0, claimNote = "2 than defective"))
            ),
            3
        )
        assertNull(engine.sync().error)
        val row = cloud.table("bills").values.single()
        assertEquals("Ram Traders\nSurat", row.getString("buyer"))
        assertEquals("Shiv Mills", row.getString("supplier"))
        assertEquals(1, row.getJSONArray("items").length())
        val line = cloud.table("advice_lines").values.single()
        assertEquals(100.0, line.getDouble("claim"))
        assertEquals("2 than defective", line.getString("claim_note"))
        assertEquals(com.example.otaapplication.data.PaymentStatus.PAID, orders.bill(bill.id)!!.status)
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
        cloud.staff = JSONObject().put("code", 4).put("name", "Test").put("is_admin", false)
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
}
