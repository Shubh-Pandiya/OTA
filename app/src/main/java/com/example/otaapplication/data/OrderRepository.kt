package com.example.otaapplication.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Order search index plus bills. The index mirrors the order history file and is kept in step
 * with [reconcile]; bill links reference orders by their id (the order uuid).
 * All calls hit the database; run them off the main thread.
 */
class OrderRepository(
    private val helper: OtaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Brings the index in line with the history file. Returns true when anything changed. */
    fun reconcile(entries: List<HistoryIndexEntry>): Boolean {
        val db = helper.writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            data class Row(val fingerprint: String, val seq: Int, val deleted: Boolean)
            val existing = HashMap<String, Row>()
            db.rawQuery("SELECT id, fingerprint, seq, is_deleted FROM orders", null).use { c ->
                while (c.moveToNext()) existing[c.getString(0)] = Row(c.getString(1), c.getInt(2), c.getInt(3) != 0)
            }
            val now = clock()
            for (e in entries) {
                val fingerprint = fingerprint(e)
                val row = existing[e.id]
                when {
                    row == null -> {
                        db.insertOrThrow("orders", null, orderValues(e, fingerprint, now).apply {
                            put("id", e.id); put("created_at", now)
                        })
                        changed = true
                    }
                    row.fingerprint != fingerprint || row.deleted -> {
                        db.update("orders", orderValues(e, fingerprint, now), "id = ?", arrayOf(e.id))
                        changed = true
                    }
                    row.seq != e.seq -> {
                        db.update("orders", ContentValues().apply { put("seq", e.seq) }, "id = ?", arrayOf(e.id))
                        changed = true
                    }
                }
            }
            val present = entries.mapTo(HashSet()) { it.id }
            val removed = existing.filter { (id, row) -> !row.deleted && id !in present }.keys
            for (id in removed) {
                db.update("orders", tombstone(now), "id = ?", arrayOf(id))
                db.update("order_bills", tombstone(now), "order_id = ? AND is_deleted = 0", arrayOf(id))
            }
            if (removed.isNotEmpty()) {
                retireOrphanBills(db, now)
                changed = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun search(query: OrderQuery): List<OrderSummary> {
        val (where, args) = whereClause(query, includeStatus = true)
        val order = if (query.newestFirst) "o.day_key DESC, o.seq DESC" else "o.day_key ASC, o.seq ASC"
        return helper.readableDatabase.rawQuery(
            """
            SELECT o.id, o.uuid, o.offer_no, o.order_date, o.buyer, o.supplier, o.json_path, o.pdf_file_name,
                   (SELECT group_concat(b.bill_no, char(31)) FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
                    WHERE ob.order_id = o.id AND ob.is_deleted = 0 AND b.is_deleted = 0) AS bill_nos
            FROM orders o
            WHERE $where
            ORDER BY $order
            """.trimIndent(),
            args
        ).use { c ->
            val out = ArrayList<OrderSummary>(c.count)
            while (c.moveToNext()) {
                out += OrderSummary(
                    id = c.getString(0),
                    uuid = c.getStringOrNull(1),
                    offerNo = c.getString(2),
                    date = c.getString(3),
                    buyer = c.getString(4),
                    supplier = c.getString(5),
                    jsonPath = c.getString(6),
                    pdfFileName = c.getStringOrNull(7),
                    billNos = c.getStringOrNull(8)?.split(BILL_SEPARATOR)?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()
                )
            }
            out
        }
    }

    /** Totals for the status chips, honouring the text and date filters but not the status one. */
    fun counts(query: OrderQuery): OrderCounts {
        val (where, args) = whereClause(query, includeStatus = false)
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN $HAS_BILL THEN 0 ELSE 1 END), 0) FROM orders o WHERE $where",
            args
        ).use { c -> if (c.moveToFirst()) OrderCounts(c.getInt(0), c.getInt(1)) else OrderCounts(0, 0) }
    }

    fun billsForOrder(orderId: String): List<Bill> =
        helper.readableDatabase.rawQuery(
            """
            SELECT b.id, b.bill_no, b.bill_date, b.amount, $BILL_ORDER_COUNT
            FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
            WHERE ob.order_id = ? AND ob.is_deleted = 0 AND b.is_deleted = 0
            ORDER BY ob.created_at, b.bill_no COLLATE NOCASE
            """.trimIndent(),
            arrayOf(orderId)
        ).use { it.toBills() }

    /** Existing bills whose number contains [text], most recently touched first. */
    /** Existing bills whose number contains [text], skipping bills already on every one of [forOrders]. */
    fun suggestBills(text: String, forOrders: Collection<String> = emptyList(), limit: Int = 5): List<Bill> {
        val token = SearchText.normalize(text)
        if (token.isEmpty()) return emptyList()
        val targets = forOrders.distinct()
        val notOnAllTargets = if (targets.isEmpty()) "" else """
            AND (SELECT COUNT(*) FROM order_bills t WHERE t.bill_id = b.id AND t.is_deleted = 0
                 AND t.order_id IN (${targets.joinToString(",") { "?" }})) < ${targets.size}
            """
        return helper.readableDatabase.rawQuery(
            """
            SELECT b.id, b.bill_no, b.bill_date, b.amount, $BILL_ORDER_COUNT
            FROM bills b
            WHERE b.is_deleted = 0 AND lower(b.bill_no) LIKE ? ESCAPE '\'
            $notOnAllTargets
            ORDER BY b.updated_at DESC
            LIMIT $limit
            """.trimIndent(),
            arrayOf(SearchText.containsPattern(token)) + targets.toTypedArray()
        ).use { it.toBills() }
    }

    /**
     * Marks each order in [orderIds] as "bill received" with bill [billNo]. A bill number that is
     * already known is reused (so one bill can cover many orders); a new one is created otherwise.
     * [billDate]/[amount], when given, update the bill's details.
     */
    fun attachBill(orderIds: Collection<String>, billNo: String, billDate: LocalDate? = null, amount: Double? = null): Bill {
        val display = billNo.trim().replace(Regex("\\s+"), " ")
        require(display.isNotEmpty()) { "Bill number is required" }
        require(orderIds.isNotEmpty()) { "Select at least one order" }
        val key = SearchText.billKey(display)
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        try {
            val billId = db.rawQuery("SELECT id, is_deleted, bill_date, amount FROM bills WHERE bill_key = ?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)
                    val wasDeleted = c.getInt(1) != 0
                    val dateChanged = billDate != null && billDate.toString() != c.getStringOrNull(2)
                    val amountChanged = amount != null && (c.isNull(3) || c.getDouble(3) != amount)
                    if (wasDeleted || dateChanged || amountChanged) {
                        db.update("bills", ContentValues().apply {
                            put("is_deleted", 0)
                            if (billDate != null) put("bill_date", billDate.toString())
                            if (amount != null) put("amount", amount)
                            put("updated_at", now); put("sync_pending", 1)
                        }, "id = ?", arrayOf(id))
                    }
                    id
                } else null
            } ?: UUID.randomUUID().toString().also { id ->
                db.insertOrThrow("bills", null, ContentValues().apply {
                    put("id", id); put("bill_no", display); put("bill_key", key)
                    if (billDate != null) put("bill_date", billDate.toString())
                    if (amount != null) put("amount", amount)
                    put("created_at", now); put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
                })
            }
            for (orderId in orderIds.distinct()) {
                val revived = db.update(
                    "order_bills",
                    ContentValues().apply { put("is_deleted", 0); put("updated_at", now); put("sync_pending", 1) },
                    "order_id = ? AND bill_id = ? AND is_deleted = 1",
                    arrayOf(orderId, billId)
                )
                if (revived == 0) {
                    db.insertWithOnConflict("order_bills", null, ContentValues().apply {
                        put("id", UUID.randomUUID().toString()); put("order_id", orderId); put("bill_id", billId)
                        put("created_at", now); put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
            return billById(db, billId)
        } finally {
            db.endTransaction()
        }
    }

    /** Removes a (wrong) bill link from an order. A bill left with no orders is retired too. */
    fun detachBill(orderId: String, billId: String) {
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        try {
            db.update("order_bills", tombstone(now), "order_id = ? AND bill_id = ? AND is_deleted = 0", arrayOf(orderId, billId))
            retireOrphanBills(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun billById(db: SQLiteDatabase, id: String): Bill =
        db.rawQuery("SELECT b.id, b.bill_no, b.bill_date, b.amount, $BILL_ORDER_COUNT FROM bills b WHERE b.id = ?", arrayOf(id))
            .use { it.toBills().first() }

    private fun retireOrphanBills(db: SQLiteDatabase, now: Long) {
        db.update(
            "bills", tombstone(now),
            "is_deleted = 0 AND NOT EXISTS (SELECT 1 FROM order_bills ob WHERE ob.bill_id = bills.id AND ob.is_deleted = 0)",
            null
        )
    }

    private fun whereClause(query: OrderQuery, includeStatus: Boolean): Pair<String, Array<String>> {
        val clauses = mutableListOf("o.is_deleted = 0")
        val args = mutableListOf<String>()
        for (token in SearchText.tokens(query.text)) {
            clauses += """(o.search_text LIKE ? ESCAPE '\' OR EXISTS (SELECT 1 FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
                WHERE ob.order_id = o.id AND ob.is_deleted = 0 AND b.is_deleted = 0 AND lower(b.bill_no) LIKE ? ESCAPE '\'))"""
            val pattern = SearchText.containsPattern(token)
            args += pattern; args += pattern
        }
        if (includeStatus) when (query.status) {
            BillStatusFilter.ALL -> Unit
            BillStatusFilter.PENDING -> clauses += "NOT $HAS_BILL"
            BillStatusFilter.RECEIVED -> clauses += HAS_BILL
        }
        query.from?.let { clauses += "o.day_key >= ?"; args += SearchText.dayKey(it).toString() }
        query.to?.let { clauses += "o.day_key <= ?"; args += SearchText.dayKey(it).toString() }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun orderValues(e: HistoryIndexEntry, fingerprint: String, now: Long) = ContentValues().apply {
        put("uuid", e.uuid)
        put("offer_no", e.offerNo)
        put("order_date", e.date)
        put("day_key", SearchText.dayKey(e.date))
        put("seq", e.seq)
        put("buyer", e.buyer)
        put("supplier", e.supplier)
        put("particulars", e.particulars)
        put("json_path", e.jsonPath)
        put("pdf_file_name", e.pdfFileName)
        put("search_text", SearchText.normalize(listOf(e.offerNo, e.date, e.buyer, e.supplier, e.particulars).joinToString(" ")))
        put("fingerprint", fingerprint)
        put("updated_at", now)
        put("is_deleted", 0)
        put("sync_pending", 1)
    }

    private fun tombstone(now: Long) = ContentValues().apply {
        put("is_deleted", 1); put("updated_at", now); put("sync_pending", 1)
    }

    private fun fingerprint(e: HistoryIndexEntry): String {
        val raw = listOf(e.uuid.orEmpty(), e.offerNo, e.date, e.buyer, e.supplier, e.particulars, e.jsonPath, e.pdfFileName.orEmpty())
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun Cursor.toBills(): List<Bill> {
        val out = ArrayList<Bill>(count)
        while (moveToNext()) {
            out += Bill(
                id = getString(0),
                billNo = getString(1),
                billDate = getStringOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                amount = if (isNull(3)) null else getDouble(3),
                orderCount = getInt(4)
            )
        }
        return out
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val BILL_SEPARATOR = "\u001f"
        const val HAS_BILL = """EXISTS (SELECT 1 FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
            WHERE ob.order_id = o.id AND ob.is_deleted = 0 AND b.is_deleted = 0)"""
        const val BILL_ORDER_COUNT = """(SELECT COUNT(*) FROM order_bills x JOIN orders xo ON xo.id = x.order_id
            WHERE x.bill_id = b.id AND x.is_deleted = 0 AND xo.is_deleted = 0) AS order_count"""
    }
}
