package com.example.otaapplication.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.otaapplication.OrderItem
import com.example.otaapplication.cloud.OrderTable
import org.json.JSONArray
import java.time.LocalDate

/** Bill columns and helpers shared by the order and payment repositories. */
internal object BillSql {
    /** What a bill's advices settle: paid + cash discount + goods return + rate difference + claim. */
    private const val LINE_SETTLED =
        "l.paid + COALESCE(l.cd_amount, 0) + COALESCE(l.goods_return, 0) + COALESCE(l.rate_diff, 0) + COALESCE(l.claim, 0)"

    private const val LIVE_LINES = """FROM advice_lines l JOIN payment_advices a ON a.id = l.advice_id
            WHERE l.bill_id = b.id AND l.is_deleted = 0 AND a.is_deleted = 0"""

    /** Select list for [toBills]; the bill table must be aliased `b`. */
    const val COLUMNS = """b.id, b.bill_no, b.bill_date, b.amount,
        (SELECT COUNT(*) FROM order_bills x JOIN orders xo ON xo.id = x.order_id
            WHERE x.bill_id = b.id AND x.is_deleted = 0 AND xo.is_deleted = 0) AS order_count,
        b.lr_no, b.cd_percent, b.cd_amount,
        (SELECT COALESCE(SUM($LINE_SETTLED), 0) $LIVE_LINES) AS settled,
        (SELECT COUNT(*) $LIVE_LINES) AS advice_count,
        b.items, b.buyer, b.supplier, b.transport"""

    /** Number of columns in [COLUMNS]; extra select columns start at this index. */
    const val COUNT = 14

    /** Settled amount as a SQL expression, for filtering by payment status. */
    const val SETTLED_EXPR = "(SELECT COALESCE(SUM($LINE_SETTLED), 0) $LIVE_LINES)"

    /** Settled by advices other than the one bound to the single `?` parameter. */
    const val SETTLED_EXCLUDING = "(SELECT COALESCE(SUM($LINE_SETTLED), 0) $LIVE_LINES AND a.id <> ?)"

    fun Cursor.toBills(): List<Bill> {
        val out = ArrayList<Bill>(count)
        while (moveToNext()) out += toBill()
        return out
    }

    fun Cursor.toBill() = Bill(
        id = getString(0),
        billNo = getString(1),
        billDate = stringOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        amount = doubleOrNull(3),
        orderCount = getInt(4),
        lrNo = stringOrNull(5).orEmpty(),
        cdPercent = doubleOrNull(6),
        cdAmount = doubleOrNull(7),
        settled = PaymentMath.round2(getDouble(8)),
        adviceCount = getInt(9),
        items = itemsFromText(stringOrNull(10)),
        buyer = stringOrNull(11).orEmpty(),
        supplier = stringOrNull(12).orEmpty(),
        transport = stringOrNull(13).orEmpty()
    )

    /** Bill item lines as stored in bills.items: the compact JSON arrays used for order items. */
    fun itemsToText(items: List<OrderItem>): String? =
        items.takeIf { it.isNotEmpty() }?.let { OrderTable.itemsToJson(it).toString() }

    fun itemsFromText(text: String?): List<OrderItem> =
        if (text.isNullOrBlank()) emptyList() else runCatching { OrderTable.itemsFromJson(JSONArray(text)) }.getOrDefault(emptyList())

    fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    fun billById(db: SQLiteDatabase, id: String): Bill? =
        db.rawQuery("SELECT $COLUMNS FROM bills b WHERE b.id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.toBill() else null }

    /** Tables the sync helpers may touch (table names can't be bound as SQL parameters). */
    fun syncTable(table: String) = when (table) {
        "orders", "bills", "order_bills", "payment_advices", "advice_lines" -> table
        else -> throw IllegalArgumentException(table)
    }

    fun localSyncMeta(db: SQLiteDatabase, table: String, ids: Collection<String>): Map<String, LocalSyncMeta> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, LocalSyncMeta>()
        ids.distinct().chunked(500).forEach { chunk ->
            db.rawQuery(
                "SELECT id, updated_at, sync_pending FROM ${syncTable(table)} WHERE id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) out[c.getString(0)] = LocalSyncMeta(c.getLong(1), c.getInt(2) != 0) }
        }
        return out
    }

    /** Clears the dirty flag of rows that were not edited again while the upload was running. */
    fun markSynced(db: SQLiteDatabase, table: String, rows: List<Pair<String, Long>>) {
        db.beginTransaction()
        try {
            for ((id, updatedAt) in rows) {
                db.update(syncTable(table), ContentValues().apply { put("sync_pending", 0) }, "id = ? AND updated_at = ?", arrayOf(id, updatedAt.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
