package com.ota.desktop.data

import com.example.otaapplication.cloud.OrderTable
import com.example.otaapplication.data.AdviceLineValue
import com.example.otaapplication.data.AdviceValue
import com.example.otaapplication.data.BillValue
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.LinkValue
import com.example.otaapplication.data.LocalSyncMeta
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.data.SyncMerge
import com.example.otaapplication.OrderFormData
import com.ota.desktop.db.Db
import com.ota.desktop.db.Json
import com.ota.desktop.db.date
import com.ota.desktop.db.dbl
import com.ota.desktop.db.id
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.strOrNull
import com.ota.desktop.db.update
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * How one synced order table maps between the cloud row ([OrderTable]) and the local table.
 * The cloud rules (docs/desktop-sync-contract.md) are applied generically here:
 * - pulled rows win unless the local row has a newer unpushed edit (last write wins);
 * - a cloud tombstone keeps the local content and only marks the row deleted;
 * - rows pulled for the first time are stored even when deleted (numbers stay used, history stays complete);
 * - server_updated_at is the cloud's updated_at as last seen; it is cleared on push, because the
 *   cloud gives the row a new updated_at that only the next pull reveals (archiving needs the exact value).
 */
class LocalSyncTable<T>(
    val cloud: OrderTable<T>,
    val local: String,
    private val writeColumns: List<String>,
    private val bind: (T) -> List<Any?>,
    private val readColumns: String,
    private val read: (ResultSet) -> T,
    /** SQL condition for pending rows that may be uploaded now (orders wait for their number). */
    private val pushable: String = "true"
) {
    fun pending(db: Db, limit: Int): List<PendingRow<T>> = db.read { c ->
        c.query("select id, $readColumns, deleted, client_updated_at from $local where sync_pending and ($pushable) order by client_updated_at, id limit $limit") {
            PendingRow(it.id(), read(it), it.getBoolean("deleted"), it.getLong("client_updated_at"))
        }
    }

    fun pendingCount(c: Connection): Int = c.one("select count(*) from $local where sync_pending") { it.getInt(1) } ?: 0

    /** Applies a pulled page; returns how many rows changed locally. */
    fun applyPulled(db: Db, rows: List<RemoteRow<T>>): Int = db.tx { c -> rows.count { applyOne(c, it) } }

    fun applyOne(c: Connection, r: RemoteRow<T>): Boolean {
        val mine = c.one("select client_updated_at, sync_pending, server_updated_at from $local where id = ?::uuid", r.id) {
            Triple(it.getLong(1), it.getBoolean(2), it.getString(3))
        }
        if (mine != null && !mine.second && mine.third == r.serverUpdatedAt) return false
        val decision = SyncMerge.onPulled(mine?.let { LocalSyncMeta(it.first, it.second) }, r.deleted, r.clientUpdatedAt)
        if (decision == SyncMerge.Decision.KEEP_LOCAL) return false
        if (r.deleted && mine != null) {
            return c.update(
                "update $local set deleted = true, client_updated_at = ?, server_updated_at = ?, sync_pending = false where id = ?::uuid",
                r.clientUpdatedAt, r.serverUpdatedAt, r.id
            ) > 0
        }
        val cols = writeColumns + listOf("deleted", "client_updated_at", "server_updated_at", "sync_pending")
        val values = bind(r.value) + listOf(r.deleted, r.clientUpdatedAt, r.serverUpdatedAt, false)
        val placeholders = cols.joinToString(", ") { "?" }
        val updates = cols.joinToString(", ") { "$it = excluded.$it" }
        return c.update(
            "insert into $local (id, ${cols.joinToString(", ")}) values (?::uuid, $placeholders) on conflict (id) do update set $updates",
            r.id, *values.toTypedArray()
        ) > 0
    }

    /** After a successful push: rows not edited again meanwhile are no longer pending. */
    fun markPushed(db: Db, rows: List<PendingRow<T>>) = db.tx { c ->
        rows.forEach {
            c.update("update $local set sync_pending = false, server_updated_at = null where id = ?::uuid and client_updated_at = ?", it.id, it.updatedAt)
        }
    }

    // ------------------------------------------------------------------ archiving

    /** Rows whose current cloud version is stored here and not yet marked archived: (id, cloud updated_at). */
    fun toArchive(db: Db, limit: Int): List<Pair<String, String>> = db.read { c ->
        c.query(
            "select id, server_updated_at from $local where not sync_pending and server_updated_at is not null " +
                "and archived_updated_at is distinct from server_updated_at order by id limit $limit"
        ) { it.id() to it.getString(2) }
    }

    fun markArchived(db: Db, rows: List<Pair<String, String>>) = db.tx { c ->
        rows.forEach { (id, at) -> c.update("update $local set archived_updated_at = ? where id = ?::uuid and server_updated_at = ?", at, id, at) }
    }

    companion object {
        private val dmy = DateTimeFormatter.ofPattern("dd-MM-yyyy")

        /** Order date text (dd-MM-yyyy, as printed) to a date, when it parses. */
        fun orderDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), dmy) }.getOrNull()

        private fun date(text: String?): LocalDate? = text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        val ORDERS = LocalSyncTable(
            OrderTable.ORDERS, "orders",
            listOf("order_no", "order_date", "order_day", "buyer", "supplier", "items", "remarks", "delivery_ready", "transport_to", "cash_payment", "less_disc", "side"),
            { d -> listOf(d.offerNo, d.date, orderDay(d.date), d.buyer, d.supplier, Json(OrderTable.itemsToJson(d.items).toString()), d.remarks, d.deliveryReady, d.transportTo, d.cashPayment, d.lessDisc, d.side) },
            "order_no, order_date, buyer, supplier, items, remarks, delivery_ready, transport_to, cash_payment, less_disc, side",
            ::readOrder,
            pushable = "order_no <> ''"
        )

        fun readOrder(rs: ResultSet) = OrderFormData(
            date = rs.str("order_date"), offerNo = rs.str("order_no"), buyer = rs.str("buyer"), supplier = rs.str("supplier"),
            items = OrderTable.itemsFromJson(org.json.JSONArray(rs.getString("items"))), remarks = rs.str("remarks"),
            deliveryReady = rs.str("delivery_ready"), transportTo = rs.str("transport_to"), cashPayment = rs.str("cash_payment"),
            lessDisc = rs.str("less_disc"), side = rs.str("side")
        )

        val BILLS = LocalSyncTable(
            OrderTable.BILLS, "bills",
            listOf("bill_no", "bill_key", "bill_date", "amount", "lr_no", "cd_percent", "cd_amount", "items", "buyer", "supplier", "transport"),
            { b ->
                listOf(
                    b.billNo, SearchText.billKey(b.billNo), date(b.billDate), b.amount, b.lrNo, b.cdPercent, b.cdAmount,
                    Json(OrderTable.itemsToJson(b.items).toString()), b.buyer, b.supplier, b.transport
                )
            },
            "bill_no, bill_date, amount, lr_no, cd_percent, cd_amount, items, buyer, supplier, transport",
            { rs ->
                BillValue(
                    rs.str("bill_no"), rs.date("bill_date")?.toString(), rs.dbl("amount"), rs.str("lr_no"), rs.dbl("cd_percent"), rs.dbl("cd_amount"),
                    readItems(rs), rs.str("buyer"), rs.str("supplier"), rs.str("transport")
                )
            }
        )

        fun readItems(rs: ResultSet, column: String = "items") = OrderTable.itemsFromJson(org.json.JSONArray(rs.getString(column) ?: "[]"))

        val LINKS = LocalSyncTable(
            OrderTable.LINKS, "order_bills",
            listOf("order_id", "bill_id"),
            { l -> listOf(java.util.UUID.fromString(l.orderId), java.util.UUID.fromString(l.billId)) },
            "order_id, bill_id",
            { rs -> LinkValue(rs.id("order_id"), rs.id("bill_id")) }
        )

        val ADVICES = LocalSyncTable(
            OrderTable.ADVICES, "payment_advices",
            listOf("advice_no", "pay_date", "buyer", "supplier", "mode", "reference", "balance", "balance_side", "remarks"),
            { a -> listOf(a.adviceNo, date(a.payDate), a.buyer, a.supplier, a.mode.ifBlank { "CASH" }, a.reference, a.balanceAmount, a.balanceSide, a.remarks) },
            "advice_no, pay_date, buyer, supplier, mode, reference, balance, balance_side, remarks",
            { rs ->
                AdviceValue(
                    rs.str("advice_no"), rs.date("pay_date")?.toString(), rs.str("buyer"), rs.str("supplier"), rs.str("mode"),
                    rs.str("reference"), rs.dbl("balance"), rs.strOrNull("balance_side"), rs.str("remarks")
                )
            }
        )

        val ADVICE_LINES = LocalSyncTable(
            OrderTable.ADVICE_LINES, "advice_lines",
            listOf("advice_id", "bill_id", "paid", "cd_percent", "cd_amount", "goods_return", "rate_diff", "claim", "claim_note"),
            { l ->
                val a = l.amounts
                listOf(
                    java.util.UUID.fromString(l.adviceId), java.util.UUID.fromString(l.billId), a.paid, a.cdPercent, a.cdAmount, a.goodsReturn, a.rateDiff,
                    a.claim, a.claimNote
                )
            },
            "advice_id, bill_id, paid, cd_percent, cd_amount, goods_return, rate_diff, claim, claim_note",
            { rs -> AdviceLineValue(rs.id("advice_id"), rs.id("bill_id"), lineAmounts(rs)) }
        )

        /** Amounts of an advice line read from `paid, cd_percent, cd_amount, goods_return, rate_diff, claim, claim_note`. */
        fun lineAmounts(rs: ResultSet, cdPercent: String = "cd_percent", cdAmount: String = "cd_amount") = LineAmounts(
            rs.dbl("paid") ?: 0.0, rs.dbl(cdPercent), rs.dbl(cdAmount), rs.dbl("goods_return"), rs.dbl("rate_diff"), rs.dbl("claim"), rs.str("claim_note")
        )

        /** Push order: parents before the rows that point at them. */
        val ALL: List<LocalSyncTable<*>> = listOf(ORDERS, BILLS, LINKS, ADVICES, ADVICE_LINES)
    }
}

