package com.example.otaapplication.cloud

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.SyncTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where each table's pull cursor (last server `updated_at` seen) is kept. */
interface SyncCursors {
    fun get(type: PartyType): String?
    fun set(type: PartyType, value: String?)
}

class PrefsSyncCursors(private val prefs: SharedPreferences) : SyncCursors {
    override fun get(type: PartyType): String? = prefs.getString("cursor_${type.table}", null)
    override fun set(type: PartyType, value: String?) = prefs.edit { putString("cursor_${type.table}", value) }
}

data class SyncResult(val pulledChanges: Int, val pushed: Int)

/**
 * One catalog sync round per table: pull what changed on the server since the cursor, merge it
 * (last write wins), then push local rows still marked pending.
 */
class CatalogSync(
    private val repository: CatalogRepository,
    private val api: CatalogCloudApi,
    private val cursors: SyncCursors,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200
) {
    suspend fun syncAll(): SyncResult {
        var pulled = 0
        var pushed = 0
        for (type in PartyType.entries) {
            pulled += pull(type)
            val (sent, changed) = push(type)
            pushed += sent
            pulled += changed
        }
        return SyncResult(pulled, pushed)
    }

    private suspend fun pull(type: PartyType): Int {
        val saved = cursors.get(type)
        var after = saved?.let { SyncTime.minusSeconds(it, OVERLAP_SECONDS) }
        var afterId: String? = null
        var newest = saved
        var changed = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = api.pull(type, after, afterId, pageSize)
            changed += withContext(Dispatchers.IO) { repository.mergePulled(type, page) }
            newest = page.fold(newest) { acc, row -> SyncTime.later(acc, row.serverUpdatedAt) }
            if (page.size < pageSize) break
            val last = page.last()
            after = last.serverUpdatedAt
            afterId = last.party.id
        }
        // Saved only after the merge committed, so a failed sync re-reads the same rows next time.
        if (newest != saved) cursors.set(type, newest)
        return changed
    }

    /** Returns rows sent and local rows whose content changed because the server kept a newer copy. */
    private suspend fun push(type: PartyType): Pair<Int, Int> {
        var sent = 0
        var changed = 0
        var previousIds: Set<String>? = null
        var batches = 0
        while (batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { repository.pendingRows(type, pushBatch) }
            val ids = batch.mapTo(HashSet()) { it.party.id }
            // Stop when nothing is pending, or when the same rows are still pending (e.g. edited mid-push).
            if (batch.isEmpty() || ids == previousIds) break
            previousIds = ids
            val returned = api.push(type, batch)
            changed += withContext(Dispatchers.IO) { repository.applyPushResult(type, batch, returned) }
            sent += batch.size
        }
        return sent to changed
    }

    private companion object {
        const val OVERLAP_SECONDS = 60L
        const val MAX_PAGES = 100
    }
}
