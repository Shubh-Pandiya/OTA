package com.ota.desktop.db

import com.ota.desktop.AppPaths
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The app's own PostgreSQL server: binaries come inside the installer, the database cluster is
 * created on first launch under the user's AppData folder, and the server runs only while the app
 * runs, on a free localhost port. Nothing to install or configure.
 *
 * Security: the server only listens on localhost and the cluster lives in the user's own profile
 * folder. It uses local "trust" authentication, so there is no password to store or leak.
 */
class EmbeddedDb private constructor(private val pg: EmbeddedPostgres, val port: Int, private val lock: FileLock) : AutoCloseable {

    fun connect(): Connection = DriverManager.getConnection("jdbc:postgresql://localhost:$port/$DB_NAME?user=postgres&ApplicationName=ota-desktop")

    override fun close() {
        runCatching { pg.close() }.onFailure { it.printStackTrace() }
        runCatching { lock.release(); lock.channel().close() }
    }

    class AlreadyRunning : IllegalStateException("OTA Business is already open in another window.")

    companion object {
        const val DB_NAME = "ota"

        /**
         * [beforeStart] runs with the instance lock held and no server using [dataDir] (a leftover
         * one from a crash is stopped first): the moment to copy or check the data folder.
         */
        fun start(
            dataDir: File = AppPaths.pgData,
            binDir: File = AppPaths.pgBinaries,
            logFile: File = File(AppPaths.logs, "postgres.log"),
            beforeStart: () -> Unit = {}
        ): EmbeddedDb {
            val lock = instanceLock(dataDir)
            try {
                prepareDataDir(dataDir)
                clearStalePid(dataDir, binDir)
                beforeStart()
                return startLocked(dataDir, binDir, logFile, lock)
            } catch (e: Throwable) {
                runCatching { lock.release(); lock.channel().close() }
                throw e
            }
        }

        /** One app window per data folder: a second launch is told the app is already open. */
        private fun instanceLock(dataDir: File): FileLock {
            val parent = dataDir.absoluteFile.parentFile.apply { mkdirs() }
            val channel = FileChannel.open(File(parent, "${dataDir.name}.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw AlreadyRunning()
            }
            return lock
        }

        /** Runs [block] holding the instance lock, without starting the server (backup restore). */
        fun <T> locked(dataDir: File = AppPaths.pgData, binDir: File = AppPaths.pgBinaries, block: () -> T): T {
            val lock = instanceLock(dataDir)
            try {
                clearStalePid(dataDir, binDir)
                return block()
            } finally {
                runCatching { lock.release(); lock.channel().close() }
            }
        }

        private fun startLocked(dataDir: File, binDir: File, logFile: File, lock: FileLock): EmbeddedDb {
            binDir.mkdirs()
            val log = ProcessBuilder.Redirect.appendTo(logFile)
            val pg = try {
                EmbeddedPostgres.builder()
                    .setDataDirectory(dataDir)
                    .setCleanDataDirectory(false)
                    .setOverrideWorkingDirectory(binDir)
                    .setLocaleConfig("locale", "C")
                    .setLocaleConfig("encoding", "UTF8")
                    .setServerConfig("listen_addresses", "localhost")
                    .setServerConfig("max_connections", "20")
                    .setServerConfig("shared_buffers", "32MB")
                    .setServerConfig("timezone", "Asia/Kolkata")
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .setOutputRedirector(log)
                    .setErrorRedirector(log)
                    .setDataDirectoryCustomizer { dir -> clearStalePid(dir, binDir) }
                    .start()
            } catch (e: IllegalStateException) {
                if (e.message?.contains("could not lock") == true) throw AlreadyRunning()
                throw e
            } catch (_: OverlappingFileLockException) {
                throw AlreadyRunning()
            }
            val db = EmbeddedDb(pg, pg.port, lock)
            removeOldBinaries(pg, binDir)
            pg.postgresDatabase.connection.use { c ->
                val exists = c.prepareStatement("select 1 from pg_database where datname = ?").use { st ->
                    st.setString(1, DB_NAME); st.executeQuery().use { it.next() }
                }
                if (!exists) c.createStatement().use { it.execute("create database $DB_NAME") }
            }
            return db
        }

        /**
         * The binaries are unpacked into [binDir]/PG-<archive digest>, so an app version with a different
         * PostgreSQL archive unpacks a new folder and the old one (up to a few hundred MB) stays behind.
         * Once the server runs, other PG-* folders are unused: delete them in the background. If one is
         * ever needed again (an older version reinstalled), it is simply unpacked again. Only the app's own
         * folder is cleaned (it's covered by the single-instance lock); a folder shared by tests is not.
         */
        private fun removeOldBinaries(pg: EmbeddedPostgres, binDir: File) {
            if (binDir.absoluteFile != AppPaths.pgBinaries.absoluteFile) return
            val current = runCatching {
                EmbeddedPostgres::class.java.getDeclaredField("pgDir").apply { isAccessible = true }.get(pg) as File
            }.getOrNull()?.absoluteFile ?: return
            if (current.parentFile != binDir.absoluteFile) return
            val old = binDir.listFiles { f -> f.isDirectory && f.name.startsWith("PG-") && f.absoluteFile != current }.orEmpty()
            if (old.isEmpty()) return
            Thread({ old.forEach { runCatching { it.deleteRecursively() } } }, "remove-old-postgres").apply { isDaemon = true }.start()
        }

        /** A first launch that was interrupted halfway leaves a cluster without its config: start over. */
        private fun prepareDataDir(dataDir: File) {
            if (!dataDir.exists() || File(dataDir, "postgresql.conf").exists()) return
            if (File(dataDir, "base").exists()) {
                // Has data but no config: keep it aside rather than deleting anything.
                dataDir.renameTo(File(dataDir.parentFile, "pgdata-broken-${System.currentTimeMillis()}"))
            } else {
                dataDir.deleteRecursively()
            }
        }

        /**
         * After a crash or power cut the server's postmaster.pid stays behind; on Windows a reused
         * process id can make the next start fail. Stop any server still using the folder, then
         * remove the leftover file.
         */
        private fun clearStalePid(dataDir: File, binDir: File) {
            val pid = File(dataDir, "postmaster.pid")
            if (!pid.exists() || !dataDir.isDirectory) return
            val pgCtl = binDir.walkTopDown().firstOrNull { it.isFile && (it.name == "pg_ctl" || it.name == "pg_ctl.exe") }
            if (pgCtl != null) {
                runCatching {
                    ProcessBuilder(pgCtl.absolutePath, "-D", dataDir.absolutePath, "stop", "-m", "fast", "-w", "-t", "20")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                        .waitFor(30, TimeUnit.SECONDS)
                }
            }
            if (pid.exists()) pid.delete()
        }
    }
}
