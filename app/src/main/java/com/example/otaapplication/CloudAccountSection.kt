package com.example.otaapplication

import android.Manifest
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.cloud.CloudStatus
import com.example.otaapplication.cloud.CloudSync
import com.example.otaapplication.cloud.CloudUsage
import com.example.otaapplication.cloud.StorageAlert
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val NOT_CONFIGURED_MESSAGE =
    "Cloud is not set up in this build, so staff can't sign in. Rebuild from the latest code: supabase.properties holds the keys (see README)."

/** Current time that ticks every [periodMillis], for "synced 3 min ago" labels. */
@Composable
private fun rememberNow(periodMillis: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMillis)
        }
    }
    return now
}

private fun lastSyncText(lastSyncAt: Long?, now: Long): String = when {
    lastSyncAt == null -> "Not synced yet"
    now - lastSyncAt < DateUtils.MINUTE_IN_MILLIS -> "Last synced just now"
    else -> "Last synced " + DateUtils.getRelativeTimeSpanString(lastSyncAt, now, DateUtils.MINUTE_IN_MILLIS)
}

/** Staff sign-in and cloud sync controls (Sync Settings screen). */
@Composable
fun CloudAccountSection(cloud: CloudSync, modifier: Modifier = Modifier) {
    val status by cloud.status.collectAsState()
    FormSection(title = "Cloud Sync", icon = Icons.Default.Cloud, modifier = modifier) {
        AnimatedContent(
            targetState = when {
                !status.configured -> 0
                status.signedIn -> 2
                else -> 1
            },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "cloudAccount"
        ) { state ->
            when (state) {
                0 -> Text(
                    NOT_CONFIGURED_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                1 -> SignInForm(cloud)
                else -> SignedInPanel(cloud, status)
            }
        }
    }
}

@Composable
internal fun SignInForm(
    cloud: CloudSync,
    intro: String? = "Sign in with your staff account to share buyers, suppliers, orders and bills across phones. Signing in once also gives you your own order numbers.",
    initialError: String? = null
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !busy

    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        busy = true
        error = null
        scope.launch {
            try {
                cloud.signIn(email, password)
                password = ""
            } catch (e: Exception) {
                error = CloudSync.friendlyError(e)
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (intro != null) {
            Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        AnimatedVisibility(visible = error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun TestAccountBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Test account. Everything you make here is test data: only this login sees it, and it never counts in real totals or numbers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun SignedInPanel(cloud: CloudSync, status: CloudStatus) {
    val scope = rememberCoroutineScope()
    val staff by cloud.staff.collectAsState()
    val now = rememberNow()
    var confirmSignOut by remember { mutableStateOf(false) }
    val isTest = com.example.otaapplication.data.OtaAccounts.isTest(status.email)
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearResult by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    status.email.orEmpty().take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Signed in as", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status.email.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (isTest) TestAccountBanner()

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val storageAlert = status.storageFull || status.usage?.warning == true
        AnimatedVisibility(visible = storageAlert, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            StorageBanner(full = status.storageFull, usage = status.usage)
        }

        val code = staff?.code
        if (code != null) {
            StatusLine(
                Icons.Default.Tag,
                "Staff code $code. The cloud numbers orders and advices by financial year, e.g. ${OrderNumber.fyPrefix(java.time.LocalDate.now())}0001"
            )
        } else {
            StatusLine(
                Icons.Default.Tag,
                if (status.lastSyncAt == null) "Getting your advice number code…"
                else "This login has no advice number code yet. Ask the owner to add one, then tap Sync now.",
                isError = status.lastSyncAt != null,
                busy = status.lastSyncAt == null
            )
        }
        StatusLine(
            Icons.Default.Schedule,
            lastSyncText(status.lastSyncAt, now),
            busy = status.syncing
        )
        StatusLine(Icons.Default.Autorenew, "Auto-sync on: when the app opens, every 30 s while open, every 15 min in the background")
        StatusLine(
            Icons.Default.CloudUpload,
            if (status.pending == 0) "All changes uploaded" else "${status.pending} change${if (status.pending == 1) "" else "s"} waiting to upload"
        )
        status.usage?.let { usage ->
            StatusLine(Icons.Default.Storage, "Cloud storage: ${usageText(usage)}", isError = usage.warning)
            StatusLine(
                Icons.Default.History,
                "Cloud keeps orders for ${usage.retentionDays} day${if (usage.retentionDays == 1) "" else "s"} (set in the desktop app). " +
                    "Orders on this phone are never removed by it."
            )
        }
        // The storage banner already explains a full cloud.
        AnimatedVisibility(visible = status.lastError != null && !status.storageFull, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            if (status.offline) {
                StatusLine(Icons.Default.CloudOff, "Offline. Changes upload automatically when back online.")
            } else {
                StatusLine(Icons.Default.ErrorOutline, status.lastError.orEmpty(), isError = true)
            }
        }
        AnimatedVisibility(visible = status.notice != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusLine(Icons.Default.Info, status.notice.orEmpty()) }
                TextButton(onClick = cloud::dismissNotice) { Text("OK") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { scope.launch { cloud.syncNow() } },
                enabled = !status.syncing,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                if (status.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
            OutlinedButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier.height(46.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sign out")
            }
        }

        if (isTest) {
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !clearing && !status.syncing,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            ) {
                if (clearing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Clear all my test data")
            }
            clearResult?.let { StatusLine(Icons.Default.Info, it, isError = it.startsWith("Couldn't")) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear all test data?") },
            text = {
                Text(
                    "Deletes every order, bill, payment advice and catalog entry made with the test account, " +
                        "in the cloud and on this phone, including their PDFs. Real business data is not touched. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    clearResult = null
                    scope.launch {
                        clearResult = try {
                            val removed = cloud.clearTestData().values.sum()
                            "Cleared $removed test row${if (removed == 1) "" else "s"} from the cloud and emptied this phone."
                        } catch (e: Exception) {
                            "Couldn't clear test data: ${e.message ?: "unknown error"}"
                        }
                        clearing = false
                    }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null) },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (status.pending > 0) "${status.pending} change(s) are not uploaded yet. They stay on this phone and upload after you sign in again. You need to sign in again to use the app."
                    else "You need to sign in again to use the app. Orders on this phone are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { cloud.signOut() } }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isError: Boolean = false,
    busy: Boolean = false
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        SyncIcon(icon, busy, color, 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun usageText(usage: CloudUsage) =
    String.format(Locale.US, "%.0f MB of %d MB used (%.0f%%)", usage.usedMb, usage.limitMb, usage.percent)

/** Cloud almost full / full: what it means and what to do; offers to turn on alerts. */
@Composable
private fun StorageBanner(full: Boolean, usage: CloudUsage?) {
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(StorageAlert.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    LaunchedEffect(Unit) {
        // Asked the first time the warning appears, so later warnings also reach the phone's tray.
        if (!canNotify) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (full) "Cloud storage full" else "Cloud storage almost full",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                (usage?.let { usageText(it) + ". " }.orEmpty()) + (if (full) StorageAlert.FULL_HINT else StorageAlert.ALMOST_FULL_HINT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!canNotify) {
                TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Turn on storage alerts")
                }
            }
        }
    }
}

/** One-line cloud status shown above the catalog lists; tap opens Sync Settings. */
@Composable
fun CatalogCloudStatusRow(status: CloudStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val (icon, text, isError) = when {
        !status.configured -> Triple(Icons.Default.CloudOff, "Saved on this phone only", false)
        !status.signedIn -> Triple(Icons.Default.CloudOff, "Not signed in. Tap to sign in and sync", false)
        status.offline -> Triple(
            Icons.Default.CloudOff,
            if (status.pending > 0) "Offline. ${status.pending} change(s) will upload when back online" else "Offline. Showing the catalog saved on this phone",
            false
        )
        status.storageFull -> Triple(Icons.Default.Warning, "Cloud storage full. ${status.pending} change(s) kept on this phone. Tap for details", true)
        status.lastError != null -> Triple(Icons.Default.ErrorOutline, "Sync failed: ${status.lastError}", true)
        status.usage?.warning == true -> Triple(Icons.Default.Warning, "Cloud storage almost full. Tap for details", true)
        status.pending > 0 -> Triple(Icons.Default.CloudUpload, "${status.pending} change(s) waiting to upload", false)
        else -> Triple(Icons.Default.CloudDone, lastSyncText(status.lastSyncAt, now), false)
    }
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = status.configured, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        SyncIcon(icon, status.syncing, color, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Status icon that turns into a small spinner while a sync is running. */
@Composable
private fun SyncIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Crossfade(targetState = busy, animationSpec = tween(180), label = "syncIcon") { spinning ->
        if (spinning) {
            CircularProgressIndicator(modifier = Modifier.size(size).padding(2.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size))
        }
    }
}
