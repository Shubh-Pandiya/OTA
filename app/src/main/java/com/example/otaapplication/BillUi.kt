@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.otaapplication

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import com.example.otaapplication.data.OrderRepository
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

/** Editable bill details: date, amount, LR (bilty) number and cash discount (% and ₹). */
class BillFieldsState(
    day: Long? = null,
    amount: String = "",
    lrNo: String = "",
    cdPercent: String = "",
    cdAmount: String = ""
) {
    var day by mutableStateOf(day)
    var amount by mutableStateOf(amount)
    var lrNo by mutableStateOf(lrNo)
    var cdPercent by mutableStateOf(cdPercent)
    var cdAmount by mutableStateOf(cdAmount)

    val valid: Boolean get() = amountValid(amount) && amountValid(cdPercent) && amountValid(cdAmount)

    fun details() = BillDetails(
        billDate = day?.let(LocalDate::ofEpochDay),
        amount = PaymentMath.parse(amount),
        lrNo = lrNo.trim(),
        cdPercent = PaymentMath.parse(cdPercent),
        cdAmount = PaymentMath.parse(cdAmount)
    )

    /** Fills empty fields from an existing bill (used when an existing bill number is picked). */
    fun fillBlanks(bill: Bill) {
        if (day == null) day = bill.billDate?.toEpochDay()
        if (amount.isBlank()) amount = PaymentMath.plain(bill.amount)
        if (lrNo.isBlank()) lrNo = bill.lrNo
        if (cdPercent.isBlank()) cdPercent = PaymentMath.plain(bill.cdPercent)
        if (cdAmount.isBlank()) cdAmount = PaymentMath.plain(bill.cdAmount)
    }

    companion object {
        fun of(bill: Bill) = BillFieldsState(
            bill.billDate?.toEpochDay(), PaymentMath.plain(bill.amount), bill.lrNo, PaymentMath.plain(bill.cdPercent), PaymentMath.plain(bill.cdAmount)
        )
    }
}

@Composable
fun BillFields(state: BillFieldsState, optional: Boolean) {
    val suffix = if (optional) " (optional)" else ""
    DateField(label = "Bill date$suffix", day = state.day, onChange = { state.day = it })
    AmountField(
        value = state.amount,
        onValueChange = { text ->
            state.amount = text
            // Keep a percentage-based discount in step with the amount.
            PaymentMath.percentOf(PaymentMath.parse(text), PaymentMath.parse(state.cdPercent))?.let { state.cdAmount = PaymentMath.plain(it) }
        },
        label = "Bill amount$suffix"
    )
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AmountField(
            value = state.cdPercent,
            onValueChange = { text ->
                state.cdPercent = text
                PaymentMath.percentOf(PaymentMath.parse(state.amount), PaymentMath.parse(text))?.let { state.cdAmount = PaymentMath.plain(it) }
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
    data class BillEdit(val billId: String) : PaymentPage
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
            is PaymentPage.BillView -> BillDetailPage(top.billId, deps, version, onBack = pop, onOpen = push)
            is PaymentPage.BillEdit -> BillEditPage(top.billId, deps, onDone = { changed -> if (changed) onChanged(); pop() })
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
private fun BillDetailPage(billId: String, deps: PaymentDeps, version: Int, onBack: () -> Unit, onOpen: (PaymentPage) -> Unit) {
    val data by produceState<BillPageData?>(null, billId, version) {
        value = withContext(Dispatchers.IO) {
            BillPageData(deps.orders.bill(billId), deps.orders.ordersForBill(billId), deps.payments.advicesForBill(billId))
        }
    }
    val bill = data?.bill
    FormDialog(
        title = bill?.let { "Bill ${it.billNo}" } ?: "Bill",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        onDismiss = onBack,
        onConfirm = null,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        actions = {
            if (bill != null) {
                IconButton(onClick = { onOpen(PaymentPage.BillEdit(billId)) }) { Icon(Icons.Default.Edit, contentDescription = "Edit bill") }
            }
        },
        bottomBar = if (bill != null && data!!.orders.isNotEmpty()) {
            {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                    Button(
                        onClick = {
                            val first = data!!.orders.first()
                            onOpen(PaymentPage.AdviceEdit(null, billId, SearchText.firstLine(first.buyer), SearchText.firstLine(first.supplier)))
                        },
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
                        DetailRow("Bill date", bill.billDate?.format(billDateFormat).orEmpty())
                        DetailRow("Amount", bill.amount?.let(::rupees).orEmpty())
                        DetailRow("LR / Bilty no", bill.lrNo)
                        DetailRow("Cash discount", cashDiscountText(bill.cdPercent, bill.cdAmount))
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
                            title = "Offer ${order.offerNo}",
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
                            a.rateDiff?.let { "RD ${rupees(it)}" }
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

@Composable
private fun BillEditPage(billId: String, deps: PaymentDeps, onDone: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bill by produceState<Bill?>(null, billId) { value = withContext(Dispatchers.IO) { deps.orders.bill(billId) } }
    var state by remember { mutableStateOf<BillFieldsState?>(null) }
    LaunchedEffect(bill) { if (state == null) bill?.let { state = BillFieldsState.of(it) } }
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        title = bill?.let { "Edit bill ${it.billNo}" } ?: "Edit bill",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        onDismiss = { onDone(false) },
        confirmEnabled = state?.valid == true,
        busy = busy,
        onConfirm = {
            val details = state?.details() ?: return@FormDialog
            busy = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { deps.orders.updateBillDetails(billId, details) }.getOrNull() }
                busy = false
                if (saved != null) onDone(true) else Toast.makeText(context, "Could not save the bill", Toast.LENGTH_SHORT).show()
            }
        }
    ) {
        val s = state
        if (s == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            Text(
                "The bill number can't be changed. To fix a wrong number, remove the bill from its orders and add it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BillFields(s, optional = true)
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
