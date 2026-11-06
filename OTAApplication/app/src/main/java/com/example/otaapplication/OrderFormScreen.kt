package com.example.otaapplication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier
) {
    val bhagvaOrange = Color(0xFFFF6F00)
    val lightBg = Color(0xFFF8F9FA)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = lightBg,
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Button(
                    onClick = { onGeneratePdf(formData) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = bhagvaOrange)
                ) {
                    Text(
                        text = "Generate PDF Order",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader("Document Metadata")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormTextField(
                        value = formData.date,
                        onValueChange = {},
                        label = "Date",
                        icon = Icons.Default.DateRange,
                        readOnly = true,
                        enabled = false
                    )
                    FormTextField(
                        value = formData.offerNo,
                        onValueChange = { onFormChange(formData.copy(offerNo = it)) },
                        label = "Offer No",
                        placeholder = "Enter Offer Number",
                        icon = Icons.Default.Tag
                    )
                }
            }

            SectionHeader("Party Configurations")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }

            SectionHeader("Item Specification Summary")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    formData.items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = item.particulars,
                                onValueChange = { newValue ->
                                    val newList = formData.items.toMutableList()
                                    newList[index] = item.copy(particulars = newValue)
                                    onFormChange(formData.copy(items = newList))
                                },
                                label = { Text("Particulars") },
                                modifier = Modifier.weight(3.5f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = lightBg.copy(alpha = 0.5f),
                                    focusedContainerColor = lightBg.copy(alpha = 0.5f)
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                            )
                            OutlinedTextField(
                                value = item.quantity,
                                onValueChange = { newValue ->
                                    val newList = formData.items.toMutableList()
                                    newList[index] = item.copy(quantity = newValue)
                                    onFormChange(formData.copy(items = newList))
                                },
                                label = { Text("Qty") },
                                modifier = Modifier.weight(1.8f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = lightBg.copy(alpha = 0.5f),
                                    focusedContainerColor = lightBg.copy(alpha = 0.5f)
                                )
                            )
                            OutlinedTextField(
                                value = item.rate,
                                onValueChange = { newValue ->
                                    val newList = formData.items.toMutableList()
                                    newList[index] = item.copy(rate = newValue)
                                    onFormChange(formData.copy(items = newList))
                                },
                                label = { Text("Rate") },
                                modifier = Modifier.weight(1.2f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = lightBg.copy(alpha = 0.5f),
                                    focusedContainerColor = lightBg.copy(alpha = 0.5f)
                                )
                            )

                            if (index > 0) {
                                IconButton(onClick = {
                                    val newList = formData.items.toMutableList()
                                    newList.removeAt(index)
                                    val reindexedList = newList.mapIndexed { i, orderItem ->
                                        orderItem.copy(serialNo = (i + 1).toString())
                                    }
                                    onFormChange(formData.copy(items = reindexedList))
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Row", tint = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Spacer(modifier = Modifier.width(48.dp))
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            val newList = formData.items.toMutableList()
                            newList.add(OrderItem(serialNo = (newList.size + 1).toString()))
                            onFormChange(formData.copy(items = newList))
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = bhagvaOrange)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Row", fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionHeader("Logistics & Billing")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }

            SectionHeader("Remarks")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    FormTextField(
                        value = formData.remarks,
                        onValueChange = { onFormChange(formData.copy(remarks = it)) },
                        label = "Remarks / Terms",
                        icon = Icons.Default.RateReview,
                        minLines = 3,
                        imeAction = ImeAction.Default
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.ExtraBold,
        color = Color.Gray,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

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
    imeAction: ImeAction = ImeAction.Next
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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color(0xFFF8F9FA).copy(alpha = 0.5f),
            focusedContainerColor = Color(0xFFF8F9FA).copy(alpha = 0.5f)
        ),
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
