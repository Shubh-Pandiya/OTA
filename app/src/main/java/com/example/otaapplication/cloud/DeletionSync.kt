package com.example.otaapplication.cloud

import com.example.otaapplication.data.Deletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [removed]: local rows removed by deletes made elsewhere, per table. [pushed]: deletes sent from here.
 * [pulled]: tombstones read.
 */
data class DeletionSyncResult(val removed: Map<String, Int>, val pushed: Int, val pulled: Int = 0)

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
    private val pageSize: Int = DeltaPull.PAGE_SIZE,
    private val pushBatch: Int = 200
) {
    suspend fun syncAll(): DeletionSyncResult = try {
        var pulled = 0
        val removed = pull { pulled += it }
        DeletionSyncResult(removed, push(), pulled)
    } catch (e: CloudException) {
        // The cloud doesn't have hard deletes yet: deletes stay queued until it does.
        if (!e.missingInCloud) throw e
        DeletionSyncResult(emptyMap(), 0)
    }

    private suspend fun pull(counted: (Int) -> Unit): Map<String, Int> {
        val removed = HashMap<String, Int>()
        DeltaPull.run(
            saved = cursors.get(CURSOR),
            serverStart = api.serverTime(),
            pageSize = pageSize,
            fetch = { after, afterId -> api.pullDeletions(after, afterId, pageSize) },
            updatedAt = { it.serverUpdatedAt },
            id = { it.id },
            apply = { page ->
                counted(page.size)
                withContext(Dispatchers.IO) { apply(page) }.forEach { (table, n) -> if (n > 0) removed.merge(table, n, Int::plus) }
                0
            },
            save = { cursors.set(CURSOR, it) }
        )
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
