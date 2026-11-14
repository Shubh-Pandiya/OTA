package com.example.otaapplication.security

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Opens this phone's SQLite files through SQLCipher. A database written by an older version (plain
 * SQLite) is converted in place on first open: copied into an encrypted file with
 * `sqlcipher_export`, checked, then swapped in. Nothing is lost if the conversion is interrupted:
 * the plain file stays until the encrypted copy is complete.
 */
object EncryptedDb {
    private const val TAG = "EncryptedDb"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Volatile private var loaded = false

    fun loadLibrary() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }

    /**
     * Readies database [name] (null = in memory) and returns the key to open it with. Encrypts a
     * plain file left by an older version. If this phone's key can no longer be read (Keystore
     * reset), the unreadable files are set aside and a fresh database is started; synced data
     * then comes back from the cloud on the next sync.
     */
    @Synchronized
    fun keyFor(context: Context, name: String?): ByteArray {
        loadLibrary()
        val key = try {
            LocalKeys.databaseKey(context)
        } catch (e: Exception) {
            Log.e(TAG, "Database key unreadable; setting local databases aside", e)
            setAsideEncryptedDatabases(context)
            LocalKeys.resetDatabaseKey(context)
            LocalKeys.databaseKey(context)
        }
        if (name == null) return key
        val file = context.getDatabasePath(name)
        if (isPlain(file)) encryptInPlace(file, key)
        return key
    }

    fun isPlain(file: File): Boolean {
        if (!file.isFile || file.length() < PLAIN_HEADER.size) return false
        val header = ByteArray(PLAIN_HEADER.size)
        file.inputStream().use { if (it.read(header) != header.size) return false }
        return header.contentEquals(PLAIN_HEADER)
    }

    private fun encryptInPlace(file: File, key: ByteArray) {
        val temp = File(file.parentFile, file.name + ".encrypting")
        sidecars(temp).forEach { it.delete() }
        temp.delete()
        // ATTACH opens the new file with the main database's flags, so they must allow creating it.
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        SQLiteDatabase.openDatabase(file.path, "", null, flags, null, null).use { plain ->
            val version = plain.version
            val keySql = String(key, Charsets.US_ASCII).replace("\"", "")
            plain.execSQL("ATTACH DATABASE '${temp.path.replace("'", "''")}' AS encrypted KEY \"$keySql\"")
            plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
            plain.execSQL("PRAGMA encrypted.user_version = $version")
            plain.execSQL("DETACH DATABASE encrypted")
        }
        // Prove the copy opens with the key before replacing the original.
        SQLiteDatabase.openDatabase(temp.path, key, null, SQLiteDatabase.OPEN_READONLY, null, null).use { enc ->
            enc.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
        }
        sidecars(file).forEach { it.delete() }
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not replace ${file.name} with its encrypted copy" }
        }
        Log.i(TAG, "Encrypted ${file.name}")
    }

    private fun setAsideEncryptedDatabases(context: Context) {
        val dir = context.getDatabasePath("x").parentFile ?: return
        val stamp = System.currentTimeMillis()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".db") && !isPlain(it) }?.forEach { db ->
            (listOf(db) + sidecars(db)).filter { it.exists() }.forEach { it.renameTo(File(dir, "${it.name}.unreadable-$stamp")) }
        }
    }

    private fun sidecars(file: File) = listOf("-journal", "-wal", "-shm").map { File(file.path + it) }
}
