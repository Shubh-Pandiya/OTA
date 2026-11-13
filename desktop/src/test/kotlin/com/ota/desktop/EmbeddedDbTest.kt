package com.ota.desktop

import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Kv
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddedDbTest {
    @Test
    fun migrationsCreateTablesAndRunOnce() {
        val db = TestDb.fresh()
        Migrations.run(db)
        val tables = db.read { c ->
            c.one("select count(*) from information_schema.tables where table_schema = 'public'") { it.getInt(1) }
        }
        assertEquals(14, tables) // kv, schema_version, buyers, suppliers, the 5 order tables, pending_deletions and the 4 acc_ tables
        val kv = Kv(db)
        kv["a"] = "1"; kv["a"] = "2"
        assertEquals("2", kv["a"])
        kv["a"] = null
        assertEquals(null, kv["a"])
    }

    @Test
    fun clusterSurvivesRestartAndKeepsData() {
        val home = Files.createTempDirectory("ota-restart").toFile()
        val bin = File(System.getProperty("java.io.tmpdir"), "ota-test-pg-bin")
        try {
            EmbeddedDb.start(File(home, "pgdata"), bin, File(home, "pg.log")).use { srv ->
                srv.connect().use { c -> c.createStatement().use { it.execute("create table t (x int); insert into t values (42)") } }
                // A second app instance on the same folder is refused.
                assertFailsWith<EmbeddedDb.AlreadyRunning> { EmbeddedDb.start(File(home, "pgdata"), bin, File(home, "pg2.log")) }
            }
            assertTrue(File(home, "pgdata/postgresql.conf").exists())
            EmbeddedDb.start(File(home, "pgdata"), bin, File(home, "pg.log")).use { srv ->
                val x = srv.connect().use { c -> c.one("select x from t") { it.getInt(1) } }
                assertEquals(42, x)
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
