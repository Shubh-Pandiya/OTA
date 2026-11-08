package com.example.otaapplication

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.otaapplication.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.ui.theme.OTAApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun OrderFormScreen(
    formData: OrderFormData,
    onFormChange: (OrderFormData) -> Unit,
    onGeneratePdf: (OrderFormData) -> Unit,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    isNewOrder: Boolean = true,
    catalog: CatalogRepository? = null,
    /** Why a new order can't get a number yet (no staff code); null when it can. */
    numberMissingMessage: String? = null,
    onOpenSyncSettings: () -> Unit = {}
) {
    // Rows present on first composition appear instantly; rows added later animate in.
    var rowsAnimateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rowsAnimateIn = true }
    var lastAddedKey by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FormSection(title = "Document Metadata", icon = Icons.Default.Description) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Date",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formData.date,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                FormTextField(
                    value = formData.offerNo,
                    onValueChange = {},
                    label = "Offer No",
                    icon = Icons.Default.Tag,
                    readOnly = true,
                    supportingText = if (isNewOrder) "Auto-generated, assigned when the PDF is saved" else "Existing order number"
                )
                AnimatedVisibility(visible = numberMissingMessage != null) {
                    StaffCodeNotice(numberMissingMessage.orEmpty(), onOpenSyncSettings)
                }
            }

            FormSection(title = "Party Configurations", icon = Icons.Default.Groups) {
                PartyField(
                    type = PartyType.BUYER,
                    value = formData.buyer,
                    onValueChange = { onFormChange(formData.copy(buyer = it)) },
                    label = "Buyer Name & Address",
                    placeholder = "Enter Buyer Details",
                    catalog = catalog
                )
                PartyField(
                    type = PartyType.SUPPLIER,
                    value = formData.supplier,
                    onValueChange = { onFormChange(formData.copy(supplier = it)) },
                    label = "Supplier Name & Address",
                    placeholder = "Enter Supplier Details",
                    catalog = catalog
                )
            }

            FormSection(
                title = "Item Specification Summary",
                icon = Icons.Default.Inventory2,
                trailing = {
                    Text(
                        text = "${formData.items.size} item${if (formData.items.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            ) {
                formData.items.forEachIndexed { index, item ->
                    key(item.key) {
                        OrderItemRow(
                            item = item,
                            canDelete = index > 0,
                            animateIn = rowsAnimateIn,
                            autoFocus = item.key == lastAddedKey,
                            onItemChange = { updated ->
                                val newList = formData.items.toMutableList()
                                newList[index] = updated
                                onFormChange(formData.copy(items = newList))
                            },
                            onRemoved = { removeItemByKey(formData, item.key, onFormChange) }
                        )
                    }
                }

                Units.totals(formData.items)?.let { total ->
                    Text(
                        "Total: $total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val newList = formData.items.toMutableList()
                        val newItem = OrderItem(serialNo = (newList.size + 1).toString(), unit = Units.forNewRow(newList))
                        newList.add(newItem)
                        lastAddedKey = newItem.key
                        onFormChange(formData.copy(items = newList))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Row")
                }
            }

            FormSection(title = "Logistics & Billing", icon = Icons.Default.LocalShipping) {
                FormTextField(
                    value = formData.deliveryReady,
                    onValueChange = { onFormChange(formData.copy(deliveryReady = it)) },
                    label = "Delivery Ready / Challan By",
                    icon = Icons.Default.Info
                )
                FormTextField(
                    value = formData.transportTo,
                    onValueChange = { onFormChange(formData.copy(transportTo = it)) },
                    label = "Transport To",
                    icon = Icons.Default.LocalShipping
                )
                FormTextField(
                    value = formData.cashPayment,
                    onValueChange = { onFormChange(formData.copy(cashPayment = it)) },
                    label = "Cash Payment Terms",
                    icon = Icons.Default.Payments
                )
                FormTextField(
                    value = formData.lessDisc,
                    onValueChange = { onFormChange(formData.copy(lessDisc = it)) },
                    label = "Less / Discount",
                    icon = Icons.Default.Discount
                )
                FormTextField(
                    value = formData.side,
                    onValueChange = { onFormChange(formData.copy(side = it)) },
                    label = "Side",
                    icon = Icons.AutoMirrored.Filled.Segment
                )
            }

            FormSection(title = "Remarks", icon = Icons.Default.RateReview) {
                FormTextField(
                    value = formData.remarks,
                    onValueChange = { onFormChange(formData.copy(remarks = it)) },
                    label = "Remarks / Terms",
                    icon = Icons.Default.RateReview,
                    minLines = 3,
                    imeAction = ImeAction.Default
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        GenerateBar(isGenerating = isGenerating, onClick = { onGeneratePdf(formData) })
    }
}

// Same removal rule as before: drop the row, then renumber serials 1..n.
private fun removeItemByKey(formData: OrderFormData, key: Long, onFormChange: (OrderFormData) -> Unit) {
    val newList = formData.items.toMutableList()
    val index = newList.indexOfFirst { it.key == key }
    if (index < 0) return
    newList.removeAt(index)
    val reindexedList = newList.mapIndexed { i, orderItem -> orderItem.copy(serialNo = (i + 1).toString()) }
    onFormChange(formData.copy(items = reindexedList))
}

@Composable
private fun GenerateBar(isGenerating: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 12.dp
    ) {
        Button(
            onClick = onClick,
            enabled = !isGenerating,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(54.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            AnimatedContent(
                targetState = isGenerating,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "generate"
            ) { generating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Generating…", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Generate PDF Order", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderItemRow(
    item: OrderItem,
    canDelete: Boolean,
    animateIn: Boolean,
    autoFocus: Boolean,
    onItemChange: (OrderItem) -> Unit,
    onRemoved: () -> Unit
) {
    val visibleState = remember { MutableTransitionState(!animateIn).apply { targetState = true } }
    val currentOnRemoved by rememberUpdatedState(onRemoved)
    val exitFinished = !visibleState.targetState && visibleState.isIdle
    LaunchedEffect(exitFinished) { if (exitFinished) currentOnRemoved() }

    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(220)) + expandVertically(tween(260), expandFrom = Alignment.Top),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Top)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.serialNo,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Item ${item.serialNo}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (canDelete) {
                        IconButton(onClick = { visibleState.targetState = false }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete Row",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = item.particulars,
                        onValueChange = { onItemChange(item.copy(particulars = it)) },
                        label = { Text("Particulars") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        shape = MaterialTheme.shapes.small,
                        colors = formFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = item.quantity,
                            onValueChange = { onItemChange(item.copy(quantity = it)) },
                            label = { Text("Qty") },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            colors = formFieldColors(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                        )
                        UnitField(unit = item.unit, onUnitChange = { onItemChange(item.copy(unit = it)) })
                        OutlinedTextField(
                            value = item.rate,
                            onValueChange = { onItemChange(item.copy(rate = it)) },
                            label = { Text("Rate") },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            colors = formFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                    }
                }
            }
        }
    }
}

/** Compact unit picker shown next to the quantity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnitField(unit: String, onUnitChange: (String) -> Unit, modifier: Modifier = Modifier.width(104.dp)) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = QtyUnit.ofCode(unit)?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.small,
            colors = formFieldColors()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            QtyUnit.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onUnitChange(option.code); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

/**
 * Name & address field with type-ahead from the buyer/supplier catalog; free typing still works.
 * [nameOnly] makes it a one-line name field (a picked entry fills just the name).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartyField(
    type: PartyType,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    catalog: CatalogRepository?,
    nameOnly: Boolean = false,
    imeAction: ImeAction = ImeAction.Default
) {
    val fill: (Party) -> String = { if (nameOnly) it.name else it.toFieldText() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Party>>(emptyList()) }
    var canAdd by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Party?>(null) }
    var catalogVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(value, focused, catalogVersion) {
        if (!focused || catalog == null || SearchText.firstLine(value).isEmpty()) {
            suggestions = emptyList(); canAdd = false
            return@LaunchedEffect
        }
        delay(150)
        val (found, exists) = withContext(Dispatchers.IO) {
            catalog.suggest(type, value) to (catalog.findByName(type, SearchText.firstLine(value)) != null)
        }
        // Hide suggestions once the field already holds a picked entry.
        suggestions = if (found.any { fill(it) == value.trim() }) emptyList() else found
        canAdd = !exists
    }

    val visible = focused && (suggestions.isNotEmpty() || canAdd)
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(visible) { if (visible) { delay(240); bringIntoView.bringIntoView() } }

    Column {
        FormTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            icon = type.icon(),
            minLines = if (nameOnly) 1 else 3,
            imeAction = imeAction,
            modifier = Modifier.onFocusChanged { focused = it.isFocused }
        )
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(tween(220)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).bringIntoViewRequester(bringIntoView),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    suggestions.forEach { party ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(fill(party))
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(type.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(party.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOf(party.city, party.phone).filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Default.NorthWest, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (canAdd) {
                        if (suggestions.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        val (name, address) = SearchText.splitNameAndAddress(value)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { adding = Party(type = type, name = name, address = address) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Add \u201C$name\u201D as new ${type.label.lowercase()}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    adding?.let { party ->
        if (catalog != null) {
            PartyEditDialog(
                initial = party,
                catalog = catalog,
                onDismiss = { adding = null },
                onSaved = { saved ->
                    adding = null
                    catalogVersion++
                    onValueChange(fill(saved))
                    focusManager.clearFocus()
                    Toast.makeText(context, "${saved.name} added to ${type.pluralLabel.lowercase()}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun FormSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                SectionHeader(title, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun formFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary
)

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    placeholder: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    minLines: Int = 1,
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = false,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        modifier = modifier.fillMaxWidth(),
        readOnly = readOnly,
        enabled = enabled,
        minLines = minLines,
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        shape = MaterialTheme.shapes.small,
        colors = formFieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        )
    )
}

@Preview(showBackground = true)
@Composable
fun OrderFormScreenPreview() {
    OTAApplicationTheme {
        OrderFormScreen(
            formData = OrderFormData(),
            onFormChange = {},
            onGeneratePdf = {}
        )
    }
}

@Composable
private fun StaffCodeNotice(message: String, onOpenSyncSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onOpenSyncSettings, modifier = Modifier.align(Alignment.End)) { Text("Open Sync Settings") }
        }
    }
}
