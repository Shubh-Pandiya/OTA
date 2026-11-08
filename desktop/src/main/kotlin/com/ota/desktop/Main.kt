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
                        Crossfade(session != null) { signedIn ->
                            if (signedIn) AppShell(current) else SignInScreen(current)
                        }
                    }
                }
            }
        }
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
