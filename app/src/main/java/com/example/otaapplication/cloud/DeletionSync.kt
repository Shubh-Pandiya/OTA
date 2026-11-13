package com.example.otaapplication.cloud

import com.example.otaapplication.data.Deletion
import com.example.otaapplication.data.SyncTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [removed]: local rows removed by deletes made elsewhere, per table. [pushed]: deletes sent from here. */
data class DeletionSyncResult(val removed: Map<String, Int>, val pushed: Int)

/**
 * Hard deletes (docs/desktop-sync-contract.md, "Deleting"): reads the cloud's tombstones
 * (ota_deletions) and removes those rows here, then sends the deletes made on this phone
 * (ota_delete_rows). A delete the cloud refuses because the row was edited later elsewhere is
 * dropped, and that table is pulled again from the start so the newer row comes back.
 * Runs before the row sync, so a pulled row can never bring back something just deleted.
 */
class DeletionSync(
    private val api: OrderCloudApi,
    private val cursors: CursorStore,
    private val pending: (limit: Int) -> List<Deletion>,
    private val markSent: (List<Deletion>) -> Unit,
    private val apply: (List<Deletion>) -> Map<String, Int>,
    private val resetTable: (table: String) -> Unit,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200
) {
    suspend fun syncAll(): DeletionSyncResult = try {
        val removed = pull()
        DeletionSyncResult(removed, push())
    } catch (e: CloudException) {
        // The cloud doesn't have hard deletes yet: deletes stay queued until it does.
        if (!e.missingInCloud) throw e
        DeletionSyncResult(emptyMap(), 0)
    }

    private suspend fun pull(): Map<String, Int> {
        val saved = cursors.get(CURSOR)
        var after = saved?.let { SyncTime.minusSeconds(it, OrderSync.OVERLAP_SECONDS) }
        var afterId: String? = null
        var newest = saved
        val removed = HashMap<String, Int>()
        var pages = 0
        while (pages++ < OrderSync.MAX_PAGES) {
            val page = api.pullDeletions(after, afterId, pageSize)
            withContext(Dispatchers.IO) { apply(page) }.forEach { (table, n) -> if (n > 0) removed.merge(table, n, Int::plus) }
            newest = page.fold(newest) { acc, row -> SyncTime.later(acc, row.serverUpdatedAt) }
            if (page.size < pageSize) break
            after = page.last().serverUpdatedAt
            afterId = page.last().id
        }
        if (newest != saved) cursors.set(CURSOR, newest)
        return removed
    }

    private suspend fun push(): Int {
        var sent = 0
        var previous: Set<Pair<String, Long>>? = null
        var batches = 0
        while (batches++ < OrderSync.MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { pending(pushBatch) }
            val keys = batch.mapTo(HashSet()) { it.id to it.at }
            if (batch.isEmpty() || keys == previous) break
            previous = keys
            val kept = api.deleteRows(batch)
            withContext(Dispatchers.IO) {
                markSent(batch)
                batch.filter { it.id in kept }.mapTo(HashSet()) { it.table }.forEach(resetTable)
            }
            sent += batch.size - batch.count { it.id in kept }
        }
        return sent
    }

    companion object {
        const val CURSOR = "ota_deletions"
    }
}
