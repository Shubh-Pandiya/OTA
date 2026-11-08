@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.otaapplication

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import com.example.otaapplication.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillDetails
import com.example.otaapplication.data.BillPayment
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OrderQuery
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.OrderSummary
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentRepository
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.theme.BillPendingContainer
import com.example.otaapplication.ui.theme.BillReceivedContainer
import com.example.otaapplication.ui.theme.OnBillPendingContainer
import com.example.otaapplication.ui.theme.OnBillReceivedContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val billDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
internal val amountFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
    minimumFractionDigits = 0; maximumFractionDigits = 2
}

internal fun rupees(value: Double): String = "₹ ${amountFormat.format(value)}"
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun Long.pickerMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val PartlyPaidContainer = Color(0xFFFFE0B2)
private val OnPartlyPaidContainer = Color(0xFF7A3E00)

@Composable
fun PaymentStatusPill(status: PaymentStatus, modifier: Modifier = Modifier) {
    val (bg, fg, icon) = when (status) {
        PaymentStatus.PAID -> Triple(BillReceivedContainer, OnBillReceivedContainer, Icons.Default.TaskAlt)
        PaymentStatus.PARTLY_PAID -> Triple(PartlyPaidContainer, OnPartlyPaidContainer, Icons.Default.Timelapse)
        PaymentStatus.UNPAID -> Triple(BillPendingContainer, OnBillPendingContainer, Icons.Default.Schedule)
    }
    Row(
        modifier = modifier.clip(CircleShape).background(bg).padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(status.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** Read-only date field that opens a date picker. */
@Composable
fun DateField(label: String, day: Long?, onChange: (Long?) -> Unit, clearable: Boolean = true, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) showPicker = true }
    }
    OutlinedTextField(
        value = day?.let { LocalDate.ofEpochDay(it).format(billDateFormat) }.orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (clearable && day != null) {
            { IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Close, contentDescription = "Clear $label") } }
        } else null,
        singleLine = true,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth()
    )
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day?.let(LocalDate::ofEpochDay) ?: LocalDate.now()).toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(it.pickerMillisToDate().toEpochDay()) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

/** Single-line amount field (₹). Blank is allowed; [isError] marks unreadable text. */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    prefix: String = "₹",
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String? = null
) {
    val error = value.isNotBlank() && PaymentMath.parse(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Text(prefix, style = MaterialTheme.typography.titleMedium) },
        singleLine = true,
        isError = error,
        supportingText = when {
            error -> { { Text("Enter a number") } }
            supportingText != null -> { { Text(supportingText) } }
            else -> null
        },
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier.fillMaxWidth()
    )
}

internal fun amountValid(text: String) = text.isBlank() || PaymentMath.parse(text) != null

/**
 * Editable bill details: date, amount, LR (bilty) number, cash discount (% and ₹) and the item
 * lines as billed. The amount follows the lines' total until the user types a different one.
 */
class BillFieldsState(
    day: Long? = null,
    amount: String = "",
    lrNo: String = "",
    cdPercent: String = "",
    cdAmount: String = "",
    items: List<OrderItem> = emptyList()
) {
    var day by mutableStateOf(day)
    var amount by mutableStateOf(amount)
        private set
    var lrNo by mutableStateOf(lrNo)
    var cdPercent by mutableStateOf(cdPercent)
    var cdAmount by mutableStateOf(cdAmount)
    val items = mutableStateListOf<OrderItem>().apply { addAll(items) }

    /** True while the amount is blank or equal to the lines' total, so it keeps following them. */
    private var amountFollowsLines = amount.isBlank() || PaymentMath.parse(amount) == PaymentMath.linesTotal(items)

    val linesTotal: Double? get() = PaymentMath.linesTotal(items)

    val valid: Boolean
        get() = amountValid(amount) && amountValid(cdPercent) && amountValid(cdAmount) &&
            items.all { amountValid(it.quantity) && amountValid(it.rate) }

    fun typeAmount(text: String) {
        amount = text
        amountFollowsLines = text.isBlank() || PaymentMath.parse(text) == linesTotal
        syncDiscount()
    }

    fun setItems(updated: List<OrderItem>) {
        items.clear()
        items.addAll(updated.mapIndexed { i, item -> item.copy(serialNo = (i + 1).toString()) })
        if (amountFollowsLines) {
            amount = PaymentMath.plain(linesTotal)
            syncDiscount()
        }
    }

    fun updateItem(index: Int, item: OrderItem) = setItems(items.toMutableList().also { it[index] = item })

    fun addItem() = setItems(items + OrderItem(unit = Units.forNewRow(items)))

    fun removeItem(index: Int) = setItems(items.toMutableList().also { it.removeAt(index) })

    /** Keeps a percentage-based cash discount in step with the amount. */
    fun syncDiscount() {
        PaymentMath.percentOf(PaymentMath.parse(amount), PaymentMath.parse(cdPercent))?.let { cdAmount = PaymentMath.plain(it) }
    }

    fun details() = BillDetails(
        billDate = day?.let(LocalDate::ofEpochDay),
        amount = PaymentMath.parse(amount),
        lrNo = lrNo.trim(),
        cdPercent = PaymentMath.parse(cdPercent),
        cdAmount = PaymentMath.parse(cdAmount),
        items = items.filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() || it.rate.isNotBlank() }
            .mapIndexed { i, item -> item.copy(serialNo = (i + 1).toString(), particulars = item.particulars.trim(), quantity = item.quantity.trim(), rate = item.rate.trim()) }
    )

    /** Fills empty fields from an existing bill (used when an existing bill number is picked). */
    fun fillBlanks(bill: Bill) {
        if (day == null) day = bill.billDate?.toEpochDay()
        if (items.isEmpty() && bill.items.isNotEmpty()) setItems(bill.items)
        if (amount.isBlank()) typeAmount(PaymentMath.plain(bill.amount))
        if (lrNo.isBlank()) lrNo = bill.lrNo
        if (cdPercent.isBlank()) cdPercent = PaymentMath.plain(bill.cdPercent)
        if (cdAmount.isBlank()) cdAmount = PaymentMath.plain(bill.cdAmount)
    }

    companion object {
        fun of(bill: Bill) = BillFieldsState(
            bill.billDate?.toEpochDay(), PaymentMath.plain(bill.amount), bill.lrNo, PaymentMath.plain(bill.cdPercent),
            PaymentMath.plain(bill.cdAmount), bill.items
        )
    }
}

/**
 * Bill fields. [loadOrderItems], when given, offers to copy the linked orders' items into the
 * bill lines as a starting point (what was billed often differs, so they stay editable).
 */
@Composable
fun BillFields(state: BillFieldsState, optional: Boolean, loadOrderItems: (suspend () -> List<OrderItem>)? = null) {
    val suffix = if (optional) " (optional)" else ""
    DateField(label = "Bill date$suffix", day = state.day, onChange = { state.day = it })
    OutlinedTextField(
        value = state.lrNo,
        onValueChange = { state.lrNo = it },
        label = { Text("LR / Bilty number$suffix") },
        leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
    BillLinesEditor(state, loadOrderItems)
    val total = state.linesTotal
    AmountField(
        value = state.amount,
        onValueChange = state::typeAmount,
        label = "Bill amount$suffix",
        supportingText = when {
            total == null -> null
            PaymentMath.parse(state.amount) == total -> "Total of the bill lines"
            else -> "Lines total ${rupees(total)}"
        }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AmountField(
            value = state.cdPercent,
            onValueChange = { text ->
                state.cdPercent = text
                state.syncDiscount()
            },
            label = "Cash disc. %",
            prefix = "%",
            modifier = Modifier.weight(1f)
        )
        AmountField(
            value = state.cdAmount,
            onValueChange = { state.cdAmount = it },
            label = "Cash disc. ₹",
            imeAction = ImeAction.Done,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Item lines as billed by the supplier: particulars, qty + unit and rate, with each line's amount. */
@Composable
private fun BillLinesEditor(state: BillFieldsState, loadOrderItems: (suspend () -> List<OrderItem>)?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bill items", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Units.totals(state.items)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.items.isEmpty()) {
            Text(
                "Optional. Enter what the supplier actually billed; it can differ from the order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.items.forEachIndexed { index, item ->
            key(item.key) {
                BillLineRow(
                    item = item,
                    onChange = { state.updateItem(index, it) },
                    onRemove = { state.removeItem(index) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = state::addItem, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add line")
            }
            if (loadOrderItems != null) {
                TextButton(onClick = {
                    scope.launch {
                        val copied = runCatching { withContext(Dispatchers.IO) { loadOrderItems() } }.getOrDefault(emptyList())
                        if (copied.isEmpty()) {
                            Toast.makeText(context, "The order has no items to copy", Toast.LENGTH_SHORT).show()
                        } else {
                            state.setItems(state.items.filter { it.particulars.isNotBlank() } + copied.map { it.copy(key = OrderItem().key) })
                        }
                    }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy from order")
                }
            }
        }
    }
}

@Composable
private fun BillLineRow(item: OrderItem, onChange: (OrderItem) -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Line ${item.serialNo}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                val qty = PaymentMath.parse(item.quantity)
                val rate = PaymentMath.parse(item.rate)
                if (qty != null && rate != null) {
                    Text(rupees(PaymentMath.round2(qty * rate)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove line ${item.serialNo}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = item.particulars,
                    onValueChange = { onChange(item.copy(particulars = it)) },
                    label = { Text("Particulars") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = item.quantity,
                        onValueChange = { onChange(item.copy(quantity = it)) },
                        label = { Text("Qty") },
                        singleLine = true,
                        isError = !amountValid(item.quantity),
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                    UnitField(unit = item.unit, onUnitChange = { onChange(item.copy(unit = it)) }, modifier = Modifier.width(116.dp))
                    OutlinedTextField(
                        value = item.rate,
                        onValueChange = { onChange(item.copy(rate = it)) },
                        label = { Text("Rate") },
                        singleLine = true,
                        isError = !amountValid(item.rate),
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Items of the given orders, numbered afresh, as a starting point for a bill's lines. */
internal fun orderItemsFor(context: android.content.Context, orderIds: Collection<String>): List<OrderItem> =
    LocalOrders.get(context).contentOf(orderIds).values
        .flatMap { order -> order.items.map(Units::splitLegacy) }
        .filter { it.particulars.isNotBlank() || it.quantity.isNotBlank() }
        .mapIndexed { i, item -> item.copy(serialNo = (i + 1).toString()) }

/** Repositories and staff state the payment pages need. */
class PaymentDeps(
    val orders: OrderRepository,
    val payments: PaymentRepository,
    val catalog: CatalogRepository?,
    val numbers: AdviceNumberAllocator,
    val staffCode: Int?,
    val staffMessage: String
)

/** Pages stacked over a screen: bill and advice details and editors link to one another. */
sealed interface PaymentPage {
    data class BillView(val billId: String) : PaymentPage
    /** New bill (entered from the paper bill) when [billId] is null. */
    data class BillEdit(val billId: String?) : PaymentPage
    data class AdviceView(val adviceId: String) : PaymentPage
    /** New advice when [adviceId] is null, optionally starting from a bill and its parties. */
    data class AdviceEdit(
        val adviceId: String?,
        val presetBillId: String? = null,
        val presetBuyer: String = "",
        val presetSupplier: String = ""
    ) : PaymentPage
}

/** Shows the top page of [stack]; closing a page goes back to the one below it. */
@Composable
fun PaymentPages(
    stack: List<PaymentPage>,
    onStackChange: (List<PaymentPage>) -> Unit,
    deps: PaymentDeps,
    version: Int,
    onChanged: () -> Unit
) {
    val top = stack.lastOrNull() ?: return
    val pop = { onStackChange(stack.dropLast(1)) }
    val push = { page: PaymentPage -> onStackChange(stack + page) }
    key(stack.size, top) {
        when (top) {
            is PaymentPage.BillView -> BillDetailPage(top.billId, deps, version, onBack = pop, onOpen = push, onDeleted = { onChanged(); pop() })
            is PaymentPage.BillEdit -> BillEditPage(top.billId, deps, onDone = { savedId ->
                if (savedId == null) pop()
                else {
                    onChanged()
                    // A new bill opens its detail page in place of the editor.
                    onStackChange(stack.dropLast(1).let { below ->
                        if (below.lastOrNull() == PaymentPage.BillView(savedId)) below else below + PaymentPage.BillView(savedId)
                    })
                }
            })
            is PaymentPage.AdviceView -> AdviceDetailPage(
                adviceId = top.adviceId,
                deps = deps,
                version = version,
                onBack = pop,
                onOpen = push,
                onDeleted = { onChanged(); pop() }
            )
            is PaymentPage.AdviceEdit -> AdviceEditorPage(
                page = top,
                deps = deps,
                onCancel = pop,
                onSaved = { adviceId ->
                    onChanged()
                    // A new advice opens its detail page in place of the editor.
                    onStackChange(stack.dropLast(1).let { below ->
                        if (below.lastOrNull() == PaymentPage.AdviceView(adviceId)) below else below + PaymentPage.AdviceView(adviceId)
                    })
                }
            )
        }
    }
}

private data class BillPageData(val bill: Bill?, val orders: List<OrderSummary>, val payments: List<BillPayment>)

@Composable
private fun BillDetailPage(
    billId: String,
    deps: PaymentDeps,
    version: Int,
    onBack: () -> Unit,
    onOpen: (PaymentPage) -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by produceState<BillPageData?>(null, billId, version) {
        value = withContext(Dispatchers.IO) {
            BillPageData(deps.orders.bill(billId), deps.orders.ordersForBill(billId), deps.payments.advicesForBill(billId))
        }
    }
    val bill = data?.bill
    val parties = data?.let { d -> d.bill?.let { billParties(it, d.orders) } }
    var confirmDelete by remember { mutableStateOf(false) }
    FormDialog(
        title = bill?.let { "Bill ${it.billNo}" } ?: "Bill",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        onDismiss = onBack,
        onConfirm = null,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        actions = {
            if (bill != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Delete bill") }
                IconButton(onClick = { onOpen(PaymentPage.BillEdit(billId)) }) { Icon(Icons.Default.Edit, contentDescription = "Edit bill") }
            }
        },
        bottomBar = if (bill != null && parties != null) {
            {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                    Button(
                        onClick = { onOpen(PaymentPage.AdviceEdit(null, billId, parties.first, parties.second)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(50.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New payment advice")
                    }
                }
            }
        } else null
    ) {
        when {
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            bill == null -> Text("This bill was removed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val d = data!!
                BillBalanceCard(bill)
                FormSection(title = "Bill details", icon = Icons.Default.Description) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DetailRow("Bill / invoice no", bill.billNo)
                        DetailRow("Buyer", parties?.first.orEmpty())
                        DetailRow("Supplier", parties?.second.orEmpty())
                        DetailRow("Bill date", bill.billDate?.format(billDateFormat).orEmpty())
                        DetailRow("Amount", bill.amount?.let(::rupees).orEmpty())
                        DetailRow("LR / Bilty no", bill.lrNo)
                        DetailRow("Cash discount", cashDiscountText(bill.cdPercent, bill.cdAmount))
                    }
                }
                if (bill.items.isNotEmpty()) {
                    FormSection(
                        title = "Bill items",
                        icon = Icons.AutoMirrored.Filled.Segment,
                        trailing = { Units.totals(bill.items)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            bill.items.forEach { item -> BillItemRow(item) }
                            bill.linesTotal?.let { total ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                DetailRow("Lines total", rupees(total))
                            }
                        }
                    }
                }
                FormSection(
                    title = "Orders",
                    icon = Icons.Default.Inventory2,
                    trailing = { Text("${d.orders.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ) {
                    if (d.orders.isEmpty()) {
                        Text("Not linked to any order.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    d.orders.forEach { order ->
                        LinkRow(
                            icon = Icons.Default.Description,
                            title = "Offer ${OrderNumber.display(order.offerNo)}",
                            subtitle = listOf(order.date, SearchText.firstLine(order.buyer), SearchText.firstLine(order.supplier))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = null
                        )
                    }
                }
                FormSection(
                    title = "Payment advices",
                    icon = Icons.Default.Payments,
                    trailing = { Text("${d.payments.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ) {
                    if (d.payments.isEmpty()) {
                        Text("No payment recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    d.payments.forEach { payment ->
                        val a = payment.amounts
                        val parts = listOfNotNull(
                            "Paid ${rupees(a.paid)}",
                            a.cdAmount?.let { "CD ${rupees(it)}" },
                            a.goodsReturn?.let { "GR ${rupees(it)}" },
                            a.rateDiff?.let { "RD ${rupees(it)}" },
                            a.claim?.let { "Claim ${rupees(it)}" + a.claimNote.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" }.orEmpty() }
                        )
                        LinkRow(
                            icon = Icons.Default.Payments,
                            title = payment.adviceNo,
                            subtitle = listOfNotNull(payment.payDate?.format(billDateFormat), parts.joinToString(" · ")).joinToString(" · "),
                            onClick = { onOpen(PaymentPage.AdviceView(payment.adviceId)) }
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete && bill != null) {
        DeleteBillDialog(bill, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { deps.orders.deleteBill(billId) } }
                result.onSuccess {
                    Toast.makeText(context, "Bill ${bill.billNo} deleted", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }.onFailure { Toast.makeText(context, it.message ?: "Could not delete the bill", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

@Composable
private fun DeleteBillDialog(bill: Bill, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete bill ${bill.billNo}?") },
        text = {
            Text(
                if (bill.adviceCount > 0) "This bill is on ${bill.adviceCount} payment advice${if (bill.adviceCount == 1) "" else "s"}. Remove it from the advice first."
                else if (bill.orderCount > 0) "It will be removed from its ${bill.orderCount} order${if (bill.orderCount == 1) "" else "s"}, which go back to Pending if they have no other bill."
                else "The bill will be removed from every phone and the laptop."
            )
        },
        confirmButton = {
            if (bill.adviceCount == 0) {
                TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (bill.adviceCount == 0) "Cancel" else "OK") } }
    )
}

/** Buyer and supplier of a bill: those stored on it, else those of its first order; null when unknown. */
internal fun billParties(bill: Bill, orders: List<OrderSummary>): Pair<String, String>? {
    if (bill.buyer.isNotBlank() && bill.supplier.isNotBlank()) return bill.buyer to bill.supplier
    val first = orders.firstOrNull() ?: return null
    return SearchText.firstLine(bill.buyer.ifBlank { first.buyer }) to SearchText.firstLine(bill.supplier.ifBlank { first.supplier })
}

@Composable
private fun BillItemRow(item: OrderItem) {
    val qty = PaymentMath.parse(item.quantity)
    val rate = PaymentMath.parse(item.rate)
    Row(verticalAlignment = Alignment.Top) {
        Text("${item.serialNo}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(item.particulars.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    Units.display(item).takeIf { it.isNotBlank() },
                    item.rate.takeIf { it.isNotBlank() }?.let { "@ ₹$it" }
                ).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (qty != null && rate != null) Text(rupees(PaymentMath.round2(qty * rate)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

internal fun cashDiscountText(percent: Double?, amount: Double?): String =
    listOfNotNull(percent?.let { "${PaymentMath.plain(it)}%" }, amount?.let(::rupees)).joinToString(" · ")

@Composable
private fun BillBalanceCard(bill: Bill) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance due", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                PaymentStatusPill(bill.status)
            }
            Text(
                bill.balance?.let(::rupees) ?: "Amount not entered",
                style = if (bill.balance != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                listOfNotNull(bill.amount?.let { "Bill ${rupees(it)}" }, "Settled ${rupees(bill.settled)}", "${bill.adviceCount} advice${if (bill.adviceCount == 1) "" else "s"}")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
        if (onClick != null) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Adds a bill from the supplier's paper bill ([billId] null) or edits one: number (new bills
 * only), buyer and supplier, the orders it covers (optional) and its details. Linking an order
 * fills blank parties from it; a bill without orders needs both parties.
 */
@Composable
private fun BillEditPage(billId: String?, deps: PaymentDeps, onDone: (savedId: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(billId == null) }
    var billNo by remember { mutableStateOf("") }
    var buyer by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    val linked = remember { mutableStateListOf<OrderSummary>() }
    var state by remember { mutableStateOf(if (billId == null) BillFieldsState(day = LocalDate.now().toEpochDay()) else null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(billId) {
        if (billId == null) return@LaunchedEffect
        val (bill, orders) = withContext(Dispatchers.IO) { deps.orders.bill(billId) to deps.orders.ordersForBill(billId) }
        if (bill == null) { onDone(null); return@LaunchedEffect }
        billNo = bill.billNo
        billParties(bill, orders)?.let { (b, s) -> buyer = b; supplier = s }
        linked.addAll(orders)
        state = BillFieldsState.of(bill)
        ready = true
    }
    val s = state
    val problems = buildList {
        if (billId == null && billNo.isBlank()) add("Enter the bill number from the supplier's bill.")
        if (linked.isEmpty() && (buyer.isBlank() || supplier.isBlank())) add("Enter the buyer and supplier, or link an order.")
        if (s != null && !s.valid) add("Fix the amounts marked in red.")
    }
    FormDialog(
        title = if (billId == null) "Add bill" else "Edit bill $billNo",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        onDismiss = { onDone(null) },
        confirmEnabled = ready && s != null && problems.isEmpty(),
        busy = busy,
        onConfirm = {
            val fields = s ?: return@FormDialog
            // Parties that match a linked order are left to the order, which keeps the row small.
            val fromOrders = linked.any { SearchText.partyKey(it.buyer) == SearchText.partyKey(buyer) && SearchText.partyKey(it.supplier) == SearchText.partyKey(supplier) }
            val details = fields.details().let { if (fromOrders) it else it.copy(buyer = buyer.trim(), supplier = supplier.trim()) }
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { deps.orders.saveBill(billId, billNo, details, linked.map { it.id }) } }
                busy = false
                result.onSuccess { bill ->
                    Toast.makeText(context, "Bill ${bill.billNo} saved", Toast.LENGTH_SHORT).show()
                    onDone(bill.id)
                }.onFailure { Toast.makeText(context, it.message ?: "Could not save the bill", Toast.LENGTH_LONG).show() }
            }
        }
    ) {
        if (s == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@FormDialog }
        FormSection(title = "Bill", icon = Icons.AutoMirrored.Filled.ReceiptLong) {
            FormTextField(
                value = billNo,
                onValueChange = { billNo = it },
                label = "Bill / invoice number",
                icon = Icons.Default.Tag,
                readOnly = billId != null,
                singleLine = true,
                supportingText = if (billId != null) "The number can't be changed; delete the bill and add it again." else "As printed on the supplier's bill"
            )
            val suffix = if (linked.isEmpty()) "" else " (from orders)"
            PartyField(PartyType.BUYER, buyer, { buyer = it }, "Buyer$suffix", "Buyer name", deps.catalog, nameOnly = true, imeAction = ImeAction.Next)
            PartyField(PartyType.SUPPLIER, supplier, { supplier = it }, "Supplier$suffix", "Supplier name", deps.catalog, nameOnly = true, imeAction = ImeAction.Next)
        }
        LinkedOrdersSection(linked, deps.orders) { order ->
            linked += order
            if (buyer.isBlank()) buyer = SearchText.firstLine(order.buyer)
            if (supplier.isBlank()) supplier = SearchText.firstLine(order.supplier)
        }
        FormSection(title = "Bill details", icon = Icons.Default.Description) {
            BillFields(s, optional = true, loadOrderItems = if (linked.isEmpty()) null else {
                { orderItemsFor(context, linked.mapNotNull { it.uuid }) }
            })
        }
        if (billId != null || billNo.isNotBlank()) problems.firstOrNull()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Orders the bill covers: tap × to unlink, or search by order number / party to link more. */
@Composable
private fun LinkedOrdersSection(linked: MutableList<OrderSummary>, orders: OrderRepository, onLink: (OrderSummary) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OrderSummary>>(emptyList()) }
    var searchedFor by remember { mutableStateOf("") }
    val resultsView = remember { BringIntoViewRequester() }
    LaunchedEffect(query, linked.size) {
        if (query.isBlank()) { results = emptyList(); searchedFor = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(200)
        val taken = linked.map { it.id }.toSet()
        // An order still waiting for its number can't take a bill yet.
        results = withContext(Dispatchers.IO) { orders.search(OrderQuery(text = query)) }
            .filter { it.id !in taken && !OrderNumber.isPending(it.offerNo) }
            .take(5)
        searchedFor = query
        // Keep the matches above the keyboard.
        kotlinx.coroutines.delay(50)
        resultsView.bringIntoView()
    }
    FormSection(
        title = "Orders (optional)",
        icon = Icons.Default.Inventory2,
        trailing = { if (linked.isNotEmpty()) Text("${linked.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ) {
        linked.forEach { order ->
            LinkRow(
                icon = Icons.Default.Description,
                title = "Offer ${OrderNumber.display(order.offerNo)}",
                subtitle = listOf(order.date, SearchText.firstLine(order.buyer), SearchText.firstLine(order.supplier)).filter { it.isNotBlank() }.joinToString(" · "),
                onClick = null,
                trailing = {
                    IconButton(onClick = { linked.remove(order) }) {
                        Icon(Icons.Default.Close, contentDescription = "Unlink order ${OrderNumber.display(order.offerNo)}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Link an order") },
            placeholder = { Text("Order no, buyer or supplier", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(resultsView)
        ) {
            results.forEach { order ->
                LinkRow(
                    icon = Icons.Default.Add,
                    title = "Offer ${OrderNumber.display(order.offerNo)}",
                    subtitle = listOf(order.date, SearchText.firstLine(order.buyer), SearchText.firstLine(order.supplier)).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { onLink(order); query = "" }
                )
            }
            if (query.isNotBlank() && searchedFor == query && results.isEmpty()) {
                Text("No matching order", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A bill row in order details: status, balance; tap for the bill's details and payments. */
@Composable
fun BillSummaryRow(bill: Bill, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (onRemove == null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bill ${bill.billNo}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                PaymentStatusPill(bill.status)
            }
            val details = listOfNotNull(
                bill.billDate?.format(billDateFormat),
                bill.amount?.let(::rupees),
                bill.lrNo.takeIf { it.isNotBlank() }?.let { "LR $it" },
                if (bill.orderCount > 1) "covers ${bill.orderCount} orders" else null
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            bill.balance?.takeIf { bill.status != PaymentStatus.UNPAID }?.let {
                Text("Balance ${rupees(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove bill ${bill.billNo}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
