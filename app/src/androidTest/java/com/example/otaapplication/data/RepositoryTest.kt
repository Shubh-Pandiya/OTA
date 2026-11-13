package com.example.otaapplication.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    private lateinit var db: OtaDatabase
    private lateinit var catalog: CatalogRepository
    private lateinit var orders: OrderRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        db = OtaDatabase(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
        catalog = CatalogRepository(db) { now }
        orders = OrderRepository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun entry(uuid: String, offerNo: String, date: String, buyer: String, supplier: String = "Supplier", particulars: String = "", seq: Int = 0) =
        HistoryIndexEntry(uuid, uuid, offerNo, date, buyer, supplier, particulars, "/sync/$uuid.json", "Order_$seq.pdf", seq)

    private fun seedOrders(): List<HistoryIndexEntry> = listOf(
        entry("a", "2026110701", "07-11-2026", "Ram Textiles\nIndore", "Mafatlal Mills", "Cotton shirting 40s", 0),
        entry("b", "2026110702", "07-11-2026", "Shyam Garments\nUjjain", "Raymond", "Suiting wool blend", 1),
        entry("c", "2026110801", "08-11-2026", "Ram Textiles\nIndore", "Raymond", "Linen shirting", 2)
    ).also { orders.reconcile(it) }

    private fun syncColumns(table: String, id: String): Triple<Long, Int, Int> =
        db.readableDatabase.rawQuery("SELECT updated_at, is_deleted, sync_pending FROM $table WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst(); Triple(it.getLong(0), it.getInt(1), it.getInt(2))
        }

    private fun exists(table: String, id: String): Boolean =
        db.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun queuedDelete(id: String): Boolean =
        db.readableDatabase.rawQuery("SELECT 1 FROM pending_deletions WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    @Test
    fun catalogSaveSuggestEditAndDelete() {
        val ram = catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles", address = "12 MG Road", city = "Indore", phone = "9876543210"))
        catalog.save(Party(type = PartyType.BUYER, name = "Shri Ram Cloth", city = "Ujjain"))
        catalog.save(Party(type = PartyType.SUPPLIER, name = "Raymond"))
        assertTrue(ram.id.isNotBlank())

        // Prefix matches rank first, contains-matches follow; suppliers are separate.
        assertEquals(listOf("Ram Textiles", "Shri Ram Cloth"), catalog.suggest(PartyType.BUYER, "ram").map { it.name })
        assertEquals(listOf("Ram Textiles"), catalog.suggest(PartyType.BUYER, "ram ind").map { it.name })
        assertEquals(listOf("Ram Textiles"), catalog.suggest(PartyType.BUYER, "98765").map { it.name })
        assertEquals(emptyList<Party>(), catalog.suggest(PartyType.SUPPLIER, "ram"))
        // Only the first line of a multi-line field is used for matching.
        assertEquals(listOf("Ram Textiles"), catalog.suggest(PartyType.BUYER, "Ram Tex\nsomething else").map { it.name })

        assertNotNull(catalog.findByName(PartyType.BUYER, "  ram   TEXTILES "))
        assertNull(catalog.findByName(PartyType.BUYER, "Ram Textiles", excludingId = ram.id))

        now = 2_000L
        catalog.save(ram.copy(city = "Dewas"))
        assertEquals("Dewas", catalog.list(PartyType.BUYER, "ram tex").single().city)
        assertEquals(Triple(2_000L, 0, 1), syncColumns("buyers", ram.id))

        catalog.delete(PartyType.BUYER, ram.id)
        assertEquals(listOf("Shri Ram Cloth"), catalog.list(PartyType.BUYER).map { it.name })
        // Gone for good; only a note for the cloud stays.
        assertFalse(exists("buyers", ram.id))
        assertTrue(queuedDelete(ram.id))
        assertEquals(1, catalog.count(PartyType.BUYER))
    }

    @Test
    fun searchMatchesOrderFieldsAndSortsByDate() {
        seedOrders()
        assertEquals(listOf("c", "b", "a"), orders.search(OrderQuery()).map { it.id })
        assertEquals(listOf("a", "b", "c"), orders.search(OrderQuery(newestFirst = false)).map { it.id })
        assertEquals(listOf("c", "a"), orders.search(OrderQuery(text = "ram")).map { it.id })
        assertEquals(listOf("c", "b"), orders.search(OrderQuery(text = "RAYMOND")).map { it.id })
        assertEquals(listOf("c", "a"), orders.search(OrderQuery(text = "shirting")).map { it.id })
        assertEquals(listOf("a"), orders.search(OrderQuery(text = "ram cotton")).map { it.id })
        assertEquals(listOf("b"), orders.search(OrderQuery(text = "2026110702")).map { it.id })
        val day = LocalDate.of(2026, 11, 7)
        assertEquals(listOf("b", "a"), orders.search(OrderQuery(from = day, to = day)).map { it.id })
        assertEquals(listOf("c"), orders.search(OrderQuery(from = day.plusDays(1))).map { it.id })
    }

    @Test
    fun oneBillCanCoverManyOrdersAndOneOrderManyBills() {
        seedOrders()
        val bill = orders.attachBill(listOf("a", "b"), "inv-101", BillDetails(LocalDate.of(2026, 11, 20), 15000.0))
        assertEquals("inv-101", bill.billNo)
        assertEquals(2, bill.orderCount)
        // Same number typed differently reuses the bill.
        val again = orders.attachBill(listOf("a"), " INV-101 ")
        assertEquals(bill.id, again.id)
        assertEquals(LocalDate.of(2026, 11, 20), again.billDate)
        orders.attachBill(listOf("a"), "INV-102")

        assertEquals(listOf("inv-101", "INV-102"), orders.billsForOrder("a").map { it.billNo })
        assertEquals(listOf("inv-101"), orders.billsForOrder("b").map { it.billNo })
        assertEquals(listOf("inv-101", "INV-102"), orders.search(OrderQuery(text = "a")).first { it.id == "a" }.billNos)

        // Bill number is searchable, and status filters work.
        assertEquals(listOf("b", "a"), orders.search(OrderQuery(text = "inv-101")).map { it.id })
        assertEquals(listOf("a"), orders.search(OrderQuery(text = "102")).map { it.id })
        assertEquals(listOf("c"), orders.search(OrderQuery(status = BillStatusFilter.PENDING)).map { it.id })
        assertEquals(listOf("b", "a"), orders.search(OrderQuery(status = BillStatusFilter.RECEIVED)).map { it.id })
        assertEquals(OrderCounts(total = 3, pending = 1), orders.counts(OrderQuery()))
        assertEquals(OrderCounts(total = 2, pending = 1), orders.counts(OrderQuery(text = "ram")))
        assertEquals(listOf("inv-101"), orders.suggestBills("101").map { it.billNo })
        // Bills already on every target order aren't suggested again.
        assertTrue(orders.suggestBills("inv", forOrders = listOf("a")).isEmpty())
        assertEquals(listOf("INV-102"), orders.suggestBills("inv", forOrders = listOf("b")).map { it.billNo })
        assertEquals(2, orders.suggestBills("inv", forOrders = listOf("a", "c")).size)
    }

    @Test
    fun detachRemovesOnlyThatLinkAndRetiresOrphanBills() {
        seedOrders()
        val shared = orders.attachBill(listOf("a", "b"), "INV-1")
        val single = orders.attachBill(listOf("c"), "INV-2")

        orders.detachBill("a", shared.id)
        assertEquals(emptyList<Bill>(), orders.billsForOrder("a"))
        assertEquals(1, orders.billsForOrder("b").single().orderCount)
        assertEquals(0, syncColumns("bills", shared.id).second)

        orders.detachBill("c", single.id)
        assertFalse(exists("bills", single.id))
        assertTrue(queuedDelete(single.id))
        assertEquals(listOf("c", "a"), orders.search(OrderQuery(status = BillStatusFilter.PENDING)).map { it.id })

        // Re-attaching a retired bill number brings the same bill back.
        assertEquals(single.id, orders.attachBill(listOf("a"), "inv-2").id)
        assertEquals(0, syncColumns("bills", single.id).second)
    }

    @Test
    fun reconcileTracksAddsEditsAndDeletes() {
        val seeded = seedOrders()
        assertFalse(orders.reconcile(seeded))
        orders.attachBill(listOf("b"), "INV-9")

        now = 5_000L
        val edited = listOf(seeded[0].copy(buyer = "Ram Textiles\nDewas"), seeded[2].copy(seq = 1), entry("d", "2026110901", "09-11-2026", "New Buyer", seq = 2))
        assertTrue(orders.reconcile(edited))
        assertEquals(listOf("d", "c", "a"), orders.search(OrderQuery()).map { it.id })
        assertEquals(listOf("a"), orders.search(OrderQuery(text = "dewas")).map { it.id })
        // The deleted order is gone with its bill link, and the orphaned bill is retired.
        assertFalse(exists("orders", "b"))
        assertTrue(orders.search(OrderQuery(text = "INV-9")).isEmpty())
        assertTrue(orders.suggestBills("INV-9").isEmpty())
        // Seq-only change doesn't mark the order dirty.
        assertEquals(1_000L, syncColumns("orders", "c").first)
    }

    @Test
    fun historyFileParsingKeepsLegacyEntriesSearchable() {
        val json = JSONArray()
            .put(JSONObject().put("date", "07-11-2026").put("offerNo", "11").put("buyer", "Old Buyer").put("supplier", "S")
                .put("filePath", "/x/old.json").put("items", JSONArray().put(JSONObject().put("particulars", "Terry cot"))))
            .put(JSONObject().put("date", "08-11-2026").put("offerNo", "12").put("buyer", "B").put("uuid", "u-1")
                .put("filePath", "/x/u1.json").put("pdfFileName", "Order_1.pdf"))
            .put(JSONObject().put("date", "08-11-2026").put("offerNo", "13").put("buyer", "C"))
        val entries = HistoryIndex.parse(json.toString())
        assertEquals(listOf("file:/x/old.json", "u-1", "legacy:2"), entries.map { it.id })
        assertEquals("Terry cot", entries[0].particulars)
        assertEquals("Order_1.pdf", entries[1].pdfFileName)
        assertTrue(HistoryIndex.parse("").isEmpty())

        orders.reconcile(entries)
        assertEquals(listOf("file:/x/old.json"), orders.search(OrderQuery(text = "terry")).map { it.id })
        assertEquals(null, orders.search(OrderQuery(text = "old buyer")).single().uuid)
    }

    @Test
    fun bankDetailsRoundTripAndSearch() {
        val saved = catalog.save(
            Party(
                type = PartyType.SUPPLIER, name = "Mafatlal Mills",
                bank = BankDetails(accountName = "Mafatlal Industries", bankName = " State Bank of India ", accountNo = "3012 3456 789", ifsc = "sbin0001234", branch = "Ahmedabad", upi = "mafatlal@sbi")
            )
        )
        val loaded = catalog.list(PartyType.SUPPLIER).single()
        assertEquals(saved.id, loaded.id)
        assertEquals(
            BankDetails("Mafatlal Industries", "State Bank of India", "30123456789", "SBIN0001234", "Ahmedabad", "mafatlal@sbi"),
            loaded.bank
        )
        assertEquals(listOf("Mafatlal Mills"), catalog.list(PartyType.SUPPLIER, "sbin0001234").map { it.name })
        assertEquals(listOf("Mafatlal Mills"), catalog.list(PartyType.SUPPLIER, "30123456789").map { it.name })
        // The order form's field text never includes bank details.
        assertEquals("Mafatlal Mills", loaded.toFieldText())

        catalog.save(loaded.copy(bank = BankDetails()))
        assertTrue(catalog.list(PartyType.SUPPLIER).single().bank.isEmpty)
        assertEquals(1, syncColumns("suppliers", saved.id).third)
    }

    @Test
    fun upgradeFromVersion1KeepsCatalogAndAddsBankColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upgrade_test.db"
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            for (table in listOf("buyers", "suppliers")) {
                v1.execSQL(
                    """
                    CREATE TABLE $table (
                        id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, name_key TEXT NOT NULL,
                        address TEXT NOT NULL DEFAULT '', city TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '',
                        gstin TEXT NOT NULL DEFAULT '', search_key TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                        is_deleted INTEGER NOT NULL DEFAULT 0, sync_pending INTEGER NOT NULL DEFAULT 1)
                    """.trimIndent()
                )
            }
            v1.execSQL("INSERT INTO buyers (id, name, name_key, city, search_key, created_at, updated_at) VALUES ('b1', 'Ram Textiles', 'ram textiles', 'Indore', 'ram textiles indore', 1, 1)")
            // Soft-deleted rows from before hard deletes: one whose delete never reached the cloud, one that did.
            v1.execSQL("INSERT INTO buyers (id, name, name_key, created_at, updated_at, is_deleted, sync_pending) VALUES ('b2', 'Gone', 'gone', 1, 7, 1, 1)")
            v1.execSQL("INSERT INTO suppliers (id, name, name_key, created_at, updated_at, is_deleted, sync_pending) VALUES ('s1', 'Old', 'old', 1, 8, 1, 0)")
            v1.version = 1
        }
        val upgraded = OtaDatabase(context, name)
        try {
            val repo = CatalogRepository(upgraded) { now }
            val ram = repo.list(PartyType.BUYER).single()
            assertEquals(Party(id = "b1", type = PartyType.BUYER, name = "Ram Textiles", city = "Indore"), ram)
            repo.save(ram.copy(bank = BankDetails(bankName = "HDFC Bank", ifsc = "HDFC0001234")))
            assertEquals("HDFC0001234", repo.list(PartyType.BUYER, "hdfc").single().bank.ifsc)
            assertEquals(OtaDatabase.VERSION, upgraded.readableDatabase.version)
            // v8: soft-deleted rows are removed; an unsent delete is queued for the cloud.
            val db = upgraded.readableDatabase
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM buyers", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM suppliers", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(listOf(Deletion("buyers", "b2", 7)), LocalDeletions.pending(db, 10))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
