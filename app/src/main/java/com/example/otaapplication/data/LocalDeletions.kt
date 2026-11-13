package com.example.otaapplication.data

import android.database.sqlite.SQLiteDatabase

/**
 * Hard deletes on this phone (docs/desktop-sync-contract.md, "Deleting"). A delete removes the row
 * and the rows that only make sense with it ([DeleteCascade]) at once, and queues a small note in
 * `pending_deletions` until the cloud has it (ota_delete_rows). Deletes made on other devices
 * arrive as tombstones and remove the local row unless it was edited here after the delete.
 * Nothing deleted is ever kept as a row.
 */
object LocalDeletions {
    const val TABLE = "pending_deletions"

    const val CREATE = "CREATE TABLE IF NOT EXISTS $TABLE (id TEXT PRIMARY KEY NOT NULL, tbl TEXT NOT NULL, deleted_at INTEGER NOT NULL)"

    /**
     * Deletes [id] from [table] with its children. With [queue] the cloud is told on the next sync
     * (not for rows that never reached it, e.g. an order still waiting for its number).
     * Returns false if there was no such row.
     */
    fun delete(db: SQLiteDatabase, table: String, id: String, now: Long, queue: Boolean = true): Boolean {
        val edited = editedAt(db, table, id) ?: return false
        removeLocal(db, table, id)
        if (queue) enqueue(db, table, id, maxOf(now, edited))
        return true
    }

    /** Queues a delete for a row this phone no longer has (e.g. removed from the order history file). */
    fun enqueue(db: SQLiteDatabase, table: String, id: String, at: Long) {
        require(table in DeleteCascade.TABLES) { "Unknown table $table" }
        db.execSQL(
            "INSERT INTO $TABLE (id, tbl, deleted_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET tbl = excluded.tbl, deleted_at = max($TABLE.deleted_at, excluded.deleted_at)",
            arrayOf(id, table, at)
        )
    }

    fun removeLocal(db: SQLiteDatabase, table: String, id: String) {
        require(table in DeleteCascade.TABLES) { "Unknown table $table" }
        for ((child, column) in DeleteCascade.children(table)) db.delete(child, "$column = ?", arrayOf(id))
        db.delete(table, "id = ?", arrayOf(id))
    }

    /**
     * A delete made on another device (or an old app's deleted = true row): removes the local row
     * unless it was edited here after [at] (that edit wins and is uploaded). Returns true if removed.
     */
    fun applyRemote(db: SQLiteDatabase, table: String, id: String, at: Long): Boolean {
        if (table !in DeleteCascade.TABLES) return false
        val edited = editedAt(db, table, id) ?: return false
        if (edited > at) return false
        removeLocal(db, table, id)
        return true
    }

    /** True when this phone deleted [id] at or after [editedAt]: a pulled copy must not bring it back. */
    fun blocks(db: SQLiteDatabase, id: String, editedAt: Long): Boolean =
        db.rawQuery("SELECT 1 FROM $TABLE WHERE id = ? AND deleted_at >= ?", arrayOf(id, editedAt.toString())).use { it.moveToFirst() }

    fun pending(db: SQLiteDatabase, limit: Int): List<Deletion> =
        db.rawQuery("SELECT id, tbl, deleted_at FROM $TABLE ORDER BY deleted_at, id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Deletion(c.getString(1), c.getString(0), c.getLong(2))) }
        }

    fun pendingCount(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** The cloud has these deletes; ones made again meanwhile stay queued. */
    fun markSent(db: SQLiteDatabase, rows: List<Deletion>) {
        db.beginTransaction()
        try {
            rows.forEach { db.delete(TABLE, "id = ? AND deleted_at = ?", arrayOf(it.id, it.at.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun editedAt(db: SQLiteDatabase, table: String, id: String): Long? {
        require(table in DeleteCascade.TABLES) { "Unknown table $table" }
        return db.rawQuery("SELECT updated_at FROM $table WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }
}
