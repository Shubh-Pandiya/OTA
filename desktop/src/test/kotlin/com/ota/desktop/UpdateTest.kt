package com.ota.desktop

import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import com.ota.desktop.update.AppVersion
import com.ota.desktop.update.Backups
import com.ota.desktop.update.Downloader
import com.ota.desktop.update.Installer
import com.ota.desktop.update.MacInstaller
import com.ota.desktop.update.Platform
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
    /** Download responses still to be cut off halfway. */
    @Volatile var drops = 0
    /** Range (resume) requests served. */
    @Volatile var ranges = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/")
            val full = if (path == "releases") releases.toString().toByteArray() else files[path]
            val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (full != null && from != null) ranges++
            val body = if (full != null && from != null) full.copyOfRange(from, full.size) else full
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(if (from != null) 206 else 200, body.size.toLong())
                if (path != "releases" && drops > 0) {
                    // A dropped connection: half the bytes, then gone.
                    drops--
                    runCatching { ex.responseBody.write(body, 0, body.size / 2); ex.responseBody.flush() }
                } else ex.responseBody.use { it.write(body) }
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
            val feed = ReleaseFeed(r.feed, platform = Platform.WINDOWS)
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
            val release = ReleaseFeed(r.feed, platform = Platform.WINDOWS).newer(v("1.2.1"))!!
            var last = 0f
            val file = Downloader.fetch(release.installer!!, dir, null) { last = it }
            assertEquals(1f, last)
            assertTrue(file.readBytes().contentEquals(bytes))

            // Tampered in transit: right size, wrong content → refused and nothing left behind.
            r.files["OTA-Business-1.3.0.msi"] = ByteArray(300_000)
            file.delete()
            assertFailsWith<VerificationFailed> { Downloader.fetch(release.installer!!, dir, null) }
            assertTrue(dir.listFiles()!!.isEmpty())
            // Cut short every time: retried, then given up.
            r.files["OTA-Business-1.3.0.msi"] = bytes.copyOf(1000)
            assertFailsWith<java.io.IOException> { Downloader.fetch(release.installer!!, dir, null, backoff = {}) }
            assertTrue(dir.listFiles()!!.isEmpty())
            // SHA256SUMS.txt and the API digest disagree.
            assertFailsWith<VerificationFailed> { Downloader.fetch(release.installer!!, dir, "00".repeat(32)) }
            dir.deleteRecursively()
        }
    }

    @Test
    fun droppedDownloadResumesWhereItStopped() {
        FakeReleases().use { r ->
            val bytes = ByteArray(400_000) { (it * 7).toByte() }
            r.publish("1.3.0", bytes)
            val dir = Files.createTempDirectory("ota-dl").toFile()
            val release = ReleaseFeed(r.feed, platform = Platform.WINDOWS).newer(v("1.2.1"))!!
            r.drops = 2
            var retries = 0
            val file = Downloader.fetch(release.installer!!, dir, null, backoff = { retries++ })
            assertTrue(file.readBytes().contentEquals(bytes))
            assertEquals(2, retries)
            assertEquals(2, r.ranges, "the later attempts asked only for the missing bytes")
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
        val steps = listOf("V1__init.sql", "V2__bill_lines_claims_parties.sql", "V3__bill_transport.sql", "V4__accounts.sql", "V5__hard_deletes.sql", "V6__test_schema.sql").map { n ->
            n to { Migrations::class.java.getResource("/db/$n")!!.readText() }
        }
        val bad = "V7__bad.sql" to { "create table new_stuff (x int); update kv set value = 'changed'; select no_such_column from kv;" }
        assertFailsWith<Exception> { Migrations.run(db, steps + bad) }
        db.read { c ->
            assertEquals(6, c.one("select max(version) from schema_version") { it.getInt(1) })
            assertEquals("v", c.one("select value from kv where key = 'k'") { it.getString(1) })
            assertEquals(0, c.one("select count(*) from information_schema.tables where table_name = 'new_stuff'") { it.getInt(1) })
        }
        // A database from a newer app (version 9) is refused untouched.
        db.read { c -> c.createStatement().use { it.execute("insert into schema_version (version) values (9)") } }
        val e = assertFailsWith<Migrations.SchemaTooNew> { Migrations.run(db) }
        assertEquals(9, e.found)
    }

    @Test
    fun eachComputerPicksItsOwnInstaller() {
        val names = listOf("OTA-Business-1.5.0.msi", "OTA-Business-Setup-1.5.0.exe", "OTA-Business-1.5.0-mac-arm64.dmg", "SHA256SUMS.txt")
        assertEquals("OTA-Business-1.5.0.msi", Platform.WINDOWS.pick(names))
        assertEquals("OTA-Business-1.5.0-mac-arm64.dmg", Platform.MAC_ARM.pick(names))
        assertNull(Platform.MAC_INTEL.pick(names), "an Apple-silicon build is never offered to an Intel Mac")
        assertEquals("OTA-Business-1.5.0.dmg", Platform.MAC_INTEL.pick(listOf("OTA-Business-1.5.0.dmg")))
        // Older releases have only Windows installers: a Mac finds no installer and isn't offered one.
        val json = """[{"tag_name":"desktop-v1.4.0","assets":[{"name":"OTA-Business-1.4.0.msi","size":1,"browser_download_url":"u"}]},
            {"tag_name":"desktop-v1.5.0","assets":[{"name":"OTA-Business-1.5.0-mac-arm64.dmg","size":2,"browser_download_url":"d"},{"name":"OTA-Business-1.5.0.msi","size":3,"browser_download_url":"m"}]}]"""
        val mac = ReleaseFeed.parse(json, Platform.MAC_ARM)
        assertNull(mac.first { it.tag == "desktop-v1.4.0" }.installer)
        assertEquals("d", mac.first { it.tag == "desktop-v1.5.0" }.installer!!.url)
        assertEquals("m", ReleaseFeed.parse(json, Platform.WINDOWS).first { it.tag == "desktop-v1.5.0" }.installer!!.url)
        assertTrue(ReleaseFeed.parse(json, null).all { it.installer == null })
    }

    @Test
    fun macBundlesThatCannotBeReplacedAreNotSelfUpdated() {
        assertNotNull(MacInstaller.replaceableIssue("/private/var/folders/x/AppTranslocation/ABC/d/OTA Business.app"))
        assertNotNull(MacInstaller.replaceableIssue("/Volumes/OTA Business/OTA Business.app"))
        assertNull(MacInstaller.replaceableIssue("/Applications/OTA Business.app"))
    }

    /** The real helper script on a real DMG: swaps the bundle, keeps data outside it, restores on failure. */
    @Test
    fun macScriptSwapsTheBundleFromADmg() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val dir = Files.createTempDirectory("ota-mac-update").toFile()
        fun bundle(root: File, version: String) = File(root, "OTA Business.app").apply {
            File(this, "Contents/MacOS").mkdirs()
            File(this, "Contents/version.txt").writeText(version)
        }
        val installed = bundle(File(dir, "Applications").apply { mkdirs() }, "1.5.0")
        val data = File(dir, "Application Support/OTA Business Data/pgdata/PG_VERSION").apply { parentFile.mkdirs(); writeText("17") }
        val src = File(dir, "src").apply { mkdirs() }
        bundle(src, "1.5.1")
        val dmg = File(dir, "update.dmg")
        val made = ProcessBuilder("hdiutil", "create", "-quiet", "-fs", "HFS+", "-volname", "OTA Business", "-srcfolder", src.path, "-format", "UDZO", dmg.path)
            .inheritIO().start().waitFor()
        // Sandboxed shells may forbid making or mounting disk images; CI runners must not skip.
        if (made != 0 && System.getenv("CI") == null) { dir.deleteRecursively(); return }
        assertEquals(0, made, "hdiutil create")
        val probe = Files.createTempDirectory("ota-probe").toFile()
        val mounted = ProcessBuilder("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", probe.path, dmg.path)
            .redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0
        if (mounted) ProcessBuilder("hdiutil", "detach", probe.path, "-quiet").start().waitFor()
        if (!mounted && System.getenv("CI") == null) { dir.deleteRecursively(); return }

        fun run(dmgFile: File): Int {
            val log = File(dir, "install.log"); val result = File(dir, "result.txt").apply { delete() }
            // A finished process stands in for the app; "true" stands in for `open` by not passing a bundle path to open.
            val dead = ProcessBuilder("true").start().apply { waitFor() }.pid()
            val script = File(dir, "install.sh")
            script.writeText(MacInstaller.script(dmgFile, log, result, dead, installed, emptyList()).replace("open -n", "echo would open"))
            val code = ProcessBuilder("/bin/bash", script.path).inheritIO().start().waitFor()
            assertEquals(0, code, log.readText())
            return result.readText().trim().toInt()
        }
        assertEquals(0, run(dmg))
        assertEquals("1.5.1", File(installed, "Contents/version.txt").readText())
        assertFalse(File(installed.path + ".old").exists() || File(installed.path + ".new").exists())
        assertEquals("17", data.readText(), "the data folder is outside the bundle")

        // A broken download leaves the installed app as it was.
        val bad = File(dir, "bad.dmg").apply { writeText("not a disk image") }
        assertEquals(2, run(bad))
        assertEquals("1.5.1", File(installed, "Contents/version.txt").readText())
        dir.deleteRecursively()
    }
}
