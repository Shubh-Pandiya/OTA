package com.ota.desktop.sync

import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.cloud.AdviceLimitReached
import com.example.otaapplication.cloud.CatalogCloudApi
import com.example.otaapplication.cloud.CloudException
import com.example.otaapplication.cloud.CloudUsage
import com.example.otaapplication.cloud.OrderCloudApi
import com.example.otaapplication.cloud.OrderLimitReached
import com.example.otaapplication.cloud.PostgrestCatalogApi
import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.PostgrestOrderApi
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.data.OtaAccounts
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.SyncIds
import com.example.otaapplication.data.SyncTime
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Deletions
import com.ota.desktop.data.LocalSyncTable
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.Kv
import com.ota.desktop.db.one
import com.ota.desktop.db.update
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
import java.time.LocalDate

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
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called once an offline advice gets its number (the app writes its PDF then). */
    private val onAdviceNumbered: (PaymentAdvice) -> Unit = {}
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
        return StaffProfile(code, kv[KEY_STAFF_NAME].orEmpty(), isAdmin())
    }

    /** Admins (archive, purge, retention, Accounts) are fixed by sign-in email, the same rule the cloud enforces. */
    fun isAdmin(): Boolean = OtaAccounts.isAdmin(auth.session.value?.email)

    /** Which data set is in use (the last login's); installs from before 1.5.0 hold real data. */
    fun dataMode(): String = kv[KEY_DATA_MODE] ?: OtaAccounts.MODE_PRODUCTION

    /** On start: picks up the data set in use, so queries, ids and PDF folders follow it. */
    fun loadDataMode() {
        if (kv[KEY_DATA_MODE] == null) auth.session.value?.let { kv[KEY_DATA_MODE] = OtaAccounts.dataMode(it.email) }
        useDataMode(dataMode())
    }

    private fun useDataMode(mode: String) {
        val test = mode == OtaAccounts.MODE_TEST
        db.schema = if (test) Db.TEST else Db.PRODUCTION
        SyncIds.testMode = test
    }

    /**
     * After a sign-in: switches to [email]'s data set. Real and test data live side by side in
     * separate schemas, so switching never deletes or mixes anything; each set keeps its own
     * unsynced changes, which go up the next time its account signs in.
     */
    suspend fun adoptDataMode(email: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val mode = OtaAccounts.dataMode(email)
            kv[KEY_DATA_MODE] = mode
            useDataMode(mode)
            _status.update { it.copy(lastSyncAt = kv.long(KEY_LAST_SYNC), error = null, notices = emptyList()) }
        }
        refreshPending()
    }

    /** Test account only: deletes its rows in the cloud, then this laptop's test copy, test books and PDFs. */
    suspend fun clearTestData(): Map<String, Int> = mutex.withLock {
        check(OtaAccounts.isTest(auth.session.value?.email) && dataMode() == OtaAccounts.MODE_TEST && db.schema == Db.TEST) { "Only the test account can clear test data" }
        val counts = orderApi.clearTestData()
        withContext(Dispatchers.IO) {
            db.tx { c ->
                // Schema-qualified: whatever happens to the pool, this only ever touches the test data set.
                for (t in listOf("advice_lines", "payment_advices", "order_bills", "bills", "orders", "buyers", "suppliers", "pending_deletions", "acc_vouchers", "acc_audit")) {
                    c.update("delete from ${Db.TEST}.$t")
                }
                c.update("delete from ${Db.TEST}.kv where key like 'cursor.%' or key like 'counter.%' or key = 'sync.last'")
            }
            com.ota.desktop.AppPaths.testDocuments.deleteRecursively()
        }
        _status.update { it.copy(lastSyncAt = null, error = null, notices = emptyList(), pending = pendingCount()) }
        counts
    }

    /** Reads the staff row for the signed-in user and caches it, so numbering works offline. */
    suspend fun refreshStaff(): StaffProfile? {
        val userId = auth.session.value?.userId ?: return cachedStaff()
        val body = client.call("GET", "staff?select=code,name,is_admin&user_id=eq.$userId")
        val array = JSONArray(body)
        val profile = if (array.length() == 0) null else array.getJSONObject(0).let {
            StaffProfile(it.getInt("code"), if (it.isNull("name")) "" else it.optString("name"), isAdmin())
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

    private fun pendingCount() = catalog.pendingCount() + orders.pendingCount() + db.read { c -> Deletions.pendingCount(c) }

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
            syncDeletions()
            syncCatalog()
            syncOrders(notices)
            if (maintenance && isAdmin()) maintain()
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

    // ------------------------------------------------------------------ deletes

    /**
     * Hard deletes, before any rows move: deletes made elsewhere (tombstones) are applied first, so a
     * deleted row is never uploaded again; then this laptop's deletes go up. Where the cloud kept a
     * row because it was edited after the delete, that table is pulled again from the start to bring
     * the newer row back. Until the cloud has the hard-delete migration, deletes simply wait.
     */
    private suspend fun syncDeletions() {
        try {
            pullPages(DELETIONS_CURSOR, { after, afterId -> orderApi.pullDeletions(after, afterId, pageSize) }, { it.serverUpdatedAt }, { it.id }) { page ->
                Deletions.applyPulled(db, page)
            }
            var previous: Set<Pair<String, Long>>? = null
            var batches = 0
            while (batches++ < MAX_PAGES) {
                val batch = withContext(Dispatchers.IO) { Deletions.pending(db, pushBatch) }
                val keys = batch.mapTo(HashSet()) { it.id to it.at }
                if (batch.isEmpty() || keys == previous) break
                previous = keys
                val kept = orderApi.deleteRows(batch)
                withContext(Dispatchers.IO) {
                    Deletions.markSent(db, batch)
                    batch.filter { it.id in kept }.mapTo(HashSet()) { it.table }.forEach { kv.remove("cursor.$it") }
                }
            }
        } catch (e: CloudException) {
            if (!e.missingInCloud) throw e
        }
    }

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
        numberPendingOrders(notices)
        pushNumbered(LocalSyncTable.ORDERS, notices) { row ->
            resolveDuplicate(row, row.value.offerNo, Numbers.Kind.ORDER, LocalSyncTable.ORDERS) { OrderNumber.parseOrderDate(row.value.date) }
        }
        push(LocalSyncTable.BILLS)
        push(LocalSyncTable.LINKS)
        numberPendingAdvices(notices)
        pushNumbered(LocalSyncTable.ADVICES, notices) { row ->
            resolveDuplicate(row, row.value.adviceNo, Numbers.Kind.ADVICE, LocalSyncTable.ADVICES) { row.value.payDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
        }
        push(LocalSyncTable.ADVICE_LINES)
    }

    /**
     * The agency-wide number for a new order dated [orderDate], from the cloud; null when the laptop
     * is offline or not signed in (the order is saved as "Pending number" and numbered by the next
     * sync). Throws [OrderLimitReached] once the financial year's 9999 numbers are used.
     */
    suspend fun allocateOrderNo(orderDate: LocalDate): String? {
        if (auth.session.value == null) return null
        return try {
            orderApi.allocateOrderNo(orderDate)
        } catch (e: OrderLimitReached) {
            throw e
        } catch (e: CloudException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    /**
     * Same as the phone: orders saved offline get the next number from the cloud before anything is
     * uploaded (an order never reaches the cloud without its number). Deleted before they were
     * numbered, they never existed anywhere else, so they are simply dropped.
     */
    private suspend fun numberPendingOrders(notices: MutableList<String>) {
        val refs = withContext(Dispatchers.IO) { orders.numberPending() }
        for ((id, dateText, deleted) in refs) {
            if (deleted) {
                withContext(Dispatchers.IO) { orders.dropUnnumbered(id) }
                continue
            }
            val date = OrderNumber.parseOrderDate(dateText) ?: LocalDate.now()
            val number = try {
                orderApi.allocateOrderNo(date)
            } catch (e: OrderLimitReached) {
                notices += e.message.orEmpty()
                return
            } catch (e: CloudException) {
                if (e.authFailed || e.storageFull) throw e
                notices += "Orders saved offline could not get a number yet: ${e.message}"
                return
            }
            if (withContext(Dispatchers.IO) { orders.assignNumber(id, number) }) notices += "An order saved offline is now order $number."
        }
    }

    /**
     * A new payment advice's FY number (PA-26270001), from the cloud's own advice counter; null when
     * offline or signed out (saved as "Pending number"). Throws [AdviceLimitReached] after 9999 a year.
     */
    suspend fun allocateAdviceNo(payDate: LocalDate): String? {
        if (auth.session.value == null) return null
        return try {
            orderApi.allocateAdviceNo(payDate)
        } catch (e: AdviceLimitReached) {
            throw e
        } catch (e: CloudException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** Advices saved offline get their number before anything about them uploads; same as orders. */
    private suspend fun numberPendingAdvices(notices: MutableList<String>) {
        val refs = withContext(Dispatchers.IO) { payments.numberPending() }
        for ((id, payDate, deleted) in refs) {
            if (deleted) {
                withContext(Dispatchers.IO) { payments.dropUnnumbered(id) }
                continue
            }
            val number = try {
                orderApi.allocateAdviceNo(payDate ?: LocalDate.now())
            } catch (e: AdviceLimitReached) {
                notices += e.message.orEmpty()
                return
            } catch (e: CloudException) {
                if (e.authFailed || e.storageFull) throw e
                notices += "Payment advices saved offline could not get a number yet: ${e.message}"
                return
            }
            if (withContext(Dispatchers.IO) { payments.assignNumber(id, number) }) {
                withContext(Dispatchers.IO) { payments.advice(id)?.let { a -> runCatching { onAdviceNumbered(a) } } }
                notices += "A payment advice saved offline is now ${AdviceNumber.display(number)}."
            }
        }
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
    private suspend fun <T> pushNumbered(t: LocalSyncTable<T>, notices: MutableList<String>, resolve: suspend (PendingRow<T>) -> Pair<String?, Boolean>): Int {
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
                        val (notice, renumbered) = resolve(row)
                        notice?.let(notices::add)
                        if (renumbered) progress = true
                    }
                }
            }
        }
        return sent
    }

    /**
     * The cloud already has another row with [old]'s number. A per-staff number is replaced locally;
     * an FY number (26270001) gets a fresh one from the cloud counter, which never hands one out twice.
     */
    private suspend fun <T> resolveDuplicate(
        row: PendingRow<T>, old: String, kind: Numbers.Kind, t: LocalSyncTable<T>, date: () -> LocalDate?
    ): Pair<String?, Boolean> {
        if (row.deleted) {
            // Deleted before it ever reached the cloud; there is nothing to keep there.
            withContext(Dispatchers.IO) { t.markPushed(db, listOf(row)) }
            return null to true
        }
        val isOrder = kind == Numbers.Kind.ORDER
        val label = if (isOrder) "Order" else "Payment advice"
        val replacement = if (OrderNumber.isFy(old)) {
            val day = date() ?: LocalDate.now()
            try {
                if (isOrder) orderApi.allocateOrderNo(day) else orderApi.allocateAdviceNo(day)
            } catch (e: CloudException) {
                if (e.authFailed || e.storageFull) throw e
                null
            } catch (e: OrderLimitReached) {
                null
            } catch (e: AdviceLimitReached) {
                null
            }
        } else {
            val parsable = if (isOrder) OrderNumber.prefixOf(old) != null else AdviceNumber.prefixOf(old) != null
            if (parsable) withContext(Dispatchers.IO) { numbers.replacementFor(kind, old) } else null
        }
        val done = replacement != null && withContext(Dispatchers.IO) {
            if (isOrder) orders.renumber(row.id, replacement) else payments.renumber(row.id, replacement)
        }
        if (done && !isOrder) withContext(Dispatchers.IO) { payments.advice(row.id)?.let { a -> runCatching { onAdviceNumbered(a) } } }
        val shown = { no: String -> if (isOrder) no else AdviceNumber.display(no) }
        return if (done) "$label ${shown(old)} was also used on another device; this copy is now ${shown(replacement!!)}." to true
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
        private const val DELETIONS_CURSOR = "cursor.ota_deletions"
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
        private const val KEY_DATA_MODE = "data.mode"
    }
}
