package com.ota.desktop

import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Migrations
import java.io.File
import java.nio.file.Files

/** One embedded Postgres for the whole test run; each test gets a fresh, empty schema. */
object TestDb {
    private val home: File = Files.createTempDirectory("ota-test").toFile()
    private val sharedBin = File(System.getProperty("java.io.tmpdir"), "ota-test-pg-bin")

    val server: EmbeddedDb by lazy {
        EmbeddedDb.start(File(home, "pgdata"), sharedBin, File(home, "pg.log")).also { srv ->
            Runtime.getRuntime().addShutdownHook(Thread { srv.close(); home.deleteRecursively() })
        }
    }

    private val opened = mutableListOf<Db>()

    /** Drops every table and runs the migrations again. */
    fun fresh(): Db {
        // Earlier tests' pools would otherwise keep their connections open until the JVM exits and
        // run into max_connections (20). A pool that is still in use just reopens what it needs.
        synchronized(opened) { opened.forEach { it.close() }; opened.clear() }
        val db = Db(server::connect).also { synchronized(opened) { opened += it } }
        db.read { c -> c.createStatement().use { it.execute("drop schema public cascade; create schema public") } }
        Migrations.run(db)
        return db
    }
}
