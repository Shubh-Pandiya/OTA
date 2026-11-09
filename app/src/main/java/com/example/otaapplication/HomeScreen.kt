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
import com.example.otaapplication.ui.RoamingCat
import com.example.otaapplication.ui.WeatherCard
import com.example.otaapplication.ui.icons.*
import com.example.otaapplication.weather.WeatherView
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
internal val ageColors = listOf(Color(0xFF43A047), Color(0xFFFFB300), Color(0xFFF4511E), Color(0xFFC62828), Color(0xFF9E9E9E))

class HomeActions(
    val newOrder: () -> Unit,
    val newAdvice: () -> Unit,
    val addBill: () -> Unit,
    val orders: () -> Unit,
    val bills: () -> Unit,
    val advices: () -> Unit,
    val parties: () -> Unit,
    val openBill: (String) -> Unit,
    val openAdvice: (String) -> Unit,
    val sync: () -> Unit,
    val insights: () -> Unit,
    /** Opens the weather place picker. */
    val weather: () -> Unit
)

/** Start screen: today's to-dos, the weather, quick actions and the little that needs attention. The numbers live in Insights. */
@Composable
fun HomeScreen(
    stats: HomeStats?,
    cloudStatus: CloudStatus,
    staffName: String?,
    actions: HomeActions,
    weather: WeatherView,
    refreshKey: Int = 0,
    catActive: Boolean = true,
    reduceMotion: Boolean = false
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "today") { Reveal(0) { TodayCard(refreshKey) } }
                    item(key = "weather") {
                        Reveal(1) {
                            val hour = remember { LocalTime.now().hour }
                            val greeting = when (hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..22 -> "Good evening"; else -> "Hello" }
                            WeatherCard(
                                weather = weather.weather,
                                note = weather.note,
                                greeting = staffName?.let { "$greeting, $it" } ?: greeting,
                                date = remember { LocalDate.now().format(homeDateFormat) },
                                onClick = actions.weather,
                                modifier = Modifier.fillMaxWidth()
                            ) { SyncChip(cloudStatus, actions.sync) }
                        }
                    }
                    item(key = "actions") { Reveal(2) { QuickActions(actions) } }
                    item(key = "attention") { Reveal(3) { NeedsAttention(stats, actions) } }
                    item(key = "insights") { Reveal(4) { InsightsLink(stats, actions.insights) } }
                }
                // Cards scrolling down fade into the cat's floor instead of being cut off.
                val bg = MaterialTheme.colorScheme.background
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)))
                )
            }
            // The cat's own floor: its body never covers a card, only a jump or a "Meow!" briefly does.
            Spacer(Modifier.fillMaxWidth().height(CAT_FLOOR))
        }
        RoamingCat(active = catActive, reduceMotion = reduceMotion, modifier = Modifier.fillMaxSize(), floor = 3.dp, catWidth = 58.dp)
    }
}

private val CAT_FLOOR = 46.dp

/** At most two light reminders: orders still waiting for their number, and bills still to come. */
@Composable
private fun NeedsAttention(stats: HomeStats?, actions: HomeActions) {
    HomeSection("Needs attention", Icons.Default.Notifications, "Orders", actions.orders) {
        if (stats == null) {
            Text("Checking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HomeSection
        }
        if (stats.pendingNumbers > 0) {
            HomeRow(
                icon = Icons.Default.Tag,
                title = "${stats.pendingNumbers} order${if (stats.pendingNumbers == 1) "" else "s"} waiting for a number",
                subtitle = "Numbered on the next sync; PDFs can be shared after that",
                value = "",
                valueColor = MaterialTheme.colorScheme.tertiary,
                onClick = actions.orders
            )
        }
        if (stats.ordersAwaitingBill > 0) {
            HomeRow(
                icon = Icons.Default.Schedule,
                title = "${stats.ordersAwaitingBill} order${if (stats.ordersAwaitingBill == 1) "" else "s"} waiting for a bill",
                subtitle = "Add the supplier's bill when the hard copy comes",
                value = "",
                valueColor = MaterialTheme.colorScheme.tertiary,
                onClick = actions.orders
            )
        }
        if (stats.pendingNumbers == 0 && stats.ordersAwaitingBill == 0) {
            HomeRow(
                icon = Icons.Default.TaskAlt,
                title = "All caught up",
                subtitle = "Every order has its number and bill",
                value = "",
                valueColor = MaterialTheme.colorScheme.primary,
                onClick = actions.orders
            )
        }
    }
}

@Composable
private fun InsightsLink(stats: HomeStats?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stats?.let { "${shortRupees(it.outstanding)} outstanding · trends, ageing, top parties" } ?: "Trends, ageing and top parties",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

/** Fades and lifts each block in once, staggered, the first time Home is shown. */
@Composable
internal fun Reveal(index: Int, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(320, delayMillis = 40 * index)) +
            slideInVertically(tween(380, delayMillis = 40 * index, easing = FastOutSlowInEasing)) { it / 6 }
    ) { content() }
}

@Composable
internal fun AnimatedAmount(value: Double?, style: androidx.compose.ui.text.TextStyle) {
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
        QuickAction(Icons.AutoMirrored.Filled.ReceiptLong, "Add bill", actions.addBill)
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
internal fun HomeSection(title: String, icon: ImageVector, linkLabel: String, onLink: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
internal fun HomeRow(icon: ImageVector, title: String, subtitle: String, value: String, valueColor: Color, onClick: () -> Unit) {
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
        if (value.isEmpty()) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
