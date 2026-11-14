package com.ota.desktop.backup

import com.ota.desktop.db.Migrations
import com.ota.desktop.update.AppVersion
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Properties

/** Puts a backup back as the data folder; shared by Sync & Cloud → Restore and `--restore-backup`. */
object Restorer {
    /** Refuses (before anything is touched) a backup made by a newer database version than this app's. */
    fun checkCompatible(info: Properties?, latestSchema: Int = Migrations.latest) {
        val schema = info?.getProperty("schema")?.toIntOrNull() ?: return
        if (schema > latestSchema) throw IOException(
            "This backup was made by a newer version of OTA Business${info.getProperty("app")?.let { " ($it)" } ?: ""}. " +
                "Update this laptop first, then restore it."
        )
    }

    /**
     * Restores [zip] into [pgData] (PostgreSQL must be stopped, see EmbeddedDb.locked) and resets the
     * data folder's version marker so the next start checks and migrates the restored schema. Returns
     * the previous data folder, which is kept aside.
     */
    fun restore(home: File, pgData: File, zip: File, password: CharArray?, info: Properties?, current: AppVersion): File? {
        checkCompatible(info)
        val aside = BackupArchive.restore(zip, pgData, password)
        val marker = File(home, "app-version.properties")
        val schema = info?.getProperty("schema")
        if (schema == null) {
            marker.delete()
        } else {
            // Opened next by this version: no extra pre-update copy (a safety backup was just taken); migrations still run.
            com.ota.desktop.update.PropsFile.write(
                marker, mapOf("version" to current.toString(), "schema" to schema, "at" to Instant.now().toString())
            )
        }
        return aside
    }
}
