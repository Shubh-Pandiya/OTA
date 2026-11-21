package com.ota.desktop.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyBook
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.data.AdviceFilter
import com.ota.desktop.data.BillFilter
import com.ota.desktop.data.OrderFilter

/**
 * A buyer's or supplier's full history from the desktop's permanent store: orders, bills and
 * payment advices with their status and what is still due. Rows open the record on its own screen.
 */
@Composable
fun PartyDetail(party: Party, onBack: () -> Unit, onChanged: (Party?) -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var openOnly by remember(party.id) { mutableStateOf(true) }
    var counterparty by remember(party.id) { mutableStateOf<PartyBook.Counterparty?>(null) }
    var editing by remember(party.id) { mutableStateOf(false) }
    val book by rememberLoad<PartyBook.Book?>(null, party) {
        // The longest word of the name narrows the queries; PartyBook then matches names exactly as the app does.
        val word = PartyBook.searchWord(party.name)
        val orders = app.orders.list(OrderFilter(text = word), limit = 100_000)
        val bills = app.payments.bills(BillFilter(text = word))
        val advices = app.payments.search(AdviceFilter(text = word))
        PartyBook.build(
            party.type, party.name,
            orders.map { PartyBook.Order(it.id, it.orderNo, it.data.date, it.day?.let(SearchText::dayKey) ?: 0, it.data.buyer, it.data.supplier, it.billNos) },
            bills.map { PartyBook.BillItem(it.bill, it.buyer, it.supplier) },
            advices.map { PartyBook.Advice(it.id, it.adviceNo, it.payDate, it.buyer, it.supplier, it.paid, it.billNos) }
        )
    }
    val otherType = if (party.type == PartyType.BUYER) PartyType.SUPPLIER else PartyType.BUYER

    Column(Modifier.fillMaxSize()) {
        val place = listOf(party.city, party.phone, party.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN $it" }.orEmpty()).filter { it.isNotBlank() }
        PageHeader(party.name, (listOf(party.type.label) + place).joinToString(" · ")) {
            OutlinedButton(onClick = onBack) { IconText(Icons.AutoMirrored.Filled.ArrowBack, "All ${party.type.pluralLabel.lowercase()}") }
            Button(onClick = { editing = !editing }) { IconText(Icons.Default.Edit, if (editing) "Close edit" else "Edit details") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.8f).fillMaxHeight()) {
                val whole = book
                if (whole == null) {
                    Panel(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    }
                } else {
                    val focus = remember(whole, counterparty) { whole.view(openOnly = false, counterparty = counterparty?.key) }
                    val shown = remember(whole, openOnly, counterparty) { whole.view(openOnly, counterparty?.key) }
                    Summary(focus)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Open only", openOnly) { openOnly = true }
                        Chip("All", !openOnly) { openOnly = false }
                        Spacer(Modifier.width(8.dp))
                        if (whole.counterparties.size > 1) {
                            Select(
                                otherType.label, listOf<PartyBook.Counterparty?>(null) + whole.counterparties, counterparty, { counterparty = it },
                                Modifier.width(320.dp),
                                text = { c -> c?.let { if (it.outstanding > 0) "${it.name} · ${rupees(it.outstanding)} due" else it.name } ?: "All ${otherType.pluralLabel.lowercase()}" }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Panel(Modifier.fillMaxSize(), padding = PaddingValues(0.dp)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            billsTable(shown, otherType, openOnly) { nav.go(Section.BILLS, Target.Bill(it)) }
                            ordersTable(shown, otherType, openOnly) { nav.go(Section.ORDERS, Target.Order(it)) }
                            advicesTable(shown, otherType, openOnly) { nav.go(Section.ADVICES, Target.Advice(it)) }
                        }
                    }
                }
            }
            if (editing) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    androidx.compose.runtime.key(party.id) {
                        PartyForm(party, onDone = { saved -> editing = false; if (saved != null) onChanged(saved) }, onDeleted = { onChanged(null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(book: PartyBook.Book) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Total outstanding", rupees(book.outstanding), Modifier.weight(1.7f), Brand.SaffronDark, big = true,
            note = if (book.noAmountBills > 0) "${book.noAmountBills} bill(s) without an amount not counted" else null)
        Stat("Open bills", book.openBills.toString(), Modifier.weight(0.8f), if (book.openBills > 0) Brand.Red else Brand.Green)
        Stat("Partly paid", book.partlyPaidBills.toString(), Modifier.weight(0.8f), Brand.Amber)
        Stat("Not billed", book.unbilledOrders.toString(), Modifier.weight(0.9f), Brand.Blue)
        Stat("Billed", rupees(book.billed), Modifier.weight(1.5f))
        Stat("Advised paid", rupees(book.received), Modifier.weight(1.5f), Brand.Green)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = Color.Unspecified, big: Boolean = false, note: String? = null) {
    Panel(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value, color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = (if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).merge(Numeric)
        )
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.sectionTitle(title: String, count: Int) {
    item(key = "t-$title") {
        Text(
            "$title ($count)", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp)
        )
    }
}

private fun LazyListScope.emptyRow(key: String, text: String) {
    item(key = "e-$key") { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
}

private fun LazyListScope.billsTable(book: PartyBook.Book, other: PartyType, openOnly: Boolean, open: (String) -> Unit) {
    sectionTitle("Bills", book.bills.size)
    item(key = "h-bills") {
        TableHeader(listOf(
            Col("Bill", 0f, width = BILL_NO_W), Col("Date", 0f, width = DATE_W), Col(other.label, 2f),
            Col("Amount", 0f, TextAlign.End, AMOUNT_W), Col("Balance due", 0f, TextAlign.End, AMOUNT_W), Col("Status", 0f, width = STATUS_W)
        ))
    }
    if (book.bills.isEmpty()) emptyRow("bills", if (openOnly) "No bills due" else "No bills")
    items(book.bills, key = { "b" + it.bill.id }) { e ->
        val b = e.bill
        TableRow(false, { open(b.id) }) {
            Cell(b.billNo, 0f, bold = true, width = BILL_NO_W)
            Cell(b.billDate.dmy(), 0f, width = DATE_W)
            Cell(e.other, 2f)
            Cell(rupees(b.amount), 0f, TextAlign.End, width = AMOUNT_W)
            Cell(rupees(b.balance), 0f, TextAlign.End, bold = e.open, color = if (e.open) Brand.SaffronDark else Color.Unspecified, width = AMOUNT_W)
            Row(Modifier.width(STATUS_W)) { StatusPill(b.status) }
        }
    }
}

private fun LazyListScope.ordersTable(book: PartyBook.Book, other: PartyType, openOnly: Boolean, open: (String) -> Unit) {
    sectionTitle("Orders", book.orders.size)
    item(key = "h-orders") {
        TableHeader(listOf(
            Col("Order", 0f, width = ORDER_NO_W), Col("Date", 0f, width = DATE_W), Col(other.label, 2f), Col("Bills", 1f),
            Col("Due", 0f, TextAlign.End, AMOUNT_W), Col("Status", 0f, width = STATUS_W)
        ))
    }
    if (book.orders.isEmpty()) emptyRow("orders", if (openOnly) "No open orders" else "No orders")
    items(book.orders, key = { "o" + it.order.id }) { e ->
        val o = e.order
        TableRow(false, { open(o.id) }) {
            Cell(OrderNumber.display(o.orderNo), 0f, bold = true, width = ORDER_NO_W)
            Cell(o.date, 0f, width = DATE_W)
            Cell(e.other, 2f)
            Cell(o.billNos.joinToString(", ").ifEmpty { "—" }, 1f)
            val due = e.bills.mapNotNull { it.balance }.filter { it > 0 }.sum()
            Cell(if (due > 0) rupees(due) else "—", 0f, TextAlign.End, width = AMOUNT_W)
            Row(Modifier.width(STATUS_W)) { OrderStatePill(e.state) }
        }
    }
}

private fun LazyListScope.advicesTable(book: PartyBook.Book, other: PartyType, openOnly: Boolean, open: (String) -> Unit) {
    sectionTitle("Payment advices", book.advices.size)
    item(key = "h-advices") {
        TableHeader(listOf(
            Col("Advice", 0f, width = ADVICE_NO_W), Col("Date", 0f, width = DATE_W), Col(other.label, 2f), Col("Bills", 1f),
            Col("Paid", 0f, TextAlign.End, AMOUNT_W)
        ))
    }
    if (book.advices.isEmpty()) emptyRow("advices", if (openOnly) "No advices on bills still due" else "No payment advices")
    items(book.advices, key = { "a" + it.advice.id }) { e ->
        val a = e.advice
        TableRow(false, { open(a.id) }) {
            Cell(AdviceNumber.display(a.adviceNo), 0f, bold = true, width = ADVICE_NO_W)
            Cell(a.payDate.dmy(), 0f, width = DATE_W)
            Cell(e.other, 2f)
            Cell(a.billNos.joinToString(", ").ifEmpty { "—" }, 1f)
            Cell(rupees(a.paid), 0f, TextAlign.End, width = AMOUNT_W)
        }
    }
    item(key = "end") { Spacer(Modifier.height(16.dp)) }
}

@Composable
private fun OrderStatePill(state: PartyBook.OrderState) {
    val color = when (state) {
        PartyBook.OrderState.UNBILLED -> Brand.Blue
        PartyBook.OrderState.BILL_DUE -> Brand.status(PaymentStatus.UNPAID)
        PartyBook.OrderState.PARTLY_PAID -> Brand.status(PaymentStatus.PARTLY_PAID)
        PartyBook.OrderState.PAID, PartyBook.OrderState.BILLED -> Brand.status(PaymentStatus.PAID)
    }
    Pill(state.label, color)
}
