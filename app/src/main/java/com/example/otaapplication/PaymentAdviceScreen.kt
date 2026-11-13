@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.otaapplication

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import com.example.otaapplication.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.cloud.AdviceLimitReached
import com.example.otaapplication.data.AdviceDraft
import com.example.otaapplication.data.AdviceImageFiles
import com.example.otaapplication.data.AdviceSummary
import com.example.otaapplication.data.AdviceTotals
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.BillListItem
import com.example.otaapplication.data.BillQuery
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.PairBill
import com.example.otaapplication.data.PartyMatch
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PaymentMath
import com.example.otaapplication.data.PaymentMode
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class PaymentTab { ADVICES, BILLS }

/**
 * The Payment advices section (receipts for bill payments) or the Bills section, which share the
 * same detail and editor pages. [openPage] opens a page on arrival (e.g. a bill tapped on Home).
 */
@Composable
fun PaymentAdviceScreen(
    deps: PaymentDeps,
    remoteChanges: Int,
    tab: PaymentTab,
    openPage: PaymentPage? = null,
    onPageOpened: () -> Unit = {}
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var billStatus by rememberSaveable { mutableStateOf<PaymentStatus?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf<List<PaymentPage>>(emptyList()) }
    var advices by remember { mutableStateOf<List<AdviceSummary>?>(null) }
    var bills by remember { mutableStateOf<List<BillListItem>?>(null) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(openPage) {
        if (openPage != null) {
            stack = listOf(openPage)
            onPageOpened()
        }
    }

    LaunchedEffect(searchText, tab, billStatus, version, remoteChanges) {
        delay(200)
        if (tab == PaymentTab.ADVICES) advices = withContext(Dispatchers.IO) { deps.payments.search(searchText) }
        else bills = withContext(Dispatchers.IO) { deps.payments.bills(BillQuery(searchText, billStatus)) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                placeholder = {
                    Text(
                        if (tab == PaymentTab.ADVICES) "Advice no, buyer, supplier, bill, cheque" else "Bill no, LR no, buyer, supplier, order",
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(visible = searchText.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                if (tab == PaymentTab.ADVICES) {
                    AdviceList(advices, filtered = searchText.isNotBlank(), onOpen = { stack = listOf(PaymentPage.AdviceView(it.id)) })
                } else {
                    Column {
                        BillStatusChips(billStatus) { billStatus = it }
                        BillList(bills, filtered = searchText.isNotBlank() || billStatus != null, onOpen = { stack = listOf(PaymentPage.BillView(it.bill.id)) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { stack = listOf(if (tab == PaymentTab.ADVICES) PaymentPage.AdviceEdit(null) else PaymentPage.BillEdit(null)) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(if (tab == PaymentTab.ADVICES) "New advice" else "Add bill") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
        )
    }

    PaymentPages(stack = stack, onStackChange = { stack = it }, deps = deps, version = version + remoteChanges, onChanged = { version++ })
}

@Composable
private fun BillStatusChips(selected: PaymentStatus?, onSelect: (PaymentStatus?) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        (listOf<PaymentStatus?>(null) + PaymentStatus.entries).forEach { status ->
            val isSelected = selected == status
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(status) },
                label = { Text(status?.label ?: "All") },
                leadingIcon = {
                    AnimatedVisibility(visible = isSelected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                colors = colors
            )
        }
    }
}

@Composable
private fun <T> ResultList(
    items: List<T>?,
    key: (T) -> Any,
    empty: @Composable () -> Unit,
    row: @Composable (T, Modifier) -> Unit
) {
    val state = when {
        items == null -> 0
        items.isEmpty() -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        modifier = Modifier.fillMaxSize(),
        label = "results"
    ) { s ->
        when (s) {
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            1 -> empty()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = WindowInsets.navigationBars.asPaddingValues().let {
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = it.calculateBottomPadding() + 96.dp)
                },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items.orEmpty(), key = key) { item -> row(item, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AdviceList(advices: List<AdviceSummary>?, filtered: Boolean, onOpen: (AdviceSummary) -> Unit) {
    ResultList(
        items = advices,
        key = { it.id },
        empty = {
            if (filtered) EmptyState(Icons.Default.SearchOff, "No matching advices", "Try another advice number, party, bill or cheque number.")
            else EmptyState(Icons.Default.Payments, "No payment advices yet", "Tap New advice to record a payment against one or more bills.")
        }
    ) { advice, modifier -> AdviceCard(advice, onClick = { onOpen(advice) }, modifier = modifier) }
}

@Composable
private fun BillList(bills: List<BillListItem>?, filtered: Boolean, onOpen: (BillListItem) -> Unit) {
    ResultList(
        items = bills,
        key = { it.bill.id },
        empty = {
            if (filtered) EmptyState(Icons.Default.SearchOff, "No matching bills", "Try another bill, LR or order number, or another status.")
            else EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "No bills yet", "Bills appear here once they are marked received on an order in Order History.")
        }
    ) { item, modifier -> BillCard(item, onClick = { onOpen(item) }, modifier = modifier) }
}

@Composable
private fun ListCard(onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

@Composable
private fun PartiesLine(buyer: String, supplier: String) {
    Text(
        SearchText.firstLine(buyer).ifEmpty { "—" },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (supplier.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Store, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(SearchText.firstLine(supplier), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AdviceCard(advice: AdviceSummary, onClick: () -> Unit, modifier: Modifier) {
    ListCard(onClick, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(AdviceNumber.display(advice.adviceNo), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rupees(advice.paid), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        PartiesLine(advice.buyer, advice.supplier)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            advice.payDate?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(it.format(billDateFormat), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                listOf(advice.mode.label, advice.reference).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            advice.billNos.forEach { BillChip(it) }
        }
    }
}

@Composable
private fun BillCard(item: BillListItem, onClick: () -> Unit, modifier: Modifier) {
    val bill = item.bill
    ListCard(onClick, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bill ${bill.billNo}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            PaymentStatusPill(bill.status)
        }
        PartiesLine(item.buyer, item.supplier)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    bill.billDate?.format(billDateFormat), bill.lrNo.takeIf { it.isNotBlank() }?.let { "LR $it" },
                    bill.transport.takeIf { it.isNotBlank() }, bill.amount?.let(::rupees)
                )
                    .joinToString(" · ").ifEmpty { "No details yet" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            bill.balance?.let {
                Text("Due ${rupees(it)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Advice detail

@Composable
internal fun AdviceDetailPage(
    adviceId: String,
    deps: PaymentDeps,
    version: Int,
    onBack: () -> Unit,
    onOpen: (PaymentPage) -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    val advice by produceState<PaymentAdvice?>(null, adviceId, version) {
        value = withContext(Dispatchers.IO) { deps.payments.advice(adviceId) }
        loaded = true
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    FormDialog(
        title = advice?.let { AdviceNumber.display(it.adviceNo) } ?: "Payment advice",
        icon = Icons.Default.Payments,
        onDismiss = onBack,
        onConfirm = null,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        actions = {
            if (advice != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Delete advice") }
                IconButton(onClick = { onOpen(PaymentPage.AdviceEdit(adviceId)) }) { Icon(Icons.Default.Edit, contentDescription = "Edit advice") }
            }
        },
        bottomBar = advice?.let { a ->
            {
                val pending = AdviceNumber.isPending(a.adviceNo)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                  Column {
                    if (pending) Text(
                        "This advice gets its number on the next sync; it can be shared after that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    )
                    Button(
                        onClick = {
                            sharing = true
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { runCatching { AdvicePdf.write(context, a) }.getOrNull() }
                                sharing = false
                                if (file == null) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_SHORT).show()
                                else AdvicePdf.share(context, file, a.adviceNo)
                            }
                        },
                        enabled = !sharing && !pending,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(50.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (sharing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (pending) "Share PDF (pending number)" else "Share PDF")
                    }
                  }
                }
            }
        }
    ) {
        val a = advice
        when {
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            a == null -> Text("This advice was deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val totals = a.totals
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Amount paid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rupees(totals.paid), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(a.payDate.format(billDateFormat), a.mode.label, a.reference).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FormSection(title = "Parties", icon = Icons.Default.Groups) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DetailRow("Paid by (buyer)", a.buyer)
                        DetailRow("Paid to (supplier)", a.supplier)
                        DetailRow(a.mode.referenceLabel.substringBefore(" ("), if (a.mode == PaymentMode.CASH && a.reference.isBlank()) "Cash" else a.reference)
                    }
                }
                FormSection(
                    title = "Bills",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    trailing = { Text("${a.lines.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ) {
                    a.lines.forEach { line ->
                        val am = line.amounts
                        LinkRow(
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            title = "Bill ${line.bill.billNo}" + (line.bill.billDate?.let { " · ${it.format(billDateFormat)}" } ?: ""),
                            subtitle = listOfNotNull(
                                "Paid ${rupees(am.paid)}",
                                am.cdAmount?.let { "CD ${rupees(it)}" + (am.cdPercent?.let { p -> " (${PaymentMath.plain(p)}%)" } ?: "") },
                                am.goodsReturn?.let { "GR ${rupees(it)}" },
                                am.rateDiff?.let { "RD ${rupees(it)}" },
                                am.claim?.let { "Claim ${rupees(it)}" + am.claimNote.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" }.orEmpty() },
                                line.bill.balance?.let { "now due ${rupees(it)}" }
                            ).joinToString(" · "),
                            onClick = { onOpen(PaymentPage.BillView(line.bill.id)) }
                        )
                    }
                }
                TotalsSection(totals, a.balanceAmount, a.balanceSide)
                if (a.images.isNotEmpty()) {
                    val store = remember { AdviceImageFiles.forContext(context) }
                    val shown = remember(a.images) { mutableStateListOf<String>().apply { addAll(a.images) } }
                    FormSection(title = "Cheque / payment proof", icon = Icons.Default.Image) {
                        AdviceAttachments(images = shown, fileFor = { store.file(a.id, it) }, editable = false)
                    }
                }
                if (a.remarks.isNotBlank()) {
                    FormSection(title = "Remarks", icon = Icons.Default.RateReview) {
                        Text(a.remarks, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            what = "advice ${advice?.let { AdviceNumber.display(it.adviceNo) }.orEmpty()}".trim(),
            detail = "The advice, its bill lines, photos and PDF are deleted, and its payments come off the bills' balances. " +
                "The advice number is not reused. $DELETED_EVERYWHERE",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) { deps.payments.delete(adviceId) }
                    Toast.makeText(context, "Advice deleted", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }
            }
        )
    }
}

@Composable
private fun TotalsSection(totals: AdviceTotals, balanceAmount: Double?, balanceSide: BalanceSide?) {
    FormSection(title = "Totals", icon = Icons.Default.Calculate) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DetailRow("Amount paid", rupees(totals.paid))
            DetailRow("Cash discount", rupees(totals.cashDiscount))
            DetailRow("Goods return", rupees(totals.goodsReturn))
            DetailRow("Rate difference", rupees(totals.rateDiff))
            if (totals.claim != 0.0) DetailRow("Claim / defect", rupees(totals.claim))
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow("Total adjusted", rupees(totals.settled))
            DetailRow("Balance (Dr/Cr)", balanceAmount?.let { "${rupees(it)} ${balanceSide?.label.orEmpty()}".trim() }.orEmpty())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Advice editor

private class LineState(
    val pair: PairBill,
    paid: String,
    cdPercent: String,
    cdAmount: String,
    goodsReturn: String,
    rateDiff: String,
    claim: String = "",
    claimNote: String = ""
) {
    var paid by mutableStateOf(paid)
    var cdPercent by mutableStateOf(cdPercent)
    var cdAmount by mutableStateOf(cdAmount)
    var goodsReturn by mutableStateOf(goodsReturn)
    var rateDiff by mutableStateOf(rateDiff)
    var claim by mutableStateOf(claim)
    var claimNote by mutableStateOf(claimNote)

    val billId: String get() = pair.bill.id
    val valid: Boolean get() = listOf(paid, cdPercent, cdAmount, goodsReturn, rateDiff, claim).all(::amountValid)

    fun amounts() = LineAmounts(
        paid = PaymentMath.parse(paid) ?: 0.0,
        cdPercent = PaymentMath.parse(cdPercent),
        cdAmount = PaymentMath.parse(cdAmount),
        goodsReturn = PaymentMath.parse(goodsReturn),
        rateDiff = PaymentMath.parse(rateDiff),
        claim = PaymentMath.parse(claim),
        claimNote = if (PaymentMath.parse(claim) == null) "" else claimNote.trim()
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
            pair, PaymentMath.plain(a.paid), PaymentMath.plain(a.cdPercent), PaymentMath.plain(a.cdAmount), PaymentMath.plain(a.goodsReturn),
            PaymentMath.plain(a.rateDiff), PaymentMath.plain(a.claim), a.claimNote
        )
    }
}

@Composable
internal fun AdviceEditorPage(page: PaymentPage.AdviceEdit, deps: PaymentDeps, onCancel: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editingId = page.adviceId
    var ready by remember { mutableStateOf(editingId == null) }
    var adviceNo by remember { mutableStateOf("") }
    var payDay by remember { mutableStateOf<Long?>(LocalDate.now().toEpochDay()) }
    var buyer by remember { mutableStateOf(page.presetBuyer) }
    var supplier by remember { mutableStateOf(page.presetSupplier) }
    var mode by remember { mutableStateOf(PaymentMode.CHEQUE) }
    var reference by remember { mutableStateOf("") }
    var balanceText by remember { mutableStateOf("") }
    var balanceSide by remember { mutableStateOf(BalanceSide.DR) }
    var remarks by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<LineState>() }
    val images = remember { mutableStateListOf<String>() }
    val imageStore = remember { AdviceImageFiles.forContext(context) }
    // Pictures picked but not saved wait in staging; drop them when the editor closes.
    DisposableEffect(Unit) { onDispose { imageStore.clearStaging() } }
    var available by remember { mutableStateOf<List<PairBill>?>(null) }
    var presetPending by remember { mutableStateOf(page.presetBillId) }
    var busy by remember { mutableStateOf(false) }

    // Existing advice: load it once.
    LaunchedEffect(editingId) {
        if (editingId == null) return@LaunchedEffect
        val (a, pairs) = withContext(Dispatchers.IO) {
            val a = deps.payments.advice(editingId)
            a to a?.let { deps.payments.pairBills(it.buyer, it.supplier, editingId) }.orEmpty()
        }
        if (a == null) { onCancel(); return@LaunchedEffect }
        adviceNo = a.adviceNo; payDay = a.payDate.toEpochDay(); buyer = a.buyer; supplier = a.supplier
        mode = a.mode; reference = a.reference; balanceText = PaymentMath.plain(a.balanceAmount); balanceSide = a.balanceSide ?: BalanceSide.DR
        remarks = a.remarks
        images.addAll(a.images)
        val byId = pairs.associateBy { it.bill.id }
        a.lines.forEach { line ->
            val pair = byId[line.bill.id] ?: deps.payments.let { p -> withContext(Dispatchers.IO) { p.pairBill(line.bill.id, editingId) } }
                ?: PairBill(line.bill, PaymentMath.round2(line.bill.settled - line.amounts.settled), mixedParties = false)
            lines += LineState.of(pair, line.amounts)
        }
        ready = true
    }

    // Bills for the chosen buyer → supplier pair.
    LaunchedEffect(buyer, supplier, ready) {
        if (!ready) return@LaunchedEffect
        delay(250)
        available = if (SearchText.partyKey(buyer).isEmpty() || SearchText.partyKey(supplier).isEmpty()) null
        else withContext(Dispatchers.IO) { deps.payments.pairBills(buyer, supplier, editingId) }
        presetPending?.let { billId ->
            val pair = available?.firstOrNull { it.bill.id == billId } ?: withContext(Dispatchers.IO) { deps.payments.pairBill(billId, editingId) }
            if (pair != null && lines.none { it.billId == billId }) lines += LineState.fresh(pair)
            presetPending = null
        }
    }

    // "Search all unpaid bills": every bill with something due, whoever it is for.
    var searchingAll by remember { mutableStateOf(false) }
    var allQuery by remember { mutableStateOf("") }
    var allUnpaid by remember { mutableStateOf<List<PairBill>?>(null) }
    LaunchedEffect(searchingAll) {
        if (searchingAll && allUnpaid == null) allUnpaid = withContext(Dispatchers.IO) { deps.payments.unpaidBills(editingId) }
    }
    // A picked bill whose names don't match this advice's buyer and supplier waits here for the user's choice.
    var confirmPick by remember { mutableStateOf<PairBill?>(null) }
    var lineToRemove by remember { mutableStateOf<LineState?>(null) }
    fun addLine(pair: PairBill) { if (lines.none { it.billId == pair.bill.id }) lines += LineState.fresh(pair) }
    fun pick(pair: PairBill) {
        when {
            pair.buyer.isBlank() || pair.supplier.isBlank() -> addLine(pair)
            buyer.isBlank() && supplier.isBlank() -> { buyer = pair.buyer; supplier = pair.supplier; addLine(pair) }
            PartyMatch.pairLevel(buyer, supplier, pair.buyer, pair.supplier) != PartyMatch.Level.NONE -> addLine(pair)
            else -> confirmPick = pair
        }
    }

    val lineAmounts = lines.map { it.amounts() }
    val totals = AdviceTotals.of(lineAmounts)
    // Outstanding for the pair after this advice: every known bill balance, less what this advice settles.
    val outstanding = available?.let { pairs ->
        val pairIds = pairs.map { it.bill.id }.toSet()
        PaymentMath.round2(pairs.sumOf { it.balanceBefore ?: 0.0 } -
            lines.filter { it.billId in pairIds && it.pair.bill.amount != null }.sumOf { it.amounts().settled })
    }
    val suggestion = outstanding?.let { PaymentMath.suggestedBalance(it) }
    val referenceNeeded = mode != PaymentMode.CASH && reference.isBlank()
    val problems = buildList {
        if (buyer.isBlank() || supplier.isBlank()) add("Pick who paid (buyer) and who was paid (supplier).")
        if (lines.isEmpty()) add("Add at least one bill.")
        if (lines.any { !it.valid } || !amountValid(balanceText)) add("Fix the amounts marked in red.")
        if (lines.any { it.valid && it.amounts().settled <= 0.0 }) add("Each bill needs a paid amount or an adjustment.")
        if (referenceNeeded) add("Enter the ${mode.referenceLabel.lowercase()}.")
    }
    val canSave = ready && problems.isEmpty() && payDay != null

    lineToRemove?.let { line ->
        ConfirmDeleteDialog(
            what = "bill ${line.pair.bill.billNo} from this advice",
            detail = "Its payment line goes when you save the advice; the bill itself stays.",
            confirm = "Remove",
            onDismiss = { lineToRemove = null },
            onConfirm = { lines.remove(line); lineToRemove = null }
        )
    }

    confirmPick?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmPick = null },
            icon = { Icon(Icons.Default.ReportProblem, contentDescription = null) },
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
                    if (lines.isEmpty()) TextButton(onClick = {
                        buyer = pair.buyer; supplier = pair.supplier; addLine(pair); confirmPick = null
                    }) { Text("Use bill's parties") }
                    TextButton(onClick = { addLine(pair); confirmPick = null }) { Text("Add anyway") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmPick = null }) { Text("Cancel") } }
        )
    }

    FormDialog(
        title = if (editingId == null) "New payment advice" else "Edit ${AdviceNumber.display(adviceNo)}",
        icon = Icons.Default.Payments,
        onDismiss = onCancel,
        confirmEnabled = canSave,
        busy = busy,
        onConfirm = {
            val draft = AdviceDraft(
                id = editingId,
                payDate = LocalDate.ofEpochDay(payDay!!),
                buyer = buyer, supplier = supplier,
                mode = mode, reference = reference,
                balanceAmount = PaymentMath.parse(balanceText),
                balanceSide = balanceSide,
                remarks = remarks,
                lines = lines.map { it.billId to it.amounts() },
                images = images.toList()
            )
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val saved = if (editingId == null) {
                            // Numbered by the cloud; offline it is saved pending and numbered on the next sync.
                            val number = try {
                                deps.allocateAdviceNo(draft.payDate)
                            } catch (e: AdviceLimitReached) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AdviceNumber.PENDING
                            }
                            deps.payments.save(draft, number)
                        } else deps.payments.save(draft)
                        // Keep the PDF on the phone in step with the saved advice, like orders. A pending
                        // advice gets its PDF with its number, so nothing is shared under a temporary one.
                        val pdf = if (AdviceNumber.isPending(saved.adviceNo)) null else runCatching { AdvicePdf.write(context, saved) }.getOrNull()
                        saved to pdf
                    }
                }
                busy = false
                result.onSuccess { (saved, pdf) ->
                    if (AdviceNumber.isPending(saved.adviceNo)) {
                        Toast.makeText(context, "Saved offline with a pending number. The advice number and PDF are made on the next sync.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Advice ${AdviceNumber.display(saved.adviceNo)} saved", Toast.LENGTH_SHORT).show()
                        // Generated: straight to the share sheet, like orders.
                        pdf?.let { AdvicePdf.share(context, it, saved.adviceNo) }
                    }
                    onSaved(saved.id)
                }.onFailure { Toast.makeText(context, it.message ?: "Could not save the advice", Toast.LENGTH_LONG).show() }
            }
        }
    ) {
        if (!ready) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@FormDialog }

        FormSection(title = "Payment", icon = Icons.Default.Payments) {
            FormTextField(
                value = if (editingId == null) "" else AdviceNumber.display(adviceNo),
                onValueChange = {},
                label = "Advice number",
                icon = Icons.Default.Tag,
                readOnly = true,
                singleLine = true,
                supportingText = when {
                    editingId == null -> "Given by the cloud when saved (PA-${payDay?.let { OrderNumber.fyPrefix(LocalDate.ofEpochDay(it)) } ?: ""}…). Offline it stays pending until the next sync."
                    AdviceNumber.isPending(adviceNo) -> "Numbered on the next sync."
                    else -> null
                }
            )
            DateField(label = "Payment date", day = payDay, onChange = { payDay = it }, clearable = false)
            PartyField(PartyType.BUYER, buyer, { buyer = it }, "Paid by (buyer)", "Buyer name", deps.catalog, nameOnly = true, imeAction = ImeAction.Next)
            PartyField(PartyType.SUPPLIER, supplier, { supplier = it }, "Paid to (supplier)", "Supplier name", deps.catalog, nameOnly = true, imeAction = ImeAction.Next)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PaymentMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, PaymentMode.entries.size),
                        label = { Text(m.label) }
                    )
                }
            }
            FormTextField(
                value = reference,
                onValueChange = { reference = it },
                label = mode.referenceLabel,
                icon = when (mode) {
                    PaymentMode.CHEQUE -> Icons.Default.Money
                    PaymentMode.ONLINE -> Icons.Default.Language
                    PaymentMode.CASH -> Icons.Default.Payments
                },
                singleLine = true
            )
        }

        FormSection(
            title = "Bills",
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            trailing = { Text("${lines.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ) {
            lines.forEach { line ->
                key(line.billId) {
                    LineEditor(line, partyNote(line.pair, buyer, supplier), onRemove = { lineToRemove = line })
                }
            }
            val addable = available?.filter { p -> lines.none { it.billId == p.bill.id } }
            when {
                available == null -> Text(
                    "Pick the buyer and supplier to see their bills, or search all unpaid bills below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                available!!.isEmpty() -> Text(
                    "No bills found from ${SearchText.firstLine(buyer)} to ${SearchText.firstLine(supplier)}. Names are matched " +
                        "ignoring case, spacing, M/s, Ltd and a city typed after the name. A bill must be entered first: " +
                        "Bills → Add bill, or “Mark bill received” on the order.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                addable!!.isEmpty() -> Text(
                    "All bills for this buyer and supplier are on this advice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text("Add a bill", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    addable.forEach { pair ->
                        key(pair.bill.id) { AddBillRow(pair, partyNote(pair, buyer, supplier), onAdd = { pick(pair) }) }
                    }
                }
            }
            if (!searchingAll) {
                TextButton(onClick = { searchingAll = true }) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (available.isNullOrEmpty()) "Search all unpaid bills" else "Bill not listed? Search all unpaid bills")
                }
            }
            AnimatedVisibility(visible = searchingAll, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                AllUnpaidBills(
                    query = allQuery,
                    onQuery = { allQuery = it },
                    bills = allUnpaid?.filter { p -> lines.none { it.billId == p.bill.id } },
                    note = { partyNote(it, buyer, supplier) },
                    onPick = { pick(it) },
                    onClose = { searchingAll = false; allQuery = "" }
                )
            }
        }

        FormSection(title = "Totals", icon = Icons.Default.Calculate) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DetailRow("Amount paid", rupees(totals.paid))
                DetailRow("Cash discount", rupees(totals.cashDiscount))
                DetailRow("Goods return", rupees(totals.goodsReturn))
                DetailRow("Rate difference", rupees(totals.rateDiff))
                DetailRow("Claim / defect", rupees(totals.claim))
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DetailRow("Total adjusted", rupees(totals.settled))
            }
        }

        FormSection(title = "Balance after payment", icon = Icons.Default.AccountBalance) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AmountField(value = balanceText, onValueChange = { balanceText = it }, label = "Dr/Cr balance (optional)", modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow(Modifier.width(120.dp)) {
                    BalanceSide.entries.forEachIndexed { i, side ->
                        SegmentedButton(
                            selected = balanceSide == side,
                            onClick = { balanceSide = side },
                            shape = SegmentedButtonDefaults.itemShape(i, BalanceSide.entries.size),
                            icon = {},
                            label = { Text(side.label) }
                        )
                    }
                }
            }
            AnimatedVisibility(visible = available != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val hint = suggestion?.let { (amount, side) -> "${rupees(amount)} ${side.label}" } ?: "Nil (all settled)"
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Suggested from this phone's bills and payments for this buyer and supplier. Bills without an amount aren't counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AssistChip(
                        onClick = {
                            balanceText = PaymentMath.plain(suggestion?.first ?: 0.0)
                            balanceSide = suggestion?.second ?: BalanceSide.DR
                        },
                        label = { Text("Use $hint") },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }

        FormSection(
            title = "Cheque / payment proof",
            icon = Icons.Default.Image,
            trailing = { if (images.isNotEmpty()) Text("${images.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ) {
            AdviceAttachments(
                images = images,
                fileFor = { id -> editingId?.let { imageStore.file(it, id) }?.takeIf { it.isFile } ?: imageStore.staging(id) },
                editable = true
            )
        }

        FormSection(title = "Remarks", icon = Icons.Default.RateReview) {
            FormTextField(value = remarks, onValueChange = { remarks = it }, label = "Remarks (optional)", icon = Icons.Default.Notes, minLines = 2, imeAction = ImeAction.Default)
        }

        AnimatedVisibility(visible = problems.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
private fun AllUnpaidBills(
    query: String,
    onQuery: (String) -> Unit,
    bills: List<PairBill>?,
    note: (PairBill) -> PartyNote?,
    onPick: (PairBill) -> Unit,
    onClose: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All unpaid bills", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close bill search") }
        }
        FormTextField(
            value = query,
            onValueChange = onQuery,
            label = "Search bill no., buyer, supplier",
            icon = Icons.Default.Search,
            singleLine = true,
            imeAction = ImeAction.Search
        )
        val tokens = SearchText.tokens(query)
        val shown = bills?.filter { p ->
            val text = SearchText.normalize("${p.bill.billNo} ${p.bill.lrNo} ${p.bill.transport} ${p.buyer} ${p.supplier}")
            tokens.all { it in text }
        }
        when {
            shown == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            shown.isEmpty() -> Text(
                if (query.isBlank()) "No unpaid bills on this phone." else "No unpaid bill matches “$query”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> shown.take(50).forEach { pair -> key(pair.bill.id) { AddBillRow(pair, note(pair), onAdd = { onPick(pair) }) } }
        }
    }
}

@Composable
private fun AddBillRow(pair: PairBill, note: PartyNote?, onAdd: () -> Unit) {
    val bill = pair.bill
    Column {
        LinkRow(
            icon = Icons.Default.AddCircleOutline,
            title = "Bill ${bill.billNo}" + (bill.billDate?.let { " · ${it.format(billDateFormat)}" } ?: ""),
            subtitle = listOfNotNull(
                bill.amount?.let { "Bill ${rupees(it)}" },
                when {
                    pair.balanceBefore == null -> "amount not entered"
                    !pair.due -> "fully paid"
                    pair.settledElsewhere > 0 -> "part paid · due ${rupees(pair.balanceBefore!!)}"
                    else -> "due ${rupees(pair.balanceBefore!!)}"
                },
                if (pair.mixedParties) "orders for other parties too" else null
            ).joinToString(" · "),
            onClick = onAdd
        )
        note?.let { PartyNoteText(it, Modifier.padding(start = 52.dp, bottom = 4.dp)) }
    }
}

@Composable
private fun PartyNoteText(note: PartyNote, modifier: Modifier = Modifier) {
    Text(
        note.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (note.differs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun LineEditor(line: LineState, note: PartyNote?, onRemove: () -> Unit) {
    val bill = line.pair.bill
    val settled = line.amounts().settled
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (note?.differs == true) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bill ${bill.billNo}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            bill.billDate?.format(billDateFormat),
                            bill.amount?.let { "Bill ${rupees(it)}" },
                            line.pair.balanceBefore?.let { if (line.pair.settledElsewhere > 0) "due ${rupees(it)} after earlier advices" else "due ${rupees(it)}" },
                            if (line.pair.mixedParties) "mixed parties" else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    note?.let { PartyNoteText(it) }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove bill ${bill.billNo}") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AmountField(value = line.paid, onValueChange = { line.paid = it }, label = "Paid amount")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(
                        value = line.cdPercent,
                        onValueChange = { text ->
                            line.cdPercent = text
                            PaymentMath.percentOf(bill.amount, PaymentMath.parse(text))?.let { line.cdAmount = PaymentMath.plain(it) }
                        },
                        label = "C.D. %",
                        prefix = "%",
                        modifier = Modifier.weight(1f)
                    )
                    AmountField(value = line.cdAmount, onValueChange = { line.cdAmount = it }, label = "C.D. ₹", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(value = line.goodsReturn, onValueChange = { line.goodsReturn = it }, label = "Goods return", modifier = Modifier.weight(1f))
                    AmountField(value = line.rateDiff, onValueChange = { line.rateDiff = it }, label = "Rate diff.", modifier = Modifier.weight(1f))
                }
                AmountField(
                    value = line.claim,
                    onValueChange = { line.claim = it },
                    label = "Claim / defect deduction",
                    supportingText = "Amount the buyer cut for defective goods"
                )
                AnimatedVisibility(visible = line.claim.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = line.claimNote,
                        onValueChange = { line.claimNote = it },
                        label = { Text("Claim reason") },
                        placeholder = { Text("e.g. 2 than shade variation") },
                        leadingIcon = { Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val after = line.pair.balanceBefore?.let { PaymentMath.round2(it - settled) }
                Text(
                    "Settles ${rupees(settled)}" + (after?.let { " · balance after ${rupees(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (after != null && after < -0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
