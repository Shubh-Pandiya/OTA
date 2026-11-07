@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.otaapplication

import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val gstinPattern = Regex("^[0-9A-Z]{15}$")

fun PartyType.icon(): ImageVector = if (this == PartyType.BUYER) Icons.Default.Person else Icons.Default.Store

@Composable
fun CatalogScreen(catalog: CatalogRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(PartyType.BUYER) }
    var query by rememberSaveable { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Party?>(null) }
    var counts by remember { mutableStateOf<Map<PartyType, Int>>(emptyMap()) }

    LaunchedEffect(version) {
        counts = withContext(Dispatchers.IO) { PartyType.entries.associateWith { catalog.count(it) } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                PartyType.entries.forEach { type ->
                    Tab(
                        selected = tab == type,
                        onClick = { tab = type },
                        text = {
                            val n = counts[type]
                            Text(if (n == null) type.pluralLabel else "${type.pluralLabel} ($n)", fontWeight = FontWeight.SemiBold)
                        },
                        icon = { Icon(type.icon(), contentDescription = null) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search name, city, phone, GSTIN or bank") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(260)) { dir * it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(200)) { -dir * it / 4 } + fadeOut(tween(150)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "catalogTab"
            ) { type ->
                PartyList(type = type, query = query, version = version, catalog = catalog, onOpen = { editing = it })
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Party(type = tab, name = "") },
            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
            text = { AnimatedContent(targetState = tab.label, label = "fabLabel") { Text("Add $it") } },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
        )
    }

    editing?.let { party ->
        PartyEditDialog(
            initial = party,
            catalog = catalog,
            onDismiss = { editing = null },
            onSaved = { saved ->
                editing = null
                version++
                Toast.makeText(context, "${saved.type.label} ${if (party.id.isBlank()) "added" else "updated"}", Toast.LENGTH_SHORT).show()
            },
            onDelete = if (party.id.isBlank()) null else {
                {
                    editing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { catalog.delete(party.type, party.id) }
                        version++
                        Toast.makeText(context, "${party.type.label} removed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun PartyList(type: PartyType, query: String, version: Int, catalog: CatalogRepository, onOpen: (Party) -> Unit) {
    var parties by remember(type) { mutableStateOf<List<Party>?>(null) }
    LaunchedEffect(type, query, version) {
        if (parties != null) delay(200)
        parties = withContext(Dispatchers.IO) { catalog.list(type, query) }
    }
    val list = parties
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) { Icon(type.icon(), contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            Text(
                if (query.isBlank()) "No ${type.pluralLabel.lowercase()} yet" else "No matching ${type.pluralLabel.lowercase()}",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (query.isBlank()) "Add them here or straight from the order form. They show up as suggestions while typing an order."
                else "Try a different name, city, phone or bank detail.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues().let {
                PaddingValues(start = 16.dp, end = 16.dp, bottom = it.calculateBottomPadding() + 96.dp)
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.id }) { party ->
                PartyCard(party = party, onClick = { onOpen(party) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun PartyCard(party: Party, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    party.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(party.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val place = listOf(party.address.lineSequence().firstOrNull().orEmpty(), party.city).filter { it.isNotBlank() }.joinToString(", ")
                if (place.isNotBlank()) {
                    Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOf(party.phone, party.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN $it" }.orEmpty()).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) {
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                bankSummary(party.bank)?.let { summary ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccountBalance, contentDescription = "Bank",
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        }
    }
}

/** One-line bank hint for list rows, e.g. "HDFC Bank · A/c ••4321 · HDFC0001234". */
private fun bankSummary(bank: BankDetails): String? {
    if (bank.isEmpty) return null
    val parts = listOfNotNull(
        bank.bankName.takeIf { it.isNotBlank() },
        bank.accountNo.takeIf { it.isNotBlank() }?.let { "A/c ••" + it.takeLast(4) },
        bank.ifsc.takeIf { it.isNotBlank() },
        bank.upi.takeIf { it.isNotBlank() && bank.accountNo.isBlank() }
    )
    return parts.joinToString(" · ").ifBlank { "Bank details saved" }
}

/** Add / edit dialog shared by the catalog screen and the order form's "Add as new" shortcut. */
@Composable
fun PartyEditDialog(
    initial: Party,
    catalog: CatalogRepository,
    onDismiss: () -> Unit,
    onSaved: (Party) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var address by rememberSaveable { mutableStateOf(initial.address) }
    var city by rememberSaveable { mutableStateOf(initial.city) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var gstin by rememberSaveable { mutableStateOf(initial.gstin) }
    var accountName by rememberSaveable { mutableStateOf(initial.bank.accountName) }
    var bankName by rememberSaveable { mutableStateOf(initial.bank.bankName) }
    var accountNo by rememberSaveable { mutableStateOf(initial.bank.accountNo) }
    var ifsc by rememberSaveable { mutableStateOf(initial.bank.ifsc) }
    var branch by rememberSaveable { mutableStateOf(initial.bank.branch) }
    var upi by rememberSaveable { mutableStateOf(initial.bank.upi) }
    var bankExpanded by rememberSaveable { mutableStateOf(!initial.bank.isEmpty) }
    var duplicate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        delay(200)
        duplicate = name.isNotBlank() && withContext(Dispatchers.IO) { catalog.findByName(initial.type, name, initial.id) != null }
    }
    val gstinError = gstin.isNotBlank() && !gstinPattern.matches(gstin.trim().uppercase())
    val bank = BankDetails(accountName, bankName, accountNo, ifsc, branch, upi)
    val ifscError = !BankDetails.isValidIfsc(ifsc)
    val accountNoError = !BankDetails.isValidAccountNo(accountNo)
    val upiError = !BankDetails.isValidUpi(upi)
    val bankError = ifscError || accountNoError || upiError
    // Keep an invalid bank field visible so the disabled Save button is explained.
    LaunchedEffect(bankError) { if (bankError) bankExpanded = true }
    val canSave = name.isNotBlank() && !duplicate && !gstinError && !bankError && !saving
    val isNew = initial.id.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (isNew) Icons.Default.PersonAdd else initial.type.icon(), contentDescription = null) },
        title = { Text(if (isNew) "New ${initial.type.label.lowercase()}" else "Edit ${initial.type.label.lowercase()}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (initial.name.isBlank()) focus.requestFocus() }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) { { Text("A ${initial.type.label.lowercase()} with this name already exists") } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = city, onValueChange = { city = it },
                    label = { Text("City") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gstin, onValueChange = { gstin = it.uppercase() },
                    label = { Text("GSTIN (optional)") },
                    singleLine = true,
                    isError = gstinError,
                    supportingText = if (gstinError) { { Text("GSTIN has 15 letters/digits") } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                BankSection(
                    partyName = name,
                    bank = bank,
                    expanded = bankExpanded,
                    onToggle = { bankExpanded = !bankExpanded },
                    hasError = bankError
                ) {
                    OutlinedTextField(
                        value = accountName, onValueChange = { accountName = it },
                        label = { Text("Account holder name") },
                        placeholder = { Text(name.ifBlank { "Same as name" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bankName, onValueChange = { bankName = it },
                        label = { Text("Bank name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = accountNo, onValueChange = { accountNo = it.uppercase() },
                        label = { Text("Account number") },
                        singleLine = true,
                        isError = accountNoError,
                        supportingText = if (accountNoError) { { Text("Use 6–20 letters/digits") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ifsc, onValueChange = { ifsc = it.uppercase().take(11) },
                        label = { Text("IFSC") },
                        singleLine = true,
                        isError = ifscError,
                        supportingText = if (ifscError) { { Text(if (ifsc.trim().length == 11) "5th character must be 0, e.g. HDFC0001234" else "11 characters, e.g. HDFC0001234") } } else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = branch, onValueChange = { branch = it },
                        label = { Text("Branch") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = upi, onValueChange = { upi = it.trim() },
                        label = { Text("UPI ID") },
                        singleLine = true,
                        isError = upiError,
                        supportingText = if (upiError) { { Text("Looks like name@bank") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove from catalog")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saving = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching { catalog.save(initial.copy(name = name, address = address, city = city, phone = phone, gstin = gstin, bank = bank)) }
                        }
                        saving = false
                        saved.onSuccess(onSaved)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${initial.name}?") },
            text = { Text("It will no longer be suggested on the order form. Existing orders are not changed.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BankSection(
    partyName: String,
    bank: BankDetails,
    expanded: Boolean,
    onToggle: () -> Unit,
    hasError: Boolean,
    fields: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "bankChevron")
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AccountBalance, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("Bank details", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (bank.isEmpty) "Optional" else bankSummary(bank).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedVisibility(visible = !bank.isEmpty, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Bank details", bank.cleaned().toShareText(partyName.trim()))))
                            Toast.makeText(context, "Bank details copied", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy bank details", modifier = Modifier.size(20.dp)) }
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide bank details" else "Show bank details",
                    modifier = Modifier.padding(horizontal = 8.dp).rotate(rotation)
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = fields
                )
            }
        }
    }
}
