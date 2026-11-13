package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.BillDetails
import com.ota.desktop.accounts.AccGroup
import com.ota.desktop.accounts.Account
import com.ota.desktop.accounts.AccountReports
import com.ota.desktop.accounts.AccountsError
import com.ota.desktop.accounts.AccountsExport
import com.ota.desktop.accounts.AccountsExporter
import com.ota.desktop.accounts.AccountsStore
import com.ota.desktop.accounts.ExportFormat
import com.ota.desktop.accounts.ExportLayout
import com.ota.desktop.accounts.ExportRequest
import com.ota.desktop.accounts.ExportSheet
import com.ota.desktop.accounts.ReportFilter
import com.ota.desktop.accounts.Side
import com.ota.desktop.accounts.ColKind
import com.ota.desktop.accounts.PartyKind
import com.ota.desktop.accounts.Paise
import com.ota.desktop.accounts.ReportKind
import com.ota.desktop.accounts.RowStyle
import com.ota.desktop.accounts.VType
import com.ota.desktop.accounts.VoucherDraft
import com.ota.desktop.accounts.VoucherLine
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.exec
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.example.otaapplication.data.PaymentStatus
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountsTest {
    private val day = LocalDate.of(2026, 11, 10)
    private lateinit var db: Db
    private lateinit var orders: OrderStore
    private lateinit var payments: PaymentStore
    private lateinit var books: AccountsStore
    private lateinit var reports: AccountReports
    private var changes = 0

    @BeforeTest
    fun setUp() {
        db = TestDb.fresh()
        orders = OrderStore(db)
        payments = PaymentStore(db)
        changes = 0
        books = AccountsStore(db) { changes++ }
        reports = AccountReports(db, books, payments)
    }

    private fun bill(no: String, amount: Double, supplier: String = "Shiv Mills", buyer: String = "Ram Traders", date: LocalDate = day) =
        orders.saveBill(null, no, BillDetails(billDate = date, amount = amount, buyer = buyer, supplier = supplier), emptyList())

    private fun supplier(name: String) = assertNotNull(books.partyAccount(PartyKind.SUPPLIER, name), "ledger for $name")
    private fun sys(key: String) = assertNotNull(books.accountBySystemKey(key))
    private fun commissionVouchers() = db.read { c -> c.one("select count(*) from acc_vouchers where source_bill_id is not null and not deleted") { it.getInt(1) } }!!
    private fun allCommissionRows() = db.read { c -> c.one("select count(*) from acc_vouchers where vtype = 'COMMISSION'") { it.getInt(1) } }!!

    @Test
    fun defaultChartIsSeeded() {
        val accounts = books.accounts()
        assertEquals(20, accounts.size)
        assertEquals(AccGroup.INCOME, sys("commission").group)
        assertTrue(sys("cash").cashBank && sys("bank").cashBank)
        // Seeding is idempotent: running the accounts migration again changes nothing.
        db.tx { c -> c.exec(Migrations::class.java.getResource("/db/V4__accounts.sql")!!.readText()) }
        assertEquals(accounts, books.accounts())
    }

    @Test
    fun commissionFollowsBillsAndNeverDuplicates() {
        val b = bill("SB-1", 3900.0)
        bill("SB-2", 10_000.0, supplier = "  shiv   MILLS ")
        bill("OT-1", 5000.0, supplier = "Other Mills")
        books.reconcileCommission(day)
        // Parties are opened from bills; names differing only in case and spacing share one ledger.
        assertEquals(2, books.parties(PartyKind.SUPPLIER).size)
        assertEquals(0, commissionVouchers(), "no % set yet")

        val shiv = supplier("Shiv Mills")
        val r = books.setCommission(shiv.id, 2.5)
        assertEquals(2, r.created)
        val v = assertNotNull(books.voucherForBill(b.id))
        assertEquals(VType.COMMISSION, v.type)
        assertEquals("CM/2627/0001", v.no)
        assertEquals(day, v.date)
        assertEquals(listOf(VoucherLine(shiv.id, 9750, 0), VoucherLine(sys("commission").id, 0, 9750)), v.lines)

        // Idempotent: nothing changes, nothing is written, the screens aren't refreshed.
        val before = changes
        assertFalse(books.reconcileCommission(day).changed)
        assertFalse(books.reconcileCommission(day).changed)
        assertEquals(before, changes)
        assertEquals(2, commissionVouchers())

        // A bill edit re-posts the same voucher.
        orders.saveBill(b.id, b.billNo, BillDetails(billDate = day, amount = 5000.0, buyer = "Ram Traders", supplier = "Shiv Mills"), emptyList())
        assertEquals(1, books.reconcileCommission(day).updated)
        val edited = books.voucherForBill(b.id)!!
        assertEquals(v.id, edited.id)
        assertEquals(v.no, edited.no)
        assertEquals(12500, edited.total)

        // Deleting the bill deletes its commission voucher completely (one audit line stays);
        // adding the bill back posts a fresh one.
        orders.deleteBill(b.id)
        assertEquals(1, books.reconcileCommission(day).removed)
        assertEquals(1, commissionVouchers())
        assertEquals(1, allCommissionRows())
        assertNull(books.voucher(v.id))
        assertEquals(listOf("auto-removed"), books.audit().filter { it.voucherId == v.id }.map { it.action })
        bill("SB-1", 4000.0)
        assertEquals(1, books.reconcileCommission(day).created)
        val again = books.voucherForBill(b.id)!!
        assertNotEquals(v.id, again.id)
        assertEquals(10000, again.total)
        assertEquals(2, allCommissionRows(), "one voucher per bill")

        // % to zero removes commission; back again posts it again.
        books.setCommission(shiv.id, 0.0)
        assertEquals(0, commissionVouchers())
        assertEquals(0, allCommissionRows())
        books.setCommission(shiv.id, 2.0)
        assertEquals(2, commissionVouchers())
        assertEquals(2, allCommissionRows())

        // Commission vouchers are read-only by hand.
        val live = books.voucherForBill(b.id)!!
        assertFailsWith<AccountsError> { books.deleteVoucher(live.id) }
        assertFailsWith<AccountsError> { books.saveVoucher(VoucherDraft(live.id, VType.JOURNAL, day, "x", live.lines)) }
    }

    @Test
    fun billPartiesComeFromOrdersWhenTheBillHasNone() {
        val o = orders.create(
            OrderFormData(date = "10-11-2026", buyer = "Ram Traders\nSurat", supplier = "Ganga Fabrics", items = listOf(OrderItem("1", "Shirting", "10", "100", "M"))),
            OrderNumber.formatFy(day, 1)
        )
        val b = orders.saveBill(null, "GF-9", BillDetails(billDate = day, amount = 2000.0), listOf(o.id))
        books.reconcileCommission(day)
        books.setCommission(supplier("ganga fabrics").id, 3.0)
        assertEquals(6000, books.voucherForBill(b.id)!!.total)
        assertNotNull(books.partyAccount(PartyKind.BUYER, "RAM TRADERS"))
    }

    @Test
    fun vouchersMustBalanceAndUseTheRightAccounts() {
        val cash = sys("cash").id
        val bank = sys("bank").id
        val rent = sys("rent").id
        fun save(type: VType, vararg lines: VoucherLine, id: String? = null, date: LocalDate? = day) =
            books.saveVoucher(VoucherDraft(id, type, date, "Test", lines.toList()))

        assertFailsWith<AccountsError> { save(VType.JOURNAL, VoucherLine(rent, 100, 0)) }
        assertFailsWith<AccountsError> { save(VType.JOURNAL, VoucherLine(rent, 100, 0), VoucherLine(cash, 0, 90)) }
        assertFailsWith<AccountsError> { save(VType.JOURNAL, VoucherLine(rent, 100, 100), VoucherLine(cash, 0, 0)) }
        assertFailsWith<AccountsError> { save(VType.JOURNAL, VoucherLine("", 100, 0), VoucherLine(cash, 0, 100)) }
        assertFailsWith<AccountsError> { save(VType.JOURNAL, VoucherLine(rent, 100, 0), VoucherLine(cash, 0, 100), date = null) }
        assertFailsWith<AccountsError> { save(VType.PAYMENT, VoucherLine(rent, 100, 0), VoucherLine(sys("capital").id, 0, 100)) }
        assertFailsWith<AccountsError> { save(VType.RECEIPT, VoucherLine(rent, 100, 0), VoucherLine(cash, 0, 100)) }
        assertFailsWith<AccountsError> { save(VType.CONTRA, VoucherLine(rent, 100, 0), VoucherLine(cash, 0, 100)) }
        assertFailsWith<AccountsError> { save(VType.COMMISSION, VoucherLine(rent, 100, 0), VoucherLine(cash, 0, 100)) }
        assertEquals(0, books.vouchers().size)

        val p1 = save(VType.PAYMENT, VoucherLine(rent, 1_500_000, 0), VoucherLine(cash, 0, 1_500_000))
        val p2 = save(VType.PAYMENT, VoucherLine(rent, 50_000, 0), VoucherLine(bank, 0, 50_000))
        val c1 = save(VType.CONTRA, VoucherLine(cash, 2_000_000, 0), VoucherLine(bank, 0, 2_000_000))
        assertEquals(listOf("PV/2627/0001", "PV/2627/0002", "CV/2627/0001"), listOf(p1.no, p2.no, c1.no))
        // A voucher in the next financial year starts its own series.
        assertEquals("PV/2728/0001", save(VType.PAYMENT, VoucherLine(rent, 1, 0), VoucherLine(cash, 0, 1), date = LocalDate.of(2027, 4, 1)).no)

        // Editing keeps the number unless the type or year changes.
        val e1 = save(VType.PAYMENT, VoucherLine(rent, 1_600_000, 0), VoucherLine(cash, 0, 1_600_000), id = p1.id)
        assertEquals(p1.no, e1.no)
        val e2 = save(VType.JOURNAL, VoucherLine(rent, 1_600_000, 0), VoucherLine(cash, 0, 1_600_000), id = p1.id)
        assertEquals("JV/2627/0001", e2.no)

        // Deleting removes the voucher and its lines completely; the audit keeps one line: which number, when.
        books.deleteVoucher(p2.id)
        assertNull(books.voucher(p2.id))
        assertEquals(3, books.vouchers().size)
        assertEquals(3, books.vouchers(includeDeleted = true).size)
        assertEquals(0, db.read { c -> c.one("select count(*) from acc_voucher_lines where voucher_id = ?::uuid", p2.id) { it.getInt(1) } })
        assertEquals(1, books.vouchers(text = "jv/2627").size)
        assertEquals(listOf("deleted", "edited", "edited", "created", "created", "created"), books.audit().map { it.action })
        assertEquals("PV/2627/0002", books.audit().first().voucherNo)
        assertEquals(null to null, books.auditDetail(books.audit().first().id))
    }

    @Test
    fun accountsCanBeAddedEditedAndProtected() {
        val a = books.saveAccount(Account("", "E10", "Diwali gifts", AccGroup.EXPENSE, "Indirect expenses", opening = 0))
        assertEquals("Diwali gifts", a.name)
        assertFailsWith<AccountsError> { books.saveAccount(Account("", "", "diwali GIFTS", AccGroup.EXPENSE)) }
        assertFailsWith<AccountsError> { books.saveAccount(sys("commission").copy(active = false)) }
        assertFailsWith<AccountsError> { books.deleteAccount(sys("cash").id) }
        books.saveVoucher(VoucherDraft(null, VType.PAYMENT, day, "", listOf(VoucherLine(a.id, 100, 0), VoucherLine(sys("cash").id, 0, 100))))
        assertFailsWith<AccountsError> { books.deleteAccount(a.id) }
        val b = books.saveAccount(Account("", "", "Unused", AccGroup.EXPENSE))
        books.deleteAccount(b.id)
        assertNull(books.account(b.id))

        // Party accounts: only code, %, opening and active change; the name follows the catalog.
        bill("SB-1", 1000.0)
        books.reconcileCommission(day)
        val shiv = supplier("Shiv Mills")
        val saved = books.saveAccount(shiv.copy(name = "Renamed", code = "S1", commissionPercent = 1.5, opening = -25_000))
        assertEquals("Shiv Mills", saved.name)
        assertEquals("S1", saved.code)
        assertEquals(-25_000, saved.opening)
        assertEquals(1500, books.voucherForBill(db.read { c -> c.one("select id from bills limit 1") { it.getString(1) } }!!)!!.total)
    }

    @Test
    fun reportsAgreeAndExport() {
        val cash = sys("cash").id
        sys("capital").let { books.saveAccount(it.copy(opening = -10_000_000)) }
        books.saveAccount(sys("cash").copy(opening = 10_000_000))
        bill("SB-1", 100_000.0, date = LocalDate.of(2026, 10, 5))
        bill("SB-2", 50_000.0, date = day)
        books.reconcileCommission(day)
        val shiv = supplier("Shiv Mills")
        books.setCommission(shiv.id, 2.0)
        // Commission earned 3,000.00; the supplier pays 2,000.00; rent 500.00 paid in cash.
        books.saveVoucher(VoucherDraft(null, VType.RECEIPT, day, "Commission received", listOf(VoucherLine(cash, 200_000, 0), VoucherLine(shiv.id, 0, 200_000))))
        books.saveVoucher(VoucherDraft(null, VType.PAYMENT, day, "Office rent", listOf(VoucherLine(sys("rent").id, 50_000, 0), VoucherLine(cash, 0, 50_000))))

        val tb = reports.trialBalance(day)
        val total = tb.rows.last()
        assertEquals(RowStyle.TOTAL, total.style)
        assertEquals(total.cells[3], total.cells[4])
        assertEquals(100_000 + 1_500.0 + 1_000.0 + 500.0, total.cells[3])

        val pl = reports.profitAndLoss(null, day)
        assertEquals(listOf("Net profit", "", 2_500.0), pl.rows.last().cells)

        val bs = reports.balanceSheet(day)
        val left = bs.rows.first { it.cells[0] == "Total capital & liabilities" }.cells[1]
        val right = bs.rows.first { it.cells[0] == "Total assets" }.cells[1]
        assertEquals(left, right)
        assertEquals(102_500.0, right)

        val ledger = reports.ledger(shiv.id, null, day)
        assertEquals(listOf(null, "", "", "Closing balance", "", 1_000.0, null, 1_000.0, "Dr"), ledger.rows.last().cells.let { listOf(null) + it.drop(1) })

        val com = reports.commission(null, day)
        val grand = com.rows.last()
        assertEquals(listOf("All suppliers", "", "", 2, 150_000.0, 3_000.0, 2_000.0, 1_000.0), grand.cells)

        val cashBook = reports.report(ReportKind.CASH_BOOK, null, day)
        assertEquals(100_000.0 + 2_000.0 - 500.0, cashBook.rows.last().cells[7])

        // Every report builds, with ₹ money headers.
        ReportKind.entries.forEach { k ->
            val t = reports.report(k, null, day)
            assertTrue(t.columns.filter { it.kind == ColKind.MONEY }.all { it.title.endsWith("(₹)") }, k.label)
        }

        val out = File("build/accounts-samples").apply { mkdirs() }
        val xlsx = AccountsExport.xlsx(File(out, "accounts.xlsx"), ReportKind.entries.map { reports.report(it, null, day) })
        ZipFile(xlsx).use { z -> assertEquals(ReportKind.entries.size, z.entries().toList().count { it.name.startsWith("xl/worksheets/sheet") }) }
        val pdf = AccountsExport.pdf(File(out, "commission.pdf"), com)
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("(₹)" in text, text)
        assertTrue("1,50,000.00" in text && "3,000.00" in text, text)
        assertTrue("COMMISSION BY SUPPLIER" in text.uppercase(), text)
    }

    @Test
    fun accountsMigrationKeepsAnExistingDatabase() {
        bill("SB-1", 1000.0)
        // Back to a version 3 database with data in it, then upgrade.
        db.tx { c ->
            c.exec("drop table acc_audit, acc_voucher_lines, acc_vouchers, acc_accounts")
            c.exec("delete from schema_version where version >= 4")
        }
        Migrations.run(db)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), db.read { c -> c.query("select version from schema_version order by 1") { it.getInt(1) } })
        assertEquals(20, books.accounts().size)
        assertEquals(1, db.read { c -> c.one("select count(*) from bills") { it.getInt(1) } })
        Migrations.run(db)
        assertEquals(20, books.accounts().size)
        assertEquals("1,000.00", Paise.format(100_000))
    }

    @Test
    fun demoBooksBalanceWithoutDuplicates() {
        val demo = DemoData(db).seed()
        val demoBooks = demo.seedAccounts()
        val again = demoBooks.reconcileCommission(demo.today)
        assertEquals(0, again.created + again.updated + again.removed, "a second reconcile changes nothing")
        assertEquals(0, duplicates(db))
        val tb = AccountReports(db, demoBooks, demo.payments).trialBalance(null)
        val total = tb.rows.last().cells
        assertEquals(total[3], total[4], "trial balance totals agree")
        assertTrue(demoBooks.vouchers(type = VType.COMMISSION).size > 100)
    }

    @Test
    fun theTestAccountKeepsItsOwnBooks() {
        bill("RB-1", 10_000.0)
        books.reconcileCommission(day)
        books.setCommission(supplier("Shiv Mills").id, 2.0)
        db.schema = Db.TEST
        try {
            assertEquals(20, books.accounts().size, "the test books start from the default chart")
            assertNull(books.partyAccount(PartyKind.SUPPLIER, "Shiv Mills"), "no real parties in the test books")
            assertEquals(0, allCommissionRows(), "no real commission in the test books")
            bill("TB-1", 1_000.0, supplier = "Test Mills")
            books.reconcileCommission(day)
            assertTrue(books.setCommission(supplier("Test Mills").id, 5.0).changed)
            assertEquals(1, allCommissionRows())
            assertEquals(1, payments.bills().size)
        } finally {
            db.schema = Db.PRODUCTION
        }
        assertEquals(1, allCommissionRows(), "test commission never reaches the real books")
        assertNull(books.partyAccount(PartyKind.SUPPLIER, "Test Mills"))
        assertEquals(listOf("RB-1"), payments.bills().map { it.bill.billNo })
    }

    @Test
    fun accountsExportFiltersLayoutsAndFormats() {
        val cash = sys("cash").id
        books.saveAccount(sys("capital").copy(opening = -10_000_000))
        books.saveAccount(sys("cash").copy(opening = 10_000_000))
        bill("SB-1", 100_000.0, date = LocalDate.of(2026, 10, 5))
        bill("GF-1", 20_000.0, supplier = "Ganga Fabrics")
        books.reconcileCommission(day)
        val shiv = supplier("Shiv Mills")
        val ganga = supplier("Ganga Fabrics")
        books.setCommission(shiv.id, 2.0)
        books.setCommission(ganga.id, 1.0)
        books.saveVoucher(VoucherDraft(null, VType.RECEIPT, day, "Commission received", listOf(VoucherLine(cash, 200_000, 0), VoucherLine(shiv.id, 0, 200_000))))
        books.saveVoucher(VoucherDraft(null, VType.PAYMENT, day, "Office rent", listOf(VoucherLine(sys("rent").id, 50_000, 0), VoucherLine(cash, 0, 50_000))))
        val exporter = AccountsExporter(reports, books)
        fun req(vararg sheets: ExportSheet, f: ReportFilter = ReportFilter()) = ExportRequest(sheets.toSet(), null, day, f)
        fun tables(vararg sheets: ExportSheet, f: ReportFilter = ReportFilter()) = exporter.tables(req(*sheets, f = f)).map { it.second }
        fun normal(t: com.ota.desktop.accounts.ReportTable) = t.rows.filter { it.style == RowStyle.NORMAL }

        // Every Accounts table exports.
        val everything = tables(*ExportSheet.entries.toTypedArray())
        ExportSheet.entries.forEach { s -> assertTrue(exporter.tables(req(s)).isNotEmpty(), s.label) }
        assertTrue(everything.any { it.title == "Ledger — Shiv Mills" } && everything.any { it.title == "Trade statement — Ganga Fabrics" })

        // Filters: voucher type, amount, narration text, side, parties, bill status, zero balances.
        assertEquals(listOf("Office rent"), normal(tables(ExportSheet.VOUCHERS, f = ReportFilter(types = setOf(VType.PAYMENT))).single()).map { it.cells[5] })
        assertEquals(2, normal(tables(ExportSheet.VOUCHERS, f = ReportFilter(minAmount = 100_000)).single()).size)
        assertEquals(setOf("Office rent"), normal(tables(ExportSheet.DAY_BOOK, f = ReportFilter(text = "rent")).single()).map { it.cells[4] }.filter { it != "" }.toSet())
        val cashCr = tables(ExportSheet.CASH_BOOK, f = ReportFilter(side = Side.CR)).single()
        assertEquals(listOf(500.0), normal(cashCr).map { it.cells[6] })
        assertEquals(100_000 + 2_000 - 500.0, cashCr.rows.last().cells[7], "running balance counts every entry")
        val shivOnly = ReportFilter(accounts = setOf(shiv.id))
        assertEquals(listOf("Ledger — Shiv Mills"), tables(ExportSheet.LEDGERS, f = shivOnly).map { it.title })
        assertEquals(listOf("Shiv Mills"), normal(tables(ExportSheet.TRADE_OUTSTANDING, f = shivOnly).single()).map { it.cells[1] })
        assertTrue(normal(tables(ExportSheet.TRADE_OUTSTANDING, f = ReportFilter(billStatus = setOf(PaymentStatus.PAID))).single()).isEmpty())
        assertTrue(
            tables(ExportSheet.TRIAL_BALANCE, f = ReportFilter(hideZero = false)).single().rows.size > tables(ExportSheet.TRIAL_BALANCE).single().rows.size
        )
        // Extras: subtotals, running balance and opening/closing can be switched; columns can be left out.
        assertTrue(tables(ExportSheet.TRIAL_BALANCE, f = ReportFilter(subtotals = true)).single().rows.any { it.cells[1] == "Total assets" })
        assertFalse(tables(ExportSheet.COMMISSION, f = ReportFilter(subtotals = false)).single().rows.any { (it.cells[0] as String).endsWith("— total") })
        val bare = exporter.tables(req(ExportSheet.LEDGERS, f = shivOnly.copy(runningBalance = false, openingClosing = false)).copy(excludedColumns = setOf("Narration"))).single().second
        assertEquals(listOf("Date", "Voucher", "Type", "Particulars", "Debit (₹)", "Credit (₹)"), bare.columns.map { it.title })
        assertFalse(bare.rows.any { it.cells.contains("Opening balance") || it.cells.contains("Closing balance") })

        // XLSX: title rows, frozen filterable header, real number cells with a ₹ Indian format, a sheet per table.
        val out = File("build/accounts-samples").apply { deleteRecursively(); mkdirs() }
        val all = exporter.export(req(*ExportSheet.entries.toTypedArray()), File(out, "OTA accounts export.xlsx")).single()
        ZipFile(all).use { z ->
            assertEquals(everything.size, z.entries().toList().count { it.name.startsWith("xl/worksheets/sheet") })
            for (e in z.entries().toList().filter { it.name.endsWith(".xml") })
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.getInputStream(e))
            assertTrue("₹" in z.getInputStream(z.getEntry("xl/styles.xml")).reader().readText())
            val sheet = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
            assertTrue("<autoFilter ref=\"A4:" in sheet, sheet.take(600))
            assertTrue("ySplit=\"4\"" in sheet, sheet.take(600))
        }
        ReadableWorkbook(all).use { wb ->
            val rows = wb.sheets.toList().first { it.name == "Trial balance" }.read()
            assertEquals("Trial balance", rows[0].getCellText(0))
            assertEquals("Debit (₹)", rows.first { it.rowNum == 4 }.getCellText(3))
            assertEquals(CellType.NUMBER, rows.last().getCell(3).type)
        }

        // Layouts: a file per party (plus one for the other reports) and a file per report; CSV and PDF.
        val parties = ReportFilter(accounts = setOf(shiv.id, ganga.id))
        val perParty = exporter.export(req(ExportSheet.LEDGERS, ExportSheet.TRADE_STATEMENTS, ExportSheet.TRIAL_BALANCE, f = parties).copy(layout = ExportLayout.PER_PARTY), File(out, "per-party"))
        assertEquals(setOf("Shiv Mills.xlsx", "Ganga Fabrics.xlsx", "Reports.xlsx"), perParty.map { it.name }.toSet())
        ZipFile(perParty.first { it.name == "Shiv Mills.xlsx" }).use { z -> assertEquals(2, z.entries().toList().count { it.name.startsWith("xl/worksheets/sheet") }) }
        val csv = exporter.export(req(ExportSheet.VOUCHERS, ExportSheet.DAY_BOOK).copy(layout = ExportLayout.PER_REPORT, format = ExportFormat.CSV), File(out, "per-report"))
        assertEquals(listOf("Vouchers.csv", "Day book.csv"), csv.map { it.name })
        val vouchersCsv = csv.first().readText()
        assertTrue(vouchersCsv.startsWith("\uFEFFDate,Voucher,Type,Debit,Credit,Narration,Amount (₹)"), vouchersCsv.take(80))
        assertTrue(",Office rent,500.00\r\n" in vouchersCsv, vouchersCsv)
        val pdf = exporter.export(req(ExportSheet.TRIAL_BALANCE, ExportSheet.COMMISSION).copy(format = ExportFormat.PDF), File(out, "reports.pdf")).single()
        Loader.loadPDF(pdf).use { d ->
            val text = PDFTextStripper().getText(d)
            assertTrue("TRIAL BALANCE" in text && "COMMISSION BY SUPPLIER" in text.uppercase() && "(₹)" in text, text)
            assertTrue(d.numberOfPages >= 2)
        }

        // The test login exports only the test books.
        db.schema = Db.TEST
        try {
            val texts = tables(*ExportSheet.entries.toTypedArray()).flatMap { t -> t.rows.flatMap { r -> r.cells.map { it.toString() } } + t.title }
            assertFalse(texts.any { "Shiv Mills" in it || "Ganga" in it || "Office rent" in it }, texts.filter { "Shiv" in it || "Ganga" in it }.toString())
        } finally {
            db.schema = Db.PRODUCTION
        }
    }
}
