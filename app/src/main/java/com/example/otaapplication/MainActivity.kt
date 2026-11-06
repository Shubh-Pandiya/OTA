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
    SyncSettings("Sync Settings")
}

private val screenIcons = mapOf(
    Screen.OrderEntry to Icons.Default.EditNote,
    Screen.OrderHistory to Icons.Default.History,
    Screen.SyncSettings to Icons.Default.Settings
)

// Material "fade through" used for top-level destination changes.
private fun AnimatedContentTransitionScope<Screen>.fadeThrough(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220, delayMillis = 90)))
        .togetherWith(fadeOut(tween(90)))

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light-only app: keep dark system-bar icons even when the device is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            OTAApplicationTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by rememberSaveable { mutableStateOf(Screen.OrderEntry) }

                var formData by remember { mutableStateOf(OrderFormData()) }
                var focusedOrderState by remember { mutableStateOf<OrderFormData?>(null) }
                var isGenerating by remember { mutableStateOf(false) }

                fun navigate(screen: Screen) {
                    currentScreen = screen
                    scope.launch { drawerState.close() }
                }

                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
                BackHandler(enabled = drawerState.isClosed && currentScreen != Screen.OrderEntry) { currentScreen = Screen.OrderEntry }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = { AppDrawer(currentScreen = currentScreen, onNavigate = ::navigate) }
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
                                    formData = focusedOrderState ?: formData,
                                    onFormChange = { if (focusedOrderState != null) focusedOrderState = it else formData = it },
                                    isGenerating = isGenerating,
                                    onGeneratePdf = { data ->
                                        if (!isGenerating) {
                                            isGenerating = true
                                            scope.launch {
                                                val (ok, message) = withContext(Dispatchers.IO) { generateOrderPdf(data) }
                                                Toast.makeText(this@MainActivity, message, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                                                isGenerating = false
                                            }
                                        }
                                    }
                                )
                                Screen.OrderHistory -> OrderHistoryScreen(
                                    activity = this@MainActivity,
                                    onOrderFocused = { selected ->
                                        focusedOrderState = selected
                                        Toast.makeText(this@MainActivity, "Order Focused: ${selected.offerNo}", Toast.LENGTH_SHORT).show()
                                        currentScreen = Screen.OrderEntry
                                    }
                                )
                                Screen.SyncSettings -> {
                                    val totalOrders by produceState<Int?>(initialValue = null) {
                                        value = withContext(Dispatchers.IO) { countTotalOrders() }
                                    }
                                    SyncControlScreen(totalOrders = totalOrders)
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
private fun AppDrawer(currentScreen: Screen, onNavigate: (Screen) -> Unit) {
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

private val historyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

private fun historyDateKey(date: String): LocalDate =
    runCatching { LocalDate.parse(date, historyDateFormat) }.getOrDefault(LocalDate.MIN)

/** Sorts by the real calendar date (string sort put 01-12 before 31-01); same-day orders keep save order. */
private fun sortHistory(items: List<OrderHistoryItem>, newestFirst: Boolean): List<Pair<String, OrderHistoryItem>> {
    val uuids = items.mapNotNull { it.uuid?.takeIf(String::isNotBlank) }
    val uuidsUnique = uuids.size == items.size && uuids.toSet().size == uuids.size
    val keyed = items.mapIndexed { index, item ->
        Triple(if (uuidsUnique) "u:${item.uuid}" else "f:${item.fileName}", index, item)
    }
    val comparator = compareBy<Triple<String, Int, OrderHistoryItem>>({ historyDateKey(it.third.date) }, { it.second })
    val sorted = if (newestFirst) keyed.sortedWith(comparator.reversed()) else keyed.sortedWith(comparator)
    return sorted.map { it.first to it.third }
}

private sealed interface DetailLoad {
    data object Loading : DetailLoad
    data class Loaded(val data: OrderFormData?) : DetailLoad
}

@Composable
fun OrderHistoryScreen(activity: MainActivity, onOrderFocused: (OrderFormData) -> Unit) {
    val scope = rememberCoroutineScope()
    var historyItems by remember { mutableStateOf<List<OrderHistoryItem>?>(null) }
    var isNewestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedItemForDetail by remember { mutableStateOf<OrderHistoryItem?>(null) }
    val listState = rememberLazyListState()

    suspend fun refreshHistory() {
        historyItems = withContext(Dispatchers.IO) { activity.loadOrderHistory() }
    }
    LaunchedEffect(Unit) { refreshHistory() }

    val sortedItems = remember(historyItems, isNewestFirst) { sortHistory(historyItems.orEmpty(), isNewestFirst) }

    BackHandler(enabled = selectedItemForDetail != null) { selectedItemForDetail = null }

    AnimatedContent(
        targetState = selectedItemForDetail,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
            } else {
                fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
            }
        },
        contentKey = { it?.fileName },
        label = "historyDetail"
    ) { selected ->
        if (selected == null) {
            HistoryList(
                historyItems = historyItems,
                sortedItems = sortedItems,
                isNewestFirst = isNewestFirst,
                onToggleSort = { isNewestFirst = !isNewestFirst },
                listState = listState,
                onItemClick = { selectedItemForDetail = it }
            )
        } else {
            val load by produceState<DetailLoad>(initialValue = DetailLoad.Loading, selected.uuid) {
                value = DetailLoad.Loaded(withContext(Dispatchers.IO) { activity.getOrderFromHistory(selected.uuid) })
            }
            OrderDetailScreen(
                load = load,
                onDismiss = { selectedItemForDetail = null },
                onDelete = {
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            activity.deleteOrderPermanently(selected.uuid, selected.filePath, selected.pdfFileName)
                        }
                        if (success) {
                            Toast.makeText(activity, "Data Erased Successfully", Toast.LENGTH_SHORT).show()
                            refreshHistory()
                            selectedItemForDetail = null
                        } else {
                            Toast.makeText(activity, "Error deleting files", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFocus = { data -> onOrderFocused(data); selectedItemForDetail = null }
            )
        }
    }
}

@Composable
private fun HistoryList(
    historyItems: List<OrderHistoryItem>?,
    sortedItems: List<Pair<String, OrderHistoryItem>>,
    isNewestFirst: Boolean,
    onToggleSort: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onItemClick: (OrderHistoryItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "BOOKED ORDER LOGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedContent(
                    targetState = historyItems?.size,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "count"
                ) { count ->
                    Text(
                        text = if (count == null) "Loading…" else "Total Orders: $count",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            val arrowRotation by animateFloatAsState(if (isNewestFirst) 0f else 180f, tween(250), label = "sortArrow")
            FilterChip(
                selected = true,
                onClick = onToggleSort,
                label = {
                    AnimatedContent(
                        targetState = isNewestFirst,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                        label = "sortLabel"
                    ) { newest -> Text(text = if (newest) "Newest First" else "Oldest First") }
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp).rotate(arrowRotation))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        val state = when {
            historyItems == null -> 0
            sortedItems.isEmpty() -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.fillMaxSize(),
            label = "historyState"
        ) { s ->
            when (s) {
                0 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                1 -> EmptyHistory()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues().let {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = it.calculateBottomPadding() + 16.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sortedItems, key = { it.first }) { (_, item) ->
                        HistoryCard(item = item, onClick = { onItemClick(item) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "No Previous Orders Found", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "Your booked customer invoices will be securely tracked and logged here chronologically.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun HistoryCard(item: OrderHistoryItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.buyer.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Offer No: ${item.offerNo}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.buyer.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = item.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun OrderDetailScreen(load: DetailLoad, onDismiss: () -> Unit, onDelete: () -> Unit, onFocus: (OrderFormData) -> Unit) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val order = (load as? DetailLoad.Loaded)?.data

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Order Details", style = MaterialTheme.typography.titleLarge)
                if (order != null) {
                    Text(text = "Offer No ${order.offerNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when {
            load is DetailLoad.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            order == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Order data not found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DetailCard("General Information", Icons.Default.Description) {
                        DetailRow("Date", order.date); DetailRow("Offer No", order.offerNo); DetailRow("Buyer", order.buyer); DetailRow("Supplier", order.supplier)
                    }
                    DetailCard("Items List", Icons.Default.Inventory2) {
                        order.items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item.serialNo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = item.particulars, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(text = "Qty: ${item.quantity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = "Rate: ${item.rate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    DetailCard("Terms & Logistics", Icons.Default.LocalShipping) {
                        DetailRow("Delivery", order.deliveryReady); DetailRow("Transport", order.transportTo); DetailRow("Payment", order.cashPayment); DetailRow("Less/Disc", order.lessDisc); DetailRow("Side", order.side)
                    }
                    DetailCard("Remarks", Icons.Default.RateReview) {
                        Text(
                            text = order.remarks.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (order.remarks.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Delete")
                        }
                        Button(onClick = { onFocus(order) }, modifier = Modifier.weight(1f).height(50.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Reuse / Edit")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation && order != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Permanently Delete?") },
            text = { Text("Are you absolutely sure you want to permanently delete Offer No ${order.offerNo}? This action will completely erase the sync record from your system storage.") },
            confirmButton = { TextButton(onClick = { showDeleteConfirmation = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("PERMANENTLY DELETE") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun DetailCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    FormSection(title = title, icon = icon) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (value.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
fun SyncControlScreen(totalOrders: Int?) {
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
