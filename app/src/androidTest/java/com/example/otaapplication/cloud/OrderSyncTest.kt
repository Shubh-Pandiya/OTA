package com.example.otaapplication.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.otaapplication.AdviceNumberAllocator
import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumberAllocator
import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OrderHistoryStore
import com.example.otaapplication.data.OrderJson
import com.example.otaapplication.data.OrderQuery
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.PaymentMode
import com.example.otaapplication.data.PaymentRepository
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory Supabase orders/bills/order_bills/payment_advices/advice_lines, following the SQL rules (LWW trigger, unique order_no, no FKs). */
private class OrderCloudFake : OrderCloudApi {
    val tables = mapOf(
        "orders" to linkedMapOf<String, JSONObject>(), "bills" to linkedMapOf(), "order_bills" to linkedMapOf(),
        "payment_advices" to linkedMapOf(), "advice_lines" to linkedMapOf()
    )
    /** Simulates a read-only / over-quota project. */
    var full = false
    private var tick = 0L
    private val numberPattern = Regex("^[1-9]20[0-9]{6}[0-9]+$")
    private val adviceNumberPattern = Regex("^PA[1-9]20[0-9]{6}[0-9]+$")

    private fun nextServerTime() = Instant.parse("2026-11-07T10:00:00Z").plusNanos(++tick * 1_000).toString()

    override suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>> {
        val afterInstant = after?.let { Instant.parse(it.replace("+00:00", "Z")) }
        return tables.getValue(table.name).values
            .sortedWith(compareBy({ Instant.parse(it.getString("updated_at")) }, { it.getString("id") }))
            .filter { row ->
                val t = Instant.parse(row.getString("updated_at"))
                afterInstant == null || t.isAfter(afterInstant) || (afterId != null && t == afterInstant && row.getString("id") > afterId)
            }
            .take(limit)
            .map { table.parse(JSONObject(it.toString())) }
    }

    override suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>) {
        if (full) throw CloudException("cannot execute INSERT in a read-only transaction", 405, code = "25006")
        val target = tables.getValue(table.name)
        val incoming = rows.map(table.toJson)
        val unique = when (table.name) {
            "orders" -> "order_no" to numberPattern
            "payment_advices" -> "advice_no" to adviceNumberPattern
            else -> null
        }
        if (unique != null) {
            val (column, pattern) = unique
            // The whole request fails, like PostgREST, when any number is taken by another row.
            for (json in incoming) {
                val no = json.getString(column)
                if (no.matches(pattern) && target.values.any { it.getString(column) == no && it.getString("id") != json.getString("id") }) {
                    throw CloudException("duplicate key value violates unique constraint \"${table.name}_${column}_key\"", 409, code = "23505")
                }
            }
        }
        for (json in incoming) {
            val existing = target[json.getString("id")]
            if (existing != null && json.getLong("client_updated_at") < existing.getLong("client_updated_at")) continue
            target[json.getString("id")] = json.put("updated_at", nextServerTime())
        }
    }

    override suspend fun staff(userId: String): StaffInfo? = StaffInfo(1, "Test")

    /** What the desktop's archive + purge does to the cloud copy. */
    fun purge(table: String, id: String) { tables.getValue(table).remove(id) }

    fun edit(table: String, id: String, clientUpdatedAt: Long, change: (JSONObject) -> Unit) {
        val row = tables.getValue(table).getValue(id)
        change(row)
        row.put("client_updated_at", clientUpdatedAt).put("updated_at", nextServerTime())
    }
}

private class OrderCursors : CursorStore {
    val values = mutableMapOf<String, String?>()
    override fun get(table: String) = values[table]
    override fun set(table: String, value: String?) { values[table] = value }
}

/** One simulated phone: its own history folder, database, number counter and clock. */
private class OrderPhone(cloud: OrderCloudApi, var now: Long, val day: LocalDate = LocalDate.of(2026, 11, 7)) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir: File = File(context.cacheDir, "phone-" + UUID.randomUUID()).apply { mkdirs() }
    val db = OtaDatabase(context, name = null)
    val repo = OrderRepository(db) { now }
    val local = LocalOrders(OrderHistoryStore(dir), repo)
    private val prefsName = "order-test-" + UUID.randomUUID()
    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val numbers = OrderNumberAllocator(prefs, local.history, repo) { day }
    val payments = PaymentRepository(db) { now }
    val adviceNumbers = AdviceNumberAllocator(prefs, payments) { day }
    val sync = OrderSync(
        local, cloud, OrderCursors(), numbers::replacementFor,
        payments = payments, renumberAdvice = adviceNumbers::replacementFor, pageSize = 2, pushBatch = 2
    )

    /** Saves a new advice the way the editor does. */
    fun newAdvice(code: Int, lines: List<Pair<String, LineAmounts>>, reference: String = "CHQ 1"): String =
        adviceNumbers.assign(code) { no ->
            payments.save(AdviceDraft(null, day, "Ram", "Raymond", PaymentMode.CHEQUE, reference, null, null, "", lines), no)
        }!!.id

    /** Saves a new order the way the form does: number from the allocator, entry in the history. */
    fun newOrder(code: Int, buyer: String, particulars: String = "Shirting"): String {
        val uuid = UUID.randomUUID().toString()
        numbers.assign(code) { offerNo ->
            local.history.upsert(OrderJson.historyEntry(order(offerNo, buyer, particulars), uuid, "", null))
            local.reindex()
        }
        return uuid
    }

    fun edit(uuid: String, change: (OrderFormData) -> OrderFormData) {
        local.history.upsert(OrderJson.historyEntry(change(local.get(uuid)!!), uuid, "", null))
        local.reindex()
    }

    fun syncNow() = runBlocking { sync.syncAll() }

    fun close() {
        db.close()
        dir.deleteRecursively()
        context.deleteSharedPreferences(prefsName)
    }

    companion object {
        fun order(offerNo: String, buyer: String, particulars: String) = OrderFormData(
            date = "07-11-2026", offerNo = offerNo, buyer = buyer, supplier = "Raymond\nMumbai",
            items = listOf(OrderItem("1", particulars, "120 m", "245"), OrderItem("2", "Suiting", "60 m", "410")),
            remarks = "Urgent"
        )
    }
}

@RunWith(AndroidJUnit4::class)
class OrderSyncTest {
    private lateinit var cloud: OrderCloudFake
    private lateinit var a: OrderPhone
    private lateinit var b: OrderPhone

    @Before
    fun setUp() {
        cloud = OrderCloudFake()
        a = OrderPhone(cloud, now = 10_000)
        b = OrderPhone(cloud, now = 10_000)
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    // Item keys are UI-only and differ per load; compare what is saved.
    private fun content(phone: OrderPhone, uuid: String) = OrderJson.historyEntry(phone.local.get(uuid)!!, uuid, "", null).toString()

    private fun billsOf(phone: OrderPhone, uuid: String) = phone.repo.billsForOrder(uuid).map { it.billNo }.sorted()

    @Test
    fun ordersAndBillsReachTheOtherPhone() {
        val o1 = a.newOrder(1, "Ram Textiles\nIndore")
        val o2 = a.newOrder(1, "Shyam Garments")
        val o3 = a.newOrder(1, "Ram Textiles\nIndore", particulars = "Linen")
        a.repo.attachBill(listOf(o1, o2), "INV 1")
        a.repo.attachBill(listOf(o1), "INV 2", BillDetails(LocalDate.of(2026, 11, 20), 12_500.0))
        assertEquals(3 + 2 + 3, a.repo.pendingCount())

        a.syncNow()
        assertEquals(0, a.repo.pendingCount())
        assertEquals(3, cloud.tables.getValue("orders").size)
        assertEquals(2, cloud.tables.getValue("bills").size)
        assertEquals(3, cloud.tables.getValue("order_bills").size)
        assertEquals(listOf("120261107001", "120261107002", "120261107003"), cloud.tables.getValue("orders").values.map { it.getString("order_no") }.sorted())
        // Compact item arrays only.
        assertEquals("[[\"1\",\"Shirting\",\"120 m\",\"245\"],[\"2\",\"Suiting\",\"60 m\",\"410\"]]",
            cloud.tables.getValue("orders").getValue(o1).getJSONArray("items").toString())

        val pulled = b.syncNow()
        assertTrue(pulled.pulledChanges >= 8)
        assertEquals(content(a, o1), content(b, o1))
        assertEquals(content(a, o3), content(b, o3))
        assertEquals(listOf("INV 1", "INV 2"), billsOf(b, o1))
        assertEquals(listOf("INV 1"), billsOf(b, o2))
        assertEquals(setOf(o1, o2), b.repo.search(OrderQuery(text = "inv 1")).map { it.uuid }.toSet())
        assertEquals(setOf(o1, o3), b.repo.search(OrderQuery(text = "ram")).map { it.uuid }.toSet())
        // Pulled rows are not uploaded back.
        assertEquals(0, b.repo.pendingCount())
        assertEquals(0, b.syncNow().pushed)
    }

    @Test
    fun sameBillTypedOnTwoPhonesIsOneRow() {
        val o1 = a.newOrder(1, "Ram")
        a.syncNow(); b.syncNow()
        a.repo.attachBill(listOf(o1), "inv  9")
        b.repo.attachBill(listOf(o1), "INV 9")
        a.syncNow(); b.syncNow(); a.syncNow()
        assertEquals(1, cloud.tables.getValue("bills").size)
        assertEquals(1, cloud.tables.getValue("order_bills").size)
        assertEquals(1, a.repo.billsForOrder(o1).size)
        assertEquals(1, b.repo.billsForOrder(o1).size)
    }

    @Test
    fun newestEditWinsAndDeletesPropagate() {
        val o1 = a.newOrder(1, "Ram")
        a.syncNow(); b.syncNow()

        b.now = 20_000; b.edit(o1) { it.copy(remarks = "B edit") }
        a.now = 30_000; a.edit(o1) { it.copy(remarks = "A edit (newer)") }
        b.syncNow(); a.syncNow(); b.syncNow()
        assertEquals("A edit (newer)", a.local.get(o1)!!.remarks)
        assertEquals("A edit (newer)", b.local.get(o1)!!.remarks)

        // An edit made elsewhere (another device or the desktop) is pulled.
        cloud.edit("orders", o1, 40_000) { it.put("buyer", "Ram Textiles\nIndore") }
        a.syncNow()
        assertEquals("Ram Textiles\nIndore", a.local.get(o1)!!.buyer)

        a.now = 50_000
        a.local.delete(o1, "", null)
        a.syncNow(); b.syncNow()
        assertTrue(cloud.tables.getValue("orders").getValue(o1).getBoolean("deleted"))
        assertNull(b.local.get(o1))
        assertTrue(b.repo.search(OrderQuery()).isEmpty())
        // The deleted number is never handed out again.
        assertEquals("120261107002", b.numbers.peek(1))
    }

    @Test
    fun numberTakenOnAnotherPhoneIsRenumbered() {
        // Same staff login, two phones, both offline: both pick 120261107001.
        val fromA = a.newOrder(1, "Ram")
        val fromB = b.newOrder(1, "Shyam")
        assertEquals(a.local.get(fromA)!!.offerNo, b.local.get(fromB)!!.offerNo)

        a.syncNow()
        val result = b.syncNow()
        assertEquals(1, result.notices.size)
        assertEquals("120261107002", b.local.get(fromB)!!.offerNo)
        assertEquals(0, b.repo.pendingCount())
        assertEquals(setOf("120261107001", "120261107002"), cloud.tables.getValue("orders").values.map { it.getString("order_no") }.toSet())
        // B's counter moved on too, and other staff codes are untouched.
        assertEquals("120261107003", b.numbers.peek(1))
        assertEquals("220261107001", b.numbers.peek(2))
    }

    @Test
    fun billForAnOrderAlreadyPurgedFromTheCloudStillUploads() {
        val o1 = a.newOrder(1, "Ram")
        val o2 = a.newOrder(1, "Shyam")
        a.repo.attachBill(listOf(o1), "INV 5")
        a.syncNow(); b.syncNow()

        // The desktop archived and purged everything; phones keep their copies.
        listOf("orders" to o1, "orders" to o2).forEach { (t, id) -> cloud.purge(t, id) }
        cloud.tables.getValue("bills").keys.toList().forEach { cloud.purge("bills", it) }
        cloud.tables.getValue("order_bills").keys.toList().forEach { cloud.purge("order_bills", it) }
        a.syncNow(); b.syncNow()
        assertNotNull(a.local.get(o1)); assertNotNull(b.local.get(o2))
        assertEquals(listOf("INV 5"), billsOf(b, o1))

        // Weeks later the bill for o2 arrives; it is the same bill number, attached to the old order.
        a.now = 99_000
        a.repo.attachBill(listOf(o2), "INV 5")
        a.syncNow()
        assertEquals(1, cloud.tables.getValue("bills").size) // re-uploaded although unchanged
        assertEquals(1, cloud.tables.getValue("order_bills").size)
        assertEquals(0, a.repo.pendingCount())
        b.syncNow()
        assertEquals(listOf("INV 5"), billsOf(b, o2))
        assertEquals(listOf("INV 5"), billsOf(b, o1))
    }

    @Test
    fun fullCloudKeepsChangesPendingUntilThereIsSpace() {
        val o1 = a.newOrder(1, "Ram")
        a.repo.attachBill(listOf(o1), "INV 7")
        cloud.full = true
        try {
            a.syncNow()
            fail("expected the full cloud to refuse the upload")
        } catch (e: CloudException) {
            assertTrue(e.storageFull)
        }
        assertEquals(3, a.repo.pendingCount())
        assertTrue(cloud.tables.values.all { it.isEmpty() })

        cloud.full = false
        a.syncNow()
        assertEquals(0, a.repo.pendingCount())
        assertEquals(1, cloud.tables.getValue("orders").size)
        assertNotEquals(0, cloud.tables.getValue("order_bills").size)
    }

    @Test
    fun advicesAndLinesReachTheOtherPhone() {
        val o1 = a.newOrder(1, "Ram")
        val o2 = a.newOrder(1, "Ram")
        val inv1 = a.repo.attachBill(listOf(o1, o2), "INV 1", BillDetails(LocalDate.of(2026, 11, 7), 10_000.0, lrNo = "LR 5"))
        val inv2 = a.repo.attachBill(listOf(o2), "INV 2", BillDetails(LocalDate.of(2026, 11, 7), 4_000.0))
        val first = a.newAdvice(1, listOf(inv1.id to LineAmounts(paid = 6_000.0, cdAmount = 200.0), inv2.id to LineAmounts(paid = 4_000.0)))
        a.now = 11_000
        val second = a.newAdvice(1, listOf(inv1.id to LineAmounts(paid = 3_800.0)), reference = "UTR 9")
        assertEquals(2 + 3, a.payments.pendingCount())

        a.syncNow()
        assertEquals(0, a.payments.pendingCount())
        assertEquals(0, a.repo.pendingCount())
        assertEquals(setOf("PA120261107001", "PA120261107002"), cloud.tables.getValue("payment_advices").values.map { it.getString("advice_no") }.toSet())
        assertEquals(3, cloud.tables.getValue("advice_lines").size)
        assertEquals("LR 5", cloud.tables.getValue("bills").getValue(inv1.id).getString("lr_no"))

        b.syncNow()
        assertEquals(PaymentStatus.PAID, b.repo.bill(inv1.id)!!.status)
        assertEquals(PaymentStatus.PAID, b.repo.bill(inv2.id)!!.status)
        assertEquals(listOf("PA120261107001", "PA120261107002"), b.payments.advicesForBill(inv1.id).map { it.adviceNo })
        assertEquals(listOf(second), b.payments.search("utr").map { it.id })
        assertEquals(0, b.payments.pendingCount())
        // B's advice counter moved past A's numbers.
        assertEquals("PA120261107003", b.adviceNumbers.peek(1))

        // Deleting on A reaches B and frees the bills there.
        a.now = 12_000
        a.payments.delete(first)
        a.syncNow(); b.syncNow()
        assertNull(b.payments.advice(first))
        assertEquals(PaymentStatus.PARTLY_PAID, b.repo.bill(inv1.id)!!.status)
        assertEquals(PaymentStatus.UNPAID, b.repo.bill(inv2.id)!!.status)
    }

    @Test
    fun adviceNumberTakenOnAnotherPhoneIsRenumbered() {
        val o1 = a.newOrder(1, "Ram")
        val inv = a.repo.attachBill(listOf(o1), "INV 1", BillDetails(null, 1_000.0))
        a.syncNow(); b.syncNow()
        val fromA = a.newAdvice(1, listOf(inv.id to LineAmounts(paid = 100.0)))
        val fromB = b.newAdvice(1, listOf(inv.id to LineAmounts(paid = 200.0)))
        assertEquals(a.payments.advice(fromA)!!.adviceNo, b.payments.advice(fromB)!!.adviceNo)

        a.syncNow()
        val result = b.syncNow()
        assertEquals(1, result.notices.size)
        assertEquals("PA120261107002", b.payments.advice(fromB)!!.adviceNo)
        assertEquals(0, b.payments.pendingCount())
        a.syncNow()
        assertEquals(700.0, a.repo.bill(inv.id)!!.balance!!, 0.0)
    }

    @Test
    fun paymentForABillPurgedFromTheCloudReuploadsTheBill() {
        val o1 = a.newOrder(1, "Ram")
        val inv = a.repo.attachBill(listOf(o1), "INV 8", BillDetails(null, 1_000.0))
        a.syncNow()
        cloud.purge("orders", o1)
        cloud.purge("bills", inv.id)
        cloud.tables.getValue("order_bills").keys.toList().forEach { cloud.purge("order_bills", it) }

        a.now = 50_000
        a.newAdvice(1, listOf(inv.id to LineAmounts(paid = 1_000.0)))
        a.syncNow()
        assertEquals(0, a.payments.pendingCount())
        assertTrue(cloud.tables.getValue("bills").containsKey(inv.id))
        assertEquals(1, cloud.tables.getValue("advice_lines").size)
    }

    @Test
    fun itemUnitsRoundTripCompactlyAndOldRowsLoadUnchanged() {
        val items = listOf(
            OrderItem("1", "Cotton", "250", "120", unit = "M"),
            OrderItem("2", "Suiting", "10", "900", unit = "T"),
            OrderItem("3", "Old row", "5 kg", "40")
        )
        val cloud = OrderTable.itemsToJson(items)
        assertEquals("""[["1","Cotton","250","120","M"],["2","Suiting","10","900","T"],["3","Old row","5 kg","40"]]""", cloud.toString())
        assertEquals(items.map { it.copy(key = 0) }, OrderTable.itemsFromJson(cloud).map { it.copy(key = 0) })

        val oldCloudRow = org.json.JSONArray("""[["1","Poly","20m","250"]]""")
        assertEquals(listOf(OrderItem("1", "Poly", "20m", "250", key = 0)), OrderTable.itemsFromJson(oldCloudRow).map { it.copy(key = 0) })

        val data = OrderFormData(date = "08-11-2026", offerNo = "1", items = items)
        val local = OrderJson.historyEntry(data, "u", "", null)
        assertEquals(items.map { it.copy(key = 0) }, OrderJson.parse(local).items.map { it.copy(key = 0) })
        assertTrue(!local.getJSONArray("items").getJSONObject(2).has("unit"))

        // Content keys of orders saved before units existed must not change.
        val old = OrderFormData(date = "08-11-2026", offerNo = "1", items = listOf(OrderItem("1", "Poly", "20m", "250")))
        assertEquals(listOf("08-11-2026", "1", "", "", "", "", "", "", "", "", "1", "Poly", "20m", "250").joinToString("\u0001"), OrderJson.contentKey(old))
    }
}
