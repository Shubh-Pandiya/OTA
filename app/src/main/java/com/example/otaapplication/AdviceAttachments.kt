package com.example.otaapplication

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.otaapplication.data.AdviceImageFiles
import com.example.otaapplication.ui.icons.Image
import com.example.otaapplication.ui.icons.PhotoCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cheque photos / payment screenshots on an advice. They stay on this phone and are added to
 * the advice PDF, one page each, after the advice itself.
 */
@Composable
internal fun AdviceAttachments(
    images: SnapshotStateList<String>,
    fileFor: (String) -> File,
    editable: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AdviceImageFiles.forContext(context) }
    var importing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }

    fun import(uri: Uri?) {
        if (uri == null) return
        importing = true
        scope.launch {
            val id = AdviceImageFiles.newId()
            val ok = withContext(Dispatchers.IO) { runCatching { AdviceImageImport.compress(context, uri, store.staging(id)) }.getOrDefault(false) }
            importing = false
            if (ok) images += id else Toast.makeText(context, "Could not read that picture", Toast.LENGTH_SHORT).show()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { import(it) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile
        if (taken && file != null) import(Uri.fromFile(file))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isEmpty() && !importing) {
            Text(
                if (editable) "Attach the cheque photo or the online payment screenshot. Each picture becomes a page after the advice in the PDF. Pictures stay on this phone."
                else "No pictures attached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (images.isNotEmpty() || importing) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                images.forEachIndexed { index, id ->
                    key(id) {
                        Thumbnail(
                            file = fileFor(id),
                            label = "Page ${index + 1}",
                            onOpen = { viewing = id },
                            onRemove = if (editable) ({ images.remove(id) }) else null
                        )
                    }
                }
                if (importing) {
                    Box(
                        Modifier.size(width = 92.dp, height = 120.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
                }
            }
        }
        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val (file, uri) = AdviceImageImport.cameraTarget(context)
                        cameraFile = file
                        runCatching { camera.launch(uri) }.onFailure { Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show() }
                    },
                    enabled = !importing,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera")
                }
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !importing,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gallery")
                }
            }
        }
    }

    viewing?.let { id -> ImageViewer(fileFor(id), onDismiss = { viewing = null }) }
}

@Composable
private fun Thumbnail(file: File, label: String, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    val edgePx = with(LocalDensity.current) { 240.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { AdviceImageImport.thumbnail(file, edgePx) }.getOrNull() }
    }
    Box(Modifier.size(width = 92.dp, height = 120.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxSize().clickable(onClick = onOpen)
        ) {
            AnimatedVisibility(visible = bitmap != null, enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut() + scaleOut()) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove $label", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ImageViewer(file: File, onDismiss: () -> Unit) {
    val edgePx = with(LocalDensity.current) { 900.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { AdviceImageImport.thumbnail(file, edgePx) }.getOrNull() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Attachment", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp)) }
                ?: CircularProgressIndicator()
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
