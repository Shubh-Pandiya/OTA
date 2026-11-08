package com.example.otaapplication

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OrderJson
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes an order's files on this phone: the sync JSON, the history entry and the order PDF in
 * Documents/OTA Orders. Used by the order form and by the sync once a pending order gets its number.
 */
class OrderFiles(private val context: Context, private val localOrders: LocalOrders) {

    /**
     * Runs on a background thread; returns (success, user message). With [existingUuid] the saved
     * order is updated in place (same uuid, bills kept) and its old files are replaced.
     */
    fun save(data: OrderFormData, existingUuid: String?): Pair<Boolean, String> = localOrders.history.locked {
        // An order reopened while still pending may have got its number from a sync meanwhile; keep it.
        val data = if (OrderNumber.isPending(data.offerNo) && existingUuid != null) {
            localOrders.history.find(existingUuid)?.optString("offerNo")?.takeUnless(OrderNumber::isPending)
                ?.let { data.copy(offerNo = it) } ?: data
        } else data
        val now = LocalDateTime.now(); val year = now.year.toString(); val monthName = now.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val sharedPrefs = context.getSharedPreferences("OTA_Prefs", Context.MODE_PRIVATE)
        val deviceId = sharedPrefs.getString("device_id", "")?.takeIf { it.isNotBlank() } ?: "UnknownDevice"
        val uniqueId = existingUuid ?: java.util.UUID.randomUUID().toString()
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val otaDir = File(baseDir, "OTA Orders/$year/$monthName")
        if (!otaDir.exists()) otaDir.mkdirs()
        // An order still waiting for its number gets no PDF, so nothing is shared without one.
        val numbered = !OrderNumber.isPending(data.offerNo)
        val pdfFile = if (numbered) {
            val ordersToday = otaDir.listFiles { file -> file.name.contains("Order") && file.name.contains(dateStamp) }?.size ?: 0
            PublicFiles.orderPdf(otaDir, ordersToday + 1) { "Order_${it}_${dateStamp}_${deviceId}.pdf" }
        } else null
        val fileName = pdfFile?.name
        try {
            if (pdfFile != null) writePdf(data, pdfFile)
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
            return@locked true to (pdfFile?.let { "PDF Generated: ${it.absolutePath}" } ?: "Order saved")
        } catch (e: Exception) { e.printStackTrace(); if (pdfFile != null && pdfFile.length() == 0L) pdfFile.delete(); return@locked false to "Error: ${e.message}" }
    }

    /** Draws the order form PDF into [pdfFile]. Throws if it can't be written. */
    fun writePdf(data: OrderFormData, pdfFile: File) {
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
        PdfMarks.drawLogo(context, canvas, RectF(40f, 40f, 100f, 100f))
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
        PdfMarks.drawStamp(context, canvas, RectF(410f, sigY - 105f, 510f, sigY - 5f))
        pdfDocument.finishPage(page)
        try { FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) } } finally { pdfDocument.close() }
    }


    /** After an in-place update, deletes the order's previous sync JSON and PDF if they are different files. */
    private fun removeReplacedFiles(previous: JSONObject, newJson: File, newPdf: File?) {
        val oldPath = previous.optString("filePath")
        if (oldPath.isBlank()) return
        val oldJson = File(oldPath)
        if (oldJson.absolutePath != newJson.absolutePath) oldJson.delete()
        val oldPdfName = if (previous.has("pdfFileName")) previous.optString("pdfFileName") else ""
        if (oldPdfName.isNotBlank()) {
            val oldPdf = File(oldJson.parentFile?.parentFile, oldPdfName)
            if (oldPdf.absolutePath != newPdf?.absolutePath) oldPdf.delete()
        }
    }
    private fun createStaticLayout(text: String, width: Int, textPaint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).setIncludePad(false).build()
    }
}
