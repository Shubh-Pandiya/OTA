package com.ota.desktop.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.BillPayment
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.data.BillFilter
import com.ota.desktop.data.BillListItem
import com.ota.desktop.data.OrderRow
import com.ota.desktop.export.XlsxExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BillsScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf<PaymentStatus?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(nav.target) { (nav.take(Section.BILLS) as? Target.Bill)?.let { selected = it.id; text = ""; status = null } }
    val filter = BillFilter(text = text, status = status)
    val rows by rememberLoad(emptyList<BillListItem>(), filter) { app.payments.bills(filter) }

    Column(Modifier.fillMaxSize()) {
        val due = rows.sumOf { (it.bill.balance ?: 0.0).coerceAtLeast(0.0) }
        PageHeader("Bills", "${rows.size} bills · ${moneyFull(PaymentMath.round2(due))} outstanding") {
            OutlinedButton(onClick = {
                val file = chooseSaveFile("Export bills", "OTA bills ${LocalDate.now()}.xlsx") ?: return@OutlinedButton
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { XlsxExport.write(file, listOf(app.export.bills(rows))) } }
                        .onSuccess { toast("Exported ${rows.size} bills"); openFile(it) }.onFailure { toast("Export failed: ${it.message}") }
                }
            }) { IconText(Icons.Default.Share, "Export view") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.35f).fillMaxHeight(), padding = PaddingValues(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Field(text, { text = it }, "Search bill no, LR no, buyer, supplier…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("All", status == null) { status = null }
                        PaymentStatus.entries.forEach { s -> Chip(s.label, status == s) { status = s } }
                    }
                }
                TableHeader(listOf(Col("Bill no", 1f), Col("Date", 0.9f), Col("Buyer", 1.5f), Col("Supplier", 1.5f), Col("Amount", 1f, TextAlign.End), Col("Balance", 1f, TextAlign.End), Col("Status", 0.9f, TextAlign.End)))
                if (rows.isEmpty()) Empty("No bills. Attach a bill to an order to start.")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.bill.id }) { r ->
                        val b = r.bill
                        TableRow(b.id == selected, { selected = b.id }) {
                            Cell(b.billNo, 1f, bold = true)
                            Cell(b.billDate.dmy(), 0.9f)
                            Cell(SearchText.firstLine(r.buyer), 1.5f)
                            Cell(SearchText.firstLine(r.supplier), 1.5f)
                            Cell(rupees(b.amount), 1f, TextAlign.End)
                            Cell(rupees(b.balance), 1f, TextAlign.End, color = if ((b.balance ?: 0.0) > 0.005) Brand.Red else Color.Unspecified)
                            Box(Modifier.weight(0.9f), contentAlignment = Alignment.CenterEnd) { StatusPill(b.status) }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val item = rows.firstOrNull { it.bill.id == selected }
                if (item == null) Panel(Modifier.fillMaxSize()) { Empty("Select a bill to see its orders and payments") }
                else BillDetail(item)
            }
        }
    }
}

@Composable
private fun BillDetail(item: BillListItem) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val toast = LocalToast.current
    val b = item.bill
    val orders by rememberLoad(emptyList<OrderRow>(), b.id) { app.orders.ordersForBill(b.id) }
    val payments by rememberLoad(emptyList<BillPayment>(), b.id) { app.payments.advicesForBill(b.id) }
    var edit by remember { mutableStateOf(false) }

    Panel(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bill ${b.billNo}", style = MaterialTheme.typography.titleLarge)
                    Text("${SearchText.firstLine(item.buyer)} → ${SearchText.firstLine(item.supplier)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(b.status)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.go(Section.ADVICES, Target.NewAdvice(b.id)) }) { IconText(Icons.Default.Add, "New payment advice") }
                OutlinedButton(onClick = { edit = true }) { IconText(Icons.Default.Edit, "Edit details") }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi("Amount", rupees(b.amount), b.billDate.nice().ifEmpty { "No bill date" }, Brand.Blue, Modifier.weight(1f))
                Kpi("Settled", moneyFull(b.settled), "${b.adviceCount} advice${if (b.adviceCount == 1) "" else "s"}", Brand.Green, Modifier.weight(1f))
                Kpi("Balance", rupees(b.balance), if (b.amount == null) "Enter the amount" else b.status.label, Brand.Red, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("LR (bilty) no", b.lrNo, Modifier.weight(1f))
                LabelValue("Cash discount", listOfNotNull(b.cdPercent?.let { "${PaymentMath.plain(it)}%" }, b.cdAmount?.let(::moneyFull)).joinToString(" · "), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("Orders", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            orders.forEach { o ->
                TableRow(false, { nav.go(Section.ORDERS, Target.Order(o.id)) }) {
                    Cell(o.orderNo, 1f, bold = true)
                    Cell(o.data.date, 0.8f)
                    Cell(SearchText.firstLine(o.data.buyer), 1.6f)
                }
            }
            if (orders.isEmpty()) Text("Not linked to any order on this laptop.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Payments", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (payments.isEmpty()) Text("No payment advice yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TableHeader(listOf(Col("Advice", 1.2f), Col("Date", 0.9f), Col("Paid", 1f, TextAlign.End), Col("CD + GR + RD", 1f, TextAlign.End)))
            payments.forEach { p ->
                TableRow(false, { nav.go(Section.ADVICES, Target.Advice(p.adviceId)) }) {
                    Cell(p.adviceNo, 1.2f, bold = true)
                    Cell(p.payDate.dmy(), 0.9f)
                    Cell(moneyFull(p.amounts.paid), 1f, TextAlign.End)
                    Cell(moneyFull(PaymentMath.round2(p.amounts.settled - p.amounts.paid)), 1f, TextAlign.End)
                }
            }
        }
    }
    if (edit) BillDialog("Bill ${b.billNo}", b, onDismiss = { edit = false }) { _, details ->
        withContext(Dispatchers.IO) { app.orders.updateBillDetails(b.id, details) }
        edit = false
        toast("Bill ${b.billNo} updated")
    }
}

/** Bill number (new bills only), date, amount, LR number and cash discount. */
@Composable
fun BillDialog(title: String, bill: Bill?, onDismiss: () -> Unit, onSave: suspend (String, BillDetails) -> Unit) {
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf(bill?.billNo.orEmpty()) }
    var date by remember { mutableStateOf(bill?.billDate.dmy()) }
    var amount by remember { mutableStateOf(PaymentMath.plain(bill?.amount)) }
    var lr by remember { mutableStateOf(bill?.lrNo.orEmpty()) }
    var cdPct by remember { mutableStateOf(PaymentMath.plain(bill?.cdPercent)) }
    var cdAmt by remember { mutableStateOf(PaymentMath.plain(bill?.cdAmount)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun bad(text: String) = text.isNotBlank() && (PaymentMath.parse(text) ?: -1.0) < 0
    val numberError = if (bill == null && number.isBlank() && error != null) "Required" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        containerColor = Color.White,
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (bill == null) Field(number, { number = it }, "Bill / invoice number", Modifier.fillMaxWidth(), error = numberError)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField(date, { date = it }, "Bill date", Modifier.weight(1f), optional = true)
                    Field(amount, { amount = it }, "Bill amount (₹)", Modifier.weight(1f), number = true, error = if (bad(amount)) "Invalid" else null)
                }
                Field(lr, { lr = it }, "LR (bilty) number", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(cdPct, {
                        cdPct = it
                        PaymentMath.percentOf(PaymentMath.parse(amount), PaymentMath.parse(it))?.let { v -> cdAmt = PaymentMath.plain(v) }
                    }, "Cash discount %", Modifier.weight(1f), number = true, error = if (bad(cdPct)) "Invalid" else null)
                    Field(cdAmt, { cdAmt = it }, "Cash discount ₹", Modifier.weight(1f), number = true, error = if (bad(cdAmt)) "Invalid" else null)
                }
                error?.let { Text(it, color = Brand.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                val day = if (date.isBlank()) null else parseDay(date)
                error = when {
                    bill == null && number.isBlank() -> "Enter the bill number"
                    date.isNotBlank() && day == null -> "Bill date must be dd-mm-yyyy"
                    bad(amount) || bad(cdPct) || bad(cdAmt) -> "Amounts must be numbers"
                    else -> null
                }
                if (error != null) return@Button
                busy = true
                scope.launch {
                    runCatching {
                        onSave(number.trim(), BillDetails(day, PaymentMath.parse(amount), lr.trim(), PaymentMath.parse(cdPct), PaymentMath.parse(cdAmt)))
                    }.onFailure { error = it.message; busy = false }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
