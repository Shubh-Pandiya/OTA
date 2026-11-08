package com.ota.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.Units
import com.example.otaapplication.data.SearchText
import com.ota.desktop.dash.Home
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TODAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

/** Start screen: today's work, bills still to come, money due by age and the latest orders and advices. */
@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val staff by app.staff.collectAsState()
    val status by app.engine.status.collectAsState()
    val home by rememberLoad<Home?>(null) { app.analytics.home() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val today = LocalDate.now()
        val fyStart = if (today.monthValue >= 4) today.year else today.year - 1
        val hello = when (LocalTime.now().hour) { in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val name = staff?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        PageHeader(if (name != null) "$hello, $name" else hello, "${today.format(TODAY)} · FY $fyStart-${(fyStart + 1) % 100}") {
            OutlinedButton(enabled = !status.running, onClick = { scope.launch { app.engine.sync() } }) {
                IconText(Icons.Default.Refresh, if (status.running) "Syncing…" else "Sync now")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.go(Section.ADVICES, Target.NewAdvice(null)) }) { IconText(Icons.Default.Send, "New advice") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.go(Section.BILLS, Target.NewBill) }) { IconText(Icons.Default.List, "Add bill") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { nav.go(Section.ORDERS, Target.NewOrder) }) { IconText(Icons.Default.Add, "New order") }
        }

        val h = home ?: return@Column
        if (h.pendingNumbers > 0) {
            Banner(
                "${h.pendingNumbers} order(s) saved offline are waiting for their order number. They get it on the next sync.",
                Brand.Amber, Modifier.fillMaxWidth()
            ) { TextButton(enabled = !status.running, onClick = { scope.launch { app.engine.sync() } }) { Text("Sync now") } }
            Spacer(Modifier.height(12.dp))
        }

        val gap = Arrangement.spacedBy(14.dp)
        Row(horizontalArrangement = gap) {
            Kpi("Orders today", "${h.ordersToday}", "${h.ordersThisMonth} this month", Brand.Saffron, Modifier.weight(1f).clickable { nav.go(Section.ORDERS) })
            Kpi("Bills to receive", "${h.awaitingBill}", "orders without a bill yet", Brand.Blue, Modifier.weight(1f).clickable { nav.go(Section.ORDERS) })
            Kpi(
                "Outstanding", moneyFull(h.outstanding),
                "${h.billsDue} bill(s) due" + if (h.billsWithoutAmount > 0) " · ${h.billsWithoutAmount} without amount" else "",
                Brand.Red, Modifier.weight(1f).clickable { nav.go(Section.BILLS) }
            )
            Kpi("Received this month", moneyFull(h.receivedThisMonth), "${h.advicesThisMonth} payment advice(s)", Brand.Green, Modifier.weight(1f).clickable { nav.go(Section.ADVICES) })
        }
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = gap, modifier = Modifier.height(IntrinsicSize.Min)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Outstanding by age") {
                if (h.ageing.all { it.count == 0 }) Empty("Nothing due. Every bill with an amount is settled.")
                else {
                    val colors = listOf(Brand.Green, Brand.Amber, Color(0xFFEF6C00), Brand.Red, Brand.Muted)
                    HBars(h.ageing.mapIndexed { i, b -> HBar("${b.label} · ${b.count}", b.amount, null, colors[i.coerceAtMost(colors.lastIndex)]) })
                }
            }
            Panel(Modifier.weight(1.4f).fillMaxHeight(), title = "Oldest dues", actions = { TextButton(onClick = { nav.go(Section.BILLS) }) { Text("All bills") } }) {
                TableHeader(listOf(Col("Bill", 1f), Col("Date", 0.8f), Col("Parties", 2f), Col("Balance", 1f, TextAlign.End)))
                if (h.oldestDue.isEmpty()) Empty("No bills due")
                h.oldestDue.forEach { (b, buyer, supplier) ->
                    TableRow(false, { nav.go(Section.BILLS, Target.Bill(b.id)) }) {
                        Cell(b.billNo, 1f, bold = true)
                        Cell(b.billDate.dmy().ifEmpty { "—" }, 0.8f)
                        Cell2(SearchText.firstLine(buyer), SearchText.firstLine(supplier), 2f)
                        Cell(rupees(b.balance), 1f, TextAlign.End, color = Brand.Red)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = gap, modifier = Modifier.height(IntrinsicSize.Min)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Recent orders", actions = { TextButton(onClick = { nav.go(Section.ORDERS) }) { Text("All orders") } }) {
                TableHeader(listOf(Col("Order no", 1f), Col("Date", 0.8f), Col("Buyer / supplier", 2f), Col("Quantity", 1.1f, TextAlign.End)))
                if (h.recentOrders.isEmpty()) Empty("No orders yet")
                h.recentOrders.forEach { o ->
                    TableRow(false, { nav.go(Section.ORDERS, Target.Order(o.id)) }) {
                        Cell(OrderNumber.display(o.orderNo), 1f, bold = true, color = if (OrderNumber.isPending(o.orderNo)) Brand.Amber else Color.Unspecified)
                        Cell(o.day.dmy().ifEmpty { o.data.date }, 0.8f)
                        Cell2(SearchText.firstLine(o.data.buyer), SearchText.firstLine(o.data.supplier), 2f)
                        Cell(Units.totals(o.data.items.map(Units::splitLegacy)).orEmpty(), 1.1f, TextAlign.End)
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Recent payment advices", actions = { TextButton(onClick = { nav.go(Section.ADVICES) }) { Text("All advices") } }) {
                TableHeader(listOf(Col("Advice", 1f), Col("Date", 0.8f), Col("Buyer / supplier", 2f), Col("Paid", 1f, TextAlign.End)))
                if (h.recentAdvices.isEmpty()) Empty("No payment advices yet")
                h.recentAdvices.forEach { a ->
                    TableRow(false, { nav.go(Section.ADVICES, Target.Advice(a.id)) }) {
                        Cell(a.adviceNo, 1f, bold = true)
                        Cell(a.payDate.dmy(), 0.8f)
                        Cell2(SearchText.firstLine(a.buyer), SearchText.firstLine(a.supplier), 2f)
                        Cell(moneyFull(a.paid), 1f, TextAlign.End, color = Brand.Green)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
