package com.ota.desktop.sync

import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.cloud.CatalogCloudApi
import com.example.otaapplication.cloud.CloudException
import com.example.otaapplication.cloud.CloudUsage
import com.example.otaapplication.cloud.OrderCloudApi
import com.example.otaapplication.cloud.PostgrestCatalogApi
import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.PostgrestOrderApi
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.SyncTime
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.LocalSyncTable
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.Kv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The signed-in staff member as stored in the cloud's staff table. */
data class StaffProfile(val code: Int, val name: String, val admin: Boolean)

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    val storageFull: Boolean = false,
    val usage: CloudUsage? = null,
    val notices: List<String> = emptyList(),
    val pending: Int = 0,
    val lastMaintenanceAt: Long? = null,
    val lastPurge: String? = null
)

/**
 * Desktop sync, following docs/desktop-sync-contract.md:
 * - a round pulls every table (delta, paged, by the server updated_at cursor) and pushes local edits;
 * - maintenance (admins only) also marks the exact cloud versions stored here as archived and then
 *   asks the cloud to purge archived rows older than the retention window. A row is never archived
 *   unless its current cloud version is committed in the local database.
 */
class SyncEngine(
    private val db: Db,
    private val kv: Kv,
    private val auth: SupabaseAuth,
    private val client: PostgrestClient,
    private val catalog: CatalogStore,
    private val orders: OrderStore,
    private val payments: PaymentStore,
    private val numbers: Numbers,
    private val orderApi: OrderCloudApi = PostgrestOrderApi(client),
    private val catalogApi: CatalogCloudApi = PostgrestCatalogApi(client),
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(
        SyncStatus(
            lastSyncAt = kv.long(KEY_LAST_SYNC), lastMaintenanceAt = kv.long(KEY_LAST_MAINTENANCE), lastPurge = kv[KEY_LAST_PURGE],
            usage = CloudUsage.parse(kv[KEY_USAGE])
        )
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun cachedStaff(): StaffProfile? {
        val code = kv.int(KEY_STAFF_CODE) ?: return null
        return StaffProfile(code, kv[KEY_STAFF_NAME].orEmpty(), kv.bool(KEY_STAFF_ADMIN))
    }

    /** Reads the staff row for the signed-in user and caches it, so numbering works offline. */
    suspend fun refreshStaff(): StaffProfile? {
        val userId = auth.session.value?.userId ?: return cachedStaff()
        val body = client.call("GET", "staff?select=code,name,is_admin&user_id=eq.$userId")
        val array = JSONArray(body)
        val profile = if (array.length() == 0) null else array.getJSONObject(0).let {
            StaffProfile(it.getInt("code"), if (it.isNull("name")) "" else it.optString("name"), it.optBoolean("is_admin"))
        }
        withContext(Dispatchers.IO) {
            if (profile == null) clearStaff()
            else {
                kv[KEY_STAFF_CODE] = profile.code.toString()
                kv[KEY_STAFF_NAME] = profile.name
                kv[KEY_STAFF_ADMIN] = profile.admin.toString()
            }
        }
        return profile
    }

    fun clearStaff() = kv.remove(KEY_STAFF_CODE, KEY_STAFF_NAME, KEY_STAFF_ADMIN)

    fun refreshPending() {
        _status.update { it.copy(pending = pendingCount()) }
    }

    private fun pendingCount() = catalog.pendingCount() + orders.pendingCount()

    /**
     * One sync round; with [maintenance] (admins only) also archives and purges. Single flight:
     * a call while another round runs waits for it and then runs its own.
     */
    suspend fun sync(maintenance: Boolean = false): SyncStatus = mutex.withLock {
        if (auth.session.value == null) return@withLock _status.value
        _status.update { it.copy(running = true, error = null) }
        val notices = ArrayList<String>()
        var full = false
        var error: String? = null
        try {
            val staff = try {
                refreshStaff()
            } catch (e: CloudException) {
                if (e.authFailed) throw e
                cachedStaff()
            }
            syncCatalog()
            syncOrders(notices)
            if (maintenance && staff?.admin == true) maintain()
        } catch (e: CloudException) {
            full = e.storageFull
            error = when {
                e.storageFull -> STORAGE_FULL
                e.authFailed -> "Signed out: ${e.message}"
                else -> e.message
            }
        } catch (e: java.io.IOException) {
            error = "Offline: changes are kept on this laptop and upload automatically later."
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val usage = if (auth.session.value != null) runCatching { orderApi.usage() }.getOrNull() else null
        val now = clock()
        withContext(Dispatchers.IO) {
            usage?.let { kv[KEY_USAGE] = it.toJson() }
            if (error == null) kv[KEY_LAST_SYNC] = now.toString()
        }
        _status.update {
            it.copy(
                running = false, error = error, storageFull = full || (usage != null && usage.percent >= 100.0),
                usage = usage ?: it.usage, lastSyncAt = if (error == null) now else it.lastSyncAt,
                notices = (notices + it.notices).take(20), pending = pendingCount(),
                lastMaintenanceAt = kv.long(KEY_LAST_MAINTENANCE), lastPurge = kv[KEY_LAST_PURGE]
            )
        }
        _status.value
    }

    /** True when maintenance has not run for [everyMillis]. */
    fun maintenanceDue(everyMillis: Long): Boolean = clock() - (kv.long(KEY_LAST_MAINTENANCE) ?: 0) >= everyMillis

    // ------------------------------------------------------------------ catalog

    private suspend fun syncCatalog() {
        for (type in PartyType.entries) {
            pullPages("cursor.${type.table}", { after, afterId -> catalogApi.pull(type, after, afterId, pageSize) }, { it.serverUpdatedAt }, { it.party.id }) { page ->
                catalog.mergePulled(type, page)
            }
            var previous: Set<String>? = null
            var batches = 0
            while (batches++ < MAX_PAGES) {
                val batch = withContext(Dispatchers.IO) { catalog.pendingRows(type, pushBatch) }
                val ids = batch.mapTo(HashSet()) { it.party.id }
                if (batch.isEmpty() || ids == previous) break
                previous = ids
                val returned = catalogApi.push(type, batch)
                withContext(Dispatchers.IO) { catalog.applyPushResult(type, batch, returned) }
            }
        }
    }

    // ------------------------------------------------------------------ orders, bills, advices

    private suspend fun syncOrders(notices: MutableList<String>) {
        pullAll()
        pushNumbered(LocalSyncTable.ORDERS, notices) { row -> resolveDuplicate(row, row.value.offerNo, Numbers.Kind.ORDER, LocalSyncTable.ORDERS) }
        push(LocalSyncTable.BILLS)
        push(LocalSyncTable.LINKS)
        pushNumbered(LocalSyncTable.ADVICES, notices) { row -> resolveDuplicate(row, row.value.adviceNo, Numbers.Kind.ADVICE, LocalSyncTable.ADVICES) }
        push(LocalSyncTable.ADVICE_LINES)
    }

    private suspend fun pullAll() {
        for (t in LocalSyncTable.ALL) pullTable(t)
    }

    private suspend fun <T> pullTable(t: LocalSyncTable<T>) =
        pullPages("cursor.${t.cloud.name}", { after, afterId -> orderApi.pull(t.cloud, after, afterId, pageSize) }, { it.serverUpdatedAt }, { it.id }) { page ->
            t.applyPulled(db, page)
        }

    private suspend fun <R> pullPages(
        cursorKey: String,
        fetch: suspend (String?, String?) -> List<R>,
        updatedAt: (R) -> String,
        id: (R) -> String,
        apply: (List<R>) -> Int
    ): Int {
        val saved = withContext(Dispatchers.IO) { kv[cursorKey] }
        var after = saved?.let { SyncTime.minusSeconds(it, OVERLAP_SECONDS) }
        var afterId: String? = null
        var newest = saved
        var changed = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = fetch(after, afterId)
            changed += withContext(Dispatchers.IO) { apply(page) }
            newest = page.fold(newest) { acc, row -> SyncTime.later(acc, updatedAt(row)) }
            if (page.size < pageSize) break
            after = updatedAt(page.last())
            afterId = id(page.last())
        }
        // Saved only after the rows were committed, so a failed round reads them again next time.
        if (newest != saved) withContext(Dispatchers.IO) { kv[cursorKey] = newest }
        return changed
    }

    private suspend fun <T> push(t: LocalSyncTable<T>): Int {
        var sent = 0
        var previous: Set<Pair<String, Long>>? = null
        var batches = 0
        while (batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { t.pending(db, pushBatch) }
            val keys = batch.mapTo(HashSet()) { it.id to it.updatedAt }
            if (batch.isEmpty() || keys == previous) break
            previous = keys
            orderApi.push(t.cloud, batch)
            withContext(Dispatchers.IO) { t.markPushed(db, batch) }
            sent += batch.size
        }
        return sent
    }

    /** Same as the phone: on a duplicate number, rows go one by one and the clashing one is renumbered. */
    private suspend fun <T> pushNumbered(t: LocalSyncTable<T>, notices: MutableList<String>, resolve: (PendingRow<T>) -> Pair<String?, Boolean>): Int {
        var sent = 0
        var batches = 0
        var progress = true
        while (progress && batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { t.pending(db, pushBatch) }
            if (batch.isEmpty()) break
            progress = false
            try {
                orderApi.push(t.cloud, batch)
                withContext(Dispatchers.IO) { t.markPushed(db, batch) }
                sent += batch.size
                progress = true
            } catch (e: CloudException) {
                if (!e.duplicateKey) throw e
                for (row in batch) {
                    try {
                        orderApi.push(t.cloud, listOf(row))
                        withContext(Dispatchers.IO) { t.markPushed(db, listOf(row)) }
                        sent++
                        progress = true
                    } catch (e: CloudException) {
                        if (!e.duplicateKey) throw e
                        val (notice, renumbered) = withContext(Dispatchers.IO) { resolve(row) }
                        notice?.let(notices::add)
                        if (renumbered) progress = true
                    }
                }
            }
        }
        return sent
    }

    private fun <T> resolveDuplicate(row: PendingRow<T>, old: String, kind: Numbers.Kind, t: LocalSyncTable<T>): Pair<String?, Boolean> {
        if (row.deleted) {
            // Deleted before it ever reached the cloud; there is nothing to keep there.
            t.markPushed(db, listOf(row))
            return null to true
        }
        val isOrder = kind == Numbers.Kind.ORDER
        val label = if (isOrder) "Order" else "Payment advice"
        val parsable = if (isOrder) OrderNumber.prefixOf(old) != null else AdviceNumber.prefixOf(old) != null
        val replacement = if (parsable) numbers.replacementFor(kind, old) else null
        val done = replacement != null && (if (isOrder) orders.renumber(row.id, replacement) else payments.renumber(row.id, replacement))
        return if (done) "$label $old was also used on another device; this copy is now $replacement." to true
        else "$label $old could not be uploaded: the cloud already has another one with that number." to false
    }

    // ------------------------------------------------------------------ maintenance

    /**
     * Archive then purge. Pulls again first so rows pushed in this round get their final cloud
     * version stored locally; only those exact versions are marked archived.
     */
    private suspend fun maintain() {
        pullAll()
        var marked = 0
        for (t in LocalSyncTable.ALL) {
            var rounds = 0
            while (rounds++ < MAX_PAGES) {
                val batch = withContext(Dispatchers.IO) { t.toArchive(db, ARCHIVE_BATCH) }
                if (batch.isEmpty()) break
                val rows = JSONArray().apply { batch.forEach { (id, at) -> put(JSONObject().put("id", id).put("updated_at", at)) } }
                val body = JSONObject().put("p_table", t.cloud.name).put("p_rows", rows).toString()
                marked += client.call("POST", "rpc/ota_mark_archived", body = body).trim().toIntOrNull() ?: 0
                withContext(Dispatchers.IO) { t.markArchived(db, batch) }
            }
        }
        val purged = JSONObject(client.call("POST", "rpc/ota_purge_archived_orders", body = "{}"))
        val summary = buildString {
            append("Archived ").append(marked).append(" row(s); removed from cloud: ")
            append(listOf("orders", "bills", "payment_advices").joinToString(", ") { "${purged.optInt(it)} ${it.replace('_', ' ')}" })
            if (purged.has("cutoff")) append(" (dated before ").append(purged.optString("cutoff")).append(")")
        }
        withContext(Dispatchers.IO) {
            kv[KEY_LAST_MAINTENANCE] = clock().toString()
            kv[KEY_LAST_PURGE] = summary
        }
    }

    companion object {
        private const val OVERLAP_SECONDS = 60L
        private const val MAX_PAGES = 100
        private const val ARCHIVE_BATCH = 500
        const val STORAGE_FULL = "Cloud storage is full. Changes are kept on this laptop and upload once space is freed " +
            "(lower the retention days or run cloud clean-up)."
        private const val KEY_STAFF_CODE = "staff.code"
        private const val KEY_STAFF_NAME = "staff.name"
        private const val KEY_STAFF_ADMIN = "staff.admin"
        private const val KEY_LAST_SYNC = "sync.last"
        private const val KEY_LAST_MAINTENANCE = "maintenance.last"
        private const val KEY_LAST_PURGE = "maintenance.summary"
        private const val KEY_USAGE = "cloud.usage"
    }
}
