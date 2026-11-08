package com.ota.desktop.data

import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.AdviceLine
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillPayment
import com.example.otaapplication.data.PairBill
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentMode
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.data.SyncIds
import com.ota.desktop.data.BillSql.toBill
import com.ota.desktop.db.Db
import com.ota.desktop.db.date
import com.ota.desktop.db.dbl
import com.ota.desktop.db.id
import com.ota.desktop.db.nextEditTime
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.strOrNull
import com.ota.desktop.db.update
import com.ota.desktop.db.uuidArray
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** A bill in the Bills list, with its own buyer and supplier or else those of its first order. */
data class BillListItem(val bill: Bill, val buyer: String, val supplier: String)

data class BillFilter(
    val text: String = "",
    val status: PaymentStatus? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val buyer: String = "",
    val supplier: String = ""
)

data class AdviceFilter(
    val text: String = "",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val buyer: String = "",
    val supplier: String = "",
    val staff: Int? = null
)

/** Payment advices and their lines; also the Bills list. Same rules as the phone's PaymentRepository. */
class PaymentStore(private val db: Db, private val numbers: Numbers, private val onChange: () -> Unit = {}) {

    /** Saves a new advice (numbered for [staffCode]) or replaces an existing one. */
    fun save(draft: AdviceDraft, staffCode: Int?): PaymentAdvice {
        require(draft.lines.isNotEmpty()) { "Add at least one bill" }
        require(draft.lines.map { it.first }.toSet().size == draft.lines.size) { "A bill can appear only once on an advice" }
        val existing = draft.id?.let { id -> db.read { c -> c.one("select advice_no from payment_advices where id = ?::uuid", id) { it.getString(1) } } }
        val id = if (existing != null) draft.id!! else UUID.randomUUID().toString()
        if (existing != null) {
            write(id, null, draft)
        } else {
            requireNotNull(staffCode) { "This account has no staff code yet, so it can't number new advices." }
            numbers.assign(Numbers.Kind.ADVICE, staffCode) { no -> write(id, no, draft) }
        }
        onChange()
        return advice(id)!!
    }

    private fun write(id: String, newNo: String?, draft: AdviceDraft) = db.tx { c ->
        val prev = c.one("select client_updated_at from payment_advices where id = ?::uuid", id) { it.getLong(1) }
        val now = nextEditTime(prev)
        val balance = draft.balanceAmount?.let(PaymentMath::round2)
        val side = draft.balanceAmount?.let { draft.balanceSide?.name }
        if (newNo == null) {
            c.update(
                "update payment_advices set pay_date = ?, buyer = ?, supplier = ?, mode = ?, reference = ?, balance = ?, balance_side = ?, remarks = ?, " +
                    "deleted = false, client_updated_at = ?, sync_pending = true where id = ?::uuid",
                draft.payDate, SearchText.firstLine(draft.buyer), SearchText.firstLine(draft.supplier), draft.mode.name, draft.reference.trim(),
                balance, side, draft.remarks.trim(), now, id
            )
        } else {
            c.update(
                "insert into payment_advices (id, advice_no, pay_date, buyer, supplier, mode, reference, balance, balance_side, remarks, deleted, client_updated_at, sync_pending) " +
                    "values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, true)",
                id, newNo, draft.payDate, SearchText.firstLine(draft.buyer), SearchText.firstLine(draft.supplier), draft.mode.name, draft.reference.trim(),
                balance, side, draft.remarks.trim(), now
            )
        }
        val live = c.query("select bill_id from advice_lines where advice_id = ?::uuid and not deleted", id) { it.id("bill_id") }.toMutableSet()
        for ((billId, a) in draft.lines) {
            val known = c.one("select 1 from bills where id = ?::uuid and not deleted", billId) { true } ?: false
            require(known) { "A bill on this advice no longer exists" }
            val lineId = SyncIds.adviceLine(id, billId)
            val lineAt = nextEditTime(c.one("select client_updated_at from advice_lines where id = ?::uuid", lineId) { it.getLong(1) })
            c.update(
                "insert into advice_lines (id, advice_id, bill_id, paid, cd_percent, cd_amount, goods_return, rate_diff, claim, claim_note, deleted, client_updated_at, sync_pending) " +
                    "values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, false, ?, true) on conflict (id) do update set paid = excluded.paid, " +
                    "cd_percent = excluded.cd_percent, cd_amount = excluded.cd_amount, goods_return = excluded.goods_return, rate_diff = excluded.rate_diff, " +
                    "claim = excluded.claim, claim_note = excluded.claim_note, " +
                    "deleted = false, client_updated_at = excluded.client_updated_at, sync_pending = true",
                lineId, id, billId, PaymentMath.round2(a.paid), a.cdPercent, a.cdAmount?.let(PaymentMath::round2),
                a.goodsReturn?.let(PaymentMath::round2), a.rateDiff?.let(PaymentMath::round2), a.claim?.let(PaymentMath::round2),
                a.claimNote.trim().takeIf { a.claim != null }.orEmpty(), lineAt
            )
            live -= billId
            // Re-upload the bill too: the cloud may have purged it since it was last sent.
            c.update("update bills set sync_pending = true where id = ?::uuid", billId)
        }
        for (removed in live) {
            c.update(
                "update advice_lines set deleted = true, client_updated_at = greatest(client_updated_at + 1, ?), sync_pending = true where id = ?::uuid",
                System.currentTimeMillis(), SyncIds.adviceLine(id, removed)
            )
        }
    }

    fun delete(adviceId: String): Boolean {
        val deleted = db.tx { c ->
            val now = System.currentTimeMillis()
            val n = c.update(
                "update payment_advices set deleted = true, client_updated_at = greatest(client_updated_at + 1, ?), sync_pending = true where id = ?::uuid and not deleted",
                now, adviceId
            )
            c.update(
                "update advice_lines set deleted = true, client_updated_at = greatest(client_updated_at + 1, ?), sync_pending = true where advice_id = ?::uuid and not deleted",
                now, adviceId
            )
            n > 0
        }
        if (deleted) onChange()
        return deleted
    }

    fun advice(adviceId: String): PaymentAdvice? = db.read { c ->
        val lines = c.query(
            """select ${BillSql.COLUMNS}, l.id as line_id, l.paid, l.cd_percent as l_cdp, l.cd_amount as l_cda, l.goods_return, l.rate_diff, l.claim, l.claim_note
               from advice_lines l join bills b on b.id = l.bill_id
               where l.advice_id = ?::uuid and not l.deleted order by b.bill_date nulls last, b.bill_no""",
            adviceId
        ) { AdviceLine(it.id("line_id"), it.toBill(), LocalSyncTable.lineAmounts(it, cdPercent = "l_cdp", cdAmount = "l_cda")) }
        c.one("select * from payment_advices where id = ?::uuid and not deleted", adviceId) {
            PaymentAdvice(
                id = adviceId, adviceNo = it.str("advice_no"), payDate = it.date("pay_date") ?: LocalDate.now(),
                buyer = it.str("buyer"), supplier = it.str("supplier"), mode = PaymentMode.parse(it.str("mode")), reference = it.str("reference"),
                balanceAmount = it.dbl("balance"), balanceSide = BalanceSide.parse(it.strOrNull("balance_side")), remarks = it.str("remarks"), lines = lines
            )
        }
    }

    /** Advices matching every word (number, parties, mode, cheque/txn, remarks, bill numbers), newest first. */
    fun search(filter: AdviceFilter = AdviceFilter()): List<AdviceSummary> {
        val where = StringBuilder("not a.deleted")
        val args = ArrayList<Any?>()
        for (token in SearchText.tokens(filter.text)) {
            where.append(
                """ and (lower(a.advice_no || ' ' || a.buyer || ' ' || a.supplier || ' ' || a.mode || ' ' || a.reference || ' ' || a.remarks) like ? escape '\'
                     or exists (select 1 from advice_lines sl join bills sb on sb.id = sl.bill_id where sl.advice_id = a.id and not sl.deleted and lower(sb.bill_no) like ? escape '\'))"""
            )
            val p = SearchText.containsPattern(token); args += p; args += p
        }
        filter.from?.let { where.append(" and a.pay_date >= ?"); args += it }
        filter.to?.let { where.append(" and a.pay_date <= ?"); args += it }
        if (filter.buyer.isNotBlank()) { where.append(" and ota_party_key(a.buyer) = ?"); args += SearchText.partyKey(filter.buyer) }
        if (filter.supplier.isNotBlank()) { where.append(" and ota_party_key(a.supplier) = ?"); args += SearchText.partyKey(filter.supplier) }
        filter.staff?.let { where.append(" and substr(a.advice_no, 3, 1) = ?"); args += it.toString() }
        return db.read { c ->
            c.query(
                """select a.id, a.advice_no, a.pay_date, a.buyer, a.supplier, a.mode, a.reference,
                     (select coalesce(sum(l.paid), 0) from advice_lines l where l.advice_id = a.id and not l.deleted) as paid,
                     (select string_agg(b.bill_no, E'\u001f') from advice_lines l join bills b on b.id = l.bill_id where l.advice_id = a.id and not l.deleted) as bill_nos
                   from payment_advices a where $where order by a.pay_date desc nulls last, a.advice_no desc""",
                *args.toTypedArray()
            ) {
                AdviceSummary(
                    it.id(), it.str("advice_no"), it.date("pay_date"), it.str("buyer"), it.str("supplier"), PaymentMode.parse(it.str("mode")),
                    it.str("reference"), PaymentMath.round2(it.dbl("paid") ?: 0.0),
                    it.strOrNull("bill_nos")?.split('\u001f')?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()
                )
            }
        }
    }

    fun advicesForBill(billId: String): List<BillPayment> = db.read { c ->
        c.query(
            """select a.id, a.advice_no, a.pay_date, l.paid, l.cd_percent, l.cd_amount, l.goods_return, l.rate_diff, l.claim, l.claim_note
               from advice_lines l join payment_advices a on a.id = l.advice_id
               where l.bill_id = ?::uuid and not l.deleted and not a.deleted order by a.pay_date, a.advice_no""",
            billId
        ) { BillPayment(it.id(), it.str("advice_no"), it.date("pay_date"), LocalSyncTable.lineAmounts(it)) }
    }

    /**
     * Bills that can go on an advice from [buyer] to [supplier]: bills with at least one live order
     * for that pair, or naming that pair themselves (bills added without orders; matched by name).
     * Bills spanning several pairs are flagged as mixed.
     */
    fun pairBills(buyer: String, supplier: String, excludeAdviceId: String? = null): List<PairBill> {
        val buyerKey = SearchText.partyKey(buyer)
        val supplierKey = SearchText.partyKey(supplier)
        if (buyerKey.isEmpty() || supplierKey.isEmpty()) return emptyList()
        return db.read { c ->
            val pairs = HashMap<String, MutableSet<Pair<String, String>>>()
            c.query(
                """select l.bill_id, ota_party_key(o.buyer) as bk, ota_party_key(o.supplier) as sk
                   from order_bills l join orders o on o.id = l.order_id join bills b on b.id = l.bill_id
                   where not l.deleted and not o.deleted and not b.deleted"""
            ) { pairs.getOrPut(it.id("bill_id")) { HashSet() } += it.str("bk") to it.str("sk") }
            c.query(
                "select id, ota_party_key(buyer) as bk, ota_party_key(supplier) as sk from bills where not deleted and buyer <> '' and supplier <> ''"
            ) { pairs.getOrPut(it.id("id")) { HashSet() } += it.str("bk") to it.str("sk") }
            val eligible = pairs.filterValues { (buyerKey to supplierKey) in it }
            if (eligible.isEmpty()) return@read emptyList()
            c.query(
                "select ${BillSql.COLUMNS}, ${BillSql.SETTLED_EXCLUDING} from bills b where b.id = any(?)",
                excludeAdviceId.orEmpty(), uuidArray(eligible.keys)
            ) { PairBill(it.toBill(), PaymentMath.round2(it.dbl("settled_elsewhere") ?: 0.0), eligible.getValue(it.id("id")).size > 1) }
                .sortedWith(compareBy<PairBill>({ it.bill.billDate == null }, { it.bill.billDate }, { it.bill.billNo }))
        }
    }

    /** Bills list: bill/LR number, buyer, supplier or order number; newest first. */
    fun bills(filter: BillFilter = BillFilter()): List<BillListItem> {
        val where = StringBuilder("not b.deleted")
        val args = ArrayList<Any?>()
        for (token in SearchText.tokens(filter.text)) {
            where.append(
                """ and (lower(b.bill_no || ' ' || b.lr_no || ' ' || b.buyer || ' ' || b.supplier || ' ' || b.items::text) like ? escape '\' or exists (
                     select 1 from order_bills sl join orders so on so.id = sl.order_id
                     where sl.bill_id = b.id and not sl.deleted and not so.deleted
                       and lower(so.order_no || ' ' || so.buyer || ' ' || so.supplier || ' ' || so.items::text) like ? escape '\'))"""
            )
            val p = SearchText.containsPattern(token); repeat(2) { args += p }
        }
        filter.from?.let { where.append(" and b.bill_date >= ?"); args += it }
        filter.to?.let { where.append(" and b.bill_date <= ?"); args += it }
        val party = """from order_bills pl join orders po on po.id = pl.order_id
            where pl.bill_id = b.id and not pl.deleted and not po.deleted order by po.order_day nulls last, po.order_no limit 1"""
        val rows = db.read { c ->
            c.query(
                "select ${BillSql.COLUMNS}, (select po.buyer $party) as p_buyer, (select po.supplier $party) as p_supplier from bills b " +
                    "where $where order by b.bill_date desc nulls last, b.bill_no",
                *args.toTypedArray()
            ) {
                val bill = it.toBill()
                // Parties typed on the bill win; otherwise those of its first order.
                val own = bill.buyer.isNotBlank() && bill.supplier.isNotBlank()
                BillListItem(
                    bill,
                    SearchText.firstLine(if (own) bill.buyer else it.str("p_buyer")),
                    SearchText.firstLine(if (own) bill.supplier else it.str("p_supplier"))
                )
            }
        }
        val bk = SearchText.partyKey(filter.buyer)
        val sk = SearchText.partyKey(filter.supplier)
        return rows.filter { item ->
            (filter.status == null || item.bill.status == filter.status) &&
                (bk.isEmpty() || SearchText.partyKey(item.buyer) == bk) &&
                (sk.isEmpty() || SearchText.partyKey(item.supplier) == sk)
        }
    }

    /** Gives an advice a new number after the cloud reported its number as taken. */
    fun renumber(id: String, adviceNo: String): Boolean = db.tx { c ->
        val prev = c.one("select client_updated_at from payment_advices where id = ?::uuid", id) { it.getLong(1) } ?: return@tx false
        c.update("update payment_advices set advice_no = ?, client_updated_at = ?, sync_pending = true where id = ?::uuid", adviceNo, nextEditTime(prev), id) > 0
    }.also { if (it) onChange() }
}
