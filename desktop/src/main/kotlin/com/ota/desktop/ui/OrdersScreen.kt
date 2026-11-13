package com.ota.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.QtyUnit
import com.example.otaapplication.Units
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillStatusFilter
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.SearchText
import com.ota.desktop.AppPaths
import com.ota.desktop.data.OrderFilter
import com.ota.desktop.data.OrderRow
import com.ota.desktop.export.XlsxExport
import com.ota.desktop.pdf.OrderPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** null = list; Editing(null) = a new order. */
private data class Editing(val row: OrderRow?)

@Composable
fun OrdersScreen() {
    val nav = LocalNav.current
    var editing by remember { mutableStateOf<Editing?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(nav.target) {
        when (val t = nav.take(Section.ORDERS)) {
            is Target.Order -> { selected = t.id; editing = null }
            Target.NewOrder -> editing = Editing(null)
            else -> {}
        }
    }
    AnimatedContent(editing, transitionSpec = { fadeIn() togetherWith fadeOut() }) { e ->
        if (e == null) OrderList(selected, { selected = it }, onNew = { editing = Editing(null) }, onEdit = { editing = Editing(it) })
        else OrderEditor(e.row, onDone = { saved -> saved?.let { selected = it }; editing = null })
    }
}

@Composable
private fun OrderList(selected: String?, onSelect: (String?) -> Unit, onNew: () -> Unit, onEdit: (OrderRow) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var bills by rememberSaveable { mutableStateOf(BillStatusFilter.ALL) }
    val (from, to) = range.bounds()
    val filter = OrderFilter(text = text, from = from, to = to, bills = bills)
    val rows by rememberLoad(emptyList<OrderRow>(), filter) { app.orders.list(filter) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Orders", "${rows.size} order${if (rows.size == 1) "" else "s"}") {
            OutlinedButton(onClick = {
                val file = chooseSaveFile("Export orders", "OTA orders ${LocalDate.now()}.xlsx") ?: return@OutlinedButton
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { XlsxExport.write(file, listOf(app.export.orders(rows), app.export.items(rows))) } }
                        .onSuccess { toast("Exported ${rows.size} orders"); openFile(it) }
                        .onFailure { toast("Export failed: ${it.message}") }
                }
            }) { IconText(Icons.Default.Share, "Export view") }
            Button(onClick = onNew) { IconText(Icons.Default.Add, "New order") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.5f).fillMaxHeight(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Field(text, { text = it }, "Search number, buyer, supplier, item, bill…", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                    Spacer(Modifier.height(10.dp))
                    ChipRow {
                        listOf(Range.ALL, Range.D30, Range.D90, Range.M12).forEach { r -> Chip(r.label, range == r) { range = r } }
                    }
                    ChipRow {
                        listOf(BillStatusFilter.ALL to "Any bill", BillStatusFilter.PENDING to "Bill pending", BillStatusFilter.RECEIVED to "Bill received")
                            .forEach { (f, label) -> Chip(label, bills == f) { bills = f } }
                    }
                }
                val cols = listOf(Col("Order no", 0f, width = ORDER_NO_W), Col("Date", 0f, width = DATE_W), Col("Buyer", 1.2f), Col("Supplier", 1.1f), Col("Bills", 0.8f))
                TableHeader(cols)
                if (rows.isEmpty()) Empty(if (text.isBlank()) "No orders yet. Create one, or sign in and sync to fetch the phones' orders." else "No orders match \"$text\"")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        TableRow(r.id == selected, { onSelect(r.id) }) {
                            Row(Modifier.width(ORDER_NO_W), verticalAlignment = Alignment.CenterVertically) {
                                Text(OrderNumber.display(r.orderNo), style = MaterialTheme.typography.bodyMedium.merge(Numeric), fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    color = if (OrderNumber.isPending(r.orderNo)) Brand.Amber else Color.Unspecified)
                                if (r.pending) { Spacer(Modifier.width(6.dp)); Dot(Brand.Amber, 6.dp) }
                            }
                            Cell(r.data.date, 0f, width = DATE_W)
                            Cell(SearchText.firstLine(r.data.buyer), 1.2f)
                            Cell(SearchText.firstLine(r.data.supplier), 1.1f)
                            Cell(r.billNos.joinToString(", ").ifEmpty { "Pending" }, 0.8f, color = if (r.billNos.isEmpty()) Brand.Amber else Color.Unspecified)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val row = rows.firstOrNull { it.id == selected }
                if (row == null) Panel(Modifier.fillMaxSize()) { Empty("Select an order to see its details") }
                else OrderDetail(row, onEdit = { onEdit(row) }, onDeleted = { onSelect(null) })
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected, onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.SaffronSoft, selectedLabelColor = Brand.SaffronDark)
    )
}

@Composable
private fun OrderDetail(row: OrderRow, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val bills by rememberLoad(emptyList<Bill>(), row.id) { app.orders.billsForOrder(row.id) }
    var confirmDelete by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    val d = row.data

    Panel(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(OrderNumber.display(row.orderNo), style = MaterialTheme.typography.titleLarge.merge(Numeric))
                    Text(d.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.pending) Pill("Not synced yet", Brand.Amber)
            }
            val numberPending = OrderNumber.isPending(row.orderNo)
            if (numberPending) {
                Spacer(Modifier.height(10.dp))
                Banner(
                    "Saved offline: this order gets its number on the next sync. Its PDF and bills wait until then, so nothing is shared with a temporary number.",
                    Brand.Amber
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              ChipRow(Modifier.weight(1f), spacing = 8.dp) {
                Button(enabled = !numberPending, onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { OrderPdf.write(d, OrderPdf.fileFor(d, AppPaths.documents)) } }
                            .onSuccess { openFile(it) }.onFailure { toast("PDF failed: ${it.message}") }
                    }
                }) { IconText(Icons.Default.Share, "Open PDF") }
                OutlinedButton(onClick = onEdit) { IconText(Icons.Default.Edit, "Edit") }
                OutlinedButton(enabled = !numberPending, onClick = { attach = true }) { IconText(Icons.Default.Add, "Attach bill") }
              }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = Brand.Red) }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Buyer", d.buyer, Modifier.weight(1f))
                LabelValue("Supplier", d.supplier, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("Items", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            TableHeader(listOf(Col("#", 0.3f), Col("Particulars", 2f), Col("Qty", 1f, TextAlign.End), Col("Rate", 0.8f, TextAlign.End)))
            d.items.filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() || it.rate.isNotBlank() }.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cell(item.serialNo, 0.3f, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Cell(item.particulars, 2f, maxLines = 3)
                    Cell(Units.display(item), 1f, TextAlign.End)
                    Cell(item.rate, 0.8f, TextAlign.End)
                }
                HorizontalDivider(color = Brand.Line)
            }
            Units.totals(d.items)?.let {
                Text("Total: $it", Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Delivery ready / challan by", d.deliveryReady, Modifier.weight(1f))
                LabelValue("Transport to", d.transportTo, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Cash payment terms", d.cashPayment, Modifier.weight(1f))
                LabelValue("Less / discount", d.lessDisc, Modifier.weight(1f))
                LabelValue("Side", d.side, Modifier.weight(1f))
            }
            LabelValue("Remarks / terms", d.remarks)
            Spacer(Modifier.height(12.dp))
            Text("Bills", style = MaterialTheme.typography.titleSmall)
            if (bills.isEmpty()) Text("No bill received yet.", color = Brand.Amber, modifier = Modifier.padding(vertical = 6.dp))
            bills.forEach { b ->
                TableRow(false, { nav.go(Section.BILLS, Target.Bill(b.id)) }) {
                    Cell2(b.billNo, b.billDate.dmy(), 1f, bold = true)
                    Cell(rupees(b.amount), 0f, TextAlign.End, width = AMOUNT_W)
                    Box(Modifier.width(STATUS_W), contentAlignment = Alignment.CenterEnd) { StatusPill(b.status) }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDeleteDialog(
        "order ${OrderNumber.display(row.orderNo)}",
        "$EVERYWHERE Its bill links go with it; a bill that belongs only to this order and has no parties of its own is deleted too. Its number is never reused.",
        onDismiss = { confirmDelete = false }
    ) {
        confirmDelete = false
        scope.launch { withContext(Dispatchers.IO) { app.orders.delete(row.id) }; toast("Order ${OrderNumber.display(row.orderNo)} deleted"); onDeleted() }
    }
    if (attach) BillDialog(
        title = "Attach bill to ${row.orderNo}", bill = null,
        onDismiss = { attach = false }
    ) { billNo, details ->
        withContext(Dispatchers.IO) { app.orders.attachBill(listOf(row.id), billNo, details) }
        attach = false
        toast("Bill $billNo attached")
    }
}

// ------------------------------------------------------------------------------------ editor

@Composable
private fun OrderEditor(row: OrderRow?, onDone: (String?) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(row?.data?.let(Units::splitLegacy) ?: OrderFormData()) }
    var saving by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val dateBad = parseDay(data.date) == null

    fun setItem(i: Int, item: OrderItem) { data = data.copy(items = data.items.toMutableList().also { it[i] = item }) }

    fun save() {
        tried = true
        if (dateBad) { toast("Enter the order date as dd-mm-yyyy"); return }
        saving = true
        scope.launch {
            runCatching {
                // New orders take the next agency-wide number from the cloud; offline they wait for it.
                val number = if (row == null) app.engine.allocateOrderNo(parseDay(data.date)!!) else null
                withContext(Dispatchers.IO) {
                    val saved = if (row == null) app.orders.create(data, number.orEmpty()) else app.orders.update(row.id, data)
                    if (!OrderNumber.isPending(saved.orderNo)) OrderPdf.write(saved.data, OrderPdf.fileFor(saved.data, AppPaths.documents))
                    saved
                }
            }.onSuccess { saved ->
                toast(
                    if (OrderNumber.isPending(saved.orderNo)) "Order saved offline · it gets its number and PDF on the next sync"
                    else "Order ${saved.orderNo} saved · PDF in Documents/OTA Orders"
                )
                onDone(saved.id)
            }.onFailure { toast("Couldn't save: ${it.message}"); saving = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(if (row == null) "New order" else "Edit order ${OrderNumber.display(row.orderNo)}", if (row == null) "The order number is assigned by the cloud when you save" else null) {
            TextButton(onClick = { onDone(null) }) { IconText(Icons.Default.ArrowBack, "Cancel") }
            Button(onClick = ::save, enabled = !saving) { IconText(Icons.Default.Check, if (saving) "Saving…" else "Save & make PDF") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Panel(Modifier.fillMaxWidth(), title = "Order") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField(data.date, { data = data.copy(date = it) }, "Date", Modifier.width(180.dp))
                    Field(row?.orderNo?.let(OrderNumber::display) ?: "Assigned on save", {}, "Offer No", Modifier.width(220.dp), enabled = false)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PartyField(PartyType.BUYER, data.buyer, { data = data.copy(buyer = it) }, "Buyer Name & Address", Modifier.weight(1f), fullText = true, minLines = 3)
                    PartyField(PartyType.SUPPLIER, data.supplier, { data = data.copy(supplier = it) }, "Supplier Name & Address", Modifier.weight(1f), fullText = true, minLines = 3)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth(), title = "Items", actions = { Units.totals(data.items)?.let { Pill("Total $it", Brand.SaffronDark) } }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("#" to 0.25f, "Particulars" to 3f, "Qty" to 0.8f, "Unit" to 0.7f, "Rate" to 0.8f, "" to 0.25f).forEach { (t, w) ->
                        Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                data.items.forEachIndexed { i, item ->
                    androidx.compose.runtime.key(item.key) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).animateContentSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.serialNo, Modifier.weight(0.25f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CellField(item.particulars, { setItem(i, item.copy(particulars = it)) }, Modifier.weight(3f), "Description")
                            CellField(item.quantity, { setItem(i, item.copy(quantity = it)) }, Modifier.weight(0.8f), "0", TextAlign.End)
                            Select("Unit", listOf<QtyUnit?>(null) + QtyUnit.entries, QtyUnit.ofCode(item.unit), { setItem(i, item.copy(unit = it?.code.orEmpty())) },
                                Modifier.weight(0.7f), text = { it?.label ?: "—" }, dense = true)
                            CellField(item.rate, { setItem(i, item.copy(rate = it)) }, Modifier.weight(0.8f), "0", TextAlign.End)
                            Box(Modifier.weight(0.25f), contentAlignment = Alignment.Center) {
                                IconButton(onClick = {
                                    // Same as the phone: remove, then renumber serials 1..n.
                                    val left = data.items.filterIndexed { j, _ -> j != i }.mapIndexed { j, it -> it.copy(serialNo = (j + 1).toString()) }
                                    data = data.copy(items = left)
                                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Remove row", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    data = data.copy(items = data.items + OrderItem(serialNo = (data.items.size + 1).toString(), unit = Units.forNewRow(data.items)))
                }) { IconText(Icons.Default.Add, "Add Row") }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth(), title = "Logistics & terms") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(data.deliveryReady, { data = data.copy(deliveryReady = it) }, "Delivery Ready / Challan By", Modifier.weight(1f))
                    Field(data.transportTo, { data = data.copy(transportTo = it) }, "Transport To", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(data.cashPayment, { data = data.copy(cashPayment = it) }, "Cash Payment Terms", Modifier.weight(1f))
                    Field(data.lessDisc, { data = data.copy(lessDisc = it) }, "Less / Discount", Modifier.weight(1f))
                    Field(data.side, { data = data.copy(side = it) }, "Side", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Field(data.remarks, { data = data.copy(remarks = it) }, "Remarks / Terms", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
