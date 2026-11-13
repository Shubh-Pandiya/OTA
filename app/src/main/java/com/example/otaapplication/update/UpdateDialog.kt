package com.example.otaapplication.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The update prompt and its progress. [onUpdate] starts the update, [onLater] puts it off for a
 * day, [onAllow] opens Android's "Install unknown apps" page for this app.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onUpdate: (PhoneRelease) -> Unit,
    onLater: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UpdateState.Idle) return
    val busy = state is UpdateState.Syncing || state is UpdateState.Downloading || state is UpdateState.Installing || state is UpdateState.Checking
    AlertDialog(
        onDismissRequest = { if (!busy) (if (state is UpdateState.Available) onLater() else onDismiss()) },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
        icon = {
            Icon(
                when (state) {
                    is UpdateState.Failed -> Icons.Default.Warning
                    UpdateState.UpToDate -> Icons.Default.CheckCircle
                    else -> Icons.Default.Refresh
                },
                contentDescription = null,
                tint = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                when (state) {
                    UpdateState.Checking -> "Checking for updates"
                    UpdateState.UpToDate -> "OTA Business is up to date"
                    is UpdateState.Failed -> "Update didn't finish"
                    is UpdateState.NeedsPermission -> "Allow updates"
                    else -> "Update available"
                }
            )
        },
        text = {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "update"
            ) { s ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (s) {
                        UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        UpdateState.UpToDate -> Text("You have the latest version, $currentVersion.")
                        is UpdateState.Available -> {
                            Text("Version ${s.release.version} is ready. You have $currentVersion.", fontWeight = FontWeight.SemiBold)
                            if (s.release.notes.isNotBlank()) {
                                Text(
                                    releaseNotesText(s.release.notes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                )
                            }
                            Text(
                                "Your orders, bills, advices, to-dos and photos stay on this phone. Changes not yet synced are uploaded first.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is UpdateState.NeedsPermission -> {
                            Text("Android needs your OK once before OTA Business can install its own updates.")
                            Text("On the next screen, turn on \"Allow from this source\", then come back here.", style = MaterialTheme.typography.bodyMedium)
                        }
                        is UpdateState.Syncing -> {
                            Text("Uploading this phone's changes first…")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        is UpdateState.Downloading -> {
                            Text("Downloading version ${s.release.version}…")
                            val p = s.progress
                            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                        is UpdateState.Installing -> {
                            Text("Checked and ready. Confirm the update in Android's installer; the app restarts on the new version.")
                            s.syncNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        is UpdateState.Failed -> {
                            Text(s.message)
                            Text("The app on this phone and all its data are unchanged.", style = MaterialTheme.typography.bodySmall)
                        }
                        UpdateState.Idle -> Unit
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(onClick = { onUpdate(state.release) }) { Text("Update now") }
                is UpdateState.NeedsPermission -> Button(onClick = onAllow) { Text("Open settings") }
                is UpdateState.Failed -> state.release?.let { r -> Button(onClick = { onUpdate(r) }) { Text("Try again") } }
                    ?: TextButton(onClick = onDismiss) { Text("OK") }
                UpdateState.UpToDate, is UpdateState.Installing -> TextButton(onClick = onDismiss) { Text("OK") }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onLater) { Text("Later") }
                is UpdateState.NeedsPermission -> TextButton(onClick = onDismiss) { Text("Not now") }
                is UpdateState.Failed -> if (state.release != null) TextButton(onClick = onDismiss) { Text("Close") }
                else -> Unit
            }
        }
    )
}

/**
 * The release notes are Markdown written for GitHub; show them as plain text with the little
 * formatting they use: **bold**, `code` and "- " bullets. Headings lose their #s, and the signing
 * certificate line CI appends for the admin is left out.
 */
internal fun releaseNotesText(markdown: String): AnnotatedString = buildAnnotatedString {
    val lines = markdown.replace("\r\n", "\n").lines()
        .filterNot { it.trimStart().startsWith("Signing certificate SHA-256") }
        .dropLastWhile { it.isBlank() }
    lines.forEachIndexed { i, raw ->
        val line = raw.trimEnd()
        val heading = Regex("^#{1,6}\\s+").find(line)
        val bullet = Regex("^\\s*[-*]\\s+").find(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(line.substring(heading.range.last + 1)) }
            bullet != null -> { append("•  "); inline(line.substring(bullet.range.last + 1)) }
            else -> inline(line)
        }
        if (i < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.inline(text: String) {
    var i = 0
    while (i < text.length) {
        val bold = text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2
        val code = text[i] == '`' && text.indexOf('`', i + 1) > i + 1
        when {
            bold -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            code -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}
