package com.ota.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentStatus
import com.ota.desktop.dash.Analytics
import com.ota.desktop.dash.DashFilter
import com.ota.desktop.dash.Dashboard
import com.ota.desktop.export.XlsxExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month

enum class Range(val label: String) {
    D30("30 days"), D90("90 days"), M6("6 months"), M12("12 months"), FY("This FY"), ALL("All time"), CUSTOM("Custom");

    fun bounds(today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> = when (this) {
        D30 -> today.minusDays(29) to today
        D90 -> today.minusDays(89) to today
        M6 -> today.minusMonths(6).plusDays(1) to today
        M12 -> today.minusMonths(12).plusDays(1) to today
        // Indian financial year: 1 April to 31 March.
        FY -> LocalDate.of(if (today.monthValue >= 4) today.year else today.year - 1, Month.APRIL, 1) to today
        ALL, CUSTOM -> null to null
    }
}

@Composable
fun DashboardScreen() {
    val app = LocalApp.current
    val toast = LocalToast.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(Range.M6) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var buyer by rememberSaveable { mutableStateOf("") }
    var supplier by rememberSaveable { mutableStateOf("") }
    var staff by rememberSaveable { mutableStateOf<Int?>(null) }

    val (from, to) = if (range == Range.CUSTOM) parseDay(fromText) to parseDay(toText) else range.bounds()
    val filter = DashFilter(from, to, buyer.trim(), supplier.trim(), staff)
    var loading by remember { mutableStateOf(true) }
    val dash by rememberLoad<Dashboard?>(null, filter) {
        loading = true
        app.analytics.dashboard(filter).also { loading = false }
    }
    val staffCodes by rememberLoad(emptyList<Int>()) {
        app.analytics.staffCodes()
    }
    val status by app.engine.status.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Dashboard", dash?.let { Dashboard.filterLabel(it.filter) } ?: "Loading…") {
            OutlinedButton(onClick = { nav.go(Section.ORDERS, Target.NewOrder) }) { IconText(Icons.Default.Add, "New order") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val d = dash ?: return@Button
                val file = chooseSaveFile("Export dashboard", "OTA dashboard ${LocalDate.now()}.xlsx") ?: return@Button
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { XlsxExport.write(file, d.tables()) } }
                        .onSuccess { toast("Dashboard exported to ${it.name}"); openFile(it) }
                        .onFailure { toast("Export failed: ${it.message}") }
                }
            }) { IconText(Icons.Default.Share, "Export dashboard") }
        }

        // Filters
        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Range.entries.forEach { r ->
                    FilterChip(
                        selected = range == r, onClick = { range = r }, label = { Text(r.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Saffron, selectedLabelColor = androidx.compose.ui.graphics.Color.White)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (buyer.isNotEmpty() || supplier.isNotEmpty() || staff != null || range != Range.M6) {
                    TextButton(onClick = { range = Range.M6; buyer = ""; supplier = ""; staff = null }) { Text("Reset filters") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                if (range == Range.CUSTOM) {
                    DateField(fromText, { fromText = it }, "From", Modifier.width(150.dp), optional = true)
                    DateField(toText, { toText = it }, "To", Modifier.width(150.dp), optional = true)
                }
                PartyField(PartyType.BUYER, buyer, { buyer = it }, "Buyer", Modifier.weight(1f))
                PartyField(PartyType.SUPPLIER, supplier, { supplier = it }, "Supplier", Modifier.weight(1f))
                Select("Staff", listOf<Int?>(null) + staffCodes, staff, { staff = it }, Modifier.width(150.dp).padding(top = 8.dp), text = { it?.let { c -> "Staff $c" } ?: "All staff" })
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), trackColor = Brand.SaffronSoft)
        }
        Spacer(Modifier.height(10.dp))

        val d = dash ?: return@Column
        val gap = Arrangement.spacedBy(14.dp)
        Row(horizontalArrangement = gap) {
            Kpi("Orders", "${d.orderCount}", "${d.itemCount} items · ${d.pendingBills} without bill", Brand.Saffron, Modifier.weight(1f))
            Kpi("Billed", moneyFull(d.billed), "${d.billCount} bills", Brand.Blue, Modifier.weight(1f))
            Kpi("Outstanding", moneyFull(d.outstanding), "${d.status.firstOrNull { it.status == PaymentStatus.UNPAID }?.count ?: 0} unpaid · ${d.status.firstOrNull { it.status == PaymentStatus.PARTLY_PAID }?.count ?: 0} part paid", Brand.Red, Modifier.weight(1f))
            Kpi("Paid", moneyFull(d.paid), "${d.adviceCount} payment advices", Brand.Green, Modifier.weight(1f))
            Kpi("Adjustments", moneyFull(d.cashDiscount + d.goodsReturn + d.rateDiff), "CD + goods return + rate diff.", Brand.Amber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = gap) {
            Panel(Modifier.weight(1f), title = "Orders over time", actions = { Pill("by ${d.period.lowercase()}", Brand.Muted) }) {
                BarChart(d.ordersOverTime, Modifier.height(220.dp), tipFormat = { "${it.toInt()} orders" })
            }
            Panel(Modifier.weight(1f), title = "Payments over time", actions = { Pill("by ${d.period.lowercase()}", Brand.Muted) }) {
                LineChart(d.paymentsOverTime, Modifier.height(220.dp), color = Brand.Green, tipFormat = ::moneyFull)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = gap) {
            Panel(Modifier.weight(1f), title = "Top buyers", actions = { Legend(Brand.Saffron) }) {
                HBars(d.topBuyers.map { HBar(it.name, it.billed, it.outstanding, Brand.Saffron, "") }, Modifier.height(IntrinsicSize.Min))
            }
            Panel(Modifier.weight(1f), title = "Top suppliers", actions = { Legend(Brand.Blue) }) {
                HBars(d.topSuppliers.map { HBar(it.name, it.billed, it.outstanding, Brand.Blue, "") })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = gap, modifier = Modifier.height(IntrinsicSize.Min)) {
            Panel(Modifier.weight(1.1f).fillMaxHeight(), title = "Outstanding by age") {
                val colors = listOf(Brand.Green, Brand.Amber, androidx.compose.ui.graphics.Color(0xFFEF6C00), Brand.Red, Brand.Muted)
                HBars(d.ageing.mapIndexed { i, b -> HBar("${b.label} · ${b.count}", b.amount, null, colors[i.coerceAtMost(colors.lastIndex)]) })
            }
            Panel(Modifier.weight(1.2f).fillMaxHeight(), title = "Bills: paid vs unpaid") {
                Donut(
                    d.status.map { Slice(it.status.label, it.count.toDouble(), Brand.status(it.status), "${it.count} bills · ${shortMoney(it.billed)}" + if (it.balance > 0) " · due ${shortMoney(it.balance)}" else "") },
                    "${d.billCount}", "bills"
                )
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "CD, goods return, rate diff.") {
                HBars(d.adjustments.mapIndexed { i, p -> HBar(p.label, p.value, null, listOf(Brand.Amber, Brand.Blue, androidx.compose.ui.graphics.Color(0xFF8E24AA))[i]) })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = gap, modifier = Modifier.height(IntrinsicSize.Min)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Quantity per unit") {
                HBars(d.qtyByUnit.mapIndexed { i, p -> HBar(p.label, p.value, null, Brand.Series[i % Brand.Series.size]) }, format = ::shortNumber)
            }
            Panel(Modifier.weight(1.3f).fillMaxHeight(), title = "Staff activity") {
                val cols = listOf(Col("Staff", 1f), Col("Orders", 1f, TextAlign.End), Col("Advices", 1f, TextAlign.End), Col("Paid", 1.6f, TextAlign.End))
                TableHeader(cols)
                if (d.staff.isEmpty()) Empty("No activity")
                d.staff.forEach { s ->
                    TableRow(false, {}) {
                        Cell("Staff ${s.code}", 1f, bold = true)
                        Cell("${s.orders}", 1f, TextAlign.End)
                        Cell("${s.advices}", 1f, TextAlign.End)
                        Cell(moneyFull(s.paid), 1.6f, TextAlign.End)
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Cloud storage") {
                val u = status.usage
                if (u == null) Empty("Not known yet. It's read on the next sync.")
                else {
                    Gauge(u.percent, u.warnPercent, "${"%.1f".format(u.usedMb)} of ${u.limitMb} MB", Modifier.fillMaxWidth().height(130.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Orders are kept in the cloud for ${u.retentionDays} days; older ones live only on this laptop.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Legend(billed: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(billed); Text("Billed", style = MaterialTheme.typography.labelSmall)
        Dot(Brand.Red.copy(alpha = 0.75f)); Text("Outstanding", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Normal)
    }
}
