package com.ota.desktop.data

import com.example.otaapplication.data.DeleteCascade
import com.example.otaapplication.data.Deletion
import com.ota.desktop.db.Db
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.update
import java.sql.Connection

/**
 * Hard deletes (docs/desktop-sync-contract.md, "Deleting"). A delete removes the row and the rows
 * that only make sense with it ([DeleteCascade]) at once, and queues a small note in
 * pending_deletions until the cloud has it (ota_delete_rows). Deletes made elsewhere arrive as
 * tombstones and remove the local row unless it was edited here after the delete.
 */
object Deletions {
    /**
     * Deletes [id] from [table] with its children. With [queue] the cloud is told on the next sync
     * (not for rows that never reached it, e.g. an order still waiting for its number).
     */
    fun delete(c: Connection, table: String, id: String, queue: Boolean = true): Boolean {
        require(table in DeleteCascade.TABLES) { "Unknown table $table" }
        val edited = c.one("select client_updated_at from $table where id = ?::uuid", id) { it.getLong(1) }
        removeLocal(c, table, id)
        if (queue) {
            // Never older than the row's last edit, so the cloud lets this delete win over it.
            val at = maxOf(System.currentTimeMillis(), edited ?: 0L)
            c.update(
                "insert into pending_deletions (id, tbl, deleted_at) values (?::uuid, ?, ?) " +
                    "on conflict (id) do update set tbl = excluded.tbl, deleted_at = greatest(pending_deletions.deleted_at, excluded.deleted_at)",
                id, table, at
            )
        }
        return edited != null
    }

    private fun removeLocal(c: Connection, table: String, id: String) {
        for ((child, column) in DeleteCascade.children(table)) c.update("delete from $child where $column = ?::uuid", id)
        c.update("delete from $table where id = ?::uuid", id)
    }

    /**
     * A delete made on another device (or an old app's deleted = true row): removes the local row
     * unless it was edited here after [at] (that edit wins and is uploaded). Returns true if removed.
     */
    fun applyRemote(c: Connection, table: String, id: String, at: Long): Boolean {
        if (table !in DeleteCascade.TABLES) return false
        val edited = c.one("select client_updated_at from $table where id = ?::uuid", id) { it.getLong(1) } ?: return false
        if (edited > at) return false
        removeLocal(c, table, id)
        return true
    }

    fun applyPulled(db: Db, rows: List<Deletion>): Int = db.tx { c -> rows.count { applyRemote(c, it.table, it.id, it.at) } }

    /** True when this device deleted [id] at or after [editedAt]: a pulled copy must not bring it back. */
    fun blocks(c: Connection, id: String, editedAt: Long): Boolean =
        c.one("select 1 from pending_deletions where id = ?::uuid and deleted_at >= ?", id, editedAt) { true } ?: false

    fun pending(db: Db, limit: Int): List<Deletion> = db.read { c ->
        c.query("select id, tbl, deleted_at from pending_deletions order by deleted_at, id limit $limit") {
            Deletion(it.getString("tbl"), it.getObject("id").toString(), it.getLong("deleted_at"))
        }
    }

    fun pendingCount(c: Connection): Int = c.one("select count(*) from pending_deletions") { it.getInt(1) } ?: 0

    /** The cloud has these deletes; ones made again meanwhile stay queued. */
    fun markSent(db: Db, rows: List<Deletion>) = db.tx { c ->
        rows.forEach { c.update("delete from pending_deletions where id = ?::uuid and deleted_at = ?", it.id, it.at) }
    }
}
