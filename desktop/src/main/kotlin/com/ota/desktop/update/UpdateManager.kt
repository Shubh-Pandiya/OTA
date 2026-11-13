package com.ota.desktop.update

import com.ota.desktop.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val manual: Boolean) : UpdateState
    data class Available(val release: Release, val canInstall: Boolean) : UpdateState
    data class Working(val release: Release, val step: UpdateStep, val progress: Float? = null) : UpdateState
    /** [restart]: the database was already closed, so the only way on is to restart the app. */
    data class Failed(val message: String, val release: Release? = null, val restart: Boolean = false) : UpdateState
    /** The result of an update started by the previous run, shown once. */
    data class Finished(val ok: Boolean, val message: String) : UpdateState
}

enum class UpdateStep(val label: String) {
    DOWNLOADING("Downloading the update"),
    VERIFYING("Checking the download"),
    SYNCING("Sending your unsynced changes to the cloud"),
    STOPPING("Closing the local database"),
    BACKING_UP("Backing up your data"),
    INSTALLING("Installing. OTA Business will restart by itself.")
}

/** What the update needs from the running app: one last sync, then a clean stop. */
interface UpdateHost {
    /** Pushes pending changes if signed in and online; returns a note when they stay pending. */
    suspend fun syncBeforeUpdate(): String?

    /** Stops background sync and the local PostgreSQL cleanly (no more writes after this). */
    fun shutdown()

    /** Ends the process (the installer waits for it). */
    fun exit()
}

/**
 * Checks GitHub for a newer desktop release on start and every [every], and installs it on request:
 * download → verify (size + SHA-256) → final sync → stop PostgreSQL → cold backup → run the MSI →
 * restart. Nothing is installed without the user's "Update now".
 */
class UpdateManager(
    val current: AppVersion,
    private val home: File = AppPaths.home,
    private val pgData: File = AppPaths.pgData,
    private val feed: ReleaseFeed = ReleaseFeed(),
    /** Whether this computer can install updates by itself (Windows MSI, or a Mac app bundle). */
    private val selfInstall: Boolean = AppPaths.isWindows || (AppPaths.isMac && MacInstaller.appBundle() != null),
    private val every: Duration = Duration.ofHours(6),
    private val snooze: Duration = Duration.ofHours(24),
    /** Arguments for the restarted app (the CI update run restarts it in self-test mode). */
    private val relaunchArgs: List<String> = emptyList(),
    private val launcher: (installer: File, log: File, result: File, args: List<String>) -> Unit =
        if (AppPaths.isMac) MacInstaller::launch else WindowsInstaller::launch
) {
    companion object {
        /** This build's version (gradle.properties `otaDesktopVersion`). */
        val RUNNING: AppVersion = AppVersion.parse(com.example.otaapplication.BuildConfig.VERSION_NAME) ?: error("Bad version ${com.example.otaapplication.BuildConfig.VERSION_NAME}")
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()
    /** Installs run here, not in a screen's scope: the screens go away while the update runs. */
    private val installScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var snoozed: Pair<AppVersion, Instant>? = null

    val updatesDir: File get() = File(home, "updates")
    val backupsDir: File get() = File(home, "backups")

    /** Checks on start (after [initialDelay]) and then every [every]. */
    fun start(scope: CoroutineScope, initialDelay: Duration = Duration.ofSeconds(10)) {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(initialDelay.toMillis())
            while (true) {
                check(manual = false)
                delay(every.toMillis())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Looks for a newer release. A background check stays silent when offline or snoozed. */
    suspend fun check(manual: Boolean): Release? = mutex.withLock {
        val busy = _state.value
        if (busy is UpdateState.Working || busy is UpdateState.Finished) return@withLock null
        if (manual) _state.value = UpdateState.Checking
        val found = try {
            withContext(Dispatchers.IO) { feed.newer(current) }
        } catch (e: Exception) {
            log("check failed: ${e.message}")
            _state.value = if (manual) UpdateState.Failed("Could not check for updates: ${e.message ?: "no connection"}") else UpdateState.Idle
            return@withLock null
        }
        if (found == null) {
            _state.value = if (manual) UpdateState.UpToDate(true) else UpdateState.Idle
            return@withLock null
        }
        val quiet = snoozed?.let { (v, until) -> !manual && v == found.version && Instant.now() < until } == true
        if (!quiet || _state.value is UpdateState.Available) _state.value = UpdateState.Available(found, selfInstall && found.installer != null)
        found
    }

    /** "Later": hides the prompt for this version until the next start or [snooze]. */
    fun later() {
        val s = _state.value
        if (s is UpdateState.Available) snoozed = s.release.version to Instant.now().plus(snooze)
        if (s !is UpdateState.Working) _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Working) _state.value = UpdateState.Idle
    }

    fun installAsync(release: Release, host: UpdateHost) {
        if (_state.value is UpdateState.Working) return
        _state.value = UpdateState.Working(release, UpdateStep.DOWNLOADING, 0f)
        installScope.launch { install(release, host) }
    }

    /**
     * Installs [release]. Before anything is stopped the installer is downloaded and verified; a
     * failure up to that point leaves the app running as it was.
     */
    suspend fun install(release: Release, host: UpdateHost) = withContext(Dispatchers.IO) {
        val installer = release.installer ?: run {
            _state.value = UpdateState.Failed("This release has no installer for this computer.", release)
            return@withContext
        }
        stop()
        val msi = try {
            _state.value = UpdateState.Working(release, UpdateStep.DOWNLOADING, 0f)
            val published = runCatching { feed.publishedChecksum(release, installer.name) }.getOrNull()
            if (published == null && installer.sha256 == null) throw VerificationFailed("The release has no published checksum, so the download cannot be verified")
            Downloader.fetch(installer, updatesDir, published) { p -> _state.value = UpdateState.Working(release, UpdateStep.DOWNLOADING, p) }
                .also { _state.value = UpdateState.Working(release, UpdateStep.VERIFYING) }
        } catch (e: Exception) {
            log("download of ${release.version} failed: ${e.message}")
            _state.value = UpdateState.Failed("The update could not be downloaded: ${e.message ?: e.javaClass.simpleName}. Nothing was changed.", release)
            return@withContext
        }

        _state.value = UpdateState.Working(release, UpdateStep.SYNCING)
        val note = runCatching { host.syncBeforeUpdate() }.getOrElse { it.message }
        note?.let { log("before ${release.version}: $it (pending changes stay in the local database)") }

        _state.value = UpdateState.Working(release, UpdateStep.STOPPING)
        host.shutdown()

        _state.value = UpdateState.Working(release, UpdateStep.BACKING_UP)
        val backup = try {
            Backups.create(pgData, backupsDir, "before-${release.version}")
        } catch (e: Exception) {
            log("backup before ${release.version} failed: ${e.message}")
            _state.value = UpdateState.Failed(
                "Your data could not be backed up (${e.message}), so the update was not installed. Nothing was changed; please restart OTA Business.",
                release, restart = true
            )
            return@withContext
        }

        _state.value = UpdateState.Working(release, UpdateStep.INSTALLING)
        val log = File(updatesDir, "install-${release.version}.log")
        val result = File(updatesDir, "install-${release.version}.result")
        PendingUpdate(current.toString(), release.version.toString(), backup.absolutePath, msi.absolutePath, log.absolutePath, result.absolutePath, Instant.now().toString()).save(home)
        log("installing ${release.version} from ${msi.name}; backup ${backup.name}")
        try {
            launcher(msi, log, result, relaunchArgs)
        } catch (e: Exception) {
            PendingUpdate.clear(home)
            _state.value = UpdateState.Failed("The installer could not be started: ${e.message}. Nothing was changed; please restart OTA Business.", release, restart = true)
            return@withContext
        }
        delay(400)
        host.exit()
    }

    /** Reports (once) how the update started by the previous run went, and tidies the downloads. */
    fun consumeOutcome(): UpdateState.Finished? {
        val pending = PendingUpdate.read(home) ?: return null
        PendingUpdate.clear(home)
        val target = AppVersion.parse(pending.to)
        val outcome = if (target != null && current >= target) {
            updatesDir.listFiles { f -> f.name.endsWith(".msi", true) || f.name.endsWith(".dmg", true) || f.name.endsWith(".part") }?.forEach { it.delete() }
            UpdateState.Finished(true, "OTA Business was updated to $current. Your data was backed up first" + (pending.backup?.let { ":\n$it" } ?: "."))
        } else {
            val code = pending.exitCode()
            val why = when (code) {
                null -> "it did not run"
                1602 -> "it was cancelled"
                1618 -> "another installation was in progress"
                in 2..4 -> if (AppPaths.isMac) MacInstaller.reason(code)!! else "the installer stopped with code $code"
                else -> "the installer stopped with code $code"
            }
            UpdateState.Finished(false, "The update to ${pending.to} was not installed ($why). You are still on $current and your data is as it was." + (pending.log?.let { "\nInstaller log: $it" } ?: ""))
        }
        log(outcome.message.lineSequence().first())
        _state.value = outcome
        return outcome
    }

    /** Screenshots and previews only. */
    internal fun preview(state: UpdateState) {
        _state.value = state
    }

    private fun log(line: String) {
        runCatching { File(File(home, "logs").apply { mkdirs() }, "update.log").appendText("${Instant.now()} $line\n") }
    }
}
