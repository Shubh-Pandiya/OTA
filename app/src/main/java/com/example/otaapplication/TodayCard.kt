package com.example.otaapplication

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otaapplication.reminders.Reminders
import com.example.otaapplication.reminders.Todo
import com.example.otaapplication.reminders.TodoLists
import com.example.otaapplication.reminders.TodoStore
import com.example.otaapplication.ui.icons.DeleteOutline
import com.example.otaapplication.ui.icons.TaskAlt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_TODAY_ROWS = 5
private val dayDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayDate)
}

/** "today" / "yesterday" in a sentence, otherwise "Mon, 16 Nov". */
private fun inlineDay(date: LocalDate, today: LocalDate): String =
    dayLabel(date, today).let { if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(1))) it else it.lowercase(Locale.ENGLISH) }

private fun carriedLabel(todo: Todo, today: LocalDate): String? =
    if (TodoLists.carriedOver(todo, today)) "From ${inlineDay(todo.date, today)}" else null

/**
 * "Today" card at the top of Home: the day's to-dos (unfinished ones carry over), each with an
 * optional reminder that rings as a notification. Stored on this phone only; nothing syncs.
 * [refreshKey] changes whenever the app comes back to the front, so the day and permissions are re-read.
 */
@Composable
fun TodayCard(refreshKey: Int) {
    val context = LocalContext.current
    val store = remember { TodoStore.get(context) }
    val version by store.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(refreshKey, version) { LocalDate.now() }
    val all by produceState<List<Todo>?>(null, version, refreshKey) {
        value = withContext(Dispatchers.IO) { store.all() }
    }
    var canNotify by remember { mutableStateOf(Reminders.canNotify(context)) }
    var onTime by remember { mutableStateOf(Reminders.canRingOnTime(context)) }
    LaunchedEffect(refreshKey) {
        canNotify = Reminders.canNotify(context)
        onTime = Reminders.canRingOnTime(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    fun add(day: LocalDate = today) { editing = Todo(title = "", day = day.toEpochDay()) }
    fun toggle(todo: Todo) { scope.launch(Dispatchers.IO) { Reminders.setDone(context, todo.id, !todo.done) } }
    fun save(todo: Todo) {
        scope.launch(Dispatchers.IO) { Reminders.save(context, todo) }
        // Asked the first time a reminder is set, so the alert can reach the phone's tray.
        if (todo.minute != null && !todo.done && !Reminders.canNotify(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun delete(todo: Todo) { scope.launch(Dispatchers.IO) { Reminders.delete(context, todo.id) } }

    val items = remember(all, today) { all?.let { TodoLists.today(it, today, zone) }.orEmpty() }
    val doneCount = items.count { it.done }
    val openCount = items.size - doneCount
    val alertsWaiting = all?.any(TodoLists::needsAlarm) == true

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(250))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            all == null -> " "
                            items.isEmpty() -> "Plan your day"
                            openCount == 0 -> "All done for today"
                            doneCount == 0 -> "$openCount to do"
                            else -> "$openCount to do · $doneCount done"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showAll = true }) { Text("All") }
                FilledTonalIconButton(onClick = { add() }) { Icon(Icons.Default.Add, contentDescription = "Add to-do") }
            }
            if (items.isNotEmpty()) {
                val progress by animateFloatAsState(doneCount.toFloat() / items.size, tween(450), label = "todayProgress")
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(CircleShape),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(4.dp))
            }
            if (all != null && items.isEmpty()) {
                Text(
                    "Nothing planned. Tap + to add a to-do, or set a time to get a reminder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, end = 8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { add() }
                        .padding(vertical = 8.dp)
                )
            }
            (if (expanded) items else items.take(MAX_TODAY_ROWS)).forEach { todo ->
                key(todo.id) {
                    TodoRow(
                        todo = todo,
                        caption = carriedLabel(todo, today),
                        captionAlert = true,
                        onToggle = { toggle(todo) },
                        onClick = { editing = todo }
                    )
                }
            }
            if (items.size > MAX_TODAY_ROWS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show ${items.size - MAX_TODAY_ROWS} more")
                }
            }
            when {
                alertsWaiting && !canNotify -> PermissionHint("Notifications are off, so reminders can't alert you.", "Turn on") {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
                alertsWaiting && !onTime -> PermissionHint("Reminders may ring a few minutes late.", "Ring on time") {
                    context.startActivity(Reminders.exactAlarmSettings(context))
                }
            }
        }
    }

    editing?.let { todo ->
        TodoEditor(
            initial = todo,
            today = today,
            onDismiss = { editing = null },
            onSave = { save(it); editing = null },
            onDelete = { delete(it); editing = null }
        )
    }
    if (showAll) {
        TodoListDialog(
            all = all.orEmpty(),
            today = today,
            zone = zone,
            onDismiss = { showAll = false },
            onAdd = { add() },
            onEdit = { editing = it },
            onToggle = ::toggle
        )
    }
}

@Composable
private fun TodoRow(todo: Todo, caption: String?, captionAlert: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val fade by animateFloatAsState(if (todo.done) 0.5f else 1f, tween(250), label = "todoFade")
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = todo.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).graphicsLayer { alpha = fade }.padding(vertical = 6.dp)) {
            Text(
                todo.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null
            )
            if (todo.minute != null || caption != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    todo.minute?.let { minute ->
                        val color = if (todo.done || todo.notified) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = color, modifier = Modifier.size(14.dp))
                        Text(TodoLists.timeLabel(minute), style = MaterialTheme.typography.labelMedium, color = color)
                    }
                    if (todo.minute != null && caption != null) Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    caption?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (captionAlert && !todo.done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionHint(text: String, action: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp, bottom = 4.dp)
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

/** Add or edit one to-do: what, which day, and an optional reminder time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoEditor(initial: Todo, today: LocalDate, onDismiss: () -> Unit, onSave: (Todo) -> Unit, onDelete: (Todo) -> Unit) {
    val isNew = initial.id == 0L
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var day by rememberSaveable { mutableLongStateOf(initial.day) }
    var minute by rememberSaveable { mutableStateOf(initial.minute) }
    var done by rememberSaveable { mutableStateOf(initial.done) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew) focus.requestFocus() }

    fun result() = initial.copy(
        title = title.trim(),
        day = day,
        minute = minute,
        done = done,
        doneAt = if (done) initial.doneAt ?: System.currentTimeMillis() else null
    )
    val past = minute?.let { LocalDate.ofEpochDay(day).atTime(it / 60, it % 60).isBefore(LocalDateTime.now()) } == true

    FormDialog(
        title = if (isNew) "New to-do" else "Edit to-do",
        icon = Icons.Default.TaskAlt,
        onDismiss = onDismiss,
        onConfirm = { onSave(result()) },
        confirmEnabled = title.isNotBlank(),
        actions = {
            if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Delete to-do") }
        }
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("What needs doing?") },
            placeholder = { Text("e.g. Call Shyam Garments about payment") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        DateField(label = "Date", day = day, onChange = { it?.let { d -> day = d } }, clearable = false)

        val interaction = remember { MutableInteractionSource() }
        LaunchedEffect(interaction) {
            interaction.interactions.collect { if (it is PressInteraction.Release) pickTime = true }
        }
        OutlinedTextField(
            value = minute?.let(TodoLists::timeLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Remind me at") },
            placeholder = { Text("No reminder") },
            leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (minute != null) {
                { IconButton(onClick = { minute = null }) { Icon(Icons.Default.Close, contentDescription = "Remove reminder") } }
            } else null,
            supportingText = when {
                minute == null -> null
                past -> { { Text("This time has already passed, so no alert will ring.") } }
                else -> { { Text("You'll get a notification at this time.") } }
            },
            isError = past,
            singleLine = true,
            interactionSource = interaction,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Quick reminder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = {
                val at = LocalDateTime.now().plusHours(1)
                val rounded = (at.hour * 60 + at.minute + 4) / 5 * 5
                day = at.toLocalDate().toEpochDay() + rounded / (24 * 60)
                minute = rounded % (24 * 60)
            }, label = { Text("In 1 hour") })
            if (LocalTime.now().isBefore(LocalTime.of(18, 0))) {
                SuggestionChip(onClick = { day = today.toEpochDay(); minute = 18 * 60 }, label = { Text("This evening, 6 pm") })
            }
            SuggestionChip(onClick = { day = today.plusDays(1).toEpochDay(); minute = 10 * 60 }, label = { Text("Tomorrow, 10 am") })
        }
        if (!isNew) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { done = !done },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = done, onCheckedChange = { done = it })
                Text("Done", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (pickTime) {
        TimeDialog(initial = minute, onDismiss = { pickTime = false }, onPick = { minute = it; pickTime = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this to-do?") },
            text = { Text(initial.title) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Int?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = initial ?: ((LocalTime.now().hour + 1) % 24 * 60)
    val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = false)
    var typing by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder time") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (typing) TimeInput(state = state) else TimePicker(state = state)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = {
            Row {
                TextButton(onClick = { typing = !typing }) { Text(if (typing) "Use dial" else "Type time") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Everything in one place: today's list, what's planned for later, and what's been ticked off. */
@Composable
private fun TodoListDialog(
    all: List<Todo>,
    today: LocalDate,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Todo) -> Unit,
    onToggle: (Todo) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lists = remember(all, today) { listOf(TodoLists.today(all, today, zone), TodoLists.upcoming(all, today), TodoLists.done(all)) }
    val labels = listOf("Today", "Upcoming", "Done")
    FormDialog(
        title = "To-dos & reminders",
        icon = Icons.Default.TaskAlt,
        onDismiss = onDismiss,
        onConfirm = null,
        actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add to-do") } }
    ) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            labels.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text("$label · ${lists[i].size}", maxLines = 1) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "todoTab"
        ) { t ->
            val items = lists[t]
            Column(Modifier.fillMaxWidth()) {
                if (items.isEmpty()) {
                    Text(
                        when (t) {
                            0 -> "Nothing for today."
                            1 -> "Nothing planned for later. Pick a future date when adding a to-do."
                            else -> "Ticked to-dos show here for ${TodoLists.KEEP_DONE_DAYS} days."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                items.forEach { todo ->
                    key(todo.id) {
                        TodoRow(
                            todo = todo,
                            caption = when (t) {
                                0 -> carriedLabel(todo, today)
                                1 -> dayLabel(todo.date, today)
                                else -> todo.doneAt?.let { "Done ${inlineDay(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), today)}" }
                            },
                            captionAlert = t == 0,
                            onToggle = { onToggle(todo) },
                            onClick = { onEdit(todo) }
                        )
                    }
                }
            }
        }
    }
}
