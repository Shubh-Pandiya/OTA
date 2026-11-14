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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ota.desktop.AppContext
import com.ota.desktop.backup.AutoBackup
import com.ota.desktop.backup.BackupArchive
import com.ota.desktop.backup.Restorer
import com.ota.desktop.security.PinHash
import com.ota.desktop.security.PinLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.AWTEventListener
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

private val whenFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

private fun formatTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(whenFormat)

private fun muted(): Color = Brand.Muted

// ------------------------------------------------------------------------------------ backups

/** Sync & Cloud → Backups: where the automatic backups go, how many are kept, the password, restore. */
@Composable
fun BackupPanel(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val status by app.backups.status.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val password = remember(changingPassword) { app.backups.password }

    fun backupNow() = app.scope.launch {
        message = try {
            val f = app.backups.backupNow()
            "Backed up to ${f.name}"
        } catch (e: Throwable) {
            null // shown from the status
        }
    }

    Panel(modifier, title = "Backups") {
        Text(
            "Automatic, encrypted copies of everything on this laptop (orders, bills, advices, the Accounts books and test data): " +
                "every day and when you close the app, if anything changed. Pick a USB drive, or a Google Drive / OneDrive folder to keep them off this laptop.",
            style = MaterialTheme.typography.bodySmall, color = muted()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                LabelValue(
                    "Last backup",
                    when {
                        status.running -> "Backing up now…"
                        status.lastAt != null -> formatTime(status.lastAt!!) + (status.lastFile?.let { " · ${File(it).name}" } ?: "")
                        else -> "None yet"
                    }
                )
                LabelValue("Folder", status.folder.path, valueColor = if (status.folder.isDirectory || status.folder == app.backups.defaultFolder) Color.Unspecified else Brand.Red)
            }
            Select(
                "Keep", (listOf(7, 14, 30, 60) + status.keep).distinct().sorted(), status.keep,
                { app.backups.setKeep(it) }, Modifier.width(150.dp), text = { "Last $it backups" }
            )
        }
        status.error?.let { Banner("Last backup failed: $it", Brand.Red, Modifier.padding(top = 8.dp)) }
        message?.takeIf { status.error == null && !status.running }?.let { Banner(it, Brand.Green, Modifier.padding(top = 8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Button(enabled = !status.running, onClick = { message = null; backupNow() }) {
                if (status.running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (status.running) "Backing up…" else "Back up now")
            }
            OutlinedButton(enabled = !status.running, onClick = { restoring = true }) { Text("Restore…") }
            OutlinedButton(onClick = {
                chooseFolder("Choose the backup folder", status.folder.takeIf { it.isDirectory })?.let { dir ->
                    // A first backup right away shows the folder works (or why not).
                    app.backups.setFolder(dir)
                    message = null
                    backupNow()
                }
            }) { Text("Change folder…") }
            TextButton(onClick = { status.folder.takeIf { it.isDirectory || it == app.backups.defaultFolder }?.let { it.mkdirs(); openFile(it) } }) { Text("Open folder") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Backup password", style = MaterialTheme.typography.labelMedium, color = muted())
                Text(
                    if (showPassword) password else password.map { if (it == '-') '-' else '•' }.joinToString(""),
                    style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace
                )
            }
            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
            TextButton(onClick = { copyText(password); message = "Backup password copied" }) { Text("Copy") }
            TextButton(onClick = { changingPassword = true }) { Text("Change…") }
        }
        Text(
            "Write this password down and keep it somewhere safe. It opens these backups on another laptop, or with 7-Zip if " +
                "the app can't be installed. Without it, nobody can open them, not even us.",
            style = MaterialTheme.typography.bodySmall, color = muted()
        )
    }
    if (changingPassword) ChangeBackupPasswordDialog(app.backups) { changingPassword = false }
    if (restoring) RestoreFlow(app) { restoring = false }
}

@Composable
private fun ChangeBackupPasswordDialog(backups: AutoBackup, onDone: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val error = when {
        first.isNotEmpty() && first.length < AutoBackup.MIN_PASSWORD -> "Use at least ${AutoBackup.MIN_PASSWORD} characters"
        second.isNotEmpty() && first != second -> "The two don't match"
        else -> null
    }
    val ok = first.length >= AutoBackup.MIN_PASSWORD && first == second
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Change the backup password") },
        text = {
            Column(Modifier.width(420.dp)) {
                Text(
                    "New backups use the new password. Backups already made keep their old one; this laptop remembers it for restores. " +
                        "Write the new password down.",
                    style = MaterialTheme.typography.bodySmall, color = muted()
                )
                Spacer(Modifier.height(10.dp))
                Field(first, { first = it }, "New password", Modifier.fillMaxWidth(), password = true)
                Spacer(Modifier.height(6.dp))
                Field(second, { second = it }, "Type it again", Modifier.fillMaxWidth(), password = true, error = error)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { backups.changePassword(first); onDone() }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        containerColor = Color.White
    )
}

/** Restore…: pick a file → (password) → confirm → safety backup → replace → reopen. */
@Composable
private fun RestoreFlow(app: AppContext, onDone: () -> Unit) {
    var zip by remember { mutableStateOf<File?>(null) }
    var check by remember { mutableStateOf<BackupArchive.Check?>(null) }
    var password by remember { mutableStateOf<CharArray?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val picked = chooseOpenFile("Choose a backup to restore", app.backups.status.value.folder.takeIf { it.isDirectory }) ?: return@LaunchedEffect onDone()
        zip = picked
        val c = withContext(Dispatchers.IO) {
            runCatching { BackupArchive.inspect(picked, app.backups.knownPasswords.map { it.toCharArray() }) }
                .getOrElse { BackupArchive.Check.Invalid(it.message ?: "${picked.name} could not be read.") }
        }
        val info = (c as? BackupArchive.Check.Ok)?.info ?: (c as? BackupArchive.Check.NeedsPassword)?.info
        problem = (c as? BackupArchive.Check.Invalid)?.reason ?: runCatching { Restorer.checkCompatible(info) }.exceptionOrNull()?.message
        if (c is BackupArchive.Check.Ok) password = c.password
        check = c
    }

    val file = zip ?: return
    val c = check ?: return
    when {
        problem != null -> NoticeDialog("Can't restore this file", problem!!, onDone)
        working != null -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Restoring") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(working!!)
                }
            },
            confirmButton = {},
            containerColor = Color.White
        )
        c is BackupArchive.Check.NeedsPassword && password == null -> BackupPasswordPrompt(file, onDone) { pw ->
            app.backups.remember(String(pw))
            password = pw
        }
        else -> {
            val info = (c as? BackupArchive.Check.Ok)?.info ?: (c as? BackupArchive.Check.NeedsPassword)?.info
            ConfirmDialog(
                title = "Replace this laptop's data?",
                text = describe(file, info) +
                    "\n\nRestoring replaces ALL OTA Business data on this laptop (orders, bills, payment advices, the Accounts books and test data) " +
                    "with the data in this backup. Anything changed since that backup that is not in the cloud is lost.\n\n" +
                    "A safety backup of the current data is saved to your backup folder first, then the app reopens with the restored data.",
                confirm = "Replace this laptop's data",
                danger = true,
                onDismiss = onDone
            ) {
                working = "Saving a safety backup of the current data…"
                app.scope.launch {
                    try {
                        app.restoreBackup(file, password, info)
                    } catch (e: Throwable) {
                        scope.launch {
                            working = null
                            problem = "The safety backup of the current data failed, so nothing was restored: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
            }
        }
    }
}

private fun describe(zip: File, info: Properties?): String {
    val made = info?.getProperty("created")?.take(16)?.replace('T', ' ')
    val machine = info?.getProperty("machine")
    return "Backup: ${zip.name}" + (if (made != null) "\nMade: $made" + (machine?.let { " on $it" } ?: "") else "")
}

@Composable
private fun BackupPasswordPrompt(zip: File, onCancel: () -> Unit, onOpened: (CharArray) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (text.isEmpty() || busy) return
        busy = true
        scope.launch {
            val pw = text.trim().toCharArray()
            val ok = withContext(Dispatchers.IO) { BackupArchive.opens(zip, pw) }
            busy = false
            if (ok) onOpened(pw) else error = "That password does not open this backup."
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Backup password") },
        text = {
            Column(Modifier.width(420.dp)) {
                Text(
                    "${zip.name} was encrypted with a different password (another laptop's, or an older one). " +
                        "Enter the backup password shown in Sync & Cloud → Backups on the laptop that made it.",
                    style = MaterialTheme.typography.bodySmall, color = muted()
                )
                Spacer(Modifier.height(10.dp))
                Field(text, { text = it; error = null }, "Backup password", Modifier.fillMaxWidth().onEnter(::submit), password = true, error = error)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty() && !busy, onClick = ::submit) { Text(if (busy) "Checking…" else "Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = Color.White
    )
}

@Composable
fun NoticeDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        containerColor = Color.White
    )
}

// ------------------------------------------------------------------------------------ PIN lock settings

/** Sync & Cloud → Security: the optional PIN lock of this laptop. */
@Composable
fun SecurityPanel(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val lock = app.lock
    var revision by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<PinDialogMode?>(null) }
    val enabled = remember(revision) { lock.enabled }
    val idle = remember(revision) { lock.idleMinutes }
    Panel(modifier, title = "Security") {
        Text(
            "Lock OTA Business with a PIN when it starts and after you step away. Only the screens lock: sync and backups keep running. " +
                "The PIN is for this laptop only.",
            style = MaterialTheme.typography.bodySmall, color = muted()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelValue("PIN lock", if (enabled) "On" else "Off", Modifier.weight(1f), valueColor = if (enabled) Brand.Green else Color.Unspecified)
            if (enabled) {
                Select(
                    "Lock after", PinLock.IDLE_CHOICES, idle, { lock.idleMinutes = it; revision++ }, Modifier.width(190.dp),
                    text = { if (it == 0) "Only when the app starts" else "$it minutes idle" }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            if (!enabled) {
                Button(onClick = { dialog = PinDialogMode.SET }) { Text("Set a PIN…") }
            } else {
                Button(onClick = { lock.lockNow() }) { Text("Lock now") }
                OutlinedButton(onClick = { dialog = PinDialogMode.CHANGE }) { Text("Change PIN…") }
                TextButton(onClick = { dialog = PinDialogMode.TURN_OFF }) { Text("Turn off") }
            }
        }
    }
    dialog?.let { mode -> PinDialog(lock, mode) { dialog = null; revision++ } }
}

private enum class PinDialogMode { SET, CHANGE, TURN_OFF }

@Composable
private fun PinDialog(lock: PinLock, mode: PinDialogMode, onDone: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val needsCurrent = mode != PinDialogMode.SET
    val needsNew = mode != PinDialogMode.TURN_OFF
    val ready = (!needsCurrent || current.isNotEmpty()) && (!needsNew || (PinHash.valid(first) && first == second))
    fun submit() {
        if (!ready || busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                if (needsCurrent) {
                    when (val r = lock.unlock(current)) {
                        is PinLock.Attempt.Wrong -> return@withContext "Wrong PIN."
                        is PinLock.Attempt.Wait -> return@withContext "Too many wrong PINs. Try again at ${formatClock(r.untilMillis)}."
                        PinLock.Attempt.Ok -> Unit
                    }
                }
                if (mode == PinDialogMode.TURN_OFF) lock.turnOff() else lock.setPin(first)
                null
            }
            busy = false
            if (result == null) onDone() else error = result
        }
    }
    val pinError = when {
        first.isNotEmpty() && !PinHash.valid(first) -> "4 to 8 digits"
        second.isNotEmpty() && first != second -> "The two don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(when (mode) { PinDialogMode.SET -> "Set a PIN"; PinDialogMode.CHANGE -> "Change the PIN"; PinDialogMode.TURN_OFF -> "Turn off the PIN lock" }) },
        text = {
            Column(Modifier.width(380.dp)) {
                if (mode == PinDialogMode.SET) {
                    Text(
                        "4 to 8 digits. If you forget it, your OTA Business account password resets it (needs internet).",
                        style = MaterialTheme.typography.bodySmall, color = muted()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (needsCurrent) {
                    Field(current, { current = digits(it); error = null }, "Current PIN", Modifier.fillMaxWidth().onEnter(::submit), password = true)
                    Spacer(Modifier.height(6.dp))
                }
                if (needsNew) {
                    Field(first, { first = digits(it) }, "New PIN", Modifier.fillMaxWidth().onEnter(::submit), password = true)
                    Spacer(Modifier.height(6.dp))
                    Field(second, { second = digits(it) }, "New PIN again", Modifier.fillMaxWidth().onEnter(::submit), password = true, error = pinError)
                }
                error?.let { Banner(it, Brand.Red, Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !busy, onClick = ::submit) {
                Text(when (mode) { PinDialogMode.SET -> "Set PIN"; PinDialogMode.CHANGE -> "Change PIN"; PinDialogMode.TURN_OFF -> "Turn off" })
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        containerColor = Color.White
    )
}

private fun digits(s: String) = s.filter { it.isDigit() }.take(8)

private fun formatClock(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm:ss a"))

// ------------------------------------------------------------------------------------ lock screen

/** Any key or mouse input in the app counts as activity for the idle lock. */
@Composable
fun IdleWatch(lock: PinLock) {
    DisposableEffect(lock) {
        val listener = AWTEventListener { lock.activity() }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(
            listener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK
        )
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
}

/** The lock screen over everything in the window (open dialogs and unsaved edits stay underneath). */
@Composable
fun LockOverlay(app: AppContext) {
    val session by app.auth.session.collectAsState()
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        LockScreen(app.lock, session?.email.orEmpty(), app::verifyAccountPassword)
    }
}

private enum class LockStep { PIN, FORGOT, NEW_PIN }

@Composable
fun LockScreen(lock: PinLock, email: String, verifyPassword: suspend (String) -> Unit) {
    var step by remember { mutableStateOf(LockStep.PIN) }
    var pin by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableLongStateOf(lock.waitUntil) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    LaunchedEffect(waitUntil) {
        while (waitUntil > now) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }
    val waiting = waitUntil > now

    fun unlock() {
        if (busy || waiting || pin.length < 4) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { lock.unlock(pin) }
            busy = false
            pin = ""
            when (r) {
                PinLock.Attempt.Ok -> error = null
                is PinLock.Attempt.Wrong -> error = "Wrong PIN." + if (r.triesLeft <= 2) " ${r.triesLeft} more ${if (r.triesLeft == 1) "try" else "tries"} before a pause." else ""
                is PinLock.Attempt.Wait -> {
                    error = "Too many wrong PINs."
                    now = System.currentTimeMillis()
                    waitUntil = r.untilMillis
                }
            }
        }
    }

    fun verify() {
        if (busy || account.isEmpty()) return
        busy = true
        scope.launch {
            error = try {
                verifyPassword(account)
                step = LockStep.NEW_PIN
                null
            } catch (e: Throwable) {
                "Could not confirm your password: ${e.message ?: e.javaClass.simpleName}. Resetting the PIN needs an internet connection."
            }
            account = ""
            busy = false
        }
    }

    fun setNew() {
        if (busy || !PinHash.valid(first) || first != second) return
        busy = true
        scope.launch {
            withContext(Dispatchers.Default) { lock.setPin(first) }
            lock.unlocked()
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(Brand.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(6.dp)) { Logo(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(14.dp))
            Text("OTA Business is locked", style = MaterialTheme.typography.titleLarge)
            if (email.isNotEmpty()) Text(email, style = MaterialTheme.typography.bodyMedium, color = muted())
            Spacer(Modifier.height(18.dp))
            when (step) {
                LockStep.PIN -> {
                    Field(
                        pin, { pin = digits(it); error = null }, "PIN",
                        Modifier.fillMaxWidth().focusRequester(focus).onEnter(::unlock), password = true, enabled = !waiting
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = ::unlock, enabled = !busy && !waiting && pin.length >= 4, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                        Text(if (busy) "Checking…" else "Unlock")
                    }
                    if (waiting) {
                        val left = ((waitUntil - now + 999) / 1000).toInt()
                        Banner("Too many wrong PINs. Try again in ${left / 60}:${"%02d".format(left % 60)}.", Brand.Amber, Modifier.padding(top = 12.dp))
                    } else error?.let { Banner(it, Brand.Red, Modifier.padding(top = 12.dp)) }
                    TextButton(onClick = { step = LockStep.FORGOT; error = null }, modifier = Modifier.padding(top = 6.dp)) { Text("Forgot PIN?") }
                }
                LockStep.FORGOT -> {
                    Text(
                        "Enter the password of your OTA Business account to set a new PIN. This checks it online.",
                        style = MaterialTheme.typography.bodySmall, color = muted(), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Field(account, { account = it; error = null }, "Account password", Modifier.fillMaxWidth().focusRequester(focus).onEnter(::verify), password = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = ::verify, enabled = !busy && account.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(44.dp)) {
                        Text(if (busy) "Checking…" else "Continue")
                    }
                    error?.let { Banner(it, Brand.Red, Modifier.padding(top = 12.dp)) }
                    TextButton(onClick = { step = LockStep.PIN; error = null }, modifier = Modifier.padding(top = 6.dp)) { Text("Back") }
                }
                LockStep.NEW_PIN -> {
                    Text("Password confirmed. Choose a new PIN (4 to 8 digits).", style = MaterialTheme.typography.bodySmall, color = muted(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Field(first, { first = digits(it) }, "New PIN", Modifier.fillMaxWidth().focusRequester(focus).onEnter(::setNew), password = true)
                    Spacer(Modifier.height(6.dp))
                    Field(
                        second, { second = digits(it) }, "New PIN again", Modifier.fillMaxWidth().onEnter(::setNew), password = true,
                        error = if (second.isNotEmpty() && first != second) "The two don't match" else null
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = ::setNew, enabled = !busy && PinHash.valid(first) && first == second, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                        Text("Set PIN and unlock")
                    }
                    TextButton(onClick = { lock.turnOff() }, modifier = Modifier.padding(top = 6.dp)) { Text("Turn the PIN lock off instead") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ files

private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** Native folder picker; null when cancelled. */
fun chooseFolder(title: String, start: File? = null): File? {
    if (isMac) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            start?.let { dialog.directory = it.absolutePath }
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return File(dialog.directory, name)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    val chooser = javax.swing.JFileChooser(start).apply {
        dialogTitle = title
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showDialog(null, "Use this folder") == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Native "Open" dialog for a backup .zip; null when cancelled. */
fun chooseOpenFile(title: String, directory: File? = null): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    directory?.let { dialog.directory = it.absolutePath }
    dialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
    if (!isMac) dialog.file = "*.zip"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

private fun copyText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
