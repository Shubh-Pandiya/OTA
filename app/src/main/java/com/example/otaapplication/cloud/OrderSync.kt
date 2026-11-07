package com.example.otaapplication.cloud

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import com.example.otaapplication.data.SyncTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pull cursors (last server `updated_at` seen) by table name. */
interface CursorStore {
    fun get(table: String): String?
    fun set(table: String, value: String?)
}

class PrefsCursorStore(private val prefs: SharedPreferences) : CursorStore {
    override fun get(table: String): String? = prefs.getString("cursor_$table", null)
    override fun set(table: String, value: String?) = prefs.edit { putString("cursor_$table", value) }
}

/**
 * [pulledChanges]: orders, bills or links changed on this phone by the pull.
 * [notices]: things the user should know, e.g. an order that had to get a new number.
 */
data class OrderSyncResult(val pulledChanges: Int, val pushed: Int, val notices: List<String>)

/**
 * One sync round for orders, bills and bill links: pull what changed in the cloud (last write
 * wins against unsynced local edits), then push what changed here. Only changed rows travel.
 *
 * [renumber] gives a free number for an order whose number the cloud already has (the same
 * staff member made orders offline on two phones); null when the number can't be replaced.
 */
class OrderSync(
    private val orders: LocalOrders,
    private val api: OrderCloudApi,
    private val cursors: CursorStore,
    private val renumber: (String) -> String?,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200
) {
    suspend fun syncAll(): OrderSyncResult {
        val repo = orders.index
        var changed = 0
        // Parents first, so links can find their order and bill.
        changed += pull(OrderTable.ORDERS) { withContext(Dispatchers.IO) { orders.applyPulled(it) } }
        changed += pull(OrderTable.BILLS) { withContext(Dispatchers.IO) { repo.applyRemoteBills(it) } }
        changed += pull(OrderTable.LINKS) { withContext(Dispatchers.IO) { repo.applyRemoteLinks(it) } }

        val notices = ArrayList<String>()
        var pushed = pushOrders(notices)
        pushed += push(OrderTable.BILLS) { repo.pendingBills(pushBatch) }
        pushed += push(OrderTable.LINKS) { repo.pendingLinks(pushBatch) }
        return OrderSyncResult(changed, pushed, notices)
    }

    private suspend fun <T> pull(table: OrderTable<T>, apply: suspend (List<RemoteRow<T>>) -> Int): Int {
        val saved = cursors.get(table.name)
        var after = saved?.let { SyncTime.minusSeconds(it, OVERLAP_SECONDS) }
        var afterId: String? = null
        var newest = saved
        var changed = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = api.pull(table, after, afterId, pageSize)
            changed += apply(page)
            newest = page.fold(newest) { acc, row -> SyncTime.later(acc, row.serverUpdatedAt) }
            if (page.size < pageSize) break
            after = page.last().serverUpdatedAt
            afterId = page.last().id
        }
        // Saved only after the rows were applied, so a failed round reads them again next time.
        if (newest != saved) cursors.set(table.name, newest)
        return changed
    }

    private suspend fun <T> push(table: OrderTable<T>, pending: () -> List<PendingRow<T>>): Int {
        var sent = 0
        var previous: Set<Pair<String, Long>>? = null
        var batches = 0
        while (batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { pending() }
            val keys = batch.mapTo(HashSet()) { it.id to it.updatedAt }
            // Stop when nothing is pending, or the same rows are still pending (nothing would change).
            if (batch.isEmpty() || keys == previous) break
            previous = keys
            api.push(table, batch)
            withContext(Dispatchers.IO) { orders.index.markSynced(table.name, batch.map { it.id to it.updatedAt }) }
            sent += batch.size
        }
        return sent
    }

    private suspend fun pushOrders(notices: MutableList<String>): Int {
        var sent = 0
        var batches = 0
        var progress = true
        // Stops once a batch makes no progress (nothing uploaded or renumbered), so a row the
        // cloud keeps refusing can never loop forever.
        while (progress && batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { pendingOrders() }
            if (batch.isEmpty()) break
            progress = false
            try {
                api.push(OrderTable.ORDERS, batch)
                markOrdersSynced(batch)
                sent += batch.size
                progress = true
            } catch (e: CloudException) {
                if (!e.duplicateKey) throw e
                // Some order number is already in the cloud: send one by one to find it.
                for (row in batch) {
                    try {
                        api.push(OrderTable.ORDERS, listOf(row))
                        markOrdersSynced(listOf(row))
                        sent++
                        progress = true
                    } catch (e: CloudException) {
                        if (!e.duplicateKey) throw e
                        val (notice, renumbered) = resolveDuplicate(row)
                        notice?.let(notices::add)
                        if (renumbered) progress = true
                    }
                }
            }
        }
        return sent
    }

    private fun pendingOrders(): List<PendingRow<OrderFormData>> {
        val refs = orders.index.pendingOrders(pushBatch)
        val content = orders.contentOf(refs.filterNot { it.deleted }.map { it.id })
        return refs.mapNotNull { ref ->
            val data = if (ref.deleted) OrderFormData(date = ref.date, offerNo = ref.offerNo, items = emptyList()) else content[ref.id]
            data?.let { PendingRow(ref.id, it, ref.deleted, ref.updatedAt) }
        }
    }

    private suspend fun markOrdersSynced(rows: List<PendingRow<OrderFormData>>) =
        withContext(Dispatchers.IO) { orders.index.markSynced(OrderTable.ORDERS.name, rows.map { it.id to it.updatedAt }) }

    /** Returns the notice for the user and whether the order got a new number to retry with. */
    private suspend fun resolveDuplicate(row: PendingRow<OrderFormData>): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        if (row.deleted) {
            // Deleted before it ever reached the cloud; there is nothing to keep.
            orders.index.markSynced(OrderTable.ORDERS.name, listOf(row.id to row.updatedAt))
            return@withContext null to true
        }
        val old = row.value.offerNo
        val replacement = if (OrderNumber.prefixOf(old) != null) renumber(old) else null
        if (replacement != null && orders.renumber(row.id, replacement)) {
            // The order stays pending and goes up with its new number in the next batch.
            "Order $old was also used on another phone; this copy is now order $replacement." to true
        } else {
            "Order $old could not be uploaded: the cloud already has another order with that number." to false
        }
    }

    private companion object {
        const val OVERLAP_SECONDS = 60L
        const val MAX_PAGES = 100
    }
}
