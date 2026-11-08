package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
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
        orders = OrderStore(db, numbers)
        payments = PaymentStore(db, numbers)
    }

    private fun order(buyer: String = "Ram Traders\nSurat", supplier: String = "Shiv Mills", qty: String = "100") = OrderFormData(
        date = "10-11-2026", buyer = buyer, supplier = supplier,
        items = listOf(OrderItem("1", "Cotton shirting", qty, "120", "M"), OrderItem("2", "Suiting", "5", "900", "T"))
    )

    @Test
    fun ordersAreNumberedPerStaffAndDay() {
        val a = orders.create(order(), 3)
        val b = orders.create(order(), 3)
        val c = orders.create(order(), 1)
        assertEquals("320261110001", a.orderNo)
        assertEquals("320261110002", b.orderNo)
        assertEquals("120261110001", c.orderNo)
        assertEquals(listOf("M", "T"), a.data.items.map { it.unit })
        assertEquals(day, a.day)

        assertEquals(2, orders.list(OrderFilter(staff = 3)).size)
        assertEquals(3, orders.list(OrderFilter(buyer = "ram traders")).size)
        assertEquals(1, orders.list(OrderFilter(text = "320261110002")).size)
        assertEquals(3, orders.list(OrderFilter(text = "suiting")).size)

        orders.update(a.id, a.data.copy(remarks = "urgent"))
        assertEquals("urgent", orders.get(a.id)!!.data.remarks)
        assertEquals("320261110001", orders.get(a.id)!!.orderNo)
        orders.delete(b.id)
        assertNull(orders.get(b.id))
        // A deleted number is never reused.
        assertEquals("320261110003", orders.create(order(), 3).orderNo)
    }

    @Test
    fun billsAttachToManyOrdersAndRetireWhenOrphaned() {
        val a = orders.create(order(), 3)
        val b = orders.create(order(), 3)
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
        val a = orders.create(order(), 3)
        val other = orders.create(order(buyer = "Other Buyer"), 3)
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
        orders.updateBillDetails(id, BillDetails(amount = 20.0))
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
