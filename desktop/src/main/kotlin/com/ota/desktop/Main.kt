package com.ota.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val selfTest = args.indexOf("--self-test")
    if (selfTest >= 0) {
        val report = File(args.getOrNull(selfTest + 1) ?: File(AppPaths.logs, "self-test.txt").path)
        exitProcess(SelfTest.run(report))
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "OTA Business") {
            Text("OTA Business")
        }
    }
}
