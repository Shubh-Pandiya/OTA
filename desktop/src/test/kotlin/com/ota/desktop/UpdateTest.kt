package com.ota.desktop

import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import com.ota.desktop.update.AppVersion
import com.ota.desktop.update.Backups
import com.ota.desktop.update.Downloader
import com.ota.desktop.update.Installer
import com.ota.desktop.update.ReleaseFeed
import com.ota.desktop.update.StartupBlocked
import com.ota.desktop.update.StartupGuard
import com.ota.desktop.update.VerificationFailed
import com.ota.desktop.update.WindowsInstaller
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serves a GitHub-style release list and its files from memory. */
class FakeReleases : AutoCloseable {
    val files = HashMap<String, ByteArray>()
    var releases = JSONArray()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/")
            val body = if (path == "releases") releases.toString().toByteArray() else files[path]
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val feed: String get() = "$url/releases"

    /** Adds release [version] with an MSI of [bytes] (digest in the API, plus SHA256SUMS.txt when [sums]). */
    fun publish(version: String, bytes: ByteArray, sums: Boolean = true, digest: String? = sha(bytes), draft: Boolean = false, prerelease: Boolean = false) {
        val msi = "OTA-Business-$version.msi"
        files[msi] = bytes
        val assets = JSONArray().put(JSONObject().put("name", msi).put("size", bytes.size).put("browser_download_url", "$url/$msi").apply { if (digest != null) put("digest", "sha256:$digest") })
        if (sums) {
            files["sums-$version"] = "${sha(bytes)}  $msi\n".toByteArray()
            assets.put(JSONObject().put("name", "SHA256SUMS.txt").put("size", 80).put("browser_download_url", "$url/sums-$version"))
        }
        releases.put(
            JSONObject().put("tag_name", "desktop-v$version").put("draft", draft).put("prerelease", prerelease)
                .put("html_url", "$url/page/$version").put("body", "## What's new\n- Things in $version").put("assets", assets)
        )
    }

    override fun close() = server.stop(0)

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

class UpdateTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun versionsCompareNumerically() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("1.3.0") > v("1.2.1"))
        assertEquals(v("1.3"), v("1.3.0"))
        assertTrue(v("v2.0.0") > v("1.99.99"))
        assertNull(AppVersion.parse("1.3.0-beta"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun feedPicksNewestPublishedDesktopRelease() {
        FakeReleases().use { r ->
            r.publish("1.2.1", byteArrayOf(1))
            r.publish("1.3.0", byteArrayOf(2))
            r.publish("1.4.0", byteArrayOf(3), draft = true)
            r.publish("1.5.0", byteArrayOf(4), prerelease = true)
            // The phone's release tags are not desktop releases.
            r.releases.put(JSONObject().put("tag_name", "v9.0.0").put("assets", JSONArray()))
            val feed = ReleaseFeed(r.feed)
            val found = assertNotNull(feed.newer(v("1.2.1")))
            assertEquals(v("1.3.0"), found.version)
            assertEquals("OTA-Business-1.3.0.msi", found.installer!!.name)
            assertEquals(FakeReleases.sha(byteArrayOf(2)), found.installer!!.sha256)
            assertEquals(FakeReleases.sha(byteArrayOf(2)), feed.publishedChecksum(found, "OTA-Business-1.3.0.msi"))
            assertTrue(found.notes.contains("1.3.0"))
            assertNull(feed.newer(v("1.3.0")), "the newest version is not offered to itself")
            assertNull(feed.newer(v("1.4.0")), "never a downgrade")
        }
    }

    @Test
    fun parsesTheRealGithubFormat() {
        val json = """[{"tag_name":"desktop-v1.2.1","draft":false,"prerelease":false,"html_url":"https://github.com/Shubh-Pandiya/OTA/releases/tag/desktop-v1.2.1","body":"OTA Business for Windows 1.2.1.",
            "assets":[{"name":"OTA-Business-1.2.1.msi","size":96416484,"digest":"sha256:2a40e78f65bb8629d6f4126d35f6a6d1d279efe199244f035b2625ab8250b66f","browser_download_url":"https://github.com/Shubh-Pandiya/OTA/releases/download/desktop-v1.2.1/OTA-Business-1.2.1.msi"},
            {"name":"OTA-Business-Setup-1.2.1.exe","size":97015296,"browser_download_url":"https://x/y.exe"}]}]"""
        val r = ReleaseFeed.parse(json).single()
        assertEquals(Installer("OTA-Business-1.2.1.msi", "https://github.com/Shubh-Pandiya/OTA/releases/download/desktop-v1.2.1/OTA-Business-1.2.1.msi", 96416484, "2a40e78f65bb8629d6f4126d35f6a6d1d279efe199244f035b2625ab8250b66f"), r.installer)
        assertNull(r.checksumsUrl)
        assertEquals(mapOf("a.msi" to "ab".repeat(32)), ReleaseFeed.parseChecksums("${"AB".repeat(32)}  a.msi\nnot a line\n"))
    }

    @Test
    fun downloadIsVerifiedBeforeUse() {
        FakeReleases().use { r ->
            val bytes = ByteArray(300_000) { (it * 31).toByte() }
            r.publish("1.3.0", bytes)
            val dir = Files.createTempDirectory("ota-dl").toFile()
            val release = ReleaseFeed(r.feed).newer(v("1.2.1"))!!
            var last = 0f
            val file = Downloader.fetch(release.installer!!, dir, null) { last = it }
            assertEquals(1f, last)
            assertTrue(file.readBytes().contentEquals(bytes))

            // Tampered in transit: right size, wrong content → refused and nothing left behind.
            r.files["OTA-Business-1.3.0.msi"] = ByteArray(300_000)
            file.delete()
            assertFailsWith<VerificationFailed> { Downloader.fetch(release.installer!!, dir, null) }
            assertTrue(dir.listFiles()!!.isEmpty())
            // Cut short.
            r.files["OTA-Business-1.3.0.msi"] = bytes.copyOf(1000)
            assertFailsWith<VerificationFailed> { Downloader.fetch(release.installer!!, dir, null) }
            // SHA256SUMS.txt and the API digest disagree.
            assertFailsWith<VerificationFailed> { Downloader.fetch(release.installer!!, dir, "00".repeat(32)) }
            dir.deleteRecursively()
        }
    }

    @Test
    fun installerScriptWaitsInstallsAndRestarts() {
        val s = WindowsInstaller.script(File("C:/Data/updates/OTA-Business-1.3.0.msi"), File("C:/Data/updates/i.log"), File("C:/Data/updates/i.result"), 4242,
            File("C:/Users/O'Neil/AppData/Local/OTA Business/OTA Business.exe"), listOf("--self-test", "C:/r e/x.txt"))
        assertTrue(s.contains("Get-Process -Id 4242"))
        assertTrue(s.contains("/passive /norestart"))
        assertTrue(s.contains("O''Neil"), "single quotes escaped for PowerShell")
        assertTrue(s.indexOf("msiexec") < s.lastIndexOf("Start-Process -FilePath"), "restart only after the installer finished")
        assertTrue(s.contains("\"--self-test\" \"C:/r e/x.txt\""))
    }

    @Test
    fun guardBacksUpOnVersionChangeAndRefusesDowngrade() {
        val home = Files.createTempDirectory("ota-guard").toFile()
        val pgData = File(home, "pgdata").apply { mkdirs() }
        File(pgData, "PG_VERSION").writeText("17\n")
        File(pgData, "base/1").apply { mkdirs() }.resolve("1259").writeBytes(ByteArray(5000) { it.toByte() })
        File(pgData, "pg_tblspc").mkdirs()

        // First start of a version with the guard over existing data (e.g. 1.2.1 → 1.3.0 by hand): backed up.
        val first = StartupGuard(home, pgData, v("1.3.0"), latestSchema = 3)
        first.beforeStart()
        val backup = assertNotNull(first.backup)
        assertTrue(backup.name.contains("before-1.3.0"))
        first.started()

        // Same version again: no new backup.
        StartupGuard(home, pgData, v("1.3.0"), 3).apply { beforeStart(); assertNull(this.backup) }
        assertEquals(1, Backups.list(File(home, "backups")).size)

        // Older app over data last opened by 1.3.0: refused before anything starts.
        val e = assertFailsWith<StartupBlocked> { StartupGuard(home, pgData, v("1.2.9"), 3).beforeStart() }
        assertTrue(e.message!!.contains("newer than this version"))

        // Backups keep the empty folders PostgreSQL needs, and restore keeps the current folder aside.
        File(pgData, "base/1/1259").writeText("changed")
        val aside = Backups.restore(backup, pgData)!!
        assertTrue(File(pgData, "pg_tblspc").isDirectory)
        assertEquals(5000, File(pgData, "base/1/1259").length())
        assertEquals("changed", File(aside, "base/1/1259").readText())

        // Only the newest few are kept.
        repeat(7) { Backups.create(pgData, File(home, "backups"), "x$it") }
        assertEquals(Backups.KEEP, Backups.list(File(home, "backups")).size)
        assertFalse(Backups.list(File(home, "backups")).any { it.name.contains("before-1.3.0") })
        home.deleteRecursively()
    }

    @Test
    fun aFailedMigrationChangesNothing() {
        val db = TestDb.fresh()
        db.read { c -> c.createStatement().use { it.execute("insert into kv (key, value) values ('k', 'v')") } }
        val steps = listOf("V1__init.sql", "V2__bill_lines_claims_parties.sql", "V3__bill_transport.sql", "V4__accounts.sql").map { n ->
            n to { Migrations::class.java.getResource("/db/$n")!!.readText() }
        }
        val bad = "V5__bad.sql" to { "create table new_stuff (x int); update kv set value = 'changed'; select no_such_column from kv;" }
        assertFailsWith<Exception> { Migrations.run(db, steps + bad) }
        db.read { c ->
            assertEquals(4, c.one("select max(version) from schema_version") { it.getInt(1) })
            assertEquals("v", c.one("select value from kv where key = 'k'") { it.getString(1) })
            assertEquals(0, c.one("select count(*) from information_schema.tables where table_name = 'new_stuff'") { it.getInt(1) })
        }
        // A database from a newer app (version 9) is refused untouched.
        db.read { c -> c.createStatement().use { it.execute("insert into schema_version (version) values (9)") } }
        val e = assertFailsWith<Migrations.SchemaTooNew> { Migrations.run(db) }
        assertEquals(9, e.found)
    }
}
