package com.ota.desktop

import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import java.io.File
import java.time.Instant

/**
 * `OTA Business.exe --self-test <report file>`: boots the bundled PostgreSQL from the installed
 * app, runs the migrations and a query, then exits. CI runs it on the installed MSI so a missing
 * runtime module or a broken database bundle fails the build instead of the user's first launch.
 */
object SelfTest {
    fun run(report: File): Int {
        val lines = mutableListOf("started ${Instant.now()}", "java ${System.getProperty("java.version")} ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        val code = try {
            EmbeddedDb.start().use { server ->
                lines += "postgres port ${server.port}"
                Db(server::connect).use { db ->
                    Migrations.run(db)
                    val version = db.read { c -> c.one("select max(version) from schema_version") { it.getInt(1) } }
                    val pg = db.read { c -> c.one("select version()") { it.getString(1) } }
                    lines += "schema $version / ${Migrations.latest}"
                    lines += "server $pg"
                    check(version == Migrations.latest) { "schema not migrated" }
                }
            }
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
