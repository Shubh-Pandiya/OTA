package com.ota.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ota.desktop.AppContext
import com.ota.desktop.update.Backups
import com.ota.desktop.update.Release
import com.ota.desktop.update.UpdateState
import com.ota.desktop.update.UpdateStep
import kotlinx.coroutines.launch

/** True while the update owns the window (the database is being closed or already is). */
fun UpdateState.takesOver() = this is UpdateState.Working || (this is UpdateState.Failed && restart)

/** The "new version" prompt and the one-off results (failed check, finished update). */
@Composable
fun UpdateDialog(app: AppContext, onExit: () -> Unit) {
    val state by app.updates.state.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { app.updates.later() },
            containerColor = Color.White,
            title = { Text("OTA Business ${s.release.version} is available") },
            text = {
                Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You have ${app.updates.current}. Updating keeps all your data: unsynced changes are sent to the cloud first (or kept safely on this laptop when offline) and a backup is taken before installing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReleaseNotes(s.release)
                    if (!s.canInstall) Text("Automatic install works on Windows; download it from the release page here.", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (s.canInstall) app.updates.installAsync(s.release, app.updateHost(onExit))
                    else { openUrl(s.release.page); app.updates.later() }
                }) { Text(if (s.canInstall) "Update now" else "Open release page") }
            },
            dismissButton = { TextButton(onClick = { app.updates.later() }) { Text("Later") } }
        )
        is UpdateState.Failed -> if (!s.restart) AlertDialog(
            onDismissRequest = { app.updates.dismiss() },
            containerColor = Color.White,
            title = { Text("Update not installed") },
            text = { Text(s.message, Modifier.width(480.dp)) },
            confirmButton = { TextButton(onClick = { app.updates.dismiss() }) { Text("OK") } }
        )
        is UpdateState.Finished -> AlertDialog(
            onDismissRequest = { app.updates.dismiss() },
            containerColor = Color.White,
            title = { Text(if (s.ok) "Updated to ${app.updates.current}" else "Update not installed") },
            text = { Text(s.message, Modifier.width(480.dp)) },
            confirmButton = { TextButton(onClick = { app.updates.dismiss() }) { Text("OK") } }
        )
        is UpdateState.UpToDate -> if (s.manual) AlertDialog(
            onDismissRequest = { app.updates.dismiss() },
            containerColor = Color.White,
            title = { Text("You're up to date") },
            text = { Text("OTA Business ${app.updates.current} is the latest version.") },
            confirmButton = { TextButton(onClick = { app.updates.dismiss() }) { Text("OK") } }
        )
        else -> Unit
    }
}

@Composable
private fun ReleaseNotes(release: Release) {
    val notes = release.notes.lineSequence()
        .map { it.trim().trimStart('#').trim().replace("**", "").replace("`", "") }
        .map { if (it.startsWith("- ") || it.startsWith("* ")) "•  " + it.drop(2) else it }
        .dropWhile { it.isEmpty() || it.equals("What's new", ignoreCase = true) }
        .joinToString("\n").trim()
    if (notes.isEmpty()) return
    Text("What's new", style = MaterialTheme.typography.titleSmall)
    Box(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)).background(Brand.SaffronSoft.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState()).padding(12.dp)
    ) { Text(notes, style = MaterialTheme.typography.bodyMedium) }
}

/** Fills the window while the update runs: the screens and the local database are closed. */
@Composable
fun UpdatingScreen(state: UpdateState, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp).padding(32.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(12.dp)) { Logo(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(18.dp))
            when (state) {
                is UpdateState.Working -> {
                    Text("Updating to ${state.release.version}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(18.dp))
                    UpdateStep.entries.forEach { step ->
                        val done = step.ordinal < state.step.ordinal
                        val now = step == state.step
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                when {
                                    now -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    done -> Text("✓", color = Brand.Green, fontWeight = FontWeight.Bold)
                                    else -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Brand.Muted.copy(alpha = 0.4f)))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                step.label + if (now && state.progress != null && step == UpdateStep.DOWNLOADING) "  ${(state.progress * 100).toInt()}%" else "",
                                color = if (done || now) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (state.step == UpdateStep.DOWNLOADING && state.progress != null) {
                        LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), trackColor = Brand.SaffronSoft)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), trackColor = Brand.SaffronSoft)
                    Spacer(Modifier.height(12.dp))
                    Text("Please don't turn off the computer. Your data stays in its own folder and is backed up before installing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                is UpdateState.Failed -> {
                    Text("Update not installed", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Banner(state.message, Brand.Red)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onExit) { Text("Close OTA Business") }
                }
                else -> Unit
            }
        }
    }
}

/** Sync & Cloud: version, manual check and the local backups. */
@Composable
fun UpdatesPanel(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val state by app.updates.state.collectAsState()
    Panel(modifier, title = "App version") {
        LabelValue("Installed", "OTA Business ${app.updates.current}")
        LabelValue(
            "Updates", when (val s = state) {
                is UpdateState.Available -> "Version ${s.release.version} is available"
                is UpdateState.UpToDate -> "Up to date"
                is UpdateState.Checking -> "Checking…"
                else -> "Checked on start and every 6 hours"
            }
        )
        val backups = Backups.list(app.updates.backupsDir)
        LabelValue("Backups before updates", if (backups.isEmpty()) "None yet" else "${backups.size} kept · latest ${backups.last().name}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(enabled = state !is UpdateState.Checking && state !is UpdateState.Working, onClick = { scope.launch { app.updates.check(manual = true) } }) {
                Text(if (state is UpdateState.Checking) "Checking…" else "Check for updates")
            }
            OutlinedButton(onClick = { app.updates.backupsDir.mkdirs(); revealFile(app.updates.backupsDir) }) { Text("Open backups") }
        }
    }
}

fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
