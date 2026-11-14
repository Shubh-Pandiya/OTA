package com.example.otaapplication.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otaapplication.FormSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun biometricAvailable(context: Context): Boolean = runCatching {
    context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

private fun promptBiometric(context: Context, onSuccess: () -> Unit) {
    val executor = context.mainExecutor
    runCatching {
        BiometricPrompt.Builder(context)
            .setTitle("Unlock OTA Business")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Use PIN", executor) { _, _ -> }
            .build()
            .authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            })
    }
}

/**
 * Covers the app until the PIN (or a fingerprint) is given. [verifyPassword] checks the cloud
 * password for "Forgot PIN" and returns an error message, or null when it was right.
 */
@Composable
fun LockScreen(lock: AppLock, email: String?, verifyPassword: suspend (String) -> String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val canBiometric = remember { lock.biometric && biometricAvailable(context) }

    LaunchedEffect(Unit) { if (canBiometric) promptBiometric(context) { lock.unlock() } }

    fun submit(value: String) {
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { lock.check(value) }
            checking = false
            when (result) {
                AppLock.Result.Ok -> lock.unlock()
                is AppLock.Result.Wrong -> {
                    message = "Wrong PIN. ${result.attemptsLeft} more ${if (result.attemptsLeft == 1) "try" else "tries"} before a wait."
                    pin = ""
                    for (x in listOf(14f, -12f, 8f, -4f, 0f)) shake.animateTo(x, tween(45))
                }
                is AppLock.Result.Wait -> {
                    pin = ""
                    message = "Too many wrong PINs. Try again in ${(result.millis + 999) / 1000} s."
                    for (x in listOf(14f, -12f, 8f, -4f, 0f)) shake.animateTo(x, tween(45))
                }
            }
        }
    }

    fun press(digit: Char) {
        if (checking || pin.length >= 8) return
        pin += digit
        if (pin.length == lock.pinLength) submit(pin)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(18.dp))
            Text("OTA Business is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Enter your ${lock.pinLength}-digit PIN", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                repeat(lock.pinLength) { i ->
                    val filled = i < pin.length
                    val color by animateColorAsState(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, label = "dot")
                    Box(Modifier.size(16.dp).clip(CircleShape).background(color).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                message ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.height(36.dp)
            )
            Keypad(
                onDigit = ::press,
                onBack = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                onBiometric = if (canBiometric) ({ promptBiometric(context) { lock.unlock() } }) else null
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { forgot = true }) { Text("Forgot PIN?") }
        }
    }

    if (forgot) {
        ForgotPinDialog(email = email, verifyPassword = verifyPassword, onDone = {
            forgot = false
            lock.disable()
        }, onDismiss = { forgot = false })
    }
}

@Composable
private fun Keypad(onDigit: (Char) -> Unit, onBack: () -> Unit, onBiometric: (() -> Unit)?) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { d -> Key(d.toString()) { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onBiometric != null) Key(icon = { Icon(Icons.Default.Face, contentDescription = "Unlock with fingerprint") }, onClick = onBiometric)
            else Spacer(Modifier.size(72.dp))
            Key("0") { onDigit('0') }
            Key(icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Delete digit") }, onClick = onBack)
        }
    }
}

@Composable
private fun Key(label: String? = null, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium) else icon?.invoke()
    }
}

@Composable
private fun ForgotPinDialog(email: String?, verifyPassword: suspend (String) -> String?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Forgot PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the cloud password of ${email ?: "this phone's account"} to turn the app lock off. This needs the internet. Then set a new PIN in Sync Settings.")
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    val problem = verifyPassword(password)
                    busy = false
                    if (problem == null) onDone() else error = problem
                }
            }) { Text(if (busy) "Checking…" else "Turn lock off") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Sync Settings → Security: the app lock switch, fingerprint, timeout and PIN change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecuritySection(lock: AppLock) {
    val context = LocalContext.current
    val tick by lock.settings.collectAsState()
    val enabled = remember(tick) { lock.enabled }
    val biometricHw = remember { biometricAvailable(context) }
    var dialog by remember { mutableStateOf<PinDialog?>(null) }

    FormSection(title = "Security", icon = Icons.Default.Lock) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ask for a PIN when the app opens or comes back after a while.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { dialog = if (it) PinDialog.New else PinDialog.TurnOff })
            }
            if (enabled) {
                if (biometricHw) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Unlock with fingerprint", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = remember(tick) { lock.biometric }, onCheckedChange = { lock.biometric = it })
                    }
                }
                Text("Lock after the app is away for", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = remember(tick) { lock.timeoutMs }
                    AppLock.TIMEOUTS.forEach { (ms, label) ->
                        FilterChip(selected = current == ms, onClick = { lock.timeoutMs = ms }, label = { Text(label) })
                    }
                }
                OutlinedButton(onClick = { dialog = PinDialog.Change }) { Text("Change PIN") }
            }
            Text(
                "Orders, bills, advices, to-dos and pictures on this phone are always stored encrypted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    dialog?.let { kind -> PinSetupDialog(lock, kind, onDismiss = { dialog = null }) }
}

enum class PinDialog { New, Change, TurnOff }

@Composable
private fun PinSetupDialog(lock: AppLock, kind: PinDialog, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val needsCurrent = kind != PinDialog.New
    val needsNew = kind != PinDialog.TurnOff

    @Composable
    fun pinField(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = { v -> if (v.length <= 8 && v.all(Char::isDigit)) { onChange(v); error = null } },
        label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(when (kind) { PinDialog.New -> "Set app PIN"; PinDialog.Change -> "Change PIN"; PinDialog.TurnOff -> "Turn app lock off" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsCurrent) pinField(current, "Current PIN") { current = it }
                if (needsNew) {
                    pinField(pin, "New PIN (4–8 digits)") { pin = it }
                    pinField(confirm, "Repeat new PIN") { confirm = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (needsNew && !AppLock.isValidPin(pin)) { error = "Use 4 to 8 digits."; return@TextButton }
                if (needsNew && pin != confirm) { error = "The two PINs don't match."; return@TextButton }
                busy = true
                scope.launch {
                    val ok = if (!needsCurrent) AppLock.Result.Ok else withContext(Dispatchers.Default) { lock.check(current) }
                    when (ok) {
                        AppLock.Result.Ok -> {
                            withContext(Dispatchers.Default) { if (needsNew) lock.setPin(pin) else lock.disable() }
                            onDismiss()
                        }
                        is AppLock.Result.Wrong -> error = "Current PIN is wrong."
                        is AppLock.Result.Wait -> error = "Too many wrong PINs. Try again in ${(ok.millis + 999) / 1000} s."
                    }
                    busy = false
                }
            }) { Text(if (kind == PinDialog.TurnOff) "Turn off" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
