package com.example.otaapplication.cloud

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.AdviceLineValue
import com.example.otaapplication.data.AdviceValue
import com.example.otaapplication.data.BillValue
import com.example.otaapplication.data.Deletion
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.LinkValue
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import com.example.otaapplication.data.SyncTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The staff member's order number digit, read from the cloud `staff` table. */
data class StaffInfo(val code: Int, val name: String)

/**
 * One synced order table: its name, the columns read on pull (only what the phone needs) and
 * the JSON mapping. See supabase/migrations/20261108000000_orders.sql.
 */
class OrderTable<T>(
    val name: String,
    val columns: String,
    val toJson: (PendingRow<T>) -> JSONObject,
    val parse: (JSONObject) -> RemoteRow<T>
) {
    companion object {
        val ORDERS = OrderTable<OrderFormData>(
            name = "orders",
            columns = "id,order_no,order_date,buyer,supplier,items,remarks,delivery_ready,transport_to,cash_payment,less_disc,side,deleted,updated_at,client_updated_at",
            toJson = { row ->
                // A deleted order keeps only its number and date in the cloud.
                val d = if (row.deleted) OrderFormData(date = row.value.date, offerNo = row.value.offerNo, items = emptyList()) else row.value
                JSONObject()
                    .put("id", row.id)
                    .put("order_no", d.offerNo)
                    .put("order_date", d.date)
                    .put("buyer", d.buyer)
                    .put("supplier", d.supplier)
                    .put("items", itemsToJson(d.items))
                    .put("remarks", d.remarks)
                    .put("delivery_ready", d.deliveryReady)
                    .put("transport_to", d.transportTo)
                    .put("cash_payment", d.cashPayment)
                    .put("less_disc", d.lessDisc)
                    .put("side", d.side)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, OrderFormData(
                    date = json.text("order_date"),
                    offerNo = json.text("order_no"),
                    buyer = json.text("buyer"),
                    supplier = json.text("supplier"),
                    items = itemsFromJson(json.optJSONArray("items")),
                    remarks = json.text("remarks"),
                    deliveryReady = json.text("delivery_ready"),
                    transportTo = json.text("transport_to"),
                    cashPayment = json.text("cash_payment"),
                    lessDisc = json.text("less_disc"),
                    side = json.text("side")
                ))
            }
        )

        val BILLS = OrderTable<BillValue>(
            name = "bills",
            columns = "id,bill_no,bill_date,amount,lr_no,cd_percent,cd_amount,items,buyer,supplier,transport,deleted,updated_at,client_updated_at",
            toJson = { row ->
                JSONObject()
                    .put("id", row.id)
                    .put("bill_no", row.value.billNo)
                    .put("bill_date", row.value.billDate ?: JSONObject.NULL)
                    .put("amount", row.value.amount ?: JSONObject.NULL)
                    .put("lr_no", row.value.lrNo)
                    .put("cd_percent", row.value.cdPercent ?: JSONObject.NULL)
                    .put("cd_amount", row.value.cdAmount ?: JSONObject.NULL)
                    .put("items", row.value.items.takeIf { it.isNotEmpty() }?.let(::itemsToJson) ?: JSONObject.NULL)
                    // Null (not "") when the parties come from the bill's orders: keeps rows small.
                    .put("buyer", row.value.buyer.ifBlank { null } ?: JSONObject.NULL)
                    .put("supplier", row.value.supplier.ifBlank { null } ?: JSONObject.NULL)
                    .put("transport", row.value.transport.ifBlank { null } ?: JSONObject.NULL)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, BillValue(
                    billNo = json.text("bill_no"),
                    billDate = json.text("bill_date").ifBlank { null },
                    amount = json.number("amount"),
                    lrNo = json.text("lr_no"),
                    cdPercent = json.number("cd_percent"),
                    cdAmount = json.number("cd_amount"),
                    items = itemsFromJson(json.optJSONArray("items")),
                    buyer = json.text("buyer"),
                    supplier = json.text("supplier"),
                    transport = json.text("transport")
                ))
            }
        )

        val LINKS = OrderTable<LinkValue>(
            name = "order_bills",
            columns = "id,order_id,bill_id,deleted,updated_at,client_updated_at",
            toJson = { row ->
                JSONObject()
                    .put("id", row.id)
                    .put("order_id", row.value.orderId)
                    .put("bill_id", row.value.billId)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json -> remoteRow(json, LinkValue(json.getString("order_id"), json.getString("bill_id"))) }
        )

        /** See supabase/migrations/20261109000000_payment_advice.sql. */
        val ADVICES = OrderTable<AdviceValue>(
            name = "payment_advices",
            columns = "id,advice_no,pay_date,buyer,supplier,mode,reference,balance,balance_side,remarks,deleted,updated_at,client_updated_at",
            toJson = { row ->
                val v = row.value
                // A deleted advice keeps only its number and date in the cloud.
                val keep = !row.deleted
                JSONObject()
                    .put("id", row.id)
                    .put("advice_no", v.adviceNo)
                    .put("pay_date", v.payDate ?: JSONObject.NULL)
                    .put("buyer", if (keep) v.buyer else "")
                    .put("supplier", if (keep) v.supplier else "")
                    .put("mode", if (keep) v.mode else "CASH")
                    .put("reference", if (keep) v.reference else "")
                    .put("balance", v.balanceAmount?.takeIf { keep } ?: JSONObject.NULL)
                    .put("balance_side", v.balanceSide?.takeIf { keep && v.balanceAmount != null } ?: JSONObject.NULL)
                    .put("remarks", if (keep) v.remarks else "")
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, AdviceValue(
                    adviceNo = json.text("advice_no"),
                    payDate = json.text("pay_date").ifBlank { null },
                    buyer = json.text("buyer"),
                    supplier = json.text("supplier"),
                    mode = json.text("mode"),
                    reference = json.text("reference"),
                    balanceAmount = json.number("balance"),
                    balanceSide = json.text("balance_side").ifBlank { null },
                    remarks = json.text("remarks")
                ))
            }
        )

        val ADVICE_LINES = OrderTable<AdviceLineValue>(
            name = "advice_lines",
            columns = "id,advice_id,bill_id,paid,cd_percent,cd_amount,goods_return,rate_diff,claim,claim_note,deleted,updated_at,client_updated_at",
            toJson = { row ->
                val a = row.value.amounts
                JSONObject()
                    .put("id", row.id)
                    .put("advice_id", row.value.adviceId)
                    .put("bill_id", row.value.billId)
                    .put("paid", a.paid)
                    .put("cd_percent", a.cdPercent ?: JSONObject.NULL)
                    .put("cd_amount", a.cdAmount ?: JSONObject.NULL)
                    .put("goods_return", a.goodsReturn ?: JSONObject.NULL)
                    .put("rate_diff", a.rateDiff ?: JSONObject.NULL)
                    .put("claim", a.claim ?: JSONObject.NULL)
                    .put("claim_note", a.claimNote)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, AdviceLineValue(
                    json.getString("advice_id"),
                    json.getString("bill_id"),
                    LineAmounts(
                        paid = json.number("paid") ?: 0.0,
                        cdPercent = json.number("cd_percent"),
                        cdAmount = json.number("cd_amount"),
                        goodsReturn = json.number("goods_return"),
                        rateDiff = json.number("rate_diff"),
                        claim = json.number("claim"),
                        claimNote = json.text("claim_note")
                    )
                ))
            }
        )

        /** Items as compact arrays: [s.no, particulars, qty, rate] plus the unit code when set. */
        fun itemsToJson(items: List<OrderItem>) = JSONArray().apply {
            items.forEach {
                val row = JSONArray().put(it.serialNo).put(it.particulars).put(it.quantity).put(it.rate)
                if (it.unit.isNotEmpty()) row.put(it.unit)
                put(row)
            }
        }

        fun itemsFromJson(array: JSONArray?): List<OrderItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONArray(i) ?: return@mapNotNull null
                OrderItem(item.optString(0, ""), item.optString(1, ""), item.optString(2, ""), item.optString(3, ""), item.optString(4, ""))
            }
        }

        private fun <T> remoteRow(json: JSONObject, value: T): RemoteRow<T> {
            val serverUpdatedAt = json.getString("updated_at")
            return RemoteRow(
                id = json.getString("id"),
                value = value,
                deleted = json.optBoolean("deleted", false),
                clientUpdatedAt = if (json.isNull("client_updated_at")) SyncTime.toMillis(serverUpdatedAt) else json.getLong("client_updated_at"),
                serverUpdatedAt = serverUpdatedAt
            )
        }

        private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

        /** Postgres numeric arrives as a JSON number (or a string for very large values). */
        private fun JSONObject.number(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() || it.isInfinite() }
    }
}

/** Cloud database size against the plan quota, plus how long the cloud keeps orders. */
data class CloudUsage(val usedBytes: Long, val limitMb: Int, val warnPercent: Int, val retentionDays: Int, val percent: Double) {
    val warning: Boolean get() = percent >= warnPercent
    val usedMb: Double get() = usedBytes / 1_048_576.0

    fun toJson(): String = JSONObject()
        .put("used_bytes", usedBytes).put("limit_mb", limitMb).put("warn_percent", warnPercent)
        .put("retention_days", retentionDays).put("percent", percent).toString()

    companion object {
        fun parse(text: String?): CloudUsage? {
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            if (!json.has("used_bytes")) return null
            return CloudUsage(
                usedBytes = json.optLong("used_bytes"),
                limitMb = json.optInt("limit_mb", 500),
                warnPercent = json.optInt("warn_percent", 80),
                retentionDays = json.optInt("retention_days", 30),
                percent = json.optDouble("percent", 0.0)
            )
        }
    }
}

/** Cloud side of orders, bills and bill links. */
interface OrderCloudApi {
    /** Rows changed after the cursor, oldest first. [afterId] breaks ties between equal timestamps. */
    suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>>

    /** Upserts rows (the server keeps a newer copy if it has one). */
    suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>)

    /** The signed-in user's staff code, or null when the owner hasn't added one. */
    suspend fun staff(userId: String): StaffInfo?

    /** Storage use and retention (ota_cloud_status); null when not available. */
    suspend fun usage(): CloudUsage? = null

    /**
     * The next agency-wide order number for the financial year of [orderDate] (e.g. 26270001).
     * Each call uses up a number. Throws [OrderLimitReached] once the year's 9999 are used.
     */
    suspend fun allocateOrderNo(orderDate: LocalDate): String = throw UnsupportedOperationException("allocateOrderNo")

    /**
     * The next agency-wide payment advice number for the financial year of [payDate], e.g. "26270001",
     * from the advices' own counter. Throws [AdviceLimitReached] once the year's 9999 are used.
     */
    suspend fun allocateAdviceNo(payDate: LocalDate): String = throw UnsupportedOperationException("allocateAdviceNo")

    /** Test account only: deletes all its cloud rows (ota_clear_test_data); returns rows removed per table. */
    suspend fun clearTestData(): Map<String, Int> = throw UnsupportedOperationException("clearTestData")

    /**
     * Hard-deletes rows in the cloud (ota_delete_rows): each row goes with its links / advice lines
     * and leaves a small tombstone so other devices remove it too, unless the cloud copy was edited
     * after the delete. Returns the ids the cloud kept for that reason.
     */
    suspend fun deleteRows(rows: List<Deletion>): Set<String> = throw UnsupportedOperationException("deleteRows")

    /** Tombstones logged after the cursor (ota_deletions), oldest first. */
    suspend fun pullDeletions(after: String?, afterId: String?, limit: Int): List<Deletion> = emptyList()

    /** The cloud's clock, no later than when the next call is served; null when unknown. See [DeltaPull]. */
    fun serverTime(): String? = null
}

/**
 * One table's delta pull, page by page in (updated_at, id) order, so a slow link makes progress
 * and memory stays bounded. The cursor is saved after every applied page: an interrupted pull
 * resumes there instead of starting over.
 *
 * A saved cursor C means every row changed at or before C minus [OVERLAP_SECONDS] has been read
 * (the next pull starts there; the overlap covers transactions that commit out of order, up to a
 * minute, and rows sharing a timestamp across a page boundary). With [serverStart], the cloud's
 * clock before the first page was asked for, a finished pull moves C up to that time (less
 * [CLOCK_SLACK_SECONDS]) instead of to the newest row read: then a bulk change (an import, archive
 * marking) isn't downloaded again by every later round. Without it, C is the newest row read.
 */
object DeltaPull {
    const val OVERLAP_SECONDS = 60L
    /** Allowance for the HTTP `Date` clock running ahead of the database's. */
    const val CLOCK_SLACK_SECONDS = 60L
    const val MAX_PAGES = 100
    /**
     * Rows per pull request. Never above the cloud API's "max rows" (1000 on this project): a shorter
     * page than asked for means the end of the table.
     */
    const val PAGE_SIZE = 1000

    /** Where a pull from [saved] starts. */
    fun firstAfter(saved: String?): String? = saved?.let { SyncTime.minusSeconds(it, OVERLAP_SECONDS) }

    /** Pulls, applies and saves page by page; returns what [apply] reported changed. */
    suspend fun <R> run(
        saved: String?,
        serverStart: String?,
        pageSize: Int,
        fetch: suspend (after: String?, afterId: String?) -> List<R>,
        updatedAt: (R) -> String,
        id: (R) -> String,
        apply: suspend (List<R>) -> Int,
        save: suspend (String) -> Unit
    ): Int {
        val settled = serverStart?.let { SyncTime.minusSeconds(it, CLOCK_SLACK_SECONDS) }
        var after = firstAfter(saved)
        var afterId: String? = null
        var cursor = saved
        var changed = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = fetch(after, afterId)
            changed += apply(page)
            val complete = page.size < pageSize
            val newest = page.fold(null as String?) { acc, row -> SyncTime.later(acc, updatedAt(row)) }
            val reach = when {
                settled != null && complete -> settled
                newest == null -> null
                // Rows up to the settled time were all committed before the first page, so none is missed below it.
                settled != null -> if (SyncTime.later(newest, settled) == settled) newest else settled
                else -> newest
            }
            val next = reach?.let { SyncTime.later(cursor, it) }
            // Saved only after the page's rows were applied, so a failed round reads them again.
            if (next != null && next != cursor) {
                save(next)
                cursor = next
            }
            if (complete) break
            after = updatedAt(page.last())
            afterId = id(page.last())
        }
        return changed
    }
}

/**
 * Reads tables' pages ahead while a sync round applies them one table at a time, so on a slow
 * link the round trips overlap instead of queuing (at most [parallel] at once, within the
 * keep-alive pool). Each table streams its pages in order; [fetch] hands over a page read ahead
 * when it is the one asked for, otherwise (the cursor changed meanwhile) drops the stream and
 * reads directly. A failed read surfaces when its page is asked for, as a direct read would.
 * Start it only after the round's deletes are done, so a row pulled ahead can't bring back one
 * that was just deleted. [serverStart] is the cloud's clock before anything was read ahead.
 */
class PullAhead(private val scope: CoroutineScope, val serverStart: String?, parallel: Int = 4) {
    private class Page(val after: String?, val afterId: String?, val rows: Result<List<Any?>>)
    private class Stream(val pages: Channel<Page>, val job: Job)

    private val gate = Semaphore(parallel)
    private val streams = ConcurrentHashMap<String, Stream>()

    fun <R> start(
        key: String,
        saved: String?,
        pageSize: Int,
        read: suspend (after: String?, afterId: String?) -> List<R>,
        updatedAt: (R) -> String,
        id: (R) -> String
    ) {
        // Rendezvous: at most one page per table waits in memory.
        val pages = Channel<Page>(Channel.RENDEZVOUS)
        val job = scope.launch {
            var after = DeltaPull.firstAfter(saved)
            var afterId: String? = null
            try {
                repeat(DeltaPull.MAX_PAGES) {
                    val rows = try {
                        Result.success(gate.withPermit { read(after, afterId) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    pages.send(Page(after, afterId, rows))
                    val list = rows.getOrNull() ?: return@launch
                    if (list.size < pageSize) return@launch
                    after = updatedAt(list.last())
                    afterId = id(list.last())
                }
            } finally {
                pages.close()
            }
        }
        streams.put(key, Stream(pages, job))?.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <R> fetch(key: String, after: String?, afterId: String?, read: suspend (after: String?, afterId: String?) -> List<R>): List<R> {
        val stream = streams[key] ?: return read(after, afterId)
        val page = stream.pages.receiveCatching().getOrNull()
        if (page != null && page.after == after && page.afterId == afterId) return page.rows.getOrThrow() as List<R>
        streams.remove(key)?.cancel()
        return read(after, afterId)
    }

    /** Cancels whatever is still being read ahead. */
    fun stop() {
        streams.values.forEach { it.cancel() }
        streams.clear()
    }

    private fun Stream.cancel() {
        job.cancel()
        pages.cancel()
    }
}

/** All 9999 payment advice numbers of a financial year are used. */
class AdviceLimitReached(message: String) : Exception(message)

/** All 9999 order numbers of a financial year are used; new orders can't get a number. */
class OrderLimitReached(message: String) : Exception(message)

class PostgrestOrderApi(private val client: PostgrestClient) : OrderCloudApi {

    override suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>> {
        val filter = when {
            after == null -> ""
            afterId == null -> "&updated_at=gt.${Http.encode(after)}"
            else -> "&or=" + Http.encode("(updated_at.gt.\"$after\",and(updated_at.eq.\"$after\",id.gt.$afterId))")
        }
        val body = client.call("GET", "${table.name}?select=${table.columns}&order=updated_at.asc,id.asc&limit=$limit$filter")
        val array = JSONArray(body)
        return List(array.length()) { i -> table.parse(array.getJSONObject(i)) }
    }

    override suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>) {
        if (rows.isEmpty()) return
        client.call(
            "POST", "${table.name}?on_conflict=id",
            // Nothing is sent back: changes made elsewhere arrive with the next pull.
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            JSONArray().apply { rows.forEach { put(table.toJson(it)) } }.toString()
        )
    }

    override suspend fun staff(userId: String): StaffInfo? {
        val body = client.call("GET", "staff?select=code,name&user_id=eq.${Http.encode(userId)}")
        val array = JSONArray(body)
        if (array.length() == 0) return null
        val json = array.getJSONObject(0)
        return StaffInfo(json.getInt("code"), json.optString("name").takeUnless { json.isNull("name") }.orEmpty())
    }

    override suspend fun usage(): CloudUsage? =
        CloudUsage.parse(client.call("POST", "rpc/ota_cloud_status", body = "{}").trim().takeUnless { it == "null" })

    override suspend fun allocateOrderNo(orderDate: LocalDate): String {
        val body = try {
            client.call("POST", "rpc/ota_allocate_order_no", body = JSONObject().put("p_date", orderDate.toString()).toString())
        } catch (e: CloudException) {
            if (e.message.orEmpty().contains(ORDER_LIMIT_CODE)) {
                throw OrderLimitReached(
                    "All ${OrderNumber.FY_MAX_SERIAL} order numbers for FY ${OrderNumber.fyLabel(orderDate)} are used. " +
                        "No more orders can be numbered this financial year; contact the owner."
                )
            }
            throw e
        }
        val number = JSONTokener(body.trim()).nextValue() as? String
        if (number == null || !OrderNumber.isFy(number)) throw CloudException("Unexpected order number from the cloud: $body", 200)
        return number
    }

    override suspend fun allocateAdviceNo(payDate: LocalDate): String {
        val body = try {
            client.call("POST", "rpc/ota_allocate_advice_no", body = JSONObject().put("p_date", payDate.toString()).toString())
        } catch (e: CloudException) {
            if (e.message.orEmpty().contains(ADVICE_LIMIT_CODE)) {
                throw AdviceLimitReached(
                    "All ${OrderNumber.FY_MAX_SERIAL} payment advice numbers for FY ${OrderNumber.fyLabel(payDate)} are used. " +
                        "No more advices can be numbered this financial year; contact the owner."
                )
            }
            throw e
        }
        val number = JSONTokener(body.trim()).nextValue() as? String
        if (number == null || !AdviceNumber.isFy(number)) throw CloudException("Unexpected advice number from the cloud: $body", 200)
        return number
    }

    override suspend fun clearTestData(): Map<String, Int> {
        val json = JSONObject(client.call("POST", "rpc/ota_clear_test_data", body = "{}").trim())
        return json.keys().asSequence().associateWith { json.optInt(it) }
    }

    override suspend fun deleteRows(rows: List<Deletion>): Set<String> {
        if (rows.isEmpty()) return emptySet()
        val payload = JSONArray().apply {
            rows.forEach { put(JSONObject().put("table", it.table).put("id", it.id).put("at", it.at)) }
        }
        val json = JSONObject(client.call("POST", "rpc/ota_delete_rows", body = JSONObject().put("p_rows", payload).toString()).trim())
        val kept = json.optJSONArray("kept") ?: JSONArray()
        return (0 until kept.length()).mapTo(HashSet()) { kept.getString(it) }
    }

    override suspend fun pullDeletions(after: String?, afterId: String?, limit: Int): List<Deletion> {
        val filter = when {
            after == null -> ""
            afterId == null -> "&updated_at=gt.${Http.encode(after)}"
            else -> "&or=" + Http.encode("(updated_at.gt.\"$after\",and(updated_at.eq.\"$after\",id.gt.$afterId))")
        }
        val array = JSONArray(client.call("GET", "ota_deletions?select=id,tbl,client_updated_at,updated_at&order=updated_at.asc,id.asc&limit=$limit$filter"))
        return List(array.length()) { i ->
            val json = array.getJSONObject(i)
            Deletion(json.getString("tbl"), json.getString("id"), json.optLong("client_updated_at"), json.getString("updated_at"))
        }
    }

    override fun serverTime(): String? = client.serverTime()

    private companion object {
        const val ORDER_LIMIT_CODE = "ORDER_LIMIT_REACHED"
        const val ADVICE_LIMIT_CODE = "ADVICE_LIMIT_REACHED"
    }
}
