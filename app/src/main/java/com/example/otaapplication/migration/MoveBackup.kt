package com.example.otaapplication.migration

import android.content.Context
import android.content.SharedPreferences
import com.example.otaapplication.data.AdviceImageFiles
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.reminders.TodoStore
import com.example.otaapplication.security.EncryptedDb
import com.example.otaapplication.security.LocalKeys
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything on this phone that the new app needs, in one file encrypted with [MoveCrypto]:
 * the databases (orders, bills, advices, catalog, pending changes, sync position) with their key,
 * to-dos, cheque/transaction pictures, saved order PDFs and settings. Sign-in tokens and this
 * install's own keys are left out: the new app makes its own and the user signs in once.
 */
object MoveBackup {
    private const val FORMAT = 1
    private const val MANIFEST = "manifest.json"
    val DATABASES = listOf(OtaDatabase.DB_NAME, TodoStore.DB_NAME)
    private val SKIP_PREFS = setOf("ota_secure", "ota_updates", AppMove.PREFS)
    private val AUTH_KEYS = setOf("auth_access_token", "auth_refresh_token", "auth_expires_at", "auth_user_id", "auth_email")
    private const val IMAGES_DIR = "advice_images"

    data class Summary(val databases: Int, val images: Int, val files: Int, val settings: Int)

    fun export(context: Context, out: OutputStream, code: String, versionName: String): Summary {
        val app = context.applicationContext
        // Everything written so far goes into the main database files before they are copied.
        listOf(OtaDatabase.get(app).writableDatabase, TodoStore.get(app).writableDatabase).forEach { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
        }
        var dbs = 0
        var images = 0
        var files = 0
        val prefs = JSONObject()
        prefNames(app).filter { it !in SKIP_PREFS }.forEach { name ->
            val values = JSONObject()
            app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (name == "ota_cloud" && key in AUTH_KEYS) return@forEach
                encode(value)?.let { values.put(key, it) }
            }
            prefs.put(name, values)
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("from", app.packageName)
            .put("version", versionName)
            .put("createdAt", System.currentTimeMillis())
            .put("dbKey", LocalKeys.exportDatabaseKey(app).joinToString("") { "%02x".format(it) })
            .put("prefs", prefs)
        ZipOutputStream(MoveCrypto.encrypt(out, code)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            fun put(name: String, write: (OutputStream) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                write(zip)
                zip.closeEntry()
            }
            put(MANIFEST) { it.write(manifest.toString().toByteArray()) }
            DATABASES.forEach { name ->
                val file = app.getDatabasePath(name)
                if (file.isFile) {
                    put("db/$name") { o -> file.inputStream().use { it.copyTo(o) } }
                    dbs++
                }
            }
            AdviceImageFiles.forContext(app).exportAll().forEach { (path, bytes) ->
                put("images/$path") { it.write(bytes) }
                images++
            }
            app.filesDir.walkTopDown()
                .onEnter { it == app.filesDir || it.relativeTo(app.filesDir).invariantSeparatorsPath.substringBefore('/') != IMAGES_DIR }
                .filter { it.isFile }
                .forEach { f ->
                    put("files/" + f.relativeTo(app.filesDir).invariantSeparatorsPath) { o -> f.inputStream().use { it.copyTo(o) } }
                    files++
                }
        }
        return Summary(dbs, images, files, prefs.length())
    }

    /**
     * Restores a move file into this (new) app. Everything is decrypted and checked into a staging
     * folder first; this app's data is only replaced once the whole file proved complete and
     * genuine and its database opens with the carried key.
     */
    fun restore(context: Context, input: InputStream, code: String): Summary {
        val app = context.applicationContext
        val staging = File(app.noBackupFilesDir, "move-in").apply { deleteRecursively(); mkdirs() }
        try {
            val plain = MoveCrypto.decrypt(BufferedInputStream(input, 1 shl 16), code)
            val zip = ZipInputStream(plain)
            val root = staging.canonicalFile
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val target = File(staging, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("The move file is damaged")
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
            // Read to the very end so a file cut short is noticed (the last chunk is authenticated).
            val rest = ByteArray(1 shl 14)
            while (plain.read(rest) >= 0) Unit
            zip.close()

            val manifest = File(staging, MANIFEST).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
                ?: throw IOException("The move file is damaged")
            if (manifest.optInt("format") != FORMAT) throw IOException("This move file was made by a newer app; update this app first")
            val dbKey = manifest.optString("dbKey").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            if (dbKey.size != 32) throw IOException("The move file is damaged")

            EncryptedDb.loadLibrary()
            val stagedDbs = DATABASES.map { File(staging, "db/$it") }.filter { it.isFile }
            stagedDbs.forEach { f ->
                SQLiteDatabase.openDatabase(f.path, LocalKeys.rawKeyLiteral(dbKey), null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
                    db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
                }
            }

            // Checked: now replace this app's data.
            DATABASES.forEach { name ->
                val live = app.getDatabasePath(name)
                (listOf(live) + listOf("-journal", "-wal", "-shm").map { File(live.path + it) }).forEach { it.delete() }
            }
            LocalKeys.importDatabaseKey(app, dbKey)
            stagedDbs.forEach { f ->
                val live = app.getDatabasePath(f.name).apply { parentFile?.mkdirs() }
                f.copyTo(live, overwrite = true)
            }

            var files = 0
            File(staging, "files").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                f.copyTo(File(app.filesDir, f.relativeTo(File(staging, "files")).path), overwrite = true)
                files++
            }

            val store = AdviceImageFiles.forContext(app)
            store.clear()
            var images = 0
            val imageRoot = File(staging, "images")
            imageRoot.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                store.write(f.relativeTo(imageRoot).invariantSeparatorsPath, f.readBytes())
                images++
            }

            val prefs = manifest.optJSONObject("prefs") ?: JSONObject()
            prefs.keys().forEach { name ->
                if (name in SKIP_PREFS) return@forEach
                val values = prefs.getJSONObject(name)
                val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.keys().forEach { key ->
                    if (name == "ota_cloud" && key in AUTH_KEYS) return@forEach
                    decode(edit, key, values.getJSONObject(key))
                }
                edit.commit()
            }
            app.getSharedPreferences(AppMove.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(AppMove.KEY_MOVED_IN_AT, System.currentTimeMillis())
                .putString(AppMove.KEY_MOVED_FROM, manifest.optString("version"))
                .commit()
            return Summary(stagedDbs.size, images, files, prefs.length())
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun prefNames(context: Context): List<String> =
        File(context.dataDir, "shared_prefs").listFiles { f -> f.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filterNot { it.startsWith("androidx.") || it.startsWith("WebView") }
            .orEmpty()

    private fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    private fun decode(edit: SharedPreferences.Editor, key: String, o: JSONObject) {
        when (o.optString("t")) {
            "s" -> edit.putString(key, o.getString("v"))
            "b" -> edit.putBoolean(key, o.getBoolean("v"))
            "i" -> edit.putInt(key, o.getInt("v"))
            "l" -> edit.putLong(key, o.getLong("v"))
            "f" -> edit.putFloat(key, o.getDouble("v").toFloat())
            "ss" -> o.getJSONArray("v").let { a -> edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
        }
    }
}
