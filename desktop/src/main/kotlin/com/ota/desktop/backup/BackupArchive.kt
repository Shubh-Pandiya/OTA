package com.ota.desktop.backup

import com.ota.desktop.db.one
import com.ota.desktop.update.Backups
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.CancellationException

/**
 * The automatic backups: a complete copy of the local database (every schema: real data, the test
 * account's data and the Accounts books) taken while the app runs, as an AES-256 encrypted zip.
 *
 * The bundled PostgreSQL has no pg_dump, so this is an online base backup, the same thing
 * pg_basebackup makes: `pg_backup_start` → copy the data folder → `pg_backup_stop` → add its
 * `backup_label` and the WAL written meanwhile. A temporary replication slot keeps that WAL from
 * being recycled while the copy runs. Unzipped, it is a data folder that PostgreSQL recovers to the
 * moment the backup ended on its next start; [restore] does exactly that.
 *
 * Files are named `OTA-Business-backup_<yyyy-MM-dd_HHmmss>_<computer>_<label>.zip`; rotation only
 * ever deletes files matching that exact pattern for this computer, so a shared folder (Google
 * Drive, OneDrive, USB) can hold other files and other laptops' backups safely.
 */
object BackupArchive {
    const val PREFIX = "OTA-Business-backup_"
    const val MANIFEST = "ota-backup.properties"
    const val README = "README - OTA Business backup.txt"
    private val NAME = Regex("""^OTA-Business-backup_(\d{4}-\d{2}-\d{2}_\d{6})_([A-Za-z0-9-]+)_([A-Za-z0-9-]+)\.zip$""")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    private const val MB = 1_048_576L

    /** Folders whose contents PostgreSQL rebuilds on start (pg_basebackup leaves them out too); pg_wal is added at the end. */
    private val CONTENTS_SKIPPED = setOf("pg_wal", "pg_stat_tmp", "pg_replslot", "pg_dynshmem", "pg_notify", "pg_serial", "pg_snapshots", "pg_subtrans")
    private val FILES_SKIPPED = setOf(
        "postmaster.pid", "postmaster.opts", "backup_label", "backup_label.old", "tablespace_map", "tablespace_map.old",
        "current_logfiles", "global/pg_control",
        // The embedded server's own lock file: held open (locked) while it runs, unreadable on Windows.
        "epg-lock"
    )

    data class Name(val file: File, val time: LocalDateTime, val machine: String, val label: String)

    fun clean(s: String): String = s.replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(40).ifEmpty { "x" }

    /** This computer's name as it appears in backup file names (may be slow on some Macs: call off the UI thread). */
    val machine: String by lazy {
        clean(
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "laptop"
        )
    }

    fun parse(f: File): Name? = NAME.matchEntire(f.name)?.let { m ->
        runCatching { Name(f, LocalDateTime.parse(m.groupValues[1], stamp), m.groupValues[2], m.groupValues[3]) }.getOrNull()
    }

    /** This app's backups in [dir] (all computers, or only [machine]'s), oldest first. */
    fun list(dir: File, machine: String? = null): List<Name> =
        dir.listFiles { f -> f.isFile }.orEmpty().mapNotNull(::parse)
            .filter { machine == null || it.machine.equals(machine, ignoreCase = true) }
            .sortedWith(compareBy({ it.time }, { it.file.name }))

    /** Keeps [machine]'s newest [keep] backups in [dir]; returns what was deleted. Never touches other files. */
    fun rotate(dir: File, machine: String, keep: Int): List<File> =
        list(dir, machine).dropLast(keep.coerceAtLeast(1)).map { it.file }.filter { it.delete() }

    fun newFile(dir: File, machine: String, label: String, now: LocalDateTime = LocalDateTime.now()): File {
        var t = now
        while (true) {
            val f = File(dir, "$PREFIX${t.format(stamp)}_${clean(machine)}_${clean(label)}.zip")
            if (!f.exists()) return f
            t = t.plusSeconds(1)
        }
    }

    /**
     * Takes an online backup of the server behind [connect] into [dir]. The zip is built and fully
     * read back in [staging] (a local folder), then copied into [dir] under a temporary name and
     * renamed, so a synced folder never sees a half-written backup. [cancelled] aborts it cleanly.
     */
    fun hot(
        connect: () -> Connection,
        dir: File,
        staging: File,
        machine: String,
        label: String,
        password: CharArray?,
        info: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false }
    ): File {
        if (!dir.isDirectory) throw IOException("The backup folder $dir is not available")
        staging.mkdirs()
        LocalSettings.ownerOnly(staging)
        staging.listFiles()?.forEach { it.delete() }
        val target = newFile(dir, machine, label)
        val part = File(staging, target.name + ".part")
        try {
            connect().use { c ->
                c.autoCommit = true
                val data = File(c.one("show data_directory") { it.getString(1) }!!)
                val size = data.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (staging.usableSpace in 0 until size + 100 * MB) throw IOException("Not enough free disk space for a backup (needs about ${size / MB + 100} MB)")
                // Keeps the WAL from the backup's start until the session ends (dropped automatically).
                c.one("select 1 from pg_create_physical_replication_slot(?, true, true)", "ota_backup_${System.nanoTime().toString(36)}") { it.getInt(1) }
                val start = c.one("select pg_backup_start(?, true)::text", "OTA ${target.name}") { it.getString(1) }!!
                val manifest = linkedMapOf(
                    "format" to "1", "kind" to "online", "label" to label, "machine" to machine,
                    "created" to LocalDateTime.now().toString(), "encrypted" to (password != null).toString(),
                    "postgres" to (c.one("show server_version") { it.getString(1) } ?: "")
                ) + info
                ZipWriter(part, password, cancelled).use { zip ->
                    zip.text(README, readme(manifest), encrypt = false)
                    zip.text(MANIFEST, Properties().apply { putAll(manifest) }.let { p -> java.io.StringWriter().also { p.store(it, null) }.toString() }, encrypt = false)
                    data.walkTopDown().forEach { f ->
                        val rel = f.relativeTo(data).invariantSeparatorsPath
                        if (rel.isEmpty()) return@forEach
                        val top = rel.substringBefore('/')
                        if (top in CONTENTS_SKIPPED && rel.contains('/')) {
                            // Left out entirely, sub-folders too (e.g. a replication slot's folder without its state file).
                        } else if (f.isDirectory) {
                            zip.dir(rel)
                        } else if (rel !in FILES_SKIPPED &&
                            f.name != "pg_internal.init" && !f.name.startsWith("pgsql_tmp")
                        ) {
                            zip.file(rel, f)
                        }
                    }
                    zip.file("global/pg_control", File(data, "global/pg_control"))
                    val (lsn, labelFile, mapFile) = c.one("select lsn::text, labelfile, spcmapfile from pg_backup_stop(false)") {
                        Triple(it.getString(1), it.getString(2), it.getString(3))
                    }!!
                    zip.text("backup_label", labelFile, encrypt = true)
                    if (!mapFile.isNullOrBlank()) zip.text("tablespace_map", mapFile, encrypt = true)
                    val first = c.one("select pg_walfile_name(?::pg_lsn)", start) { it.getString(1) }!!
                    val last = c.one("select pg_walfile_name(?::pg_lsn)", lsn) { it.getString(1) }!!
                    File(data, "pg_wal").listFiles { f -> f.isFile }.orEmpty()
                        .filter { f -> f.name.endsWith(".history") || (f.name.length == 24 && f.name.all { it.isDigit() || it in 'A'..'F' } && f.name >= first && f.name <= last) }
                        .sortedBy { it.name }
                        .forEach { zip.file("pg_wal/${it.name}", it) }
                    if (zip.files < 10) throw IOException("The database folder looks empty; no backup made")
                }
            }
            if (cancelled()) throw CancellationException("Backup cancelled")
            // Read every entry back (AES authentication + CRC): a damaged copy fails here, not on restore.
            val count = verify(part, password)
            if (!count.contains("PG_VERSION") || !count.contains("backup_label")) throw IOException("The backup copy could not be verified")
            val copying = File(dir, target.name + ".part")
            try {
                Files.copy(part.toPath(), copying.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (copying.length() != part.length()) throw IOException("The backup could not be written to $dir")
                moveRetrying(copying, target)
            } finally {
                copying.delete()
            }
            return target
        } finally {
            part.delete()
        }
    }

    private fun moveRetrying(from: File, to: File) {
        var last: IOException? = null
        repeat(10) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: IOException) {
                // A sync client (OneDrive, Google Drive) may hold the new file for a moment.
                last = e
                Thread.sleep(500)
            }
        }
        throw last!!
    }

    /** Reads every file in [zip] (checking each one) and returns their names. */
    fun verify(zip: File, password: CharArray?): Set<String> {
        val names = mutableSetOf<String>()
        ZipFile(zip, password).use { z ->
            val sink = ByteArray(1 shl 16)
            for (h in z.fileHeaders) {
                if (h.isDirectory) continue
                z.getInputStream(h).use { input -> while (input.read(sink) >= 0) Unit }
                names += h.fileName
            }
        }
        return names
    }

    sealed interface Check {
        /** Restorable: [password] opens it (null for an unencrypted backup). */
        class Ok(val password: CharArray?, val info: Properties?) : Check
        class NeedsPassword(val info: Properties?) : Check
        class Invalid(val reason: String) : Check
    }

    /** What [zip] is, and which of [passwords] (current first, then older ones) opens it. */
    fun inspect(zip: File, passwords: List<CharArray>): Check {
        val headers = try {
            ZipFile(zip).use { z -> if (!z.isValidZipFile) null else z.fileHeaders.toList() }
        } catch (e: ZipException) {
            null
        } ?: return Check.Invalid("${zip.name} is not a zip file.")
        val pgVersion = headers.firstOrNull { it.fileName == "PG_VERSION" } ?: return Check.Invalid("${zip.name} is not an OTA Business backup.")
        val info = headers.firstOrNull { it.fileName == MANIFEST && !it.isEncrypted }?.let {
            runCatching { ZipFile(zip).use { z -> z.getInputStream(it).use { s -> Properties().apply { load(s) } } } }.getOrNull()
        }
        if (!pgVersion.isEncrypted) return Check.Ok(null, info)
        val pw = passwords.firstOrNull { opens(zip, it) } ?: return Check.NeedsPassword(info)
        return Check.Ok(pw, info)
    }

    /** Whether [password] opens [zip] (reads one small encrypted file). */
    fun opens(zip: File, password: CharArray): Boolean = try {
        ZipFile(zip, password).use { z -> z.getInputStream(z.getFileHeader("PG_VERSION")).use { it.readBytes() } }
        true
    } catch (e: ZipException) {
        false
    }

    /**
     * Puts [zip] (an automatic backup, or a pre-update one) back as [pgData]; PostgreSQL must be
     * stopped. The current folder is kept aside (see [Backups.install]). Returns that folder.
     */
    fun restore(zip: File, pgData: File, password: CharArray?): File? {
        val staging = File(pgData.parentFile, "${pgData.name}.restoring").apply { deleteRecursively(); mkdirs() }
        LocalSettings.grantCurrentUser(staging)
        try {
            ZipFile(zip, password).use { z ->
                val root = staging.canonicalPath + File.separator
                for (h in z.fileHeaders) {
                    if (h.fileName == MANIFEST || h.fileName == README) continue
                    val out = File(staging, h.fileName).canonicalFile
                    if (!(out.path + File.separator).startsWith(root)) throw IOException("Bad entry ${h.fileName}")
                    if (h.isDirectory) out.mkdirs() else {
                        out.parentFile.mkdirs()
                        z.getInputStream(h).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            if (!File(staging, "PG_VERSION").exists()) throw IOException("${zip.name} is not a database backup")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            if (e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD) throw IOException("Wrong backup password", e)
            throw e
        }
        return Backups.install(staging, pgData)
    }

    private fun readme(m: Map<String, String>) = """
        OTA Business backup
        ===================
        Made ${m["created"]?.take(19)?.replace('T', ' ')} on ${m["machine"]} by OTA Business ${m["app"] ?: ""} (${m["label"]}).

        A complete copy of that laptop's OTA Business data: orders, bills, payment advices, buyers and
        suppliers, the Accounts books and the test account's data (a PostgreSQL ${m["postgres"]} data folder).

        To restore it: install OTA Business, open Sync & Cloud > Backups > Restore..., and pick this file.

        ${if (m["encrypted"] == "true") "The data is encrypted with AES-256. The password is the backup password shown in\n        Sync & Cloud > Backups (Show) on the laptop that made it. 7-Zip, WinZip, Keka or The Unarchiver\n        can open it with that password; the zip viewer built into Windows cannot." else "This backup is not encrypted."}
    """.trimIndent().replace("\n", "\r\n")

    private class ZipWriter(target: File, private val password: CharArray?, private val cancelled: () -> Boolean) : Closeable {
        private val out = ZipOutputStream(target.outputStream().buffered(1 shl 16), password)
        private val buf = ByteArray(1 shl 16)
        var files = 0
            private set

        private fun params(name: String, encrypt: Boolean, time: Long) = ZipParameters().apply {
            fileNameInZip = name
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.FAST
            lastModifiedFileTime = if (time > 0) time else System.currentTimeMillis()
            if (encrypt && password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

        fun dir(name: String) {
            out.putNextEntry(ZipParameters().apply { fileNameInZip = "$name/"; lastModifiedFileTime = System.currentTimeMillis() })
            out.closeEntry()
        }

        /** Copies [f] in; a file PostgreSQL removed meanwhile is skipped (the WAL replay recreates what matters). */
        fun file(name: String, f: File) {
            if (cancelled()) throw CancellationException("Backup cancelled")
            // NIO opens files shareable for delete/rename on Windows, so PostgreSQL is never blocked.
            val input = try {
                Files.newInputStream(f.toPath())
            } catch (_: NoSuchFileException) {
                return
            }
            input.use {
                out.putNextEntry(params(name, true, f.lastModified()))
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.closeEntry()
            }
            files++
        }

        fun text(name: String, text: String, encrypt: Boolean) {
            out.putNextEntry(params(name, encrypt, System.currentTimeMillis()))
            out.write(text.toByteArray())
            out.closeEntry()
            files++
        }

        override fun close() = out.close()
    }
}
