package com.ota.desktop.data

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.cloud.OrderTable
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.BillStatusFilter
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.data.SyncIds
import com.ota.desktop.db.Db
import com.ota.desktop.db.Json
import com.ota.desktop.db.date
import com.ota.desktop.db.dbl
import com.ota.desktop.db.id
import com.ota.desktop.db.nextEditTime
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.update
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

data class OrderRow(
    val id: String,
    val data: OrderFormData,
    val day: LocalDate?,
    val billNos: List<String>,
    val pending: Boolean
) {
    val orderNo: String get() = data.offerNo
}

data class OrderFilter(
    val text: String = "",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val buyer: String = "",
    val supplier: String = "",
    val staff: Int? = null,
    val bills: BillStatusFilter = BillStatusFilter.ALL
)

/** Bill select list and row mapping shared by the order and payment stores. Bills are aliased `b`. */
object BillSql {
    private const val LINE_SETTLED = "l.paid + coalesce(l.cd_amount, 0) + coalesce(l.goods_return, 0) + coalesce(l.rate_diff, 0)"
    private const val LIVE_LINES = "from advice_lines l join payment_advices a on a.id = l.advice_id where l.bill_id = b.id and not l.deleted and not a.deleted"

    const val COLUMNS = """b.id, b.bill_no, b.bill_date, b.amount, b.lr_no, b.cd_percent, b.cd_amount,
        (select count(*) from order_bills x join orders xo on xo.id = x.order_id where x.bill_id = b.id and not x.deleted and not xo.deleted) as order_count,
        (select coalesce(sum($LINE_SETTLED), 0) $LIVE_LINES) as settled,
        (select count(*) $LIVE_LINES) as advice_count"""

    /** Settled by advices other than the one bound to the single `?::text` parameter. */
    const val SETTLED_EXCLUDING = "(select coalesce(sum($LINE_SETTLED), 0) $LIVE_LINES and a.id::text <> ?) as settled_elsewhere"

    fun ResultSet.toBill() = Bill(
        id = id("id"), billNo = str("bill_no"), billDate = date("bill_date"), amount = dbl("amount"),
        orderCount = getInt("order_count"), lrNo = str("lr_no"), cdPercent = dbl("cd_percent"), cdAmount = dbl("cd_amount"),
        settled = PaymentMath.round2(dbl("settled") ?: 0.0), adviceCount = getInt("advice_count")
    )

    fun byId(c: Connection, id: String): Bill? = c.one("select $COLUMNS from bills b where b.id = ?::uuid", id) { it.toBill() }
}

/** Orders, their bills and bill links. */
class OrderStore(private val db: Db, private val numbers: Numbers, private val onChange: () -> Unit = {}) {

    fun list(filter: OrderFilter = OrderFilter(), limit: Int = 5000): List<OrderRow> {
        val where = StringBuilder("not o.deleted")
        val args = ArrayList<Any?>()
        for (token in SearchText.tokens(filter.text)) {
            where.append(" and lower(o.order_no || ' ' || o.order_date || ' ' || o.buyer || ' ' || o.supplier || ' ' || o.items::text || ' ' || o.remarks || ' ' || o.transport_to || ' ' || o.bill_nos) like ? escape '\\'")
            args += SearchText.containsPattern(token)
        }
        filter.from?.let { where.append(" and o.order_day >= ?"); args += it }
        filter.to?.let { where.append(" and o.order_day <= ?"); args += it }
        if (filter.buyer.isNotBlank()) { where.append(" and ota_party_key(o.buyer) = ?"); args += SearchText.partyKey(filter.buyer) }
        if (filter.supplier.isNotBlank()) { where.append(" and ota_party_key(o.supplier) = ?"); args += SearchText.partyKey(filter.supplier) }
        filter.staff?.let { where.append(" and o.order_no ~ '^[1-9]20[0-9]{6}[0-9]+$' and left(o.order_no, 1) = ?"); args += it.toString() }
        when (filter.bills) {
            BillStatusFilter.PENDING -> where.append(" and o.bill_nos = ''")
            BillStatusFilter.RECEIVED -> where.append(" and o.bill_nos <> ''")
            BillStatusFilter.ALL -> Unit
        }
        return db.read { c ->
            c.query(
                """
                with o as (
                  select o.*, coalesce((select string_agg(b.bill_no, E'\u001f' order by b.bill_no) from order_bills l join bills b on b.id = l.bill_id
                                        where l.order_id = o.id and not l.deleted and not b.deleted), '') as bill_nos
                  from orders o
                )
                select o.* from o where $where
                order by o.order_day desc nulls last, o.order_no desc limit $limit
                """.trimIndent(), *args.toTypedArray()
            ) { it.toOrderRow() }
        }
    }

    fun get(id: String): OrderRow? = db.read { c ->
        c.one(
            """select o.*, coalesce((select string_agg(b.bill_no, E'\u001f' order by b.bill_no) from order_bills l join bills b on b.id = l.bill_id
               where l.order_id = o.id and not l.deleted and not b.deleted), '') as bill_nos from orders o where o.id = ?::uuid and not o.deleted""",
            id
        ) { it.toOrderRow() }
    }

    /** Saves a new order with the next number for [staffCode]. */
    fun create(data: OrderFormData, staffCode: Int): OrderRow {
        val id = UUID.randomUUID().toString()
        numbers.assign(Numbers.Kind.ORDER, staffCode) { no ->
            db.tx { c ->
                val d = data.copy(offerNo = no)
                val now = System.currentTimeMillis()
                c.update(
                    "insert into orders (id, order_no, order_date, order_day, buyer, supplier, items, remarks, delivery_ready, transport_to, cash_payment, less_disc, side, " +
                        "deleted, client_updated_at, sync_pending) values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, true)",
                    id, d.offerNo, d.date, LocalSyncTable.orderDay(d.date), d.buyer, d.supplier, Json(OrderTable.itemsToJson(d.items).toString()),
                    d.remarks, d.deliveryReady, d.transportTo, d.cashPayment, d.lessDisc, d.side, now
                )
            }
        }
        onChange()
        return get(id)!!
    }

    /** Replaces an order's content; its number stays. */
    fun update(id: String, data: OrderFormData): OrderRow {
        db.tx { c ->
            val prev = c.one("select order_no, client_updated_at from orders where id = ?::uuid", id) { it.getString(1) to it.getLong(2) }
                ?: error("This order no longer exists")
            c.update(
                "update orders set order_date = ?, order_day = ?, buyer = ?, supplier = ?, items = ?, remarks = ?, delivery_ready = ?, transport_to = ?, " +
                    "cash_payment = ?, less_disc = ?, side = ?, deleted = false, client_updated_at = ?, sync_pending = true where id = ?::uuid",
                data.date, LocalSyncTable.orderDay(data.date), data.buyer, data.supplier, Json(OrderTable.itemsToJson(data.items).toString()),
                data.remarks, data.deliveryReady, data.transportTo, data.cashPayment, data.lessDisc, data.side, nextEditTime(prev.second), id
            )
        }
        onChange()
        return get(id)!!
    }

    fun delete(id: String) {
        db.tx { c ->
            val prev = c.one("select client_updated_at from orders where id = ?::uuid", id) { it.getLong(1) }
            c.update("update orders set deleted = true, client_updated_at = ?, sync_pending = true where id = ?::uuid and not deleted", nextEditTime(prev), id)
        }
        onChange()
    }

    // ------------------------------------------------------------------ bills

    fun billsForOrder(orderId: String): List<Bill> = db.read { c ->
        c.query(
            "select ${BillSql.COLUMNS} from bills b join order_bills l on l.bill_id = b.id where l.order_id = ?::uuid and not l.deleted and not b.deleted order by b.bill_no",
            orderId
        ) { with(BillSql) { it.toBill() } }
    }

    fun bill(billId: String): Bill? = db.read { c -> BillSql.byId(c, billId) }

    fun billByNumber(billNo: String): Bill? = db.read { c ->
        c.one("select ${BillSql.COLUMNS} from bills b where b.bill_key = ? and not b.deleted", SearchText.billKey(billNo)) { with(BillSql) { it.toBill() } }
    }

    fun ordersForBill(billId: String): List<OrderRow> = db.read { c ->
        c.query(
            """select o.*, '' as bill_nos from order_bills l join orders o on o.id = l.order_id
               where l.bill_id = ?::uuid and not l.deleted and not o.deleted order by o.order_day nulls last, o.order_no""",
            billId
        ) { it.toOrderRow() }
    }

    /**
     * Marks each order as "bill received" with [billNo]. A known bill number is reused (one bill can
     * cover many orders); [details], when given, update only the details that are filled in.
     */
    fun attachBill(orderIds: Collection<String>, billNo: String, details: BillDetails? = null): Bill {
        val display = billNo.trim().replace(Regex("\\s+"), " ")
        require(display.isNotEmpty()) { "Bill number is required" }
        require(orderIds.isNotEmpty()) { "Select at least one order" }
        val key = SearchText.billKey(display)
        val bill = db.tx { c ->
            val existing = c.one("select id, client_updated_at from bills where bill_key = ? order by deleted, id limit 1", key) { it.id() to it.getLong(2) }
            val billId = if (existing != null) {
                // Uploaded again even when unchanged: the cloud may have purged an old bill.
                c.update("update bills set deleted = false, client_updated_at = ?, sync_pending = true where id = ?::uuid", nextEditTime(existing.second), existing.first)
                details?.let { writeDetails(c, existing.first, it, onlyGiven = true) }
                existing.first
            } else {
                SyncIds.bill(key).also { id ->
                    c.update(
                        "insert into bills (id, bill_no, bill_key, deleted, client_updated_at, sync_pending) values (?::uuid, ?, ?, false, ?, true) " +
                            "on conflict (id) do update set deleted = false, bill_no = excluded.bill_no, bill_key = excluded.bill_key, " +
                            "client_updated_at = greatest(bills.client_updated_at + 1, excluded.client_updated_at), sync_pending = true",
                        id, display, key, System.currentTimeMillis()
                    )
                    details?.let { writeDetails(c, id, it, onlyGiven = false) }
                }
            }
            for (orderId in orderIds.distinct()) {
                val linkId = SyncIds.link(orderId, billId)
                val prev = c.one("select client_updated_at, deleted from order_bills where order_id = ?::uuid and bill_id = ?::uuid order by deleted limit 1", orderId, billId) {
                    it.getLong(1) to it.getBoolean(2)
                }
                when {
                    prev == null -> c.update(
                        "insert into order_bills (id, order_id, bill_id, deleted, client_updated_at, sync_pending) values (?::uuid, ?::uuid, ?::uuid, false, ?, true) on conflict (id) do nothing",
                        linkId, orderId, billId, System.currentTimeMillis()
                    )
                    prev.second -> c.update(
                        "update order_bills set deleted = false, client_updated_at = ?, sync_pending = true where order_id = ?::uuid and bill_id = ?::uuid and deleted",
                        nextEditTime(prev.first), orderId, billId
                    )
                }
            }
            BillSql.byId(c, billId)!!
        }
        onChange()
        return bill
    }

    /** Replaces a bill's date, amount, LR number and cash discount (the number stays). */
    fun updateBillDetails(billId: String, details: BillDetails): Bill? {
        val bill = db.tx { c ->
            val prev = c.one("select client_updated_at from bills where id = ?::uuid and not deleted", billId) { it.getLong(1) } ?: return@tx null
            writeDetails(c, billId, details, onlyGiven = false)
            c.update("update bills set client_updated_at = ?, sync_pending = true where id = ?::uuid", nextEditTime(prev), billId)
            BillSql.byId(c, billId)
        }
        if (bill != null) onChange()
        return bill
    }

    /** Removes a wrong bill link from an order. A bill left with no orders is retired too. */
    fun detachBill(orderId: String, billId: String) {
        db.tx { c ->
            val now = System.currentTimeMillis()
            c.update(
                "update order_bills set deleted = true, client_updated_at = greatest(client_updated_at + 1, ?), sync_pending = true " +
                    "where order_id = ?::uuid and bill_id = ?::uuid and not deleted", now, orderId, billId
            )
            c.update(
                "update bills b set deleted = true, client_updated_at = greatest(b.client_updated_at + 1, ?), sync_pending = true " +
                    "where not b.deleted and not exists (select 1 from order_bills l where l.bill_id = b.id and not l.deleted)", now
            )
        }
        onChange()
    }

    private fun writeDetails(c: Connection, billId: String, d: BillDetails, onlyGiven: Boolean) {
        val sets = ArrayList<String>()
        val args = ArrayList<Any?>()
        fun put(col: String, value: Any?, given: Boolean) {
            if (!onlyGiven || given) { sets += "$col = ?"; args += value }
        }
        put("bill_date", d.billDate, d.billDate != null)
        put("amount", d.amount, d.amount != null)
        put("lr_no", d.lrNo.trim(), d.lrNo.isNotBlank())
        put("cd_percent", d.cdPercent, d.cdPercent != null)
        put("cd_amount", d.cdAmount, d.cdAmount != null)
        if (sets.isEmpty()) return
        c.update("update bills set ${sets.joinToString(", ")} where id = ?::uuid", *args.toTypedArray(), billId)
    }

    fun pendingCount(): Int = db.read { c -> LocalSyncTable.ALL.sumOf { it.pendingCount(c) } }

    /** Gives an order a new number after the cloud reported its number as taken. */
    fun renumber(id: String, orderNo: String): Boolean = db.tx { c ->
        val prev = c.one("select client_updated_at from orders where id = ?::uuid", id) { it.getLong(1) } ?: return@tx false
        c.update("update orders set order_no = ?, client_updated_at = ?, sync_pending = true where id = ?::uuid", orderNo, nextEditTime(prev), id) > 0
    }.also { if (it) onChange() }

    private fun ResultSet.toOrderRow() = OrderRow(
        id = id("id"), data = LocalSyncTable.readOrder(this), day = date("order_day"),
        billNos = str("bill_nos").split('\u001f').filter { it.isNotEmpty() }, pending = getBoolean("sync_pending")
    )
}
