package com.example.otaapplication.cloud

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.RemoteParty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where each table's pull cursor (a server `updated_at`, see [DeltaPull]) is kept. */
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
    private val pageSize: Int = DeltaPull.PAGE_SIZE,
    private val pushBatch: Int = 200,
    /** The cloud's clock (see [DeltaPull]); null when unknown. */
    private val serverTime: () -> String? = { null }
) {
    /** Starts reading every table's changes ahead (see [PullAhead]). */
    fun readAhead(ahead: PullAhead) {
        for (type in PartyType.entries) {
            ahead.start(key(type), cursors.get(type), pageSize, { after, afterId -> api.pull(type, after, afterId, pageSize) }, { it.serverUpdatedAt }, { it.party.id })
        }
    }

    suspend fun syncAll(ahead: PullAhead? = null): SyncResult {
        var pulled = 0
        var pushed = 0
        for (type in PartyType.entries) {
            pulled += pull(type, ahead)
            val (sent, changed) = push(type)
            pushed += sent
            pulled += changed
        }
        return SyncResult(pulled, pushed)
    }

    private suspend fun pull(type: PartyType, ahead: PullAhead?): Int = DeltaPull.run(
        saved = cursors.get(type),
        serverStart = ahead?.serverStart ?: serverTime(),
        pageSize = pageSize,
        fetch = { after, afterId ->
            val read: suspend (String?, String?) -> List<RemoteParty> = { a, i -> api.pull(type, a, i, pageSize) }
            ahead?.fetch(key(type), after, afterId, read) ?: read(after, afterId)
        },
        updatedAt = { it.serverUpdatedAt },
        id = { it.party.id },
        apply = { page -> withContext(Dispatchers.IO) { repository.mergePulled(type, page) } },
        save = { cursors.set(type, it) }
    )

    private fun key(type: PartyType) = "catalog:${type.table}"

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
        const val MAX_PAGES = 100
    }
}
