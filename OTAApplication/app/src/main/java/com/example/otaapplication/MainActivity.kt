package com.example.otaapplication

import android.content.Context
import android.graphics.Bitmap
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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.otaapplication.ui.theme.OTAApplicationTheme
import kotlinx.coroutines.launch
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

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OTAApplicationTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf(Screen.OrderEntry) }

                var formData by remember { mutableStateOf(OrderFormData()) }
                var focusedOrderState by remember { mutableStateOf<OrderFormData?>(null) }

                val bhagvaOrangeCompose = Color(0xFFFF6F00)

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = {
                        ModalDrawerSheet {
                            Spacer(Modifier.height(12.dp))
                            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                                Text(text = "ONKAR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = bhagvaOrangeCompose)
                                Text(text = "TEXTILE AGENCY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = bhagvaOrangeCompose)
                                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                            }

                            NavigationDrawerItem(
                                label = { Text("Order Entry", fontWeight = FontWeight.SemiBold) },
                                selected = currentScreen == Screen.OrderEntry,
                                onClick = { currentScreen = Screen.OrderEntry; scope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            NavigationDrawerItem(
                                label = { Text("Order History", fontWeight = FontWeight.SemiBold) },
                                selected = currentScreen == Screen.OrderHistory,
                                onClick = { currentScreen = Screen.OrderHistory; scope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            NavigationDrawerItem(
                                label = { Text("Sync Settings", fontWeight = FontWeight.SemiBold) },
                                selected = currentScreen == Screen.SyncSettings,
                                onClick = { currentScreen = Screen.SyncSettings; scope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text(text = currentScreen.title, fontWeight = FontWeight.Bold) },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                }
                            )
                        },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) { innerPadding ->
                        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding), color = MaterialTheme.colorScheme.background) {
                            when (currentScreen) {
                                Screen.OrderEntry -> OrderFormScreen(
                                    formData = focusedOrderState ?: formData,
                                    onFormChange = { if (focusedOrderState != null) focusedOrderState = it else formData = it },
                                    onGeneratePdf = { data -> generateOrderPdf(data) }
                                )
                                Screen.OrderHistory -> OrderHistoryScreen(
                                    activity = this@MainActivity,
                                    onOrderFocused = { selected ->
                                        focusedOrderState = selected
                                        Toast.makeText(this@MainActivity, "Order Focused: ${selected.offerNo}", Toast.LENGTH_SHORT).show()
                                        currentScreen = Screen.OrderEntry
                                    }
                                )
                                Screen.SyncSettings -> SyncControlScreen(totalOrders = countTotalOrders())
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

    private fun generateOrderPdf(data: OrderFormData) {
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
            Toast.makeText(this, "PDF Generated: ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { e.printStackTrace(); Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
fun OrderHistoryScreen(activity: MainActivity, onOrderFocused: (OrderFormData) -> Unit) {
    val historyItems = remember { mutableStateListOf<OrderHistoryItem>() }
    var isLoading by remember { mutableStateOf(true) }; var isNewestFirst by remember { mutableStateOf(true) }; var selectedItemForDetail by remember { mutableStateOf<OrderHistoryItem?>(null) }
    fun refreshHistory() { isLoading = true; val history = activity.loadOrderHistory(); historyItems.clear(); historyItems.addAll(history); isLoading = false }
    LaunchedEffect(Unit) { refreshHistory() }
    val sortedItems = remember(historyItems.size, isNewestFirst) { if (isNewestFirst) historyItems.sortedByDescending { it.date } else historyItems.sortedBy { it.date } }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "BOOKED ORDER LOGS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                Text(text = "Total Orders: ${historyItems.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            FilterChip(selected = true, onClick = { isNewestFirst = !isNewestFirst }, label = { Text(text = if (isNewestFirst) "Newest First" else "Oldest First") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) })
        }
        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (sortedItems.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(16.dp)); Text(text = "No Previous Orders Found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = "Your booked customer invoices will be securely tracked and logged here chronologically.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sortedItems) { item ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), onClick = { selectedItemForDetail = item }, elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Offer No: ${item.offerNo}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(text = item.buyer, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp)); Text(text = item.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
    selectedItemForDetail?.let { item ->
        val fullData = activity.getOrderFromHistory(item.uuid)
        if (fullData != null) {
            OrderDetailOverlay(order = fullData, onDismiss = { selectedItemForDetail = null }, onDelete = {
                val success = activity.deleteOrderPermanently(item.uuid, item.filePath, item.pdfFileName)
                if (success) { Toast.makeText(activity, "Data Erased Successfully", Toast.LENGTH_SHORT).show(); refreshHistory(); selectedItemForDetail = null }
                else { Toast.makeText(activity, "Error deleting files", Toast.LENGTH_SHORT).show() }
            }, onFocus = { onOrderFocused(fullData); selectedItemForDetail = null })
        }
    }
}

@Composable
fun OrderDetailOverlay(order: OrderFormData, onDismiss: () -> Unit, onDelete: () -> Unit, onFocus: () -> Unit) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Order Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    DetailSection("General Information"); DetailRow("Date", order.date); DetailRow("Offer No", order.offerNo); DetailRow("Buyer", order.buyer); DetailRow("Supplier", order.supplier)
                    DetailSection("Terms & Logistics"); DetailRow("Delivery", order.deliveryReady); DetailRow("Transport", order.transportTo); DetailRow("Payment", order.cashPayment); DetailRow("Less/Disc", order.lessDisc); DetailRow("Side", order.side)
                    DetailSection("Remarks"); Text(text = order.remarks, style = MaterialTheme.typography.bodyMedium); DetailSection("Items List")
                    order.items.forEach { item ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "S.No: ${item.serialNo}", style = MaterialTheme.typography.labelSmall); Text(text = "Qty: ${item.quantity}", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(text = item.particulars, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold); Text(text = "Rate: ${item.rate}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onFocus, modifier = Modifier.weight(1f)) { Text("Reuse / Edit") }
                    Button(onClick = { showDeleteConfirmation = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Delete")
                    }
                }
            }
        }
        if (showDeleteConfirmation) {
            AlertDialog(onDismissRequest = { showDeleteConfirmation = false }, title = { Text("Permanently Delete?") }, text = { Text("Are you absolutely sure you want to permanently delete Offer No ${order.offerNo}? This action will completely erase the sync record from your system storage.") }, confirmButton = { TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("PERMANENTLY DELETE") } }, dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("CANCEL") } })
        }
    }
}

@Composable
fun DetailSection(title: String) {
    Text(text = title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "$label:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(text = value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(2f))
    }
}

@Composable
fun SyncControlScreen(totalOrders: Int) {
    val context = LocalContext.current; val sharedPrefs = remember { context.getSharedPreferences("OTA_Prefs", Context.MODE_PRIVATE) }
    var deviceId by remember { mutableStateOf(sharedPrefs.getString("device_id", "") ?: "") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "LAPTOP SYNCHRONIZATION DATA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(value = deviceId, onValueChange = { deviceId = it; sharedPrefs.edit().putString("device_id", it).apply() }, label = { Text("Device / Salesman Identifier") }, placeholder = { Text("e.g., Phone_A, Sales_Onkar") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Sync Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(8.dp)); Text(text = "Total Orders Ready for Sync: $totalOrders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Text(text = "System Architecture: Live JSON Autogeneration Active. Connect to laptop to pull data safely.", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(text = "Your laptop software will read this clean data directly when connected. Please ensure all orders are generated before initiating the synchronization process from your laptop application.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
