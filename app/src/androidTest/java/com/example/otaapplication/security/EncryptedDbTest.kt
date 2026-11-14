package com.example.otaapplication.security

import android.database.sqlite.SQLiteDatabase as PlainDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDbTest {
    @Test
    fun plainDatabaseFromAnOlderVersionIsEncryptedWithItsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "encrypted_db_test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        PlainDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execSQL("INSERT INTO t (v) VALUES ('one'), ('two')")
            db.version = 8
        }

        val key = EncryptedDb.keyFor(context, name)

        assertFalse(EncryptedDb.isPlain(file))
        assertFalse(java.io.File(file.path + ".encrypting").exists())
        SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT group_concat(v) FROM t", emptyArray<String>()).use { c ->
                c.moveToFirst()
                assertEquals("one,two", c.getString(0))
            }
        }
        context.deleteDatabase(name)
    }
}
