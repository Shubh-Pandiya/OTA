package com.ota.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.otaapplication.data.OtaAccounts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ota.desktop.AppContext
import com.ota.desktop.pdf.Marks
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class Section(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DASHBOARD("Insights", Icons.Default.DateRange),
    ORDERS("Orders", Icons.Default.ShoppingCart),
    BILLS("Bills", Icons.Default.List),
    ADVICES("Payment Advices", Icons.Default.Send),
    CATALOG("Buyers & Suppliers", Icons.Default.AccountBox),
    ACCOUNTS("Accounts", AccountsIcon),
    SYNC("Sync & Cloud", Icons.Default.Refresh),
    EXPORT("Export", Icons.Default.Share)
}

/** A request to show one record, possibly on another screen. */
sealed interface Target {
    data class Order(val id: String) : Target
    data class Bill(val id: String) : Target
    data class Advice(val id: String) : Target
    data class NewAdvice(val billId: String?) : Target
    data object NewOrder : Target
    data object NewBill : Target
    /** Accounts opened on a tab, optionally with a ledger, a report or a voucher showing. */
    data class Accounts(
        val tab: AccTab,
        val accountId: String? = null,
        val report: com.ota.desktop.accounts.ReportKind? = null,
        val voucherId: String? = null
    ) : Target
}

class Navigator {
    var section by mutableStateOf(Section.HOME)
    var target by mutableStateOf<Target?>(null)
        private set

    fun go(section: Section, target: Target? = null) {
        this.target = target
        this.section = section
    }

    /** Takes the pending target for [section], if any. */
    fun take(section: Section): Target? = if (this.section == section) target.also { target = null } else null
}

val LocalNav = staticCompositionLocalOf { Navigator() }

/** The letterhead logo drawn from its vector path. */
@Composable
fun Logo(modifier: Modifier = Modifier, color: Color = Color(0xFF000000 or Marks.LOGO_RGB.toLong())) {
    val path = remember {
        Path().apply {
            for (op in Marks.logo) {
                val c = op.coords
                when (op.cmd) {
                    'M' -> moveTo(c[0], c[1])
                    'L' -> lineTo(c[0], c[1])
                    'C' -> cubicTo(c[0], c[1], c[2], c[3], c[4], c[5])
                    'Z' -> close()
                }
            }
        }
    }
    Canvas(modifier) {
        val k = size.minDimension / Marks.VIEWPORT
        drawContext.transform.scale(k, k, androidx.compose.ui.geometry.Offset.Zero)
        drawPath(path, color)
    }
}

fun ago(millis: Long?): String {
    if (millis == null) return "never"
    val d = Duration.between(Instant.ofEpochMilli(millis), Instant.now())
    return when {
        d.seconds < 45 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} d ago"
    }
}

@Composable
fun AppShell(app: AppContext, nav: Navigator = remember { Navigator() }) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
    CompositionLocalProvider(LocalNav provides nav, LocalToast provides toast) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(app, nav)
                AnimatedContent(
                    targetState = nav.section,
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 40 }) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { section ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
                        when (section) {
                            Section.HOME -> HomeScreen()
                            Section.DASHBOARD -> DashboardScreen()
                            Section.ORDERS -> OrdersScreen()
                            Section.BILLS -> BillsScreen()
                            Section.ADVICES -> AdvicesScreen()
                            Section.CATALOG -> CatalogScreen()
                            Section.ACCOUNTS -> if (app.admin.collectAsState().value) AccountsScreen() else HomeScreen()
                            Section.SYNC -> SyncScreen()
                            Section.EXPORT -> ExportScreen()
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun Sidebar(app: AppContext, nav: Navigator) {
    val status by app.engine.status.collectAsState()
    val staff by app.staff.collectAsState()
    val session by app.auth.session.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.width(236.dp).fillMaxHeight().background(Brand.Sidebar).padding(vertical = 18.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(4.dp)) { Logo(Modifier.fillMaxSize()) }
            Column(Modifier.padding(start = 10.dp)) {
                Text("OTA Business", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Onkar Textile Agency", color = Brand.SidebarText.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(22.dp))
        val admin by app.admin.collectAsState()
        // Accounts (the agency's own books) is for the admins only.
        LaunchedEffect(admin) { if (!admin && nav.section == Section.ACCOUNTS) nav.go(Section.HOME) }
        Section.entries.filter { admin || it != Section.ACCOUNTS }.forEach { s -> NavItem(s, nav.section == s) { nav.go(s) } }
        Spacer(Modifier.weight(1f))

        // Sync status
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))
                .clickable { nav.go(Section.SYNC) }.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, text) = when {
                    status.running -> Brand.Saffron to "Syncing…"
                    status.storageFull -> Brand.Red to "Cloud storage full"
                    status.error != null -> Brand.Amber to (if (status.error!!.startsWith("Offline")) "Offline" else "Sync problem")
                    status.pending > 0 -> Brand.Amber to "${status.pending} to upload"
                    else -> Brand.Green to "Synced"
                }
                if (status.running) {
                    val t = rememberInfiniteTransition()
                    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse))
                    Dot(color.copy(alpha = a))
                } else Dot(color)
                Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Text("Last sync ${ago(status.lastSyncAt)}", color = Brand.SidebarText.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            status.usage?.let { u ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (u.percent / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (u.percent >= 100) Brand.Red else if (u.warning) Brand.Amber else Brand.Green,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    drawStopIndicator = {}
                )
                Text("Cloud ${"%.0f".format(u.usedMb)} / ${u.limitMb} MB", color = Brand.SidebarText.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.08f))
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(staff?.name?.ifBlank { null } ?: session?.email.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    staff?.let { "Staff ${it.code}" + if (admin) " · Admin" else if (OtaAccounts.isTest(session?.email)) " · Test" else "" } ?: "No staff code",
                    color = Brand.SidebarText.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall
                )
            }
            androidx.compose.material3.IconButton(onClick = { app.engine.refreshPending(); confirmSignOut = true }) {
                Icon(Icons.Default.ExitToApp, "Sign out", tint = Brand.SidebarText)
            }
        }
    }
    if (confirmSignOut) {
        val pending = status.pending
        ConfirmDialog(
            title = "Sign out?",
            text = if (pending > 0) "$pending change(s) haven't reached the cloud yet. They stay on this laptop and upload after the next sign-in, but other devices won't see them until then."
            else "You'll need your staff email and password to sign in again. Your data stays on this laptop.",
            confirm = "Sign out",
            danger = pending > 0,
            onDismiss = { confirmSignOut = false },
            onConfirm = { confirmSignOut = false; scope.launch { app.signOut() } }
        )
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> Brand.Saffron
            hovered -> Color.White.copy(alpha = 0.07f)
            else -> Color.Transparent
        }, tween(160)
    )
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(bg)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(section.icon, null, tint = if (selected) Color.White else Brand.SidebarText.copy(alpha = 0.75f), modifier = Modifier.size(19.dp))
        Text(
            section.title, color = if (selected) Color.White else Brand.SidebarText, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// ------------------------------------------------------------------------------------ sign-in

@Composable
fun SignInScreen(app: AppContext) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val configured = app.config.isConfigured
    fun submit() {
        if (busy || email.isBlank() || password.isEmpty() || !configured) return
        busy = true; error = null
        scope.launch {
            try {
                app.signIn(email, password)
            } catch (e: java.io.IOException) {
                error = if (e is com.example.otaapplication.cloud.CloudException) e.message else "Can't reach the cloud. Check the internet connection and try again."
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Brand.Saffron, Color(0xFFE65100), Brand.SaffronDark))
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(14.dp)) { Logo(Modifier.fillMaxSize()) }
                Spacer(Modifier.height(24.dp))
                Text("OTA Business", style = MaterialTheme.typography.headlineSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color.White)
                Text("Onkar Textile Agency", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(18.dp))
                Text(
                    "Orders, bills and payment advices — your main ledger on this laptop,\nkept in sync with the phones through the cloud.",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 380.dp).padding(32.dp)) {
                Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                Text("Use your staff email and password. You only need to do this once; after that the app works offline too.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                if (!configured) {
                    Banner("This build has no cloud configured, so nobody can sign in. Install the official OTA Business installer.", Brand.Red)
                    Spacer(Modifier.height(12.dp))
                }
                Field(email, { email = it.trim() }, "Email", Modifier.fillMaxWidth().onEnter(::submit))
                Spacer(Modifier.height(10.dp))
                Field(password, { password = it }, "Password", Modifier.fillMaxWidth().onEnter(::submit), password = true)
                FadeIn(error != null) {
                    Banner(error.orEmpty(), Brand.Red, Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = ::submit,
                    enabled = !busy && configured && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Sign in")
                }
            }
        }
    }
}

fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key == Key.Enter && it.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) { action(); true } else false
}

// ------------------------------------------------------------------------------------ splash

@Composable
fun Splash(message: String, error: String?, onQuit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp).padding(32.dp)) {
            val t = rememberInfiniteTransition()
            val pulse by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
            Box(
                Modifier.size(96.dp * (if (error == null) pulse else 1f)).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(12.dp)
            ) { Logo(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(20.dp))
            Text("OTA Business", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            if (error == null) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.width(220.dp).clip(RoundedCornerShape(2.dp)), trackColor = Brand.SaffronSoft)
            } else {
                Banner(error, Brand.Red)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onQuit, colors = ButtonDefaults.buttonColors()) { Text("Close") }
            }
        }
    }
}

@Composable
fun SmallAction(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text)
    }
}

@Suppress("unused")
private val keep = Icons.Default.DateRange
