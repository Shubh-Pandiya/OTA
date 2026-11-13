package com.ota.desktop.accounts

import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.data.BillFilter
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.date
import com.ota.desktop.db.id
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.strOrNull
import com.ota.desktop.db.uuidArray
import java.sql.Connection
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class ColKind { TEXT, MONEY, DATE, NUMBER }

/** A report column; money columns carry "(₹)" in their title and hold rupees as Double. */
/** [foldInto]: on a narrow screen this column is dropped and its text joins column [foldInto]'s cell (exports keep every column). */
data class RCol(val title: String, val kind: ColKind = ColKind.TEXT, val weight: Float = 1f, val foldInto: Int? = null)

enum class RowStyle { NORMAL, HEADING, TOTAL }

/** Where a report row leads when clicked. */
sealed interface RLink {
    data class Voucher(val id: String) : RLink
    data class Bill(val id: String) : RLink
    data class Advice(val id: String) : RLink
    data class Account(val id: String) : RLink
}

data class RRow(val cells: List<Any?>, val style: RowStyle = RowStyle.NORMAL, val link: RLink? = null)

/** One table: shown on screen, and exported as an XLSX sheet or a PDF page set. */
data class ReportTable(
    val title: String,
    val subtitle: String,
    val columns: List<RCol>,
    val rows: List<RRow>,
    /** The ledger's or statement's account: exports can write one file per party. */
    val accountId: String? = null
) {
    val moneyColumns: Set<Int> get() = columns.indices.filter { columns[it].kind == ColKind.MONEY }.toSet()

    /** The table without the columns at [drop]; folds into a dropped column are cleared. */
    fun withoutColumns(drop: Set<Int>): ReportTable {
        if (drop.isEmpty()) return this
        val keep = columns.indices.filter { it !in drop }
        val index = keep.withIndex().associate { (new, old) -> old to new }
        return copy(
            columns = keep.map { i -> columns[i].let { c -> c.copy(foldInto = c.foldInto?.let(index::get)) } },
            rows = rows.map { r -> r.copy(cells = keep.map { r.cells.getOrNull(it) }) }
        )
    }

    /** Drops the columns whose title (without " (₹)") is in [titles]. */
    fun withoutTitles(titles: Set<String>): ReportTable =
        withoutColumns(columns.indices.filter { columnName(columns[it].title) in titles }.toSet())

    companion object {
        fun columnName(title: String) = title.substringBefore(" (₹)")
    }
}

enum class Side(val label: String) { ANY("Dr and Cr"), DR("Dr only"), CR("Cr only") }

/**
 * Narrows the Accounts reports for an export. The defaults show what the screens show.
 * Entry filters (types, amount, side, text) pick ledger and day-book entries; running balances and
 * closing balances still count every entry, so they stay the account's true balance.
 */
data class ReportFilter(
    /** Party or books accounts to include; empty = all. */
    val accounts: Set<String> = emptySet(),
    val groups: Set<AccGroup> = emptySet(),
    val types: Set<VType> = emptySet(),
    /** On an entry's or voucher's amount, in paise. */
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val side: Side = Side.ANY,
    /** Words that must all appear in the voucher no, narration, note or accounts. */
    val text: String = "",
    /** Bills on trade statements and trade outstanding; empty = the report's own (outstanding: unpaid and part paid). */
    val billStatus: Set<PaymentStatus> = emptySet(),
    val hideZero: Boolean = true,
    val openingClosing: Boolean = true,
    /** null = each report's own default (only commission has supplier subtotals). */
    val subtotals: Boolean? = null,
    val runningBalance: Boolean = true
) {
    private val tokens = SearchText.tokens(text)

    fun allows(a: Account) = (accounts.isEmpty() || a.id in accounts) && (groups.isEmpty() || a.group in groups)
    fun allowsType(t: VType) = types.isEmpty() || t in types
    fun allowsAmount(paise: Long) = (minAmount == null || paise >= minAmount) && (maxAmount == null || paise <= maxAmount)
    fun allowsSide(dr: Long, cr: Long) = when (side) { Side.ANY -> true; Side.DR -> dr > 0; Side.CR -> cr > 0 }
    fun allowsText(vararg parts: String): Boolean =
        tokens.isEmpty() || SearchText.normalize(parts.joinToString(" ")).let { hay -> tokens.all { it in hay } }
    fun allowsEntry(type: VType, dr: Long, cr: Long, vararg text: String) =
        allowsType(type) && allowsAmount(dr + cr) && allowsSide(dr, cr) && allowsText(*text)
    val narrowsAccounts: Boolean get() = accounts.isNotEmpty() || groups.isNotEmpty()
}

enum class ReportKind(val label: String, val asOf: Boolean) {
    DAY_BOOK("Day book", false),
    TRIAL_BALANCE("Trial balance", true),
    PROFIT_LOSS("Profit & loss", false),
    BALANCE_SHEET("Balance sheet", true),
    COMMISSION("Commission by supplier & month", false),
    AGEING("Party balances ageing (books)", true),
    TRADE_OUTSTANDING("Trade outstanding (bills)", true),
    CASH_BOOK("Cash / bank book", false)
}

/** Every accounts report, as [ReportTable]s built from the local books (and bills for trade reports). */
class AccountReports(private val db: Db, private val store: AccountsStore, private val payments: PaymentStore) {

    private data class Posting(
        val voucherId: String, val no: String, val type: VType, val date: LocalDate, val narration: String,
        val accountId: String, val dr: Long, val cr: Long, val lineNo: Int, val note: String, val billId: String?
    ) {
        val signed: Long get() = dr - cr
    }

    private fun postings(c: Connection, to: LocalDate?): List<Posting> = c.query(
        """select v.id, v.voucher_no, v.vtype, v.vdate, v.narration, v.source_bill_id, l.account_id, l.dr, l.cr, l.line_no, l.note
           from acc_voucher_lines l join acc_vouchers v on v.id = l.voucher_id
           where not v.deleted""" + (if (to != null) " and v.vdate <= ?" else "") + " order by v.vdate, v.voucher_no, l.line_no",
        *listOfNotNull(to).toTypedArray()
    ) {
        Posting(
            it.id(), it.str("voucher_no"), VType.valueOf(it.str("vtype")), it.date("vdate")!!, it.str("narration"), it.id("account_id"),
            Paise.of(it.getBigDecimal("dr")), Paise.of(it.getBigDecimal("cr")), it.getInt("line_no"), it.str("note"), it.strOrNull("source_bill_id")
        )
    }

    private fun period(from: LocalDate?, to: LocalDate?): String = when {
        from == null && to == null -> "All dates"
        from == null -> "Up to ${to.dmy()}"
        to == null -> "From ${from.dmy()}"
        else -> "${from.dmy()} to ${to.dmy()}"
    }

    // ------------------------------------------------------------------------------ ledgers

    /** An account's ledger: opening balance, every entry with its running balance, closing balance. */
    fun ledger(accountId: String, from: LocalDate?, to: LocalDate?, cashBook: Boolean = false, f: ReportFilter = ReportFilter()): ReportTable =
        ledgers(listOf(accountId), from, to, cashBook, f).single()

    /** Several ledgers from one read of the books. With [ReportFilter.hideZero], untouched zero ledgers are skipped. */
    fun ledgers(accountIds: List<String>, from: LocalDate?, to: LocalDate?, cashBook: Boolean = false, f: ReportFilter = ReportFilter()): List<ReportTable> = db.read { c ->
        val accounts = store.accounts().associateBy { it.id }
        val names = accounts.mapValues { it.value.name }
        val all = postings(c, to)
        val byVoucher = all.groupBy { it.voucherId }
        val byAccount = all.groupBy { it.accountId }
        accountIds.mapNotNull { id ->
            val account = accounts[id] ?: error("No such account")
            val mine = byAccount[id].orEmpty()
            if (accountIds.size > 1 && f.hideZero && account.opening == 0L && mine.isEmpty()) null
            else ledgerTable(account, mine, byVoucher, names, from, to, cashBook, f)
        }
    }

    private fun ledgerTable(
        account: Account, mine: List<Posting>, byVoucher: Map<String, List<Posting>>, names: Map<String, String>,
        from: LocalDate?, to: LocalDate?, cashBook: Boolean, f: ReportFilter
    ): ReportTable {
        val accountId = account.id
        var balance = account.opening + mine.filter { from != null && it.date < from }.sumOf { it.signed }
        val rows = ArrayList<RRow>()
        val inRange = mine.filter { from == null || it.date >= from }
        if (f.openingClosing) rows += RRow(listOf(from, "", "", "Opening balance", "", if (balance > 0) Paise.rupees(balance) else null, if (balance < 0) Paise.rupees(-balance) else null, Paise.rupees(kotlin.math.abs(balance)), side(balance)), RowStyle.HEADING)
        var shownDr = 0L; var shownCr = 0L
        var month: YearMonth? = null; var monthDr = 0L; var monthCr = 0L
        fun monthTotal() {
            if (f.subtotals == true && month != null) rows += RRow(listOf(null, "", "", "Total ${month!!.format(MONTH)}", "", Paise.rupees(monthDr), Paise.rupees(monthCr), null, ""), RowStyle.TOTAL)
        }
        for (p in inRange) {
            balance += p.signed
            val others = byVoucher[p.voucherId].orEmpty().filter { it.accountId != accountId && (it.dr > 0) != (p.dr > 0) }.map { names[it.accountId].orEmpty() }.distinct()
            val particulars = others.joinToString(", ").ifEmpty { "—" }
            val narration = listOf(p.narration, p.note).filter { it.isNotBlank() }.joinToString(" · ")
            if (!f.allowsEntry(p.type, p.dr, p.cr, p.no, particulars, narration)) continue
            val m = YearMonth.from(p.date)
            if (m != month) { monthTotal(); month = m; monthDr = 0; monthCr = 0 }
            monthDr += p.dr; monthCr += p.cr; shownDr += p.dr; shownCr += p.cr
            rows += RRow(
                listOf(
                    p.date, p.no, p.type.label, particulars, narration,
                    p.dr.takeIf { it > 0 }?.let(Paise::rupees), p.cr.takeIf { it > 0 }?.let(Paise::rupees), Paise.rupees(kotlin.math.abs(balance)), side(balance)
                ),
                link = RLink.Voucher(p.voucherId)
            )
        }
        monthTotal()
        rows += RRow(listOf(null, "", "", "Total for the period", "", Paise.rupees(shownDr), Paise.rupees(shownCr), null, ""), RowStyle.TOTAL)
        if (f.openingClosing) rows += RRow(listOf(to, "", "", "Closing balance", "", if (balance > 0) Paise.rupees(balance) else null, if (balance < 0) Paise.rupees(-balance) else null, Paise.rupees(kotlin.math.abs(balance)), side(balance)), RowStyle.TOTAL)
        return ReportTable(
            if (cashBook) "Cash / bank book — ${account.name}" else "Ledger — ${account.name}",
            listOfNotNull(account.party?.label ?: account.group.label, account.subgroup.ifBlank { null }, period(from, to)).joinToString(" · "),
            listOf(
                RCol("Date", ColKind.DATE, 0.8f), RCol("Voucher", weight = 1.2f), RCol("Type", weight = 0.9f, foldInto = 1), RCol("Particulars", weight = 1.6f),
                RCol("Narration", weight = 2f, foldInto = 3), RCol(if (cashBook) "Receipts (₹)" else "Debit (₹)", ColKind.MONEY), RCol(if (cashBook) "Payments (₹)" else "Credit (₹)", ColKind.MONEY),
                RCol("Balance (₹)", ColKind.MONEY), RCol("Dr/Cr", weight = 0.4f)
            ),
            rows,
            accountId
        ).let { if (f.runningBalance) it else it.withoutColumns(setOf(7, 8)) }
    }

    /**
     * Trade statement of a buyer or supplier (memorandum, outside the books): their bills against
     * what payment advices settled — paid, cash discount, goods return, rate difference and claims.
     */
    fun partyStatement(accountId: String, from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable {
        val account = store.account(accountId) ?: error("No such account")
        val kind = account.party ?: error("Not a party account")
        val key = account.partyKey.orEmpty()
        val bills = payments.bills(BillFilter())
            .filter { SearchText.partyKey(if (kind == PartyKind.BUYER) it.buyer else it.supplier) == key && (f.billStatus.isEmpty() || it.bill.status in f.billStatus) }
        data class Entry(val date: LocalDate?, val ref: String, val text: String, val dr: Long, val cr: Long, val link: RLink)
        val entries = ArrayList<Entry>()
        bills.forEach { b ->
            val other = if (kind == PartyKind.BUYER) b.supplier else b.buyer
            b.bill.amount?.let { entries += Entry(b.bill.billDate, "Bill ${b.bill.billNo}", other, Paise.of(it), 0, RLink.Bill(b.bill.id)) }
        }
        if (bills.isNotEmpty()) db.read { c ->
            c.query(
                """select a.id, a.advice_no, a.pay_date, b.bill_no, l.paid, coalesce(l.cd_amount, 0) cd, coalesce(l.goods_return, 0) gr, coalesce(l.rate_diff, 0) rd, coalesce(l.claim, 0) claim
                   from advice_lines l join payment_advices a on a.id = l.advice_id join bills b on b.id = l.bill_id
                   where not l.deleted and not a.deleted and l.bill_id = any(?)""",
                uuidArray(bills.map { it.bill.id })
            ) {
                val parts = listOf("Paid" to "paid", "CD" to "cd", "G.R." to "gr", "R.D." to "rd", "Claim" to "claim")
                    .map { (label, col) -> label to Paise.of(it.getBigDecimal(col)) }.filter { (_, v) -> v != 0L }
                Entry(
                    it.date("pay_date"), "Advice ${AdviceNumber.display(it.str("advice_no"))}",
                    "Bill ${it.str("bill_no")}: " + parts.joinToString(", ") { (l, v) -> "$l ₹${Paise.format(v)}" },
                    0, parts.sumOf { p -> p.second }, RLink.Advice(it.id())
                )
            }
        }.let(entries::addAll)
        val sorted = entries.sortedWith(compareBy<Entry>({ it.date ?: LocalDate.MIN }, { it.cr > 0 }, { it.ref }))
        var balance = sorted.filter { from != null && (it.date ?: LocalDate.MIN) < from }.sumOf { it.dr - it.cr }
        val inRange = sorted.filter { (from == null || (it.date ?: LocalDate.MIN) >= from) && (to == null || (it.date ?: LocalDate.MIN) <= to) }
        val shown = inRange.filter { f.allowsAmount(it.dr + it.cr) && f.allowsSide(it.dr, it.cr) && f.allowsText(it.ref, it.text) }
        val visible = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Entry, Boolean>()).apply { addAll(shown) }
        val rows = ArrayList<RRow>()
        if (f.openingClosing) rows += RRow(listOf(from, "", "Opening balance", null, null, Paise.rupees(kotlin.math.abs(balance)), side(balance)), RowStyle.HEADING)
        inRange.forEach { e ->
            balance += e.dr - e.cr
            if (e !in visible) return@forEach
            rows += RRow(
                listOf(e.date, e.ref, e.text, e.dr.takeIf { it > 0 }?.let(Paise::rupees), e.cr.takeIf { it > 0 }?.let(Paise::rupees), Paise.rupees(kotlin.math.abs(balance)), side(balance)),
                link = e.link
            )
        }
        rows += RRow(listOf(null, "", "Total for the period", Paise.rupees(shown.sumOf { it.dr }), Paise.rupees(shown.sumOf { it.cr }), null, ""), RowStyle.TOTAL)
        if (f.openingClosing) rows += RRow(listOf(to, "", "Balance due", null, null, Paise.rupees(kotlin.math.abs(balance)), side(balance)), RowStyle.TOTAL)
        return ReportTable(
            "Trade statement — ${account.name}",
            "${kind.label} · bills and payment advices (memorandum, not part of the books) · ${period(from, to)}",
            listOf(
                RCol("Date", ColKind.DATE, 0.8f), RCol("Reference", weight = 1.2f), RCol(if (kind == PartyKind.BUYER) "Supplier / details" else "Buyer / details", weight = 2.6f),
                RCol("Billed (₹)", ColKind.MONEY), RCol("Settled (₹)", ColKind.MONEY), RCol("Balance (₹)", ColKind.MONEY), RCol("Dr/Cr", weight = 0.4f)
            ),
            rows,
            accountId
        ).let { if (f.runningBalance) it else it.withoutColumns(setOf(5, 6)) }
    }

    // ------------------------------------------------------------------------------ reports

    fun report(kind: ReportKind, from: LocalDate?, to: LocalDate?, cashAccountId: String? = null, f: ReportFilter = ReportFilter()): ReportTable = when (kind) {
        ReportKind.DAY_BOOK -> dayBook(from, to, f)
        ReportKind.TRIAL_BALANCE -> trialBalance(to, f)
        ReportKind.PROFIT_LOSS -> profitAndLoss(from, to, f)
        ReportKind.BALANCE_SHEET -> balanceSheet(to)
        ReportKind.COMMISSION -> commission(from, to, f)
        ReportKind.AGEING -> ageing(to ?: LocalDate.now(), f)
        ReportKind.TRADE_OUTSTANDING -> tradeOutstanding(to ?: LocalDate.now(), f)
        ReportKind.CASH_BOOK -> ledger(
            cashAccountId ?: store.accountBySystemKey("cash")?.id ?: store.accounts().first { it.cashBank }.id, from, to, cashBook = true, f = f
        )
    }

    fun dayBook(from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable = db.read { c ->
        val accounts = store.accounts().associateBy { it.id }
        val names = accounts.mapValues { it.value.name }
        val list = postings(c, to).filter { from == null || it.date >= from }.groupBy { it.voucherId }.values
            .filter { v ->
                val p = v.first()
                f.allowsType(p.type) && f.allowsAmount(v.sumOf { it.dr }) &&
                    f.allowsText(p.no, p.narration, *v.map { names[it.accountId].orEmpty() + " " + it.note }.toTypedArray()) &&
                    (!f.narrowsAccounts || v.any { l -> accounts[l.accountId]?.let(f::allows) == true })
            }
            .flatMap { v -> v.filter { f.allowsSide(it.dr, it.cr) } }
        val rows = ArrayList<RRow>()
        var last: String? = null
        var day: LocalDate? = null; var dayDr = 0L; var dayCr = 0L
        fun dayTotal() {
            if (f.subtotals == true && day != null) rows += RRow(listOf(null, "", "", "Total ${day.dmy()}", "", Paise.rupees(dayDr), Paise.rupees(dayCr)), RowStyle.TOTAL)
        }
        for (p in list) {
            val first = p.voucherId != last
            last = p.voucherId
            if (p.date != day) { dayTotal(); day = p.date; dayDr = 0; dayCr = 0 }
            dayDr += p.dr; dayCr += p.cr
            rows += RRow(
                listOf(
                    if (first) p.date else null, if (first) p.no else "", if (first) p.type.label else "", names[p.accountId].orEmpty(),
                    if (first) p.narration else p.note, p.dr.takeIf { it > 0 }?.let(Paise::rupees), p.cr.takeIf { it > 0 }?.let(Paise::rupees)
                ),
                link = RLink.Voucher(p.voucherId)
            )
        }
        dayTotal()
        rows += RRow(listOf(null, "", "", "Total (${list.map { it.voucherId }.distinct().size} vouchers)", "", Paise.rupees(list.sumOf { it.dr }), Paise.rupees(list.sumOf { it.cr })), RowStyle.TOTAL)
        ReportTable(
            "Day book", period(from, to),
            listOf(
                RCol("Date", ColKind.DATE, 0.8f), RCol("Voucher", weight = 1.2f), RCol("Type", weight = 0.9f, foldInto = 1), RCol("Account", weight = 1.8f),
                RCol("Narration", weight = 2.2f, foldInto = 3), RCol("Debit (₹)", ColKind.MONEY), RCol("Credit (₹)", ColKind.MONEY)
            ),
            rows
        )
    }

    private fun closing(c: Connection, asOf: LocalDate?): List<Pair<Account, Long>> {
        val moves = store.balances(c, asOf)
        return store.accounts().map { it to it.opening + (moves[it.id] ?: 0L) }
    }

    fun trialBalance(asOf: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable = db.read { c ->
        val all = closing(c, asOf)
        val list = all.filter { (a, bal) -> f.allows(a) && (!f.hideZero || bal != 0L) }
        val rows = ArrayList<RRow>()
        AccGroup.entries.forEach { g ->
            val inGroup = list.filter { it.first.group == g }
            if (inGroup.isEmpty()) return@forEach
            rows += RRow(listOf("", g.label, "", null, null), RowStyle.HEADING)
            inGroup.forEach { (a, bal) ->
                rows += RRow(listOf(a.code, a.name, a.party?.label ?: a.subgroup, if (bal > 0) Paise.rupees(bal) else null, if (bal < 0) Paise.rupees(-bal) else null), link = RLink.Account(a.id))
            }
            if (f.subtotals == true) rows += RRow(
                listOf("", "Total ${g.label.lowercase()}", "", Paise.rupees(inGroup.sumOf { maxOf(it.second, 0) }), Paise.rupees(inGroup.sumOf { maxOf(-it.second, 0) })), RowStyle.TOTAL
            )
        }
        // A difference only means something across the whole books.
        val diff = if (f.narrowsAccounts) 0L else all.sumOf { it.second }
        if (diff != 0L) rows += RRow(listOf("", "Difference in opening balances", "", if (diff < 0) Paise.rupees(-diff) else null, if (diff > 0) Paise.rupees(diff) else null))
        val dr = list.sumOf { maxOf(it.second, 0) } + maxOf(-diff, 0)
        val cr = list.sumOf { maxOf(-it.second, 0) } + maxOf(diff, 0)
        rows += RRow(listOf("", "Total", "", Paise.rupees(dr), Paise.rupees(cr)), RowStyle.TOTAL)
        ReportTable(
            "Trial balance", "As on ${(asOf ?: LocalDate.now()).dmy()}",
            listOf(RCol("Code", weight = 0.5f), RCol("Account", weight = 2.4f), RCol("Group", weight = 1.4f), RCol("Debit (₹)", ColKind.MONEY), RCol("Credit (₹)", ColKind.MONEY)),
            rows
        )
    }

    fun profitAndLoss(from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable = db.read { c ->
        val accounts = store.accounts().associateBy { it.id }
        val moves = postings(c, to).filter { from == null || it.date >= from }.groupBy { it.accountId }.mapValues { (_, l) -> l.sumOf { it.signed } }
        fun section(group: AccGroup, sign: Int): Pair<List<RRow>, Long> {
            val list = accounts.values.filter { it.group == group && f.allows(it) }
                .mapNotNull { a -> (moves[a.id] ?: 0L).takeIf { it != 0L || !f.hideZero }?.let { a to it * sign } }
            return list.map { (a, v) -> RRow(listOf(a.name, a.subgroup, Paise.rupees(v)), link = RLink.Account(a.id)) } to list.sumOf { it.second }
        }
        val (income, totalIncome) = section(AccGroup.INCOME, -1)
        val (expense, totalExpense) = section(AccGroup.EXPENSE, 1)
        val profit = totalIncome - totalExpense
        val rows = ArrayList<RRow>()
        rows += RRow(listOf("Income", "", null), RowStyle.HEADING)
        rows += income
        rows += RRow(listOf("Total income", "", Paise.rupees(totalIncome)), RowStyle.TOTAL)
        rows += RRow(listOf("Expenses", "", null), RowStyle.HEADING)
        rows += expense
        rows += RRow(listOf("Total expenses", "", Paise.rupees(totalExpense)), RowStyle.TOTAL)
        rows += RRow(listOf(if (profit >= 0) "Net profit" else "Net loss", "", Paise.rupees(kotlin.math.abs(profit))), RowStyle.TOTAL)
        ReportTable("Profit & loss", period(from, to), listOf(RCol("Particulars", weight = 2.4f), RCol("Group", weight = 1.4f), RCol("Amount (₹)", ColKind.MONEY)), rows)
    }

    /**
     * Balance sheet: capital and liabilities (with the profit to date) against assets. Party
     * balances are totalled as sundry debtors (Dr) and sundry creditors (Cr). Any difference in the
     * opening balances is shown so both sides always agree.
     */
    fun balanceSheet(asOf: LocalDate?): ReportTable = db.read { c ->
        val list = closing(c, asOf)
        val rows = ArrayList<RRow>()
        val profit = -list.filter { it.first.group == AccGroup.INCOME || it.first.group == AccGroup.EXPENSE }.sumOf { it.second }
        val parties = list.filter { it.first.isParty && it.second != 0L }
        val debtors = parties.filter { it.second > 0 }
        val creditors = parties.filter { it.second < 0 }
        val diff = list.sumOf { it.second }

        rows += RRow(listOf("Capital & liabilities", null), RowStyle.HEADING)
        var left = 0L
        for (g in listOf(AccGroup.EQUITY, AccGroup.LIABILITY)) {
            list.filter { it.first.group == g && !it.first.isParty && it.second != 0L }.forEach { (a, bal) ->
                rows += RRow(listOf(a.name, Paise.rupees(-bal)), link = RLink.Account(a.id)); left += -bal
            }
        }
        if (creditors.isNotEmpty()) { val s = -creditors.sumOf { it.second }; rows += RRow(listOf("Sundry creditors (${creditors.size} parties)", Paise.rupees(s))); left += s }
        rows += RRow(listOf(if (profit >= 0) "Profit & loss account (profit to date)" else "Profit & loss account (loss to date)", Paise.rupees(profit))); left += profit
        if (diff != 0L) { rows += RRow(listOf("Difference in opening balances", Paise.rupees(diff))); left += diff }
        rows += RRow(listOf("Total capital & liabilities", Paise.rupees(left)), RowStyle.TOTAL)

        rows += RRow(listOf("Assets", null), RowStyle.HEADING)
        var right = 0L
        list.filter { it.first.group == AccGroup.ASSET && !it.first.isParty && it.second != 0L }.forEach { (a, bal) ->
            rows += RRow(listOf(a.name, Paise.rupees(bal)), link = RLink.Account(a.id)); right += bal
        }
        if (debtors.isNotEmpty()) { val s = debtors.sumOf { it.second }; rows += RRow(listOf("Sundry debtors (${debtors.size} parties)", Paise.rupees(s))); right += s }
        rows += RRow(listOf("Total assets", Paise.rupees(right)), RowStyle.TOTAL)
        ReportTable("Balance sheet", "As on ${(asOf ?: LocalDate.now()).dmy()}", listOf(RCol("Particulars", weight = 3f), RCol("Amount (₹)", ColKind.MONEY)), rows)
    }

    /** Commission earned (posted from bills) against commission received, per supplier and month. */
    fun commission(from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable = db.read { c ->
        val end = to ?: LocalDate.now()
        val suppliers = store.accounts().filter { it.party == PartyKind.SUPPLIER && f.allows(it) }.associateBy { it.id }
        val billAmounts = store.loadBills(c).associate { it.id to (it.amount ?: 0L) }
        val all = postings(c, end).filter { it.accountId in suppliers }
        val start = from ?: all.minOfOrNull { it.date } ?: end
        val rows = ArrayList<RRow>()
        var gBills = 0; var gBilled = 0L; var gEarned = 0L; var gReceived = 0L; var gDue = 0L
        for ((id, list) in all.groupBy { it.accountId }.toList().sortedBy { suppliers[it.first]!!.name.lowercase() }) {
            val a = suppliers[id]!!
            val inRange = list.filter { it.date >= start }
            if (inRange.isEmpty() && a.opening + list.sumOf { it.signed } == 0L) continue
            var sBills = 0; var sBilled = 0L; var sEarned = 0L; var sReceived = 0L
            val months = inRange.groupBy { YearMonth.from(it.date) }.toSortedMap()
            for ((month, ps) in months) {
                val earned = ps.filter { it.type == VType.COMMISSION }
                val bills = earned.mapNotNull { it.billId }.distinct()
                val billed = bills.sumOf { billAmounts[it] ?: 0L }
                val e = earned.sumOf { it.signed }
                val r = ps.filter { it.type != VType.COMMISSION }.sumOf { it.cr - it.dr }
                val due = a.opening + list.filter { it.date <= month.atEndOfMonth() }.sumOf { it.signed }
                rows += RRow(
                    listOf(a.name, month.format(MONTH), AccountsStore.pct(a.commissionPercent) + "%", bills.size, Paise.rupees(billed), Paise.rupees(e), Paise.rupees(r), Paise.rupees(due)),
                    link = RLink.Account(a.id)
                )
                sBills += bills.size; sBilled += billed; sEarned += e; sReceived += r
            }
            val due = a.opening + list.sumOf { it.signed }
            if (f.subtotals != false) rows += RRow(listOf("${a.name} — total", "", "", sBills, Paise.rupees(sBilled), Paise.rupees(sEarned), Paise.rupees(sReceived), Paise.rupees(due)), RowStyle.TOTAL, RLink.Account(a.id))
            gBills += sBills; gBilled += sBilled; gEarned += sEarned; gReceived += sReceived; gDue += due
        }
        rows += RRow(listOf("All suppliers", "", "", gBills, Paise.rupees(gBilled), Paise.rupees(gEarned), Paise.rupees(gReceived), Paise.rupees(gDue)), RowStyle.TOTAL)
        ReportTable(
            "Commission by supplier & month", "${period(from, to)} · earned is posted from bills, received is every other credit to the supplier",
            listOf(
                RCol("Supplier", weight = 2.2f), RCol("Month", weight = 0.9f), RCol("Rate", weight = 0.5f), RCol("Bills", ColKind.NUMBER, 0.5f),
                RCol("Billed (₹)", ColKind.MONEY), RCol("Earned (₹)", ColKind.MONEY), RCol("Received (₹)", ColKind.MONEY), RCol("Due at month end (₹)", ColKind.MONEY, 1.1f)
            ),
            rows
        )
    }

    /** Party balances in the books, aged first-in first-out: payments clear the oldest debits first. */
    fun ageing(asOf: LocalDate, f: ReportFilter = ReportFilter()): ReportTable = db.read { c ->
        val parties = store.accounts().filter { it.isParty && f.allows(it) }.associateBy { it.id }
        val byAccount = postings(c, asOf).filter { it.accountId in parties }.groupBy { it.accountId }
        val rows = ArrayList<RRow>()
        val totals = LongArray(BUCKETS.size + 1)
        for (a in parties.values.sortedWith(compareBy({ it.party }, { it.name.lowercase() }))) {
            val list = byAccount[a.id].orEmpty()
            val balance = a.opening + list.sumOf { it.signed }
            if (balance == 0L && f.hideZero) continue
            val buckets = LongArray(BUCKETS.size)
            if (balance > 0) {
                // Credits clear the oldest debits first, so the balance is made of the newest debits.
                val debits = (list.filter { it.signed > 0 }.map { it.date to it.signed } + listOfNotNull((LocalDate.MIN to a.opening).takeIf { a.opening > 0 }))
                    .sortedByDescending { it.first }
                var left = balance
                for ((date, amount) in debits) {
                    if (left <= 0) break
                    val part = minOf(amount, left)
                    buckets[bucket(date, asOf)] += part
                    left -= part
                }
                if (left > 0) buckets[BUCKETS.lastIndex] += left
            }
            rows += RRow(
                listOf(a.name, a.party!!.label, Paise.rupees(kotlin.math.abs(balance)), side(balance)) + buckets.map { if (it != 0L) Paise.rupees(it) else null },
                link = RLink.Account(a.id)
            )
            totals[0] += balance
            buckets.forEachIndexed { i, v -> totals[i + 1] += v }
        }
        rows += RRow(listOf("Total", "", Paise.rupees(kotlin.math.abs(totals[0])), side(totals[0])) + (1..BUCKETS.size).map { Paise.rupees(totals[it]) }, RowStyle.TOTAL)
        ReportTable(
            "Party balances ageing", "Books (commission receivable and other entries) · as on ${asOf.dmy()} · Cr balances are advances and are not aged",
            listOf(RCol("Party", weight = 2.2f), RCol("Type", weight = 0.7f), RCol("Balance (₹)", ColKind.MONEY), RCol("Dr/Cr", weight = 0.4f)) +
                BUCKETS.map { RCol("$it (₹)", ColKind.MONEY, 0.9f) },
            rows
        )
    }

    /** Unpaid and part-paid bills per buyer and supplier, aged by bill date. */
    fun tradeOutstanding(asOf: LocalDate, f: ReportFilter = ReportFilter()): ReportTable {
        val picked = if (f.accounts.isEmpty()) null else store.accounts().filter { it.id in f.accounts && it.isParty }.takeIf { it.isNotEmpty() }
        val buyers = picked?.filter { it.party == PartyKind.BUYER }?.mapNotNull { it.partyKey }?.toSet()
        val sellers = picked?.filter { it.party == PartyKind.SUPPLIER }?.mapNotNull { it.partyKey }?.toSet()
        val due = payments.bills(BillFilter())
            .filter { if (f.billStatus.isEmpty()) (it.bill.balance ?: 0.0) > 0.0 else it.bill.status in f.billStatus }
            .filter { buyers == null || SearchText.partyKey(it.buyer) in buyers || SearchText.partyKey(it.supplier) in sellers.orEmpty() }
            .filter { f.allowsText(it.bill.billNo, it.buyer, it.supplier) && f.allowsAmount(Paise.of(it.bill.balance ?: 0.0)) }
        data class Key(val buyer: String, val supplier: String)
        val rows = ArrayList<RRow>()
        val totals = LongArray(BUCKETS.size + 2)
        var count = 0
        var buyerKey: String? = null
        val sub = LongArray(totals.size); var subCount = 0; var subName = ""
        fun buyerTotal() {
            if (f.subtotals == true && buyerKey != null) rows += RRow(
                listOf("$subName — total", "", subCount, Paise.rupees(sub[0]), Paise.rupees(sub[1])) + (2 until sub.size).map { Paise.rupees(sub[it]) }, RowStyle.TOTAL
            )
            sub.fill(0); subCount = 0
        }
        due.groupBy { Key(SearchText.partyKey(it.buyer), SearchText.partyKey(it.supplier)) }.values
            .sortedWith(compareBy({ it.first().buyer.lowercase() }, { it.first().supplier.lowercase() }))
            .forEach { bills ->
                val key = SearchText.partyKey(bills.first().buyer)
                if (key != buyerKey) { buyerTotal(); buyerKey = key; subName = bills.first().buyer.ifBlank { "—" } }
                val buckets = LongArray(BUCKETS.size)
                bills.forEach { b -> buckets[bill(b.bill.billDate, asOf)] += Paise.of(b.bill.balance ?: 0.0) }
                val billed = bills.sumOf { Paise.of(it.bill.amount ?: 0.0) }
                val balance = buckets.sum()
                rows += RRow(
                    listOf(bills.first().buyer.ifBlank { "—" }, bills.first().supplier.ifBlank { "—" }, bills.size, Paise.rupees(billed), Paise.rupees(balance)) +
                        buckets.map { if (it != 0L) Paise.rupees(it) else null }
                )
                count += bills.size; totals[0] += billed; totals[1] += balance
                buckets.forEachIndexed { i, v -> totals[i + 2] += v }
                subCount += bills.size; sub[0] += billed; sub[1] += balance
                buckets.forEachIndexed { i, v -> sub[i + 2] += v }
            }
        buyerTotal()
        rows += RRow(listOf("Total", "", count, Paise.rupees(totals[0]), Paise.rupees(totals[1])) + (2 until totals.size).map { Paise.rupees(totals[it]) }, RowStyle.TOTAL)
        return ReportTable(
            "Trade outstanding", "Bills still due after payment advices · aged by bill date as on ${asOf.dmy()} (undated bills count as oldest)",
            listOf(RCol("Buyer", weight = 1.8f), RCol("Supplier", weight = 1.8f), RCol("Bills", ColKind.NUMBER, 0.5f), RCol("Billed (₹)", ColKind.MONEY), RCol("Due (₹)", ColKind.MONEY)) +
                BUCKETS.map { RCol("$it (₹)", ColKind.MONEY, 0.9f) },
            rows
        )
    }

    // ------------------------------------------------------------------------------ lists

    /** The voucher register: one row per voucher, with optional per-type subtotals. */
    fun voucherList(from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable {
        val accounts = store.accounts()
        val chosen = accounts.filter(f::allows).map { it.name }.toSet()
        val list = store.vouchers(from, to).filter { v ->
            f.allowsType(v.type) && f.allowsAmount(v.total) && f.allowsText(v.no, v.narration, v.debits, v.credits) &&
                (!f.narrowsAccounts || (v.debits.split(", ") + v.credits.split(", ")).any { it in chosen }) &&
                when (f.side) { Side.ANY -> true; Side.DR -> v.debits.split(", ").any { it in chosen }; Side.CR -> v.credits.split(", ").any { it in chosen } }
        }.sortedWith(compareBy({ it.date }, { it.no }))
        val rows = ArrayList<RRow>()
        fun row(v: VoucherSummary) = RRow(listOf(v.date, v.no, v.type.label, v.debits, v.credits, v.narration, Paise.rupees(v.total)), link = RLink.Voucher(v.id))
        if (f.subtotals == true) {
            list.groupBy { it.type }.toSortedMap().forEach { (type, vs) ->
                rows += RRow(listOf(null, "", type.label, "", "", "", null), RowStyle.HEADING)
                vs.forEach { rows += row(it) }
                rows += RRow(listOf(null, "", "Total ${type.label.lowercase()} (${vs.size})", "", "", "", Paise.rupees(vs.sumOf { it.total })), RowStyle.TOTAL)
            }
        } else list.forEach { rows += row(it) }
        rows += RRow(listOf(null, "", "Total (${list.size} vouchers)", "", "", "", Paise.rupees(list.sumOf { it.total })), RowStyle.TOTAL)
        return ReportTable(
            "Vouchers", period(from, to),
            listOf(
                RCol("Date", ColKind.DATE, 0.8f), RCol("Voucher", weight = 0.8f), RCol("Type", weight = 0.8f), RCol("Debit", weight = 1.6f),
                RCol("Credit", weight = 1.6f), RCol("Narration", weight = 2f), RCol("Amount (₹)", ColKind.MONEY)
            ),
            rows
        )
    }

    /** Buyers or suppliers with their books balance today, bills and commission %. */
    fun partyList(kind: PartyKind, f: ReportFilter = ReportFilter()): ReportTable {
        val list = store.parties(kind).filter { f.allows(it.account) && (!f.hideZero || it.balance != 0L || it.bills > 0) }
        val supplier = kind == PartyKind.SUPPLIER
        val rows = list.map { p ->
            RRow(
                listOf(p.account.code, p.account.name, p.bills, Paise.rupees(p.billed)) +
                    (if (supplier) listOf(AccountsStore.pct(p.account.commissionPercent) + "%") else emptyList()) +
                    listOf(Paise.rupees(kotlin.math.abs(p.account.opening)), side(p.account.opening), Paise.rupees(kotlin.math.abs(p.balance)), side(p.balance)),
                link = RLink.Account(p.account.id)
            )
        } + RRow(
            listOf("", "Total (${list.size})", list.sumOf { it.bills }, Paise.rupees(list.sumOf { it.billed })) +
                (if (supplier) listOf("") else emptyList()) + listOf(null, "", null, ""),
            RowStyle.TOTAL
        )
        return ReportTable(
            if (supplier) "Suppliers" else "Buyers", "Books balance as on ${LocalDate.now().dmy()}",
            listOf(RCol("Code", weight = 0.6f), RCol("Name", weight = 2f), RCol("Bills", ColKind.NUMBER, 0.5f), RCol("Billed (₹)", ColKind.MONEY)) +
                (if (supplier) listOf(RCol("Commission", weight = 0.6f)) else emptyList()) +
                listOf(RCol("Opening (₹)", ColKind.MONEY), RCol("Op. Dr/Cr", weight = 0.4f), RCol("Balance (₹)", ColKind.MONEY), RCol("Dr/Cr", weight = 0.4f)),
            rows
        )
    }

    /** The chart of accounts with opening and closing balances. */
    fun chart(asOf: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable {
        val closing = store.closingBalances(asOf)
        val list = store.accounts().filter { a -> f.allows(a) && (!f.hideZero || a.opening != 0L || (closing[a.id] ?: 0L) != 0L || !a.isParty) }
        val rows = ArrayList<RRow>()
        AccGroup.entries.forEach { g ->
            val inGroup = list.filter { it.group == g }
            if (inGroup.isEmpty()) return@forEach
            rows += RRow(listOf("", g.label, "", "", null, "", null, ""), RowStyle.HEADING)
            inGroup.forEach { a ->
                val bal = closing[a.id] ?: 0L
                rows += RRow(
                    listOf(a.code, a.name, a.kindLabel, a.subgroup, Paise.rupees(kotlin.math.abs(a.opening)), side(a.opening), Paise.rupees(kotlin.math.abs(bal)), side(bal)),
                    link = RLink.Account(a.id)
                )
            }
        }
        return ReportTable(
            "Chart of accounts", "Balances as on ${(asOf ?: LocalDate.now()).dmy()}",
            listOf(
                RCol("Code", weight = 0.6f), RCol("Account", weight = 2f), RCol("Kind"), RCol("Subgroup"),
                RCol("Opening (₹)", ColKind.MONEY), RCol("Op. Dr/Cr", weight = 0.4f), RCol("Balance (₹)", ColKind.MONEY), RCol("Dr/Cr", weight = 0.4f)
            ),
            rows
        )
    }

    /** Who created, edited or deleted which voucher and when. */
    fun auditLog(from: LocalDate?, to: LocalDate?, f: ReportFilter = ReportFilter()): ReportTable {
        val list = store.audit(100_000).filter { e ->
            val day = e.at.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
            (from == null || day >= from) && (to == null || day <= to) && f.allowsText(e.action, e.voucherNo, e.summary)
        }
        return ReportTable(
            "Audit log", period(from, to),
            listOf(RCol("When", weight = 1.2f), RCol("Action", weight = 0.7f), RCol("Voucher", weight = 0.8f), RCol("Details", weight = 4f)),
            list.map { e ->
                RRow(
                    listOf(e.at.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(WHEN), e.action, e.voucherNo, e.summary),
                    link = e.voucherId?.let(RLink::Voucher)
                )
            }
        )
    }

    private fun bill(date: LocalDate?, asOf: LocalDate) = bucket(date ?: LocalDate.MIN, asOf)

    private fun bucket(date: LocalDate, asOf: LocalDate): Int {
        if (date == LocalDate.MIN) return BUCKETS.lastIndex
        val days = ChronoUnit.DAYS.between(date, asOf)
        return when {
            days <= 30 -> 0
            days <= 60 -> 1
            days <= 90 -> 2
            days <= 180 -> 3
            else -> 4
        }
    }

    companion object {
        val BUCKETS = listOf("0–30 days", "31–60", "61–90", "91–180", "Over 180")
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
        private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

        fun side(signed: Long) = when { signed > 0 -> "Dr"; signed < 0 -> "Cr"; else -> "" }
        private fun LocalDate?.dmy(): String = this?.format(DMY).orEmpty()
    }
}
