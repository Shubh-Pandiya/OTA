package com.ota.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val gstinPattern = Regex("^[0-9A-Z]{15}$")

@Composable
fun CatalogScreen() {
    val app = LocalApp.current
    var type by rememberSaveable { mutableStateOf(PartyType.BUYER) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Party?>(null) }
    val parties by rememberLoad(emptyList<Party>(), type, query) { app.catalog.list(type, query) }
    val counts by rememberLoad(0 to 0) { app.catalog.count(PartyType.BUYER) to app.catalog.count(PartyType.SUPPLIER) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Buyers & Suppliers", "${counts.first} buyers · ${counts.second} suppliers. Shared with the phones and kept in the cloud.") {
            Button(onClick = { editing = Party(type = type, name = "") }) { IconText(Icons.Default.Add, "Add ${type.label.lowercase()}") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.2f).fillMaxHeight(), padding = PaddingValues(0.dp)) {
                PrimaryTabRow(selectedTabIndex = type.ordinal, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Brand.SaffronDark) {
                    PartyType.entries.forEach { t ->
                        Tab(type == t, { type = t; editing = null }, text = { Text(t.pluralLabel) }, selectedContentColor = Brand.SaffronDark, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.padding(14.dp)) {
                    Field(query, { query = it }, "Search ${type.pluralLabel.lowercase()} by name, city, phone, GSTIN", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, null) })
                }
                TableHeader(listOf(Col("Name", 2f), Col("City", 1f), Col("Phone", 1f), Col("GSTIN", 1.2f)))
                if (parties.isEmpty()) Empty(if (query.isBlank()) "No ${type.pluralLabel.lowercase()} yet" else "Nothing matches \"$query\"")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(parties, key = { it.id }) { p ->
                        TableRow(editing?.id == p.id, { editing = p }) {
                            Cell(p.name, 2f, bold = true)
                            Cell(p.city, 1f)
                            Cell(p.phone, 1f)
                            Cell(p.gstin, 1.2f)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val e = editing
                if (e == null) Panel(Modifier.fillMaxSize()) { Empty("Select an entry to edit it, or add a new one") }
                else androidx.compose.runtime.key(e.id, e.type) { PartyForm(e, onDone = { editing = it }) }
            }
        }
    }
}

@Composable
private fun PartyForm(initial: Party, onDone: (Party?) -> Unit) {
    val app = LocalApp.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var city by remember { mutableStateOf(initial.city) }
    var phone by remember { mutableStateOf(initial.phone) }
    var gstin by remember { mutableStateOf(initial.gstin) }
    var accountName by remember { mutableStateOf(initial.bank.accountName) }
    var bankName by remember { mutableStateOf(initial.bank.bankName) }
    var accountNo by remember { mutableStateOf(initial.bank.accountNo) }
    var ifsc by remember { mutableStateOf(initial.bank.ifsc) }
    var branch by remember { mutableStateOf(initial.bank.branch) }
    var upi by remember { mutableStateOf(initial.bank.upi) }
    var showBank by remember { mutableStateOf(!initial.bank.isEmpty) }
    var duplicate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        delay(200)
        duplicate = name.isNotBlank() && withContext(Dispatchers.IO) { app.catalog.findByName(initial.type, name, initial.id.ifEmpty { null }) != null }
    }
    val gstinError = gstin.isNotBlank() && !gstinPattern.matches(gstin.trim().uppercase())
    val ifscError = !BankDetails.isValidIfsc(ifsc)
    val accountNoError = !BankDetails.isValidAccountNo(accountNo)
    val upiError = !BankDetails.isValidUpi(upi)
    val canSave = name.isNotBlank() && !duplicate && !gstinError && !ifscError && !accountNoError && !upiError && !saving
    val isNew = initial.id.isEmpty()

    Panel(Modifier.fillMaxSize(), title = if (isNew) "New ${initial.type.label.lowercase()}" else "Edit ${initial.type.label.lowercase()}") {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field(name, { name = it }, "Name", Modifier.fillMaxWidth(), error = if (duplicate) "A ${initial.type.label.lowercase()} with this name already exists" else null)
            Field(address, { address = it }, "Address", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(city, { city = it }, "City", Modifier.weight(1f))
                Field(phone, { phone = it }, "Phone", Modifier.weight(1f))
            }
            Field(gstin, { gstin = it.uppercase() }, "GSTIN", Modifier.fillMaxWidth(), error = if (gstinError) "GSTIN has 15 letters/digits" else null)
            TextButton(onClick = { showBank = !showBank }) { Text(if (showBank) "Hide bank details" else "Bank details (optional)") }
            AnimatedVisibility(showBank) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Field(accountName, { accountName = it }, "Account holder name", Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(bankName, { bankName = it }, "Bank name", Modifier.weight(1f))
                        Field(branch, { branch = it }, "Branch", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(accountNo, { accountNo = it.filter(Char::isDigit) }, "Account number", Modifier.weight(1f), error = if (accountNoError) "9 to 18 digits" else null)
                        Field(ifsc, { ifsc = it.uppercase().trim() }, "IFSC", Modifier.weight(1f), error = if (ifscError) "Like SBIN0001234" else null)
                    }
                    Field(upi, { upi = it.trim() }, "UPI ID", Modifier.fillMaxWidth(), error = if (upiError) "Like name@bank" else null)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canSave, onClick = {
                saving = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            app.catalog.save(
                                initial.copy(
                                    name = name.trim(), address = address.trim(), city = city.trim(), phone = phone.trim(), gstin = gstin.trim().uppercase(),
                                    bank = BankDetails(accountName, bankName, accountNo, ifsc, branch, upi).cleaned()
                                )
                            )
                        }
                    }.onSuccess { toast("${it.name} saved"); onDone(it) }.onFailure { toast("Couldn't save: ${it.message}"); saving = false }
                }
            }) { Text("Save") }
            OutlinedButton(onClick = { onDone(null) }) { Text("Close") }
            Spacer(Modifier.weight(1f))
            if (!isNew) TextButton(onClick = { confirmDelete = true }) { IconText(Icons.Default.Delete, "Delete") }
        }
    }
    if (confirmDelete) ConfirmDialog(
        "Delete ${initial.name}?", "It's removed from the catalog here and on the phones. Orders that mention it are not changed.", "Delete", danger = true,
        onDismiss = { confirmDelete = false }
    ) {
        confirmDelete = false
        scope.launch { withContext(Dispatchers.IO) { app.catalog.delete(initial.type, initial.id) }; toast("${initial.name} deleted"); onDone(null) }
    }
}
