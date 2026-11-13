package com.example.otaapplication.cloud

import com.example.otaapplication.data.Deletion
import java.time.Instant

/**
 * The cloud's ota_deletions log for the fakes, following the SQL rules: a delete loses to a newer
 * edit (kept), an upload not newer than a delete is dropped by the guard trigger, and a newer
 * upload (a re-create) clears the tombstone.
 */
internal class FakeTombstones(private val serverTime: () -> String) {
    val rows = linkedMapOf<String, Deletion>()

    /** ota_delete_rows for one row: [editedAt] is the cloud row's client_updated_at (null if absent). */
    fun delete(row: Deletion, editedAt: Long?, remove: () -> Unit): Boolean {
        if (editedAt != null && editedAt > row.at) return false
        remove()
        val previous = rows[row.id]
        rows[row.id] = Deletion(row.table, row.id, maxOf(row.at, previous?.at ?: 0), serverTime())
        return true
    }

    fun admits(id: String, clientUpdatedAt: Long): Boolean {
        val tombstone = rows[id] ?: return true
        if (clientUpdatedAt <= tombstone.at) return false
        rows.remove(id)
        return true
    }

    fun pull(after: String?, afterId: String?, limit: Int): List<Deletion> {
        val afterInstant = after?.let { Instant.parse(it.replace("+00:00", "Z")) }
        return rows.values
            .sortedWith(compareBy({ Instant.parse(it.serverUpdatedAt) }, { it.id }))
            .filter { row ->
                val t = Instant.parse(row.serverUpdatedAt)
                afterInstant == null || t.isAfter(afterInstant) || (afterId != null && t == afterInstant && row.id > afterId)
            }
            .take(limit)
    }
}

/** A cursor store in memory. */
internal class MemoryCursorStore : CursorStore {
    val values = mutableMapOf<String, String?>()
    override fun get(table: String) = values[table]
    override fun set(table: String, value: String?) { values[table] = value }
}
