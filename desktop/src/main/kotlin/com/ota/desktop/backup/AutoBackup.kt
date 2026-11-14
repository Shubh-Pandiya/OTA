package com.ota.desktop.backup

import com.ota.desktop.db.one
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic encrypted backups of this laptop's database: once a day while the app runs and when it
 * closes (only if something changed), to a folder the user picks: a USB drive, or a Google Drive /
 * OneDrive folder that syncs it off the laptop. The newest [keep] are kept.
 *
 * Backups run on one low-priority background thread and never block the screens. The password is
 * generated on first use and kept in [settings] (this laptop only); older passwords are remembered
 * so older backups restore without asking.
 */
class AutoBackup(
    private val settings: LocalSettings,
    private val connect: () -> Connection,
    /** Local scratch folder for building a backup before it is copied out. */
    private val staging: File,
    /** Extra manifest lines (app version, schema). */
    private val info: () -> Map<String, String> = { emptyMap() },
    private val machine: () -> String = { BackupArchive.machine },
    private val clock: () -> Long = System::currentTimeMillis,
    val defaultFolder: File = File(File(System.getProperty("user.home"), "Documents"), "OTA Backups")
) {
    data class Status(
        val running: Boolean = false,
        val lastAt: Long? = null,
        val lastFile: String? = null,
        val error: String? = null,
        val folder: File,
        val keep: Int
    )

    private val _status = MutableStateFlow(read(false))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val busy = AtomicBoolean(false)
    @Volatile private var cancelRequested = false

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ota-backup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    val folder: File get() = settings[FOLDER]?.let(::File) ?: defaultFolder
    val keep: Int get() = settings[KEEP]?.toIntOrNull()?.coerceIn(MIN_KEEP, MAX_KEEP) ?: DEFAULT_KEEP

    /** The password new backups are encrypted with (made on first use). */
    val password: String
        @Synchronized get() = settings[PASSWORD] ?: generatePassword().also { settings[PASSWORD] = it }

    /** Every password this laptop has used, newest first: tried in turn on restore. */
    val knownPasswords: List<String>
        get() = listOf(password) + previous()

    private fun previous(): List<String> = previous(settings)

    @Synchronized
    fun changePassword(new: String) {
        require(new.length >= MIN_PASSWORD) { "Use at least $MIN_PASSWORD characters" }
        val old = settings[PASSWORD]
        val history = (listOfNotNull(old) + previous()).filter { it != new }.distinct().take(20)
        settings.update(PASSWORD to new, PREVIOUS to JSONArray(history).toString())
    }

    /** Remembers a password that opened a backup from elsewhere, for later restores. */
    @Synchronized
    fun remember(pw: String) {
        if (pw in knownPasswords) return
        settings[PREVIOUS] = JSONArray((previous() + pw).distinct().take(20)).toString()
    }

    fun setFolder(dir: File) {
        settings[FOLDER] = dir.absolutePath
        publish()
    }

    fun setKeep(n: Int) {
        settings[KEEP] = n.coerceIn(MIN_KEEP, MAX_KEEP).toString()
        publish()
    }

    /** Checks once a day (from [initialDelay] after start) and backs up when the last one is a day old and data changed. */
    fun start(scope: CoroutineScope, initialDelay: Duration = Duration.ofMinutes(3), every: Duration = Duration.ofMinutes(30)) {
        cleanStaging()
        scope.launch {
            delay(initialDelay.toMillis())
            while (true) {
                if (dailyDue()) runCatching { withContext(thread) { run("daily", onlyIfChanged = true) } }
                delay(every.toMillis())
            }
        }
    }

    fun dailyDue(): Boolean = settings[LAST_AT]?.toLongOrNull()?.let { clock() - it >= DAY_MS } ?: true

    /** "Back up now": always makes a new backup. */
    suspend fun backupNow(label: String = "manual"): File = withContext(thread) { run(label, onlyIfChanged = false)!! }

    /**
     * On closing the window: true when a backup is worth the wait (none in the last [recent] and
     * the data changed since the last one). Quick: one small query.
     */
    suspend fun wantsExitBackup(recent: Duration = Duration.ofMinutes(10)): Boolean {
        // One already running (the daily one) is stopped by close(); don't queue behind it.
        if (busy.get()) return false
        return withContext(thread) { exitDue(recent) }
    }

    private fun exitDue(recent: Duration): Boolean {
        val last = settings[LAST_AT]?.toLongOrNull()
        if (last != null && clock() - last < recent.toMillis()) return false
        return runCatching { changed(changeMark()) }.getOrDefault(false)
    }

    suspend fun exitBackup(): File? = withContext(thread) { run("on-exit", onlyIfChanged = true) }

    /** Stops a running backup at its next file (it leaves nothing behind). */
    fun cancel() {
        if (busy.get()) cancelRequested = true
    }

    /** Waits up to [millis] for a running backup to finish or notice [cancel]. */
    fun awaitIdle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (busy.get() && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    fun close() {
        cancel()
        awaitIdle(5_000)
        thread.close()
    }

    /** Total rows inserted/updated/deleted in the data tables (PostgreSQL's statistics); kv cursors don't count. */
    internal fun changeMark(): String? = runCatching {
        connect().use { c ->
            c.one(
                "select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0)::text " +
                    "from pg_stat_all_tables where schemaname in ('public', 'ota_test') and relname <> 'kv'"
            ) { it.getString(1) }
        }
    }.getOrNull()

    private fun changed(mark: String?): Boolean {
        val lastFile = settings[LAST_FILE]?.let(::File)
        if (lastFile == null || !lastFile.isFile || lastFile.absoluteFile.parentFile != folder.absoluteFile) return true
        return mark == null || mark != settings[LAST_MARK]
    }

    private fun run(label: String, onlyIfChanged: Boolean): File? {
        if (!busy.compareAndSet(false, true)) {
            if (onlyIfChanged) return null
            throw IOException("A backup is already running")
        }
        cancelRequested = false
        publish()
        try {
            val mark = changeMark()
            if (onlyIfChanged && !changed(mark)) return null
            val dir = folder
            if (dir == defaultFolder) dir.mkdirs()
            if (!dir.isDirectory) throw IOException("The backup folder $dir is not available. Plug in the drive or choose another folder.")
            val file = BackupArchive.hot(connect, dir, staging, machine(), label, password.toCharArray(), info(), cancelled = { cancelRequested })
            runCatching { BackupArchive.rotate(dir, machine(), keep) }
            settings.update(LAST_AT to clock().toString(), LAST_FILE to file.absolutePath, LAST_MARK to mark, LAST_ERROR to null)
            log("backup ${file.name} (${file.length() / 1024} KB)")
            return file
        } catch (e: Throwable) {
            if (e !is CancellationException && !cancelRequested) {
                settings[LAST_ERROR] = e.message ?: e.javaClass.simpleName
                log("backup ($label) failed: ${e.message}")
            }
            throw e
        } finally {
            busy.set(false)
            cancelRequested = false
            publish()
        }
    }

    private fun cleanStaging() = runCatching { staging.listFiles()?.forEach { it.delete() } }

    private fun publish() {
        _status.value = read(busy.get())
    }

    private fun read(running: Boolean) = Status(
        running = running,
        lastAt = settings[LAST_AT]?.toLongOrNull(),
        lastFile = settings[LAST_FILE],
        error = settings[LAST_ERROR],
        folder = folder,
        keep = keep
    )

    private fun log(line: String) {
        runCatching { File(staging.parentFile, "logs/backup.log").apply { parentFile.mkdirs() }.appendText("${Instant.now()} $line\n") }
    }

    companion object {
        const val DEFAULT_KEEP = 14
        const val MIN_KEEP = 2
        const val MAX_KEEP = 365
        const val MIN_PASSWORD = 8
        private const val DAY_MS = 24L * 3600 * 1000
        private const val FOLDER = "backup.folder"
        private const val KEEP = "backup.keep"
        private const val PASSWORD = "backup.password"
        private const val PREVIOUS = "backup.passwords.previous"
        private const val LAST_AT = "backup.last.at"
        private const val LAST_FILE = "backup.last.file"
        private const val LAST_MARK = "backup.last.mark"
        private const val LAST_ERROR = "backup.last.error"

        /** The passwords saved in [settings] without making one (for `--restore-backup`). */
        fun knownPasswords(settings: LocalSettings): List<String> = listOfNotNull(settings[PASSWORD]) + previous(settings)

        private fun previous(settings: LocalSettings): List<String> =
            settings[PREVIOUS]?.let { runCatching { JSONArray(it).let { a -> (0 until a.length()).map(a::getString) } }.getOrNull() }.orEmpty()

        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** 20 random characters (100 bits) in groups of four, easy to read out and type: `K7QM-3XPA-…`. */
        fun generatePassword(random: SecureRandom = SecureRandom()): String =
            (1..5).joinToString("-") { (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }
    }
}
