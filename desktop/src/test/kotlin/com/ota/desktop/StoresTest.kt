package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.BillValue
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.PaymentMode
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.RemoteRow
import com.ota.desktop.data.AdviceFilter
import com.ota.desktop.data.LocalSyncTable
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderFilter
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Kv
import com.ota.desktop.db.one
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    private val day = LocalDate.of(2026, 11, 10)
    private lateinit var db: com.ota.desktop.db.Db
    private lateinit var numbers: Numbers
    private lateinit var orders: OrderStore
    private lateinit var payments: PaymentStore

    @BeforeTest
    fun setUp() {
        db = TestDb.fresh()
        numbers = Numbers(db, Kv(db)) { day }
        orders = OrderStore(db)
        payments = PaymentStore(db, numbers)
    }

    private fun order(buyer: String = "Ram Traders\nSurat", supplier: String = "Shiv Mills", qty: String = "100") = OrderFormData(
        date = "10-11-2026", buyer = buyer, supplier = supplier,
        items = listOf(OrderItem("1", "Cotton shirting", qty, "120", "M"), OrderItem("2", "Suiting", "5", "900", "T"))
    )

    private var serial = 0
    private fun numbered(data: OrderFormData = order()) = orders.create(data, OrderNumber.formatFy(day, ++serial))

    @Test
    fun ordersKeepTheirFyNumberOrWaitForOne() {
        val a = numbered()
        val b = numbered()
        assertEquals("26270001", a.orderNo)
        assertEquals("26270002", b.orderNo)
        assertFailsWith<IllegalArgumentException> { orders.create(order(), "26270001") }
        val p1 = orders.create(order(), "")
        val p2 = orders.create(order(), "")
        assertTrue(OrderNumber.isPending(p1.orderNo) && OrderNumber.isPending(p2.orderNo))
        assertEquals(listOf("M", "T"), a.data.items.map { it.unit })
        assertEquals(day, a.day)

        assertEquals(4, orders.list(OrderFilter(buyer = "ram traders")).size)
        assertEquals(1, orders.list(OrderFilter(text = "26270002")).size)
        assertEquals(4, orders.list(OrderFilter(text = "suiting")).size)

        // A pending order can't get a bill until it has its number.
        assertFailsWith<IllegalArgumentException> { orders.attachBill(listOf(p1.id), "X1") }
        assertEquals(listOf(p1.id, p2.id), orders.numberPending().map { it.first })
        assertTrue(orders.assignNumber(p1.id, "26270003"))
        assertFalse(orders.assignNumber(p1.id, "26270004"), "a number is given once")
        assertEquals("26270003", orders.get(p1.id)!!.orderNo)
        orders.attachBill(listOf(p1.id), "X1")

        orders.update(a.id, a.data.copy(remarks = "urgent"))
        assertEquals("urgent", orders.get(a.id)!!.data.remarks)
        assertEquals("26270001", orders.get(a.id)!!.orderNo)
        orders.delete(b.id)
        assertNull(orders.get(b.id))
    }

    @Test
    fun standaloneBillsCarryTheirOwnPartiesAndLines() {
        val lines = listOf(OrderItem("1", "Cotton shirting", "25", "120", "M"), OrderItem("2", "Suiting", "1", "900", "T"))
        assertFailsWith<IllegalArgumentException> { orders.saveBill(null, "SB-1", BillDetails(amount = 3900.0), emptyList()) }
        val bill = orders.saveBill(null, " sb-1 ", BillDetails(amount = 3900.0, items = lines, buyer = "Ram Traders\nSurat", supplier = "Shiv Mills"), emptyList())
        assertEquals("sb-1", bill.billNo)
        assertEquals(0, bill.orderCount)
        assertEquals("Ram Traders\nSurat", bill.buyer)
        assertEquals(lines.map { it.copy(key = 0L) }, bill.items.map { it.copy(key = 0L) })
        assertFailsWith<IllegalArgumentException>("numbers stay unique") {
            orders.saveBill(null, " SB-1", BillDetails(buyer = "x", supplier = "y"), emptyList())
        }

        // Paid through an advice even with no orders; listed with its own parties.
        val pair = payments.pairBills("ram traders", "Shiv Mills")
        assertEquals(listOf("sb-1"), pair.map { it.bill.billNo })
        assertEquals("Ram Traders", payments.bills(com.ota.desktop.data.BillFilter(text = "shirting")).single().buyer)

        // Edit: link an order; the stored parties stay.
        val o = numbered()
        val edited = orders.saveBill(bill.id, "ignored", BillDetails(amount = 4000.0, items = lines, buyer = "Ram Traders\nSurat", supplier = "Shiv Mills"), listOf(o.id))
        assertEquals(4000.0, edited.amount)
        assertEquals(1, edited.orderCount)
        assertEquals("sb-1", edited.billNo)
        // Detaching its only order doesn't retire a bill with parties.
        orders.detachBill(o.id, bill.id)
        assertNotNull(orders.bill(bill.id))

        // Claim counts towards settled; a bill on an advice can't be deleted.
        val adv = payments.save(
            AdviceDraft(
                payDate = day, buyer = "Ram Traders", supplier = "Shiv Mills", mode = PaymentMode.CASH, reference = "", balanceAmount = null,
                balanceSide = null, remarks = "", lines = listOf(bill.id to LineAmounts(paid = 3800.0, claim = 200.0, claimNote = "shade variation"))
            ),
            3
        )
        assertEquals(4000.0, orders.bill(bill.id)!!.settled)
        assertEquals(PaymentStatus.PAID, orders.bill(bill.id)!!.status)
        assertEquals("shade variation", payments.advice(adv.id)!!.lines.single().amounts.claimNote)
        assertFailsWith<IllegalArgumentException> { orders.deleteBill(bill.id) }
        payments.delete(adv.id)
        orders.deleteBill(bill.id)
        assertNull(orders.billByNumber("SB-1"))
        assertTrue(payments.bills().none { it.bill.id == bill.id })
        // Its number is free again.
        assertNotNull(orders.saveBill(null, "SB-1", BillDetails(buyer = "a", supplier = "b"), emptyList()))
    }

    @Test
    fun billsAttachToManyOrdersAndRetireWhenOrphaned() {
        val a = numbered()
        val b = numbered()
        val bill = orders.attachBill(listOf(a.id, b.id), " inv  77 ", BillDetails(amount = 1000.0, lrNo = "LR9"))
        assertEquals("inv 77", bill.billNo)
        assertEquals(2, bill.orderCount)
        assertEquals(1000.0, bill.amount)
        assertEquals(listOf("inv 77"), orders.get(a.id)!!.billNos)

        // Same number again (any case/spacing) reuses the bill; only given details change.
        val again = orders.attachBill(listOf(a.id), "INV 77", BillDetails(billDate = day))
        assertEquals(bill.id, again.id)
        assertEquals(1000.0, again.amount)
        assertEquals(day, again.billDate)
        assertEquals(2, orders.ordersForBill(bill.id).size)

        orders.detachBill(a.id, bill.id)
        assertNotNull(orders.bill(bill.id))
        assertEquals(1, orders.bill(bill.id)!!.orderCount)
        orders.detachBill(b.id, bill.id)
        assertNull(orders.billByNumber("inv 77"))

        // Re-attaching revives the same bill and link ids.
        val revived = orders.attachBill(listOf(a.id), "Inv 77")
        assertEquals(bill.id, revived.id)
        assertEquals(1, revived.orderCount)
    }

    @Test
    fun advicesSettleBillsAndAreSearchable() {
        val a = numbered()
        val other = numbered(order(buyer = "Other Buyer"))
        val bill = orders.attachBill(listOf(a.id), "B1", BillDetails(amount = 1000.0))
        orders.attachBill(listOf(other.id), "B2", BillDetails(amount = 500.0))

        val pair = payments.pairBills("Ram Traders", "shiv  mills")
        assertEquals(listOf("B1"), pair.map { it.bill.billNo })
        assertFalse(pair.single().mixedParties)

        val draft = AdviceDraft(
            payDate = day, buyer = "Ram Traders\nSurat", supplier = "Shiv Mills", mode = PaymentMode.CHEQUE, reference = " 004512 ",
            balanceAmount = 200.0, balanceSide = BalanceSide.DR, remarks = "",
            lines = listOf(bill.id to LineAmounts(paid = 700.0, cdPercent = 2.0, cdAmount = 20.0, goodsReturn = 30.0))
        )
        val adv = payments.save(draft, 3)
        assertEquals("PA320261110001", adv.adviceNo)
        assertEquals("Ram Traders", adv.buyer)
        assertEquals("004512", adv.reference)
        val settled = orders.bill(bill.id)!!
        assertEquals(750.0, settled.settled)
        assertEquals(PaymentStatus.PARTLY_PAID, settled.status)
        assertEquals(1, payments.advicesForBill(bill.id).size)

        assertEquals(1, payments.search(AdviceFilter(text = "b1")).size)
        assertEquals(1, payments.search(AdviceFilter(text = "004512")).size)
        assertEquals(0, payments.search(AdviceFilter(text = "zzz")).size)
        assertEquals(1, payments.search(AdviceFilter(staff = 3)).size)

        // Editing the advice: settledElsewhere excludes it, a second advice settles the rest.
        assertEquals(0.0, payments.pairBills("Ram Traders", "Shiv Mills", adv.id).single().settledElsewhere)
        val second = payments.save(draft.copy(lines = listOf(bill.id to LineAmounts(paid = 250.0))), 3)
        assertEquals("PA320261110002", second.adviceNo)
        assertEquals(PaymentStatus.PAID, orders.bill(bill.id)!!.status)
        assertEquals(1, payments.bills().count { it.bill.status == PaymentStatus.PAID })

        assertTrue(payments.delete(second.id))
        assertEquals(PaymentStatus.PARTLY_PAID, orders.bill(bill.id)!!.status)
        assertEquals("Ram Traders", payments.bills(com.ota.desktop.data.BillFilter(text = "b1")).single().buyer)
    }

    @Test
    fun pulledRowsMergeAndArchiveOnlyCloudVersions() {
        val t = LocalSyncTable.BILLS
        val id = "6b7f8f8c-0a42-4a7e-9d1b-1a2b3c4d5e6f"
        val v1 = RemoteRow(id, BillValue("X1", "2026-11-01", 10.0), false, 1000, "2026-11-10T10:00:00.000001+00:00")
        assertEquals(1, t.applyPulled(db, listOf(v1)))
        assertEquals(0, t.applyPulled(db, listOf(v1)), "unchanged row is a no-op")
        assertEquals(listOf(id to v1.serverUpdatedAt), t.toArchive(db, 10))
        t.markArchived(db, t.toArchive(db, 10))
        assertTrue(t.toArchive(db, 10).isEmpty())

        // A newer local edit wins over an older cloud version and blocks archiving until pushed.
        orders.saveBill(id, "X1", BillDetails(amount = 20.0, buyer = "B", supplier = "S"), emptyList())
        assertEquals(0, t.applyPulled(db, listOf(v1.copy(clientUpdatedAt = 999, serverUpdatedAt = "2026-11-10T11:00:00+00:00"))))
        assertEquals(20.0, orders.bill(id)!!.amount)
        assertTrue(t.toArchive(db, 10).isEmpty())
        val pending = t.pending(db, 10)
        assertEquals(1, pending.size)
        t.markPushed(db, pending)
        assertEquals(0, db.read { c -> t.pendingCount(c) })
        assertTrue(t.toArchive(db, 10).isEmpty(), "pushed version is archived only after it is pulled back")

        // A cloud tombstone keeps the content and marks it deleted.
        val dead = v1.copy(deleted = true, clientUpdatedAt = System.currentTimeMillis() + 10_000, serverUpdatedAt = "2026-11-10T12:00:00+00:00")
        assertEquals(1, t.applyPulled(db, listOf(dead)))
        assertEquals(true, db.read { c -> c.one("select deleted from bills where id = ?::uuid", id) { it.getBoolean(1) } })
        assertEquals(20.0, orders.bill(id)!!.amount)
    }
}
