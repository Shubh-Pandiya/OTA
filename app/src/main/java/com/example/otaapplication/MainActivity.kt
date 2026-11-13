package com.example.otaapplication

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import com.example.otaapplication.reminders.Reminders
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import com.example.otaapplication.weather.rememberHomeWeather
import com.example.otaapplication.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.otaapplication.cloud.CloudStatus
import com.example.otaapplication.cloud.CloudSync
import com.example.otaapplication.cloud.OrderLimitReached
import com.example.otaapplication.data.BillQuery
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.HomeStats
import com.example.otaapplication.data.OrderQuery
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OrderJson
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.PaymentRepository
import com.example.otaapplication.ui.theme.OTAApplicationTheme
import com.example.otaapplication.update.AppUpdater
import com.example.otaapplication.update.UpdateDialog
import com.example.otaapplication.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class OrderHistoryItem(
    val date: String,
    val offerNo: String,
    val buyer: String,
    val filePath: String,
    val fileName: String,
    val pdfFileName: String? = null,
    val uuid: String? = null
)

enum class Screen(val title: String, val tabLabel: String) {
    Home("OTA Business", "Home"),
    Insights("Insights", "Insights"),
    OrderEntry("Order Entry", "New order"),
    OrderHistory("Order History", "Orders"),
    Bills("Bills", "Bills"),
    Advices("Payment Advices", "Advices"),
    Catalog("Buyers & Suppliers", "Parties"),
    SyncSettings("Sync Settings", "Sync")
}

/** The bottom bar; the rest live in the drawer. */
private val bottomTabs = listOf(Screen.Home, Screen.OrderEntry, Screen.OrderHistory, Screen.Bills, Screen.Advices)

/** Drawer groups: orders and payments are separate sections. */
private val drawerGroups = listOf(
    null to listOf(Screen.Home, Screen.Insights),
    "Orders" to listOf(Screen.OrderEntry, Screen.OrderHistory),
    "Payments" to listOf(Screen.Bills, Screen.Advices),
    "Manage" to listOf(Screen.Catalog, Screen.SyncSettings)
)

private val screenIcons = mapOf(
    Screen.Home to Icons.Default.Home,
    Screen.Insights to Icons.Default.Insights,
    Screen.OrderEntry to Icons.Default.EditNote,
    Screen.OrderHistory to Icons.Default.History,
    Screen.Bills to Icons.AutoMirrored.Filled.ReceiptLong,
    Screen.Advices to Icons.Default.Payments,
    Screen.Catalog to Icons.Default.Contacts,
    Screen.SyncSettings to Icons.Default.Settings
)

// Material "fade through" used for top-level destination changes.
private fun AnimatedContentTransitionScope<Screen>.fadeThrough(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220, delayMillis = 90)))
        .togetherWith(fadeOut(tween(90)))

private const val PREF_PDFS_SHRUNK = "order_pdfs_vector_v2"

class MainActivity : ComponentActivity() {

    internal val catalogRepository by lazy {
        CatalogRepository(OtaDatabase.get(this)).also { repo ->
            // Upload catalog edits shortly after they are made (debounced).
            repo.onLocalChange = { cloud.requestSync(delayMillis = 1_500) }
        }
    }
    private val cloud by lazy { CloudSync.get(this) }
    internal val localOrders by lazy { LocalOrders.get(this) }
    internal val orderRepository: OrderRepository get() = localOrders.index
    private val orderFiles by lazy { OrderFiles(this, localOrders) }
    private val payments by lazy { PaymentRepository.get(this) }
    private val updater by lazy { AppUpdater.get(this) }

    // Bumped on every resume so the previewed order number follows a change of day.
    private var resumeTick by mutableIntStateOf(0)

    // True while the app is in front; Home's cat and weather pause otherwise to save battery.
    private var inForeground by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        resumeTick++
        inForeground = true
        // Back from "Install unknown apps": carry on with the update the user started.
        (updater.state.value as? UpdateState.NeedsPermission)?.let { s ->
            if (updater.canInstall()) startUpdate(s.release)
        }
    }

    private fun startUpdate(release: com.example.otaapplication.update.PhoneRelease) {
        lifecycleScope.launch { updater.update(release, ::syncBeforeUpdate) }
    }

    /** Uploads unsynced changes before an update. Whatever can't upload now stays in the database, which the update keeps. */
    private suspend fun syncBeforeUpdate(): String? {
        withTimeoutOrNull(90_000) { cloud.syncNow() }
        val pending = cloud.status.value.pending
        return if (pending > 0) {
            "$pending change${if (pending == 1) "" else "s"} not uploaded yet. They stay safely on this phone and upload after the update."
        } else null
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    // Bumped when a reminder notification is tapped, which opens Home.
    private var homeRequest by mutableIntStateOf(0)

    private fun takeHomeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(Reminders.EXTRA_OPEN_HOME, false) == true) {
            intent.removeExtra(Reminders.EXTRA_OPEN_HOME)
            homeRequest++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeHomeRequest(intent)
    }

    override fun onStart() {
        super.onStart()
        // Catalog sync whenever the app becomes visible, then a light poll while it stays visible.
        cloud.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        cloud.onAppBackground()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light-only app: keep dark system-bar icons even when the device is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Index existing orders (including ones saved before search existed) in the background.
        lifecycleScope.launch(Dispatchers.IO) { syncOrderIndex(); shrinkOldOrderPdfs() }
        // Alarms are cleared when the app is force-stopped; setting them again on launch is cheap.
        lifecycleScope.launch(Dispatchers.IO) { Reminders.rescheduleAll(this@MainActivity) }
        lifecycleScope.launch(Dispatchers.IO) { runCatching { updater.cleanUp() } }
        // New phone releases on GitHub: looked for when the app comes to the front, then every few hours.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updater.check(manual = false)
                    delay(AppUpdater.CHECK_EVERY_MS)
                }
            }
        }
        takeHomeRequest(intent)
        setContent {
            OTAApplicationTheme {
                val authStatus by cloud.status.collectAsState()
                // Staff must sign in before anything else is shown. Once signed in the app opens offline
                // too; only signing out or the server rejecting the session brings this screen back.
                AnimatedContent(
                    targetState = authStatus.configured && authStatus.signedIn,
                    transitionSpec = { fadeIn(tween(320, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
                    label = "authGate"
                ) { signedIn ->
                    if (signedIn) SignedInApp() else SignInScreen(cloud = cloud, status = authStatus)
                }
                val updateState by updater.state.collectAsState()
                UpdateDialog(
                    state = updateState,
                    currentVersion = updater.currentName,
                    onUpdate = ::startUpdate,
                    onLater = updater::later,
                    onAllow = { runCatching { startActivity(updater.permissionIntent()) } },
                    onDismiss = updater::dismiss
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun SignedInApp() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var currentScreen by rememberSaveable { mutableStateOf(Screen.Home) }
        // A bill or advice tapped on Home, opened once its section is showing.
        var pendingPage by remember { mutableStateOf<PaymentPage?>(null) }

        var formData by remember { mutableStateOf(OrderFormData()) }
        var focusedOrderState by remember { mutableStateOf<OrderFormData?>(null) }
        // uuid of the order reopened from history; saving updates that order in place.
        var focusedUuid by remember { mutableStateOf<String?>(null) }
        var isGenerating by remember { mutableStateOf(false) }
        val resumed = resumeTick
        val cloudStatus by cloud.status.collectAsState()
        val remoteCatalogChanges by cloud.catalogChanges.collectAsState()
        val remoteOrderChanges by cloud.orderChanges.collectAsState()
        val staff by cloud.staff.collectAsState()
        val paymentDeps = remember {
            PaymentDeps(orderRepository, payments, catalogRepository, cloud::allocateAdviceNo)
        }

        // Typing stops whenever the user navigates: menu, drawer swipe, tabs, Home shortcuts.
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun dismissKeyboard() {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        LaunchedEffect(drawerState.targetValue) { if (drawerState.targetValue == DrawerValue.Open) dismissKeyboard() }
        LaunchedEffect(currentScreen) { dismissKeyboard() }

        fun navigate(screen: Screen) {
            currentScreen = screen
            scope.launch { drawerState.close() }
        }

        val openHome = homeRequest
        LaunchedEffect(openHome) {
            if (openHome > 0) {
                currentScreen = Screen.Home
                drawerState.close()
            }
        }

        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
        BackHandler(enabled = drawerState.isClosed && currentScreen != Screen.Home) { currentScreen = Screen.Home }

        val statsShown = currentScreen == Screen.Home || currentScreen == Screen.Insights
        val homeStats by produceState<HomeStats?>(null, statsShown, resumed, remoteOrderChanges, cloudStatus.lastSyncAt) {
            if (statsShown) value = withContext(Dispatchers.IO) { runCatching { loadHomeStats() }.onFailure { it.printStackTrace() }.getOrNull() } ?: value
        }
        val homeWeather = rememberHomeWeather(active = currentScreen == Screen.Home && inForeground)
        val reduceMotion = remember(resumed) {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
        val homeActions = remember {
            HomeActions(
                newOrder = { currentScreen = Screen.OrderEntry },
                newAdvice = { pendingPage = PaymentPage.AdviceEdit(null); currentScreen = Screen.Advices },
                addBill = { pendingPage = PaymentPage.BillEdit(null); currentScreen = Screen.Bills },
                orders = { currentScreen = Screen.OrderHistory },
                bills = { currentScreen = Screen.Bills },
                advices = { currentScreen = Screen.Advices },
                parties = { currentScreen = Screen.Catalog },
                openBill = { pendingPage = PaymentPage.BillView(it); currentScreen = Screen.Bills },
                openAdvice = { pendingPage = PaymentPage.AdviceView(it); currentScreen = Screen.Advices },
                sync = { scope.launch { cloud.syncNow() } },
                insights = { currentScreen = Screen.Insights },
                weather = { homeWeather.showSettings = true }
            )
        }
        val imeVisible = WindowInsets.isImeVisible

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = { AppDrawer(currentScreen = currentScreen, cloudStatus = cloudStatus, onNavigate = ::navigate) }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            AnimatedContent(
                                targetState = currentScreen.title,
                                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                                label = "title"
                            ) { title -> Text(text = title, style = MaterialTheme.typography.titleLarge) }
                        },
                        navigationIcon = {
                            IconButton(onClick = { dismissKeyboard(); scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
                    ) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                            bottomTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentScreen == tab,
                                    onClick = { dismissKeyboard(); currentScreen = tab },
                                    icon = { Icon(screenIcons.getValue(tab), contentDescription = null) },
                                    label = { Text(tab.tabLabel, maxLines = 1, softWrap = false) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                AnimatedContent(
                    targetState = currentScreen,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    transitionSpec = { fadeThrough() },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            stats = homeStats,
                            cloudStatus = cloudStatus,
                            staffName = staff?.name,
                            actions = homeActions,
                            weather = homeWeather.view,
                            refreshKey = resumed,
                            catActive = inForeground && !drawerState.isOpen,
                            reduceMotion = reduceMotion
                        )
                        Screen.Insights -> InsightsScreen(stats = homeStats, actions = homeActions)
                        Screen.OrderEntry -> OrderFormScreen(
                            // New orders get their number from the cloud when saved; orders reopened
                            // from history keep their original one.
                            formData = focusedOrderState ?: formData,
                            onFormChange = { if (focusedOrderState != null) focusedOrderState = it else formData = it },
                            isNewOrder = focusedOrderState == null,
                            isGenerating = isGenerating,
                            catalog = catalogRepository,
                            onOpenSyncSettings = { currentScreen = Screen.SyncSettings },
                            onGeneratePdf = { data ->
                                val isNewOrder = focusedOrderState == null
                                if (!isGenerating) {
                                    isGenerating = true
                                    val existingUuid = focusedUuid
                                    scope.launch {
                                        val saved = if (isNewOrder) saveNewOrder(data) else saveEditedOrder(data, existingUuid)
                                        val ok = saved.ok
                                        Toast.makeText(this@MainActivity, saved.message, if (ok && saved.pdf == null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                                        // Generated: straight to the share sheet. A pending order has no PDF yet.
                                        saved.pdf?.let { shareOrderPdf(it, saved.offerNo) }
                                        if (ok) {
                                            focusedOrderState = null
                                            focusedUuid = null
                                            formData = OrderFormData()
                                        }
                                        isGenerating = false
                                    }
                                }
                            }
                        )
                        Screen.OrderHistory -> OrderHistoryScreen(
                            activity = this@MainActivity,
                            orders = orderRepository,
                            paymentDeps = paymentDeps,
                            remoteChanges = remoteOrderChanges,
                            onOrderFocused = { selected, uuid ->
                                focusedOrderState = Units.splitLegacy(selected)
                                focusedUuid = uuid
                                Toast.makeText(this@MainActivity, "Order Focused: ${selected.offerNo}", Toast.LENGTH_SHORT).show()
                                currentScreen = Screen.OrderEntry
                            }
                        )
                        Screen.Bills, Screen.Advices -> PaymentAdviceScreen(
                            deps = paymentDeps,
                            remoteChanges = remoteOrderChanges,
                            tab = if (screen == Screen.Bills) PaymentTab.BILLS else PaymentTab.ADVICES,
                            openPage = pendingPage,
                            onPageOpened = { pendingPage = null }
                        )
                        Screen.Catalog -> CatalogScreen(
                            catalog = catalogRepository,
                            cloudStatus = cloudStatus,
                            remoteChanges = remoteCatalogChanges,
                            onOpenCloudSettings = { currentScreen = Screen.SyncSettings }
                        )
                        Screen.SyncSettings -> {
                            val totalOrders by produceState<Int?>(initialValue = null) {
                                value = withContext(Dispatchers.IO) { countTotalOrders() }
                            }
                            SyncControlScreen(
                                totalOrders = totalOrders, cloud = cloud,
                                appVersion = updater.currentName,
                                onCheckUpdates = { scope.launch { updater.check(manual = true) } }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadHomeStats(): HomeStats {
        syncOrderIndex()
        val today = LocalDate.now()
        return HomeStats.compute(
            today = today,
            ordersToday = orderRepository.counts(OrderQuery(from = today, to = today)).total,
            ordersThisMonth = orderRepository.counts(OrderQuery(from = today.withDayOfMonth(1), to = today)).total,
            ordersAwaitingBill = orderRepository.counts(OrderQuery()).pending,
            pendingNumbers = orderRepository.unnumberedOrders().count { !it.deleted },
            ordersPerMonth = HomeStats.monthStarts(today).map { m ->
                orderRepository.counts(OrderQuery(from = m, to = minOf(m.plusMonths(1).minusDays(1), today))).total
            },
            recentOrders = orderRepository.search(OrderQuery()).take(3),
            bills = payments.bills(BillQuery()),
            advices = payments.search("")
        )
    }

    private fun countTotalOrders(): Int {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val otaDir = File(baseDir, "OTA Orders")
        val now = LocalDateTime.now(); val year = now.year.toString()
        val monthName = now.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val syncDir = File(otaDir, "$year/$monthName/SyncData")
        if (!syncDir.exists()) return 0
        return syncDir.listFiles { file -> file.isFile && file.extension == "json" }?.size ?: 0
    }

    internal fun loadOrderHistory(): List<OrderHistoryItem> {
        val historyList = mutableListOf<OrderHistoryItem>()
        try {
            val array = localOrders.history.readArray()
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                historyList.add(OrderHistoryItem(
                    date = json.optString("date", "N/A"),
                    offerNo = json.optString("offerNo", "N/A"),
                    buyer = json.optString("buyer", "Unknown Buyer"),
                    filePath = json.optString("filePath", ""),
                    fileName = "internal_cache_${i}",
                    pdfFileName = if (json.has("pdfFileName")) json.getString("pdfFileName") else null,
                    uuid = if (json.has("uuid")) json.getString("uuid") else null
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return historyList
    }

    /** Runs on a background thread; mirrors the history file into the search index. */
    internal fun syncOrderIndex(): Boolean = runCatching { localOrders.reindex() }.getOrElse { it.printStackTrace(); false }

    internal fun getOrderFromHistory(uuid: String?): OrderFormData? =
        uuid?.let { runCatching { localOrders.get(it) }.onFailure { e -> e.printStackTrace() }.getOrNull() }

    internal fun deleteOrderPermanently(uuid: String?, syncFilePath: String, pdfName: String?): Boolean =
        localOrders.delete(uuid, syncFilePath, pdfName)

    /**
     * Saves a new order under the next agency-wide number from the cloud. Offline (or when the
     * cloud can't be reached) it is saved with a pending number instead: no PDF is made until the
     * next sync gives it a number, so an order is never shared under a temporary number.
     * Returns (success, user message).
     */
    /** Result of the order form's Generate: [pdf] is set when a PDF was made (then it is shared). */
    private data class GeneratedOrder(val ok: Boolean, val message: String, val pdf: File? = null, val offerNo: String = "")

    private suspend fun saveNewOrder(data: OrderFormData): GeneratedOrder {
        val date = OrderNumber.parseOrderDate(data.date) ?: LocalDate.now()
        val number = try {
            cloud.allocateOrderNo(date)
        } catch (e: OrderLimitReached) {
            return GeneratedOrder(false, e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val saved = withContext(Dispatchers.IO) { orderFiles.save(data.copy(offerNo = number ?: OrderNumber.PENDING), existingUuid = null) }
        return when {
            !saved.ok -> GeneratedOrder(false, saved.message)
            number != null -> GeneratedOrder(true, "Order $number saved", saved.pdf, number)
            else -> GeneratedOrder(true, "Saved offline with a pending number. The order number and PDF are made on the next sync.")
        }
    }

    /** Saves an order reopened from history in place; a pending one still waits for its number. */
    private suspend fun saveEditedOrder(data: OrderFormData, uuid: String?): GeneratedOrder {
        val saved = withContext(Dispatchers.IO) { orderFiles.save(data, uuid) }
        if (!saved.ok) return GeneratedOrder(false, saved.message)
        val offerNo = withContext(Dispatchers.IO) { uuid?.let { localOrders.get(it)?.offerNo } } ?: data.offerNo
        return if (saved.pdf == null) GeneratedOrder(true, "Order saved. Its number and PDF are made on the next sync.")
        else GeneratedOrder(true, "Order $offerNo saved", saved.pdf, offerNo)
    }

    /**
     * A fresh render of the order as it is now, in the app cache. The PDF saved earlier is never
     * reused because a pulled edit or a duplicate renumber updates the order without redrawing it.
     */
    internal suspend fun orderPdfForSharing(data: OrderFormData): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            File(dir, OrderFiles.pdfName(data.offerNo)).also { orderFiles.writePdf(data, it) }
        }.getOrNull()
    }

    internal fun shareOrderPdf(file: File, offerNo: String) =
        PdfShare.share(this, file, "Order $offerNo - Onkar Textile Agency", "Share order")

    /**
     * One-time pass: order PDFs saved before the letterhead marks became vectors embed ~9 MB of
     * bitmaps each. Re-renders those in place (same file name, same order data) so they take a few
     * KB. The sync JSON saved beside each PDF is the record it was drawn from, so that is what is
     * re-rendered (history entries pulled from the cloud have no local file path). A file is only
     * replaced after its new version was written successfully; files this install can't write
     * (e.g. left by an earlier install) are skipped.
     */
    internal fun shrinkOldOrderPdfs() {
        val prefs = getSharedPreferences("OTA_Prefs", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_PDFS_SHRUNK, false)) return
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "OTA Orders")
        var ok = true
        root.walkTopDown().filter { it.isFile && it.extension == "json" && it.parentFile?.name == "SyncData" }.forEach { json ->
            val record = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return@forEach
            val pdfName = record.optString("pdfFileName")
            if (pdfName.isBlank()) return@forEach
            val pdf = File(json.parentFile?.parentFile, pdfName)
            if (!pdf.isFile || pdf.length() < 1_000_000L || !pdf.canWrite()) return@forEach
            localOrders.history.locked {
                // Named without "Order" so a leftover never counts toward the daily file index.
                val tmp = File(pdf.parentFile, ".shrink.tmp").apply { delete() }
                runCatching {
                    orderFiles.writePdf(OrderJson.parse(record), tmp)
                    check(tmp.length() in 1 until pdf.length())
                    check(tmp.renameTo(pdf))
                }.onFailure { e -> e.printStackTrace(); tmp.delete(); ok = false }
            }
        }
        if (ok) prefs.edit().putBoolean(PREF_PDFS_SHRUNK, true).apply()
    }


}


@Composable
private fun AppDrawer(currentScreen: Screen, cloudStatus: CloudStatus, onNavigate: (Screen) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primary)
                .padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
                contentAlignment = Alignment.Center
            ) {
                Text("OTA", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text(text = "ONKAR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimary)
            Text(text = "TEXTILE AGENCY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f))
            Spacer(Modifier.height(4.dp))
            Text(text = "OTA Business", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f))
            if (cloudStatus.configured) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (cloudStatus.signedIn) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = cloudStatus.email ?: "Not signed in",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        drawerGroups.forEach { (group, screens) ->
            if (group != null) {
                Text(
                    group.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)
                )
            }
            screens.forEach { screen -> NavigationDrawerItem(
                label = { Text(if (screen == Screen.Home) screen.tabLabel else screen.title, fontWeight = FontWeight.SemiBold) },
                selected = currentScreen == screen,
                onClick = { onNavigate(screen) },
                icon = { Icon(screenIcons.getValue(screen), contentDescription = null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) }
        }
    }
}

@Composable
fun SyncControlScreen(totalOrders: Int?, cloud: CloudSync? = null, appVersion: String? = null, onCheckUpdates: (() -> Unit)? = null) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("OTA_Prefs", Context.MODE_PRIVATE) }
    var deviceId by remember { mutableStateOf(sharedPrefs.getString("device_id", "") ?: "") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.padding(top = 8.dp).size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(text = "LAPTOP SYNCHRONIZATION DATA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing)

        if (cloud != null) CloudAccountSection(cloud)

        FormSection(title = "Sync Status", icon = Icons.Default.CloudUpload) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Total Orders Ready for Sync",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                AnimatedContent(
                    targetState = totalOrders,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "syncCount"
                ) { count ->
                    if (count == null) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Text(text = "$count", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        FormSection(title = "Device", icon = Icons.Default.PhoneAndroid) {
            FormTextField(
                value = deviceId,
                onValueChange = { deviceId = it; sharedPrefs.edit().putString("device_id", it).apply() },
                label = "Device / Salesman Identifier",
                placeholder = "e.g., Phone_A, Sales_Onkar",
                icon = Icons.Default.Badge,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            )
        }

        if (onCheckUpdates != null) {
            FormSection(title = "App updates", icon = Icons.Default.Refresh) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("OTA Business ${appVersion.orEmpty()}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "New versions install from GitHub, keeping all data on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onCheckUpdates) { Text("Check") }
                }
            }
        }

        FormSection(title = "Weather on Home", icon = Icons.Default.LocationOn) {
            com.example.otaapplication.weather.WeatherSettingsSection()
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "System Architecture: Live JSON Autogeneration Active. Connect to laptop to pull data safely.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(text = "Your laptop software will read this clean data directly when connected. Please ensure all orders are generated before initiating the synchronization process from your laptop application.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
