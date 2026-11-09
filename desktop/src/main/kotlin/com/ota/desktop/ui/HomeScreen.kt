package com.ota.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.ui.RoamingCat
import com.example.otaapplication.ui.WeatherCard
import com.example.otaapplication.weather.Weather
import com.ota.desktop.dash.Home
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TODAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

/** Height of the strip under the cards where the cat walks, so it never sits on a button. */
private val CAT_FLOOR = 70.dp

/**
 * Start screen, kept light: greeting with the local weather, the day's shortcuts and at most a
 * couple of things that need attention. Numbers and charts live in Insights.
 */
@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val staff by app.staff.collectAsState()
    val status by app.engine.status.collectAsState()
    val home by rememberLoad<Home?>(null) { app.analytics.home() }
    val weather by app.weather.view.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    var choosePlace by remember { mutableStateOf(false) }

    // Cached for 45 minutes, so this only reaches the internet when the forecast is stale.
    LaunchedEffect(Unit) {
        while (true) {
            app.weather.refresh()
            delay(Weather.REFRESH_MS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                val today = LocalDate.now()
                val fyStart = if (today.monthValue >= 4) today.year else today.year - 1
                val hello = when (LocalTime.now().hour) { in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
                val name = staff?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
                val gap = Arrangement.spacedBy(14.dp)

                Row(horizontalArrangement = gap, modifier = Modifier.height(IntrinsicSize.Min)) {
                    WeatherCard(
                        weather = weather?.weather,
                        note = weather?.note,
                        greeting = if (name != null) "$hello, $name" else hello,
                        date = "${today.format(TODAY)} · FY $fyStart-${(fyStart + 1) % 100}",
                        onClick = { choosePlace = true },
                        modifier = Modifier.weight(1.5f).fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        iconSize = 76.dp,
                        trailing = { SyncPill(status.running, status.pending, status.error, status.lastSyncAt) { scope.launch { app.engine.sync() } } }
                    )
                    NeedsAttention(home, Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = gap) {
                    Action(Icons.Default.Add, "New order", "Share the PDF", Modifier.weight(1f)) { nav.go(Section.ORDERS, Target.NewOrder) }
                    Action(Icons.Default.List, "Add bill", "From the paper bill", Modifier.weight(1f)) { nav.go(Section.BILLS, Target.NewBill) }
                    Action(Icons.Default.Send, "New advice", "Pay a supplier's bills", Modifier.weight(1f)) { nav.go(Section.ADVICES, Target.NewAdvice(null)) }
                    Action(Icons.Default.AccountBox, "Buyers & suppliers", "Addresses and GSTIN", Modifier.weight(1f)) { nav.go(Section.CATALOG) }
                    Action(Icons.Default.Share, "Export", "Excel files", Modifier.weight(1f)) { nav.go(Section.EXPORT) }
                }
                Spacer(Modifier.height(14.dp))
                InsightsLink(home) { nav.go(Section.DASHBOARD) }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.fillMaxWidth().height(CAT_FLOOR))
        }
        // Rests while the window is in the background or minimised.
        RoamingCat(active = focused, reduceMotion = false, modifier = Modifier.fillMaxSize(), floor = 4.dp, catWidth = 72.dp)
    }
    if (choosePlace) WeatherPlaceDialog { choosePlace = false }
}

@Composable
private fun SyncPill(running: Boolean, pending: Int, error: String?, lastSyncAt: Long?, onSync: () -> Unit) {
    val text = when {
        running -> "Syncing…"
        error != null -> if (error.startsWith("Offline")) "Offline" else "Sync problem"
        pending > 0 -> "$pending to upload"
        else -> "Synced ${ago(lastSyncAt)}"
    }
    Surface(onClick = onSync, enabled = !running, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, contentDescription = "Sync now", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** At most two light reminders: orders waiting for their number and bills still to come. */
@Composable
private fun NeedsAttention(h: Home?, modifier: Modifier) {
    val nav = LocalNav.current
    Panel(modifier, title = "Needs attention") {
        when {
            h == null -> Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            h.pendingNumbers == 0 && h.awaitingBill == 0 -> AttentionRow(Icons.Default.CheckCircle, Brand.Green, "All caught up", "Every order has its number and its bill", null)
            else -> {
                if (h.pendingNumbers > 0) AttentionRow(
                    Icons.Default.Warning, Brand.Amber,
                    "${h.pendingNumbers} order${if (h.pendingNumbers == 1) "" else "s"} waiting for a number",
                    "Saved offline. They get their number on the next sync."
                ) { nav.go(Section.ORDERS) }
                if (h.awaitingBill > 0) AttentionRow(
                    Icons.Default.DateRange, Brand.Blue,
                    "${h.awaitingBill} order${if (h.awaitingBill == 1) "" else "s"} waiting for a bill",
                    "Add the supplier's bill when the hard copy comes."
                ) { nav.go(Section.ORDERS) }
            }
        }
    }
}

@Composable
private fun AttentionRow(icon: ImageVector, color: Color, title: String, sub: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Brand.Muted)
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Brand.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brand.SaffronSoft), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Brand.SaffronDark, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InsightsLink(h: Home?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.background(Brush.horizontalGradient(listOf(Brand.SaffronSoft, Brand.SaffronTint))).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brand.Saffron), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        h?.takeIf { it.outstanding > 0 }?.let { "${moneyFull(it.outstanding)} outstanding" },
                        h?.let { "${it.ordersThisMonth} orders this month" },
                        "trends, ageing and top parties"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onOpen) { Text("Open Insights") }
        }
    }
}
