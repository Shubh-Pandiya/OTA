package com.ota.desktop

import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import com.ota.desktop.update.DataSnapshot
import com.ota.desktop.update.StartupGuard
import com.ota.desktop.update.UpdateManager
import java.io.File
import java.time.Instant

/**
 * `OTA Business.exe --self-test <report file>`: boots the bundled PostgreSQL from the installed
 * app through the same start-up path as a normal launch (version guard, pre-update backup,
 * migrations), runs a query, then exits. CI runs it on the installed MSI so a missing runtime module
 * or a broken database bundle fails the build instead of the user's first launch. The report lists
 * every table's row count and checksum, which the update test compares across versions.
 */
object SelfTest {
    fun run(report: File): Int {
        val lines = mutableListOf(
            "started ${Instant.now()}",
            "app ${UpdateManager.RUNNING}",
            "java ${System.getProperty("java.version")} ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        )
        val code = try {
            val guard = StartupGuard(AppPaths.home, AppPaths.pgData, UpdateManager.RUNNING)
            lines += "last-run ${guard.lastVersion ?: "none"}"
            EmbeddedDb.start(beforeStart = guard::beforeStart).use { server ->
                lines += "postgres port ${server.port}"
                Db(server::connect).use { db ->
                    guard.migrate(db)
                    guard.started()
                    guard.backup?.let { lines += "backup ${it.absolutePath}" }
                    val version = db.read { c -> c.one("select max(version) from schema_version") { it.getInt(1) } }
                    val pg = db.read { c -> c.one("select version()") { it.getString(1) } }
                    lines += "schema $version / ${Migrations.latest}"
                    lines += "server $pg"
                    check(version == Migrations.latest) { "schema not migrated" }
                    DataSnapshot.take(db).forEach { (table, sum) -> lines += "table $table $sum" }
                }
            }
            UpdateManager(UpdateManager.RUNNING).consumeOutcome()?.let { lines += "update ${if (it.ok) "ok" else "failed"}: ${it.message.lineSequence().first()}" }
            lines += "OK"
            0
        } catch (e: Throwable) {
            lines += "FAILED ${e::class.java.name}: ${e.message}"
            lines += e.stackTraceToString()
            1
        }
        report.absoluteFile.parentFile?.mkdirs()
        report.writeText(lines.joinToString("\n") + "\n")
        return code
    }
}
