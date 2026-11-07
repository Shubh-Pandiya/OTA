package com.example.otaapplication

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
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
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.HistoryIndex
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.ui.theme.OTAApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
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

enum class Screen(val title: String) {
    OrderEntry("Order Entry"),
    OrderHistory("Order History"),
    Catalog("Buyers & Suppliers"),
    SyncSettings("Sync Settings")
}

private const val KEY_ORDER_SERIAL_DATE = "order_serial_date"
private const val KEY_ORDER_SERIAL = "order_serial"

private val screenIcons = mapOf(
    Screen.OrderEntry to Icons.Default.EditNote,
    Screen.OrderHistory to Icons.Default.History,
    Screen.Catalog to Icons.Default.Contacts,
    Screen.SyncSettings to Icons.Default.Settings
)

// Material "fade through" used for top-level destination changes.
private fun AnimatedContentTransitionScope<Screen>.fadeThrough(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220, delayMillis = 90)))
        .togetherWith(fadeOut(tween(90)))

class MainActivity : ComponentActivity() {

    private val orderNumberLock = Any()

    internal val catalogRepository by lazy {
        CatalogRepository(OtaDatabase.get(this)).also { repo ->
            // Upload catalog edits shortly after they are made (debounced).
            repo.onLocalChange = { cloud.requestSync(delayMillis = 1_500) }
        }
    }
    private val cloud by lazy { CloudSync.get(this) }
    internal val orderRepository by lazy { OrderRepository(OtaDatabase.get(this)) }

    // Bumped on every resume so the previewed order number follows a change of day.
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Catalog sync on app start / return to foreground (throttled to once a minute).
        cloud.onAppForeground()
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
        lifecycleScope.launch(Dispatchers.IO) { syncOrderIndex() }
        setContent {
            OTAApplicationTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by rememberSaveable { mutableStateOf(Screen.OrderEntry) }

                var formData by remember { mutableStateOf(OrderFormData()) }
                var focusedOrderState by remember { mutableStateOf<OrderFormData?>(null) }
                var isGenerating by remember { mutableStateOf(false) }
                var nextOfferNo by remember { mutableStateOf("") }
                var offerNoRefresh by remember { mutableIntStateOf(0) }
                val resumed = resumeTick
                val cloudStatus by cloud.status.collectAsState()
                val remoteCatalogChanges by cloud.catalogChanges.collectAsState()

                LaunchedEffect(currentScreen, offerNoRefresh, resumed) {
                    if (currentScreen == Screen.OrderEntry) {
                        nextOfferNo = withContext(Dispatchers.IO) { peekNextOrderNumber() }
                    }
                }

                fun navigate(screen: Screen) {
                    currentScreen = screen
                    scope.launch { drawerState.close() }
                }

                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
                BackHandler(enabled = drawerState.isClosed && currentScreen != Screen.OrderEntry) { currentScreen = Screen.OrderEntry }

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
                                Screen.OrderEntry -> OrderFormScreen(
                                    // New orders show the next auto-generated number; orders reopened
                                    // from history keep their original one.
                                    formData = focusedOrderState ?: formData.copy(offerNo = nextOfferNo),
                                    onFormChange = { if (focusedOrderState != null) focusedOrderState = it else formData = it },
                                    isNewOrder = focusedOrderState == null,
                                    isGenerating = isGenerating,
                                    catalog = catalogRepository,
                                    onGeneratePdf = { data ->
                                        if (!isGenerating) {
                                            isGenerating = true
                                            val isNewOrder = focusedOrderState == null
                                            scope.launch {
                                                val (ok, message) = withContext(Dispatchers.IO) {
                                                    (if (isNewOrder) generateNewOrderPdf(data) else generateOrderPdf(data))
                                                        .also { if (it.first) syncOrderIndex() }
                                                }
                                                Toast.makeText(this@MainActivity, message, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                                                if (ok) {
                                                    focusedOrderState = null
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
                                    onOrderFocused = { selected ->
                                        focusedOrderState = selected
                                        Toast.makeText(this@MainActivity, "Order Focused: ${selected.offerNo}", Toast.LENGTH_SHORT).show()
                                        currentScreen = Screen.OrderEntry
                                    }
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
        }
    }

    private fun jsonFileRoot(jsonRoot: JSONObject, data: OrderFormData) {
        jsonRoot.put("date", data.date); jsonRoot.put("offerNo", data.offerNo); jsonRoot.put("buyer", data.buyer)
        jsonRoot.put("supplier", data.supplier); jsonRoot.put("remarks", data.remarks); jsonRoot.put("deliveryReady", data.deliveryReady)
        jsonRoot.put("transportTo", data.transportTo); jsonRoot.put("cashPayment", data.cashPayment); jsonRoot.put("lessDisc", data.lessDisc); jsonRoot.put("side", data.side)
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

    @Synchronized
    private fun writeToInternalHistoryCache(orderRoot: JSONObject) {
        try {
            val historyFile = File(filesDir, "internal_order_history_db.json")
            val historyArray = if (historyFile.exists()) {
                val content = historyFile.readText()
                if (content.isNotBlank()) JSONArray(content) else JSONArray()
            } else JSONArray()
            val historyEntry = JSONObject()
            jsonFileRoot(historyEntry, parseOrderFromJsonObject(orderRoot))
            historyEntry.put("items", orderRoot.optJSONArray("items"))
            historyEntry.put("filePath", orderRoot.optString("filePath"))
            historyEntry.put("pdfFileName", orderRoot.optString("pdfFileName"))
            historyEntry.put("uuid", orderRoot.optString("uuid"))
            historyArray.put(historyEntry)
            val writer = FileWriter(historyFile); writer.write(historyArray.toString()); writer.close()
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    internal fun loadOrderHistory(): List<OrderHistoryItem> {
        val historyList = mutableListOf<OrderHistoryItem>()
        try {
            val historyFile = File(filesDir, "internal_order_history_db.json")
            if (historyFile.exists()) {
                val content = historyFile.readText()
                if (content.isNotBlank()) {
                    val array = JSONArray(content)
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
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return historyList
    }

    /** Runs on a background thread; mirrors the history file into the search index. */
    internal fun syncOrderIndex(): Boolean {
        val text = synchronized(this) {
            val historyFile = File(filesDir, "internal_order_history_db.json")
            if (historyFile.exists()) historyFile.readText() else ""
        }
        val entries = runCatching { HistoryIndex.parse(text) }.getOrElse { it.printStackTrace(); return false }
        return runCatching { orderRepository.reconcile(entries) }.getOrElse { it.printStackTrace(); false }
    }

    @Synchronized
    internal fun getOrderFromHistory(uuid: String?): OrderFormData? {
        if (uuid == null) return null
        try {
            val historyFile = File(filesDir, "internal_order_history_db.json")
            if (historyFile.exists()) {
                val array = JSONArray(historyFile.readText())
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    if (json.optString("uuid") == uuid) return parseOrderFromJsonObject(json)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private fun parseOrderFromJsonObject(json: JSONObject): OrderFormData {
        val itemsArray = json.optJSONArray("items") ?: JSONArray()
        val itemsList = mutableListOf<OrderItem>()
        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.getJSONObject(i)
            itemsList.add(OrderItem(itemObj.optString("serialNo", ""), itemObj.optString("particulars", ""), itemObj.optString("quantity", ""), itemObj.optString("rate", "")))
        }
        return OrderFormData(json.optString("date", ""), json.optString("offerNo", ""), json.optString("buyer", ""), json.optString("supplier", ""), itemsList, json.optString("remarks", ""), json.optString("deliveryReady", ""), json.optString("transportTo", ""), json.optString("cashPayment", ""), json.optString("lessDisc", ""), json.optString("side", ""))
    }

    @Synchronized
    internal fun deleteOrderPermanently(uuid: String?, syncFilePath: String, pdfName: String?): Boolean {
        var success = true
        try {
            val historyFile = File(filesDir, "internal_order_history_db.json")
            if (historyFile.exists()) {
                val array = JSONArray(historyFile.readText()); val newArray = JSONArray()
                for (i in 0 until array.length()) { val json = array.getJSONObject(i); if (json.optString("uuid") != uuid) newArray.put(json) }
                val writer = FileWriter(historyFile); writer.write(newArray.toString()); writer.close()
            }
        } catch (e: Exception) { e.printStackTrace(); success = false }
        try {
            val jsonFile = File(syncFilePath); if (jsonFile.exists()) jsonFile.delete()
            if (pdfName != null) {
                val pdfFile = File(jsonFile.parentFile?.parentFile, pdfName)
                if (pdfFile.exists()) pdfFile.delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
        return success
    }

    private fun createStaticLayout(text: String, width: Int, textPaint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).setIncludePad(false).build()
    }

    private fun nextOrderSerial(prefix: String): Int {
        val prefs = getSharedPreferences("OTA_Prefs", MODE_PRIVATE)
        return OrderNumber.nextSerial(
            prefix = prefix,
            storedPrefix = prefs.getString(KEY_ORDER_SERIAL_DATE, null),
            storedSerial = prefs.getInt(KEY_ORDER_SERIAL, 0),
            existingOfferNos = loadOrderHistory().map { it.offerNo }
        )
    }

    /** Runs on a background thread; the number a new order will get if saved now. */
    private fun peekNextOrderNumber(): String = synchronized(orderNumberLock) {
        val prefix = OrderNumber.prefix(LocalDate.now())
        OrderNumber.format(prefix, nextOrderSerial(prefix))
    }

    /**
     * Runs on a background thread. Assigns today's next order number at save time and only
     * consumes it when the PDF was written, so abandoned or failed attempts don't use up numbers.
     */
    private fun generateNewOrderPdf(data: OrderFormData): Pair<Boolean, String> = synchronized(orderNumberLock) {
        val prefix = OrderNumber.prefix(LocalDate.now())
        val serial = nextOrderSerial(prefix)
        val offerNo = OrderNumber.format(prefix, serial)
        val (ok, message) = generateOrderPdf(data.copy(offerNo = offerNo))
        if (ok) {
            getSharedPreferences("OTA_Prefs", MODE_PRIVATE).edit()
                .putString(KEY_ORDER_SERIAL_DATE, prefix)
                .putInt(KEY_ORDER_SERIAL, serial)
                .commit()
            true to "Order $offerNo saved. $message"
        } else {
            false to message
        }
    }

    /** Runs on a background thread; returns (success, user message). */
    private fun generateOrderPdf(data: OrderFormData): Pair<Boolean, String> {
        val now = LocalDateTime.now(); val year = now.year.toString(); val monthName = now.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val sharedPrefs = getSharedPreferences("OTA_Prefs", MODE_PRIVATE)
        val deviceId = sharedPrefs.getString("device_id", "")?.takeIf { it.isNotBlank() } ?: "UnknownDevice"
        val uniqueId = java.util.UUID.randomUUID().toString()
        val bhagvaOrange = android.graphics.Color.parseColor("#FF6F00")
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val otaDir = File(baseDir, "OTA Orders/$year/$monthName")
        if (!otaDir.exists()) otaDir.mkdirs()
        val ordersToday = otaDir.listFiles { file -> file.name.contains("Order") && file.name.contains(dateStamp) }?.size ?: 0
        val nextIndex = ordersToday + 1
        val fileName = "Order_${nextIndex}_${dateStamp}_${deviceId}.pdf"
        val pdfFile = File(otaDir, fileName)
        try {
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
            val decodeOptions = BitmapFactory.Options().apply { inScaled = false }
            val logo = BitmapFactory.decodeResource(resources, R.drawable.ic_logo, decodeOptions)
            if (logo != null) {
                val logoHeight = 60f; val logoWidth = (logo.width.toFloat() / logo.height.toFloat()) * logoHeight
                canvas.drawBitmap(logo, null, RectF(40f, 40f, 40f + logoWidth, 40f + logoHeight), paint)
            }
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
                val partL = createStaticLayout(item.particulars, 210, textPaint); val qtyL = createStaticLayout(item.quantity, 75, textPaint); val rateL = createStaticLayout(item.rate, 60, textPaint); val rowH = maxOf(20f, partL.height.toFloat(), qtyL.height.toFloat(), rateL.height.toFloat()) + 10f
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
            val sigImg = BitmapFactory.decodeResource(resources, R.drawable.ic_signature, decodeOptions)
            if (sigImg != null) { canvas.drawBitmap(sigImg, null, RectF(410f, sigY - 105f, 510f, sigY - 5f), paint) }
            pdfDocument.finishPage(page); val outputStream = FileOutputStream(pdfFile); pdfDocument.writeTo(outputStream); pdfDocument.close(); outputStream.close()
            try {
                val syncDir = File(otaDir, "SyncData"); if (!syncDir.exists()) syncDir.mkdirs()
                val jsonFileName = "Order_${deviceId}_${uniqueId}.json"; val jsonFile = File(syncDir, jsonFileName); val jsonRoot = JSONObject(); jsonFileRoot(jsonRoot, data); jsonRoot.put("deviceId", deviceId); jsonRoot.put("uuid", uniqueId); jsonRoot.put("pdfFileName", fileName); jsonRoot.put("filePath", jsonFile.absolutePath)
                val itemsArray = JSONArray()
                for (item in data.items) { val itemObj = JSONObject()
                    itemObj.put("serialNo", item.serialNo); itemObj.put("particulars", item.particulars); itemObj.put("quantity", item.quantity); itemObj.put("rate", item.rate); itemsArray.put(itemObj) }
                jsonRoot.put("items", itemsArray); val writer = FileWriter(jsonFile); writer.write(jsonRoot.toString()); writer.close(); writeToInternalHistoryCache(jsonRoot)
            } catch (e: Exception) { e.printStackTrace() }
            return true to "PDF Generated: ${pdfFile.absolutePath}"
        } catch (e: Exception) { e.printStackTrace(); return false to "Error: ${e.message}" }
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
        Screen.entries.forEach { screen ->
            NavigationDrawerItem(
                label = { Text(screen.title, fontWeight = FontWeight.SemiBold) },
                selected = currentScreen == screen,
                onClick = { onNavigate(screen) },
                icon = { Icon(screenIcons.getValue(screen), contentDescription = null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
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
