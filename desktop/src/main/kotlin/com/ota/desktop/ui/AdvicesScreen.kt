package com.ota.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.AdviceTotals
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.PairBill
import com.example.otaapplication.data.PartyMatch
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentMode
import com.example.otaapplication.data.SearchText
import com.ota.desktop.AppPaths
import com.ota.desktop.data.AdviceFilter
import com.ota.desktop.data.BillFilter
import com.ota.desktop.export.XlsxExport
import com.ota.desktop.pdf.AdvicePdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Editing an advice: [id] null for a new one, optionally starting from [billId]. */
private data class AdviceEdit(val id: String?, val billId: String? = null)

@Composable
fun AdvicesScreen() {
    val nav = LocalNav.current
    var editing by remember { mutableStateOf<AdviceEdit?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(nav.target) {
        when (val t = nav.take(Section.ADVICES)) {
            is Target.Advice -> { selected = t.id; editing = null }
            is Target.NewAdvice -> editing = AdviceEdit(null, t.billId)
            else -> {}
        }
    }
    AnimatedContent(editing, transitionSpec = { fadeIn() togetherWith fadeOut() }) { e ->
        if (e == null) AdviceList(selected, { selected = it }, onNew = { editing = AdviceEdit(null) }, onEdit = { editing = AdviceEdit(it) })
        else AdviceEditor(e.id, e.billId) { saved -> saved?.let { selected = it }; editing = null }
    }
}

@Composable
private fun AdviceList(selected: String?, onSelect: (String?) -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    val (from, to) = range.bounds()
    val filter = AdviceFilter(text = text, from = from, to = to)
    val rows by rememberLoad(emptyList<AdviceSummary>(), filter) { app.payments.search(filter) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Payment Advices", "${rows.size} advices · ${moneyFull(PaymentMath.round2(rows.sumOf { it.paid }))} paid") {
            OutlinedButton(onClick = {
                val file = chooseSaveFile("Export advices", "OTA payment advices ${LocalDate.now()}.xlsx") ?: return@OutlinedButton
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val lines = app.analytics.paymentLines().filter { l -> rows.any { it.id == l.adviceId } }
                            XlsxExport.write(file, listOf(app.export.advices(rows, lines), app.export.lines(lines)))
                        }
                    }.onSuccess { toast("Exported ${rows.size} advices"); openFile(it) }.onFailure { toast("Export failed: ${it.message}") }
                }
            }) { IconText(Icons.Default.Share, "Export view") }
            Button(onClick = onNew) { IconText(Icons.Default.Add, "New advice") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.5f).fillMaxHeight(), padding = PaddingValues(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Field(text, { text = it }, "Search advice no, buyer, supplier, bill no, cheque / txn…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                    Spacer(Modifier.height(10.dp))
                    ChipRow() {
                        listOf(Range.ALL, Range.D30, Range.D90, Range.M12, Range.FY).forEach { r -> Chip(r.label, range == r) { range = r } }
                    }
                }
                TableHeader(listOf(Col("Advice", 0f, width = ADVICE_NO_W), Col("Paid by", 1f), Col("Paid to", 1f), Col("Paid", 0f, TextAlign.End, AMOUNT_W)))
                if (rows.isEmpty()) Empty("No payment advices. Create one from a bill or with New advice.")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { a ->
                        TableRow(a.id == selected, { onSelect(a.id) }) {
                            Cell2(AdviceNumber.display(a.adviceNo), a.payDate.dmy(), 0f, bold = true, width = ADVICE_NO_W)
                            Cell(SearchText.firstLine(a.buyer), 1f)
                            Cell(SearchText.firstLine(a.supplier), 1f)
                            Cell2(moneyFull(a.paid), a.billNos.joinToString(", "), 0f, TextAlign.End, width = AMOUNT_W)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selected == null || rows.none { it.id == selected }) Panel(Modifier.fillMaxSize()) { Empty("Select an advice to see its bills") }
                else AdviceDetail(selected, onEdit = { onEdit(selected) }, onDeleted = { onSelect(null) })
            }
        }
    }
}

private fun modeText(a: PaymentAdvice) = when {
    a.mode == PaymentMode.CASH && a.reference.isBlank() -> "Cash"
    else -> "${a.mode.label} · ${a.reference}"
}

@Composable
private fun AdviceDetail(id: String, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val advice by rememberLoad<PaymentAdvice?>(null, id) { app.payments.advice(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    val a = advice ?: return
    val t = a.totals

    Panel(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize()) {
            Text(AdviceNumber.display(a.adviceNo), style = MaterialTheme.typography.titleLarge.merge(Numeric))
            Text(a.payDate.nice(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val numberPending = AdviceNumber.isPending(a.adviceNo)
            if (numberPending) {
                Spacer(Modifier.height(10.dp))
                Banner(
                    "Saved offline: this advice gets its number on the next sync. Its PDF waits until then, so nothing is shared with a temporary number.",
                    Brand.Amber
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              ChipRow(Modifier.weight(1f), spacing = 8.dp) {
                Button(enabled = !numberPending, onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { AdvicePdf.write(a, AdvicePdf.fileFor(a, AppPaths.documents)) } }
                            .onSuccess { openFile(it) }.onFailure { toast("PDF failed: ${it.message}") }
                    }
                }) { IconText(Icons.Default.Share, "Open PDF") }
                OutlinedButton(onClick = onEdit) { IconText(Icons.Default.Edit, "Edit") }
              }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = Brand.Red) }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Paid by", a.buyer, Modifier.weight(1f))
                LabelValue("Paid to", a.supplier, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Payment", modeText(a), Modifier.weight(1f))
                LabelValue("Dr/Cr balance", a.balanceAmount?.let { "${moneyFull(it)} ${a.balanceSide?.label.orEmpty()}" }.orEmpty(), Modifier.weight(1f))
            }
            if (a.remarks.isNotBlank()) LabelValue("Remarks", a.remarks)
            Spacer(Modifier.height(12.dp))
            Text("Bills", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            TableHeader(listOf(Col("Bill", 1f), Col("Paid", 0f, TextAlign.End, AMOUNT_W)))
            a.lines.forEach { l ->
                TableRow(false, { nav.go(Section.BILLS, Target.Bill(l.bill.id)) }) {
                    val adj = listOfNotNull(
                        l.amounts.cdAmount?.takeIf { it != 0.0 }?.let { "C.D. ${moneyFull(it)}" },
                        l.amounts.goodsReturn?.takeIf { it != 0.0 }?.let { "G.R. ${moneyFull(it)}" },
                        l.amounts.rateDiff?.takeIf { it != 0.0 }?.let { "R.D. ${moneyFull(it)}" },
                        l.amounts.claim?.takeIf { it != 0.0 }?.let { "Claim ${moneyFull(it)}" + l.amounts.claimNote.trim().let { n -> if (n.isEmpty()) "" else " ($n)" } }
                    ).joinToString(" · ")
                    Cell2(l.bill.billNo, adj, 1f, bold = true)
                    Cell(moneyFull(l.amounts.paid), 0f, TextAlign.End, width = AMOUNT_W)
                }
            }
            Spacer(Modifier.height(10.dp))
            Totals(t)
        }
    }
    if (confirmDelete) ConfirmDeleteDialog(
        "advice ${AdviceNumber.display(a.adviceNo)}", "$EVERYWHERE Its payments come off the bills' balances. The number is never reused.",
        onDismiss = { confirmDelete = false }
    ) {
        confirmDelete = false
        scope.launch { withContext(Dispatchers.IO) { app.payments.delete(a.id) }; toast("Advice ${AdviceNumber.display(a.adviceNo)} deleted"); onDeleted() }
    }
}

@Composable
private fun Totals(t: AdviceTotals) {
    Column(Modifier.fillMaxWidth()) {
        listOf("Amount paid" to t.paid, "Cash discount" to t.cashDiscount, "Goods return" to t.goodsReturn, "Rate difference" to t.rateDiff, "Claim / defect" to t.claim).forEach { (label, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(moneyFull(v), style = MaterialTheme.typography.bodyMedium.merge(Numeric))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Brand.Line)
        Row(Modifier.fillMaxWidth()) {
            Text("Total adjusted", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(moneyFull(t.settled), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
        }
    }
}

// ------------------------------------------------------------------------------------ editor

private fun amountValid(text: String) = text.isBlank() || (PaymentMath.parse(text)?.let { it >= 0 } ?: false)

/** Mirrors the phone's advice editor line. */
private class LineState(
    val pair: PairBill, paid: String, cdPercent: String, cdAmount: String, goodsReturn: String, rateDiff: String,
    claim: String = "", claimNote: String = ""
) {
    var paid by mutableStateOf(paid)
    var cdPercent by mutableStateOf(cdPercent)
    var cdAmount by mutableStateOf(cdAmount)
    var goodsReturn by mutableStateOf(goodsReturn)
    var rateDiff by mutableStateOf(rateDiff)
    /** Claim / defect deduction the buyer cut, with its reason. */
    var claim by mutableStateOf(claim)
    var claimNote by mutableStateOf(claimNote)
    val billId: String get() = pair.bill.id
    val valid: Boolean get() = listOf(paid, cdPercent, cdAmount, goodsReturn, rateDiff, claim).all(::amountValid)

    fun amounts() = LineAmounts(
        PaymentMath.parse(paid) ?: 0.0, PaymentMath.parse(cdPercent), PaymentMath.parse(cdAmount), PaymentMath.parse(goodsReturn), PaymentMath.parse(rateDiff),
        PaymentMath.parse(claim), claimNote.trim()
    )

    companion object {
        /** A new line settles the bill's remaining balance, less its agreed cash discount. */
        fun fresh(pair: PairBill): LineState {
            val bill = pair.bill
            val cd = bill.cdAmount ?: PaymentMath.percentOf(bill.amount, bill.cdPercent)
            val paid = pair.balanceBefore?.let { PaymentMath.round2(it - (cd ?: 0.0)) }?.takeIf { it > 0 }
            return LineState(pair, PaymentMath.plain(paid), PaymentMath.plain(bill.cdPercent), PaymentMath.plain(cd), "", "")
        }

        fun of(pair: PairBill, a: LineAmounts) = LineState(
            pair, PaymentMath.plain(a.paid), PaymentMath.plain(a.cdPercent), PaymentMath.plain(a.cdAmount), PaymentMath.plain(a.goodsReturn), PaymentMath.plain(a.rateDiff),
            PaymentMath.plain(a.claim), a.claimNote
        )
    }
}

@Composable
private fun AdviceEditor(editingId: String?, presetBillId: String?, onDone: (String?) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(editingId == null) }
    var adviceNo by remember { mutableStateOf("") }
    var payDate by remember { mutableStateOf(LocalDate.now().dmy()) }
    var buyer by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PaymentMode.CHEQUE) }
    var reference by remember { mutableStateOf("") }
    var balanceText by remember { mutableStateOf("") }
    var balanceSide by remember { mutableStateOf(BalanceSide.DR) }
    var remarks by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<LineState>() }
    var available by remember { mutableStateOf<List<PairBill>?>(null) }
    var presetPending by remember { mutableStateOf(presetBillId) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(editingId) {
        if (editingId == null) {
            presetBillId?.let { billId ->
                withContext(Dispatchers.IO) { app.payments.bills(BillFilter()).firstOrNull { it.bill.id == billId } }?.let {
                    buyer = SearchText.firstLine(it.buyer); supplier = SearchText.firstLine(it.supplier)
                }
            }
            return@LaunchedEffect
        }
        val (a, pairs) = withContext(Dispatchers.IO) {
            val a = app.payments.advice(editingId)
            a to a?.let { app.payments.pairBills(it.buyer, it.supplier, editingId) }.orEmpty()
        }
        if (a == null) { onDone(null); return@LaunchedEffect }
        adviceNo = a.adviceNo; payDate = a.payDate.dmy(); buyer = a.buyer; supplier = a.supplier
        mode = a.mode; reference = a.reference; balanceText = PaymentMath.plain(a.balanceAmount); balanceSide = a.balanceSide ?: BalanceSide.DR
        remarks = a.remarks
        val byId = pairs.associateBy { it.bill.id }
        a.lines.forEach { line ->
            val pair = byId[line.bill.id] ?: withContext(Dispatchers.IO) { app.payments.pairBill(line.bill.id, editingId) }
                ?: PairBill(line.bill, PaymentMath.round2(line.bill.settled - line.amounts.settled), false)
            lines += LineState.of(pair, line.amounts)
        }
        ready = true
    }

    LaunchedEffect(buyer, supplier, ready) {
        if (!ready) return@LaunchedEffect
        delay(250)
        available = if (SearchText.partyKey(buyer).isEmpty() || SearchText.partyKey(supplier).isEmpty()) null
        else withContext(Dispatchers.IO) { app.payments.pairBills(buyer, supplier, editingId) }
        presetPending?.let { billId ->
            val pair = available?.firstOrNull { it.bill.id == billId } ?: withContext(Dispatchers.IO) { app.payments.pairBill(billId, editingId) }
            if (pair != null && lines.none { it.billId == billId }) lines += LineState.fresh(pair)
            presetPending = null
        }
    }

    // "Search all unpaid bills": every bill with something due, whoever it is for.
    var searchingAll by remember { mutableStateOf(false) }
    var allQuery by remember { mutableStateOf("") }
    var allUnpaid by remember { mutableStateOf<List<PairBill>?>(null) }
    LaunchedEffect(searchingAll) {
        if (searchingAll && allUnpaid == null) allUnpaid = withContext(Dispatchers.IO) { app.payments.unpaidBills(editingId) }
    }
    // A picked bill whose names don't match this advice's buyer and supplier waits here for the user's choice.
    var confirmPick by remember { mutableStateOf<PairBill?>(null) }
    fun addLine(pair: PairBill) { if (lines.none { it.billId == pair.bill.id }) lines += LineState.fresh(pair) }
    fun pick(pair: PairBill) {
        when {
            pair.buyer.isBlank() || pair.supplier.isBlank() -> addLine(pair)
            buyer.isBlank() && supplier.isBlank() -> { buyer = pair.buyer; supplier = pair.supplier; addLine(pair) }
            PartyMatch.pairLevel(buyer, supplier, pair.buyer, pair.supplier) != PartyMatch.Level.NONE -> addLine(pair)
            else -> confirmPick = pair
        }
    }

    val totals = AdviceTotals.of(lines.map { it.amounts() })
    val outstanding = available?.let { pairs ->
        val ids = pairs.map { it.bill.id }.toSet()
        PaymentMath.round2(pairs.sumOf { it.balanceBefore ?: 0.0 } - lines.filter { it.billId in ids && it.pair.bill.amount != null }.sumOf { it.amounts().settled })
    }
    val suggestion = outstanding?.let { PaymentMath.suggestedBalance(it) }
    val day = parseDay(payDate)
    val problems = buildList {
        if (day == null) add("Enter the payment date as dd-mm-yyyy.")
        if (buyer.isBlank() || supplier.isBlank()) add("Pick who paid (buyer) and who was paid (supplier).")
        if (lines.isEmpty()) add("Add at least one bill.")
        if (lines.any { !it.valid } || !amountValid(balanceText)) add("Fix the amounts marked in red.")
        if (lines.any { it.valid && it.amounts().settled <= 0.0 }) add("Each bill needs a paid amount or an adjustment.")
        if (mode != PaymentMode.CASH && reference.isBlank()) add("Enter the ${mode.referenceLabel.lowercase()}.")
    }
    val canSave = ready && problems.isEmpty() && !busy

    fun save() {
        val draft = AdviceDraft(
            id = editingId, payDate = day!!, buyer = buyer.trim(), supplier = supplier.trim(), mode = mode, reference = reference.trim(),
            balanceAmount = PaymentMath.parse(balanceText), balanceSide = balanceSide, remarks = remarks.trim(),
            lines = lines.map { it.billId to it.amounts() }
        )
        busy = true
        scope.launch {
            runCatching {
                // New advices take the next FY number from the cloud's advice counter; offline they wait for it.
                val number = if (editingId == null) app.engine.allocateAdviceNo(draft.payDate) else null
                withContext(Dispatchers.IO) {
                    app.payments.save(draft, number.orEmpty()).also {
                        if (!AdviceNumber.isPending(it.adviceNo)) runCatching { AdvicePdf.write(it, AdvicePdf.fileFor(it, AppPaths.documents)) }
                    }
                }
            }.onSuccess {
                toast(
                    if (AdviceNumber.isPending(it.adviceNo)) "Advice saved offline · it gets its number and PDF on the next sync"
                    else "Advice ${AdviceNumber.display(it.adviceNo)} saved · PDF in Documents/OTA Orders"
                )
                onDone(it.id)
            }
                .onFailure { toast(it.message ?: "Could not save the advice"); busy = false }
        }
    }

    confirmPick?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmPick = null },
            title = { Text("Bill ${pair.bill.billNo} is for other parties") },
            text = {
                Text(
                    "This bill is from ${pair.buyer} to ${pair.supplier}, but this advice is from ${SearchText.firstLine(buyer)} " +
                        "to ${SearchText.firstLine(supplier)}. One advice pays one buyer to one supplier." +
                        if (lines.isEmpty()) " Switch the advice to the bill's parties, or add it anyway if it is the same party spelled differently."
                        else " Add it anyway only if it is the same party spelled differently."
                )
            },
            confirmButton = {
                Row {
                    if (lines.isEmpty()) TextButton(onClick = { buyer = pair.buyer; supplier = pair.supplier; addLine(pair); confirmPick = null }) {
                        Text("Use bill's parties")
                    }
                    TextButton(onClick = { addLine(pair); confirmPick = null }) { Text("Add anyway") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmPick = null }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            if (editingId == null) "New payment advice" else "Edit ${AdviceNumber.display(adviceNo)}",
            if (editingId == null) "Numbered by the cloud when saved (PA-${OrderNumber.fyPrefix(parseDay(payDate) ?: LocalDate.now())}…)" else null
        ) {
            TextButton(onClick = { onDone(null) }) { IconText(Icons.Default.ArrowBack, "Cancel") }
            Button(onClick = ::save, enabled = canSave) { IconText(Icons.Default.Check, if (busy) "Saving…" else "Save & make PDF") }
        }
        if (!ready) { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Panel(Modifier.fillMaxWidth(), title = "Payment") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DateField(payDate, { payDate = it }, "Payment date", Modifier.width(160.dp))
                        PartyField(PartyType.BUYER, buyer, { buyer = it }, "Paid by (buyer)", Modifier.weight(1f))
                        PartyField(PartyType.SUPPLIER, supplier, { supplier = it }, "Paid to (supplier)", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow(Modifier.width(300.dp)) {
                            PaymentMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(mode == m, { mode = m }, SegmentedButtonDefaults.itemShape(i, PaymentMode.entries.size)) { Text(m.label) }
                            }
                        }
                        Field(reference, { reference = it }, mode.referenceLabel, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Panel(Modifier.fillMaxWidth(), title = "Bills", actions = { Pill("${lines.size}", Brand.Muted) }) {
                    lines.forEach { line ->
                        key(line.billId) {
                            LineEditor(line, partyNote(line.pair, buyer, supplier)) { lines.remove(line) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    val addable = available?.filter { p -> lines.none { it.billId == p.bill.id } }
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        available == null -> Text("Pick the buyer and supplier to see their bills, or search all unpaid bills below.", color = muted)
                        available!!.isEmpty() -> Text(
                            "No bills found from ${SearchText.firstLine(buyer)} to ${SearchText.firstLine(supplier)}. Names are matched ignoring case, " +
                                "spacing, M/s, Ltd and a city typed after the name. A bill must be entered first: Bills → Add bill, or attach it to its order.",
                            color = muted
                        )
                        addable!!.isEmpty() -> Text("All bills for this buyer and supplier are on this advice.", color = muted)
                        else -> {
                            Text("Add a bill", style = MaterialTheme.typography.labelLarge, color = muted)
                            Spacer(Modifier.height(4.dp))
                            addable.forEach { pair -> key(pair.bill.id) { AddBillRow(pair, partyNote(pair, buyer, supplier)) { pick(pair) } } }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!searchingAll) {
                        TextButton(onClick = { searchingAll = true }) {
                            IconText(Icons.Default.Search, if (available.isNullOrEmpty()) "Search all unpaid bills" else "Bill not listed? Search all unpaid bills")
                        }
                    }
                    AnimatedVisibility(searchingAll) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field(allQuery, { allQuery = it }, "Search all unpaid bills: bill no., buyer, supplier", Modifier.weight(1f))
                                IconButton(onClick = { searchingAll = false; allQuery = "" }) { Icon(Icons.Default.Close, "Close bill search") }
                            }
                            Spacer(Modifier.height(6.dp))
                            val tokens = SearchText.tokens(allQuery)
                            val shown = allUnpaid?.filter { p ->
                                lines.none { it.billId == p.bill.id } &&
                                    SearchText.normalize("${p.bill.billNo} ${p.bill.lrNo} ${p.bill.transport} ${p.buyer} ${p.supplier}").let { text -> tokens.all { it in text } }
                            }
                            when {
                                shown == null -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                                shown.isEmpty() -> Text(if (allQuery.isBlank()) "No unpaid bills on this laptop." else "No unpaid bill matches “$allQuery”.", color = muted)
                                else -> shown.take(80).forEach { pair -> key(pair.bill.id) { AddBillRow(pair, partyNote(pair, buyer, supplier)) { pick(pair) } } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Panel(Modifier.fillMaxWidth(), title = "Totals") { Totals(totals) }
                Spacer(Modifier.height(14.dp))
                Panel(Modifier.fillMaxWidth(), title = "Balance after payment") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(balanceText, { balanceText = it }, "Dr/Cr balance (optional)", Modifier.weight(1f), number = true, error = if (amountValid(balanceText)) null else "Invalid")
                        SingleChoiceSegmentedButtonRow(Modifier.width(120.dp)) {
                            BalanceSide.entries.forEachIndexed { i, s ->
                                SegmentedButton(balanceSide == s, { balanceSide = s }, SegmentedButtonDefaults.itemShape(i, BalanceSide.entries.size), icon = {}) { Text(s.label) }
                            }
                        }
                    }
                    AnimatedVisibility(available != null) {
                        Column {
                            Text(
                                "Suggested from this laptop's bills and payments for this buyer and supplier. Bills without an amount aren't counted.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val hint = suggestion?.let { (amt, side) -> "${moneyFull(amt)} ${side.label}" } ?: "Nil (all settled)"
                            AssistChip(onClick = {
                                balanceText = PaymentMath.plain(suggestion?.first ?: 0.0); balanceSide = suggestion?.second ?: BalanceSide.DR
                            }, label = { Text("Use $hint") })
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Panel(Modifier.fillMaxWidth(), title = "Remarks") {
                    Field(remarks, { remarks = it }, "Remarks (optional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                }
                AnimatedVisibility(problems.isNotEmpty()) {
                    Surface(Modifier.fillMaxWidth().padding(top = 14.dp), RoundedCornerShape(10.dp), color = Brand.Amber.copy(alpha = 0.08f), border = BorderStroke(1.dp, Brand.Amber.copy(alpha = 0.35f))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("To save this advice", style = MaterialTheme.typography.labelLarge, color = Brand.SaffronDark)
                            problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

/** How a bill's own names compare with the advice's buyer and supplier; null when they match exactly. */
private class PartyNote(val text: String, val differs: Boolean)

private fun partyNote(pair: PairBill, buyer: String, supplier: String): PartyNote? {
    if (pair.buyer.isBlank() || pair.supplier.isBlank()) return null
    val names = "${pair.buyer} → ${pair.supplier}"
    if (SearchText.partyKey(buyer).isEmpty() || SearchText.partyKey(supplier).isEmpty()) return PartyNote(names, differs = false)
    return when (PartyMatch.pairLevel(buyer, supplier, pair.buyer, pair.supplier)) {
        PartyMatch.Level.EXACT -> null
        PartyMatch.Level.CLOSE -> PartyNote("On bill: $names", differs = false)
        PartyMatch.Level.NONE -> PartyNote("Different parties on bill: $names", differs = true)
    }
}

@Composable
private fun AddBillRow(pair: PairBill, note: PartyNote?, onAdd: () -> Unit) {
    val b = pair.bill
    TableRow(false, onAdd) {
        Icon(Icons.Default.Add, null, tint = Brand.Saffron, modifier = Modifier.size(18.dp))
        Cell2(b.billNo, b.billDate.dmy(), 0f, bold = true, width = BILL_NO_W)
        Cell(
            listOfNotNull(note?.text, if (pair.mixedParties) "Orders for other parties too" else null).joinToString(" · "),
            1f, color = if (note?.differs == true) Brand.Red else if (note != null) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Amber, maxLines = 2
        )
        Cell2(
            when {
                pair.balanceBefore == null -> "amount not entered"
                !pair.due -> "fully paid"
                else -> "due ${moneyFull(pair.balanceBefore!!)}"
            },
            listOfNotNull(b.amount?.let { "bill ${moneyFull(it)}" }, if (pair.settledElsewhere > 0 && pair.due) "part paid" else null).joinToString(" · "),
            0f, TextAlign.End, color = Brand.Red, width = 150.dp
        )
    }
}

@Composable
private fun LineEditor(line: LineState, note: PartyNote?, onRemove: () -> Unit) {
    val bill = line.pair.bill
    val settled = line.amounts().settled
    Surface(
        Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), color = Brand.SaffronTint,
        border = BorderStroke(1.dp, if (note?.differs == true) Brand.Red else Brand.Line)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bill ${bill.billNo}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(bill.billDate.dmy().ifEmpty { null }, bill.amount?.let { "Bill ${moneyFull(it)}" },
                            line.pair.balanceBefore?.let { if (line.pair.settledElsewhere > 0) "due ${moneyFull(it)} after earlier advices" else "due ${moneyFull(it)}" },
                            if (line.pair.mixedParties) "mixed parties" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    note?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.differs) Brand.Red else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val after = line.pair.balanceBefore?.let { PaymentMath.round2(it - settled) }
                Text(
                    "Settles ${moneyFull(settled)}" + (after?.let { " · after ${moneyFull(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = if (after != null && after < -0.005) Brand.Red else Brand.SaffronDark
                )
                var confirmRemove by remember { mutableStateOf(false) }
                IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.Close, "Remove bill ${bill.billNo}") }
                if (confirmRemove) ConfirmDeleteDialog(
                    "bill ${bill.billNo} from this advice", "The bill stays; its payment line on this advice is deleted when you save.",
                    onDismiss = { confirmRemove = false }, confirm = "Remove"
                ) { confirmRemove = false; onRemove() }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun amount(label: String, value: String, weight: Float, onChange: (String) -> Unit) = Column(Modifier.weight(weight)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CellField(value, onChange, Modifier.fillMaxWidth(), "0", TextAlign.End, error = !amountValid(value))
                }
                amount("Paid amount", line.paid, 1.4f) { line.paid = it }
                amount("C.D. %", line.cdPercent, 0.7f) {
                    line.cdPercent = it
                    PaymentMath.percentOf(bill.amount, PaymentMath.parse(it))?.let { v -> line.cdAmount = PaymentMath.plain(v) }
                }
                amount("C.D. ₹", line.cdAmount, 1f) { line.cdAmount = it }
                amount("Goods return", line.goodsReturn, 1f) { line.goodsReturn = it }
                amount("Rate diff.", line.rateDiff, 1f) { line.rateDiff = it }
                amount("Claim / defect", line.claim, 1f) { line.claim = it }
            }
            if (line.claim.isNotBlank() || line.claimNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Field(line.claimNote, { line.claimNote = it }, "Claim reason (e.g. 2 than defective, shade variation)", Modifier.fillMaxWidth())
            }
        }
    }
}
