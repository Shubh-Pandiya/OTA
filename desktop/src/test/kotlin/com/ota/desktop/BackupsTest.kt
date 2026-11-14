package com.ota.desktop

import com.ota.desktop.backup.AutoBackup
import com.ota.desktop.backup.BackupArchive
import com.ota.desktop.backup.LocalSettings
import com.ota.desktop.backup.Restorer
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.exec
import com.ota.desktop.db.one
import com.ota.desktop.db.update
import com.ota.desktop.security.PinHash
import com.ota.desktop.security.PinLock
import com.ota.desktop.update.AppVersion
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupsTest {
    private fun dir(name: String) = File("build/backups-test/$name").absoluteFile.apply { deleteRecursively(); mkdirs() }

    private fun count(c: java.sql.Connection, table: String) = c.one("select count(*) from $table") { it.getInt(1) }!!

    @Test
    fun `an encrypted online backup restores every schema, even with writes during the backup`() {
        val db = TestDb.fresh()
        db.read { c ->
            c.exec("create schema if not exists ota_test")
            c.exec("create table if not exists public.bk_probe(id serial primary key, v text)")
            c.exec("create table if not exists ota_test.bk_probe(id serial primary key, v text)")
            c.exec("truncate public.bk_probe, ota_test.bk_probe")
            repeat(500) { c.update("insert into public.bk_probe(v) values (?)", "real $it") }
            repeat(200) { c.update("insert into ota_test.bk_probe(v) values (?)", "test $it") }
        }
        val accounts = db.read { c -> count(c, "public.acc_accounts") }
        val work = dir("roundtrip")
        val out = File(work, "out").apply { mkdirs() }

        // Writes keep going while the backup copies the files: the restore must still be consistent.
        val stop = AtomicBoolean(false)
        val during = AtomicInteger()
        val writer = Thread {
            TestDb.server.connect().use { c ->
                while (!stop.get()) {
                    c.update("insert into public.bk_probe(v) values ('during')")
                    during.incrementAndGet()
                }
            }
        }.apply { start() }
        val zip = try {
            BackupArchive.hot(TestDb.server::connect, out, File(work, "staging"), "TEST-PC", "manual", "s3cret-pass".toCharArray(), mapOf("schema" to "6"))
        } finally {
            stop.set(true)
            writer.join()
        }
        assertTrue(zip.isFile && BackupArchive.parse(zip)?.machine == "TEST-PC")
        assertEquals(listOf(zip.name), out.list()!!.toList(), "only the finished backup is left in the folder")
        assertTrue(File(work, "staging").listFiles().orEmpty().isEmpty(), "the staging copy is removed")

        // The manifest and readme are readable without the password; the data is not.
        val check = BackupArchive.inspect(zip, emptyList())
        assertIs<BackupArchive.Check.NeedsPassword>(check)
        assertEquals("TEST-PC", check.info?.getProperty("machine"))
        assertIs<BackupArchive.Check.Ok>(BackupArchive.inspect(zip, listOf("old".toCharArray(), "s3cret-pass".toCharArray())))

        val pgData = File(work, "pgdata")
        val aside = BackupArchive.restore(zip, pgData, "s3cret-pass".toCharArray())
        assertEquals(null, aside)
        val server = EmbeddedDb.start(pgData, File(System.getProperty("java.io.tmpdir"), "ota-test-pg-bin"), File(work, "pg.log"))
        try {
            server.connect().use { c ->
                assertEquals(500, c.one("select count(*) from public.bk_probe where v like 'real %'") { it.getInt(1) })
                assertEquals(200, count(c, "ota_test.bk_probe"))
                assertEquals(accounts, count(c, "public.acc_accounts"))
                val restoredDuring = c.one("select count(*) from public.bk_probe where v = 'during'") { it.getInt(1) }!!
                assertTrue(restoredDuring in 0..during.get())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `a wrong password fails cleanly and leaves the data folder alone`() {
        val work = dir("wrong")
        val backups = AutoBackup(LocalSettings.at(work), TestDb.server::connect, File(work, "staging"), defaultFolder = File(work, "OTA Backups"))
        val zip = runBlocking { backups.backupNow() }
        backups.close()
        val pgData = File(work, "pgdata").apply { mkdirs(); File(this, "PG_VERSION").writeText("17") }

        assertIs<BackupArchive.Check.NeedsPassword>(BackupArchive.inspect(zip, listOf("nope-nope".toCharArray())))
        assertFalse(BackupArchive.opens(zip, "nope-nope".toCharArray()))
        val e = assertFailsWith<IOException> { BackupArchive.restore(zip, pgData, "nope-nope".toCharArray()) }
        assertEquals("Wrong backup password", e.message)
        assertEquals("17", File(pgData, "PG_VERSION").readText())
        assertEquals(listOf("pgdata"), work.list()!!.filter { it.startsWith("pgdata") }, "nothing half-restored or moved aside")
    }

    @Test
    fun `passwords are generated, changeable, and older ones still open older backups`() {
        val work = dir("passwords")
        val settings = LocalSettings.at(work)
        val backups = AutoBackup(settings, TestDb.server::connect, File(work, "staging"), defaultFolder = File(work, "OTA Backups"))
        val first = backups.password
        assertTrue(Regex("[A-Z2-9]{4}(-[A-Z2-9]{4}){4}").matches(first))
        assertEquals(first, LocalSettings.at(work)["backup.password"], "kept on this laptop")
        val old = runBlocking { backups.backupNow() }
        assertFalse(runBlocking { backups.wantsExitBackup() }, "no exit backup right after one")

        assertFailsWith<IllegalArgumentException> { backups.changePassword("short") }
        backups.changePassword("my new password")
        assertEquals(listOf("my new password", first), backups.knownPasswords)
        assertEquals(listOf("my new password", first), AutoBackup.knownPasswords(LocalSettings.at(work)))
        val check = BackupArchive.inspect(old, backups.knownPasswords.map { it.toCharArray() })
        assertIs<BackupArchive.Check.Ok>(check)
        assertEquals(first, String(check.password!!))
        val new = runBlocking { backups.backupNow() }
        assertTrue(BackupArchive.opens(new, "my new password".toCharArray()))
        assertFalse(BackupArchive.opens(new, first.toCharArray()))
        backups.close()

        // A backup from a newer database version is refused before anything is touched.
        val newer = java.util.Properties().apply { setProperty("schema", "999") }
        assertFailsWith<IOException> {
            Restorer.restore(work, File(work, "pgdata"), new, "my new password".toCharArray(), newer, AppVersion.parse("1.0.0")!!)
        }
        assertFalse(File(work, "pgdata").exists())
    }

    @Test
    fun `rotation keeps the newest N of this computer's backups and touches nothing else`() {
        val folder = dir("rotate")
        val mine = (1..6).map { File(folder, "OTA-Business-backup_2025-07-0${it}_101500_OFFICE-PC_daily.zip") }
        val other = (1..3).map { File(folder, "OTA-Business-backup_2025-07-0${it}_090000_HOME-MAC_daily.zip") }
        val foreign = listOf(
            "ota-backup-20250701-before-1.2.0.zip", "OTA-Business-backup_notes.txt", "OTA-Business-backup_2025-07-01_101500_OFFICE-PC_daily.zip.part",
            "holiday photos.zip", "OTA-Business-backup_latest_OFFICE-PC_daily.zip"
        ).map { File(folder, it) }
        (mine + other + foreign).forEach { it.writeText("x") }

        val deleted = BackupArchive.rotate(folder, "OFFICE-PC", 4)
        assertEquals(mine.take(2).map { it.name }, deleted.map { it.name })
        assertTrue(mine.drop(2).all { it.exists() })
        assertTrue((other + foreign).all { it.exists() })
        assertEquals(mine.drop(2).map { it.name }, BackupArchive.list(folder, "OFFICE-PC").map { it.file.name })
    }

    @Test
    fun `PINs are stored only as salted PBKDF2 hashes`() {
        val a = PinHash.hash("2580")
        val b = PinHash.hash("2580")
        assertTrue(a.startsWith("pbkdf2-sha256:${PinHash.ITERATIONS}:") && !a.contains("2580"))
        assertTrue(a != b, "a random salt each time")
        assertTrue(PinHash.verify("2580", a))
        assertFalse(PinHash.verify("2581", a))
        assertFalse(PinHash.verify("2580", "garbage"))
        assertFalse(PinHash.verify("2580", null))
        assertTrue(PinHash.valid("1234") && PinHash.valid("12345678"))
        assertFalse(PinHash.valid("123") || PinHash.valid("123456789") || PinHash.valid("12a4"))
    }

    @Test
    fun `wrong PINs pause entry, also after a restart, and idle time locks`() {
        val work = dir("lock")
        var now = 1_000_000L
        val lock = PinLock(LocalSettings.at(work)) { now }
        assertFalse(lock.enabled)
        assertFalse(lock.locked.value)
        lock.setPin("2580")
        assertFalse(LocalSettings.at(work).file.readText().contains("2580"))

        repeat(PinLock.FREE_TRIES - 1) { assertIs<PinLock.Attempt.Wrong>(lock.unlock("0000")) }
        val pause = assertIs<PinLock.Attempt.Wait>(lock.unlock("0000"))
        assertEquals(now + 30_000, pause.untilMillis)
        // Even the right PIN waits, and a restart doesn't clear the pause.
        val restarted = PinLock(LocalSettings.at(work)) { now }
        assertTrue(restarted.locked.value)
        assertIs<PinLock.Attempt.Wait>(restarted.unlock("2580"))
        now += 30_001
        assertEquals(now + 60_000, assertIs<PinLock.Attempt.Wait>(restarted.unlock("1111")).untilMillis, "each further miss waits longer")
        now += 60_001
        assertEquals(PinLock.Attempt.Ok, restarted.unlock("2580"))
        assertFalse(restarted.locked.value)
        assertIs<PinLock.Attempt.Wrong>(restarted.unlock("0000"), "a success resets the count")

        restarted.idleMinutes = 5
        now += 4 * 60_000
        restarted.checkIdle()
        assertFalse(restarted.locked.value)
        restarted.activity()
        now += 4 * 60_000
        restarted.checkIdle()
        assertFalse(restarted.locked.value)
        now += 60_000
        restarted.checkIdle()
        assertTrue(restarted.locked.value)

        restarted.turnOff()
        assertFalse(restarted.enabled)
        assertFalse(restarted.locked.value)
        assertNotNull(LocalSettings.at(work).file)
    }
}
