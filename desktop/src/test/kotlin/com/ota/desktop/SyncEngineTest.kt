package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
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

    @BeforeTest
    fun setUp() = runBlocking {
        cloud = FakeCloud()
        db = TestDb.fresh()
        kv = Kv(db)
        config = SupabaseConfig(cloud.url, "publishable")
        auth = SupabaseAuth(config, SessionStore(kv))
        auth.signIn("test@ota.com", "x")
        val numbers = Numbers(db, kv) { LocalDate.of(2026, 11, 10) }
        catalog = CatalogStore(db)
        orders = OrderStore(db, numbers)
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
    fun roundPullsPushesAndRenumbersClashes() = runBlocking {
        cloud.put("buyers", JSONObject().put("id", "11111111-1111-4111-8111-111111111111").put("name", "Ram Traders").put("city", "Surat").put("deleted", false))
        cloud.put("orders", cloudOrder("22222222-2222-4222-8222-222222222222", "120261110001"))
        // The same staff member used 320261110002 on the phone while the laptop was offline.
        cloud.put("orders", cloudOrder("33333333-3333-4333-8333-333333333333", "320261110002"))
        val a = orders.create(order(), 3)
        val b = orders.create(order(), 3)
        assertEquals("320261110002", b.orderNo)

        val status = engine.sync()
        assertNull(status.error)
        assertEquals(0, status.pending)
        assertEquals(3, engine.cachedStaff()!!.code)
        assertEquals(4.0, status.usage!!.percent)
        assertEquals(1, catalog.count(com.example.otaapplication.data.PartyType.BUYER))
        val numbers = cloud.table("orders").values.map { it.getString("order_no") }.sorted()
        assertEquals(listOf("120261110001", "320261110001", "320261110002", "320261110003"), numbers)
        assertEquals("320261110003", orders.get(b.id)!!.orderNo)
        assertEquals("320261110001", orders.get(a.id)!!.orderNo)
        assertTrue(status.notices.single().contains("now 320261110003"))
        // Units travel in the compact item arrays.
        assertEquals("T", orders.get("22222222-2222-4222-8222-222222222222")!!.data.items.single().unit)

        // A second round with nothing new sends nothing.
        val before = cloud.requests.count { it.startsWith("POST /rest/v1/orders") }
        engine.sync()
        assertEquals(before, cloud.requests.count { it.startsWith("POST /rest/v1/orders") })
    }

    @Test
    fun maintenanceArchivesThenPurgesButKeepsEverythingLocally() = runBlocking {
        val o = orders.create(order(), 3)
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
        orders.create(order(), 4)
        engine.sync(maintenance = true)
        assertTrue(cloud.requests.none { it.contains("ota_mark_archived") || it.contains("ota_purge") })
        assertEquals(1, cloud.table("orders").size)
    }

    @Test
    fun fullCloudKeepsChangesLocalAndRetries() = runBlocking {
        cloud.failWrites = 402 to null
        orders.create(order(), 3)
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
