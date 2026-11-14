package com.ota.desktop.update

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cold copies of the database folder (pgdata), taken only while PostgreSQL is stopped and the app's
 * instance lock is held, so the copy is exactly what was on disk. The bundled PostgreSQL has no
 * pg_dump; a stopped cluster's files are a complete, consistent backup that restores by unzipping.
 */
object Backups {
    const val KEEP = 5
    private const val PREFIX = "ota-backup-"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }.orEmpty().sortedBy { it.name }

    /** Zips [pgData] into [dir] as `ota-backup-<time>-<label>.zip`, checks the copy, keeps the newest [keep]. */
    fun create(pgData: File, dir: File, label: String, keep: Int = KEEP): File {
        require(File(pgData, "PG_VERSION").exists()) { "No database at $pgData" }
        if (File(pgData, "postmaster.pid").exists()) throw IOException("The database is still running; not copying it")
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".zip.part") }?.forEach { it.delete() }
        val size = pgData.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (dir.usableSpace in 0 until size + 200L * 1024 * 1024) {
            throw IOException("Not enough free disk space for a backup (needs about ${size / 1_048_576 + 200} MB)")
        }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var name = "$PREFIX${LocalDateTime.now().format(stamp)}-$safe.zip"
        var n = 2
        while (File(dir, name).exists()) name = "$PREFIX${LocalDateTime.now().format(stamp)}-$safe-${n++}.zip"
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        var files = 0
        ZipOutputStream(part.outputStream().buffered()).use { zip ->
            pgData.walkTopDown().forEach { f ->
                val rel = f.relativeTo(pgData).invariantSeparatorsPath
                if (rel.isEmpty() || rel == "postmaster.pid") return@forEach
                if (f.isDirectory) {
                    // PostgreSQL needs its empty folders (pg_tblspc, pg_notify, …) back on restore.
                    zip.putNextEntry(ZipEntry("$rel/"))
                } else {
                    zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    files++
                }
                zip.closeEntry()
            }
        }
        // Read every entry back: a damaged copy (CRC mismatch, short write) fails here, not on restore.
        if (check(part) != files) {
            part.delete()
            throw IOException("The backup copy could not be verified")
        }
        if (!part.renameTo(target)) throw IOException("Could not save the backup $target")
        prune(dir, keep)
        return target
    }

    /** Number of files in [zip], reading each one fully (checks every CRC). */
    fun check(zip: File): Int {
        var files = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            val sink = ByteArray(1 shl 16)
            while (true) {
                val e = input.nextEntry ?: break
                if (!e.isDirectory) {
                    while (input.read(sink) >= 0) Unit
                    files++
                }
            }
        }
        return files
    }

    fun prune(dir: File, keep: Int = KEEP) {
        list(dir).dropLast(keep).forEach { it.delete() }
    }

    /**
     * Puts [zip] back as [pgData] (PostgreSQL must be stopped). The current folder is kept aside as
     * `pgdata-before-restore-<time>`, never deleted. Returns that folder, or null if there was none.
     */
    fun restore(zip: File, pgData: File): File? {
        val staging = File(pgData.parentFile, "${pgData.name}.restoring").apply { deleteRecursively() }
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(staging, e.name).canonicalFile
                if (!out.path.startsWith(staging.canonicalPath)) throw IOException("Bad entry ${e.name}")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        if (!File(staging, "PG_VERSION").exists()) {
            staging.deleteRecursively()
            throw IOException("${zip.name} is not a database backup")
        }
        return install(staging, pgData)
    }

    /**
     * Moves an unpacked data folder [staging] in as [pgData] (PostgreSQL must be stopped), keeping the
     * current folder aside as `pgdata-before-restore-<time>`. Returns that folder, or null.
     */
    fun install(staging: File, pgData: File): File? {
        // PostgreSQL refuses a data folder other users can read (only matters off Windows).
        runCatching { java.nio.file.Files.setPosixFilePermissions(staging.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val aside = if (pgData.exists()) File(pgData.parentFile, "${pgData.name}-before-restore-${LocalDateTime.now().format(stamp)}") else null
        if (aside != null && !pgData.renameTo(aside)) throw IOException("Could not move the current database aside")
        if (!staging.renameTo(pgData)) {
            aside?.renameTo(pgData)
            throw IOException("Could not put the backup in place")
        }
        return aside
    }
}
