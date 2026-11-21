package com.example.otaapplication.data

import java.time.LocalDate

/**
 * Everything on the books with one buyer or supplier: its orders, bills and payment advices, each
 * with its status, plus what is still due. Names are matched forgivingly ([PartyMatch]), since
 * orders carry the names as typed. Kept Android-free: the desktop app compiles this file as-is.
 */
object PartyBook {
    /** An order as listed: [date] as entered, [day] (yyyymmdd) for sorting. */
    data class Order(val id: String, val orderNo: String, val date: String, val day: Int, val buyer: String, val supplier: String, val billNos: List<String>)

    /** A bill with the parties of its orders (or as typed on it). */
    data class BillItem(val bill: Bill, val buyer: String, val supplier: String)

    data class Advice(val id: String, val adviceNo: String, val payDate: LocalDate?, val buyer: String, val supplier: String, val paid: Double, val billNos: List<String>)

    enum class OrderState(val label: String) {
        UNBILLED("Not billed"), BILL_DUE("Bill due"), PARTLY_PAID("Partly paid"), PAID("Paid"),
        /** Billed, but the bill isn't on this device (settled history kept on the desktop). */
        BILLED("Billed")
    }

    /** [other] is the party on the other side: the supplier on a buyer's page and vice versa. */
    data class OrderEntry(val order: Order, val other: String, val bills: List<Bill>, val state: OrderState) {
        val open: Boolean get() = state == OrderState.UNBILLED || state == OrderState.BILL_DUE || state == OrderState.PARTLY_PAID
    }

    data class BillEntry(val item: BillItem, val other: String) {
        val bill: Bill get() = item.bill
        val open: Boolean get() = bill.status != PaymentStatus.PAID
    }

    /** [open]: pays at least one bill that is still due. */
    data class AdviceEntry(val advice: Advice, val other: String, val open: Boolean)

    data class Counterparty(val key: String, val name: String, val outstanding: Double, val openBills: Int)

    data class Book(
        val type: PartyType,
        val name: String,
        val orders: List<OrderEntry>,
        val bills: List<BillEntry>,
        val advices: List<AdviceEntry>,
        /** Everyone this party dealt with, most still due first (from the whole book, not the view). */
        val counterparties: List<Counterparty>
    ) {
        /** Due on bills with a known amount. */
        val outstanding: Double get() = PaymentMath.round2(bills.filter { it.open }.sumOf { (it.bill.balance ?: 0.0).coerceAtLeast(0.0) })
        val openBills: Int get() = bills.count { it.open }
        val noAmountBills: Int get() = bills.count { it.bill.amount == null }
        val partlyPaidBills: Int get() = bills.count { it.bill.status == PaymentStatus.PARTLY_PAID }
        val unbilledOrders: Int get() = orders.count { it.state == OrderState.UNBILLED }
        val billed: Double get() = PaymentMath.round2(bills.sumOf { it.bill.amount ?: 0.0 })
        val received: Double get() = PaymentMath.round2(advices.sumOf { it.advice.paid })

        /** Only open items and/or only those with one counterparty ([counterparty] is its key). */
        fun view(openOnly: Boolean, counterparty: String? = null): Book {
            fun keep(other: String, open: Boolean) = (!openOnly || open) && (counterparty == null || SearchText.partyKey(other) == counterparty)
            return copy(
                orders = orders.filter { keep(it.other, it.open) },
                bills = bills.filter { keep(it.other, it.open) },
                advices = advices.filter { keep(it.other, it.open) }
            )
        }
    }

    /** Whether [text] (an order's or bill's name field) is the party called [name]. */
    fun matches(name: String, text: String): Boolean = PartyMatch.matches(name, SearchText.firstLine(text))

    /**
     * One word of [name] to narrow a database search before [matches] decides: the longest word of
     * its core name, so "Shree Ram Textiles Pvt Ltd" searches "textiles".
     */
    fun searchWord(name: String): String =
        PartyMatch.core(name).split(' ').filter { it.isNotEmpty() && it != "shri" && it != "and" }.maxByOrNull { it.length }
            ?: SearchText.tokens(SearchText.firstLine(name)).firstOrNull().orEmpty()

    /** Builds the book of [name] from candidate records (anything not for this party is dropped). */
    fun build(type: PartyType, name: String, orders: List<Order>, bills: List<BillItem>, advices: List<Advice>): Book {
        val buyerSide = type == PartyType.BUYER
        fun own(buyer: String, supplier: String) = if (buyerSide) buyer else supplier
        fun other(buyer: String, supplier: String) = SearchText.firstLine(if (buyerSide) supplier else buyer)

        val billEntries = bills.filter { matches(name, own(it.buyer, it.supplier)) }
            .map { BillEntry(it, other(it.buyer, it.supplier)) }
            .sortedWith(compareByDescending<BillEntry> { it.bill.billDate ?: LocalDate.MIN }.thenByDescending { it.bill.billNo })
        val byNumber = billEntries.groupBy { SearchText.billKey(it.bill.billNo) }
        // A bill number is the supplier's own: when two suppliers used the same one, take the one with this counterparty.
        fun billsFor(numbers: List<String>, other: String): List<BillEntry> = numbers.mapNotNull { no ->
            val same = byNumber[SearchText.billKey(no)].orEmpty()
            same.firstOrNull { PartyMatch.matches(it.other, other) } ?: same.singleOrNull()
        }.distinctBy { it.bill.id }

        val orderEntries = orders.filter { matches(name, own(it.buyer, it.supplier)) }.map { o ->
            val otherName = other(o.buyer, o.supplier)
            val found = billsFor(o.billNos, otherName).map { it.bill }
            val state = when {
                o.billNos.isEmpty() -> OrderState.UNBILLED
                found.isEmpty() -> OrderState.BILLED
                found.all { it.status == PaymentStatus.PAID } -> OrderState.PAID
                found.all { it.status == PaymentStatus.UNPAID } -> OrderState.BILL_DUE
                else -> OrderState.PARTLY_PAID
            }
            OrderEntry(o, otherName, found, state)
        }.sortedWith(compareByDescending<OrderEntry> { it.order.day }.thenByDescending { it.order.orderNo })

        val adviceEntries = advices.filter { matches(name, own(it.buyer, it.supplier)) }.map { a ->
            val otherName = other(a.buyer, a.supplier)
            AdviceEntry(a, otherName, billsFor(a.billNos, otherName).any { it.open })
        }.sortedWith(compareByDescending<AdviceEntry> { it.advice.payDate ?: LocalDate.MIN }.thenByDescending { it.advice.adviceNo })

        val names = LinkedHashMap<String, String>()
        (billEntries.map { it.other } + orderEntries.map { it.other } + adviceEntries.map { it.other })
            .forEach { if (it.isNotBlank()) names.putIfAbsent(SearchText.partyKey(it), it) }
        val counterparties = names.map { (key, display) ->
            val theirs = billEntries.filter { SearchText.partyKey(it.other) == key && it.open }
            Counterparty(key, display, PaymentMath.round2(theirs.sumOf { (it.bill.balance ?: 0.0).coerceAtLeast(0.0) }), theirs.size)
        }.sortedWith(compareByDescending<Counterparty> { it.outstanding }.thenByDescending { it.openBills }.thenBy { it.name.lowercase() })

        return Book(type, name, orderEntries, billEntries, adviceEntries, counterparties)
    }
}
