package com.example.otaapplication.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.Deletion
import com.example.otaapplication.data.LocalDeletions
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PendingParty
import com.example.otaapplication.data.RemoteParty
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** In-memory stand-in for the Supabase tables, following the same rules as the SQL trigger. */
private class FakeCloud : CatalogCloudApi {
    val tables = PartyType.entries.associateWith { linkedMapOf<String, RemoteParty>() }
    var pullCalls = 0
    private var tick = 0L

    private fun nextServerTime(): String = Instant.parse("2026-11-07T10:00:00Z").plusNanos(++tick * 1_000).toString()
    val tombstones = FakeTombstones(::nextServerTime)

    /** ota_delete_rows and ota_deletions for buyers and suppliers. */
    val deletionApi = object : OrderCloudApi {
        override suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>> = error("unused")
        override suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>) = error("unused")
        override suspend fun staff(userId: String): StaffInfo? = null
        override suspend fun deleteRows(rows: List<Deletion>): Set<String> = rows.filterNot { row ->
            val target = tables.getValue(PartyType.entries.first { it.table == row.table })
            tombstones.delete(row, target[row.id]?.clientUpdatedAt) { target.remove(row.id) }
        }.mapTo(HashSet()) { it.id }
        override suspend fun pullDeletions(after: String?, afterId: String?, limit: Int) = tombstones.pull(after, afterId, limit)
    }

    override suspend fun pull(type: PartyType, after: String?, afterId: String?, limit: Int): List<RemoteParty> {
        pullCalls++
        val afterInstant = after?.let { Instant.parse(it.replace("+00:00", "Z")) }
        return tables.getValue(type).values
            .sortedWith(compareBy({ Instant.parse(it.serverUpdatedAt) }, { it.party.id }))
            .filter { row ->
                val t = Instant.parse(row.serverUpdatedAt)
                afterInstant == null || t.isAfter(afterInstant) || (afterId != null && t == afterInstant && row.party.id > afterId)
            }
            .take(limit)
    }

    override suspend fun push(type: PartyType, rows: List<PendingParty>): List<RemoteParty> = rows.mapNotNull { row ->
        val table = tables.getValue(type)
        val existing = table[row.party.id]
        if (existing != null && row.updatedAt < existing.clientUpdatedAt) {
            existing
        } else if (!tombstones.admits(row.party.id, row.updatedAt)) {
            null // dropped by the guard trigger: PostgREST returns nothing for it
        } else {
            RemoteParty(
                party = row.party,
                deleted = row.deleted,
                createdAt = existing?.createdAt ?: row.createdAt,
                clientUpdatedAt = row.updatedAt,
                serverUpdatedAt = nextServerTime()
            ).also { table[row.party.id] = it }
        }
    }

    /** An edit made directly in the cloud (e.g. by another phone or the dashboard). */
    fun edit(type: PartyType, id: String, clientUpdatedAt: Long, change: (Party) -> Party) {
        val table = tables.getValue(type)
        val row = table.getValue(id)
        table[id] = row.copy(party = change(row.party), clientUpdatedAt = clientUpdatedAt, serverUpdatedAt = nextServerTime())
    }
}

private class MemoryCursors : SyncCursors {
    val values = mutableMapOf<PartyType, String?>()
    override fun get(type: PartyType) = values[type]
    override fun set(type: PartyType, value: String?) { values[type] = value }
}

/** One simulated phone: its own local database, clock and sync engine. */
private class Phone(cloud: CatalogCloudApi, var now: Long, pageSize: Int = 500) {
    val db = OtaDatabase(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
    val catalog = CatalogRepository(db) { now }
    val cursors = MemoryCursors()
    val sync = CatalogSync(catalog, cloud, cursors, pageSize = pageSize, pushBatch = 2)
    private val deletions = (cloud as? FakeCloud)?.let { fake ->
        DeletionSync(
            fake.deletionApi, MemoryCursorStore(),
            pending = { LocalDeletions.pending(db.writableDatabase, it) },
            markSent = { LocalDeletions.markSent(db.writableDatabase, it) },
            apply = { mapOf("catalog" to catalog.applyDeletions(it)) },
            resetTable = { table -> cursors.set(PartyType.entries.first { it.table == table }, null) },
            pageSize = pageSize, pushBatch = 2
        )
    }
    fun buyers() = catalog.list(PartyType.BUYER)
    fun pendingDeletes() = LocalDeletions.pendingCount(db.readableDatabase)
    fun syncNow() = runBlocking { deletions?.syncAll(); sync.syncAll() }
}

@RunWith(AndroidJUnit4::class)
class CatalogSyncTest {
    private lateinit var cloud: FakeCloud
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        cloud = FakeCloud()
        a = Phone(cloud, now = 10_000)
        b = Phone(cloud, now = 10_000)
    }

    @After
    fun tearDown() {
        a.db.close()
        b.db.close()
    }

    @Test
    fun localEntriesUploadAndReachOtherPhones() {
        val ram = a.catalog.save(
            Party(type = PartyType.BUYER, name = "Ram Textiles", city = "Indore", bank = BankDetails(bankName = "SBI", ifsc = "SBIN0001234", accountNo = "12345678"))
        )
        a.catalog.save(Party(type = PartyType.BUYER, name = "Shyam Garments"))
        a.catalog.save(Party(type = PartyType.SUPPLIER, name = "Raymond"))
        assertEquals(3, a.catalog.pendingCount())

        val result = a.syncNow()
        assertEquals(3, result.pushed)
        assertEquals(0, a.catalog.pendingCount())
        assertEquals(2, cloud.tables.getValue(PartyType.BUYER).size)

        val pulled = b.syncNow()
        assertEquals(3, pulled.pulledChanges)
        assertEquals(listOf("Ram Textiles", "Shyam Garments"), b.buyers().map { it.name })
        assertEquals(ram, b.buyers().first())
        assertEquals(listOf("Raymond"), b.catalog.list(PartyType.SUPPLIER).map { it.name })
        assertEquals(0, b.catalog.pendingCount())
        // Searching still works for pulled rows (search keys rebuilt locally).
        assertEquals(1, b.catalog.list(PartyType.BUYER, "sbin0001234").size)
    }

    @Test
    fun secondSyncWithNoChangesDoesNothing() {
        a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow()
        assertEquals(0, a.syncNow().pulledChanges)
        assertEquals(0, a.syncNow().pushed)
    }

    @Test
    fun cloudEditIsPulledIntoSyncedRow() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow()
        cloud.edit(PartyType.BUYER, ram.id, clientUpdatedAt = 20_000) { it.copy(city = "Bhopal") }

        assertEquals(1, a.syncNow().pulledChanges)
        assertEquals("Bhopal", a.buyers().single().city)
        assertEquals(0, a.catalog.pendingCount())
    }

    @Test
    fun deletesPropagate() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow()
        b.syncNow()
        assertEquals(1, b.buyers().size)

        a.now = 20_000
        a.catalog.delete(PartyType.BUYER, ram.id)
        // Gone here at once, with only a small note left to tell the cloud.
        assertTrue(a.buyers().isEmpty())
        assertEquals(0, rows(a, "buyers"))
        assertEquals(1, a.pendingDeletes())
        a.syncNow()
        assertEquals(0, a.pendingDeletes())
        assertNull(cloud.tables.getValue(PartyType.BUYER)[ram.id])
        assertEquals(setOf(ram.id), cloud.tombstones.rows.keys)

        b.syncNow()
        assertTrue(b.buyers().isEmpty())
        assertEquals(0, rows(b, "buyers"))
        assertNull(b.catalog.findByName(PartyType.BUYER, "Ram Textiles"))
    }

    @Test
    fun staleOfflineEditDoesNotBringADeletedEntryBack() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow(); b.syncNow()
        // B edits offline before A's delete, and syncs after it.
        b.now = 15_000
        b.catalog.save(ram.copy(city = "Indore"))
        a.now = 20_000
        a.catalog.delete(PartyType.BUYER, ram.id)
        a.syncNow()

        b.syncNow()
        assertTrue(b.buyers().isEmpty())
        assertEquals(0, b.catalog.pendingCount())
        assertNull(cloud.tables.getValue(PartyType.BUYER)[ram.id])
        a.syncNow()
        assertTrue(a.buyers().isEmpty())
    }

    @Test
    fun editMadeAfterADeleteWins() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow(); b.syncNow()
        a.now = 20_000
        a.catalog.delete(PartyType.BUYER, ram.id)
        a.syncNow()
        // B's edit is newer than the delete: it stays and comes back to A.
        b.now = 30_000
        b.catalog.save(ram.copy(city = "Indore"))
        b.syncNow()
        assertEquals("Indore", b.buyers().single().city)
        assertEquals("Indore", cloud.tables.getValue(PartyType.BUYER).getValue(ram.id).party.city)
        assertTrue(cloud.tombstones.rows.isEmpty())
        a.syncNow()
        assertEquals("Indore", a.buyers().single().city)
    }

    @Test
    fun deleteLosesToANewerEditAlreadyInTheCloud() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow(); b.syncNow()
        b.now = 30_000
        b.catalog.save(ram.copy(city = "Indore"))
        b.syncNow()
        // A deletes with an older clock, offline, then syncs: the cloud keeps B's edit and A gets it back.
        a.now = 20_000
        a.catalog.delete(PartyType.BUYER, ram.id)
        a.syncNow()
        a.syncNow()
        assertEquals("Indore", a.buyers().single().city)
        assertEquals(0, a.pendingDeletes())
        assertTrue(cloud.tombstones.rows.isEmpty())
    }

    private fun rows(phone: Phone, table: String) =
        phone.db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun newerEditWinsWhenBothPhonesChangeTheSameEntry() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow()
        b.syncNow()

        a.now = 20_000
        a.catalog.save(ram.copy(city = "Indore"))
        b.now = 30_000
        b.catalog.save(ram.copy(city = "Ujjain"))

        // The newer edit (phone B) reaches the cloud first; A's older edit arrives later and loses.
        b.syncNow()
        a.syncNow()
        assertEquals("Ujjain", a.buyers().single().city)
        assertEquals(0, a.catalog.pendingCount())
        assertEquals("Ujjain", cloud.tables.getValue(PartyType.BUYER).getValue(ram.id).party.city)
    }

    @Test
    fun pendingNewerLocalEditSurvivesPullAndIsUploaded() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.syncNow()
        cloud.edit(PartyType.BUYER, ram.id, clientUpdatedAt = 15_000) { it.copy(city = "Bhopal") }
        a.now = 20_000
        a.catalog.save(ram.copy(city = "Dewas"))

        a.syncNow()
        assertEquals("Dewas", a.buyers().single().city)
        assertEquals("Dewas", cloud.tables.getValue(PartyType.BUYER).getValue(ram.id).party.city)
        assertEquals(0, a.catalog.pendingCount())
    }

    @Test
    fun phoneWithSlowClockCanStillEditEntry() {
        val ram = a.catalog.save(Party(type = PartyType.BUYER, name = "Ram Textiles"))
        a.now = 90_000 // phone A's clock runs fast
        a.catalog.save(ram.copy(city = "Indore"))
        a.syncNow()
        b.now = 50_000 // phone B's clock is behind A's
        b.syncNow()

        b.catalog.save(b.buyers().single().copy(city = "Ratlam"))
        b.syncNow()
        a.syncNow()
        assertEquals("Ratlam", cloud.tables.getValue(PartyType.BUYER).getValue(ram.id).party.city)
        assertEquals("Ratlam", a.buyers().single().city)
    }

    @Test
    fun largeCatalogIsPulledInPages() {
        repeat(7) { i -> a.catalog.save(Party(type = PartyType.BUYER, name = "Buyer $i")) }
        a.syncNow() // pushes in batches of 2

        val paged = Phone(cloud, now = 10_000, pageSize = 3)
        try {
            cloud.pullCalls = 0
            assertEquals(7, paged.syncNow().pulledChanges)
            assertEquals(7, paged.buyers().size)
            assertTrue("expected several pages, got ${cloud.pullCalls}", cloud.pullCalls >= 4)
            val newest = cloud.tables.getValue(PartyType.BUYER).values.maxBy { Instant.parse(it.serverUpdatedAt) }.serverUpdatedAt
            assertEquals(newest, paged.cursors.get(PartyType.BUYER))
        } finally {
            paged.db.close()
        }
    }

    @Test
    fun jsonMappingMatchesCloudColumns() {
        val row = PendingParty(
            party = Party(
                id = "6f1d1c9e-0000-4000-8000-000000000001", type = PartyType.SUPPLIER, name = "Raymond", city = "Mumbai",
                gstin = "27AAACR1234A1Z5", bank = BankDetails(upi = "raymond@upi")
            ),
            deleted = true,
            createdAt = 1_000,
            updatedAt = 2_000
        )
        val json = CatalogJson.toJson(row)
        assertEquals("1970-01-01T00:00:01Z", json.getString("created_at"))
        assertEquals(2_000L, json.getLong("client_updated_at"))
        assertTrue(json.getBoolean("deleted"))
        assertTrue(!json.has("updated_at") && !json.has("updated_by"))

        val server = JSONObject(json.toString())
            .put("created_at", "1970-01-01T00:00:01+00:00")
            .put("updated_at", "2026-11-07T10:00:00.123456+00:00")
            .put("address", JSONObject.NULL)
            .put("updated_by", "c0ffee00-0000-4000-8000-000000000000")
        val parsed = CatalogJson.parse(server, PartyType.SUPPLIER)
        assertEquals(row.party, parsed.party)
        assertEquals(true, parsed.deleted)
        assertEquals(1_000L, parsed.createdAt)
        assertEquals(2_000L, parsed.clientUpdatedAt)
        assertEquals("2026-11-07T10:00:00.123456+00:00", parsed.serverUpdatedAt)
    }
}
