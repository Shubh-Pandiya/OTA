package com.example.otaapplication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.HomeStats
import com.example.otaapplication.data.MonthValue
import com.example.otaapplication.data.PartyAmount
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.icons.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The agency's numbers: outstanding, monthly trends, ageing, top parties and recent activity. */
@Composable
fun InsightsScreen(stats: HomeStats?, actions: HomeActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hero") { Reveal(0) { OutstandingCard(stats) } }
        item(key = "kpis") { Reveal(1) { KpiGrid(stats, actions) } }
        item(key = "orders") {
            Reveal(2) {
                HomeSection("Orders · last ${HomeStats.MONTHS} months", Icons.Default.Inventory2, "Orders", actions.orders) {
                    BarChart(stats?.ordersByMonth.orEmpty(), MaterialTheme.colorScheme.primary) { it.toInt().toString() }
                }
            }
        }
        item(key = "paid") {
            Reveal(3) {
                HomeSection("Payments · last ${HomeStats.MONTHS} months", Icons.Default.Payments, "Advices", actions.advices) {
                    BarChart(stats?.paidByMonth.orEmpty(), Color(0xFF2E7D32)) { if (it == 0.0) "0" else shortRupees(it).removePrefix("₹ ") }
                }
            }
        }
        item(key = "ageing") { Reveal(4) { AgeingCard(stats, actions.bills) } }
        if (stats != null && stats.topBuyers.isNotEmpty()) {
            item(key = "buyers") {
                Reveal(5) { PartyBars("Top buyers by outstanding", Icons.Default.Groups, stats.topBuyers, actions.bills) }
            }
        }
        if (stats != null && stats.topSuppliers.isNotEmpty()) {
            item(key = "suppliers") {
                Reveal(6) { PartyBars("Top suppliers by outstanding", Icons.Default.Store, stats.topSuppliers, actions.bills) }
            }
        }
        if (stats != null && stats.oldestDue.isNotEmpty()) {
            item(key = "due") {
                Reveal(7) {
                    HomeSection("Follow up payments", Icons.Default.Notifications, "All bills", actions.bills) {
                        stats.oldestDue.forEach { item ->
                            val bill = item.bill
                            HomeRow(
                                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                                title = "Bill ${bill.billNo}",
                                subtitle = listOf(
                                    bill.billDate?.let { ChronoUnit.DAYS.between(it, LocalDate.now()).let { d -> if (d == 1L) "1 day" else "$d days" } } ?: "No date",
                                    listOf(item.buyer, item.supplier).map(SearchText::firstLine).filter { it.isNotBlank() }.joinToString(" → ")
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                value = shortRupees(bill.balance ?: 0.0),
                                valueColor = ageColors[HomeStats.ageIndex(bill.billDate, LocalDate.now())],
                                onClick = { actions.openBill(bill.id) }
                            )
                        }
                    }
                }
            }
        }
        if (stats != null && stats.recentOrders.isNotEmpty()) {
            item(key = "recentOrders") {
                Reveal(8) {
                    HomeSection("Recent orders", Icons.Default.Inventory2, "All orders", actions.orders) {
                        stats.recentOrders.forEach { order ->
                            HomeRow(
                                icon = Icons.Default.Description,
                                title = if (OrderNumber.isPending(order.offerNo)) "Pending number" else "Offer ${OrderNumber.display(order.offerNo)}",
                                subtitle = listOf(order.date, SearchText.firstLine(order.buyer)).filter { it.isNotBlank() }.joinToString(" · "),
                                value = if (order.billNos.isEmpty()) "Bill due" else "Billed",
                                valueColor = if (order.billNos.isEmpty()) MaterialTheme.colorScheme.tertiary else Color(0xFF2E7D32),
                                onClick = actions.orders
                            )
                        }
                    }
                }
            }
        }
        if (stats != null && stats.recentAdvices.isNotEmpty()) {
            item(key = "recentAdvices") {
                Reveal(9) {
                    HomeSection("Recent payment advices", Icons.Default.Payments, "All advices", actions.advices) {
                        stats.recentAdvices.forEach { advice ->
                            HomeRow(
                                icon = Icons.Default.Payments,
                                title = AdviceNumber.display(advice.adviceNo),
                                subtitle = listOfNotNull(
                                    advice.payDate?.format(billDateFormat),
                                    listOf(advice.buyer, advice.supplier).map(SearchText::firstLine).filter { it.isNotBlank() }.joinToString(" → ")
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                value = shortRupees(advice.paid),
                                valueColor = MaterialTheme.colorScheme.primary,
                                onClick = { actions.openAdvice(advice.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutstandingCard(stats: HomeStats?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFFFF8F00), Color(0xFFFFA726))))
            .padding(20.dp)
    ) {
        Text("Outstanding from buyers", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        AnimatedAmount(stats?.outstanding, style = MaterialTheme.typography.displaySmall)
        Text(
            stats?.let { s ->
                listOfNotNull(
                    "${s.billsDue} bill${if (s.billsDue == 1) "" else "s"} due",
                    s.overdueBills.takeIf { it > 0 }?.let { "$it over 90 days" },
                    s.billsWithoutAmount.takeIf { it > 0 }?.let { "$it without amount" }
                ).joinToString(" · ")
            } ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun KpiGrid(stats: HomeStats?, actions: HomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(
                Modifier.weight(1f), Icons.Default.Event, "Orders today",
                stats?.ordersToday?.toString(), stats?.let { "${it.ordersThisMonth} this month" }, actions.orders
            )
            KpiTile(
                Modifier.weight(1f), Icons.Default.Schedule, "Bills to receive",
                stats?.ordersAwaitingBill?.toString(), "orders without a bill", actions.orders
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(
                Modifier.weight(1f), Icons.Default.Money, "Paid this month",
                stats?.receivedThisMonth?.let(::shortRupees), stats?.let { "${it.advicesThisMonth} advice${if (it.advicesThisMonth == 1) "" else "s"}" }, actions.advices
            )
            KpiTile(
                Modifier.weight(1f), Icons.Default.AccountBalance, "Bills due",
                stats?.billsDue?.toString(), stats?.let { "${shortRupees(it.outstanding)} outstanding" }, actions.bills
            )
        }
    }
}

@Composable
private fun KpiTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String?, caption: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 1.dp, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(value ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(caption.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AgeingCard(stats: HomeStats?, onOpen: () -> Unit) {
    HomeSection("Outstanding by age", Icons.Default.Timelapse, "Bills", onOpen) {
        val buckets = stats?.ageing.orEmpty()
        val total = buckets.sumOf { it.amount }
        val grow = remember { Animatable(0f) }
        LaunchedEffect(stats != null) { if (stats != null) grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (total > 0) buckets.forEachIndexed { i, b ->
                if (b.amount > 0) Box(Modifier.weight((b.amount / total).toFloat() * grow.value + 0.0001f).fillMaxHeight().background(ageColors[i]))
            }
            if (total > 0 && grow.value < 1f) Spacer(Modifier.weight(1f - grow.value + 0.0001f))
        }
        if (stats != null && total == 0.0) {
            Text("Nothing outstanding. All bills with an amount are settled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        buckets.forEachIndexed { i, b ->
            if (b.count == 0) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(ageColors[i]))
                Spacer(Modifier.width(10.dp))
                Text(b.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${b.count} bill${if (b.count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(shortRupees(b.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 84.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Monthly columns that grow in once; the current month is drawn solid, earlier months lighter. */
@Composable
private fun BarChart(values: List<MonthValue>, color: Color, format: (Double) -> String) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values.isNotEmpty()) { if (values.isNotEmpty()) grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val max = values.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    if (values.isNotEmpty() && values.all { it.value == 0.0 }) {
        Text("Nothing in the last ${HomeStats.MONTHS} months yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
        val barArea = maxHeight - 40.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val current = i == values.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        format(v.value),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(barArea * ((v.value / max).toFloat() * grow.value).coerceAtLeast(0.015f))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (current) color else color.copy(alpha = 0.4f))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(v.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PartyBars(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, parties: List<PartyAmount>, onOpen: () -> Unit) {
    HomeSection(title, icon, "Bills", onOpen) {
        val grow = remember { Animatable(0f) }
        LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        val top = parties.first().amount.takeIf { it > 0 } ?: 1.0
        parties.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${p.bills} bill${if (p.bills == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(shortRupees(p.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 84.dp), textAlign = TextAlign.End)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(((p.amount / top).toFloat() * grow.value).coerceIn(0.01f, 1f)).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}
