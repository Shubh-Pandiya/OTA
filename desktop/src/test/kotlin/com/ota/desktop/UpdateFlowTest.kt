package com.ota.desktop

import com.example.otaapplication.OrderNumber
import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.SessionStore
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Kv
import com.ota.desktop.db.one
import com.ota.desktop.sync.SyncEngine
import com.ota.desktop.update.AppVersion
import com.ota.desktop.update.Backups
import com.ota.desktop.update.DataSnapshot
import com.ota.desktop.update.PendingUpdate
import com.ota.desktop.update.ReleaseFeed
import com.ota.desktop.update.StartupGuard
import com.ota.desktop.update.UpdateHost
import com.ota.desktop.update.UpdateManager
import com.ota.desktop.update.UpdateState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole in-app update against a real database folder and the fake cloud: realistic data with
 * unsynced and number-pending rows, "Update now" (download, verify, last sync, stop, backup, hand
 * over to the installer), the new version's first start, re-pulls, and a restore. Every table's
 * row count and checksum must come through unchanged, and nothing may be duplicated locally or in
 * the cloud.
 */
class UpdateFlowTest {
    private val home: File = Files.createTempDirectory("ota-update").toFile()
    private val pgData = File(home, "pgdata")
    private val bin = File(System.getProperty("java.io.tmpdir"), "ota-test-pg-bin")
    private val cloud = FakeCloud()
    private val releases = FakeReleases()
    private val day = LocalDate.of(2026, 11, 10)

    /** One run of "the app" at [version] over the data folder. */
    private inner class Run(version: String) : AutoCloseable {
        val guard = StartupGuard(home, pgData, AppVersion.parse(version)!!)
        val server = EmbeddedDb.start(pgData, bin, File(home, "pg.log"), guard::beforeStart)
        val db = Db(server::connect).also { guard.migrate(it); guard.started() }
        val kv = Kv(db)
        val config = SupabaseConfig(cloud.url, "publishable")
        val auth = SupabaseAuth(config, SessionStore(kv))
        val orders = OrderStore(db)
        val payments = PaymentStore(db)
        val engine = SyncEngine(db, kv, auth, PostgrestClient(config, auth), CatalogStore(db), orders, payments, Numbers(db, kv) { day })
        var exited = false
        val host = object : UpdateHost {
            override suspend fun syncBeforeUpdate(): String? {
                val s = engine.sync()
                return s.error ?: s.pending.takeIf { it > 0 }?.let { "$it pending" }
            }
            override fun shutdown() = close()
            override fun exit() { exited = true }
        }

        fun snapshot() = DataSnapshot.take(db)
        fun pending() = orders.pendingCount()

        override fun close() {
            runCatching { db.close() }
            runCatching { server.close() }
        }
    }

    private var launched = mutableListOf<List<String>>()
    private fun manager(version: String) = UpdateManager(
        AppVersion.parse(version)!!, home, pgData, ReleaseFeed(releases.feed), windows = true,
        relaunchArgs = listOf("--self-test", "report.txt"),
        launcher = { msi, _, result, args -> launched += listOf(msi.name) + args; result.writeText("0") }
    )

    private fun localDuplicates(db: Db) = db.read { c ->
        (c.one("select count(*) from (select order_no from orders where order_no <> '' group by order_no having count(*) > 1) d") { it.getInt(1) } ?: 0) +
            (c.one("select count(*) from (select advice_no from payment_advices where advice_no <> '' group by advice_no having count(*) > 1) d") { it.getInt(1) } ?: 0)
    }

    private fun cloudDuplicates(): Int {
        fun dups(table: String, col: String) = cloud.table(table).values.filter { !it.optBoolean("deleted") }.map { it.optString(col) }
            .filter { it.isNotEmpty() }.groupingBy { it }.eachCount().count { it.value > 1 }
        return dups("orders", "order_no") + dups("payment_advices", "advice_no")
    }

    /** Like the real migration: the FY counters start after the numbers the cloud already has. */
    private fun seedCounters() {
        fun seed(table: String, col: String, into: MutableMap<String, Int>) =
            cloud.table(table).values.map { it.optString(col) }.filter { Regex("\\d{8}").matches(it) }
                .forEach { n -> into.merge(n.take(4), n.drop(4).toInt(), ::maxOf) }
        seed("orders", "order_no", cloud.orderSerials)
        seed("payment_advices", "advice_no", cloud.adviceSerials)
    }

    private fun cloudCounts() = cloud.tables.mapValues { it.value.size }

    @AfterTest
    fun tearDown() {
        cloud.close(); releases.close(); home.deleteRecursively()
    }

    @Test
    fun updateKeepsEveryRowExactlyOnce() = runBlocking {
        releases.publish("1.3.0", Random(1).nextBytes(250_000))
        releases.publish("1.3.1", Random(2).nextBytes(250_000))

        // ---- 1.2.1 in daily use: half a year of data, synced once, then more work while offline.
        Run("1.2.1").use { app ->
            DemoData(app.db).seed()
            app.auth.signIn("staff@ota.com", "x")
            assertNull(app.engine.sync().error)
            seedCounters()
            assertEquals(0, app.pending())
            cloud.failWrites = 503 to null
            val first = app.orders.list().first()
            app.orders.create(first.data.copy(remarks = "Taken at the shop, no internet"), "")  // number pending
            app.orders.create(first.data.copy(remarks = "Second offline order"), "")
            app.orders.update(first.id, first.data.copy(remarks = "Edited offline"))
            app.engine.sync()
            assertTrue(app.pending() >= 3)
        }
        val serialsBefore = cloud.orderSerials.toMap()

        // ---- 1.2.1 → 1.3.0 while ONLINE: the last sync pushes everything and numbers the pending orders once.
        cloud.failWrites = null
        lateinit var before: Map<String, String>
        val upd130 = manager("1.2.1")
        Run("1.2.1").use { app ->
            val release = assertNotNull(upd130.check(manual = false))
            assertEquals(AppVersion.parse("1.3.1"), release.version, "straight to the newest release")
            upd130.install(release, app.host)
            assertTrue(app.exited, "the app hands over to the installer and exits")
        }
        assertEquals(listOf("OTA-Business-1.3.1.msi", "--self-test", "report.txt"), launched.single())
        assertEquals(2, cloud.orderSerials.values.sum() - serialsBefore.values.sum(), "each pending order got exactly one number")
        val pending = assertNotNull(PendingUpdate.read(home))
        val backup = File(pending.backup!!)
        assertTrue(backup.isFile && Backups.check(backup) > 100)

        // The data exactly as the old version left it.
        Run("1.2.1").use {
            before = it.snapshot()
            assertEquals(0, it.pending(), "everything was pushed before the update")
        }

        // ---- First start of the new version: no second backup, schema checked, data identical.
        Run("1.3.1").use { app ->
            assertEquals(backup, app.guard.backup, "the updater's backup is used, no second copy")
            assertEquals(before, app.snapshot())
            assertEquals(0, localDuplicates(app.db))
            val outcome = manager("1.3.1").consumeOutcome()!!
            assertTrue(outcome.ok, outcome.message)
            assertNull(PendingUpdate.read(home))

            // Re-pull everything (what a migration like V2/V3 triggers): upserts by id, no new rows.
            val counts = cloudCounts()
            app.db.read { c ->
                c.createStatement().use { st ->
                    for (t in listOf("orders", "bills", "order_bills", "payment_advices", "advice_lines")) st.execute("update $t set server_updated_at = null where not sync_pending")
                    st.execute("delete from kv where key like 'cursor.%'")
                }
            }
            val serials = cloud.orderSerials.values.sum()
            assertNull(app.engine.sync().error)
            assertEquals(serials, cloud.orderSerials.values.sum(), "no number is allocated twice")
            assertEquals(counts, cloudCounts())
            assertEquals(before.mapValues { it.value.substringBefore(' ') }.filterKeys { it != "kv" }, app.snapshot().mapValues { it.value.substringBefore(' ') }.filterKeys { it != "kv" })
            assertEquals(0, localDuplicates(app.db))
            assertEquals(0, cloudDuplicates())
        }
        assertEquals(1, Backups.list(File(home, "backups")).size)

        // ---- 1.3.1 → newer while OFFLINE: pending rows survive the update untouched.
        releases.publish("1.4.0", Random(3).nextBytes(250_000))
        lateinit var offlineBefore: Map<String, String>
        var pendingBefore = 0
        val upd140 = manager("1.3.1")
        Run("1.3.1").use { app ->
            cloud.failWrites = 503 to null
            val o = app.orders.list().first()
            app.orders.create(o.data.copy(remarks = "Offline before the update"), "")
            app.orders.update(o.id, o.data.copy(remarks = "Offline edit before the update"))
            val release = assertNotNull(upd140.check(manual = true))
            assertEquals(AppVersion.parse("1.4.0"), release.version)
            pendingBefore = app.pending()
            assertTrue(pendingBefore >= 2)
            offlineBefore = app.snapshot().filterKeys { it != "kv" }
            upd140.install(release, app.host)
            assertTrue(app.exited)
        }
        Run("1.4.0").use { app ->
            assertEquals(offlineBefore, app.snapshot().filterKeys { it != "kv" }, "offline changes came through the update unchanged")
            assertEquals(pendingBefore, app.pending())
            assertNotNull(app.guard.backup)
            // Back online: they upload once.
            cloud.failWrites = null
            val serials = cloud.orderSerials.values.sum()
            assertNull(app.engine.sync().error)
            seedCounters()
            assertEquals(0, app.pending())
            assertEquals(serials + 1, cloud.orderSerials.values.sum())
            app.engine.sync()
            assertEquals(serials + 1, cloud.orderSerials.values.sum())
            assertEquals(0, localDuplicates(app.db))
            assertEquals(0, cloudDuplicates())
        }

        // ---- An older version cannot open data the newer one used.
        val refused = runCatching { Run("1.3.1").close() }.exceptionOrNull()
        assertTrue(refused is com.ota.desktop.update.StartupBlocked, "downgrade refused: $refused")

        // ---- Restoring the backup taken before 1.3.0 gives back exactly that data.
        val first = Backups.list(File(home, "backups")).first()
        EmbeddedDb.locked(pgData, bin) { Backups.restore(first, pgData) }
        File(home, "app-version.properties").delete()
        Run("1.4.0").use { app -> assertEquals(before.filterKeys { it != "kv" }, app.snapshot().filterKeys { it != "kv" }) }
        Unit
    }

    @Test
    fun aFailedDownloadChangesNothing() = runBlocking {
        val bytes = Random(5).nextBytes(100_000)
        releases.publish("1.3.0", bytes)
        releases.files["OTA-Business-1.3.0.msi"] = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        Run("1.2.1").use { app ->
            DemoData(app.db).seed()
            val before = app.snapshot()
            val upd = manager("1.2.1")
            val release = upd.check(manual = true)!!
            upd.install(release, app.host)
            val state = upd.state.value
            assertTrue(state is UpdateState.Failed && !state.restart, "$state")
            assertTrue(!app.exited && launched.isEmpty())
            // The app is still running on its database.
            assertEquals(before, app.snapshot())
        }
        assertNull(PendingUpdate.read(home))
        assertTrue(Backups.list(File(home, "backups")).isEmpty())
    }

    @Test
    fun aFailedInstallIsReportedAndDataKept() = runBlocking {
        releases.publish("1.3.0", Random(6).nextBytes(100_000))
        val upd = UpdateManager(AppVersion.parse("1.2.1")!!, home, pgData, ReleaseFeed(releases.feed), windows = true,
            launcher = { _, _, result, _ -> result.writeText("1602") })
        lateinit var before: Map<String, String>
        Run("1.2.1").use { app ->
            DemoData(app.db).seed()
            before = app.snapshot()
            upd.install(upd.check(manual = true)!!, app.host)
        }
        // The installer was cancelled, so the old version starts again.
        Run("1.2.1").use { app ->
            assertEquals(before, app.snapshot())
            val outcome = UpdateManager(AppVersion.parse("1.2.1")!!, home, pgData).consumeOutcome()!!
            assertTrue(!outcome.ok && outcome.message.contains("cancelled"), outcome.message)
        }
    }
}
