package com.example.otaapplication

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.otaapplication.cloud.CloudStatus
import com.example.otaapplication.cloud.CloudSync
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
import kotlinx.coroutines.Dispatchers
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
    null to listOf(Screen.Home),
    "Orders" to listOf(Screen.OrderEntry, Screen.OrderHistory),
    "Payments" to listOf(Screen.Bills, Screen.Advices),
    "Manage" to listOf(Screen.Catalog, Screen.SyncSettings)
)

private val screenIcons = mapOf(
    Screen.Home to Icons.Default.Home,
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
    private val numberAllocator by lazy {
        OrderNumberAllocator(getSharedPreferences(OrderNumberAllocator.PREFS, MODE_PRIVATE), localOrders.history, localOrders.index)
    }
    private val payments by lazy { PaymentRepository.get(this) }
    private val adviceNumbers by lazy {
        AdviceNumberAllocator(getSharedPreferences(OrderNumberAllocator.PREFS, MODE_PRIVATE), payments)
    }

    // Bumped on every resume so the previewed order number follows a change of day.
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
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
        var nextOfferNo by remember { mutableStateOf("") }
        var offerNoRefresh by remember { mutableIntStateOf(0) }
        val resumed = resumeTick
        val cloudStatus by cloud.status.collectAsState()
        val remoteCatalogChanges by cloud.catalogChanges.collectAsState()
        val remoteOrderChanges by cloud.orderChanges.collectAsState()
        val staff by cloud.staff.collectAsState()
        val staffCode = staff?.code
        val paymentDeps = remember(staffCode, cloudStatus.signedIn) {
            PaymentDeps(orderRepository, payments, catalogRepository, adviceNumbers, staffCode, staffCodeMessage(cloudStatus.signedIn).replace("order number", "advice number"))
        }

        LaunchedEffect(currentScreen, offerNoRefresh, resumed, staffCode, remoteOrderChanges) {
            if (currentScreen == Screen.OrderEntry) {
                nextOfferNo = staffCode?.let { code -> withContext(Dispatchers.IO) { numberAllocator.peek(code) } }.orEmpty()
            }
        }

        fun navigate(screen: Screen) {
            currentScreen = screen
            scope.launch { drawerState.close() }
        }

        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
        BackHandler(enabled = drawerState.isClosed && currentScreen != Screen.Home) { currentScreen = Screen.Home }

        val homeStats by produceState<HomeStats?>(null, currentScreen == Screen.Home, resumed, remoteOrderChanges, cloudStatus.lastSyncAt) {
            if (currentScreen == Screen.Home) value = withContext(Dispatchers.IO) { runCatching { loadHomeStats() }.onFailure { it.printStackTrace() }.getOrNull() } ?: value
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
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
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
                                    onClick = { currentScreen = tab },
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
                            actions = remember {
                                HomeActions(
                                    newOrder = { currentScreen = Screen.OrderEntry },
                                    newAdvice = { pendingPage = PaymentPage.AdviceEdit(null); currentScreen = Screen.Advices },
                                    orders = { currentScreen = Screen.OrderHistory },
                                    bills = { currentScreen = Screen.Bills },
                                    advices = { currentScreen = Screen.Advices },
                                    parties = { currentScreen = Screen.Catalog },
                                    openBill = { pendingPage = PaymentPage.BillView(it); currentScreen = Screen.Bills },
                                    openAdvice = { pendingPage = PaymentPage.AdviceView(it); currentScreen = Screen.Advices },
                                    sync = { scope.launch { cloud.syncNow() } }
                                )
                            }
                        )
                        Screen.OrderEntry -> OrderFormScreen(
                            // New orders show the next auto-generated number; orders reopened
                            // from history keep their original one.
                            formData = focusedOrderState ?: formData.copy(offerNo = nextOfferNo),
                            onFormChange = { if (focusedOrderState != null) focusedOrderState = it else formData = it },
                            isNewOrder = focusedOrderState == null,
                            isGenerating = isGenerating,
                            catalog = catalogRepository,
                            numberMissingMessage = if (focusedOrderState == null && staffCode == null) staffCodeMessage(cloudStatus.signedIn) else null,
                            onOpenSyncSettings = { currentScreen = Screen.SyncSettings },
                            onGeneratePdf = { data ->
                                val isNewOrder = focusedOrderState == null
                                val code = staffCode
                                if (isNewOrder && code == null) {
                                    Toast.makeText(this@MainActivity, staffCodeMessage(cloudStatus.signedIn), Toast.LENGTH_LONG).show()
                                } else if (!isGenerating) {
                                    isGenerating = true
                                    val existingUuid = focusedUuid
                                    scope.launch {
                                        val (ok, message) = withContext(Dispatchers.IO) {
                                            if (isNewOrder) generateNewOrderPdf(data, code!!) else generateOrderPdf(data, existingUuid)
                                        }
                                        Toast.makeText(this@MainActivity, message, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                                        if (ok) {
                                            focusedOrderState = null
                                            focusedUuid = null
                                            formData = OrderFormData()
                                            offerNoRefresh++
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
                            SyncControlScreen(totalOrders = totalOrders, cloud = cloud)
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

    private fun createStaticLayout(text: String, width: Int, textPaint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).setIncludePad(false).build()
    }

    private fun staffCodeMessage(signedIn: Boolean): String =
        if (signedIn) "Your login has no order number code yet. Ask the owner to add it, then tap Sync now in Sync Settings."
        else "Sign in once in Sync Settings to get your order numbers."

    /**
     * Runs on a background thread. Assigns this staff member's next order number at save time and
     * only consumes it when the PDF was written, so abandoned or failed attempts don't use up numbers.
     */
    private fun generateNewOrderPdf(data: OrderFormData, staffCode: Int): Pair<Boolean, String> {
        var result = false to "Could not save the order"
        val offerNo = numberAllocator.assign(staffCode) { number ->
            result = generateOrderPdf(data.copy(offerNo = number), existingUuid = null)
            result.first
        }
        return if (offerNo != null) true to "Order $offerNo saved. ${result.second}" else result
    }

    /**
     * Runs on a background thread; returns (success, user message). With [existingUuid] the saved
     * order is updated in place (same uuid, bills kept) and its old files are replaced.
     */
    private fun generateOrderPdf(data: OrderFormData, existingUuid: String?): Pair<Boolean, String> = localOrders.history.locked {
        val now = LocalDateTime.now(); val year = now.year.toString(); val monthName = now.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val sharedPrefs = getSharedPreferences("OTA_Prefs", MODE_PRIVATE)
        val deviceId = sharedPrefs.getString("device_id", "")?.takeIf { it.isNotBlank() } ?: "UnknownDevice"
        val uniqueId = existingUuid ?: java.util.UUID.randomUUID().toString()
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val otaDir = File(baseDir, "OTA Orders/$year/$monthName")
        if (!otaDir.exists()) otaDir.mkdirs()
        val ordersToday = otaDir.listFiles { file -> file.name.contains("Order") && file.name.contains(dateStamp) }?.size ?: 0
        val pdfFile = PublicFiles.orderPdf(otaDir, ordersToday + 1) { "Order_${it}_${dateStamp}_${deviceId}.pdf" }
        val fileName = pdfFile.name
        try {
            writeOrderPdf(data, pdfFile)
            try {
                val syncDir = File(otaDir, "SyncData"); if (!syncDir.exists()) syncDir.mkdirs()
                val jsonFileName = "Order_${deviceId}_${uniqueId}.json"; val jsonFile = File(syncDir, jsonFileName)
                val jsonRoot = OrderJson.putFields(JSONObject(), data)
                jsonRoot.put("deviceId", deviceId); jsonRoot.put("uuid", uniqueId); jsonRoot.put("pdfFileName", fileName); jsonRoot.put("filePath", jsonFile.absolutePath)
                jsonRoot.put("items", OrderJson.itemsArray(data.items)); jsonFile.writeText(jsonRoot.toString())
                val previous = existingUuid?.let { localOrders.history.find(it) }
                localOrders.history.upsert(OrderJson.historyEntry(data, uniqueId, jsonFile.absolutePath, fileName))
                localOrders.reindex()
                if (previous != null) removeReplacedFiles(previous, jsonFile, pdfFile)
            } catch (e: Exception) { e.printStackTrace() }
            return@locked true to "PDF Generated: ${pdfFile.absolutePath}"
        } catch (e: Exception) { e.printStackTrace(); if (pdfFile.length() == 0L) pdfFile.delete(); return@locked false to "Error: ${e.message}" }
    }

    /** Draws the order form PDF into [pdfFile]. Throws if it can't be written. */
    private fun writeOrderPdf(data: OrderFormData, pdfFile: File) {
        val bhagvaOrange = android.graphics.Color.parseColor("#FF6F00")
        val pdfDocument = PdfDocument(); val paint = Paint(); val textPaint = TextPaint()
        var currentPageNumber = 1; var pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create()
        var page = pdfDocument.startPage(pageInfo); var canvas = page.canvas; var currentY: Float; var pageTableTop = 230f
        fun drawTableGrid(top: Float, bottom: Float) {
            paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.BLACK; paint.strokeWidth = 1f
            canvas.drawRect(40f, top, 555f, bottom, paint); canvas.drawLine(85f, top, 85f, bottom, paint); canvas.drawLine(300f, top, 300f, bottom, paint); canvas.drawLine(380f, top, 380f, bottom, paint); canvas.drawLine(445f, top, 445f, bottom, paint)
        }
        fun startNewPage() {
            drawTableGrid(pageTableTop, 780f); pdfDocument.finishPage(page); currentPageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create(); page = pdfDocument.startPage(pageInfo); canvas = page.canvas
            pageTableTop = 50f; currentY = pageTableTop; paint.style = Paint.Style.FILL; paint.color = bhagvaOrange
            canvas.drawRect(40f, currentY, 555f, currentY + 22f, paint); paint.color = android.graphics.Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f; paint.textAlign = Paint.Align.LEFT
            canvas.drawText("S.No", 45f, currentY + 15f, paint); canvas.drawText("PARTICULARS", 150f, currentY + 15f, paint); canvas.drawText("QTY", 330f, currentY + 15f, paint); canvas.drawText("RATES", 400f, currentY + 15f, paint); canvas.drawText("REMARKS / TERMS", 450f, currentY + 15f, paint); currentY += 22f
        }
        PdfMarks.drawLogo(this, canvas, RectF(40f, 40f, 100f, 100f))
        val centerX = 595f / 2f
        paint.textAlign = Paint.Align.RIGHT; paint.color = android.graphics.Color.BLACK; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); paint.textSize = 9f
        canvas.drawText("Mob.: 94250-57961", 555f, 45f, paint); canvas.drawText("94068-03061", 555f, 58f, paint)
        paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); paint.textSize = 18f; paint.color = bhagvaOrange
        canvas.drawText("ONKAR TEXTILE AGENCY", centerX, 55f, paint); paint.color = android.graphics.Color.BLACK; paint.textSize = 11f; canvas.drawText("TEXTILE INDENTING AGENT — SINCE 1991", centerX, 72f, paint); paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL); paint.textSize = 10f; canvas.drawText("290, M.T. Cloth Market, INDORE - 452 002 (M.P.)", centerX, 88f, paint); paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); paint.textSize = 12f; canvas.drawText("ORDER FORM", centerX, 112f, paint)
        paint.textAlign = Paint.Align.LEFT; paint.textSize = 11f; canvas.drawText("Offer No: ${data.offerNo}", 40f, 135f, paint); canvas.drawText("Date: ${data.date}", 450f, 135f, paint)
        paint.style = Paint.Style.STROKE; canvas.drawRect(40f, 145f, 290f, 215f, paint); canvas.drawRect(290f, 145f, 555f, 215f, paint); paint.style = Paint.Style.FILL; paint.typeface = Typeface.DEFAULT_BOLD; canvas.drawText("BUYER:", 45f, 160f, paint); canvas.drawText("SUPPLIER:", 295f, 160f, paint)
        textPaint.textSize = 10f; textPaint.typeface = Typeface.DEFAULT
        val buyerLayout = createStaticLayout(data.buyer, 240, textPaint); val supplierLayout = createStaticLayout(data.supplier, 255, textPaint)
        canvas.save(); canvas.translate(45f, 165f); buyerLayout.draw(canvas); canvas.restore(); canvas.save(); canvas.translate(295f, 165f); supplierLayout.draw(canvas); canvas.restore()
        pageTableTop = 230f; currentY = pageTableTop; paint.style = Paint.Style.FILL; paint.color = bhagvaOrange; canvas.drawRect(40f, currentY, 555f, currentY + 22f, paint); paint.color = android.graphics.Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f; canvas.drawText("S.No", 45f, currentY + 15f, paint); canvas.drawText("PARTICULARS", 150f, currentY + 15f, paint); canvas.drawText("QTY", 330f, currentY + 15f, paint); canvas.drawText("RATES", 400f, currentY + 15f, paint); canvas.drawText("REMARKS / TERMS", 450f, currentY + 15f, paint)
        val sidebarStartY = currentY + 22f; val sidebarTotalHeight = 200f; val sidebarCellHeight = sidebarTotalHeight / 5; val sidebarHeaders = listOf("DELIVERY READY", "TRANSPORT TO", "CASH PAYMENT", "LESS / DISC", "SIDE"); val sidebarValues = listOf(data.deliveryReady, data.transportTo, data.cashPayment, data.lessDisc, data.side)
        paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.BLACK
        for (i in 1..4) { val y = sidebarStartY + (i * sidebarCellHeight); canvas.drawLine(445f, y, 555f, y, paint) }
        paint.style = Paint.Style.FILL
        for (i in 0..4) {
            val y = sidebarStartY + (i * sidebarCellHeight); paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 8f; canvas.drawText(sidebarHeaders[i], 448f, y + 10f, paint)
            val valL = createStaticLayout(sidebarValues[i], 100, TextPaint().apply { textSize = 9f }); canvas.save(); canvas.translate(448f, y + 12f); valL.draw(canvas); canvas.restore()
        }
        currentY += 22f; val sidebarEndPage1 = sidebarStartY + sidebarTotalHeight
        for (item in data.items) {
            textPaint.textSize = 10f; textPaint.typeface = Typeface.DEFAULT; textPaint.color = android.graphics.Color.BLACK
            val partL = createStaticLayout(item.particulars, 210, textPaint); val qtyL = createStaticLayout(Units.display(item), 75, textPaint); val rateL = createStaticLayout(item.rate, 60, textPaint); val rowH = maxOf(20f, partL.height.toFloat(), qtyL.height.toFloat(), rateL.height.toFloat()) + 10f
            if (currentY + rowH > 780f) startNewPage()
            paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.BLACK; paint.typeface = Typeface.DEFAULT; paint.textSize = 10f; canvas.drawText(item.serialNo, 45f, currentY + 15f, paint)
            canvas.save(); canvas.translate(90f, currentY + 5f); partL.draw(canvas); canvas.restore(); canvas.save(); canvas.translate(305f, currentY + 5f); qtyL.draw(canvas); canvas.restore(); canvas.save(); canvas.translate(385f, currentY + 5f); rateL.draw(canvas); canvas.restore(); currentY += rowH; paint.style = Paint.Style.STROKE; paint.color = android.graphics.Color.BLACK; canvas.drawLine(40f, currentY, 445f, currentY, paint)
        }
        if (currentPageNumber == 1) currentY = maxOf(currentY, sidebarEndPage1)
        drawTableGrid(pageTableTop, currentY); currentY += 20f
        val warningText = "Self Bilty not accepted, compulsorily attach duplicate with R.R. or M.R."; val termsTitle = "Terms and Conditions :"; val rulesText = "1. Indent once given shall under no circumstances be cancelled by the Indenters.\n2. The Rates and time stated in Indore are subject to fluctuation and confirmation of suppliers who have right to alter the indent also.\n3. Executing or not executing the whole part of indent goods depend upon choice of the supplier, or agent of the company.\n4. Every consignment refused or not taken delivery of will be sold by publication and the indenter is responsible for this loss, damage and other charges must be incurred by the concerned."
        val remarksL = createStaticLayout(data.remarks, 500, TextPaint().apply { textSize = 10f }); val rulesL = createStaticLayout(rulesText, 515, TextPaint().apply { textSize = 10f }); val footerHeightNeeded = 25f + 25f + remarksL.height + 25f + rulesL.height + 120f
        if (currentY + footerHeightNeeded > 780f) { pdfDocument.finishPage(page); currentPageNumber++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, currentPageNumber).create(); page = pdfDocument.startPage(pageInfo); canvas = page.canvas; currentY = 60f }
        paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.BLACK; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); paint.textSize = 11f; paint.textAlign = Paint.Align.CENTER; canvas.drawText(warningText, 297.5f, currentY, paint); paint.textAlign = Paint.Align.LEFT; currentY += 25f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 11f; canvas.drawText("REMARKS / TERMS:", 40f, currentY, paint); currentY += 10f; canvas.save(); canvas.translate(40f, currentY); remarksL.draw(canvas); canvas.restore(); currentY += remarksL.height + 25f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f; canvas.drawText(termsTitle, 40f, currentY, paint); currentY += 5f; canvas.save(); canvas.translate(40f, currentY); rulesL.draw(canvas); canvas.restore(); currentY += rulesL.height
        val sigY = currentY + 120f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f; paint.style = Paint.Style.STROKE; canvas.drawLine(40f, sigY, 180f, sigY, paint); paint.style = Paint.Style.FILL; canvas.drawText("Signature of Indenter", 40f, sigY + 15f, paint); paint.style = Paint.Style.STROKE; canvas.drawLine(415f, sigY, 555f, sigY, paint); paint.style = Paint.Style.FILL; canvas.drawText("Agent's Signature", 445f, sigY + 15f, paint)
        PdfMarks.drawStamp(this, canvas, RectF(410f, sigY - 105f, 510f, sigY - 5f))
        pdfDocument.finishPage(page)
        try { FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) } } finally { pdfDocument.close() }
    }

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
                    writeOrderPdf(OrderJson.parse(record), tmp)
                    check(tmp.length() in 1 until pdf.length())
                    check(tmp.renameTo(pdf))
                }.onFailure { e -> e.printStackTrace(); tmp.delete(); ok = false }
            }
        }
        if (ok) prefs.edit().putBoolean(PREF_PDFS_SHRUNK, true).apply()
    }

    /** After an in-place update, deletes the order's previous sync JSON and PDF if they are different files. */
    private fun removeReplacedFiles(previous: JSONObject, newJson: File, newPdf: File) {
        val oldPath = previous.optString("filePath")
        if (oldPath.isBlank()) return
        val oldJson = File(oldPath)
        if (oldJson.absolutePath != newJson.absolutePath) oldJson.delete()
        val oldPdfName = if (previous.has("pdfFileName")) previous.optString("pdfFileName") else ""
        if (oldPdfName.isNotBlank()) {
            val oldPdf = File(oldJson.parentFile?.parentFile, oldPdfName)
            if (oldPdf.absolutePath != newPdf.absolutePath) oldPdf.delete()
        }
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
fun SyncControlScreen(totalOrders: Int?, cloud: CloudSync? = null) {
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
