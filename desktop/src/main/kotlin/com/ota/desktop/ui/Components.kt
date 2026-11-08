package com.ota.desktop.ui

import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentStatus
import com.example.otaapplication.data.SearchText
import com.ota.desktop.AppContext
import com.ota.desktop.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val LocalApp = staticCompositionLocalOf<AppContext> { error("No AppContext") }
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
val NICE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

fun LocalDate?.dmy(): String = this?.format(DMY).orEmpty()
fun LocalDate?.nice(): String = this?.format(NICE_DAY).orEmpty()
fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), DMY) }.getOrNull()
fun rupees(v: Double?): String = v?.let { "₹" + Money.format(it) } ?: "—"

/**
 * Loads [block] on the IO dispatcher, again whenever [keys] or the app's data revision change.
 * Holds the previous value while reloading so lists don't flash empty.
 */
@Composable
fun <T> rememberLoad(initial: T, vararg keys: Any?, block: suspend () -> T): State<T> {
    val revision by LocalApp.current.revision.collectAsState()
    return produceState(initial, revision, *keys) {
        value = withContext(Dispatchers.IO) { block() }
    }
}

// ------------------------------------------------------------------------------------ layout

@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Brand.Line)
    ) {
        Column(Modifier.padding(padding)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
            content()
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Fixed widths for table columns whose text must never be cut (numbers, dates, amounts). */
val ORDER_NO_W = 118.dp
val ADVICE_NO_W = 138.dp
val BILL_NO_W = 92.dp
val DATE_W = 88.dp
val AMOUNT_W = 108.dp
val STATUS_W = 96.dp

/** Chips or buttons that wrap onto another line when the pane is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, spacing: Dp = 6.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun StatusPill(status: PaymentStatus) = Pill(status.label, Brand.status(status))

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

// ------------------------------------------------------------------------------------ tables

/** A table column: [weight] shares the free width; a fixed [width] suits numbers, dates and status pills. */
class Col(val title: String, val weight: Float, val align: TextAlign = TextAlign.Start, val width: Dp? = null)

fun RowScope.colWidth(weight: Float, width: Dp?): Modifier = if (width != null) Modifier.width(width) else Modifier.weight(weight)

@Composable
fun TableHeader(cols: List<Col>) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        cols.forEach {
            Text(
                it.title, colWidth(it.weight, it.width), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = it.align, maxLines = 1
            )
        }
    }
}

@Composable
fun TableRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        selected -> Brand.SaffronSoft
        hovered -> Brand.SaffronTint
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().background(bg).hoverable(hover).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
    HorizontalDivider(color = Brand.Line)
}

@Composable
fun RowScope.Cell(text: String, weight: Float, align: TextAlign = TextAlign.Start, bold: Boolean = false, color: Color = Color.Unspecified, maxLines: Int = 1, width: Dp? = null) {
    Text(
        text, colWidth(weight, width), textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis, color = color,
        style = MaterialTheme.typography.bodyMedium.merge(Numeric), fontWeight = if (bold) FontWeight.SemiBold else null
    )
}

/** A cell with a main line and a smaller muted line under it (e.g. a bill number and its date). */
@Composable
fun RowScope.Cell2(
    top: String, bottom: String, weight: Float, align: TextAlign = TextAlign.Start, bold: Boolean = false,
    color: Color = Color.Unspecified, width: Dp? = null
) {
    Column(colWidth(weight, width), horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(
            top, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color, textAlign = align,
            style = MaterialTheme.typography.bodyMedium.merge(Numeric), fontWeight = if (bold) FontWeight.SemiBold else null
        )
        if (bottom.isNotEmpty()) Text(
            bottom, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
            style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------------------------ inputs

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    error: String? = null,
    placeholder: String? = null,
    number: Boolean = false,
    enabled: Boolean = true,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 8,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailing,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (password) KeyboardType.Password else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline, unfocusedContainerColor = Color.White, focusedContainerColor = Color.White)
    )
}

/** A dense single-line input for table cells (order items, advice lines). */
@Composable
fun CellField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    align: TextAlign = TextAlign.Start,
    error: Boolean = false,
    singleLine: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        error -> Brand.Red
        focused -> Brand.Saffron
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        interactionSource = interaction,
        cursorBrush = SolidColor(Brand.Saffron),
        textStyle = MaterialTheme.typography.bodyMedium.merge(Numeric).copy(textAlign = align, color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(7.dp)).background(Color.White)
            .border(if (focused) 1.5.dp else 1.dp, border, RoundedCornerShape(7.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth(), textAlign = align)
                }
                inner()
            }
        }
    )
}

/** A dd-mm-yyyy date input; red when the text isn't a valid date. */
@Composable
fun DateField(text: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, optional: Boolean = false) {
    val bad = if (text.isBlank()) !optional else parseDay(text) == null
    Field(text, onChange, label, modifier, error = if (bad && text.isNotBlank()) "Use dd-mm-yyyy" else null, placeholder = "dd-mm-yyyy")
}

/** A drop-down picker. */
@Composable
fun <T> Select(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    text: (T) -> String = { it.toString() },
    dense: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        if (dense) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(7.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)).clickable { open = true }
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text(selected), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(text(selected), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

/**
 * Buyer / supplier input with catalog type-ahead. Picking a suggestion fills the full
 * "name, address" text when [fullText] (order form), or just the name (filters, advices).
 */
@Composable
fun PartyField(
    type: PartyType,
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fullText: Boolean = false,
    minLines: Int = 1,
    error: String? = null
) {
    val app = LocalApp.current
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<Party>()) }
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val query = SearchText.firstLine(value)
    LaunchedEffect(query, focused) {
        if (!focused || query.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(120)
        suggestions = withContext(Dispatchers.IO) { app.catalog.suggest(type, query) }
            .filterNot { it.name.equals(query.trim(), ignoreCase = true) && !fullText }
    }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); dismissedFor = null },
            label = { Text(label) },
            singleLine = minLines == 1,
            minLines = minLines,
            maxLines = if (minLines == 1) 1 else 6,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(10.dp),
            trailingIcon = if (value.isNotEmpty()) {
                { androidx.compose.material3.IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp)) } }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline, unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        )
        DropdownMenu(
            expanded = focused && suggestions.isNotEmpty() && dismissedFor != value,
            onDismissRequest = { dismissedFor = value },
            properties = PopupProperties(focusable = false),
            modifier = Modifier.widthIn(min = 280.dp)
        ) {
            suggestions.forEach { party ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(party.name, fontWeight = FontWeight.SemiBold)
                            val sub = listOf(party.city, party.phone).filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        val text = if (fullText) party.toFieldText() else party.name
                        onChange(text)
                        dismissedFor = text
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------ dialogs

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = if (danger) ButtonDefaults.textButtonColors(contentColor = Brand.Red) else ButtonDefaults.textButtonColors()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color.White
    )
}

@Composable
fun IconText(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text)
}

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }

// ------------------------------------------------------------------------------------ files

/** Native "Save as" dialog; null when cancelled. */
fun chooseSaveFile(title: String, suggested: String, directory: File? = null): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = suggested
    directory?.let { it.mkdirs(); dialog.directory = it.absolutePath }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    var file = File(dialog.directory, name)
    val ext = suggested.substringAfterLast('.', "")
    if (ext.isNotEmpty() && !file.name.endsWith(".$ext", ignoreCase = true)) file = File(file.parentFile, "${file.name}.$ext")
    return file
}

fun openFile(file: File) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file) }
}

fun revealFile(file: File) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(file)
        else desktop.open(file.parentFile)
    }
}
