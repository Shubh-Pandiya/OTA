package com.ota.desktop.data

import com.ota.desktop.db.Db
import com.ota.desktop.db.dbl
import com.ota.desktop.db.query

/**
 * What the cloud keeps however old it is (supabase/migrations/20261122000000_keep_open_items.sql):
 * open bills (no amount, or amount - settled >= 0.005), unbilled orders, and everything linked to
 * them: an order with all its bills, a bill with its orders and advices, an advice with all its bills.
 * The laptop uses it once to upload open items that earlier date-based purges removed from the cloud.
 */
object OpenItems {
    private const val EPSILON = 0.005

    data class Link(val id: String, val parent: String, val billId: String)

    /** Ids to keep, per local table name. */
    data class Keep(val orders: Set<String>, val bills: Set<String>, val links: Set<String>, val advices: Set<String>, val lines: Set<String>) {
        fun of(local: String): Set<String> = when (local) {
            "orders" -> orders
            "bills" -> bills
            "order_bills" -> links
            "payment_advices" -> advices
            "advice_lines" -> lines
            else -> emptySet()
        }
    }

    /**
     * [bills]: id to amount; [settled]: bill id to what its advices settle; [links]: order links
     * (parent = order id); [lines]: advice lines (parent = advice id).
     */
    fun keep(orders: Set<String>, bills: Map<String, Double?>, settled: Map<String, Double>, links: List<Link>, advices: Set<String>, lines: List<Link>): Keep {
        val billsOfOrder = links.groupBy({ it.parent }, { it.billId })
        val ordersOfBill = links.groupBy({ it.billId }, { it.parent })
        val billsOfAdvice = lines.groupBy({ it.parent }, { it.billId })
        val advicesOfBill = lines.groupBy({ it.billId }, { it.parent })

        val keptOrders = HashSet<String>()
        val keptBills = HashSet<String>()
        val keptAdvices = HashSet<String>()
        val queue = ArrayDeque<Pair<Char, String>>()
        fun add(kind: Char, id: String) {
            val set = when (kind) { 'o' -> keptOrders; 'b' -> keptBills; else -> keptAdvices }
            if (set.add(id)) queue.addLast(kind to id)
        }
        bills.forEach { (id, amount) -> if (amount == null || amount - (settled[id] ?: 0.0) >= EPSILON) add('b', id) }
        orders.forEach { if (billsOfOrder[it].isNullOrEmpty()) add('o', it) }
        while (queue.isNotEmpty()) {
            val (kind, id) = queue.removeFirst()
            when (kind) {
                'o' -> billsOfOrder[id].orEmpty().forEach { add('b', it) }
                'a' -> billsOfAdvice[id].orEmpty().forEach { add('b', it) }
                else -> {
                    ordersOfBill[id].orEmpty().forEach { add('o', it) }
                    advicesOfBill[id].orEmpty().forEach { add('a', it) }
                }
            }
        }
        return Keep(
            orders = keptOrders.filterTo(HashSet()) { it in orders },
            bills = keptBills.filterTo(HashSet()) { it in bills },
            links = links.filter { it.parent in keptOrders || it.billId in keptBills }.mapTo(HashSet()) { it.id },
            advices = keptAdvices.filterTo(HashSet()) { it in advices },
            lines = lines.filter { it.parent in keptAdvices || it.billId in keptBills }.mapTo(HashSet()) { it.id }
        )
    }

    /** [keep] over the laptop's data set in use (numbered rows only; unnumbered ones upload by themselves). */
    fun keep(db: Db): Keep = db.read { c ->
        val orders = c.query("select id from orders where not deleted and order_no <> ''") { it.getString(1) }.toHashSet()
        val bills = c.query("select id, amount from bills where not deleted") { it.getString(1) to it.dbl("amount") }.toMap()
        val settled = c.query(
            "select l.bill_id, sum(l.paid + coalesce(l.cd_amount, 0) + coalesce(l.goods_return, 0) + coalesce(l.rate_diff, 0) + coalesce(l.claim, 0)) " +
                "from advice_lines l join payment_advices a on a.id = l.advice_id where not l.deleted and not a.deleted group by l.bill_id"
        ) { it.getString(1) to it.getDouble(2) }.toMap()
        val links = c.query("select id, order_id, bill_id from order_bills where not deleted") { Link(it.getString(1), it.getString(2), it.getString(3)) }
        val advices = c.query("select id from payment_advices where not deleted and advice_no <> ''") { it.getString(1) }.toHashSet()
        val lines = c.query("select id, advice_id, bill_id from advice_lines where not deleted") { Link(it.getString(1), it.getString(2), it.getString(3)) }
        keep(orders, bills, settled, links, advices, lines)
    }
}
