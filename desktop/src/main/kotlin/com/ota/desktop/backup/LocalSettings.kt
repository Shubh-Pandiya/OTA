package com.ota.desktop.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * This laptop's backup and lock settings (`security.properties` in the data folder). Kept out of
 * the database on purpose: restoring a backup must not bring back an older backup password or PIN,
 * and a backup must not carry the password that opens it. Readable by this user only.
 */
class LocalSettings(val file: File) {
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.reader().use { load(it) } }
    }

    @Synchronized
    operator fun get(key: String): String? = props.getProperty(key)

    operator fun set(key: String, value: String?) = update(key to value)

    @Synchronized
    fun update(vararg values: Pair<String, String?>) {
        values.forEach { (k, v) -> if (v == null) props.remove(k) else props[k] = v }
        save()
    }

    private fun save() {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        ownerOnly(tmp)
        tmp.writer().use { props.store(it, "OTA Business: this laptop's backup and lock settings") }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun at(home: File) = LocalSettings(File(home, "security.properties"))

        /** rw for this user only (POSIX; Windows profile folders are already private). */
        fun ownerOnly(f: File) {
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (f.isDirectory) "rwx------" else "rw-------")) }
        }
    }
}
