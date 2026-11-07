package com.example.otaapplication

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Staff sign-in and catalog sync controls (Sync Settings screen). */
@Composable
fun CloudAccountSection(cloud: CloudSync, modifier: Modifier = Modifier) {
    val status by cloud.status.collectAsState()
    FormSection(title = "Cloud Catalog", icon = Icons.Default.Cloud, modifier = modifier) {
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
                    "Cloud is not set up in this build. Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to local.properties and rebuild (see README).",
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
private fun SignInForm(cloud: CloudSync) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
        Text(
            "Sign in with your staff account to share the buyer and supplier catalog across phones. Order entry works without signing in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
private fun SignedInPanel(cloud: CloudSync, status: CloudStatus) {
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var confirmSignOut by remember { mutableStateOf(false) }

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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
        AnimatedVisibility(visible = status.lastError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            if (status.offline) {
                StatusLine(Icons.Default.CloudOff, "Offline. Changes upload automatically when back online.")
            } else {
                StatusLine(Icons.Default.ErrorOutline, status.lastError.orEmpty(), isError = true)
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
                onClick = { if (status.pending > 0) confirmSignOut = true else scope.launch { cloud.signOut() } },
                modifier = Modifier.height(46.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sign out")
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null) },
            title = { Text("Sign out?") },
            text = { Text("${status.pending} catalog change(s) are not uploaded yet. They stay on this phone and upload after you sign in again.") },
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
        status.lastError != null -> Triple(Icons.Default.ErrorOutline, "Sync failed: ${status.lastError}", true)
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
