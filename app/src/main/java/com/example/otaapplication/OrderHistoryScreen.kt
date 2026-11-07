@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.otaapplication

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.BillStatusFilter
import com.example.otaapplication.data.OrderCounts
import com.example.otaapplication.data.OrderQuery
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.OrderSummary
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.theme.BillPendingContainer
import com.example.otaapplication.ui.theme.BillReceivedContainer
import com.example.otaapplication.ui.theme.OnBillPendingContainer
import com.example.otaapplication.ui.theme.OnBillReceivedContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chipDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy")
private val billDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val amountFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
    minimumFractionDigits = 0; maximumFractionDigits = 2
}

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.pickerMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private sealed interface DetailLoad {
    data object Loading : DetailLoad
    data class Loaded(val data: OrderFormData?) : DetailLoad
}

/** Bill number + optional details entered in the attach dialog. */
private data class BillInput(val billNo: String, val date: LocalDate?, val amount: Double?)

@Composable
fun OrderHistoryScreen(
    activity: MainActivity,
    orders: OrderRepository,
    remoteChanges: Int = 0,
    onOrderFocused: (OrderFormData, String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(BillStatusFilter.ALL) }
    var fromDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<OrderSummary>?>(null) }
    var counts by remember { mutableStateOf<OrderCounts?>(null) }
    var indexReady by remember { mutableStateOf(false) }
    var dataVersion by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<OrderSummary?>(null) }
    var attachFor by remember { mutableStateOf<List<String>?>(null) }
    var showDateRange by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val query = OrderQuery(
        text = searchText,
        status = status,
        from = fromDay?.let(LocalDate::ofEpochDay),
        to = toDay?.let(LocalDate::ofEpochDay),
        newestFirst = newestFirst
    )

    // Show what is already indexed straight away, then catch up with the history file.
    LaunchedEffect(Unit) {
        val changed = withContext(Dispatchers.IO) { activity.syncOrderIndex() }
        indexReady = true
        if (changed) dataVersion++
    }
    // Orders or bills changed by a cloud sync while this screen is open.
    var seenRemoteChanges by remember { mutableIntStateOf(remoteChanges) }
    LaunchedEffect(remoteChanges) {
        if (remoteChanges != seenRemoteChanges) {
            seenRemoteChanges = remoteChanges
            dataVersion++
        }
    }
    val lastQuery = remember { arrayOf<OrderQuery?>(null) }
    LaunchedEffect(query, dataVersion) {
        val previous = lastQuery[0]
        if (previous != null && previous.text != query.text) delay(250) // debounce typing
        val (found, totals) = withContext(Dispatchers.IO) { orders.search(query) to orders.counts(query) }
        results = found
        counts = totals
        if (previous != null && previous != query) listState.scrollToItem(0)
        lastQuery[0] = query
    }

    BackHandler(enabled = detail == null && selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = detail != null) { detail = null }

    AnimatedContent(
        targetState = detail,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
            } else {
                fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
            }
        },
        contentKey = { it?.id },
        label = "historyDetail"
    ) { selected ->
        if (selected == null) {
            HistoryList(
                searchText = searchText,
                onSearchChange = { searchText = it },
                status = status,
                onStatusChange = { status = it },
                fromDay = fromDay,
                toDay = toDay,
                onPickDates = { showDateRange = true },
                onClearDates = { fromDay = null; toDay = null },
                newestFirst = newestFirst,
                onToggleSort = { newestFirst = !newestFirst },
                counts = counts,
                results = results.takeUnless { it.isNullOrEmpty() && !indexReady },
                selection = selection,
                onToggleSelect = { id -> selection = if (id in selection) selection - id else selection + id },
                onSelectAll = { selection = selection + results.orEmpty().map { it.id } },
                onClearSelection = { selection = emptySet() },
                onAttachToSelection = { attachFor = selection.toList() },
                onOpen = { detail = it },
                listState = listState
            )
        } else {
            val load by produceState<DetailLoad>(initialValue = DetailLoad.Loading, selected.uuid) {
                value = DetailLoad.Loaded(withContext(Dispatchers.IO) { activity.getOrderFromHistory(selected.uuid) })
            }
            val bills by produceState<List<Bill>?>(initialValue = null, selected.id, dataVersion) {
                value = withContext(Dispatchers.IO) { orders.billsForOrder(selected.id) }
            }
            OrderDetailScreen(
                load = load,
                bills = bills,
                onAddBill = { attachFor = listOf(selected.id) },
                onRemoveBill = { bill ->
                    scope.launch {
                        withContext(Dispatchers.IO) { orders.detachBill(selected.id, bill.id) }
                        Toast.makeText(activity, "Bill ${bill.billNo} removed from this order", Toast.LENGTH_SHORT).show()
                        dataVersion++
                    }
                },
                onDismiss = { detail = null },
                onDelete = {
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            activity.deleteOrderPermanently(selected.uuid, selected.jsonPath, selected.pdfFileName)
                                .also { activity.syncOrderIndex() }
                        }
                        if (success) {
                            Toast.makeText(activity, "Data Erased Successfully", Toast.LENGTH_SHORT).show()
                            dataVersion++
                            detail = null
                        } else {
                            Toast.makeText(activity, "Error deleting files", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFocus = { data -> onOrderFocused(data, selected.uuid); detail = null }
            )
        }
    }

    attachFor?.let { targets ->
        AttachBillDialog(
            targets = targets,
            orders = orders,
            onDismiss = { attachFor = null },
            onConfirm = { input ->
                attachFor = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { orders.attachBill(targets, input.billNo, input.date, input.amount) }
                    }
                    result.onSuccess { bill ->
                        val what = if (targets.size == 1) "order" else "${targets.size} orders"
                        Toast.makeText(activity, "Bill ${bill.billNo} attached to $what", Toast.LENGTH_SHORT).show()
                        selection = emptySet()
                        dataVersion++
                    }.onFailure {
                        Toast.makeText(activity, it.message ?: "Could not save bill", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showDateRange) {
        DateRangeDialog(
            from = fromDay?.let(LocalDate::ofEpochDay),
            to = toDay?.let(LocalDate::ofEpochDay),
            onDismiss = { showDateRange = false },
            onConfirm = { from, to ->
                fromDay = from?.toEpochDay(); toDay = (to ?: from)?.toEpochDay(); showDateRange = false
            }
        )
    }
}

@Composable
private fun HistoryList(
    searchText: String,
    onSearchChange: (String) -> Unit,
    status: BillStatusFilter,
    onStatusChange: (BillStatusFilter) -> Unit,
    fromDay: Long?,
    toDay: Long?,
    onPickDates: () -> Unit,
    onClearDates: () -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
    counts: OrderCounts?,
    results: List<OrderSummary>?,
    selection: Set<String>,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAttachToSelection: () -> Unit,
    onOpen: (OrderSummary) -> Unit,
    listState: LazyListState
) {
    val selecting = selection.isNotEmpty()
    val focusManager = LocalFocusManager.current
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "historyHeader"
            ) { isSelecting ->
                if (isSelecting) {
                    SelectionHeader(count = selection.size, onClose = onClearSelection, onSelectAll = onSelectAll)
                } else {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                        placeholder = { Text("Order no, buyer, supplier, item, bill no", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            AnimatedVisibility(visible = searchText.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
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
                }
            }
            FilterRow(status = status, onStatusChange = onStatusChange, counts = counts)
            val shown = results?.size
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (shown == null) "Loading…" else "$shown order${if (shown == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (selecting) "Tap to add or remove" else "Long-press to select",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DateAndSortControls(
                    fromDay = fromDay,
                    toDay = toDay,
                    onPickDates = onPickDates,
                    onClearDates = onClearDates,
                    newestFirst = newestFirst,
                    onToggleSort = onToggleSort
                )
            }

            val state = when {
                results == null -> 0
                results.isEmpty() -> 1
                else -> 2
            }
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.fillMaxSize(),
                label = "historyState"
            ) { s ->
                when (s) {
                    0 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    1 -> EmptyHistory(filtered = searchText.isNotBlank() || status != BillStatusFilter.ALL || fromDay != null)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = WindowInsets.navigationBars.asPaddingValues().let {
                            PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = it.calculateBottomPadding() + if (selecting) 96.dp else 16.dp)
                        },
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(results.orEmpty(), key = { it.id }) { item ->
                            HistoryCard(
                                item = item,
                                selecting = selecting,
                                selected = item.id in selection,
                                onClick = { if (selecting) onToggleSelect(item.id) else onOpen(item) },
                                onLongClick = { onToggleSelect(item.id) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150))
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                Button(
                    onClick = onAttachToSelection,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Attach bill to ${selection.size} order${if (selection.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
            AnimatedContent(
                targetState = count,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                modifier = Modifier.weight(1f),
                label = "selectionCount"
            ) { n ->
                Text("$n selected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            TextButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Select shown")
            }
        }
    }
}

@Composable
private fun historyChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun FilterRow(
    status: BillStatusFilter,
    onStatusChange: (BillStatusFilter) -> Unit,
    counts: OrderCounts?
) {
    val chipColors = historyChipColors()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val total = counts?.total
        val pending = counts?.pending
        val received = if (total != null && pending != null) total - pending else null
        listOf(
            Triple(BillStatusFilter.ALL, "All", total),
            Triple(BillStatusFilter.PENDING, "Pending", pending),
            Triple(BillStatusFilter.RECEIVED, "Bill received", received)
        ).forEach { (filter, label, count) ->
            val selected = status == filter
            FilterChip(
                selected = selected,
                onClick = { onStatusChange(filter) },
                label = { Text(if (count == null) label else "$label · $count") },
                leadingIcon = {
                    AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                colors = chipColors
            )
        }
    }
}

@Composable
private fun DateAndSortControls(
    fromDay: Long?,
    toDay: Long?,
    onPickDates: () -> Unit,
    onClearDates: () -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit
) {
    val chipColors = historyChipColors()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val hasRange = fromDay != null
        FilterChip(
            selected = hasRange,
            onClick = onPickDates,
            label = {
                Text(
                    if (!hasRange) "Any date" else {
                        val from = LocalDate.ofEpochDay(fromDay!!)
                        val to = LocalDate.ofEpochDay(toDay ?: fromDay)
                        if (from == to) from.format(chipDateFormat) else "${from.format(chipDateFormat)} – ${to.format(chipDateFormat)}"
                    }
                )
            },
            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (hasRange) {
                { Icon(Icons.Default.Close, contentDescription = "Clear dates", modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onClearDates)) }
            } else null,
            colors = chipColors
        )
        val arrowRotation by animateFloatAsState(if (newestFirst) 0f else 180f, tween(250), label = "sortArrow")
        FilterChip(
            selected = false,
            onClick = onToggleSort,
            label = {
                AnimatedContent(
                    targetState = newestFirst,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "sortLabel"
                ) { newest -> Text(text = if (newest) "Newest" else "Oldest") }
            },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp).rotate(arrowRotation)) }
        )
    }
}

@Composable
private fun EmptyHistory(filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (filtered) Icons.Default.SearchOff else Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = if (filtered) "No matching orders" else "No Previous Orders Found",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (filtered) "Try another order number, party name, item or bill number, or clear the filters."
            else "Your booked customer invoices will be securely tracked and logged here chronologically.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BillStatusBadge(received: Boolean, modifier: Modifier = Modifier) {
    val container by animateColorAsState(if (received) BillReceivedContainer else BillPendingContainer, tween(250), label = "badgeBg")
    val content by animateColorAsState(if (received) OnBillReceivedContainer else OnBillPendingContainer, tween(250), label = "badgeFg")
    Row(
        modifier = modifier.clip(CircleShape).background(container).padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (received) Icons.Default.TaskAlt else Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = content
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (received) "Bill received" else "Pending",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
    }
}

@Composable
private fun BillChip(billNo: String) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(billNo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HistoryCard(
    item: OrderSummary,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        tween(200), label = "cardBg"
    )
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        tween(200), label = "cardBorder"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Order ${item.offerNo}" },
        shape = MaterialTheme.shapes.large,
        color = container,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(100)) },
                label = "avatar"
            ) { isSelecting ->
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (isSelecting) {
                        Checkbox(checked = selected, onCheckedChange = { onClick() })
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.buyer.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Offer No: ${item.offerNo}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    BillStatusBadge(received = item.billReceived)
                }
                Text(
                    text = SearchText.firstLine(item.buyer).ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val supplier = SearchText.firstLine(item.supplier)
                if (supplier.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Store, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = supplier,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = item.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    item.billNos.forEach { BillChip(it) }
                }
            }
        }
    }
}

@Composable
private fun OrderDetailScreen(
    load: DetailLoad,
    bills: List<Bill>?,
    onAddBill: () -> Unit,
    onRemoveBill: (Bill) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onFocus: (OrderFormData) -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var billToRemove by remember { mutableStateOf<Bill?>(null) }
    val order = (load as? DetailLoad.Loaded)?.data

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Order Details", style = MaterialTheme.typography.titleLarge)
                if (order != null) {
                    Text(text = "Offer No ${order.offerNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bills != null) BillStatusBadge(received = bills.isNotEmpty())
        }

        when {
            load is DetailLoad.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            order == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Order data not found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DetailCard("General Information", Icons.Default.Description) {
                        DetailRow("Date", order.date); DetailRow("Offer No", order.offerNo); DetailRow("Buyer", order.buyer); DetailRow("Supplier", order.supplier)
                    }
                    BillsCard(bills = bills, onAddBill = onAddBill, onRemoveBill = { billToRemove = it })
                    DetailCard("Items List", Icons.Default.Inventory2) {
                        order.items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item.serialNo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = item.particulars, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(text = "Qty: ${item.quantity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = "Rate: ${item.rate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    DetailCard("Terms & Logistics", Icons.Default.LocalShipping) {
                        DetailRow("Delivery", order.deliveryReady); DetailRow("Transport", order.transportTo); DetailRow("Payment", order.cashPayment); DetailRow("Less/Disc", order.lessDisc); DetailRow("Side", order.side)
                    }
                    DetailCard("Remarks", Icons.Default.RateReview) {
                        Text(
                            text = order.remarks.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (order.remarks.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Delete")
                        }
                        Button(onClick = { onFocus(order) }, modifier = Modifier.weight(1f).height(50.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Reuse / Edit")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation && order != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Permanently Delete?") },
            text = { Text("Are you absolutely sure you want to permanently delete Offer No ${order.offerNo}? This action will completely erase the sync record from your system storage.") },
            confirmButton = { TextButton(onClick = { showDeleteConfirmation = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("PERMANENTLY DELETE") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("CANCEL") } }
        )
    }

    billToRemove?.let { bill ->
        AlertDialog(
            onDismissRequest = { billToRemove = null },
            icon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
            title = { Text("Remove bill ${bill.billNo}?") },
            text = {
                Text(
                    if (bill.orderCount > 1) "The bill stays attached to ${bill.orderCount - 1} other order${if (bill.orderCount == 2) "" else "s"}; only this order is unlinked."
                    else "This order will go back to Pending if it has no other bills."
                )
            },
            confirmButton = { TextButton(onClick = { billToRemove = null; onRemoveBill(bill) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { billToRemove = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BillsCard(bills: List<Bill>?, onAddBill: () -> Unit, onRemoveBill: (Bill) -> Unit) {
    FormSection(
        title = "Bills",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        trailing = { if (bills != null) Text("${bills.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ) {
        AnimatedContent(
            targetState = bills,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            contentKey = { list -> list?.joinToString { it.id } },
            label = "bills"
        ) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    list == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(
                        "No bill received yet. Add the bill number once the bill arrives.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> list.forEach { bill -> BillRow(bill = bill, onRemove = { onRemoveBill(bill) }) }
                }
            }
        }
        FilledTonalButton(onClick = onAddBill, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (bills.isNullOrEmpty()) "Mark bill received" else "Add another bill")
        }
    }
}

@Composable
private fun BillRow(bill: Bill, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(BillReceivedContainer.copy(alpha = 0.55f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.TaskAlt, contentDescription = null, tint = OnBillReceivedContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Bill ${bill.billNo}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            val details = listOfNotNull(
                bill.billDate?.format(billDateFormat),
                bill.amount?.let { "₹ ${amountFormat.format(it)}" },
                if (bill.orderCount > 1) "covers ${bill.orderCount} orders" else null
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove bill ${bill.billNo}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachBillDialog(
    targets: List<String>,
    orders: OrderRepository,
    onDismiss: () -> Unit,
    onConfirm: (BillInput) -> Unit
) {
    var billNo by rememberSaveable { mutableStateOf("") }
    var billDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<List<Bill>>(emptyList()) }
    val addingAnother by produceState(false, targets) {
        if (targets.size == 1) value = withContext(Dispatchers.IO) { orders.billsForOrder(targets.first()).isNotEmpty() }
    }

    LaunchedEffect(billNo) {
        delay(150)
        existing = withContext(Dispatchers.IO) { orders.suggestBills(billNo, targets) }
            .filterNot { SearchText.billKey(it.billNo) == SearchText.billKey(billNo) }
    }
    val amount = amountText.replace(",", "").trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val amountError = amountText.isNotBlank() && amount == null
    val canSave = billNo.isNotBlank() && !amountError

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
        title = {
            Text(
                when {
                    targets.size > 1 -> "Attach bill to ${targets.size} orders"
                    addingAnother -> "Add another bill"
                    else -> "Mark bill received"
                }
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = billNo,
                    onValueChange = { billNo = it },
                    label = { Text("Bill number") },
                    leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    supportingText = { Text("Same number on several orders = one bill covering them") }
                )
                AnimatedVisibility(visible = existing.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Existing bills", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            existing.forEach { bill ->
                                SuggestionChip(
                                    onClick = {
                                        billNo = bill.billNo
                                        if (billDay == null) billDay = bill.billDate?.toEpochDay()
                                        if (amountText.isBlank()) amountText = bill.amount?.let { amountFormat.format(it).replace(",", "") }.orEmpty()
                                    },
                                    label = { Text("${bill.billNo} · ${bill.orderCount} order${if (bill.orderCount == 1) "" else "s"}") },
                                    icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
                val dateInteraction = remember { MutableInteractionSource() }
                LaunchedEffect(dateInteraction) {
                    dateInteraction.interactions.collect { if (it is PressInteraction.Release) showDatePicker = true }
                }
                OutlinedTextField(
                    value = billDay?.let { LocalDate.ofEpochDay(it).format(billDateFormat) }.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Bill date (optional)") },
                    leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if (billDay != null) {
                        { IconButton(onClick = { billDay = null }) { Icon(Icons.Default.Close, contentDescription = "Clear bill date") } }
                    } else null,
                    singleLine = true,
                    interactionSource = dateInteraction,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (optional)") },
                    leadingIcon = { Text("₹", style = MaterialTheme.typography.titleMedium) },
                    singleLine = true,
                    isError = amountError,
                    supportingText = if (amountError) { { Text("Enter a number") } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(BillInput(billNo, billDay?.let(LocalDate::ofEpochDay), amount)) },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (billDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()).toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { billDay = it.pickerMillisToDate().toEpochDay() }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun DateRangeDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate?, LocalDate?) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = from?.toPickerMillis(),
        initialSelectedEndDateMillis = to?.toPickerMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.selectedStartDateMillis?.pickerMillisToDate(), state.selectedEndDateMillis?.pickerMillisToDate()) },
                enabled = state.selectedStartDateMillis != null
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text("Order dates", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = false
        )
    }
}

@Composable
private fun DetailCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    FormSection(title = title, icon = icon) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (value.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}
