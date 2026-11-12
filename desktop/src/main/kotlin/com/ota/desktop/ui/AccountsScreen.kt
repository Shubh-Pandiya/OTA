package com.ota.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.example.otaapplication.data.SearchText
import com.ota.desktop.accounts.AccGroup
import com.ota.desktop.accounts.Account
import com.ota.desktop.accounts.AccountsError
import com.ota.desktop.accounts.AccountsExport
import com.ota.desktop.accounts.AuditEntry
import com.ota.desktop.accounts.ColKind
import com.ota.desktop.accounts.PartyAccount
import com.ota.desktop.accounts.PartyKind
import com.ota.desktop.accounts.Paise
import com.ota.desktop.accounts.RLink
import com.ota.desktop.accounts.ReportKind
import com.ota.desktop.accounts.ReportTable
import com.ota.desktop.accounts.RowStyle
import com.ota.desktop.accounts.VType
import com.ota.desktop.accounts.Voucher
import com.ota.desktop.accounts.VoucherDraft
import com.ota.desktop.accounts.VoucherLine
import com.ota.desktop.accounts.VoucherSummary
import com.ota.desktop.accounts.fyStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A ledger book with columns: the Accounts section icon (icons-core has no such glyph). */
val AccountsIcon: ImageVector = ImageVector.Builder("Accounts", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes("M4,10h3v7H4zM10.5,10h3v7h-3zM2,19h20v3H2zM17,10h3v7h-3zM12,1L2,6v2h20V6z"), fill = SolidColor(Color.Black)
).build()

enum class AccTab(val label: String) {
    VOUCHERS("Day book & vouchers"), LEDGERS("Ledgers"), REPORTS("Reports"), PARTIES("Parties & commission"),
    CHART("Chart of accounts"), AUDIT("Audit log")
}

/** The voucher dialog request: an existing voucher, or a new one of a type. */
private sealed interface VoucherOpen {
    data class Existing(val id: String) : VoucherOpen
    data class New(val type: VType) : VoucherOpen
}

/** A date range picker's state: a preset (financial year by default) or custom dates. */
class Period(range: Range = Range.FY) {
    var range by mutableStateOf(range)
    var from by mutableStateOf("")
    var to by mutableStateOf("")

    fun bounds(today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> = when (range) {
        Range.CUSTOM -> parseDay(from) to parseDay(to)
        Range.FY -> fyStart(today).let { it to it.plusYears(1).minusDays(1) }
        else -> range.bounds(today)
    }
}

/** Accounts: the agency's own double-entry books, kept on this laptop only (never synced). */
@Composable
fun AccountsScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableStateOf(AccTab.VOUCHERS) }
    var ledgerAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<VoucherOpen?>(null) }
    var reportKind by rememberSaveable { mutableStateOf(ReportKind.TRIAL_BALANCE) }
    LaunchedEffect(nav.target) {
        (nav.take(Section.ACCOUNTS) as? Target.Accounts)?.let { t ->
            tab = t.tab
            t.accountId?.let { ledgerAccount = it }
            t.report?.let { reportKind = it }
            t.voucherId?.let { open = VoucherOpen.Existing(it) }
        }
    }
    val onLink: (RLink) -> Unit = { link ->
        when (link) {
            is RLink.Voucher -> open = VoucherOpen.Existing(link.id)
            is RLink.Bill -> nav.go(Section.BILLS, Target.Bill(link.id))
            is RLink.Advice -> nav.go(Section.ADVICES, Target.Advice(link.id))
            is RLink.Account -> { ledgerAccount = link.id; tab = AccTab.LEDGERS }
        }
    }
    val openLedger: (String) -> Unit = { ledgerAccount = it; tab = AccTab.LEDGERS }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Accounts", "The agency's books · kept on this laptop only, never synced to the cloud") {
            VType.manual.forEach { t ->
                if (t == VType.JOURNAL) Button(onClick = { open = VoucherOpen.New(t) }) { IconText(Icons.Default.Add, "Journal") }
                else OutlinedButton(onClick = { open = VoucherOpen.New(t) }) { Text(t.label) }
            }
        }
        TabBar(tab) { tab = it }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AccTab.VOUCHERS -> VouchersTab { open = VoucherOpen.Existing(it) }
                AccTab.LEDGERS -> LedgersTab(ledgerAccount, { ledgerAccount = it }, onLink)
                AccTab.REPORTS -> ReportsTab(reportKind, { reportKind = it }, onLink)
                AccTab.PARTIES -> PartiesTab(openLedger)
                AccTab.CHART -> ChartTab(openLedger)
                AccTab.AUDIT -> AuditTab { open = VoucherOpen.Existing(it) }
            }
        }
    }
    open?.let { o ->
        VoucherDialog(
            id = (o as? VoucherOpen.Existing)?.id,
            newType = (o as? VoucherOpen.New)?.type ?: VType.JOURNAL,
            onDismiss = { open = null },
            onOpenBill = { open = null; nav.go(Section.BILLS, Target.Bill(it)) }
        )
    }
}

@Composable
private fun TabBar(selected: AccTab, onSelect: (AccTab) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Brand.Line, RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        AccTab.entries.forEach { t ->
            val on = t == selected
            val bg by animateColorAsState(if (on) Brand.SaffronSoft else Color.Transparent)
            Text(
                t.label,
                Modifier.clip(RoundedCornerShape(7.dp)).background(bg).clickable { onSelect(t) }.padding(horizontal = 14.dp, vertical = 7.dp),
                color = if (on) Brand.SaffronDark else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ------------------------------------------------------------------------------------ shared pieces

@Composable
private fun PeriodBar(period: Period, presets: List<Range> = listOf(Range.FY, Range.D30, Range.D90, Range.M12, Range.ALL, Range.CUSTOM)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(Modifier.weight(1f, fill = false)) { presets.forEach { r -> Chip(r.label, period.range == r) { period.range = r } } }
        if (period.range == Range.CUSTOM) {
            DateField(period.from, { period.from = it }, "From", Modifier.width(140.dp), optional = true)
            DateField(period.to, { period.to = it }, "To", Modifier.width(140.dp), optional = true)
        }
    }
}

private fun safeName(text: String) = text.replace(Regex("[\\\\/:*?\"<>|]"), "-").replace(Regex("\\s+"), " ").trim()

/** XLSX and PDF export buttons for one report (or a set of reports for XLSX). */
@Composable
private fun ExportButtons(tables: () -> List<ReportTable>) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    fun export(ext: String) {
        val list = tables()
        if (list.isEmpty()) return
        val name = safeName("OTA ${list.first().title} ${LocalDate.now()}.$ext")
        val file = chooseSaveFile("Export ${list.first().title}", name) ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { if (ext == "pdf") AccountsExport.pdf(file, list.first()) else AccountsExport.xlsx(file, list) }
            }.onSuccess { toast("Saved ${it.name}"); openFile(it) }.onFailure { toast("Export failed: ${it.message}") }
        }
    }
    OutlinedButton(onClick = { export("xlsx") }) { IconText(Icons.Default.Share, "XLSX") }
    OutlinedButton(onClick = { export("pdf") }) { IconText(Icons.Default.Share, "PDF") }
}

private fun colWidth(kind: ColKind): Dp? = when (kind) {
    ColKind.MONEY -> 118.dp
    ColKind.DATE -> DATE_W
    ColKind.NUMBER -> 64.dp
    ColKind.TEXT -> null
}

/** A [ReportTable] on screen: headings and totals styled, linked rows clickable for drill-down. */
@Composable
fun ReportView(table: ReportTable?, onLink: (RLink) -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    if (table == null) {
        Column(modifier) { Empty(if (loading) "Loading…" else "Nothing to show") }
        return
    }
    // Text columns share the spare width. On a narrow panel the foldable columns (type, narration) merge into their
    // neighbours first; if it is still too narrow, text keeps a readable minimum and the table scrolls sideways.
    val folded = remember(table) { fold(table) }
    BoxWithConstraints(modifier) {
        val shown = if (maxWidth >= minWidth(table) || folded === table) table else folded
        val wide = maxWidth >= minWidth(shown)
        val scroll = rememberScrollState()
        Column(if (wide) Modifier.fillMaxSize() else Modifier.horizontalScroll(scroll).width(minWidth(shown)).fillMaxHeight()) {
            ReportRows(shown, onLink)
        }
    }
}

private val MIN_TEXT_W = 96.dp

private fun minWidth(table: ReportTable): Dp =
    table.columns.fold(24.dp + 10.dp * (table.columns.size - 1)) { acc, c -> acc + (colWidth(c.kind) ?: (MIN_TEXT_W * c.weight).coerceAtLeast(36.dp)) }

/** [table] with its foldable columns merged into their targets ("CM/2627/0001 · Commission"), or [table] itself if none. */
private fun fold(table: ReportTable): ReportTable {
    val drop = table.columns.withIndex().filter { it.value.foldInto != null }.map { it.index }.toSet()
    if (drop.isEmpty()) return table
    val keep = table.columns.indices.filter { it !in drop }
    fun text(v: Any?, kind: ColKind) = AccountsExport.cellText(v, kind).takeIf { it.isNotBlank() && it != "—" }
    return table.copy(
        columns = keep.map { i ->
            val c = table.columns[i]
            c.copy(weight = c.weight + table.columns.filter { it.foldInto == i }.sumOf { it.weight.toDouble() }.toFloat() * 0.5f)
        },
        rows = table.rows.map { r ->
            r.copy(cells = keep.map { i ->
                val extra = drop.filter { table.columns[it].foldInto == i }.mapNotNull { text(r.cells.getOrNull(it), table.columns[it].kind) }
                if (extra.isEmpty()) r.cells.getOrNull(i)
                else (listOfNotNull(text(r.cells.getOrNull(i), table.columns[i].kind)) + extra).joinToString(" · ")
            })
        }
    )
}

@Composable
private fun ReportRows(table: ReportTable, onLink: (RLink) -> Unit) {
    run {
        TableHeader(table.columns.map { c -> Col(c.title, c.weight, if (c.kind == ColKind.TEXT || c.kind == ColKind.DATE) TextAlign.Start else TextAlign.End, colWidth(c.kind)) })
        if (table.rows.isEmpty()) Empty("Nothing to show for this period.")
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(table.rows) { _, row ->
                val bold = row.style != RowStyle.NORMAL
                val bg = when (row.style) {
                    RowStyle.HEADING -> Brand.SaffronTint
                    RowStyle.TOTAL -> Color(0xFFF4EEE9)
                    RowStyle.NORMAL -> Color.Transparent
                }
                val content: @Composable RowScope.() -> Unit = {
                    table.columns.forEachIndexed { i, c ->
                        val text = AccountsExport.cellText(row.cells.getOrNull(i), c.kind)
                        val align = if (c.kind == ColKind.TEXT || c.kind == ColKind.DATE) TextAlign.Start else TextAlign.End
                        val color = if (c.kind == ColKind.MONEY && (row.cells.getOrNull(i) as? Double ?: 0.0) < 0) Brand.Red else Color.Unspecified
                        Cell(text, c.weight, align, bold, color, maxLines = 2, width = colWidth(c.kind))
                    }
                }
                val link = row.link
                if (link != null) Box(Modifier.background(bg)) { TableRow(false, { onLink(link) }, content) }
                else {
                    Row(
                        Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content
                    )
                    HorizontalDivider(color = Brand.Line)
                }
            }
        }
    }
}

/** A dialog wider than Material's 560dp limit, for the voucher's line grid. */
@Composable
private fun WideDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(24.dp).widthIn(max = 960.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 8.dp) {
            Column(Modifier.padding(24.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f, fill = false)) { text() }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    dismissButton(); confirmButton()
                }
            }
        }
    }
}

/** Runs [block] on IO; errors (validation or database) come back as a message. */
private suspend fun <T> attempt(block: () -> T): Result<T> = runCatching { withContext(Dispatchers.IO) { block() } }

private fun Throwable.friendly(): String = if (this is AccountsError) message.orEmpty() else "Couldn't save: ${message ?: javaClass.simpleName}"

// ------------------------------------------------------------------------------------ vouchers

@Composable
private fun VouchersTab(onOpen: (String) -> Unit) {
    val app = LocalApp.current
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<VType?>(null) }
    var deleted by rememberSaveable { mutableStateOf(false) }
    val period = remember { Period() }
    val (from, to) = period.bounds()
    val rows by rememberLoad(emptyList<VoucherSummary>(), text, type, deleted, from, to) { app.accounts.vouchers(from, to, text, type, deleted) }
    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(text, { text = it }, "Search voucher no, account, narration, amount…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
            ChipRow {
                Chip("All types", type == null) { type = null }
                VType.entries.forEach { t -> Chip("${t.label} (${t.prefix})", type == t) { type = if (type == t) null else t } }
                Chip("Show deleted", deleted) { deleted = !deleted }
            }
            PeriodBar(period)
        }
        TableHeader(
            listOf(
                Col("Date", 0f, width = DATE_W), Col("Voucher", 0f, width = 128.dp), Col("Type", 0f, width = 92.dp),
                Col("Debit → credit", 2f), Col("Narration", 2f), Col("Amount (₹)", 0f, TextAlign.End, 118.dp)
            )
        )
        if (rows.isEmpty()) Empty("No vouchers in this period. Use Journal, Payment, Receipt or Contra above; commission is posted from bills automatically.")
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.id }) { v ->
                val muted = if (v.deleted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                TableRow(false, { onOpen(v.id) }) {
                    Cell(v.date.dmy(), 0f, width = DATE_W, color = muted)
                    Text(
                        v.no, Modifier.width(128.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, color = muted,
                        style = MaterialTheme.typography.bodyMedium.merge(Numeric), textDecoration = if (v.deleted) TextDecoration.LineThrough else null
                    )
                    Box(Modifier.width(92.dp)) { Pill(if (v.deleted) "Deleted" else v.type.label, if (v.deleted) Brand.Muted else typeColor(v.type)) }
                    Cell2(v.debits, "to " + v.credits, 2f, color = muted)
                    Cell(v.narration, 2f, color = muted, maxLines = 2)
                    Cell(Paise.format(v.total), 0f, TextAlign.End, bold = true, width = 118.dp, color = muted)
                }
            }
        }
        val live = rows.filterNot { it.deleted }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("${live.size} vouchers", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("Total ₹${Paise.format(live.sumOf { it.total })}", style = MaterialTheme.typography.labelLarge.merge(Numeric), fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun typeColor(t: VType) = when (t) {
    VType.JOURNAL -> Brand.Blue
    VType.PAYMENT -> Brand.Red
    VType.RECEIPT -> Brand.Green
    VType.CONTRA -> Brand.Amber
    VType.COMMISSION -> Brand.SaffronDark
}

private data class EditLine(val key: Long, val accountId: String = "", val dr: String = "", val cr: String = "", val note: String = "")

private fun badAmount(text: String) = text.isNotBlank() && (Paise.parse(text) ?: -1L) < 0

@Composable
private fun VoucherDialog(id: String?, newType: VType, onDismiss: () -> Unit, onOpenBill: (String) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(emptyList<Account>()) }
    var original by remember { mutableStateOf<Voucher?>(null) }
    var ready by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(newType) }
    var date by remember { mutableStateOf(LocalDate.now().dmy()) }
    var narration by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<EditLine>() }
    var nextKey by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun line(accountId: String = "", dr: String = "", cr: String = "") = EditLine(nextKey++, accountId, dr, cr)

    LaunchedEffect(id) {
        val (list, v) = withContext(Dispatchers.IO) { app.accounts.accounts() to id?.let { app.accounts.voucher(it) } }
        accounts = list
        original = v
        if (v != null) {
            type = v.type; date = v.date.dmy(); narration = v.narration
            lines.addAll(v.lines.map { EditLine(nextKey++, it.accountId, Paise.input(it.dr), Paise.input(it.cr), it.note) })
        } else {
            val cash = list.firstOrNull { it.systemKey == "cash" }?.id.orEmpty()
            val bank = list.firstOrNull { it.systemKey == "bank" }?.id.orEmpty()
            lines.addAll(
                when (newType) {
                    VType.PAYMENT -> listOf(line(), line(cash))
                    VType.RECEIPT -> listOf(line(cash), line())
                    VType.CONTRA -> listOf(line(bank), line(cash))
                    else -> listOf(line(), line())
                }
            )
        }
        ready = true
    }

    val v = original
    val readOnly = v != null && (v.automatic || v.deleted)
    val dr = lines.sumOf { Paise.parse(it.dr)?.coerceAtLeast(0) ?: 0L }
    val cr = lines.sumOf { Paise.parse(it.cr)?.coerceAtLeast(0) ?: 0L }
    val byId = accounts.associateBy { it.id }

    fun save() {
        if (lines.any { badAmount(it.dr) || badAmount(it.cr) }) { error = "Fix the amounts marked in red"; return }
        val draft = VoucherDraft(id, type, parseDay(date), narration, lines.map { VoucherLine(it.accountId, Paise.parse(it.dr) ?: 0, Paise.parse(it.cr) ?: 0, it.note.trim()) })
        busy = true
        scope.launch {
            attempt { app.accounts.saveVoucher(draft) }
                .onSuccess { toast("Saved ${it.no}"); onDismiss() }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    WideDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (v == null) "New ${type.label.lowercase()} voucher" else "${v.type.label} voucher ${v.no}")
                if (v?.deleted == true) Pill("Deleted", Brand.Muted)
                if (v?.automatic == true) Pill("Automatic", Brand.SaffronDark)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) { Empty("Loading…"); return@Column }
                if (v?.automatic == true) Banner(
                    "Posted automatically from a bill's commission. Edit the bill, or the supplier's commission % in Parties & commission, to change it.",
                    Brand.SaffronDark
                ) { v.sourceBillId?.let { b -> TextButton(onClick = { onOpenBill(b) }) { Text("Open bill") } } }
                if (v?.deleted == true) Banner("This voucher was deleted; it no longer counts in any ledger or report. Its history is in the Audit log.", Brand.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (readOnly) LabelValue("Type", type.label, Modifier.width(180.dp))
                    else Select("Type", VType.manual, type, { type = it }, Modifier.width(180.dp), text = { "${it.label} (${it.prefix})" })
                    if (readOnly) LabelValue("Date", date, Modifier.width(150.dp)) else DateField(date, { date = it }, "Date", Modifier.width(150.dp))
                    LabelValue("Number", v?.no ?: "Given on save (${type.prefix}/…)", Modifier.weight(1f))
                }
                if (readOnly) LabelValue("Narration", narration)
                else Field(narration, { narration = it }, "Narration", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                if (!readOnly) Text(
                    when (type) {
                        VType.PAYMENT -> "Payment: debit what the money is for, credit the cash or bank account it left."
                        VType.RECEIPT -> "Receipt: debit the cash or bank account it came into, credit who paid."
                        VType.CONTRA -> "Contra: money moved between cash and bank accounts."
                        else -> "Journal: any balanced entry. Debits must equal credits."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Lines grid
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("#" to 24.dp, "Account" to null, "Debit (₹)" to 130.dp, "Credit (₹)" to 130.dp, "Note" to 170.dp, "" to 32.dp).forEach { (t, w) ->
                        Text(
                            t, if (w == null) Modifier.weight(1f) else Modifier.width(w), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (t.startsWith("Debit") || t.startsWith("Credit")) TextAlign.End else TextAlign.Start
                        )
                    }
                }
                lines.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (readOnly) {
                            Text(byId[l.accountId]?.label ?: "—", Modifier.weight(1f))
                            Text(Paise.parse(l.dr)?.let(Paise::format).orEmpty(), Modifier.width(130.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                            Text(Paise.parse(l.cr)?.let(Paise::format).orEmpty(), Modifier.width(130.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                            Text(l.note, Modifier.width(170.dp), maxLines = 2)
                            Spacer(Modifier.width(32.dp))
                        } else {
                            AccountPicker(accounts, l.accountId, { lines[i] = lines[i].copy(accountId = it) }, Modifier.weight(1f), error = error != null && l.accountId.isBlank())
                            CellField(l.dr, { lines[i] = lines[i].copy(dr = it, cr = if (it.isNotBlank()) "" else lines[i].cr) }, Modifier.width(130.dp), "0.00", TextAlign.End, badAmount(l.dr))
                            CellField(l.cr, { lines[i] = lines[i].copy(cr = it, dr = if (it.isNotBlank()) "" else lines[i].dr) }, Modifier.width(130.dp), "0.00", TextAlign.End, badAmount(l.cr))
                            CellField(l.note, { lines[i] = lines[i].copy(note = it) }, Modifier.width(170.dp), "Optional")
                            IconButton(onClick = { if (lines.size > 2) lines.removeAt(i) else lines[i] = line() }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Remove line", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(24.dp))
                    Box(Modifier.weight(1f)) {
                        if (!readOnly) TextButton(onClick = {
                            // A new line takes the difference, so a two-sided entry balances in one step.
                            val diff = dr - cr
                            lines += when {
                                diff > 0 -> line(cr = Paise.input(diff))
                                diff < 0 -> line(dr = Paise.input(-diff))
                                else -> line()
                            }
                        }) { IconText(Icons.Default.Add, "Add line") }
                        else Text("Total", fontWeight = FontWeight.SemiBold)
                    }
                    Text(Paise.format(dr), Modifier.width(130.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                    Text(Paise.format(cr), Modifier.width(130.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                    Text(
                        when {
                            dr == cr && dr > 0 -> "Balanced"
                            dr == cr -> ""
                            else -> "Difference ₹${Paise.format(kotlin.math.abs(dr - cr))}"
                        },
                        Modifier.width(210.dp), color = if (dr == cr) Brand.Green else Brand.Red, style = MaterialTheme.typography.labelLarge
                    )
                }
                error?.let { Banner(it, Brand.Red) }
            }
        },
        confirmButton = {
            if (!readOnly) Button(onClick = ::save, enabled = ready && !busy) { Text(if (v == null) "Save voucher" else "Save changes") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v != null && !readOnly) TextButton(onClick = { confirmDelete = true }) { IconText(Icons.Default.Delete, "Delete") }
                TextButton(onClick = onDismiss) { Text(if (readOnly) "Close" else "Cancel") }
            }
        }
    )
    if (confirmDelete && v != null) ConfirmDialog(
        "Delete ${v.no}?", "It stops counting in every ledger and report. The audit log keeps a copy of it.", "Delete", danger = true,
        onDismiss = { confirmDelete = false }
    ) {
        confirmDelete = false
        scope.launch {
            attempt { app.accounts.deleteVoucher(v.id) }.onSuccess { toast("Deleted ${v.no}"); onDismiss() }.onFailure { error = it.friendly() }
        }
    }
}

/** Account input with type-ahead over code, name, group and party kind. */
@Composable
private fun AccountPicker(accounts: List<Account>, selectedId: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, error: Boolean = false) {
    val selected = accounts.firstOrNull { it.id == selectedId }
    var query by remember(selectedId, accounts) { mutableStateOf(selected?.label.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val matches = remember(query, accounts, selectedId) {
        val tokens = if (query == selected?.label) emptyList() else SearchText.tokens(query)
        accounts.filter { it.active || it.id == selectedId }
            .filter { a -> SearchText.normalize("${a.code} ${a.name} ${a.kindLabel} ${a.subgroup}").let { hay -> tokens.all { it in hay } } }
            .take(40)
    }
    Box(modifier.onFocusChanged {
        focused = it.hasFocus
        if (!it.hasFocus) query = selected?.label.orEmpty()
        else dismissed = false
    }) {
        CellField(query, { query = it; dismissed = false }, Modifier.fillMaxWidth(), "Type to find an account", error = error)
        DropdownMenu(
            expanded = focused && !dismissed && matches.isNotEmpty(),
            onDismissRequest = { dismissed = true },
            properties = PopupProperties(focusable = false),
            modifier = Modifier.widthIn(min = 340.dp).heightIn(max = 320.dp)
        ) {
            matches.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.kindLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onPick(a.id); query = a.label; dismissed = true }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------ ledgers

private enum class LedgerFilter(val label: String) { ALL("All"), BOOKS("Accounts"), BUYERS("Buyers"), SUPPLIERS("Suppliers") }

@Composable
private fun LedgersTab(accountId: String?, onSelect: (String) -> Unit, onLink: (RLink) -> Unit) {
    val app = LocalApp.current
    var text by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LedgerFilter.ALL) }
    var trade by rememberSaveable { mutableStateOf(false) }
    // Opened straight onto a ledger (drill-down), the list starts folded away so the ledger gets the full width.
    var listOpen by rememberSaveable { mutableStateOf(accountId == null) }
    val period = remember { Period() }
    val data by rememberLoad(emptyList<Account>() to emptyMap<String, Long>()) { app.accounts.accounts() to app.accounts.closingBalances() }
    val (accounts, balances) = data
    val tokens = SearchText.tokens(text)
    val shown = accounts.filter { a ->
        when (filter) {
            LedgerFilter.ALL -> true
            LedgerFilter.BOOKS -> !a.isParty
            LedgerFilter.BUYERS -> a.party == PartyKind.BUYER
            LedgerFilter.SUPPLIERS -> a.party == PartyKind.SUPPLIER
        } && SearchText.normalize("${a.code} ${a.name} ${a.kindLabel}").let { hay -> tokens.all { it in hay } }
    }
    val account = accounts.firstOrNull { it.id == accountId }
    val (from, to) = period.bounds()
    val table by rememberLoad<ReportTable?>(null, accountId, from, to, trade) {
        accountId?.let { if (trade) app.accountReports.partyStatement(it, from, to) else app.accountReports.ledger(it, from, to) }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (listOpen || account == null) Panel(Modifier.width(320.dp).fillMaxHeight(), padding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(text, { text = it }, "Find an account or party", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                ChipRow { LedgerFilter.entries.forEach { f -> Chip(f.label, filter == f) { filter = f } } }
            }
            HorizontalDivider(color = Brand.Line)
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { a ->
                    TableRow(a.id == accountId, { onSelect(a.id) }) {
                        Cell2(a.name, a.kindLabel, 1f, bold = a.id == accountId, color = if (a.active) Color.Unspecified else Brand.Muted)
                        Cell(Paise.drCr(balances[a.id] ?: 0L), 0f, TextAlign.End, width = 120.dp)
                    }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = PaddingValues(0.dp)) {
            if (account == null) { Empty("Pick an account, buyer or supplier to see its ledger"); return@Panel }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { listOpen = !listOpen }) { Icon(Icons.Default.Menu, if (listOpen) "Hide the accounts list" else "Show the accounts list") }
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(account.kindLabel, account.code.ifBlank { null }, if (account.commissionPercent > 0) "Commission ${pct(account.commissionPercent)}" else null, "Balance ${Paise.drCr(balances[account.id] ?: 0L)}")
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ExportButtons { listOfNotNull(table) }
                }
                if (account.isParty) ChipRow {
                    Chip("Books ledger", !trade) { trade = false }
                    Chip("Bills & advices", trade) { trade = true }
                }
                PeriodBar(period)
            }
            ReportView(table, onLink, Modifier.fillMaxSize(), loading = true)
        }
    }
    LaunchedEffect(account?.isParty) { if (account?.isParty == false) trade = false }
}

private fun pct(v: Double): String = (if (v % 1.0 == 0.0) "%.0f" else "%.2f").format(v).trimEnd('0').trimEnd('.') + "%"

// ------------------------------------------------------------------------------------ reports

@Composable
private fun ReportsTab(kind: ReportKind, onKind: (ReportKind) -> Unit, onLink: (RLink) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var asOf by rememberSaveable { mutableStateOf(LocalDate.now().dmy()) }
    var cashId by rememberSaveable { mutableStateOf<String?>(null) }
    val period = remember { Period() }
    val cashAccounts by rememberLoad(emptyList<Account>()) { app.accounts.accounts().filter { it.cashBank } }
    val (from, to) = if (kind.asOf) null to parseDay(asOf) else period.bounds()
    val table by rememberLoad<ReportTable?>(null, kind, from, to, cashId) { runCatching { app.accountReports.report(kind, from, to, cashId) }.getOrNull() }
    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipRow { ReportKind.entries.forEach { k -> Chip(k.label, kind == k) { onKind(k) } } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    if (kind.asOf) DateField(asOf, { asOf = it }, "As of", Modifier.width(160.dp)) else PeriodBar(period)
                }
                if (kind == ReportKind.CASH_BOOK && cashAccounts.isNotEmpty()) {
                    val sel = cashAccounts.firstOrNull { it.id == cashId } ?: cashAccounts.first()
                    Select("Cash / bank account", cashAccounts, sel, { cashId = it.id }, Modifier.width(220.dp), text = { it.name })
                }
                ExportButtons { listOfNotNull(table) }
                OutlinedButton(onClick = {
                    val file = chooseSaveFile("Export all reports", "OTA accounts ${LocalDate.now()}.xlsx") ?: return@OutlinedButton
                    val (f, t) = period.bounds()
                    val day = parseDay(asOf) ?: LocalDate.now()
                    scope.launch {
                        attempt {
                            val tables = ReportKind.entries.map { k -> if (k.asOf) app.accountReports.report(k, null, day) else app.accountReports.report(k, f, t) }
                            AccountsExport.xlsx(file, tables)
                        }.onSuccess { toast("Saved ${it.name}"); openFile(it) }.onFailure { toast("Export failed: ${it.message}") }
                    }
                }) { IconText(Icons.Default.Share, "All reports") }
            }
            table?.let { Text("${it.title} · ${it.subtitle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ReportView(table, onLink, Modifier.fillMaxSize(), loading = true)
    }
}

// ------------------------------------------------------------------------------------ parties

@Composable
private fun PartiesTab(openLedger: (String) -> Unit) {
    val app = LocalApp.current
    var kind by rememberSaveable { mutableStateOf(PartyKind.SUPPLIER) }
    var text by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Account?>(null) }
    val rows by rememberLoad(emptyList<PartyAccount>(), kind) { app.accounts.parties(kind) }
    val tokens = SearchText.tokens(text)
    val shown = rows.filter { r -> SearchText.normalize("${r.account.name} ${r.account.code}").let { hay -> tokens.all { it in hay } } }
    val supplier = kind == PartyKind.SUPPLIER
    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipRow { PartyKind.entries.forEach { k -> Chip("${k.label}s", kind == k) { kind = k } } }
                Field(text, { text = it }, "Search ${kind.label.lowercase()}s", Modifier.weight(1f), trailing = { Icon(Icons.Default.Search, null) })
            }
            if (supplier) {
                val unset = rows.count { it.account.commissionPercent == 0.0 && it.bills > 0 }
                Banner(
                    if (unset > 0) "$unset suppliers with bills have no commission % yet. Click a supplier to set it; commission is then posted on all their bills automatically."
                    else "Commission is posted on every bill as Dr supplier · Cr Commission income, and follows bill edits and deletions. Changing a % re-posts all that supplier's bills.",
                    if (unset > 0) Brand.Amber else Brand.Blue
                )
            } else Text(
                "Every buyer and supplier in Buyers & Suppliers (and on bills) has a ledger here. Click one to set its opening balance.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val cols = buildList {
            add(Col(kind.label, 2f)); add(Col("Code", 0f, width = 80.dp))
            if (supplier) add(Col("Commission", 0f, TextAlign.End, 96.dp))
            add(Col("Bills", 0f, TextAlign.End, 56.dp)); add(Col("Billed (₹)", 0f, TextAlign.End, 130.dp)); add(Col("Books balance (₹)", 0f, TextAlign.End, 150.dp))
            add(Col("", 0f, width = 70.dp))
        }
        TableHeader(cols)
        if (shown.isEmpty()) Empty("No ${kind.label.lowercase()}s yet. They come from Buyers & Suppliers and from bills.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.account.id }) { r ->
                val a = r.account
                TableRow(false, { editing = a }) {
                    Cell2(a.name, if (a.active) "" else "Inactive", 2f, bold = true)
                    Cell(a.code, 0f, width = 80.dp)
                    if (supplier) Cell(if (a.commissionPercent > 0) pct(a.commissionPercent) else "—", 0f, TextAlign.End, width = 96.dp, color = if (a.commissionPercent > 0) Brand.SaffronDark else Brand.Muted, bold = a.commissionPercent > 0)
                    Cell(r.bills.toString(), 0f, TextAlign.End, width = 56.dp)
                    Cell(Paise.format(r.billed), 0f, TextAlign.End, width = 130.dp)
                    Cell(Paise.drCr(r.balance), 0f, TextAlign.End, width = 150.dp, bold = true)
                    TextButton(onClick = { openLedger(a.id) }, Modifier.width(70.dp)) { Text("Ledger") }
                }
            }
        }
    }
    editing?.let { AccountDialog(it, onDismiss = { editing = null }, openLedger = { id -> editing = null; openLedger(id) }) }
}

// ------------------------------------------------------------------------------------ chart of accounts

@Composable
private fun ChartTab(openLedger: (String) -> Unit) {
    val app = LocalApp.current
    var group by rememberSaveable { mutableStateOf<AccGroup?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    val data by rememberLoad(emptyList<Account>() to emptyMap<String, Long>()) { app.accounts.accounts().filterNot { it.isParty } to app.accounts.closingBalances() }
    val (accounts, balances) = data
    val shown = accounts.filter { group == null || it.group == group }
    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipRow(Modifier.weight(1f)) {
                Chip("All", group == null) { group = null }
                AccGroup.entries.forEach { g -> Chip(g.label, group == g) { group = g } }
            }
            Text("Buyers and suppliers are under Parties & commission", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { adding = true }) { IconText(Icons.Default.Add, "Add account") }
        }
        TableHeader(
            listOf(
                Col("Code", 0f, width = 70.dp), Col("Account", 2f), Col("Group", 0f, width = 110.dp), Col("Sub-group", 1.2f),
                Col("Opening (₹)", 0f, TextAlign.End, 140.dp), Col("Balance (₹)", 0f, TextAlign.End, 140.dp), Col("", 0f, width = 70.dp)
            )
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { a ->
                TableRow(false, { editing = a }) {
                    Cell(a.code, 0f, width = 70.dp)
                    Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (a.active) Color.Unspecified else Brand.Muted, modifier = Modifier.weight(1f, fill = false))
                        if (a.cashBank) Pill("Cash & bank", Brand.Green)
                        if (a.systemKey != null) Pill("Built-in", Brand.Muted)
                        if (!a.active) Pill("Inactive", Brand.Red)
                    }
                    Cell(a.group.label, 0f, width = 110.dp)
                    Cell(a.subgroup, 1.2f)
                    Cell(Paise.drCr(a.opening), 0f, TextAlign.End, width = 140.dp)
                    Cell(Paise.drCr(balances[a.id] ?: 0L), 0f, TextAlign.End, width = 140.dp, bold = true)
                    TextButton(onClick = { openLedger(a.id) }, Modifier.width(70.dp)) { Text("Ledger") }
                }
            }
        }
    }
    if (adding) AccountDialog(null, onDismiss = { adding = false }, openLedger = openLedger)
    editing?.let { AccountDialog(it, onDismiss = { editing = null }, openLedger = { id -> editing = null; openLedger(id) }) }
}

/** Adds or edits an account; party accounts only change code, commission %, opening balance and active. */
@Composable
private fun AccountDialog(account: Account?, onDismiss: () -> Unit, openLedger: (String) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val party = account?.isParty == true
    val system = account?.systemKey != null
    var code by remember { mutableStateOf(account?.code.orEmpty()) }
    var name by remember { mutableStateOf(account?.name.orEmpty()) }
    var group by remember { mutableStateOf(account?.group ?: AccGroup.EXPENSE) }
    var subgroup by remember { mutableStateOf(account?.subgroup.orEmpty()) }
    var cashBank by remember { mutableStateOf(account?.cashBank ?: false) }
    var opening by remember { mutableStateOf(Paise.input(kotlin.math.abs(account?.opening ?: 0L))) }
    var openingDr by remember { mutableStateOf((account?.opening ?: 0L).let { if (it == 0L) (account?.group ?: group).debitNature else it > 0 }) }
    var commission by remember { mutableStateOf(account?.commissionPercent?.takeIf { it > 0 }?.let { pct(it).removeSuffix("%") }.orEmpty()) }
    var active by remember { mutableStateOf(account?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val amount = Paise.parse(opening) ?: if (opening.isBlank()) 0L else { error = "Opening balance must be a number"; return }
        if (amount < 0) { error = "Enter the opening balance without a sign and pick Dr or Cr"; return }
        val pctValue = commission.trim().removeSuffix("%").ifBlank { "0" }.toDoubleOrNull() ?: run { error = "Commission must be a number, e.g. 2.5"; return }
        val next = (account ?: Account("", "", "", group)).copy(
            code = code, name = name, group = group, subgroup = subgroup, cashBank = cashBank,
            opening = if (openingDr) amount else -amount, commissionPercent = pctValue, active = active
        )
        busy = true
        scope.launch {
            attempt { app.accounts.saveAccount(next) }.onSuccess { toast("Saved ${it.name}"); onDismiss() }.onFailure { error = it.friendly() }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(if (account == null) "Add account" else account.name) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (party) Text(
                    "${account!!.party!!.label} ledger. The name follows Buyers & Suppliers.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(code, { code = it }, "Code", Modifier.width(110.dp))
                    Field(name, { name = it }, "Account name", Modifier.weight(1f), enabled = !party)
                }
                if (!party) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (account?.systemKey == "commission") LabelValue("Group", group.label, Modifier.width(170.dp))
                    else Select("Group", AccGroup.entries, group, { group = it }, Modifier.width(170.dp), text = { it.label })
                    Field(subgroup, { subgroup = it }, "Sub-group (e.g. Indirect expenses)", Modifier.weight(1f))
                }
                if (!party && group == AccGroup.ASSET) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cashBank, { cashBank = it })
                    Text("Cash or bank account (used by payments, receipts, contra and the cash book)")
                }
                if (account?.party == PartyKind.SUPPLIER) Column {
                    Field(commission, { commission = it }, "Commission % on this supplier's bills", Modifier.fillMaxWidth(), number = true, placeholder = "e.g. 2.5")
                    Text(
                        "Posted on every bill of this supplier as Dr supplier · Cr Commission income. A change re-posts all their bills.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(opening, { opening = it }, "Opening balance (₹)", Modifier.weight(1f), number = true, placeholder = "0.00")
                    Select("Side", listOf(true, false), openingDr, { openingDr = it }, Modifier.width(120.dp), text = { if (it) "Dr" else "Cr" })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it }, enabled = account?.systemKey != "commission")
                    Text("Active (inactive accounts keep their history but can't be picked on new entries)")
                }
                error?.let { Banner(it, Brand.Red) }
            }
        },
        confirmButton = { Button(onClick = ::save, enabled = !busy) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account != null && !party && !system) TextButton(onClick = { confirmDelete = true }) { IconText(Icons.Default.Delete, "Delete") }
                if (account != null) TextButton(onClick = { openLedger(account.id) }) { Text("Open ledger") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
    if (confirmDelete && account != null) ConfirmDialog(
        "Delete ${account.name}?", "Only accounts with no entries can be deleted.", "Delete", danger = true, onDismiss = { confirmDelete = false }
    ) {
        confirmDelete = false
        scope.launch { attempt { app.accounts.deleteAccount(account.id) }.onSuccess { toast("Deleted ${account.name}"); onDismiss() }.onFailure { error = it.friendly() } }
    }
}

// ------------------------------------------------------------------------------------ audit

private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")

@Composable
private fun AuditTab(onOpen: (String) -> Unit) {
    val app = LocalApp.current
    var showing by remember { mutableStateOf<AuditEntry?>(null) }
    val rows by rememberLoad(emptyList<AuditEntry>()) { app.accounts.audit() }
    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
        Text(
            "Every voucher change: who did what and when, with the voucher before and after. Automatic commission changes are listed too.",
            Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TableHeader(listOf(Col("When", 0f, width = 150.dp), Col("Action", 0f, width = 120.dp), Col("Voucher", 0f, width = 130.dp), Col("Details", 3f)))
        if (rows.isEmpty()) Empty("No changes yet")
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { e ->
                TableRow(false, { showing = e }) {
                    Cell(e.at.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(AT), 0f, width = 150.dp)
                    Box(Modifier.width(120.dp)) { Pill(e.action, auditColor(e.action)) }
                    Cell(e.voucherNo, 0f, width = 130.dp, bold = true)
                    Cell(e.summary, 3f, maxLines = 2)
                }
            }
        }
    }
    showing?.let { e ->
        val detail by rememberLoad<Pair<String?, String?>?>(null, e.id) { app.accounts.auditDetail(e.id) }
        AlertDialog(
            onDismissRequest = { showing = null },
            containerColor = Color.White,
            title = { Text("${e.action} · ${e.voucherNo}") },
            text = {
                Row(Modifier.width(860.dp).heightIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Before" to detail?.first, "After" to detail?.second).forEach { (label, json) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.Background).padding(10.dp).verticalScroll(rememberScrollState())) {
                                SelectionContainer {
                                    Text(json?.let(::prettyJson) ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (e.voucherId != null) TextButton(onClick = { showing = null; onOpen(e.voucherId) }) { Text("Open voucher") }
            },
            dismissButton = { TextButton(onClick = { showing = null }) { Text("Close") } }
        )
    }
}

private fun auditColor(action: String) = when {
    action.contains("delete", true) || action.contains("remove", true) -> Brand.Red
    action.contains("create", true) || action.contains("post", true) || action.contains("restore", true) -> Brand.Green
    else -> Brand.Blue
}

private fun prettyJson(text: String): String = runCatching { org.json.JSONObject(text).toString(2) }.getOrDefault(text)
