package com.example.otaapplication.migration

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.otaapplication.FormSection

private const val WHY = "OTA Business now comes signed with its own release key, which Android treats as a new app. " +
    "Moving installs the new app next to this one and carries everything across: orders, bills, advices, " +
    "to-dos, cheque pictures and settings. All changes upload to the cloud first."

/** Asks to move on start; Later keeps this app working as before. */
@Composable
fun MovePrompt(onMove: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Move to the new OTA Business app") },
        text = { Text("$WHY\n\nIt takes about a minute. Keep the internet on.") },
        confirmButton = { Button(onClick = onMove) { Text("Move now") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } }
    )
}

/** Settings entry for the move. */
@Composable
fun MoveSection(onMove: () -> Unit) {
    FormSection(title = "Move to the new app", icon = Icons.Default.Refresh) {
        Text(WHY, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onMove, modifier = Modifier.fillMaxWidth()) { Text("Move now") }
    }
}

/** Progress of a move, its errors, and the hand-over to the new app. */
@Composable
fun MoveDialog(move: AppMove, onAllowInstalls: () -> Unit) {
    val state by move.state.collectAsState()
    val handOver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        move.handedOver(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(state) {
        (state as? MoveState.HandingOver)?.let {
            try {
                handOver.launch(it.intent)
            } catch (_: ActivityNotFoundException) {
                move.handedOver(false)
            }
        }
    }
    val busy = state is MoveState.Syncing || state is MoveState.Downloading || state is MoveState.Packing ||
        state is MoveState.Installing || state is MoveState.HandingOver
    when (val s = state) {
        MoveState.Idle -> Unit
        MoveState.NeedsPermission -> AlertDialog(
            onDismissRequest = move::dismiss,
            title = { Text("Allow installing the new app") },
            text = { Text("Android asks once: turn on \"Allow from this source\" for OTA Business, then come back and tap Move again.") },
            confirmButton = { Button(onClick = { move.dismiss(); onAllowInstalls() }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = move::dismiss) { Text("Cancel") } }
        )
        is MoveState.Failed -> AlertDialog(
            onDismissRequest = move::dismiss,
            title = { Text("Move not finished") },
            text = { Text(s.message + "\n\nThis app keeps working with all its data.") },
            confirmButton = { Button(onClick = move::dismiss) { Text("OK") } }
        )
        else -> if (busy) AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Moving to the new app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Step("Upload all changes to the cloud", s, MoveState.Syncing)
                    Step("Download and check the new app", s, MoveState.Downloading(null))
                    Step("Pack your data (encrypted)", s, MoveState.Packing)
                    Step("Install the new app — tap Install when Android asks", s, MoveState.Installing)
                    Step("Hand your data to the new app", s, MoveState.HandingOver(android.content.Intent()))
                    (s as? MoveState.Downloading)?.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {}
        )
    }
}

private fun order(s: MoveState) = when (s) {
    MoveState.Syncing -> 0
    is MoveState.Downloading -> 1
    MoveState.Packing -> 2
    MoveState.Installing -> 3
    is MoveState.HandingOver -> 4
    else -> -1
}

@Composable
private fun Step(label: String, current: MoveState, step: MoveState) {
    val done = order(current) > order(step)
    val active = order(current) == order(step)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(if (done) "✓" else "•", color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** All this app shows once its data lives in the new app. */
@Composable
fun MovedScreen(move: AppMove) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Moved to the new app", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your data now lives in the new OTA Business app. This old app no longer syncs or rings reminders.")
            Button(onClick = { move.openNewApp()?.let(context::startActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Open the new app") }
            OutlinedButton(onClick = { context.startActivity(move.uninstallSelfIntent()) }, modifier = Modifier.fillMaxWidth()) { Text("Remove this old app") }
            move.recoveryCode?.let { code ->
                Text("Safety copy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("An encrypted copy is in ${move.backupName ?: "Download/OTA Business"}. If the new app ever needs it, open the new app's sign-in screen › \"Moving from the old app?\" and type this code:")
                Text(code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                Text("Delete that file once you've checked the new app.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
