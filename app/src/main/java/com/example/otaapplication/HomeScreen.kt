package com.example.otaapplication

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.cloud.CloudStatus
import com.example.otaapplication.data.HomeStats
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.icons.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
private val compactRupees: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }

/** Short rupee figure for tiles: ₹ 4.2 L / ₹ 1.3 Cr above a lakh, otherwise the whole amount. */
internal fun shortRupees(value: Double): String = when {
    value >= 1e7 -> "₹ ${"%.2f".format(Locale.ROOT, value / 1e7)} Cr"
    value >= 1e5 -> "₹ ${"%.2f".format(Locale.ROOT, value / 1e5)} L"
    else -> "₹ ${compactRupees.format(value)}"
}

// Age bands from fresh (green) to overdue (red), plus grey for bills without a date.
private val ageColors = listOf(Color(0xFF43A047), Color(0xFFFFB300), Color(0xFFF4511E), Color(0xFFC62828), Color(0xFF9E9E9E))

class HomeActions(
    val newOrder: () -> Unit,
    val newAdvice: () -> Unit,
    val orders: () -> Unit,
    val bills: () -> Unit,
    val advices: () -> Unit,
    val parties: () -> Unit,
    val openBill: (String) -> Unit,
    val openAdvice: (String) -> Unit,
    val sync: () -> Unit
)

/** Start screen: today at a glance for the agency, with quick actions and what needs following up. */
@Composable
fun HomeScreen(stats: HomeStats?, cloudStatus: CloudStatus, staffName: String?, actions: HomeActions, refreshKey: Int = 0) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "today") { Reveal(0) { TodayCard(refreshKey) } }
        item(key = "hero") { Reveal(0) { HeroCard(stats, cloudStatus, staffName, actions.sync) } }
        item(key = "actions") { Reveal(1) { QuickActions(actions) } }
        item(key = "kpis") { Reveal(2) { KpiGrid(stats, actions) } }
        item(key = "ageing") { Reveal(3) { AgeingCard(stats, actions.bills) } }
        if (stats != null && stats.oldestDue.isNotEmpty()) {
            item(key = "due") {
                Reveal(4) {
                    HomeSection("Follow up payments", Icons.Default.Notifications, "All bills", actions.bills) {
                        stats.oldestDue.forEach { item ->
                            val bill = item.bill
                            HomeRow(
                                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                                title = "Bill ${bill.billNo}",
                                subtitle = listOf(
                                    bill.billDate?.let { "${java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now())} days" } ?: "No date",
                                    listOf(item.buyer, item.supplier).filter { it.isNotBlank() }.joinToString(" → ")
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
            item(key = "orders") {
                Reveal(5) {
                    HomeSection("Recent orders", Icons.Default.Inventory2, "All orders", actions.orders) {
                        stats.recentOrders.forEach { order ->
                            HomeRow(
                                icon = Icons.Default.Description,
                                title = "Offer ${OrderNumber.display(order.offerNo)}",
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
            item(key = "advices") {
                Reveal(6) {
                    HomeSection("Recent payment advices", Icons.Default.Payments, "All advices", actions.advices) {
                        stats.recentAdvices.forEach { advice ->
                            HomeRow(
                                icon = Icons.Default.Payments,
                                title = advice.adviceNo,
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

/** Fades and lifts each block in once, staggered, the first time Home is shown. */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(320, delayMillis = 40 * index)) +
            slideInVertically(tween(380, delayMillis = 40 * index, easing = FastOutSlowInEasing)) { it / 6 }
    ) { content() }
}

@Composable
private fun HeroCard(stats: HomeStats?, cloud: CloudStatus, staffName: String?, onSync: () -> Unit) {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..22 -> "Good evening"; else -> "Hello" }
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(primary, Color(0xFFFF8F00), Color(0xFFFFA726))))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    staffName?.let { "$greeting, $it" } ?: greeting,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(remember { LocalDate.now().format(homeDateFormat) }, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
            SyncChip(cloud, onSync)
        }
        Spacer(Modifier.height(18.dp))
        Text("Outstanding from buyers", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        AnimatedAmount(stats?.outstanding, style = MaterialTheme.typography.displaySmall)
        Text(
            stats?.let { s ->
                listOfNotNull(
                    "${s.billsDue} bill${if (s.billsDue == 1) "" else "s"} due",
                    s.billsWithoutAmount.takeIf { it > 0 }?.let { "$it without amount" }
                ).joinToString(" · ")
            } ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun AnimatedAmount(value: Double?, style: androidx.compose.ui.text.TextStyle) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { if (value != null) anim.animateTo(value.toFloat(), tween(700, easing = FastOutSlowInEasing)) }
    Text(
        if (value == null) "—" else shortRupees(if (anim.isRunning) anim.value.toDouble() else value),
        style = style,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White
    )
}

@Composable
private fun SyncChip(cloud: CloudStatus, onSync: () -> Unit) {
    val (icon, text) = when {
        !cloud.configured -> Icons.Default.CloudOff to "Offline"
        cloud.syncing -> Icons.Default.Sync to "Syncing"
        cloud.storageFull -> Icons.Default.Warning to "Cloud full"
        cloud.offline -> Icons.Default.CloudOff to if (cloud.pending > 0) "${cloud.pending} waiting" else "Offline"
        cloud.lastError != null -> Icons.Default.Warning to "Sync error"
        cloud.pending > 0 -> Icons.Default.CloudUpload to "${cloud.pending} to sync"
        else -> Icons.Default.CloudDone to "Synced"
    }
    val rotation by animateFloatAsState(if (cloud.syncing) 360f else 0f, tween(if (cloud.syncing) 900 else 0), label = "syncSpin")
    Surface(
        onClick = onSync,
        enabled = cloud.configured && !cloud.syncing,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -rotation })
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuickActions(actions: HomeActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickAction(Icons.Default.EditNote, "New order", actions.newOrder)
        QuickAction(Icons.Default.Payments, "New advice", actions.newAdvice)
        QuickAction(Icons.AutoMirrored.Filled.ReceiptLong, "Bills", actions.bills)
        QuickAction(Icons.Default.Contacts, "Parties", actions.parties)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
private fun KpiTile(modifier: Modifier, icon: ImageVector, label: String, value: String?, caption: String?, onClick: () -> Unit) {
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
                Text(shortRupees(b.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 84.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
private fun HomeSection(title: String, icon: ImageVector, linkLabel: String, onLink: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onLink, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(linkLabel) }
            }
            content()
        }
    }
}

@Composable
private fun HomeRow(icon: ImageVector, title: String, subtitle: String, value: String, valueColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
