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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.QtyUnit
import com.example.otaapplication.Units
import com.example.otaapplication.data.PartyType
import com.ota.desktop.data.OrderFilter
import kotlinx.coroutines.delay
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
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(nav.target) {
        when (val t = nav.take(Section.BILLS)) {
            is Target.Bill -> { selected = t.id; text = ""; status = null }
            Target.NewBill -> adding = true
            else -> Unit
        }
    }
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
            Button(onClick = { adding = true }) { IconText(Icons.Default.Add, "Add bill") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.5f).fillMaxHeight(), padding = PaddingValues(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Field(text, { text = it }, "Search bill no, LR no, transport, buyer, supplier…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                    Spacer(Modifier.height(10.dp))
                    ChipRow() {
                        Chip("All", status == null) { status = null }
                        PaymentStatus.entries.forEach { s -> Chip(s.label, status == s) { status = s } }
                    }
                }
                TableHeader(listOf(Col("Bill", 0f, width = BILL_NO_W), Col("Buyer", 1f), Col("Supplier", 1f), Col("Balance", 0f, TextAlign.End, AMOUNT_W), Col("Status", 0f, TextAlign.End, STATUS_W)))
                if (rows.isEmpty()) Empty("No bills. Use Add bill when a supplier's bill arrives, or attach one to an order.")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.bill.id }) { r ->
                        val b = r.bill
                        TableRow(b.id == selected, { selected = b.id }) {
                            Cell2(b.billNo, b.billDate.dmy(), 0f, bold = true, width = BILL_NO_W)
                            Cell(SearchText.firstLine(r.buyer), 1f)
                            Cell(SearchText.firstLine(r.supplier), 1f)
                            Cell2(
                                rupees(b.balance), b.amount?.let { "of " + moneyFull(it) }.orEmpty(), 0f, TextAlign.End,
                                color = if ((b.balance ?: 0.0) > 0.005) Brand.Red else Color.Unspecified, width = AMOUNT_W
                            )
                            Box(Modifier.width(STATUS_W), contentAlignment = Alignment.CenterEnd) { StatusPill(b.status) }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val item = rows.firstOrNull { it.bill.id == selected }
                if (item == null) Panel(Modifier.fillMaxSize()) { Empty("Select a bill to see its orders and payments") }
                else BillDetail(item, onDeleted = { selected = null })
            }
        }
    }
    if (adding) BillEditorDialog(null, onDismiss = { adding = false }) { saved ->
        adding = false
        text = ""; status = null
        selected = saved.id
    }
}

@Composable
private fun BillDetail(item: BillListItem, onDeleted: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val toast = LocalToast.current
    val b = item.bill
    val orders by rememberLoad(emptyList<OrderRow>(), b.id) { app.orders.ordersForBill(b.id) }
    val payments by rememberLoad(emptyList<BillPayment>(), b.id) { app.payments.advicesForBill(b.id) }
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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
            ChipRow(spacing = 8.dp) {
                Button(onClick = { nav.go(Section.ADVICES, Target.NewAdvice(b.id)) }) { IconText(Icons.Default.Add, "New payment advice") }
                OutlinedButton(onClick = { edit = true }) { IconText(Icons.Default.Edit, "Edit bill") }
                OutlinedButton(onClick = { confirmDelete = true }) { IconText(Icons.Default.Delete, "Delete") }
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
                LabelValue("Transport", b.transport, Modifier.weight(1f))
                LabelValue("Cash discount", listOfNotNull(b.cdPercent?.let { "${PaymentMath.plain(it)}%" }, b.cdAmount?.let(::moneyFull)).joinToString(" · "), Modifier.weight(1f))
            }
            if (b.items.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Items as billed", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                TableHeader(listOf(Col("#", 0.3f), Col("Particulars", 2f), Col("Qty", 1f, TextAlign.End), Col("Rate", 0.8f, TextAlign.End), Col("Amount", 1f, TextAlign.End)))
                b.items.forEach { line ->
                    val qty = PaymentMath.parse(line.quantity)
                    val rate = PaymentMath.parse(line.rate)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Cell(line.serialNo, 0.3f, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Cell(line.particulars, 2f, maxLines = 3)
                        Cell(Units.display(line), 1f, TextAlign.End)
                        Cell(line.rate, 0.8f, TextAlign.End)
                        Cell(if (qty != null && rate != null) moneyFull(PaymentMath.round2(qty * rate)) else "", 1f, TextAlign.End)
                    }
                }
                Units.totals(b.items)?.let {
                    Text(
                        "Total: $it" + (b.linesTotal?.let { t -> " · ${moneyFull(t)}" } ?: ""),
                        Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Orders", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            orders.forEach { o ->
                TableRow(false, { nav.go(Section.ORDERS, Target.Order(o.id)) }) {
                    Cell2(o.orderNo, o.data.date, 0f, bold = true, width = ORDER_NO_W)
                    Cell(SearchText.firstLine(o.data.buyer), 1f)
                }
            }
            if (orders.isEmpty()) Text(
                if (b.buyer.isNotBlank()) "Not linked to any order: added from the supplier's bill." else "Not linked to any order on this laptop.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text("Payments", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (payments.isEmpty()) Text("No payment advice yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TableHeader(listOf(Col("Advice", 0f, width = ADVICE_NO_W), Col("Paid", 1f, TextAlign.End), Col("CD + GR + RD + claim", 1f, TextAlign.End)))
            payments.forEach { p ->
                TableRow(false, { nav.go(Section.ADVICES, Target.Advice(p.adviceId)) }) {
                    Cell2(p.adviceNo, p.payDate.dmy(), 0f, bold = true, width = ADVICE_NO_W)
                    Cell(moneyFull(p.amounts.paid), 1f, TextAlign.End)
                    Cell(moneyFull(PaymentMath.round2(p.amounts.settled - p.amounts.paid)), 1f, TextAlign.End)
                }
            }
        }
    }
    if (edit) BillEditorDialog(b, onDismiss = { edit = false }) { edit = false }
    if (confirmDelete) {
        if (b.adviceCount > 0) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false }, containerColor = Color.White,
                title = { Text("Bill ${b.billNo} is on a payment advice") },
                text = { Text("It is on ${b.adviceCount} payment advice${if (b.adviceCount == 1) "" else "s"}. Remove it from the advice first.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false }) { Text("OK") } }
            )
        } else ConfirmDialog(
            "Delete bill ${b.billNo}?",
            if (orders.isNotEmpty()) "It will be removed from its ${orders.size} order${if (orders.size == 1) "" else "s"}, which go back to Pending if they have no other bill."
            else "The bill will be removed from this laptop and, after the next sync, from the phones.",
            "Delete", danger = true, onDismiss = { confirmDelete = false }
        ) {
            confirmDelete = false
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { app.orders.deleteBill(b.id) } }
                    .onSuccess { toast("Bill ${b.billNo} deleted"); onDeleted() }
                    .onFailure { toast(it.message ?: "Could not delete the bill") }
            }
        }
    }
}

/**
 * Adds a bill from the supplier's paper bill ([bill] null) or edits one, same rules as the phone:
 * number (new bills only), buyer and supplier, the orders it covers (optional, many), the item
 * lines as billed (qty + unit + rate) and its details. The amount totals the lines until typed over.
 * Linking an order fills blank parties from it; a bill without orders needs both parties.
 */
@Composable
fun BillEditorDialog(bill: Bill?, onDismiss: () -> Unit, onSaved: (Bill) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(bill == null) }
    var number by remember { mutableStateOf(bill?.billNo.orEmpty()) }
    var buyer by remember { mutableStateOf(bill?.buyer.orEmpty()) }
    var supplier by remember { mutableStateOf(bill?.supplier.orEmpty()) }
    val linked = remember { mutableStateListOf<OrderRow>() }
    var date by remember { mutableStateOf((bill?.billDate ?: if (bill == null) LocalDate.now() else null).dmy()) }
    var lr by remember { mutableStateOf(bill?.lrNo.orEmpty()) }
    var transport by remember { mutableStateOf(bill?.transport.orEmpty()) }
    var cdPct by remember { mutableStateOf(PaymentMath.plain(bill?.cdPercent)) }
    var cdAmt by remember { mutableStateOf(PaymentMath.plain(bill?.cdAmount)) }
    var items by remember { mutableStateOf(bill?.items?.map(Units::splitLegacy).orEmpty()) }
    var amount by remember { mutableStateOf(PaymentMath.plain(bill?.amount)) }
    // The amount follows the lines' total until typed over (an old bill keeps its typed amount).
    var amountAuto by remember { mutableStateOf(bill?.amount == null || bill.amount == bill.linesTotal) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(bill?.id) {
        if (bill == null) return@LaunchedEffect
        val orders = withContext(Dispatchers.IO) { app.orders.ordersForBill(bill.id) }
        linked.addAll(orders)
        if (bill.buyer.isBlank() || bill.supplier.isBlank()) orders.firstOrNull()?.let { o ->
            if (buyer.isBlank()) buyer = SearchText.firstLine(o.data.buyer)
            if (supplier.isBlank()) supplier = SearchText.firstLine(o.data.supplier)
        }
        ready = true
    }

    fun setItems(next: List<OrderItem>) {
        items = next.mapIndexed { i, it -> it.copy(serialNo = (i + 1).toString()) }
        if (amountAuto) amount = PaymentMath.plain(PaymentMath.linesTotal(items))
    }
    fun bad(text: String) = text.isNotBlank() && (PaymentMath.parse(text) ?: -1.0) < 0
    val linesTotal = PaymentMath.linesTotal(items)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bill == null) "Add bill" else "Edit bill ${bill.billNo}") },
        containerColor = Color.White,
        text = {
            Column(Modifier.width(820.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        number, { number = it }, "Bill / invoice number", Modifier.weight(1f), enabled = bill == null,
                        error = if (bill == null && number.isBlank() && error != null) "Required" else null
                    )
                    DateField(date, { date = it }, "Bill date", Modifier.weight(0.7f), optional = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(lr, { lr = it }, "LR (bilty) number", Modifier.weight(1f))
                    SuggestField(transport, { transport = it }, "Transport (company)", Modifier.weight(1f)) { app.orders.transports(it) }
                }
                if (bill != null) Text("The number can't be changed; delete the bill and add it again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val suffix = if (linked.isEmpty()) "" else " (from orders)"
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PartyField(PartyType.BUYER, buyer, { buyer = it }, "Buyer$suffix", Modifier.weight(1f))
                    PartyField(PartyType.SUPPLIER, supplier, { supplier = it }, "Supplier$suffix", Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                LinkedOrders(linked) { order ->
                    linked += order
                    if (buyer.isBlank()) buyer = SearchText.firstLine(order.data.buyer)
                    if (supplier.isBlank()) supplier = SearchText.firstLine(order.data.supplier)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Items as billed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Units.totals(items)?.let { Pill("Total $it", Brand.SaffronDark) }
                }
                Text("What the supplier actually sent; it may differ from the orders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (items.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("#" to 0.25f, "Particulars" to 3f, "Qty" to 0.8f, "Unit" to 0.8f, "Rate" to 0.8f, "Amount" to 0.9f, "" to 0.3f).forEach { (t, w) ->
                        Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items.forEachIndexed { i, line ->
                    key(line.key) {
                        fun set(next: OrderItem) = setItems(items.toMutableList().also { it[i] = next })
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(line.serialNo, Modifier.weight(0.25f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CellField(line.particulars, { set(line.copy(particulars = it)) }, Modifier.weight(3f), "Description")
                            CellField(line.quantity, { set(line.copy(quantity = it)) }, Modifier.weight(0.8f), "0", TextAlign.End)
                            Select("Unit", listOf<QtyUnit?>(null) + QtyUnit.entries, QtyUnit.ofCode(line.unit), { set(line.copy(unit = it?.code.orEmpty())) },
                                Modifier.weight(0.8f), text = { it?.label ?: "—" }, dense = true)
                            CellField(line.rate, { set(line.copy(rate = it)) }, Modifier.weight(0.8f), "0", TextAlign.End)
                            val q = PaymentMath.parse(line.quantity)
                            val r = PaymentMath.parse(line.rate)
                            Text(if (q != null && r != null) moneyFull(PaymentMath.round2(q * r)) else "", Modifier.weight(0.9f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                            Box(Modifier.weight(0.3f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = { setItems(items.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Delete, "Remove line", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                ChipRow(spacing = 8.dp) {
                    OutlinedButton(onClick = { setItems(items + OrderItem(unit = Units.forNewRow(items))) }) { IconText(Icons.Default.Add, "Add line") }
                    if (linked.isNotEmpty()) OutlinedButton(onClick = {
                        setItems(items + linked.flatMap { o -> o.data.items.map(Units::splitLegacy) }
                            .filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() }.map { it.copy(key = OrderItem().key) })
                    }) { IconText(Icons.Default.Refresh, "Copy lines from orders") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(amount, { amount = it; amountAuto = it.isBlank() && linesTotal == null }, "Bill amount (₹)", Modifier.weight(1f), number = true,
                        error = if (bad(amount)) "Invalid" else null)
                    Field(cdPct, {
                        cdPct = it
                        PaymentMath.percentOf(PaymentMath.parse(amount), PaymentMath.parse(it))?.let { v -> cdAmt = PaymentMath.plain(v) }
                    }, "CD %", Modifier.weight(0.7f), number = true, error = if (bad(cdPct)) "Invalid" else null)
                    Field(cdAmt, { cdAmt = it }, "CD ₹", Modifier.weight(0.7f), number = true, error = if (bad(cdAmt)) "Invalid" else null)
                }
                if (linesTotal != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (amountAuto) "Amount = lines total ${moneyFull(linesTotal)}" else "Lines total ${moneyFull(linesTotal)} · amount typed over",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    if (!amountAuto) TextButton(onClick = { amountAuto = true; amount = PaymentMath.plain(linesTotal) }) { Text("Use lines total") }
                }
                error?.let { Text(it, color = Brand.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = ready && !busy, onClick = {
                val day = if (date.isBlank()) null else parseDay(date)
                error = when {
                    bill == null && number.isBlank() -> "Enter the bill number from the supplier's bill"
                    linked.isEmpty() && (buyer.isBlank() || supplier.isBlank()) -> "Enter the buyer and supplier, or link an order"
                    date.isNotBlank() && day == null -> "Bill date must be dd-mm-yyyy"
                    bad(amount) || bad(cdPct) || bad(cdAmt) || items.any { bad(it.quantity) || bad(it.rate) } -> "Amounts must be numbers"
                    else -> null
                }
                if (error != null) return@Button
                // Parties that match a linked order are left to the order, which keeps the row small.
                val fromOrders = linked.any {
                    SearchText.partyKey(it.data.buyer) == SearchText.partyKey(buyer) && SearchText.partyKey(it.data.supplier) == SearchText.partyKey(supplier)
                }
                val lines = items.filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() || it.rate.isNotBlank() }
                    .mapIndexed { i, it -> it.copy(serialNo = (i + 1).toString()) }
                val details = BillDetails(
                    day, PaymentMath.parse(amount), lr.trim(), PaymentMath.parse(cdPct), PaymentMath.parse(cdAmt), lines,
                    if (fromOrders) "" else SearchText.firstLine(buyer), if (fromOrders) "" else SearchText.firstLine(supplier),
                    transport.trim()
                )
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.orders.saveBill(bill?.id, number, details, linked.map { it.id }) } }
                        .onSuccess { toast("Bill ${it.billNo} saved"); onSaved(it) }
                        .onFailure { error = it.message; busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save bill") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Orders the bill covers: × unlinks; search by order number or party to link more. */
@Composable
private fun LinkedOrders(linked: MutableList<OrderRow>, onLink: (OrderRow) -> Unit) {
    val app = LocalApp.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<OrderRow>()) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); searchedFor = null; return@LaunchedEffect }
        delay(150)
        results = withContext(Dispatchers.IO) { app.orders.list(OrderFilter(text = query), limit = 8) }
        searchedFor = query
    }
    Text("Orders on this bill (optional)", style = MaterialTheme.typography.titleSmall)
    if (linked.isEmpty()) Text("None linked. A bill without orders keeps its own buyer and supplier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    linked.forEach { o ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(o.orderNo, Modifier.width(ORDER_NO_W), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
            Text("${o.data.date} · ${SearchText.firstLine(o.data.buyer)} → ${SearchText.firstLine(o.data.supplier)}", Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { linked.remove(o) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Close, "Unlink", Modifier.size(18.dp)) }
        }
    }
    Field(query, { query = it }, "Link an order: search number, buyer, supplier…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
    val shown = results.filter { r -> !OrderNumber.isPending(r.orderNo) && linked.none { it.id == r.id } }
    shown.forEach { o ->
        TableRow(false, { onLink(o); query = "" }) {
            Cell2(o.orderNo, o.data.date, 0f, bold = true, width = ORDER_NO_W)
            Cell("${SearchText.firstLine(o.data.buyer)} → ${SearchText.firstLine(o.data.supplier)}", 1f)
            Icon(Icons.Default.Add, "Link", tint = Brand.SaffronDark)
        }
    }
    if (searchedFor != null && searchedFor == query && shown.isEmpty()) Text("No matching order", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Quick "bill received" from an order: bill number, date, amount, LR number and cash discount. */
@Composable
fun BillDialog(title: String, bill: Bill?, onDismiss: () -> Unit, onSave: suspend (String, BillDetails) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf(bill?.billNo.orEmpty()) }
    var date by remember { mutableStateOf(bill?.billDate.dmy()) }
    var amount by remember { mutableStateOf(PaymentMath.plain(bill?.amount)) }
    var lr by remember { mutableStateOf(bill?.lrNo.orEmpty()) }
    var transport by remember { mutableStateOf(bill?.transport.orEmpty()) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(lr, { lr = it }, "LR (bilty) number", Modifier.weight(1f))
                    SuggestField(transport, { transport = it }, "Transport", Modifier.weight(1f)) { app.orders.transports(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(cdPct, {
                        cdPct = it
                        PaymentMath.percentOf(PaymentMath.parse(amount), PaymentMath.parse(it))?.let { v -> cdAmt = PaymentMath.plain(v) }
                    }, "CD %", Modifier.weight(1f), number = true, error = if (bad(cdPct)) "Invalid" else null)
                    Field(cdAmt, { cdAmt = it }, "CD ₹", Modifier.weight(1f), number = true, error = if (bad(cdAmt)) "Invalid" else null)
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
                        onSave(number.trim(), BillDetails(day, PaymentMath.parse(amount), lr.trim(), PaymentMath.parse(cdPct), PaymentMath.parse(cdAmt), transport = transport.trim()))
                    }.onFailure { error = it.message; busy = false }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
