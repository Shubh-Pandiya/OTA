package com.example.otaapplication.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.otaapplication.data.BillSql.stringOrNull
import com.example.otaapplication.data.BillSql.toBills
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Order search index plus bills. The index mirrors the order history file and is kept in step
 * with [reconcile]; bill links reference orders by their id (the order uuid).
 * All calls hit the database; run them off the main thread.
 */
class OrderRepository(
    private val helper: OtaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Called after a local change that should be uploaded (new/edited/deleted order, bill links). */
    var onLocalChange: (() -> Unit)? = null

    /**
     * Brings the index in line with the history file. Returns true when anything changed.
     *
     * [remote] lists orders whose history entry was just written (or removed) by a cloud pull,
     * with the edit time they have in the cloud: those rows are stored as already synced, so a
     * pulled change is never uploaded back.
     */
    fun reconcile(entries: List<HistoryIndexEntry>, remote: Map<String, Long> = emptyMap()): Boolean {
        val changed = reconcileInternal(entries, remote)
        // Only local edits need an upload; a pull-only round has nothing new to push.
        if (changed && remote.isEmpty()) onLocalChange?.invoke()
        return changed
    }

    private fun reconcileInternal(entries: List<HistoryIndexEntry>, remote: Map<String, Long>): Boolean {
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
                val remoteAt = remote[e.id]
                val values = { orderValues(e, fingerprint, remoteAt ?: now, pending = remoteAt == null) }
                when {
                    row == null -> {
                        db.insertOrThrow("orders", null, values().apply { put("id", e.id); put("created_at", remoteAt ?: now) })
                        changed = true
                    }
                    row.fingerprint != fingerprint || row.deleted -> {
                        db.update("orders", values(), "id = ?", arrayOf(e.id))
                        changed = true
                    }
                    remoteAt != null -> {
                        // Same content as the cloud copy: just record that it is in sync.
                        db.update("orders", syncedAt(remoteAt).apply { put("seq", e.seq) }, "id = ?", arrayOf(e.id))
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
                val remoteAt = remote[id]
                // A deletion pulled from the cloud: the phone that deleted it uploads its own link changes.
                db.update("orders", if (remoteAt != null) tombstoneSynced(remoteAt) else tombstone(now), "id = ?", arrayOf(id))
                db.update("order_bills", if (remoteAt != null) tombstoneSynced(now) else tombstone(now), "order_id = ? AND is_deleted = 0", arrayOf(id))
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
            SELECT ${BillSql.COLUMNS}
            FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
            WHERE ob.order_id = ? AND ob.is_deleted = 0 AND b.is_deleted = 0
            ORDER BY ob.created_at, b.bill_no COLLATE NOCASE
            """.trimIndent(),
            arrayOf(orderId)
        ).use { it.toBills() }

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
            SELECT ${BillSql.COLUMNS}
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
     * [details], when given, update the bill's date, amount, LR number, cash discount and item lines.
     */
    fun attachBill(orderIds: Collection<String>, billNo: String, details: BillDetails? = null): Bill {
        val display = billNo.trim().replace(Regex("\\s+"), " ")
        require(display.isNotEmpty()) { "Bill number is required" }
        require(orderIds.isNotEmpty()) { "Select at least one order" }
        val key = SearchText.billKey(display)
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        try {
            val billId = db.rawQuery("SELECT id FROM bills WHERE bill_key = ?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)
                    // Uploaded again even when unchanged: the cloud may already have purged an
                    // old bill, and other phones need it to show the new link.
                    db.update("bills", ContentValues().apply {
                        put("is_deleted", 0)
                        details?.let { putDetails(it, onlyGiven = true) }
                        put("updated_at", now); put("sync_pending", 1)
                    }, "id = ?", arrayOf(id))
                    id
                } else null
            } ?: SyncIds.bill(key).also { id ->
                db.insertOrThrow("bills", null, ContentValues().apply {
                    put("id", id); put("bill_no", display); put("bill_key", key)
                    details?.let { putDetails(it) }
                    put("created_at", now); put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
                })
            }
            for (orderId in orderIds.distinct()) linkOrder(db, orderId, billId, now)
            db.setTransactionSuccessful()
            return BillSql.billById(db, billId)!!
        } finally {
            db.endTransaction()
            onLocalChange?.invoke()
        }
    }

    fun bill(billId: String): Bill? = BillSql.billById(helper.readableDatabase, billId)

    /**
     * Saves a bill entered from the Bills section: a new one when [billId] is null (its number
     * must not belong to another live bill), otherwise the bill's details. Its live order links
     * become exactly [orderIds]; a bill without orders must name its buyer and supplier, which
     * are kept on the bill so it can still be paid through an advice.
     */
    fun saveBill(billId: String?, billNo: String, details: BillDetails, orderIds: Collection<String>): Bill {
        val wanted = orderIds.distinct()
        require(wanted.isNotEmpty() || (details.buyer.isNotBlank() && details.supplier.isNotBlank())) {
            "Enter the buyer and supplier, or link an order"
        }
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        try {
            val id = if (billId == null) {
                val display = billNo.trim().replace(Regex("\\s+"), " ")
                require(display.isNotEmpty()) { "Bill number is required" }
                val key = SearchText.billKey(display)
                val existing = db.rawQuery("SELECT id, is_deleted FROM bills WHERE bill_key = ?", arrayOf(key)).use { c ->
                    if (c.moveToFirst()) c.getString(0) to (c.getInt(1) != 0) else null
                }
                require(existing == null || existing.second) { "Bill $display already exists" }
                val values = ContentValues().apply {
                    put("bill_no", display); put("bill_key", key); putDetails(details)
                    put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
                }
                if (existing != null) {
                    db.update("bills", values, "id = ?", arrayOf(existing.first))
                    existing.first
                } else {
                    SyncIds.bill(key).also { newId ->
                        values.put("id", newId); values.put("created_at", now)
                        db.insertOrThrow("bills", null, values)
                    }
                }
            } else {
                val updated = db.update("bills", ContentValues().apply {
                    putDetails(details); put("updated_at", now); put("sync_pending", 1)
                }, "id = ? AND is_deleted = 0", arrayOf(billId))
                require(updated > 0) { "This bill was removed" }
                billId
            }
            val live = db.rawQuery("SELECT order_id FROM order_bills WHERE bill_id = ? AND is_deleted = 0", arrayOf(id)).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            for (orderId in live - wanted.toSet()) {
                db.update("order_bills", tombstone(now), "order_id = ? AND bill_id = ? AND is_deleted = 0", arrayOf(orderId, id))
            }
            for (orderId in wanted.filterNot(live::contains)) linkOrder(db, orderId, id, now)
            db.setTransactionSuccessful()
            return BillSql.billById(db, id)!!
        } finally {
            db.endTransaction()
            onLocalChange?.invoke()
        }
    }

    /** Deletes a bill and its order links. A bill already on a payment advice must be taken off it first. */
    fun deleteBill(billId: String) {
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        try {
            val bill = BillSql.billById(db, billId) ?: return
            require(bill.adviceCount == 0) { "Bill ${bill.billNo} is on a payment advice. Remove it from the advice first." }
            db.update("order_bills", tombstone(now), "bill_id = ? AND is_deleted = 0", arrayOf(billId))
            db.update("bills", tombstone(now), "id = ? AND is_deleted = 0", arrayOf(billId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onLocalChange?.invoke()
    }

    private fun linkOrder(db: SQLiteDatabase, orderId: String, billId: String, now: Long) {
        val revived = db.update(
            "order_bills",
            ContentValues().apply { put("is_deleted", 0); put("updated_at", now); put("sync_pending", 1) },
            "order_id = ? AND bill_id = ? AND is_deleted = 1",
            arrayOf(orderId, billId)
        )
        if (revived == 0) {
            db.insertWithOnConflict("order_bills", null, ContentValues().apply {
                put("id", SyncIds.link(orderId, billId)); put("order_id", orderId); put("bill_id", billId)
                put("created_at", now); put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Cloud ids (uuids) of the given local orders, in the given order; orders without one are left out. */
    fun uuidsOf(orderIds: Collection<String>): List<String> {
        if (orderIds.isEmpty()) return emptyList()
        val found = HashMap<String, String>()
        orderIds.chunked(500).forEach { chunk ->
            helper.readableDatabase.rawQuery(
                "SELECT id, uuid FROM orders WHERE uuid IS NOT NULL AND id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getString(1) }
        }
        return orderIds.mapNotNull(found::get)
    }

    /** Orders a bill is attached to, for the bill's detail view. */
    fun ordersForBill(billId: String): List<OrderSummary> =
        helper.readableDatabase.rawQuery(
            """
            SELECT o.id, o.uuid, o.offer_no, o.order_date, o.buyer, o.supplier, o.json_path, o.pdf_file_name
            FROM order_bills ob JOIN orders o ON o.id = ob.order_id
            WHERE ob.bill_id = ? AND ob.is_deleted = 0 AND o.is_deleted = 0
            ORDER BY o.day_key, o.seq
            """.trimIndent(),
            arrayOf(billId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(OrderSummary(
                    c.getString(0), c.stringOrNull(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    c.getString(6), c.stringOrNull(7), emptyList()
                ))
            }
        }

    /** Transport companies used on earlier bills that contain [text], most used first. */
    fun transports(text: String, limit: Int = 6): List<String> {
        val tokens = SearchText.tokens(text)
        val where = StringBuilder("is_deleted = 0 AND transport <> ''")
        tokens.forEach { where.append(" AND lower(transport) LIKE ? ESCAPE '\\'") }
        return helper.readableDatabase.rawQuery(
            "SELECT transport, MAX(updated_at) FROM bills WHERE $where GROUP BY lower(trim(transport)) ORDER BY COUNT(*) DESC, MAX(updated_at) DESC LIMIT $limit",
            tokens.map(SearchText::containsPattern).toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0).trim()) } }
    }

    /** With [onlyGiven], blank details keep the bill's existing values. */
    private fun ContentValues.putDetails(d: BillDetails, onlyGiven: Boolean = false) {
        if (!onlyGiven || d.billDate != null) put("bill_date", d.billDate?.toString())
        if (!onlyGiven || d.amount != null) put("amount", d.amount)
        if (!onlyGiven || d.lrNo.isNotBlank()) put("lr_no", d.lrNo.trim())
        if (!onlyGiven || d.cdPercent != null) put("cd_percent", d.cdPercent)
        if (!onlyGiven || d.cdAmount != null) put("cd_amount", d.cdAmount)
        if (!onlyGiven || d.items.isNotEmpty()) put("items", BillSql.itemsToText(d.items))
        if (!onlyGiven || d.buyer.isNotBlank()) put("buyer", d.buyer.trim())
        if (!onlyGiven || d.supplier.isNotBlank()) put("supplier", d.supplier.trim())
        if (!onlyGiven || d.transport.isNotBlank()) put("transport", d.transport.trim())
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
        onLocalChange?.invoke()
    }

    /** Bills that name their own buyer and supplier (added from the Bills section) are kept. */
    private fun retireOrphanBills(db: SQLiteDatabase, now: Long) {
        db.update(
            "bills", tombstone(now),
            "is_deleted = 0 AND (buyer = '' OR supplier = '') AND NOT EXISTS (SELECT 1 FROM order_bills ob WHERE ob.bill_id = bills.id AND ob.is_deleted = 0)",
            null
        )
    }

    // ------------------------------------------------------------------ cloud sync

    /** Order numbers starting with [prefix], including deleted orders (numbers are never reused). */
    fun offerNosWithPrefix(prefix: String): List<String> =
        helper.readableDatabase.rawQuery(
            "SELECT offer_no FROM orders WHERE substr(offer_no, 1, ?) = ?",
            arrayOf(prefix.length.toString(), prefix)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Unuploaded orders, bills and bill links. Orders without a uuid (very old entries) never sync. */
    fun pendingCount(): Int = helper.readableDatabase.rawQuery(
        """
        SELECT (SELECT COUNT(*) FROM orders WHERE sync_pending = 1 AND $SYNCABLE_ORDER)
             + (SELECT COUNT(*) FROM bills WHERE sync_pending = 1)
             + (SELECT COUNT(*) FROM order_bills WHERE sync_pending = 1)
        """.trimIndent(), null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun localSyncMeta(table: String, ids: Collection<String>): Map<String, LocalSyncMeta> =
        BillSql.localSyncMeta(helper.readableDatabase, table, ids)

    /** Orders waiting to be uploaded; their content is read from the history file by the caller. */
    fun pendingOrders(limit: Int): List<PendingOrderRef> =
        helper.readableDatabase.rawQuery(
            "SELECT id, offer_no, order_date, updated_at, is_deleted FROM orders WHERE sync_pending = 1 AND $SYNCABLE_ORDER AND offer_no <> '' ORDER BY updated_at, id LIMIT $limit",
            null
        ).use(::orderRefs)

    /** Orders saved offline that are still waiting for their number from the cloud, oldest first. */
    fun unnumberedOrders(): List<PendingOrderRef> =
        helper.readableDatabase.rawQuery(
            "SELECT id, offer_no, order_date, updated_at, is_deleted FROM orders WHERE sync_pending = 1 AND $SYNCABLE_ORDER AND offer_no = '' ORDER BY day_key, seq, id",
            null
        ).use(::orderRefs)

    private fun orderRefs(c: android.database.Cursor): List<PendingOrderRef> =
        buildList { while (c.moveToNext()) add(PendingOrderRef(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0)) }

    fun pendingBills(limit: Int): List<PendingRow<BillValue>> =
        helper.readableDatabase.rawQuery(
            "SELECT id, bill_no, bill_date, amount, is_deleted, updated_at, lr_no, cd_percent, cd_amount, items, buyer, supplier, transport FROM bills WHERE sync_pending = 1 ORDER BY updated_at, id LIMIT $limit",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    PendingRow(
                        c.getString(0),
                        BillValue(
                            c.getString(1), c.getStringOrNull(2), if (c.isNull(3)) null else c.getDouble(3),
                            lrNo = c.getStringOrNull(6).orEmpty(),
                            cdPercent = if (c.isNull(7)) null else c.getDouble(7),
                            cdAmount = if (c.isNull(8)) null else c.getDouble(8),
                            items = BillSql.itemsFromText(c.getStringOrNull(9)),
                            buyer = c.getStringOrNull(10).orEmpty(),
                            supplier = c.getStringOrNull(11).orEmpty(),
                            transport = c.getStringOrNull(12).orEmpty()
                        ),
                        c.getInt(4) != 0,
                        c.getLong(5)
                    )
                )
            }
        }

    /** Links whose order and bill have been uploaded first, so other phones can apply the link. */
    fun pendingLinks(limit: Int): List<PendingRow<LinkValue>> =
        helper.readableDatabase.rawQuery(
            """
            SELECT ob.id, ob.order_id, ob.bill_id, ob.is_deleted, ob.updated_at
            FROM order_bills ob JOIN orders o ON o.id = ob.order_id JOIN bills b ON b.id = ob.bill_id
            WHERE ob.sync_pending = 1 AND o.sync_pending = 0 AND b.sync_pending = 0 AND length(o.id) = 36
            ORDER BY ob.updated_at, ob.id LIMIT $limit
            """.trimIndent(),
            null
        ).use { c ->
            buildList { while (c.moveToNext()) add(PendingRow(c.getString(0), LinkValue(c.getString(1), c.getString(2)), c.getInt(3) != 0, c.getLong(4))) }
        }

    /** Clears the dirty flag of rows that were not edited again while the upload was running. */
    fun markSynced(table: String, rows: List<Pair<String, Long>>) = BillSql.markSynced(helper.writableDatabase, table, rows)

    /**
     * Remembers an order that was deleted in the cloud before this phone ever saw it, so that
     * its number still counts as used.
     */
    fun recordRemoteTombstone(id: String, offerNo: String, date: String, clientUpdatedAt: Long) {
        helper.writableDatabase.insertWithOnConflict("orders", null, ContentValues().apply {
            put("id", id); put("uuid", id); put("offer_no", offerNo); put("order_date", date)
            put("day_key", SearchText.dayKey(date)); put("seq", -1); put("search_text", ""); put("fingerprint", "")
            put("created_at", clientUpdatedAt); put("updated_at", clientUpdatedAt); put("is_deleted", 1); put("sync_pending", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Applies pulled bills (last write wins). Returns how many local rows changed. */
    fun applyRemoteBills(rows: List<RemoteRow<BillValue>>): Int {
        val db = helper.writableDatabase
        val local = localSyncMeta("bills", rows.map { it.id })
        var changed = 0
        db.beginTransaction()
        try {
            for (row in rows) {
                if (SyncMerge.onPulled(local[row.id], row.deleted, row.clientUpdatedAt) != SyncMerge.Decision.APPLY_REMOTE) continue
                val values = ContentValues().apply {
                    put("bill_no", row.value.billNo); put("bill_key", SearchText.billKey(row.value.billNo))
                    put("bill_date", row.value.billDate); put("amount", row.value.amount)
                    put("lr_no", row.value.lrNo); put("cd_percent", row.value.cdPercent); put("cd_amount", row.value.cdAmount)
                    put("items", BillSql.itemsToText(row.value.items))
                    put("buyer", row.value.buyer); put("supplier", row.value.supplier); put("transport", row.value.transport)
                    put("updated_at", row.clientUpdatedAt); put("is_deleted", if (row.deleted) 1 else 0); put("sync_pending", 0)
                }
                if (db.update("bills", values, "id = ?", arrayOf(row.id)) == 0) {
                    values.put("id", row.id); values.put("created_at", row.clientUpdatedAt)
                    // Ignored if the same bill number already exists here under another id.
                    if (db.insertWithOnConflict("bills", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) continue
                }
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    /** Applies pulled bill links. Links to orders or bills this phone doesn't have are skipped. */
    fun applyRemoteLinks(rows: List<RemoteRow<LinkValue>>): Int {
        val db = helper.writableDatabase
        val local = localSyncMeta("order_bills", rows.map { it.id })
        var changed = 0
        db.beginTransaction()
        try {
            for (row in rows) {
                if (SyncMerge.onPulled(local[row.id], row.deleted, row.clientUpdatedAt) != SyncMerge.Decision.APPLY_REMOTE) continue
                val values = ContentValues().apply {
                    put("updated_at", row.clientUpdatedAt); put("is_deleted", if (row.deleted) 1 else 0); put("sync_pending", 0)
                }
                if (db.update("order_bills", values, "id = ?", arrayOf(row.id)) == 0) {
                    val known = db.rawQuery(
                        "SELECT (SELECT COUNT(*) FROM orders WHERE id = ?) + (SELECT COUNT(*) FROM bills WHERE id = ?)",
                        arrayOf(row.value.orderId, row.value.billId)
                    ).use { c -> c.moveToFirst() && c.getInt(0) == 2 }
                    if (!known) continue
                    values.put("id", row.id); values.put("order_id", row.value.orderId); values.put("bill_id", row.value.billId)
                    values.put("created_at", row.clientUpdatedAt)
                    if (db.insertWithOnConflict("order_bills", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) continue
                }
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
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

    private fun orderValues(e: HistoryIndexEntry, fingerprint: String, now: Long, pending: Boolean = true) = ContentValues().apply {
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
        put("search_text", SearchText.normalize(listOf(e.offerNo.ifBlank { "pending number" }, e.date, e.buyer, e.supplier, e.particulars).joinToString(" ")))
        put("fingerprint", fingerprint)
        put("updated_at", now)
        put("is_deleted", 0)
        put("sync_pending", if (pending) 1 else 0)
    }

    private fun tombstone(now: Long) = ContentValues().apply {
        put("is_deleted", 1); put("updated_at", now); put("sync_pending", 1)
    }

    private fun tombstoneSynced(at: Long) = ContentValues().apply {
        put("is_deleted", 1); put("updated_at", at); put("sync_pending", 0)
    }

    private fun syncedAt(at: Long) = ContentValues().apply { put("updated_at", at); put("sync_pending", 0) }

    private fun fingerprint(e: HistoryIndexEntry): String {
        val raw = listOf(e.uuid.orEmpty(), e.offerNo, e.date, e.buyer, e.supplier, e.particulars, e.jsonPath, e.pdfFileName.orEmpty(), e.content)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        /** Orders with a real uuid; entries from before uuids existed stay on this phone only. */
        const val SYNCABLE_ORDER = "length(id) = 36"
        const val BILL_SEPARATOR = "\u001f"
        const val HAS_BILL = """EXISTS (SELECT 1 FROM order_bills ob JOIN bills b ON b.id = ob.bill_id
            WHERE ob.order_id = o.id AND ob.is_deleted = 0 AND b.is_deleted = 0)"""
    }
}
