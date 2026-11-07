package com.example.otaapplication.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PaymentRepositoryTest {
    private lateinit var db: OtaDatabase
    private lateinit var orders: OrderRepository
    private lateinit var payments: PaymentRepository
    private var now = 1_000L
    private val day = LocalDate.of(2026, 11, 8)

    @Before
    fun setUp() {
        db = OtaDatabase(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
        orders = OrderRepository(db) { now }
        payments = PaymentRepository(db) { now }
        orders.reconcile(listOf(
            entry("a", "120261101001", "01-11-2026", "Ram Textiles\nIndore", "Raymond\nMumbai", 0),
            entry("b", "120261102001", "02-11-2026", "ram  textiles", "RAYMOND", 1),
            entry("c", "120261103001", "03-11-2026", "Shyam Garments", "Raymond", 2),
            entry("d", "120261104001", "04-11-2026", "Ram Textiles", "Mafatlal", 3)
        ))
    }

    @After
    fun tearDown() = db.close()

    private fun entry(uuid: String, offerNo: String, date: String, buyer: String, supplier: String, seq: Int) =
        HistoryIndexEntry(uuid, uuid, offerNo, date, buyer, supplier, "Shirting", "/sync/$uuid.json", "Order_$seq.pdf", seq)

    private fun draft(lines: List<Pair<String, LineAmounts>>, id: String? = null, mode: PaymentMode = PaymentMode.CHEQUE, reference: String = "000123") =
        AdviceDraft(id, day, "Ram Textiles", "Raymond", mode, reference, 5_000.0, BalanceSide.DR, "", lines)

    private fun pending(table: String, id: String) =
        db.readableDatabase.rawQuery("SELECT sync_pending FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun balancesStatusAndLinksBothWays() {
        val inv1 = orders.attachBill(listOf("a", "b"), "INV 1", BillDetails(day, 10_000.0, lrNo = "LR 77", cdPercent = 2.0, cdAmount = 200.0))
        val inv2 = orders.attachBill(listOf("a"), "INV 2", BillDetails(day, 5_000.0))
        val numberOnly = orders.attachBill(listOf("b"), "INV 3")
        assertEquals(PaymentStatus.UNPAID, orders.bill(inv1.id)!!.status)
        assertEquals("LR 77", orders.bill(inv1.id)!!.lrNo)

        val first = payments.save(draft(listOf(
            inv1.id to LineAmounts(paid = 6_000.0, cdAmount = 200.0),
            inv2.id to LineAmounts(paid = 5_000.0)
        )), newNumber = "PA120261108001")
        assertEquals(2, first.lines.size)
        assertEquals(11_200.0, first.totals.settled, 0.0)

        orders.bill(inv1.id)!!.let {
            assertEquals(6_200.0, it.settled, 0.0)
            assertEquals(3_800.0, it.balance!!, 0.0)
            assertEquals(PaymentStatus.PARTLY_PAID, it.status)
            assertEquals(1, it.adviceCount)
        }
        assertEquals(PaymentStatus.PAID, orders.bill(inv2.id)!!.status)

        // A second advice on the same bill settles the rest.
        now = 2_000
        payments.save(draft(listOf(inv1.id to LineAmounts(paid = 3_300.0, goodsReturn = 500.0)), mode = PaymentMode.CASH, reference = ""), newNumber = "PA120261108002")
        assertEquals(PaymentStatus.PAID, orders.bill(inv1.id)!!.status)
        assertEquals(listOf("PA120261108001", "PA120261108002"), payments.advicesForBill(inv1.id).map { it.adviceNo })

        // Number-only bill keeps working and shows up in the Bills list.
        assertNull(orders.bill(numberOnly.id)!!.balance)
        assertEquals(listOf("INV 3"), payments.bills(BillQuery(status = PaymentStatus.UNPAID)).map { it.bill.billNo })
        assertEquals(setOf("INV 1", "INV 2"), payments.bills(BillQuery(status = PaymentStatus.PAID)).map { it.bill.billNo }.toSet())
        payments.bills(BillQuery(text = "lr 77")).single().let {
            assertEquals("INV 1", it.bill.billNo)
            assertEquals("Ram Textiles", it.buyer)
            assertEquals("Raymond", it.supplier)
        }
    }

    @Test
    fun searchByNumberPartiesReferenceAndBill() {
        val inv = orders.attachBill(listOf("a"), "INV 42", BillDetails(day, 1_000.0))
        payments.save(draft(listOf(inv.id to LineAmounts(paid = 1_000.0)), reference = "UTR998877"), newNumber = "PA120261108001")
        listOf("PA1202611", "ram tex", "raymond", "998877", "inv 42", "cheque").forEach { text ->
            assertEquals(text, listOf("PA120261108001"), payments.search(text).map { it.adviceNo })
        }
        assertTrue(payments.search("shyam").isEmpty())
        assertEquals(listOf("INV 42"), payments.search("").single().billNos)
    }

    @Test
    fun pairBillsMatchesBuyerAndSupplierAndFlagsMixedParties() {
        val ramOnly = orders.attachBill(listOf("a"), "R1", BillDetails(day, 1_000.0))
        val mixed = orders.attachBill(listOf("b", "c"), "M1", BillDetails(day, 2_000.0))
        orders.attachBill(listOf("c"), "S1", BillDetails(day, 3_000.0)) // Shyam → Raymond
        orders.attachBill(listOf("d"), "X1", BillDetails(day, 4_000.0)) // Ram → Mafatlal

        val bills = payments.pairBills("Ram Textiles\nIndore", "raymond")
        assertEquals(setOf("R1", "M1"), bills.map { it.bill.billNo }.toSet())
        assertFalse(bills.single { it.bill.id == ramOnly.id }.mixedParties)
        assertTrue(bills.single { it.bill.id == mixed.id }.mixedParties)
        assertTrue(payments.pairBills("", "Raymond").isEmpty())

        // While editing an advice, its own lines don't count as settled.
        val advice = payments.save(draft(listOf(ramOnly.id to LineAmounts(paid = 400.0))), newNumber = "PA120261108001")
        assertEquals(600.0, payments.pairBills("Ram Textiles", "Raymond").single { it.bill.id == ramOnly.id }.balanceBefore!!, 0.0)
        assertEquals(1_000.0, payments.pairBills("Ram Textiles", "Raymond", advice.id).single { it.bill.id == ramOnly.id }.balanceBefore!!, 0.0)
    }

    @Test
    fun editRemovesLinesAsTombstonesAndDeleteFreesTheBills() {
        val b1 = orders.attachBill(listOf("a"), "B1", BillDetails(day, 1_000.0))
        val b2 = orders.attachBill(listOf("a"), "B2", BillDetails(day, 1_000.0))
        val advice = payments.save(draft(listOf(b1.id to LineAmounts(paid = 1_000.0), b2.id to LineAmounts(paid = 1_000.0))), newNumber = "PA120261108001")
        db.writableDatabase.execSQL("UPDATE payment_advices SET sync_pending = 0")
        db.writableDatabase.execSQL("UPDATE advice_lines SET sync_pending = 0")
        db.writableDatabase.execSQL("UPDATE bills SET sync_pending = 0")

        now = 2_000
        val edited = payments.save(draft(listOf(b1.id to LineAmounts(paid = 700.0)), id = advice.id))
        assertEquals("PA120261108001", edited.adviceNo)
        assertEquals(listOf("B1"), edited.lines.map { it.bill.billNo })
        assertEquals(PaymentStatus.UNPAID, orders.bill(b2.id)!!.status)
        assertEquals(1, pending("advice_lines", SyncIds.adviceLine(advice.id, b2.id))) // tombstone to upload
        assertEquals(1, pending("bills", b1.id)) // re-uploaded with its line

        assertTrue(payments.delete(advice.id))
        assertNull(payments.advice(advice.id))
        assertEquals(PaymentStatus.UNPAID, orders.bill(b1.id)!!.status)
        assertTrue(payments.search("").isEmpty())
        // Deleted numbers stay used.
        assertEquals(listOf("PA120261108001"), payments.adviceNosWithPrefix("PA120261108"))
    }

    @Test
    fun linesWaitForTheirAdviceAndBillToUpload() {
        val b1 = orders.attachBill(listOf("a"), "B1", BillDetails(day, 1_000.0))
        val advice = payments.save(draft(listOf(b1.id to LineAmounts(paid = 500.0))), newNumber = "PA120261108001")
        assertEquals(2, payments.pendingCount())
        assertTrue(payments.pendingLines(10).isEmpty())
        payments.markSynced(PaymentRepository.ADVICES, payments.pendingAdvices(10).map { it.id to it.updatedAt })
        assertTrue(payments.pendingLines(10).isEmpty()) // bill not uploaded yet
        db.writableDatabase.execSQL("UPDATE bills SET sync_pending = 0")
        assertEquals(SyncIds.adviceLine(advice.id, b1.id), payments.pendingLines(10).single().id)
    }

    @Test
    fun remoteAdvicesAndLinesApplyWithLastWriteWins() {
        val b1 = orders.attachBill(listOf("a"), "B1", BillDetails(day, 1_000.0))
        val id = "11111111-1111-1111-1111-111111111111"
        val value = AdviceValue("PA220261108001", "2026-11-08", "Ram Textiles", "Raymond", "ONLINE", "UTR1", 300.0, "CR", "")
        assertEquals(1, payments.applyRemoteAdvices(listOf(RemoteRow(id, value, false, 5_000, "t1"))))
        val lineId = SyncIds.adviceLine(id, b1.id)
        // A line for an unknown bill is skipped; the known one applies.
        assertEquals(1, payments.applyRemoteLines(listOf(
            RemoteRow(lineId, AdviceLineValue(id, b1.id, LineAmounts(paid = 1_000.0)), false, 5_000, "t2"),
            RemoteRow(SyncIds.adviceLine(id, "nope"), AdviceLineValue(id, "nope", LineAmounts(paid = 1.0)), false, 5_000, "t3")
        )))
        assertEquals(PaymentStatus.PAID, orders.bill(b1.id)!!.status)
        payments.advice(id)!!.let {
            assertEquals(PaymentMode.ONLINE, it.mode)
            assertEquals(BalanceSide.CR, it.balanceSide)
        }
        assertEquals(0, payments.pendingCount())

        // An older remote edit loses to a newer local one.
        now = 9_000
        payments.save(AdviceDraft(id, day, "Ram Textiles", "Raymond", PaymentMode.ONLINE, "UTR2", null, null, "", listOf(b1.id to LineAmounts(paid = 1_000.0))))
        assertEquals(0, payments.applyRemoteAdvices(listOf(RemoteRow(id, value.copy(reference = "OLD"), false, 6_000, "t4"))))
        assertEquals("UTR2", payments.advice(id)!!.reference)

        // A remote delete newer than everything wins.
        payments.markSynced(PaymentRepository.ADVICES, listOf(id to 9_000L))
        assertEquals(1, payments.applyRemoteAdvices(listOf(RemoteRow(id, value, true, 10_000, "t5"))))
        assertNull(payments.advice(id))
    }
}
