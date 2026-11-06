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
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.filled.*
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
import com.example.otaapplication.ui.theme.OTAApplicationTheme

@Composable
fun OrderFormScreen(
    formData: OrderFormData,
    onFormChange: (OrderFormData) -> Unit,
    onGeneratePdf: (OrderFormData) -> Unit,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    isNewOrder: Boolean = true
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
            }

            FormSection(title = "Party Configurations", icon = Icons.Default.Groups) {
                FormTextField(
                    value = formData.buyer,
                    onValueChange = { onFormChange(formData.copy(buyer = it)) },
                    label = "Buyer Name & Address",
                    placeholder = "Enter Buyer Details",
                    icon = Icons.Default.Person,
                    minLines = 3,
                    imeAction = ImeAction.Default
                )
                FormTextField(
                    value = formData.supplier,
                    onValueChange = { onFormChange(formData.copy(supplier = it)) },
                    label = "Supplier Name & Address",
                    placeholder = "Enter Supplier Details",
                    icon = Icons.Default.Store,
                    minLines = 3,
                    imeAction = ImeAction.Default
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

                FilledTonalButton(
                    onClick = {
                        val newList = formData.items.toMutableList()
                        val newItem = OrderItem(serialNo = (newList.size + 1).toString())
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
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
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        modifier = Modifier.fillMaxWidth(),
        readOnly = readOnly,
        enabled = enabled,
        minLines = minLines,
        supportingText = supportingText?.let { { Text(it) } },
        shape = MaterialTheme.shapes.small,
        colors = formFieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
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
