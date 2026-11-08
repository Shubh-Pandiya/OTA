package com.ota.desktop

import java.io.File

/** Where the app keeps its database, logs and generated PDFs. Nothing needs to be configured. */
object AppPaths {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    val isMac = os.contains("mac")

    /** Per-user data folder: %LOCALAPPDATA%\OTA Business on Windows. `-Dota.home=…` overrides it (tests). */
    val home: File by lazy {
        System.getProperty("ota.home")?.let { return@lazy File(it).apply { mkdirs() } }
        val base = when {
            isWindows -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
            isMac -> File(System.getProperty("user.home"), "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        }
        File(base, "OTA Business").apply { mkdirs() }
    }

    val pgData: File get() = File(home, "pgdata")
    val pgBinaries: File get() = File(home, "pg-bin")
    val logs: File get() = File(home, "logs").apply { mkdirs() }

    /** PDFs go to Documents\OTA Orders, the same layout as on the phones. */
    val documents: File by lazy {
        System.getProperty("ota.documents")?.let { return@lazy File(it) }
        File(File(System.getProperty("user.home"), "Documents"), "OTA Orders")
    }
}
