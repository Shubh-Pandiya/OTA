package com.ota.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.accounts.AccGroup
import com.ota.desktop.accounts.Account
import com.ota.desktop.accounts.AccountsExporter
import com.ota.desktop.accounts.DatePreset
import com.ota.desktop.accounts.ExportFormat
import com.ota.desktop.accounts.ExportLayout
import com.ota.desktop.accounts.ExportRequest
import com.ota.desktop.accounts.ExportSheet
import com.ota.desktop.accounts.Paise
import com.ota.desktop.accounts.PartyKind
import com.ota.desktop.accounts.ReportFilter
import com.ota.desktop.accounts.ReportTable
import com.ota.desktop.accounts.Side
import com.ota.desktop.accounts.VType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** What to preselect when the export dialog opens from a tab. */
data class ExportPreset(val sheets: Set<ExportSheet>, val accounts: Set<String> = emptySet(), val cashAccountId: String? = null)

private fun <T> SnapshotStateList<T>.toggle(v: T) { if (v in this) remove(v) else add(v) }

/**
 * One export dialog for every Accounts table: what to export, format and layout, columns, extras and filters.
 * The tables come from the signed-in login's books only (test and production books are separate schemas).
 */
@Composable
fun AccountsExportDialog(preset: ExportPreset, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val sheets = remember { mutableStateListOf(*preset.sheets.toTypedArray()) }
    var format by remember { mutableStateOf(ExportFormat.XLSX) }
    var layout by remember { mutableStateOf(ExportLayout.WORKBOOK) }
    var datePreset by remember { mutableStateOf(DatePreset.THIS_FY) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf(LocalDate.now().dmy()) }
    val picked = remember { mutableStateListOf(*preset.accounts.toTypedArray()) }
    var partyText by remember { mutableStateOf("") }
    val groups = remember { mutableStateListOf<AccGroup>() }
    val types = remember { mutableStateListOf<VType>() }
    val statuses = remember { mutableStateListOf<PaymentStatus>() }
    var minText by remember { mutableStateOf("") }
    var maxText by remember { mutableStateOf("") }
    var side by remember { mutableStateOf(Side.ANY) }
    var text by remember { mutableStateOf("") }
    var hideZero by remember { mutableStateOf(true) }
    var openingClosing by remember { mutableStateOf(true) }
    var subtotals by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(true) }
    val excluded = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }

    val accounts by rememberLoad(emptyList<Account>()) { app.accounts.accounts() }
    val (from, to) = if (datePreset == DatePreset.CUSTOM) parseDay(fromText) to parseDay(toText) else datePreset.bounds(LocalDate.now())
    val filter = ReportFilter(
        accounts = picked.toSet(), groups = groups.toSet(), types = types.toSet(),
        minAmount = Paise.parse(minText), maxAmount = Paise.parse(maxText), side = side, text = text,
        billStatus = statuses.toSet(), hideZero = hideZero, openingClosing = openingClosing, subtotals = subtotals, runningBalance = running
    )
    val base = ExportRequest(sheets.toSet(), from, to, filter, cashAccountId = preset.cashAccountId)
    // Preview: which tables, how many rows, and the columns to choose from (debounced while typing).
    val preview by rememberLoad<List<ReportTable>?>(null, base) {
        delay(300)
        runCatching { app.accountsExporter.tables(base).map { it.second } }.getOrNull()
    }
    val columnNames = preview.orEmpty().flatMap { t -> t.columns.map { ReportTable.columnName(it.title) } }.distinct()
    val request = base.copy(format = format, layout = layout, excludedColumns = excluded.toSet())

    fun export() {
        val stamp = LocalDate.now()
        val target: File = if (layout == ExportLayout.WORKBOOK) {
            val title = sheets.singleOrNull()?.label ?: "accounts"
            chooseSaveFile("Export accounts", AccountsExporter.fileName("OTA $title $stamp.${format.ext}")) ?: return
        } else {
            // A new folder, named in the Save dialog, holds the files.
            chooseSaveFile("Name a new folder for the files", "OTA accounts $stamp") ?: return
        }
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.accountsExporter.export(request, target) } }
                .onSuccess { files ->
                    toast(if (files.size == 1) "Saved ${files.single().name}" else "Saved ${files.size} files in ${target.name}")
                    if (files.size == 1) openFile(files.single()) else revealFile(files.first())
                    onDismiss()
                }
                .onFailure { toast("Export failed: ${it.message}") }
            busy = false
        }
    }

    WideDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export accounts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section("Reports") {
                    ChipRow {
                        ExportSheet.entries.forEach { s -> Chip(s.label, s in sheets) { sheets.toggle(s) } }
                        TextButton(onClick = { sheets.clear(); sheets.addAll(ExportSheet.entries) }) { Text("All") }
                        TextButton(onClick = { sheets.clear() }) { Text("None") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Section("Format", Modifier.weight(1f)) { ChipRow { ExportFormat.entries.forEach { f -> Chip(f.label, format == f) { format = f } } } }
                    Section("Layout", Modifier.weight(2f)) { ChipRow { ExportLayout.entries.forEach { l -> Chip(l.label, layout == l) { layout = l } } } }
                }
                Section("Period") {
                    ChipRow {
                        DatePreset.entries.forEach { p -> Chip(p.label, datePreset == p) { datePreset = p } }
                    }
                    if (datePreset == DatePreset.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField(fromText, { fromText = it }, "From", Modifier.width(160.dp), optional = true)
                        DateField(toText, { toText = it }, "To (and as on)", Modifier.width(160.dp), optional = true)
                    }
                    Text(
                        "${if (from == null) "From the start" else from.dmy()} to ${to?.dmy() ?: "today"} · balance sheet, trial balance and ageing are as on the end date",
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted
                    )
                }
                HorizontalDivider(color = Brand.Line)
                Section("Buyers, suppliers and accounts", hint = if (picked.isEmpty()) "All" else "${picked.size} picked") {
                    PartyPicker(accounts, picked, partyText) { partyText = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Section("Account groups", Modifier.weight(1f)) { ChipRow { AccGroup.entries.forEach { g -> Chip(g.label, g in groups) { groups.toggle(g) } } } }
                    Section("Voucher types", Modifier.weight(1f)) { ChipRow { VType.entries.forEach { t -> Chip("${t.prefix} · ${t.label}", t in types) { types.toggle(t) } } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(minText, { minText = it }, "Min amount ₹", Modifier.width(140.dp), number = true)
                    Field(maxText, { maxText = it }, "Max amount ₹", Modifier.width(140.dp), number = true)
                    Field(text, { text = it }, "Narration contains", Modifier.weight(1f), trailing = { Icon(Icons.Default.Search, null) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Section("Side", Modifier.weight(1f)) { ChipRow { Side.entries.forEach { s -> Chip(s.label, side == s) { side = s } } } }
                    Section("Bill status (trade statements, outstanding)", Modifier.weight(1f)) {
                        ChipRow { PaymentStatus.entries.forEach { s -> Chip(if (s == PaymentStatus.UNPAID) "Due" else s.label, s in statuses) { statuses.toggle(s) } } }
                    }
                }
                Section("Include") {
                    ChipRow(spacing = 16.dp) {
                        Check("Hide zero balances", hideZero) { hideZero = it }
                        Check("Opening / closing balances", openingClosing) { openingClosing = it }
                        Check("Subtotals", subtotals) { subtotals = it }
                        Check("Running balance", running) { running = it }
                    }
                }
                Section("Columns", hint = "Untick to leave out") {
                    if (columnNames.isEmpty()) Text(if (preview == null) "Loading…" else "Pick a report", color = Brand.Muted)
                    ChipRow { columnNames.forEach { c -> Chip(c, c !in excluded) { excluded.toggle(c) } } }
                }
            }
        },
        confirmButton = {
            Button(onClick = ::export, enabled = !busy && sheets.isNotEmpty() && preview?.isNotEmpty() == true) {
                IconText(Icons.Default.Share, if (busy) "Exporting…" else "Export ${format.label}")
            }
        },
        dismissButton = {
            val p = preview
            Text(
                when {
                    sheets.isEmpty() -> "Pick at least one report"
                    p == null -> "Counting…"
                    p.isEmpty() -> "Nothing matches these filters"
                    else -> "${p.size} table${if (p.size == 1) "" else "s"} · ${p.sumOf { it.rows.size }} rows"
                },
                Modifier.padding(end = 8.dp), color = Brand.Muted, style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            hint?.let { Text("  ·  $it", style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
        }
        content()
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Multi-select of buyers, suppliers and books accounts, with a search box and quick picks. */
@Composable
private fun PartyPicker(accounts: List<Account>, picked: SnapshotStateList<String>, query: String, onQuery: (String) -> Unit) {
    val tokens = SearchText.tokens(query)
    val matches = accounts.filter { a -> SearchText.normalize("${a.code} ${a.name} ${a.kindLabel}").let { hay -> tokens.all { it in hay } } }
        .sortedBy { it.id !in picked }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(query, onQuery, "Find a buyer, supplier or account", Modifier.weight(1f), trailing = { Icon(Icons.Default.Search, null) })
        TextButton(onClick = { picked.clear(); picked.addAll(accounts.filter { it.party == PartyKind.BUYER }.map { it.id }) }) { Text("All buyers") }
        TextButton(onClick = { picked.clear(); picked.addAll(accounts.filter { it.party == PartyKind.SUPPLIER }.map { it.id }) }) { Text("All suppliers") }
        TextButton(onClick = { picked.clear() }, enabled = picked.isNotEmpty()) { Text("Clear") }
    }
    if (picked.isNotEmpty()) ChipRow {
        accounts.filter { it.id in picked }.take(40).forEach { a -> Chip("${a.name} ✕", true) { picked.remove(a.id) } }
        if (picked.size > 40) Text("+${picked.size - 40} more", Modifier.padding(top = 8.dp), color = Brand.Muted)
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 170.dp).border(1.dp, Brand.Line, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState())
    ) {
        matches.take(200).forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { picked.toggle(a.id) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(a.id in picked, { picked.toggle(a.id) })
                Text(a.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(a.kindLabel, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
            }
        }
        if (matches.isEmpty()) Text("No match", Modifier.padding(10.dp), color = Brand.Muted)
    }
}
