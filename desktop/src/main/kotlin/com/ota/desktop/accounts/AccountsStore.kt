package com.ota.desktop.accounts

import com.example.otaapplication.data.SearchText
import com.ota.desktop.db.Db
import com.ota.desktop.db.date
import com.ota.desktop.db.dbl
import com.ota.desktop.db.id
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.strOrNull
import com.ota.desktop.db.update
import com.ota.desktop.db.exec
import org.json.JSONArray
import org.json.JSONObject
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A bill with the parties it belongs to: its own, or else those of its first live order. */
internal data class BookBill(val id: String, val no: String, val date: LocalDate?, val amount: Long?, val buyer: String, val supplier: String)

/**
 * The agency's own books: chart of accounts, party accounts, vouchers and their audit trail. Local
 * only (never synced). Commission is posted from bills by [reconcileCommission], which is idempotent:
 * one voucher per bill at most, rewritten only when the bill, its supplier or the supplier's % changed.
 */
class AccountsStore(private val db: Db, private val onChange: () -> Unit = {}) {
    private val reconcileLock = Any()

    // ------------------------------------------------------------------------------ accounts

    fun accounts(): List<Account> = db.read { c -> c.query("select $ACC from acc_accounts $ACC_ORDER") { it.toAccount() } }

    fun account(id: String): Account? = db.read { c -> c.one("select $ACC from acc_accounts where id = ?", UUID.fromString(id)) { it.toAccount() } }

    fun accountBySystemKey(key: String): Account? = db.read { c -> c.one("select $ACC from acc_accounts where system_key = ?", key) { it.toAccount() } }

    /** Party account of a buyer or supplier by name (any case or spacing), if it exists. */
    fun partyAccount(kind: PartyKind, name: String): Account? = db.read { c ->
        c.one("select $ACC from acc_accounts where party_type = ? and party_key = ?", kind.name, SearchText.partyKey(name)) { it.toAccount() }
    }

    /** Adds or edits an account. Party accounts come from the catalog; only their code, %, opening and active flag change. */
    fun saveAccount(account: Account): Account {
        val name = account.name.trim()
        if (name.isEmpty()) throw AccountsError("Enter the account name")
        if (account.commissionPercent !in 0.0..100.0) throw AccountsError("Commission must be between 0 and 100%")
        val saved = try {
            db.tx { c ->
                if (account.id.isBlank()) {
                    if (account.party != null) throw AccountsError("Party accounts are created from Buyers & Suppliers")
                    val id = UUID.randomUUID()
                    c.update(
                        "insert into acc_accounts (id, code, name, grp, subgroup, cash_bank, opening_balance, active) values (?, ?, ?, ?, ?, ?, ?, ?)",
                        id, account.code.trim(), name, account.group.name, account.subgroup.trim(), account.cashBank, Paise.decimal(account.opening), account.active
                    )
                    id.toString()
                } else {
                    val old = c.one("select $ACC from acc_accounts where id = ?", UUID.fromString(account.id)) { it.toAccount() }
                        ?: throw AccountsError("This account no longer exists")
                    if (old.systemKey == COMMISSION && !account.active) throw AccountsError("Commission income is used by automatic posting and can't be deactivated")
                    if (old.isParty) {
                        c.update(
                            "update acc_accounts set code = ?, commission_percent = ?, opening_balance = ?, active = ? where id = ?",
                            account.code.trim(), BigDecimal.valueOf(account.commissionPercent).setScale(3, RoundingMode.HALF_UP),
                            Paise.decimal(account.opening), account.active, UUID.fromString(old.id)
                        )
                    } else {
                        c.update(
                            "update acc_accounts set code = ?, name = ?, grp = ?, subgroup = ?, cash_bank = ?, opening_balance = ?, active = ? where id = ?",
                            account.code.trim(), name, (if (old.systemKey == COMMISSION) old.group else account.group).name, account.subgroup.trim(),
                            account.cashBank, Paise.decimal(account.opening), account.active, UUID.fromString(old.id)
                        )
                    }
                    old.id
                }
            }
        } catch (e: PSQLException) {
            if (e.sqlState == "23505") throw AccountsError("An account named \"$name\" already exists")
            throw e
        }
        onChange()
        if (account.isParty) reconcileCommission()
        return account(saved)!!
    }

    /** Deletes an unused, user-made account. Accounts with entries can only be deactivated. */
    fun deleteAccount(id: String) {
        db.tx { c ->
            val a = c.one("select $ACC from acc_accounts where id = ?", UUID.fromString(id)) { it.toAccount() } ?: return@tx
            if (a.systemKey != null) throw AccountsError("${a.name} is a built-in account; deactivate it instead")
            if (a.isParty) throw AccountsError("Party accounts follow Buyers & Suppliers; deactivate it instead")
            val used = c.one("select count(*) from acc_voucher_lines where account_id = ?", UUID.fromString(id)) { it.getInt(1) } ?: 0
            if (used > 0) throw AccountsError("${a.name} has $used entries; deactivate it instead")
            c.update("delete from acc_accounts where id = ?", UUID.fromString(id))
        }
        onChange()
    }

    /** Sets a supplier's commission % and re-posts commission on its bills. */
    fun setCommission(accountId: String, percent: Double): ReconcileResult {
        if (percent !in 0.0..100.0) throw AccountsError("Commission must be between 0 and 100%")
        db.tx { c ->
            c.update(
                "update acc_accounts set commission_percent = ? where id = ? and party_type = 'SUPPLIER'",
                BigDecimal.valueOf(percent).setScale(3, RoundingMode.HALF_UP), UUID.fromString(accountId)
            )
        }
        onChange()
        return reconcileCommission()
    }

    /** Signed (Dr +) closing balance of every account, opening included. */
    fun closingBalances(asOf: LocalDate? = null): Map<String, Long> = db.read { c ->
        val moves = balances(c, asOf)
        c.query("select id, opening_balance from acc_accounts") { it.id() to Paise.of(it.getBigDecimal("opening_balance")) }
            .associate { (id, opening) -> id to opening + (moves[id] ?: 0L) }
    }

    /** Buyers or suppliers with their books balance today and the bills they appear on. */
    fun parties(kind: PartyKind): List<PartyAccount> = db.read { c ->
        val accounts = c.query("select $ACC from acc_accounts where party_type = ? order by lower(name)", kind.name) { it.toAccount() }
        val balances = balances(c, null)
        val bills = loadBills(c).groupBy { SearchText.partyKey(if (kind == PartyKind.BUYER) it.buyer else it.supplier) }
        accounts.map { a ->
            val mine = bills[a.partyKey].orEmpty()
            PartyAccount(a, a.opening + (balances[a.id] ?: 0L), mine.size, mine.sumOf { it.amount ?: 0L })
        }
    }

    // ------------------------------------------------------------------------------ vouchers

    fun vouchers(from: LocalDate? = null, to: LocalDate? = null, text: String = "", type: VType? = null, includeDeleted: Boolean = false): List<VoucherSummary> {
        val where = StringBuilder("true")
        val args = ArrayList<Any?>()
        if (!includeDeleted) where.append(" and not v.deleted")
        from?.let { where.append(" and v.vdate >= ?"); args += it }
        to?.let { where.append(" and v.vdate <= ?"); args += it }
        type?.let { where.append(" and v.vtype = ?"); args += it.name }
        val rows = db.read { c ->
            c.query(
                """select v.id, v.voucher_no, v.vtype, v.vdate, v.narration, v.source_bill_id, v.deleted, coalesce(sum(l.dr), 0) as total,
                          coalesce(string_agg(case when l.dr > 0 then a.name end, ', ' order by l.line_no), '') as debits,
                          coalesce(string_agg(case when l.cr > 0 then a.name end, ', ' order by l.line_no), '') as credits
                   from acc_vouchers v left join acc_voucher_lines l on l.voucher_id = v.id left join acc_accounts a on a.id = l.account_id
                   where $where group by v.id order by v.vdate desc, v.voucher_no desc""",
                *args.toTypedArray()
            ) {
                VoucherSummary(
                    it.id(), it.str("voucher_no"), VType.valueOf(it.str("vtype")), it.date("vdate")!!, it.str("narration"),
                    Paise.of(it.getBigDecimal("total")), it.str("debits"), it.str("credits"), it.strOrNull("source_bill_id"), it.getBoolean("deleted")
                )
            }
        }
        val tokens = SearchText.tokens(text)
        if (tokens.isEmpty()) return rows
        return rows.filter { v ->
            val hay = SearchText.normalize("${v.no} ${v.narration} ${v.debits} ${v.credits} ${v.type.label} ${Paise.format(v.total)}")
            tokens.all { it in hay }
        }
    }

    fun voucher(id: String): Voucher? = db.read { c -> load(c, id) }

    fun voucherForBill(billId: String): Voucher? = db.read { c ->
        c.one("select id from acc_vouchers where source_bill_id = ?", UUID.fromString(billId)) { it.id() }?.let { load(c, it) }
    }

    /** Saves a balanced voucher (new or edited) and records it in the audit trail. */
    fun saveVoucher(draft: VoucherDraft): Voucher {
        if (draft.type == VType.COMMISSION) throw AccountsError("Commission vouchers are posted from bills")
        val lines = draft.lines.filter { it.accountId.isNotBlank() || it.dr != 0L || it.cr != 0L }
        val id = db.tx { c ->
            val accounts = c.query("select $ACC from acc_accounts") { it.toAccount() }.associateBy { it.id }
            val before = draft.id?.let { load(c, it) ?: throw AccountsError("This voucher no longer exists") }
            if (before?.automatic == true) throw AccountsError("Commission vouchers are posted from bills; edit the bill or the supplier's % instead")
            if (before?.deleted == true) throw AccountsError("This voucher was deleted")
            validate(draft.type, draft.date, lines, accounts, before)
            val date = draft.date!!
            val narration = draft.narration.trim()
            val vid: UUID
            if (before == null) {
                vid = UUID.randomUUID()
                c.update(
                    "insert into acc_vouchers (id, voucher_no, vtype, vdate, narration) values (?, ?, ?, ?, ?)",
                    vid, nextNumber(c, draft.type, date), draft.type.name, date, narration
                )
            } else {
                vid = UUID.fromString(before.id)
                // The number follows the type (PV, RV…) and the financial year it falls in.
                val no = if (before.type != draft.type || fyCode(before.date) != fyCode(date)) nextNumber(c, draft.type, date) else before.no
                c.update(
                    "update acc_vouchers set voucher_no = ?, vtype = ?, vdate = ?, narration = ?, updated_at = now() where id = ?",
                    no, draft.type.name, date, narration, vid
                )
                c.update("delete from acc_voucher_lines where voucher_id = ?", vid)
            }
            writeLines(c, vid, lines)
            val after = load(c, vid.toString())!!
            audit(c, if (before == null) "created" else "edited", after.id, after.no, before?.let { json(it, accounts) }, json(after, accounts))
            vid.toString()
        }
        onChange()
        return voucher(id)!!
    }

    fun deleteVoucher(id: String) {
        db.tx { c ->
            val v = load(c, id) ?: return@tx
            if (v.automatic) throw AccountsError("Commission vouchers are posted from bills; delete the bill or set the supplier's % to 0 instead")
            if (v.deleted) return@tx
            c.update("update acc_vouchers set deleted = true, updated_at = now() where id = ?", UUID.fromString(id))
            val accounts = c.query("select $ACC from acc_accounts") { it.toAccount() }.associateBy { it.id }
            audit(c, "deleted", v.id, v.no, json(v, accounts), null)
        }
        onChange()
    }

    fun audit(limit: Int = 1000): List<AuditEntry> = db.read { c ->
        c.query("select id, at, action, voucher_id, voucher_no, before, after from acc_audit order by id desc limit $limit") {
            val doc = (it.getString("after") ?: it.getString("before"))?.let(::JSONObject)
            AuditEntry(
                it.getLong("id"), it.getObject("at", java.time.OffsetDateTime::class.java), it.str("action"), it.strOrNull("voucher_id"),
                it.str("voucher_no"), doc?.let(::describe).orEmpty()
            )
        }
    }

    /** The audit record's voucher before and after, as stored. */
    fun auditDetail(id: Long): Pair<String?, String?> = db.read { c ->
        c.one("select before, after from acc_audit where id = ?", id) { it.getString("before") to it.getString("after") } ?: (null to null)
    }

    // ------------------------------------------------------------------------------ commission

    /**
     * Brings commission vouchers in line with the bills: one per live bill with an amount whose
     * supplier has a commission %, dated on the bill (Dr supplier, Cr Commission income). Safe to
     * run any number of times; it writes only what differs. Also opens a ledger for every catalog
     * buyer and supplier and every party named on a bill.
     */
    fun reconcileCommission(today: LocalDate = LocalDate.now()): ReconcileResult {
        val result = synchronized(reconcileLock) {
            db.tx { c ->
                c.exec("lock table acc_vouchers in share row exclusive mode")
                val bills = loadBills(c)
                val added = ensureParties(c, bills)
                val commission = c.one("select id from acc_accounts where system_key = '$COMMISSION'") { it.id() }
                    ?: error("The Commission income account is missing")
                val suppliers = c.query("select id, party_key, commission_percent from acc_accounts where party_type = 'SUPPLIER'") {
                    it.str("party_key") to (it.id() to (it.dbl("commission_percent") ?: 0.0))
                }.toMap()
                val accounts by lazy { c.query("select $ACC from acc_accounts") { it.toAccount() }.associateBy { it.id } }
                val existing = c.query("select id from acc_vouchers where source_bill_id is not null") { it.id() }
                    .mapNotNull { load(c, it) }.associateBy { it.sourceBillId!! }

                var created = 0; var updated = 0; var removed = 0
                val wanted = HashSet<String>()
                for (b in bills) {
                    val amount = b.amount ?: continue
                    if (amount <= 0) continue
                    val (supplierId, percent) = suppliers[SearchText.partyKey(b.supplier)] ?: continue
                    if (percent <= 0.0) continue
                    val paise = Paise.decimal(amount).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
                    if (paise <= 0) continue
                    wanted += b.id
                    val old = existing[b.id]
                    val date = b.date ?: old?.date ?: today
                    val narration = buildString {
                        append("Commission ${pct(percent)}% on bill ${b.no}")
                        b.date?.let { append(" dated ${it.format(DMY)}") }
                        append(" of ₹${Paise.format(amount)}")
                        SearchText.firstLine(b.buyer).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                    }
                    val lines = listOf(VoucherLine(supplierId, paise, 0), VoucherLine(commission, 0, paise))
                    if (old == null) {
                        val id = UUID.randomUUID()
                        c.update(
                            "insert into acc_vouchers (id, voucher_no, vtype, vdate, narration, source_bill_id) values (?, ?, 'COMMISSION', ?, ?, ?)",
                            id, nextNumber(c, VType.COMMISSION, date), date, narration, UUID.fromString(b.id)
                        )
                        writeLines(c, id, lines)
                        val v = load(c, id.toString())!!
                        audit(c, "auto-posted", v.id, v.no, null, json(v, accounts))
                        created++
                    } else if (old.deleted || old.date != date || old.narration != narration || old.lines.map { it.copy(note = "") } != lines) {
                        val id = UUID.fromString(old.id)
                        c.update("update acc_vouchers set vdate = ?, narration = ?, deleted = false, updated_at = now() where id = ?", date, narration, id)
                        c.update("delete from acc_voucher_lines where voucher_id = ?", id)
                        writeLines(c, id, lines)
                        val v = load(c, old.id)!!
                        audit(c, if (old.deleted) "auto-restored" else "auto-updated", v.id, v.no, json(old, accounts), json(v, accounts))
                        updated++
                    }
                }
                for ((billId, old) in existing) {
                    if (billId in wanted || old.deleted) continue
                    c.update("update acc_vouchers set deleted = true, updated_at = now() where id = ?", UUID.fromString(old.id))
                    audit(c, "auto-removed", old.id, old.no, json(old, accounts), null)
                    removed++
                }
                ReconcileResult(created, updated, removed, added)
            }
        }
        if (result.changed) onChange()
        return result
    }

    // ------------------------------------------------------------------------------ internals

    internal fun loadBills(c: Connection): List<BookBill> {
        val party = """from order_bills pl join orders po on po.id = pl.order_id
            where pl.bill_id = b.id and not pl.deleted and not po.deleted order by po.order_day nulls last, po.order_no limit 1"""
        return c.query(
            "select b.id, b.bill_no, b.bill_date, b.amount, b.buyer, b.supplier, (select po.buyer $party) as p_buyer, (select po.supplier $party) as p_supplier " +
                "from bills b where not b.deleted order by b.bill_date nulls last, b.bill_no, b.id"
        ) {
            val own = it.str("buyer").isNotBlank() && it.str("supplier").isNotBlank()
            BookBill(
                it.id(), it.str("bill_no"), it.date("bill_date"), it.getBigDecimal("amount")?.let(Paise::of),
                if (own) it.str("buyer") else it.str("p_buyer"), if (own) it.str("supplier") else it.str("p_supplier")
            )
        }
    }

    /** Signed (Dr +) movement per account from live vouchers up to [asOf] (all when null). */
    internal fun balances(c: Connection, asOf: LocalDate?): Map<String, Long> =
        c.query(
            "select l.account_id, sum(l.dr) - sum(l.cr) as bal from acc_voucher_lines l join acc_vouchers v on v.id = l.voucher_id " +
                "where not v.deleted" + (if (asOf != null) " and v.vdate <= ?" else "") + " group by l.account_id",
            *listOfNotNull(asOf).toTypedArray()
        ) { it.id("account_id") to Paise.of(it.getBigDecimal("bal")) }.toMap()

    private fun ensureParties(c: Connection, bills: List<BookBill>): Int {
        val existing = c.query("select party_type, party_key from acc_accounts where party_type is not null") { it.str("party_type") to it.str("party_key") }.toHashSet()
        var added = 0
        fun add(kind: PartyKind, rawName: String) {
            val name = SearchText.firstLine(rawName).replace(Regex("\\s+"), " ")
            val key = SearchText.partyKey(rawName)
            if (key.isEmpty() || (kind.name to key) in existing) return
            existing += kind.name to key
            added += c.update(
                "insert into acc_accounts (id, name, grp, subgroup, party_type, party_key) values (?, ?, 'ASSET', ?, ?, ?) " +
                    "on conflict (party_type, party_key) where party_type is not null do nothing",
                UUID.randomUUID(), name, if (kind == PartyKind.BUYER) "Sundry debtors · buyers" else "Sundry debtors · suppliers", kind.name, key
            )
        }
        c.query("select name from buyers where not deleted order by lower(name)") { it.str("name") }.forEach { add(PartyKind.BUYER, it) }
        c.query("select name from suppliers where not deleted order by lower(name)") { it.str("name") }.forEach { add(PartyKind.SUPPLIER, it) }
        bills.forEach { add(PartyKind.BUYER, it.buyer); add(PartyKind.SUPPLIER, it.supplier) }
        return added
    }

    private fun validate(type: VType, date: LocalDate?, lines: List<VoucherLine>, accounts: Map<String, Account>, before: Voucher?) {
        if (date == null) throw AccountsError("Enter the voucher date as dd-mm-yyyy")
        if (lines.size < 2) throw AccountsError("A voucher needs at least two lines")
        lines.forEachIndexed { i, l ->
            val n = i + 1
            val a = accounts[l.accountId] ?: throw AccountsError("Line $n: pick an account")
            if (l.dr < 0 || l.cr < 0) throw AccountsError("Line $n: amounts can't be negative")
            if ((l.dr > 0) == (l.cr > 0)) throw AccountsError("Line $n: enter either a debit or a credit amount")
            if (!a.active && before?.lines?.none { it.accountId == a.id } != false) throw AccountsError("Line $n: ${a.name} is inactive")
        }
        val dr = lines.sumOf { it.dr }
        val cr = lines.sumOf { it.cr }
        if (dr != cr) throw AccountsError("Debits ₹${Paise.format(dr)} and credits ₹${Paise.format(cr)} differ by ₹${Paise.format(kotlin.math.abs(dr - cr))}")
        val cash = { l: VoucherLine -> accounts[l.accountId]!!.cashBank }
        when (type) {
            VType.PAYMENT -> if (lines.none { it.cr > 0 && cash(it) }) throw AccountsError("A payment is credited to a cash or bank account")
            VType.RECEIPT -> if (lines.none { it.dr > 0 && cash(it) }) throw AccountsError("A receipt is debited to a cash or bank account")
            VType.CONTRA -> if (!lines.all(cash)) throw AccountsError("A contra moves money only between cash and bank accounts")
            else -> Unit
        }
    }

    private fun nextNumber(c: Connection, type: VType, date: LocalDate): String {
        val prefix = "${type.prefix}/${fyCode(date)}/"
        val last = c.one(
            "select coalesce(max(substring(voucher_no from ${prefix.length + 1})::int), 0) from acc_vouchers where voucher_no like ? and substring(voucher_no from ${prefix.length + 1}) ~ '^[0-9]+$'",
            "$prefix%"
        ) { it.getInt(1) } ?: 0
        return prefix + "%04d".format(last + 1)
    }

    private fun writeLines(c: Connection, voucherId: UUID, lines: List<VoucherLine>) {
        lines.forEachIndexed { i, l ->
            c.update(
                "insert into acc_voucher_lines (id, voucher_id, line_no, account_id, dr, cr, note) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), voucherId, i + 1, UUID.fromString(l.accountId), Paise.decimal(l.dr), Paise.decimal(l.cr), l.note.trim()
            )
        }
    }

    private fun load(c: Connection, id: String): Voucher? {
        val uuid = UUID.fromString(id)
        val lines = c.query("select account_id, dr, cr, note from acc_voucher_lines where voucher_id = ? order by line_no", uuid) {
            VoucherLine(it.id("account_id"), Paise.of(it.getBigDecimal("dr")), Paise.of(it.getBigDecimal("cr")), it.str("note"))
        }
        return c.one("select id, voucher_no, vtype, vdate, narration, source_bill_id, deleted from acc_vouchers where id = ?", uuid) {
            Voucher(it.id(), it.str("voucher_no"), VType.valueOf(it.str("vtype")), it.date("vdate")!!, it.str("narration"), lines, it.strOrNull("source_bill_id"), it.getBoolean("deleted"))
        }
    }

    private fun audit(c: Connection, action: String, voucherId: String, voucherNo: String, before: JSONObject?, after: JSONObject?) {
        c.update(
            "insert into acc_audit (action, voucher_id, voucher_no, before, after) values (?, ?, ?, ?, ?)",
            action, UUID.fromString(voucherId), voucherNo, before?.let { com.ota.desktop.db.Json(it.toString()) }, after?.let { com.ota.desktop.db.Json(it.toString()) }
        )
    }

    private fun json(v: Voucher, accounts: Map<String, Account>) = JSONObject()
        .put("no", v.no).put("type", v.type.name).put("date", v.date.toString()).put("narration", v.narration)
        .put("total", Paise.decimal(v.total).toPlainString())
        .put("lines", JSONArray(v.lines.map { l ->
            JSONObject().put("account", accounts[l.accountId]?.name ?: l.accountId).put("account_id", l.accountId)
                .put("dr", Paise.decimal(l.dr).toPlainString()).put("cr", Paise.decimal(l.cr).toPlainString()).put("note", l.note)
        }))

    private fun describe(doc: JSONObject): String {
        val date = runCatching { LocalDate.parse(doc.optString("date")).format(DMY) }.getOrDefault("")
        val lines = doc.optJSONArray("lines")?.let { arr ->
            (0 until arr.length()).joinToString("; ") { i ->
                val l = arr.getJSONObject(i)
                val dr = l.optString("dr").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val side = if (dr.signum() > 0) "Dr" else "Cr"
                val amt = if (dr.signum() > 0) dr else l.optString("cr").toBigDecimalOrNull() ?: BigDecimal.ZERO
                "$side ${l.optString("account")} ₹${Paise.format(Paise.of(amt))}"
            }
        }.orEmpty()
        return listOf(date, doc.optString("narration"), lines).filter { it.isNotBlank() }.joinToString(" · ")
    }

    companion object {
        const val COMMISSION = "commission"
        val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

        private const val ACC = "id, code, name, grp, subgroup, cash_bank, party_type, party_key, commission_percent, opening_balance, system_key, active"
        private const val ACC_ORDER = "order by case grp when 'ASSET' then 1 when 'LIABILITY' then 2 when 'EQUITY' then 3 when 'INCOME' then 4 else 5 end, " +
            "party_type nulls first, code, lower(name)"

        fun pct(p: Double): String = BigDecimal.valueOf(p).stripTrailingZeros().toPlainString()

        internal fun ResultSet.toAccount() = Account(
            id = id(), code = str("code"), name = str("name"), group = AccGroup.valueOf(str("grp")), subgroup = str("subgroup"),
            cashBank = getBoolean("cash_bank"), party = strOrNull("party_type")?.let(PartyKind::valueOf), partyKey = strOrNull("party_key"),
            commissionPercent = dbl("commission_percent") ?: 0.0, opening = Paise.of(getBigDecimal("opening_balance")),
            systemKey = strOrNull("system_key"), active = getBoolean("active")
        )
    }
}
