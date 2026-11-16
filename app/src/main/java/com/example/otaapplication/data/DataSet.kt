package com.example.otaapplication.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import java.io.File

/**
 * Which local data set this process works on. Real data and the test account's data are kept in
 * separate places on the phone (database, order history, advice images and sync cursors), like the
 * desktop's ota_test schema; the cloud keeps them in separate tables too. The set is chosen once per
 * process from the saved data mode; switching login between the two restarts the app ([switchTo]).
 */
object DataSet {
    private const val PREFS = "ota_cloud"
    private const val TEST_PREFS = "ota_cloud_test"
    const val KEY_DATA_MODE = "data_mode"
    /** Set once 2.0.2+ has split the test set off; earlier versions kept test data in the main set. */
    private const val KEY_SPLIT = "test_set_split"
    private const val TEST_DB = "ota_local_test.db"
    private const val TEST_DIR = "test"

    @Volatile private var test: Boolean? = null

    fun isTest(context: Context): Boolean = test ?: synchronized(this) {
        test ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // No mode saved yet: the set follows whoever is signed in (SessionStore's email).
            val mode = prefs.getString(KEY_DATA_MODE, null) ?: OtaAccounts.dataMode(prefs.getString("auth_email", null))
            val isTest = mode == OtaAccounts.MODE_TEST
            if (!prefs.getBoolean(KEY_SPLIT, false)) {
                if (isTest) dropLegacyTestCopy(app, prefs)
                prefs.edit().putBoolean(KEY_SPLIT, true).commit()
            }
            SyncIds.testMode = isTest
            isTest.also { test = it }
        }
    }

    fun databaseName(context: Context): String = if (isTest(context)) TEST_DB else OtaDatabase.DB_NAME

    /** Folder for the order history and advice images of this set. */
    fun filesDir(context: Context): File {
        val base = context.applicationContext.filesDir
        return if (isTest(context)) File(base, TEST_DIR).apply { mkdirs() } else base
    }

    /** Sync cursors of this set (the sign-in itself is shared). */
    fun cursorPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(if (isTest(context)) TEST_PREFS else PREFS, Context.MODE_PRIVATE)

    /**
     * Saves [mode] as the data set to use and, if it differs from the one this process has open,
     * restarts the app on it. Returns only when no restart is needed.
     */
    fun switchTo(context: Context, mode: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DATA_MODE, mode).commit()
        if ((mode == OtaAccounts.MODE_TEST) == isTest(app)) return
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
            app.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Runtime.getRuntime().exit(0)
    }

    /**
     * Before 2.0.2 a phone signed in as the test account held test data in the main set, numbered
     * like real data. The cloud has moved and renumbered that data (T-numbers), so the old copy
     * is dropped and the test set is pulled fresh. Only test phones get here; real data never
     * shared a phone with test data.
     */
    private fun dropLegacyTestCopy(app: Context, prefs: SharedPreferences) {
        val history = File(app.filesDir, OrderHistoryStore.FILE_NAME)
        runCatching {
            val entries = JSONArray(history.readText())
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                LocalOrders.deleteFiles(entry.optString("filePath"), entry.optString("pdfFileName").ifBlank { null })
            }
        }
        history.delete()
        File(app.filesDir, OrderHistoryStore.FILE_NAME + ".tmp").delete()
        File(app.filesDir, "advice_images").deleteRecursively()
        app.deleteDatabase(OtaDatabase.DB_NAME)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("cursor_") }.forEach(::remove)
            remove("last_sync_at")
        }.commit()
    }
}
