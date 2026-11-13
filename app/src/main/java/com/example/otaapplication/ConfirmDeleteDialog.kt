package com.example.otaapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.otaapplication.ui.icons.DeleteForever

/** Synced rows: gone from this phone now, from the cloud and the other devices at the next sync. */
const val DELETED_EVERYWHERE = "It's deleted from this phone now, and from the cloud, the other phones and the laptop at the next sync."

/**
 * The one confirmation every delete goes through: names what goes, says it can't be undone, and
 * offers Cancel or a red Delete. Deletes are permanent everywhere (docs/desktop-sync-contract.md).
 * Without [onConfirm] the delete isn't allowed and [detail] says why; only OK is offered.
 */
@Composable
fun ConfirmDeleteDialog(
    what: String,
    detail: String,
    onDismiss: () -> Unit,
    confirm: String = "Delete",
    onConfirm: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete $what?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.isNotBlank()) Text(detail)
                if (onConfirm != null) {
                    Text("This can't be undone.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (onConfirm != null) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text(confirm) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (onConfirm != null) "Cancel" else "OK") } }
    )
}
