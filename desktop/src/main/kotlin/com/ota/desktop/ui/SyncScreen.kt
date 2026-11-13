package com.ota.desktop.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.PartyType
import com.ota.desktop.AppPaths
import com.ota.desktop.export.SheetTable
import com.ota.desktop.export.XlsxExport
import com.ota.desktop.dash.DashFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun SyncScreen() {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val status by app.engine.status.collectAsState()
    val staff by app.staff.collectAsState()
    val session by app.auth.session.collectAsState()
    val admin by app.admin.collectAsState()
    val testAccount by app.testAccount.collectAsState()
    val u = status.usage
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Sync & Cloud", "This laptop keeps every record. The cloud only carries recent data between the phones and this laptop.") {
            if (admin) OutlinedButton(enabled = !status.running, onClick = { scope.launch { app.engine.sync(maintenance = true); toast("Clean-up finished") } }) {
                Text("Run clean-up now")
            }
            Button(enabled = !status.running, onClick = { scope.launch { app.engine.sync() } }) {
                if (status.running) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(16.dp).height(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                IconText(Icons.Default.Refresh, if (status.running) "Syncing…" else "Sync now")
            }
        }
        if (testAccount) {
            Banner(
                "Test account. Everything made here is test data: only this login sees it, it has its own order and advice numbers, " +
                    "and it never counts in real totals, Insights or Accounts. Its PDFs go to ${AppPaths.testDocuments.path}.",
                Brand.Blue
            ) {
                OutlinedButton(enabled = !clearing && !status.running, onClick = { confirmClear = true }) {
                    if (clearing) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(16.dp).height(16.dp), strokeWidth = 2.dp, color = Brand.Red)
                    Text("Clear all my test data", color = Brand.Red)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (status.storageFull) {
            Banner("The cloud database is full or read-only. Changes stay safely on this laptop and are uploaded once space is freed. Run clean-up or lower the retention days.", Brand.Red)
            Spacer(Modifier.height(10.dp))
        } else if (u?.warning == true) {
            Banner("Cloud storage is ${"%.0f".format(u.percent)}% full. Clean-up archives old orders here and frees the cloud.", Brand.Amber)
            Spacer(Modifier.height(10.dp))
        }
        status.error?.let { Banner("Last sync failed: $it", Brand.Red); Spacer(Modifier.height(10.dp)) }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Kpi("Last sync", ago(status.lastSyncAt), if (status.running) "Running now" else "Automatic every few minutes", Brand.Saffron, Modifier.weight(1f))
            Kpi("Waiting to upload", "${status.pending}", if (status.pending == 0) "Everything is in the cloud" else "Uploaded on the next sync", if (status.pending == 0) Brand.Green else Brand.Amber, Modifier.weight(1f))
            Kpi("Cloud retention", u?.let { "${it.retentionDays} days" } ?: "—", "Older orders live only here", Brand.Blue, Modifier.weight(1f))
            Kpi("Last clean-up", ago(status.lastMaintenanceAt), if (admin) "On connect and every 6 hours" else "Run by the admin's laptop", Brand.Muted, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.weight(1f), title = "Cloud storage") {
                if (u == null) Empty("Not known yet. It's read on the next sync.")
                else {
                    Gauge(u.percent, u.warnPercent, "${"%.1f".format(u.usedMb)} of ${u.limitMb} MB", Modifier.fillMaxWidth().height(150.dp))
                    Text("Warning at ${u.warnPercent}%. At 100% the cloud becomes read-only until space is freed.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            Panel(Modifier.weight(1f), title = "Account") {
                LabelValue("Signed in as", session?.email.orEmpty())
                LabelValue("Staff", staff?.let { "${it.name.ifBlank { "Staff" }} · code ${it.code}" + if (admin) " · Admin" else if (testAccount) " · Test account" else "" } ?: "Unknown until the first sync")
                LabelValue("Last clean-up result", status.lastPurge.orEmpty())
                LabelValue("Local data folder", AppPaths.home.path)
                LabelValue("PDF folder", AppPaths.documents.path)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { AppPaths.documents.mkdirs(); revealFile(AppPaths.documents) }) { Text("Open PDF folder") }
                    OutlinedButton(onClick = { revealFile(AppPaths.logs) }) { Text("Open logs") }
                }
            }
            Panel(Modifier.weight(1f), title = "Weather on Home") {
                Text("Kept on this laptop only, never synced.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                WeatherPlaceChooser()
            }
        }
        Spacer(Modifier.height(14.dp))
        UpdatesPanel(Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        if (admin) RetentionPanel(u?.retentionDays, u?.limitMb, u?.warnPercent)
        else Panel(Modifier.fillMaxWidth(), title = "Cloud settings") {
            Text("Cloud clean-up and the retention days are managed by the admins (Varun and Shubham).", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status.notices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth(), title = "Notes from the last sync") {
                status.notices.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) ClearTestDataDialog(onDismiss = { confirmClear = false }) {
        confirmClear = false
        clearing = true
        scope.launch {
            try {
                val removed = app.clearTestData().values.sum()
                toast("Cleared $removed test row${if (removed == 1) "" else "s"} from the cloud and emptied this laptop")
            } catch (e: Exception) {
                toast("Couldn't clear test data: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                clearing = false
            }
        }
    }
}

@Composable
internal fun ClearTestDataDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) = ConfirmDeleteDialog(
    what = "all test data",
    detail = "Deletes every order, bill, payment advice and catalog entry made with the test account, in the cloud and on this laptop, " +
        "including the test PDFs. Real business data and the Accounts books are not touched.",
    confirm = "Delete all",
    onDismiss = onDismiss,
    onConfirm = onConfirm
)

@Composable
private fun RetentionPanel(days: Int?, limit: Int?, warn: Int?) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var daysText by remember { mutableStateOf(days?.toString().orEmpty()) }
    var limitText by remember { mutableStateOf(limit?.toString().orEmpty()) }
    var warnText by remember { mutableStateOf(warn?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(days, limit, warn) {
        if (daysText.isEmpty()) daysText = days?.toString().orEmpty()
        if (limitText.isEmpty()) limitText = limit?.toString().orEmpty()
        if (warnText.isEmpty()) warnText = warn?.toString().orEmpty()
    }
    Panel(Modifier.fillMaxWidth(), title = "Cloud settings (admin)") {
        Text(
            "Orders, bills and payment advices older than the retention days are removed from the cloud, but only after this laptop has stored them. " +
                "Lower the days if the cloud gets full. Buyers and suppliers always stay in the cloud.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(daysText, { daysText = it.filter(Char::isDigit) }, "Retention days", Modifier.width(170.dp), number = true)
            Field(limitText, { limitText = it.filter(Char::isDigit) }, "Storage limit (MB)", Modifier.width(170.dp), number = true)
            Field(warnText, { warnText = it.filter(Char::isDigit) }, "Warn at (%)", Modifier.width(150.dp), number = true)
            Button(enabled = !busy, onClick = {
                val d = daysText.toIntOrNull(); val l = limitText.toIntOrNull(); val w = warnText.toIntOrNull()
                if (d == null || l == null || w == null) { toast("Fill in all three numbers"); return@Button }
                busy = true
                scope.launch {
                    runCatching { app.cloudSettings.update(d, l, w); app.engine.sync(maintenance = true) }
                        .onSuccess { toast("Saved. Clean-up ran with $d days retention.") }
                        .onFailure { toast("Couldn't save: ${it.message}") }
                    busy = false
                }
            }) { Text(if (busy) "Saving…" else "Save & clean up") }
        }
    }
}

@Composable
fun ExportScreen() {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun export(key: String, suggested: String, build: () -> List<SheetTable>) {
        val file = chooseSaveFile("Export to Excel", suggested) ?: return
        busy = key
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { XlsxExport.write(file, build()) } }
                .onSuccess { last = it; toast("Saved ${it.name}") }
                .onFailure { toast("Export failed: ${it.message}") }
            busy = null
        }
    }

    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Export", "Excel (.xlsx) files that open in Excel, Google Sheets or LibreOffice")
        val cards = listOf(
            Triple("all", "Everything", "Orders, order items, bills, payment advices, advice lines, buyers and suppliers, one sheet each."),
            Triple("dash", "Insights (12 months)", "Every Insights chart's numbers for the last 12 months: summary, trends, top parties, ageing, staff."),
            Triple("orders", "Orders and items", "All orders with one row per item, with units."),
            Triple("bills", "Bills and payments", "Bills with balances and status, payment advices and their lines."),
            Triple("parties", "Buyers and suppliers", "The full catalog with addresses, GSTIN and bank details.")
        )
        cards.chunked(3).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (key, title, text) ->
                    Panel(Modifier.weight(1f).fillMaxHeight(), title = title) {
                        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
                        Spacer(Modifier.weight(1f))
                        Button(enabled = busy == null, onClick = {
                            when (key) {
                                "all" -> export(key, "OTA all data $today.xlsx") { app.export.everything() }
                                "dash" -> export(key, "OTA insights $today.xlsx") {
                                    app.analytics.dashboard(DashFilter(today.minusMonths(12).plusDays(1), today)).tables()
                                }
                                "orders" -> export(key, "OTA orders $today.xlsx") { app.orders.list(limit = 1_000_000).let { listOf(app.export.orders(it), app.export.items(it)) } }
                                "bills" -> export(key, "OTA bills and payments $today.xlsx") {
                                    val lines = app.analytics.paymentLines()
                                    listOf(app.export.bills(app.payments.bills()), app.export.advices(app.payments.search(), lines), app.export.lines(lines))
                                }
                                else -> export(key, "OTA buyers and suppliers $today.xlsx") {
                                    PartyType.entries.map { app.export.parties(it, app.catalog.list(it)) }
                                }
                            }
                        }) { IconText(Icons.Default.Share, if (busy == key) "Exporting…" else "Export") }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(14.dp))
        }
        last?.let { f ->
            Banner("Last export: ${f.path}", Brand.Green) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openFile(f) }) { Text("Open") }
                    OutlinedButton(onClick = { revealFile(f) }) { Text("Show in folder") }
                }
            }
        }
    }
}
