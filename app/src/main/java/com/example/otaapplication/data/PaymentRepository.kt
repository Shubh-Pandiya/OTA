package com.example.otaapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.otaapplication.data.BillSql.doubleOrNull
import com.example.otaapplication.data.BillSql.stringOrNull
import com.example.otaapplication.data.BillSql.toBill
import java.time.LocalDate
import java.util.UUID

/** A bill in the Bills list, with the parties of its orders. */
data class BillListItem(val bill: Bill, val buyer: String, val supplier: String)

/**
 * Payment advices and their lines (advice ↔ bill, many-to-many), stored locally and synced like
 * orders. All calls hit the database; run them off the main thread.
 */
class PaymentRepository(
    private val helper: OtaDatabase,
    /** Where the advice images are kept; null in tests that don't use images. */
    val images: AdviceImageFiles? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Called after a local change that should be uploaded. */
    var onLocalChange: (() -> Unit)? = null

    /**
     * Saves a new advice (with [newNumber]) or replaces an existing one. Lines removed in the
     * editor become tombstones so the removal reaches other phones.
     */
    fun save(draft: AdviceDraft, newNumber: String? = null): PaymentAdvice {
        require(draft.lines.isNotEmpty()) { "Add at least one bill" }
        require(draft.lines.map { it.first }.toSet().size == draft.lines.size) { "A bill can appear only once on an advice" }
        val db = helper.writableDatabase
        val now = clock()
        val id: String
        var removedImages = emptyList<String>()
        db.beginTransaction()
        try {
            val existingNo = draft.id?.let { adviceId ->
                db.rawQuery("SELECT advice_no FROM payment_advices WHERE id = ?", arrayOf(adviceId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
            id = if (existingNo != null) draft.id!! else UUID.randomUUID().toString()
            val adviceNo = existingNo ?: requireNotNull(newNumber) { "New advice needs a number" }
            val oldImages = if (existingNo != null) imagesOf(db, id) else emptyList()
            // Images picked in the editor wait in staging until the advice has an id.
            val newImages = draft.images.distinct().filter { images?.commit(id, it) ?: true }
            val values = ContentValues().apply {
                put("pay_date", draft.payDate.toString())
                put("buyer", SearchText.firstLine(draft.buyer))
                put("supplier", SearchText.firstLine(draft.supplier))
                put("mode", draft.mode.name)
                put("reference", draft.reference.trim())
                put("balance_amount", draft.balanceAmount?.let(PaymentMath::round2))
                put("balance_side", draft.balanceAmount?.let { draft.balanceSide?.name })
                put("remarks", draft.remarks.trim())
                put("search_text", searchText(adviceNo, draft))
                put("images", newImages.joinToString(","))
                put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
            }
            if (existingNo != null) {
                db.update("payment_advices", values, "id = ?", arrayOf(id))
            } else {
                values.put("id", id); values.put("advice_no", adviceNo); values.put("created_at", now)
                db.insertOrThrow("payment_advices", null, values)
            }

            val live = HashSet<String>()
            db.rawQuery("SELECT bill_id FROM advice_lines WHERE advice_id = ? AND is_deleted = 0", arrayOf(id)).use { c ->
                while (c.moveToNext()) live += c.getString(0)
            }
            for ((billId, amounts) in draft.lines) {
                val known = db.rawQuery("SELECT 1 FROM bills WHERE id = ? AND is_deleted = 0", arrayOf(billId)).use { it.moveToFirst() }
                require(known) { "A bill on this advice no longer exists" }
                val lineId = SyncIds.adviceLine(id, billId)
                val line = ContentValues().apply {
                    put("paid", PaymentMath.round2(amounts.paid))
                    put("cd_percent", amounts.cdPercent)
                    put("cd_amount", amounts.cdAmount?.let(PaymentMath::round2))
                    put("goods_return", amounts.goodsReturn?.let(PaymentMath::round2))
                    put("rate_diff", amounts.rateDiff?.let(PaymentMath::round2))
                    put("claim", amounts.claim?.let(PaymentMath::round2))
                    put("claim_note", amounts.claimNote.trim())
                    put("updated_at", now); put("is_deleted", 0); put("sync_pending", 1)
                }
                if (db.update("advice_lines", line, "id = ?", arrayOf(lineId)) == 0) {
                    line.put("id", lineId); line.put("advice_id", id); line.put("bill_id", billId); line.put("created_at", now)
                    db.insertOrThrow("advice_lines", null, line)
                }
                live -= billId
                // Re-upload the bill too: the cloud may have purged it since it was last sent.
                db.execSQL("UPDATE bills SET sync_pending = 1 WHERE id = ?", arrayOf(billId))
            }
            for (removed in live) {
                db.update("advice_lines", BillSql.tombstone(now), "id = ?", arrayOf(SyncIds.adviceLine(id, removed)))
            }
            removedImages = (oldImages - newImages.toSet()).map { AdviceImageFiles.path(id, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        removedImages.forEach { images?.delete(it) }
        onLocalChange?.invoke()
        return advice(id)!!
    }

    fun delete(adviceId: String): Boolean {
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        val deleted: Boolean
        val paths: List<String>
        try {
            paths = imagesOf(db, adviceId).map { AdviceImageFiles.path(adviceId, it) }
            deleted = db.update("payment_advices", BillSql.tombstone(now), "id = ? AND is_deleted = 0", arrayOf(adviceId)) > 0
            db.update("advice_lines", BillSql.tombstone(now), "advice_id = ? AND is_deleted = 0", arrayOf(adviceId))
            if (deleted) db.execSQL("UPDATE payment_advices SET images = '' WHERE id = ?", arrayOf(adviceId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deleted) paths.forEach { images?.delete(it) }
        if (deleted) onLocalChange?.invoke()
        return deleted
    }

    fun advice(adviceId: String): PaymentAdvice? {
        val db = helper.readableDatabase
        val lines = db.rawQuery(
            """
            SELECT ${BillSql.COLUMNS}, l.id, $LINE_AMOUNTS
            FROM advice_lines l JOIN bills b ON b.id = l.bill_id
            WHERE l.advice_id = ? AND l.is_deleted = 0
            ORDER BY b.bill_date IS NULL, b.bill_date, b.bill_no
            """.trimIndent(),
            arrayOf(adviceId)
        ).use { c ->
            buildList { while (c.moveToNext()) add(AdviceLine(c.getString(11), c.toBill(), c.lineAmounts(12))) }
        }
        return db.rawQuery(
            "SELECT advice_no, pay_date, buyer, supplier, mode, reference, balance_amount, balance_side, remarks, images FROM payment_advices WHERE id = ? AND is_deleted = 0",
            arrayOf(adviceId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            PaymentAdvice(
                id = adviceId,
                adviceNo = c.getString(0),
                payDate = c.stringOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
                buyer = c.getString(2),
                supplier = c.getString(3),
                mode = PaymentMode.parse(c.getString(4)),
                reference = c.getString(5),
                balanceAmount = c.doubleOrNull(6),
                balanceSide = BalanceSide.parse(c.stringOrNull(7)),
                remarks = c.getString(8),
                lines = lines,
                images = splitImages(c.getString(9))
            )
        }
    }

    /** Advices matching every word of [text] (number, parties, cheque/txn, remarks, bill numbers), newest first. */
    fun search(text: String): List<AdviceSummary> {
        val where = StringBuilder("a.is_deleted = 0")
        val args = ArrayList<String>()
        for (token in SearchText.tokens(text)) {
            where.append(
                """ AND (a.search_text LIKE ? ESCAPE '\' OR EXISTS (SELECT 1 FROM advice_lines sl JOIN bills sb ON sb.id = sl.bill_id
                    WHERE sl.advice_id = a.id AND sl.is_deleted = 0 AND (lower(sb.bill_no) LIKE ? ESCAPE '\' OR lower(sl.claim_note) LIKE ? ESCAPE '\')))"""
            )
            val pattern = SearchText.containsPattern(token)
            repeat(3) { args += pattern }
        }
        return helper.readableDatabase.rawQuery(
            """
            SELECT a.id, a.advice_no, a.pay_date, a.buyer, a.supplier, a.mode, a.reference,
                   (SELECT COALESCE(SUM(l.paid), 0) FROM advice_lines l WHERE l.advice_id = a.id AND l.is_deleted = 0),
                   (SELECT group_concat(b.bill_no, char(31)) FROM advice_lines l JOIN bills b ON b.id = l.bill_id
                    WHERE l.advice_id = a.id AND l.is_deleted = 0)
            FROM payment_advices a
            WHERE $where
            ORDER BY a.pay_date DESC, a.advice_no DESC
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(AdviceSummary(
                    id = c.getString(0),
                    adviceNo = c.getString(1),
                    payDate = c.stringOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    buyer = c.getString(3),
                    supplier = c.getString(4),
                    mode = PaymentMode.parse(c.getString(5)),
                    reference = c.getString(6),
                    paid = PaymentMath.round2(c.getDouble(7)),
                    billNos = c.stringOrNull(8)?.split(SEPARATOR)?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()
                ))
            }
        }
    }

    /** The advices that pay (part of) a bill, oldest first. */
    fun advicesForBill(billId: String): List<BillPayment> =
        helper.readableDatabase.rawQuery(
            """
            SELECT a.id, a.advice_no, a.pay_date, $LINE_AMOUNTS
            FROM advice_lines l JOIN payment_advices a ON a.id = l.advice_id
            WHERE l.bill_id = ? AND l.is_deleted = 0 AND a.is_deleted = 0
            ORDER BY a.pay_date, a.advice_no
            """.trimIndent(),
            arrayOf(billId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BillPayment(
                    c.getString(0), c.getString(1), c.stringOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, c.lineAmounts(3)
                ))
            }
        }

    /**
     * Bills that can go on an advice from [buyer] to [supplier]: bills with at least one order
     * for that pair (matched by name). A bill whose orders span several pairs is still offered,
     * flagged as mixed; a bill without orders has no parties and is never offered.
     * [excludeAdviceId] is the advice being edited: its own lines don't count as settled.
     */
    fun pairBills(buyer: String, supplier: String, excludeAdviceId: String? = null): List<PairBill> {
        val buyerKey = SearchText.partyKey(buyer)
        val supplierKey = SearchText.partyKey(supplier)
        if (buyerKey.isEmpty() || supplierKey.isEmpty()) return emptyList()
        val db = helper.readableDatabase
        val pairs = HashMap<String, MutableSet<Pair<String, String>>>()
        db.rawQuery(
            """
            SELECT ob.bill_id, o.buyer, o.supplier
            FROM order_bills ob JOIN orders o ON o.id = ob.order_id JOIN bills b ON b.id = ob.bill_id
            WHERE ob.is_deleted = 0 AND o.is_deleted = 0 AND b.is_deleted = 0
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                pairs.getOrPut(c.getString(0)) { HashSet() } += SearchText.partyKey(c.getString(1)) to SearchText.partyKey(c.getString(2))
            }
        }
        val eligible = pairs.filterValues { (buyerKey to supplierKey) in it }
        if (eligible.isEmpty()) return emptyList()
        val out = ArrayList<PairBill>()
        eligible.keys.chunked(400).forEach { chunk ->
            db.rawQuery(
                "SELECT ${BillSql.COLUMNS}, ${BillSql.SETTLED_EXCLUDING} FROM bills b WHERE b.id IN (${chunk.joinToString(",") { "?" }})",
                arrayOf(excludeAdviceId.orEmpty()) + chunk
            ).use { c ->
                while (c.moveToNext()) {
                    val bill = c.toBill()
                    out += PairBill(bill, PaymentMath.round2(c.getDouble(11)), mixedParties = eligible.getValue(bill.id).size > 1)
                }
            }
        }
        return out.sortedWith(compareBy<PairBill>({ it.bill.billDate == null }, { it.bill.billDate }, { it.bill.billNo }))
    }

    /** Bills for the Bills list: matches bill/LR number, buyer, supplier or order number; newest first. */
    fun bills(query: BillQuery): List<BillListItem> {
        val where = StringBuilder("b.is_deleted = 0")
        val args = ArrayList<String>()
        for (token in SearchText.tokens(query.text)) {
            where.append(
                """ AND (lower(b.bill_no) LIKE ? ESCAPE '\' OR lower(b.lr_no) LIKE ? ESCAPE '\' OR EXISTS (
                    SELECT 1 FROM order_bills sob JOIN orders so ON so.id = sob.order_id
                    WHERE sob.bill_id = b.id AND sob.is_deleted = 0 AND so.is_deleted = 0 AND so.search_text LIKE ? ESCAPE '\'))"""
            )
            val pattern = SearchText.containsPattern(token)
            repeat(3) { args += pattern }
        }
        val party = """FROM order_bills pob JOIN orders po ON po.id = pob.order_id
            WHERE pob.bill_id = b.id AND pob.is_deleted = 0 AND po.is_deleted = 0 ORDER BY po.day_key, po.seq LIMIT 1"""
        return helper.readableDatabase.rawQuery(
            """
            SELECT ${BillSql.COLUMNS}, (SELECT po.buyer $party), (SELECT po.supplier $party)
            FROM bills b
            WHERE $where
            ORDER BY b.bill_date IS NULL, b.bill_date DESC, b.updated_at DESC
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val bill = c.toBill()
                    if (query.status == null || bill.status == query.status) {
                        add(BillListItem(bill, SearchText.firstLine(c.stringOrNull(11).orEmpty()), SearchText.firstLine(c.stringOrNull(12).orEmpty())))
                    }
                }
            }
        }
    }

    /** All advice numbers starting with [prefix], deleted ones included (they stay used). */
    fun adviceNosWithPrefix(prefix: String): List<String> =
        helper.readableDatabase.rawQuery(
            "SELECT advice_no FROM payment_advices WHERE advice_no LIKE ? ESCAPE '\\'",
            arrayOf(SearchText.prefixPattern(prefix))
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Gives an advice a new number after the cloud reported its number as taken. */
    fun renumber(adviceId: String, adviceNo: String): Boolean {
        val db = helper.writableDatabase
        val draftText = db.rawQuery(
            "SELECT buyer, supplier, mode, reference, remarks FROM payment_advices WHERE id = ?", arrayOf(adviceId)
        ).use { c ->
            if (!c.moveToFirst()) return false
            listOf(adviceNo, c.getString(0), c.getString(1), PaymentMode.parse(c.getString(2)).label, c.getString(3), c.getString(4))
        }
        return db.update("payment_advices", ContentValues().apply {
            put("advice_no", adviceNo)
            put("search_text", SearchText.normalize(draftText.joinToString(" ")))
            put("updated_at", clock()); put("sync_pending", 1)
        }, "id = ?", arrayOf(adviceId)) > 0
    }

    // ---- Sync ----

    fun pendingCount(): Int = helper.readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM payment_advices WHERE sync_pending = 1) + (SELECT COUNT(*) FROM advice_lines WHERE sync_pending = 1)",
        null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun pendingAdvices(limit: Int): List<PendingRow<AdviceValue>> =
        helper.readableDatabase.rawQuery(
            """
            SELECT id, advice_no, pay_date, buyer, supplier, mode, reference, balance_amount, balance_side, remarks, is_deleted, updated_at
            FROM payment_advices WHERE sync_pending = 1 ORDER BY updated_at, id LIMIT $limit
            """.trimIndent(),
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(PendingRow(
                    c.getString(0),
                    AdviceValue(
                        adviceNo = c.getString(1), payDate = c.stringOrNull(2), buyer = c.getString(3), supplier = c.getString(4),
                        mode = c.getString(5), reference = c.getString(6), balanceAmount = c.doubleOrNull(7),
                        balanceSide = c.stringOrNull(8), remarks = c.getString(9)
                    ),
                    c.getInt(10) != 0,
                    c.getLong(11)
                ))
            }
        }

    /** Lines whose advice and bill are already in the cloud. */
    fun pendingLines(limit: Int): List<PendingRow<AdviceLineValue>> =
        helper.readableDatabase.rawQuery(
            """
            SELECT l.id, l.advice_id, l.bill_id, $LINE_AMOUNTS, l.is_deleted, l.updated_at
            FROM advice_lines l JOIN payment_advices a ON a.id = l.advice_id JOIN bills b ON b.id = l.bill_id
            WHERE l.sync_pending = 1 AND a.sync_pending = 0 AND b.sync_pending = 0
            ORDER BY l.updated_at, l.id LIMIT $limit
            """.trimIndent(),
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(PendingRow(
                    c.getString(0), AdviceLineValue(c.getString(1), c.getString(2), c.lineAmounts(3)), c.getInt(10) != 0, c.getLong(11)
                ))
            }
        }

    fun markSynced(table: String, rows: List<Pair<String, Long>>) = BillSql.markSynced(helper.writableDatabase, table, rows)

    fun applyRemoteAdvices(rows: List<RemoteRow<AdviceValue>>): Int {
        val db = helper.writableDatabase
        val local = BillSql.localSyncMeta(db, ADVICES, rows.map { it.id })
        var changed = 0
        val dropped = ArrayList<String>()
        db.beginTransaction()
        try {
            for (row in rows) {
                val meta = local[row.id]
                val v = row.value
                if (meta == null && row.deleted) {
                    // Deleted elsewhere before this phone saw it: keep the number as used.
                    db.insertWithOnConflict(ADVICES, null, ContentValues().apply {
                        put("id", row.id); put("advice_no", v.adviceNo); put("pay_date", v.payDate)
                        put("created_at", row.clientUpdatedAt); put("updated_at", row.clientUpdatedAt); put("is_deleted", 1); put("sync_pending", 0)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    continue
                }
                if (SyncMerge.onPulled(meta, row.deleted, row.clientUpdatedAt) != SyncMerge.Decision.APPLY_REMOTE) continue
                // Images never leave the phone; an advice deleted elsewhere takes its local ones along.
                if (row.deleted) dropped += imagesOf(db, row.id).map { AdviceImageFiles.path(row.id, it) }
                val values = ContentValues().apply {
                    put("advice_no", v.adviceNo); put("pay_date", v.payDate)
                    put("updated_at", row.clientUpdatedAt); put("is_deleted", if (row.deleted) 1 else 0); put("sync_pending", 0)
                    if (row.deleted) put("images", "")
                    // A tombstone carries only the number and date; keep the local content.
                    if (!row.deleted) {
                        put("buyer", v.buyer); put("supplier", v.supplier); put("mode", PaymentMode.parse(v.mode).name)
                        put("reference", v.reference); put("balance_amount", v.balanceAmount)
                        put("balance_side", BalanceSide.parse(v.balanceSide)?.name); put("remarks", v.remarks)
                        put("search_text", SearchText.normalize(listOf(v.adviceNo, v.buyer, v.supplier, PaymentMode.parse(v.mode).label, v.reference, v.remarks).joinToString(" ")))
                    }
                }
                if (db.update(ADVICES, values, "id = ?", arrayOf(row.id)) == 0) {
                    values.put("id", row.id); values.put("created_at", row.clientUpdatedAt)
                    db.insertWithOnConflict(ADVICES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        dropped.forEach { images?.delete(it) }
        return changed
    }

    /** Applies pulled advice lines; lines whose advice or bill this phone doesn't have are skipped. */
    fun applyRemoteLines(rows: List<RemoteRow<AdviceLineValue>>): Int {
        val db = helper.writableDatabase
        val local = BillSql.localSyncMeta(db, LINES, rows.map { it.id })
        var changed = 0
        db.beginTransaction()
        try {
            for (row in rows) {
                if (SyncMerge.onPulled(local[row.id], row.deleted, row.clientUpdatedAt) != SyncMerge.Decision.APPLY_REMOTE) continue
                val a = row.value.amounts
                val values = ContentValues().apply {
                    put("paid", a.paid); put("cd_percent", a.cdPercent); put("cd_amount", a.cdAmount)
                    put("goods_return", a.goodsReturn); put("rate_diff", a.rateDiff)
                    put("claim", a.claim); put("claim_note", a.claimNote)
                    put("updated_at", row.clientUpdatedAt); put("is_deleted", if (row.deleted) 1 else 0); put("sync_pending", 0)
                }
                if (db.update(LINES, values, "id = ?", arrayOf(row.id)) == 0) {
                    val known = db.rawQuery(
                        "SELECT (SELECT COUNT(*) FROM payment_advices WHERE id = ?) + (SELECT COUNT(*) FROM bills WHERE id = ?)",
                        arrayOf(row.value.adviceId, row.value.billId)
                    ).use { c -> c.moveToFirst() && c.getInt(0) == 2 }
                    if (!known) continue
                    values.put("id", row.id); values.put("advice_id", row.value.adviceId); values.put("bill_id", row.value.billId)
                    values.put("created_at", row.clientUpdatedAt)
                    if (db.insertWithOnConflict(LINES, null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) continue
                }
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    private fun searchText(adviceNo: String, d: AdviceDraft) = SearchText.normalize(
        listOf(adviceNo, SearchText.firstLine(d.buyer), SearchText.firstLine(d.supplier), d.mode.label, d.reference, d.remarks).joinToString(" ")
    )

    // ---- Advice images (kept on this phone only, for the advice PDF) ----

    private fun imagesOf(db: SQLiteDatabase, adviceId: String): List<String> =
        db.rawQuery("SELECT images FROM payment_advices WHERE id = ?", arrayOf(adviceId))
            .use { c -> if (c.moveToFirst()) splitImages(c.getString(0)) else emptyList() }

    private fun splitImages(text: String?): List<String> = text?.split(",")?.filter { it.isNotBlank() }.orEmpty()

    private fun Cursor.lineAmounts(start: Int) = LineAmounts(
        paid = getDouble(start),
        cdPercent = doubleOrNull(start + 1),
        cdAmount = doubleOrNull(start + 2),
        goodsReturn = doubleOrNull(start + 3),
        rateDiff = doubleOrNull(start + 4),
        claim = doubleOrNull(start + 5),
        claimNote = stringOrNull(start + 6).orEmpty()
    )

    companion object {
        const val ADVICES = "payment_advices"
        const val LINES = "advice_lines"

        /** Line amount columns read by [lineAmounts]; the line table must be aliased `l`. */
        private const val LINE_AMOUNTS = "l.paid, l.cd_percent, l.cd_amount, l.goods_return, l.rate_diff, l.claim, l.claim_note"
        private const val SEPARATOR = "\u001f"

        /** Advice numbers are picked and saved under this lock (see AdviceNumberAllocator). */
        val numberLock = Any()

        @Volatile
        private var instance: PaymentRepository? = null

        fun get(context: Context): PaymentRepository =
            instance ?: synchronized(this) {
                instance ?: PaymentRepository(OtaDatabase.get(context), AdviceImageFiles.forContext(context)).also { instance = it }
            }
    }
}
