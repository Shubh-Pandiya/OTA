package com.ota.desktop

import com.ota.desktop.dash.Analytics
import com.ota.desktop.dash.DashFilter
import com.ota.desktop.export.DataExport
import com.ota.desktop.export.XlsxExport
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsExportTest {
    private lateinit var demo: DemoData
    private lateinit var analytics: Analytics

    @BeforeTest
    fun setUp() {
        demo = DemoData(TestDb.fresh()).seed()
        analytics = Analytics(demo.db, demo.orders, demo.payments)
    }

    @Test
    fun dashboardAddsUp() {
        val d = analytics.dashboard(DashFilter(), today = demo.today)
        assertTrue(d.orderCount > 100, "orders ${d.orderCount}")
        assertEquals(d.orderCount, d.ordersOverTime.sumOf { it.value }.toInt())
        assertEquals("Month", d.period)
        assertEquals(d.paid, d.paymentsOverTime.sumOf { it.value }, 0.01)
        assertEquals(d.billCount, d.status.sumOf { it.count })
        assertEquals(d.outstanding, d.ageing.sumOf { it.amount }, 0.01)
        // FY numbers (from 1 Nov in the demo) are agency-wide, so only old numbers count per staff.
        val fy = demo.orders.list().count { com.example.otaapplication.OrderNumber.isFy(it.orderNo) }
        assertTrue(fy > 0)
        assertEquals(d.orderCount - fy, d.staff.sumOf { it.orders })
        assertTrue(d.claim > 0)
        assertEquals(d.paid + d.cashDiscount + d.goodsReturn + d.rateDiff + d.claim, analytics.paymentLines().sumOf { it.settled }, 0.05)
        assertTrue(d.qtyByUnit.any { it.label == "Mtr" && it.value > 0 })
        assertTrue(d.topBuyers.isNotEmpty() && d.topBuyers.size <= 10)
        assertTrue(d.cashDiscount > 0)

        val one = analytics.dashboard(DashFilter(buyer = "Raj Tailors", staff = 4), today = demo.today)
        assertTrue(one.orderCount in 1 until d.orderCount)
        assertEquals(listOf("Raj Tailors"), one.topBuyers.map { it.name })
        assertTrue(one.staff.all { it.code == 4 })

        val month = analytics.dashboard(DashFilter(from = demo.today.minusDays(30), to = demo.today), today = demo.today)
        assertEquals("Day", month.period)
        assertEquals(31, month.ordersOverTime.size)
    }

    @Test
    fun homeSummarisesTodayAndDues() {
        val h = analytics.home(demo.today)
        assertEquals(demo.orders.list().count { it.day == demo.today }, h.ordersToday)
        assertTrue(h.ordersThisMonth >= h.ordersToday)
        assertTrue(h.awaitingBill > 0)
        assertEquals(h.outstanding, h.ageing.sumOf { it.amount }, 0.01)
        assertEquals(h.billsDue, h.ageing.sumOf { it.count })
        assertTrue(h.oldestDue.zipWithNext().all { (a, b) -> b.bill.billDate == null || a.bill.billDate!! <= b.bill.billDate!! })
        assertEquals(6, h.recentOrders.size)
        assertTrue(h.recentAdvices.isNotEmpty())
    }

    @Test
    fun exportsEveryTableToXlsx() {
        val export = DataExport(demo.orders, demo.payments, demo.catalog, analytics)
        val dir = File("build/xlsx-samples").apply { mkdirs() }
        val all = XlsxExport.write(File(dir, "ota-all-data.xlsx"), export.everything())
        val dash = XlsxExport.write(File(dir, "ota-dashboard.xlsx"), analytics.dashboard(DashFilter(), demo.today).tables())

        ReadableWorkbook(all).use { wb ->
            val names = wb.sheets.map { it.name }.toList()
            assertEquals(listOf("Orders", "Order items", "Bills", "Payment advices", "Advice lines", "Buyers", "Suppliers"), names)
            val orders = wb.sheets.toList()[0].read()
            assertEquals("Order no", orders[0].getCellText(0))
            assertEquals(demo.orders.list().size + 1, orders.size)
            val bills = wb.sheets.toList()[2].read()
            assertTrue(bills[1].getCell(4).asNumber().toDouble() > 0)
        }
        ReadableWorkbook(dash).use { wb -> assertEquals(10, wb.sheets.count()) }
    }
}
