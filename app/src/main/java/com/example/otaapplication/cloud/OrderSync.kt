package com.example.otaapplication.cloud

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.data.AdviceValue
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.PaymentRepository
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Pull cursors (a server `updated_at`, see [DeltaPull]) by table name. */
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
 * [payments] adds payment advices and their lines; [renumberAdvice] does the same for advices.
 * [numberOrder] stores the number the cloud gave an order saved offline (and makes its PDF);
 * [numberAdvice] does the same for advices; [adviceRenumbered] remakes an advice's PDF after a clash.
 * A clashing FY number (26270001) always gets a fresh number from the cloud counter.
 */
class OrderSync(
    private val orders: LocalOrders,
    private val api: OrderCloudApi,
    private val cursors: CursorStore,
    private val renumber: (String) -> String?,
    private val payments: PaymentRepository? = null,
    private val renumberAdvice: (String) -> String? = { null },
    private val numberOrder: (uuid: String, offerNo: String) -> Boolean = orders::renumber,
    private val numberAdvice: (adviceId: String, adviceNo: String) -> Boolean = { id, no -> payments?.assignNumber(id, no) == true },
    private val adviceRenumbered: (adviceId: String) -> Unit = {},
    private val pageSize: Int = DeltaPull.PAGE_SIZE,
    private val pushBatch: Int = 200
) {
    private val pulledTables: List<OrderTable<*>> =
        listOf(OrderTable.ORDERS, OrderTable.BILLS, OrderTable.LINKS) + if (payments != null) listOf(OrderTable.ADVICES, OrderTable.ADVICE_LINES) else emptyList()

    /** Starts reading every table's changes ahead (see [PullAhead]). */
    fun readAhead(ahead: PullAhead) {
        for (table in pulledTables) start(ahead, table)
    }

    private fun <T> start(ahead: PullAhead, table: OrderTable<T>) =
        ahead.start(table.name, cursors.get(table.name), pageSize, { after, afterId -> api.pull(table, after, afterId, pageSize) }, { it.serverUpdatedAt }, { it.id })

    suspend fun syncAll(ahead: PullAhead? = null): OrderSyncResult {
        val repo = orders.index
        var changed = 0
        // Parents first, so links can find their order and bill.
        changed += pull(OrderTable.ORDERS, ahead) { withContext(Dispatchers.IO) { orders.applyPulled(it) } }
        changed += pull(OrderTable.BILLS, ahead) { withContext(Dispatchers.IO) { repo.applyRemoteBills(it) } }
        changed += pull(OrderTable.LINKS, ahead) { withContext(Dispatchers.IO) { repo.applyRemoteLinks(it) } }
        if (payments != null) {
            changed += pull(OrderTable.ADVICES, ahead) { withContext(Dispatchers.IO) { payments.applyRemoteAdvices(it) } }
            changed += pull(OrderTable.ADVICE_LINES, ahead) { withContext(Dispatchers.IO) { payments.applyRemoteLines(it) } }
        }

        val notices = ArrayList<String>()
        numberPendingOrders(notices)
        var pushed = pushOrders(notices)
        pushed += push(OrderTable.BILLS) { repo.pendingBills(pushBatch) }
        pushed += push(OrderTable.LINKS) { repo.pendingLinks(pushBatch) }
        if (payments != null) {
            numberPendingAdvices(payments, notices)
            pushed += pushNumbered(OrderTable.ADVICES, notices, { payments.pendingAdvices(pushBatch) }) { row -> resolveAdviceDuplicate(payments, row) }
            pushed += push(OrderTable.ADVICE_LINES) { payments.pendingLines(pushBatch) }
        }
        return OrderSyncResult(changed, pushed, notices)
    }

    private suspend fun <T> pull(table: OrderTable<T>, ahead: PullAhead?, apply: suspend (List<RemoteRow<T>>) -> Int): Int {
        val read: suspend (String?, String?) -> List<RemoteRow<T>> = { after, afterId -> api.pull(table, after, afterId, pageSize) }
        return DeltaPull.run(
            saved = cursors.get(table.name),
            serverStart = ahead?.serverStart ?: api.serverTime(),
            pageSize = pageSize,
            fetch = { after, afterId -> ahead?.fetch(table.name, after, afterId, read) ?: read(after, afterId) },
            updatedAt = { it.serverUpdatedAt },
            id = { it.id },
            apply = apply,
            save = { cursors.set(table.name, it) }
        )
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

    /**
     * Orders saved offline have no number yet: each gets the next one from the cloud before
     * anything is uploaded (an order never reaches the cloud without its number). Deleted before
     * they were numbered, they never existed anywhere else, so they are simply dropped.
     */
    private suspend fun numberPendingOrders(notices: MutableList<String>) {
        val refs = withContext(Dispatchers.IO) { orders.index.unnumberedOrders() }
        for (ref in refs) {
            if (ref.deleted) {
                withContext(Dispatchers.IO) { orders.index.markSynced(OrderTable.ORDERS.name, listOf(ref.id to ref.updatedAt)) }
                continue
            }
            val date = OrderNumber.parseOrderDate(ref.date) ?: LocalDate.now()
            val number = try {
                api.allocateOrderNo(date)
            } catch (e: OrderLimitReached) {
                notices += e.message.orEmpty()
                return
            } catch (e: CloudException) {
                // e.g. the cloud doesn't have the numbering function yet: the rest still syncs.
                if (e.authFailed || e.storageFull) throw e
                notices += "Orders saved offline could not get a number yet: ${e.message}"
                return
            }
            if (withContext(Dispatchers.IO) { numberOrder(ref.id, number) }) notices += "An order saved offline is now order $number."
        }
    }

    /** Same as [numberPendingOrders] for payment advices saved offline. */
    private suspend fun numberPendingAdvices(payments: PaymentRepository, notices: MutableList<String>) {
        val refs = withContext(Dispatchers.IO) { payments.unnumberedAdvices() }
        for (ref in refs) {
            val number = try {
                api.allocateAdviceNo(ref.payDate ?: LocalDate.now())
            } catch (e: AdviceLimitReached) {
                notices += e.message.orEmpty()
                return
            } catch (e: CloudException) {
                if (e.authFailed || e.storageFull) throw e
                notices += "Payment advices saved offline could not get a number yet: ${e.message}"
                return
            }
            if (withContext(Dispatchers.IO) { numberAdvice(ref.id, number) }) {
                notices += "A payment advice saved offline is now ${AdviceNumber.display(number)}."
            }
        }
    }

    private suspend fun pushOrders(notices: MutableList<String>): Int =
        pushNumbered(OrderTable.ORDERS, notices, ::pendingOrders, ::resolveDuplicate)

    /**
     * Pushes rows that carry a per-staff number (orders, advices). If the cloud already has a
     * number, rows go one by one to find it and [resolve] gives that row a new number.
     */
    private suspend fun <T> pushNumbered(
        table: OrderTable<T>,
        notices: MutableList<String>,
        pending: () -> List<PendingRow<T>>,
        resolve: suspend (PendingRow<T>) -> Pair<String?, Boolean>
    ): Int {
        var sent = 0
        var batches = 0
        var progress = true
        // Stops once a batch makes no progress (nothing uploaded or renumbered), so a row the
        // cloud keeps refusing can never loop forever.
        while (progress && batches++ < MAX_PAGES) {
            val batch = withContext(Dispatchers.IO) { pending() }
            if (batch.isEmpty()) break
            progress = false
            try {
                api.push(table, batch)
                markSynced(table, batch)
                sent += batch.size
                progress = true
            } catch (e: CloudException) {
                if (!e.duplicateKey) throw e
                for (row in batch) {
                    try {
                        api.push(table, listOf(row))
                        markSynced(table, listOf(row))
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

    private suspend fun <T> markSynced(table: OrderTable<T>, rows: List<PendingRow<T>>) =
        withContext(Dispatchers.IO) { orders.index.markSynced(table.name, rows.map { it.id to it.updatedAt }) }

    private suspend fun resolveAdviceDuplicate(payments: PaymentRepository, row: PendingRow<AdviceValue>): Pair<String?, Boolean> =
        withContext(Dispatchers.IO) {
            if (row.deleted) {
                payments.markSynced(OrderTable.ADVICES.name, listOf(row.id to row.updatedAt))
                return@withContext null to true
            }
            val old = row.value.adviceNo
            val replacement = if (AdviceNumber.isFy(old)) {
                freshNumber { api.allocateAdviceNo(row.value.payDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()) }
            } else if (AdviceNumber.prefixOf(old) != null) renumberAdvice(old) else null
            if (replacement != null && payments.renumber(row.id, replacement)) {
                runCatching { adviceRenumbered(row.id) }
                "Payment advice ${AdviceNumber.display(old)} was also used on another device; this copy is now ${AdviceNumber.display(replacement)}." to true
            } else {
                "Payment advice $old could not be uploaded: the cloud already has another advice with that number." to false
            }
        }

    private fun pendingOrders(): List<PendingRow<OrderFormData>> {
        val refs = orders.index.pendingOrders(pushBatch)
        val content = orders.contentOf(refs.filterNot { it.deleted }.map { it.id })
        return refs.mapNotNull { ref ->
            val data = if (ref.deleted) OrderFormData(date = ref.date, offerNo = ref.offerNo, items = emptyList()) else content[ref.id]
            data?.let { PendingRow(ref.id, it, ref.deleted, ref.updatedAt) }
        }
    }

    /** Returns the notice for the user and whether the order got a new number to retry with. */
    private suspend fun resolveDuplicate(row: PendingRow<OrderFormData>): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        if (row.deleted) {
            // Deleted before it ever reached the cloud; there is nothing to keep.
            orders.index.markSynced(OrderTable.ORDERS.name, listOf(row.id to row.updatedAt))
            return@withContext null to true
        }
        val old = row.value.offerNo
        if (OrderNumber.isFy(old)) {
            val fresh = freshNumber { api.allocateOrderNo(OrderNumber.parseOrderDate(row.value.date) ?: LocalDate.now()) }
            // Saved again under the new number, which also remakes its PDF.
            return@withContext if (fresh != null && numberOrder(row.id, fresh)) {
                "Order $old was also used on another device; this copy is now order $fresh." to true
            } else {
                "Order $old could not be uploaded: the cloud already has another order with that number." to false
            }
        }
        val replacement = if (OrderNumber.prefixOf(old) != null) renumber(old) else null
        if (replacement != null && orders.renumber(row.id, replacement)) {
            // The order stays pending and goes up with its new number in the next batch.
            "Order $old was also used on another phone; this copy is now order $replacement." to true
        } else {
            "Order $old could not be uploaded: the cloud already has another order with that number." to false
        }
    }

    /** A new number from the cloud counter, or null when it can't give one right now. */
    private suspend fun freshNumber(allocate: suspend () -> String): String? = try {
        allocate()
    } catch (e: CloudException) {
        if (e.authFailed || e.storageFull) throw e
        null
    } catch (e: OrderLimitReached) {
        null
    } catch (e: AdviceLimitReached) {
        null
    }

    internal companion object {
        const val MAX_PAGES = 100
    }
}
