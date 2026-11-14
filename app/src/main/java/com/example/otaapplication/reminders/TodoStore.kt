package com.example.otaapplication.reminders

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import com.example.otaapplication.security.EncryptedDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Small local table of Home to-dos. Kept apart from the order database: nothing here syncs. */
class TodoStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, EncryptedDb.keyFor(context, DB_NAME), null, 1, 0, null, null, false) {

    private val _changes = MutableStateFlow(0)
    /** Bumped on every write, including ones made from a notification while the app is open. */
    val changes: StateFlow<Int> = _changes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE todos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, day INTEGER NOT NULL, minute INTEGER, " +
                "done INTEGER NOT NULL DEFAULT 0, done_at INTEGER, notified INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun all(): List<Todo> = readableDatabase.rawQuery("SELECT * FROM todos", null).use { c ->
        buildList { while (c.moveToNext()) add(c.toTodo()) }
    }

    fun get(id: Long): Todo? = readableDatabase.rawQuery("SELECT * FROM todos WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) c.toTodo() else null
    }

    /** Inserts a new to-do (id 0) or replaces an existing one; returns it with its id. */
    fun save(todo: Todo): Todo {
        val values = ContentValues().apply {
            put("title", todo.title)
            put("day", todo.day)
            put("minute", todo.minute)
            put("done", if (todo.done) 1 else 0)
            put("done_at", todo.doneAt)
            put("notified", if (todo.notified) 1 else 0)
            put("created_at", todo.createdAt)
        }
        val saved = if (todo.id == 0L) {
            todo.copy(id = writableDatabase.insertOrThrow("todos", null, values))
        } else {
            writableDatabase.update("todos", values, "id = ?", arrayOf(todo.id.toString()))
            todo
        }
        changed()
        return saved
    }

    fun markNotified(id: Long) {
        writableDatabase.execSQL("UPDATE todos SET notified = 1 WHERE id = ?", arrayOf(id))
        changed()
    }

    fun delete(id: Long) {
        writableDatabase.delete("todos", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Clears to-dos ticked before [before] (epoch millis). */
    fun pruneDone(before: Long) {
        if (writableDatabase.delete("todos", "done = 1 AND done_at < ?", arrayOf(before.toString())) > 0) changed()
    }

    private fun changed() = _changes.update { it + 1 }

    private fun Cursor.toTodo() = Todo(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        day = getLong(getColumnIndexOrThrow("day")),
        minute = getColumnIndexOrThrow("minute").let { if (isNull(it)) null else getInt(it) },
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        doneAt = getColumnIndexOrThrow("done_at").let { if (isNull(it)) null else getLong(it) },
        notified = getInt(getColumnIndexOrThrow("notified")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    companion object {
        const val DB_NAME = "todos.db"
        @Volatile private var instance: TodoStore? = null
        fun get(context: Context): TodoStore =
            instance ?: synchronized(this) { instance ?: TodoStore(context.applicationContext).also { instance = it } }
    }
}
