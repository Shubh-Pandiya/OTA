package com.ota.desktop.update

import com.ota.desktop.db.Db
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import java.io.File
import java.time.Instant
import java.util.Properties

/** The app refuses to open the data (too new for this version, or it could not be updated safely). */
class StartupBlocked(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Small key=value files in the data folder, written to a temp file first and then moved in place. */
internal object PropsFile {
    fun read(file: File): Properties? = if (!file.isFile) null else runCatching { Properties().apply { file.reader().use { load(it) } } }.getOrNull()

    fun write(file: File, values: Map<String, String?>) {
        file.parentFile.mkdirs()
        val p = Properties()
        values.forEach { (k, v) -> if (v != null) p[k] = v }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writer().use { p.store(it, null) }
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

/** An update the app started: what it backed up and what the installer should leave behind. */
data class PendingUpdate(val from: String, val to: String, val backup: String?, val installer: String?, val log: String?, val result: String?, val startedAt: String) {
    fun save(home: File) = PropsFile.write(file(home), mapOf("from" to from, "to" to to, "backup" to backup, "installer" to installer, "log" to log, "result" to result, "startedAt" to startedAt))

    /** msiexec's exit code written by the helper script, if it ran. */
    fun exitCode(): Int? = result?.let(::File)?.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    companion object {
        fun file(home: File) = File(File(home, "updates"), "pending.properties")

        fun read(home: File): PendingUpdate? = PropsFile.read(file(home))?.let { p ->
            PendingUpdate(
                p.getProperty("from") ?: return null, p.getProperty("to") ?: return null, p.getProperty("backup"),
                p.getProperty("installer"), p.getProperty("log"), p.getProperty("result"), p.getProperty("startedAt") ?: ""
            )
        }

        fun clear(home: File) {
            file(home).delete()
        }
    }
}

/**
 * Protects the local database across app versions. Runs on every start, in three steps:
 *
 * 1. [beforeStart] (instance lock held, PostgreSQL not running yet): refuses data last opened by a
 *    newer version, and when the app version changed takes a cold backup of pgdata before anything
 *    touches it (unless the in-app updater already took one for exactly this step). This also
 *    covers a manual install over an older version.
 * 2. [migrate]: the schema migrations run in one transaction, so a failure leaves the database
 *    exactly as it was; the user gets a clear message with the backup's location.
 * 3. [started]: records this version (and schema) as the data folder's last-run version.
 */
class StartupGuard(
    private val home: File,
    private val pgData: File,
    private val current: AppVersion,
    private val latestSchema: Int = Migrations.latest
) {
    private val marker = File(home, "app-version.properties")
    private val last: Properties? = PropsFile.read(marker)
    val lastVersion: AppVersion? = AppVersion.parse(last?.getProperty("version"))
    private val lastSchema: Int? = last?.getProperty("schema")?.toIntOrNull()

    /** The backup covering this start (taken now or by the updater), if the version changed. */
    var backup: File? = null
        private set

    val backupsDir: File get() = File(home, "backups")

    fun beforeStart() {
        val previous = lastVersion
        if (previous != null && previous > current) {
            throw StartupBlocked(
                "This computer's data was last opened by OTA Business $previous, which is newer than this version ($current).\n" +
                    "Opening it with an older version could damage it, so nothing was opened. Please install version $previous or newer."
            )
        }
        if (!File(pgData, "PG_VERSION").exists() || previous == current) return
        val pending = PendingUpdate.read(home)
        val prepared = pending?.takeIf { AppVersion.parse(it.to) == current }?.backup?.let(::File)?.takeIf { it.isFile }
        if (prepared != null) {
            backup = prepared
            return
        }
        backup = try {
            Backups.create(pgData, backupsDir, "before-$current")
        } catch (e: Throwable) {
            // Without pending schema changes the database is not touched by this version; go on.
            if (lastSchema != null && lastSchema >= latestSchema) {
                log("backup before $current skipped: ${e.message}")
                null
            } else throw StartupBlocked(
                "OTA Business $current needs to update your local database, but could not first make a safety copy of it:\n" +
                    "${e.message ?: e.javaClass.simpleName}.\nNothing was changed. Free some disk space and open the app again.",
                e
            )
        }
    }

    fun migrate(db: Db) {
        try {
            Migrations.run(db)
        } catch (e: Migrations.SchemaTooNew) {
            throw StartupBlocked(
                "This computer's data was created by a newer version of OTA Business (database version ${e.found}, this app knows up to ${e.supported}).\n" +
                    "Nothing was opened or changed. Please install the latest version.",
                e
            )
        } catch (e: Throwable) {
            val copy = backup?.let { "\nA copy taken before the update is kept at:\n$it" } ?: ""
            val back = lastVersion?.let { " To keep working, reinstall version $it." } ?: ""
            throw StartupBlocked(
                "OTA Business $current could not update your local database: ${e.message ?: e.javaClass.simpleName}.\n" +
                    "Nothing was changed: the update was rolled back and your data is exactly as before.$back$copy",
                e
            )
        }
    }

    fun started() {
        PropsFile.write(marker, mapOf("version" to current.toString(), "schema" to latestSchema.toString(), "at" to Instant.now().toString()))
    }

    private fun log(line: String) {
        runCatching { File(File(home, "logs").apply { mkdirs() }, "update.log").appendText("${Instant.now()} $line\n") }
    }
}

/** Row count and checksum of every real-data table: compares the data before and after an update. */
object DataSnapshot {
    fun take(db: Db): Map<String, String> = db.read { c ->
        val tables = c.query("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by table_name") { it.getString(1) }
        tables.associateWith { t ->
            c.one("select count(*) || ' ' || coalesce(md5(string_agg(x::text, E'\\n' order by x::text)), '-') from public.\"$t\" x") { it.getString(1) }!!
        }
    }
}
