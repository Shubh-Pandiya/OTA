package com.ota.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ota.desktop.ui.Splash
import com.ota.desktop.ui.UpdateDialog
import com.ota.desktop.ui.UpdatingScreen
import com.ota.desktop.ui.takesOver
import com.ota.desktop.update.Backups
import com.ota.desktop.update.StartupBlocked
import com.ota.desktop.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val selfTest = args.indexOf("--self-test")
    if (selfTest >= 0) {
        val report = File(args.getOrNull(selfTest + 1) ?: File(AppPaths.logs, "self-test.txt").path)
        exitProcess(SelfTest.run(report))
    }
    val restore = args.indexOf("--restore-backup")
    if (restore >= 0) exitProcess(restoreBackup(args.getOrNull(restore + 1)))
    val updateRun = args.indexOf("--update-e2e")
    if (updateRun >= 0) exitProcess(updateUnattended(File(args.getOrNull(updateRun + 1) ?: File(AppPaths.logs, "update-e2e.txt").path)))
    var context: AppContext? = null
    Runtime.getRuntime().addShutdownHook(Thread { context?.close() })
    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1360.dp, 860.dp))
        var app by remember { mutableStateOf<AppContext?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        val firstRun = remember { !java.io.File(AppPaths.pgData, "PG_VERSION").exists() }
        LaunchedEffect(Unit) {
            try {
                val opened = withContext(Dispatchers.IO) { AppContext.open() }
                context = opened
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
        Window(
            onCloseRequest = {
                context?.close()
                context = null
                exitApplication()
            },
            state = state,
            title = "OTA Business",
            icon = remember { LogoPainter() }
        ) {
            window.minimumSize = java.awt.Dimension(1100, 700)
            OtaTheme {
                val current = app
                if (current == null) {
                    Splash(
                        if (firstRun) "Setting up the local database for the first time.\nThis takes about a minute, only once."
                        else "Starting the local database…",
                        error
                    ) { exitApplication() }
                } else {
                    CompositionLocalProvider(LocalApp provides current) {
                        val session by current.auth.session.collectAsState()
                        val update by current.updates.state.collectAsState()
                        val quit = { context = null; exitProcess(0) }
                        if (update.takesOver()) {
                            // The local database is being closed for the update: no screen may read it.
                            UpdatingScreen(update, quit)
                        } else {
                            Crossfade(session != null) { signedIn ->
                                if (signedIn) AppShell(current) else SignInScreen(current)
                            }
                            UpdateDialog(current, quit)
                        }
                    }
                }
            }
        }
    }
}

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

/** `--restore-backup <zip>`: puts a backup from the backups folder back (the app must be closed). */
private fun restoreBackup(path: String?): Int {
    val zip = path?.let(::File)?.takeIf { it.isFile } ?: run {
        System.err.println("Usage: --restore-backup <backup .zip from ${File(AppPaths.home, "backups")}>")
        return 2
    }
    return try {
        val aside = EmbeddedDb.locked { Backups.restore(zip, AppPaths.pgData) }
        // The restored data may come from an older version: let the next start check its schema.
        File(AppPaths.home, "app-version.properties").delete()
        println("Restored ${zip.name}." + (aside?.let { " The previous database was kept at $it." } ?: ""))
        0
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
