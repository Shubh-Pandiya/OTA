package com.ota.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.pdf.Marks
import com.ota.desktop.ui.AppShell
import com.ota.desktop.ui.LocalApp
import com.ota.desktop.ui.OtaTheme
import com.ota.desktop.ui.SignInScreen
import com.ota.desktop.ui.IdleWatch
import com.ota.desktop.ui.LockOverlay
import com.ota.desktop.ui.NoticeDialog
import com.ota.desktop.ui.Splash
import com.ota.desktop.ui.UpdateDialog
import com.ota.desktop.ui.UpdatingScreen
import com.ota.desktop.ui.takesOver
import com.ota.desktop.backup.AutoBackup
import com.ota.desktop.backup.BackupArchive
import com.ota.desktop.backup.LocalSettings
import com.ota.desktop.backup.Restorer
import com.ota.desktop.update.StartupBlocked
import com.ota.desktop.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val selfTest = args.indexOf("--self-test")
    if (selfTest >= 0) {
        val report = File(args.getOrNull(selfTest + 1) ?: File(AppPaths.logs, "self-test.txt").path)
        exitProcess(SelfTest.run(report))
    }
    val restore = args.indexOf("--restore-backup")
    if (restore >= 0) exitProcess(restoreBackup(args.getOrNull(restore + 1), args.getOrNull(args.indexOf("--password") + 1)?.takeIf { "--password" in args }))
    val updateRun = args.indexOf("--update-e2e")
    if (updateRun >= 0) exitProcess(updateUnattended(File(args.getOrNull(updateRun + 1) ?: File(AppPaths.logs, "update-e2e.txt").path)))
    var context: AppContext? = null
    Runtime.getRuntime().addShutdownHook(Thread { context?.close() })
    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1360.dp, 860.dp))
        var app by remember { mutableStateOf<AppContext?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        // Replaces the window while the data is closed (restore) or a last backup is being saved.
        var busy by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        var restoring by remember { mutableStateOf(false) }
        val windowScope = rememberCoroutineScope()
        val firstRun = remember { !java.io.File(AppPaths.pgData, "PG_VERSION").exists() }

        suspend fun openData() {
            try {
                val opened = withContext(Dispatchers.IO) { AppContext.open() }
                context = opened
                opened.restartWith = { message, work ->
                    windowScope.launch {
                        busy = message
                        restoring = true
                        app = null
                        context = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching { opened.close() }
                            runCatching { EmbeddedDb.locked { work() } }
                        }
                        notice = result.getOrElse { "The backup was not restored: ${it.message ?: it.javaClass.simpleName}\n\nYour data was not changed." }
                        restoring = false
                        openData()
                        busy = null
                    }
                }
                opened.start()
                app = opened
            } catch (e: EmbeddedDb.AlreadyRunning) {
                error = e.message
            } catch (e: StartupBlocked) {
                File(AppPaths.logs, "startup-error.txt").writeText(e.stackTraceToString())
                error = e.message
            } catch (e: Throwable) {
                File(AppPaths.logs, "startup-error.txt").writeText(e.stackTraceToString())
                error = "The local database could not start: ${e.message ?: e.javaClass.simpleName}.\nDetails: ${File(AppPaths.logs, "startup-error.txt")}"
            }
        }
        LaunchedEffect(Unit) { openData() }

        fun quitNow() {
            context?.close()
            context = null
            exitApplication()
        }
        Window(
            onCloseRequest = {
                val ctx = context
                if (restoring) {
                    // The data folder is being swapped: closing now could leave it half done.
                } else if (ctx == null || closing) {
                    // A second close (or "Close now") stops a backup still running at its next file.
                    quitNow()
                } else {
                    closing = true
                    windowScope.launch {
                        // A last backup if the data changed and none was made in the last few minutes; bounded.
                        if (withTimeoutOrNull(5_000) { ctx.backups.wantsExitBackup() } == true) {
                            busy = "Saving a backup before closing…"
                            val job = windowScope.launch(Dispatchers.IO) { runCatching { ctx.backups.exitBackup() } }
                            withTimeoutOrNull(EXIT_BACKUP_MAX_MS) { job.join() }
                        }
                        withContext(Dispatchers.IO) { context?.close(); context = null }
                        exitApplication()
                    }
                }
            },
            state = state,
            title = "OTA Business",
            icon = remember { LogoPainter() }
        ) {
            window.minimumSize = java.awt.Dimension(1100, 700)
            OtaTheme {
                val current = app
                val waiting = busy
                if (waiting != null) {
                    Splash(waiting, null, onCancel = if (closing) ({ quitNow() }) else null)
                } else if (current == null) {
                    Splash(
                        if (firstRun) "Setting up the local database for the first time.\nThis takes about a minute, only once."
                        else "Starting the local database…",
                        error
                    ) { exitApplication() }
                } else {
                    CompositionLocalProvider(LocalApp provides current) {
                        val session by current.auth.session.collectAsState()
                        val update by current.updates.state.collectAsState()
                        val locked by current.lock.locked.collectAsState()
                        val quit = { context = null; exitProcess(0) }
                        IdleWatch(current.lock)
                        if (update.takesOver()) {
                            // The local database is being closed for the update: no screen may read it.
                            UpdatingScreen(update, quit)
                        } else {
                            Crossfade(session != null) { signedIn ->
                                if (signedIn) AppShell(current) else SignInScreen(current)
                            }
                            UpdateDialog(current, quit)
                            // Covers the signed-in screens (and any open dialog); signing in is a gate of its own.
                            if (locked && session != null) LockOverlay(current)
                        }
                        notice?.let { NoticeDialog("Restore", it) { notice = null } }
                    }
                }
            }
        }
    }
}

/** Longest a closing window waits for its last backup; "Close now" skips it sooner. */
private const val EXIT_BACKUP_MAX_MS = 90_000L

/**
 * `--update-e2e <report>`: the in-app update without the window (CI's update test). Opens the data
 * like a normal start, looks for a newer release (OTA_UPDATE_FEED), then runs exactly the steps
 * "Update now" runs; the installer restarts the new version with `--self-test <report>`.
 */
private fun updateUnattended(report: File): Int {
    val log = File(report.absolutePath + ".updater.txt").apply { absoluteFile.parentFile?.mkdirs() }
    fun note(line: String) = log.appendText("${java.time.Instant.now()} $line\n")
    return try {
        val app = AppContext.open()
        val updates = UpdateManager(UpdateManager.RUNNING, relaunchArgs = listOf("--self-test", report.absolutePath))
        note("running ${updates.current}")
        val release = kotlinx.coroutines.runBlocking { updates.check(manual = true) }
        if (release == null) {
            note("no newer release: ${updates.state.value}")
            app.close()
            return 2
        }
        note("found ${release.version} (${release.installer?.name}, sha256 ${release.installer?.sha256})")
        kotlinx.coroutines.runBlocking {
            updates.install(release, app.updateHost { note("exiting for the installer"); exitProcess(0) })
        }
        note("not installed: ${updates.state.value}")
        app.close()
        1
    } catch (e: Throwable) {
        note("FAILED ${e.stackTraceToString()}")
        1
    }
}

/**
 * `--restore-backup <zip> [--password <pw>]`: puts a backup back (the app must be closed): a
 * pre-update copy from the backups folder or an automatic (encrypted) backup. Encrypted backups open
 * with this laptop's backup passwords, or the one given.
 */
private fun restoreBackup(path: String?, password: String?): Int {
    val zip = path?.let(::File)?.takeIf { it.isFile } ?: run {
        System.err.println("Usage: --restore-backup <backup .zip> [--password <backup password>]  (pre-update copies are in ${File(AppPaths.home, "backups")})")
        return 2
    }
    return try {
        val known = listOfNotNull(password) + AutoBackup.knownPasswords(LocalSettings.at(AppPaths.home))
        when (val check = BackupArchive.inspect(zip, known.map { it.toCharArray() })) {
            is BackupArchive.Check.Invalid -> {
                System.err.println("Restore failed: ${check.reason}")
                1
            }
            is BackupArchive.Check.NeedsPassword -> {
                System.err.println("Restore failed: ${zip.name} is encrypted. Add --password <the backup password>.")
                2
            }
            is BackupArchive.Check.Ok -> {
                val aside = EmbeddedDb.locked { Restorer.restore(AppPaths.home, AppPaths.pgData, zip, check.password, check.info, UpdateManager.RUNNING) }
                println("Restored ${zip.name}." + (aside?.let { " The previous database was kept at $it." } ?: ""))
                0
            }
        }
    } catch (e: Throwable) {
        System.err.println("Restore failed: ${e.message}")
        1
    }
}

/** Window/taskbar icon: the vector logo on white. */
private class LogoPainter : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(256f, 256f)
    private val path = androidx.compose.ui.graphics.Path().apply {
        for (op in Marks.logo) {
            val c = op.coords
            when (op.cmd) {
                'M' -> moveTo(c[0], c[1])
                'L' -> lineTo(c[0], c[1])
                'C' -> cubicTo(c[0], c[1], c[2], c[3], c[4], c[5])
                'Z' -> close()
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        drawRoundRect(androidx.compose.ui.graphics.Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.18f))
        val k = size.minDimension * 0.84f / Marks.VIEWPORT
        val pad = size.minDimension * 0.08f
        drawContext.transform.translate(pad, pad)
        drawContext.transform.scale(k, k, androidx.compose.ui.geometry.Offset.Zero)
        drawPath(path, androidx.compose.ui.graphics.Color(0xFF000000 or Marks.LOGO_RGB.toLong()))
    }
}
