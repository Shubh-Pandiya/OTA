package com.example.otaapplication

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.otaapplication.ui.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.BillListItem
import com.example.otaapplication.data.BillQuery
import com.example.otaapplication.data.OrderQuery
import com.example.otaapplication.data.OrderSummary
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyBook
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.theme.BillReceivedContainer
import com.example.otaapplication.ui.theme.OnBillReceivedContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The book of one party plus what the cards need to open each record. */
private class PartyData(
    val book: PartyBook.Book,
    val orders: Map<String, OrderSummary>,
    val bills: Map<String, BillListItem>,
    val advices: Map<String, AdviceSummary>
)

/**
 * A buyer's or supplier's orders, bills and payment advices with what is still due. Bills and
 * advices open over this screen (Back returns here); orders open in Order history.
 */
@Composable
fun PartyDetailScreen(
    party: Party,
    deps: PaymentDeps,
    remoteChanges: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenOrder: (OrderSummary) -> Unit
) {
    var openOnly by rememberSaveable(party.id) { mutableStateOf(true) }
    var counterparty by rememberSaveable(party.id) { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<PaymentPage>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    val data by produceState<PartyData?>(null, party, version, remoteChanges) {
        value = withContext(Dispatchers.IO) {
            val orders = deps.orders.search(OrderQuery())
            val bills = deps.payments.bills(BillQuery())
            val advices = deps.payments.search("")
            val book = PartyBook.build(
                party.type, party.name,
                orders.map { PartyBook.Order(it.id, it.offerNo, it.date, runCatching { SearchText.dayKey(it.date) }.getOrDefault(0), it.buyer, it.supplier, it.billNos) },
                bills.map { PartyBook.BillItem(it.bill, it.buyer, it.supplier) },
                advices.map { PartyBook.Advice(it.id, it.adviceNo, it.payDate, it.buyer, it.supplier, it.paid, it.billNos) }
            )
            PartyData(book, orders.associateBy { it.id }, bills.associateBy { it.bill.id }, advices.associateBy { it.id })
        }
    }
    BackHandler(enabled = pages.isEmpty()) { onBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PartyHeader(party, onBack, onEdit)
            AnimatedContent(
                targetState = data,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                contentKey = { it == null },
                label = "partyBook"
            ) { loaded ->
                if (loaded == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    PartyBookList(
                        data = loaded,
                        openOnly = openOnly,
                        onOpenOnlyChange = { openOnly = it },
                        counterparty = counterparty,
                        onCounterpartyChange = { counterparty = it },
                        onOpenOrder = { id -> loaded.orders[id]?.let(onOpenOrder) },
                        onOpenBill = { pages = listOf(PaymentPage.BillView(it)) },
                        onOpenAdvice = { pages = listOf(PaymentPage.AdviceView(it)) }
                    )
                }
            }
        }
        PaymentPages(stack = pages, onStackChange = { pages = it }, deps = deps, version = version + remoteChanges, onChanged = { version++ })
    }
}

@Composable
private fun PartyHeader(party: Party, onBack: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to ${party.type.pluralLabel.lowercase()}") }
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(party.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(party.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val place = listOf(party.city, party.phone).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                if (place.isBlank()) party.type.label else "${party.type.label} · $place",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit ${party.type.label.lowercase()}") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartyBookList(
    data: PartyData,
    openOnly: Boolean,
    onOpenOnlyChange: (Boolean) -> Unit,
    counterparty: String?,
    onCounterpartyChange: (String?) -> Unit,
    onOpenOrder: (String) -> Unit,
    onOpenBill: (String) -> Unit,
    onOpenAdvice: (String) -> Unit
) {
    val whole = data.book
    val shown = remember(whole, openOnly, counterparty) { whole.view(openOnly, counterparty) }
    val focus = remember(whole, counterparty) { whole.view(openOnly = false, counterparty = counterparty) }
    val otherLabel = if (whole.type == PartyType.BUYER) "suppliers" else "buyers"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.navigationBars.asPaddingValues().let {
            PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = it.calculateBottomPadding() + 24.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") { SummaryCard(focus) }
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to "Open only", false to "All").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = openOnly == value,
                            onClick = { onOpenOnlyChange(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)
                        ) { Text(label) }
                    }
                }
                if (whole.counterparties.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            FilterChip(selected = counterparty == null, onClick = { onCounterpartyChange(null) }, label = { Text("All $otherLabel") })
                        }
                        items(whole.counterparties, key = { it.key }) { c ->
                            FilterChip(
                                selected = counterparty == c.key,
                                onClick = { onCounterpartyChange(if (counterparty == c.key) null else c.key) },
                                label = {
                                    Text(
                                        if (c.outstanding > 0) "${c.name} · ${rupees(c.outstanding)}" else c.name,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        section("bills", "Bills", Icons.AutoMirrored.Filled.ReceiptLong, shown.bills.size, if (openOnly) "No bills due" else "No bills")
        items(shown.bills, key = { "b" + it.bill.id }) { entry ->
            val item = data.bills[entry.bill.id] ?: BillListItem(entry.bill, entry.item.buyer, entry.item.supplier)
            BillCard(item, onClick = { onOpenBill(entry.bill.id) }, modifier = Modifier.animateItem())
        }
        section("orders", "Orders", Icons.Default.History, shown.orders.size, if (openOnly) "No open orders" else "No orders")
        items(shown.orders, key = { "o" + it.order.id }) { entry ->
            OrderRow(entry, onClick = { onOpenOrder(entry.order.id) }, modifier = Modifier.animateItem())
        }
        section("advices", "Payment advices", Icons.Default.Payments, shown.advices.size, if (openOnly) "No advices on bills still due" else "No payment advices")
        items(shown.advices, key = { "a" + it.advice.id }) { entry ->
            data.advices[entry.advice.id]?.let { AdviceCard(it, onClick = { onOpenAdvice(entry.advice.id) }, modifier = Modifier.animateItem()) }
        }
        item(key = "note") {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Open items are always here. Older settled records are on the desktop.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(key: String, title: String, icon: ImageVector, count: Int, empty: String) {
    item(key = "h-$key") {
        Column(Modifier.animateItem()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("$title ($count)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            if (count == 0) {
                Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(book: PartyBook.Book) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("Total outstanding", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rupees(book.outstanding), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (book.noAmountBills > 0) {
                    Text(
                        "${book.noAmountBills} bill${if (book.noAmountBills == 1) "" else "s"} without an amount not counted",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Open bills", book.openBills.toString(), Modifier.weight(1f))
                Stat("Partly paid", book.partlyPaidBills.toString(), Modifier.weight(1f))
                Stat("Not billed", book.unbilledOrders.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Billed", rupees(book.billed), Modifier.weight(1f))
                Stat("Advised paid", rupees(book.received), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun OrderRow(entry: PartyBook.OrderEntry, onClick: () -> Unit, modifier: Modifier) {
    val order = entry.order
    Surface(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order ${OrderNumber.display(order.orderNo)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OrderStatePill(entry.state)
            }
            Text(entry.other.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bills = if (order.billNos.isEmpty()) "" else "Bill " + order.billNos.joinToString(", ")
            val due = entry.bills.mapNotNull { it.balance }.filter { it > 0 }.sum()
            Text(
                listOf(order.date, bills, if (due > 0) "Due ${rupees(due)}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OrderStatePill(state: PartyBook.OrderState) {
    when (state) {
        PartyBook.OrderState.BILL_DUE -> PaymentStatusPill(PaymentStatus.UNPAID, label = state.label)
        PartyBook.OrderState.PARTLY_PAID -> PaymentStatusPill(PaymentStatus.PARTLY_PAID)
        PartyBook.OrderState.PAID -> PaymentStatusPill(PaymentStatus.PAID)
        PartyBook.OrderState.UNBILLED -> Pill(state.label, Icons.Default.Description, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        PartyBook.OrderState.BILLED -> Pill(state.label, Icons.Default.Done, BillReceivedContainer.copy(alpha = 0.6f), OnBillReceivedContainer)
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector, bg: Color, fg: Color) {
    Row(
        modifier = Modifier.clip(CircleShape).background(bg).padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
