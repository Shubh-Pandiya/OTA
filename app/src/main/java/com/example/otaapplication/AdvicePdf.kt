package com.example.otaapplication

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.otaapplication.data.AdviceImageFiles
import com.example.otaapplication.data.PaymentAdvice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Payment advice PDF, in the same style as the order form. Written to
 * Documents/OTA Orders/{year}/{Month}/Payment Advices/{advice no}.pdf (replaced when regenerated).
 */
object AdvicePdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 40f
    private const val RIGHT = 555f
    private const val BOTTOM = 780f
    private val orange = Color.parseColor("#FF6F00")
    private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val money: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    // Table columns: (header, left edge, right edge, right-aligned).
    private data class Col(val title: String, val x0: Float, val x1: Float, val numeric: Boolean)
    private val cols = listOf(
        Col("S.No", 40f, 62f, false),
        Col("BILL NO", 62f, 130f, false),
        Col("BILL DATE", 130f, 184f, false),
        Col("BILL AMT", 184f, 246f, true),
        Col("PAID", 246f, 308f, true),
        Col("C.D.", 308f, 356f, true),
        Col("G.R.", 356f, 400f, true),
        Col("R.D.", 400f, 444f, true),
        Col("CLAIM", 444f, 494f, true),
        Col("TOTAL", 494f, 555f, true)
    )

    fun fileFor(advice: PaymentAdvice): File {
        val month = advice.payDate.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "OTA Orders/${advice.payDate.year}/$month/Payment Advices")
        return File(dir, "${advice.adviceNo}.pdf")
    }

    /**
     * Writes the PDF; runs on a background thread. The advice's cheque / payment images (kept
     * on this phone only) follow as one page each after the advice page(s).
     */
    fun write(context: Context, advice: PaymentAdvice): File {
        val file = fileFor(advice).let { it.parentFile?.mkdirs(); PublicFiles.writable(it) }
        val store = AdviceImageFiles.forContext(context)
        val images = advice.images.map { store.file(advice.id, it) }.filter { it.isFile }
        val base = render(context, advice, if (images.isEmpty()) emptyList() else null)
        val bytes = if (images.isEmpty()) base else try {
            PdfAppend.appendJpegPages(base, images.mapIndexed { i, img ->
                PdfAppend.Page(img.readBytes(), caption(advice, i, images.size))
            })
        } catch (e: IllegalArgumentException) {
            // Unexpected PDF structure: draw the images instead (bigger file, same pages).
            android.util.Log.w("AdvicePdf", "JPEG append failed, drawing images instead", e)
            render(context, advice, images)
        }
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun caption(advice: PaymentAdvice, index: Int, count: Int) =
        "Payment Advice ${advice.adviceNo} - Attachment ${index + 1} of $count (${advice.mode.label}" +
            (if (advice.reference.isNotBlank()) " ${advice.reference}" else "") + ")"

    /** The advice pages, plus [drawnImages] drawn as pages (null = none, appended later). */
    private fun render(context: Context, advice: PaymentAdvice, drawnImages: List<File>?): ByteArray {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var canvas = page.canvas

        // ---- Letterhead (same as the order form)
        PdfMarks.drawLogo(context, canvas, RectF(LEFT, 40f, LEFT + 60f, 100f))
        val centerX = PAGE_W / 2f
        paint.color = Color.BLACK; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 9f; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Mob.: 94250-57961", RIGHT, 45f, paint); canvas.drawText("94068-03061", RIGHT, 58f, paint)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 18f; paint.color = orange
        canvas.drawText("ONKAR TEXTILE AGENCY", centerX, 55f, paint)
        paint.color = Color.BLACK; paint.textSize = 11f
        canvas.drawText("TEXTILE INDENTING AGENT — SINCE 1991", centerX, 72f, paint)
        paint.typeface = Typeface.DEFAULT; paint.textSize = 10f
        canvas.drawText("290, M.T. Cloth Market, INDORE - 452 002 (M.P.)", centerX, 88f, paint)
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 12f
        canvas.drawText("PAYMENT ADVICE", centerX, 112f, paint)

        paint.textAlign = Paint.Align.LEFT; paint.textSize = 11f
        canvas.drawText("Advice No: ${advice.adviceNo}", LEFT, 135f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Date: ${advice.payDate.format(dateFormat)}", RIGHT, 135f, paint)

        // ---- Paid by / Paid to
        paint.textAlign = Paint.Align.LEFT; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawRect(LEFT, 145f, 297.5f, 195f, paint); canvas.drawRect(297.5f, 145f, RIGHT, 195f, paint)
        paint.style = Paint.Style.FILL; paint.textSize = 10f
        canvas.drawText("PAID BY (BUYER):", 45f, 160f, paint); canvas.drawText("PAID TO (SUPPLIER):", 302.5f, 160f, paint)
        val partyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT }
        drawLayout(canvas, layout(advice.buyer, 245, partyPaint, maxLines = 2), 45f, 166f)
        drawLayout(canvas, layout(advice.supplier, 245, partyPaint, maxLines = 2), 302.5f, 166f)

        // ---- Mode / reference
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f
        canvas.drawText("PAYMENT MODE:", LEFT, 214f, paint)
        paint.typeface = Typeface.DEFAULT
        val modeText = buildString {
            append(advice.mode.label)
            if (advice.reference.isNotBlank()) append("   ${advice.mode.referenceLabel.substringBefore(" (")}: ${advice.reference}")
        }
        canvas.drawText(modeText, LEFT + 88f, 214f, paint)

        // ---- Bills table
        val tableTop = 226f
        var y = tableTop
        fun header() {
            paint.style = Paint.Style.FILL; paint.color = orange
            canvas.drawRect(LEFT, y, RIGHT, y + 22f, paint)
            paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 8.5f
            cols.forEach { c ->
                if (c.numeric) { paint.textAlign = Paint.Align.RIGHT; canvas.drawText(c.title, c.x1 - 4f, y + 15f, paint) }
                else { paint.textAlign = Paint.Align.LEFT; canvas.drawText(c.title, c.x0 + 4f, y + 15f, paint) }
            }
            paint.textAlign = Paint.Align.LEFT; paint.color = Color.BLACK
            y += 22f
        }
        var gridTop = y
        fun grid(bottom: Float) {
            paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = 1f
            canvas.drawRect(LEFT, gridTop, RIGHT, bottom, paint)
            cols.drop(1).forEach { canvas.drawLine(it.x0, gridTop, it.x0, bottom, paint) }
            paint.style = Paint.Style.FILL
        }
        fun newPage() {
            grid(y)
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = 50f; gridTop = y
            header()
        }
        header()
        gridTop = tableTop
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.DKGRAY }
        advice.lines.forEachIndexed { i, line ->
            val bill = line.bill
            val a = line.amounts
            val billNoLayout = layout(bill.billNo, (cols[1].x1 - cols[1].x0 - 8).toInt(), cellPaint)
            val lrLayout = bill.lrNo.takeIf { it.isNotBlank() }?.let { layout("LR: $it", (cols[1].x1 - cols[1].x0 - 8).toInt(), smallPaint) }
            val cdNote = a.cdPercent?.let { "(${trimPercent(it)}%)" }
            val rowH = maxOf(20f, billNoLayout.height + (lrLayout?.height ?: 0) + 8f, if (cdNote != null) 26f else 0f)
            if (y + rowH > BOTTOM) newPage()
            paint.style = Paint.Style.FILL; paint.color = Color.BLACK; paint.typeface = Typeface.DEFAULT; paint.textSize = 9f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("${i + 1}", cols[0].x0 + 4f, y + 14f, paint)
            drawLayout(canvas, billNoLayout, cols[1].x0 + 4f, y + 5f)
            lrLayout?.let { drawLayout(canvas, it, cols[1].x0 + 4f, y + 5f + billNoLayout.height) }
            canvas.drawText(bill.billDate?.format(dateFormat).orEmpty(), cols[2].x0 + 4f, y + 14f, paint)
            paint.textAlign = Paint.Align.RIGHT
            fun num(col: Int, value: Double?) { if (value != null) canvas.drawText(money.format(value), cols[col].x1 - 4f, y + 14f, paint) }
            num(3, bill.amount); num(4, a.paid); num(5, a.cdAmount); num(6, a.goodsReturn); num(7, a.rateDiff); num(8, a.claim); num(9, a.settled)
            if (cdNote != null) {
                paint.textSize = 7.5f; paint.color = Color.DKGRAY
                canvas.drawText(cdNote, cols[5].x1 - 4f, y + 23f, paint)
                paint.textSize = 9f; paint.color = Color.BLACK
            }
            paint.textAlign = Paint.Align.LEFT
            y += rowH
            paint.style = Paint.Style.STROKE; canvas.drawLine(LEFT, y, RIGHT, y, paint); paint.style = Paint.Style.FILL
        }
        // Totals row
        val t = advice.totals
        if (y + 22f > BOTTOM) newPage()
        paint.style = Paint.Style.FILL; paint.color = Color.argb(28, 255, 111, 0)
        canvas.drawRect(LEFT, y, RIGHT, y + 22f, paint)
        paint.color = Color.BLACK; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 9f; paint.textAlign = Paint.Align.LEFT
        canvas.drawText("TOTAL", cols[1].x0 + 4f, y + 15f, paint)
        paint.textAlign = Paint.Align.RIGHT
        listOf(4 to t.paid, 5 to t.cashDiscount, 6 to t.goodsReturn, 7 to t.rateDiff, 8 to t.claim, 9 to t.settled).forEach { (col, v) ->
            canvas.drawText(money.format(v), cols[col].x1 - 4f, y + 15f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        y += 22f
        grid(y)

        // ---- Claim reasons, under the table
        val claimNotes = advice.lines.filter { it.amounts.claim != null && it.amounts.claimNote.isNotBlank() }
            .joinToString("\n") { "Bill ${it.bill.billNo}: ${it.amounts.claimNote.trim()}" }
        if (claimNotes.isNotEmpty()) {
            val notesLayout = layout("Claim / defect\n$claimNotes", (RIGHT - LEFT).toInt(), TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY })
            if (y + notesLayout.height + 12f > BOTTOM) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()); canvas = page.canvas
                y = 50f
            }
            drawLayout(canvas, notesLayout, LEFT, y + 8f)
            y += notesLayout.height + 8f
        }

        // ---- Summary
        val remarksLayout = advice.remarks.takeIf { it.isNotBlank() }?.let {
            layout(it, (RIGHT - LEFT).toInt(), TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f })
        }
        val rows = if (t.claim != 0.0) 6 else 5
        val summaryH = 30f + rows * 16f + 20f + (remarksLayout?.let { it.height + 30f } ?: 0f) + 130f
        if (y + summaryH > BOTTOM) {
            doc.finishPage(page); pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()); canvas = page.canvas
            y = 60f
        } else y += 24f
        val boxLeft = 330f
        paint.style = Paint.Style.STROKE; canvas.drawRect(boxLeft, y, RIGHT, y + rows * 16f + 30f, paint); paint.style = Paint.Style.FILL
        var sy = y + 16f
        fun summary(label: String, value: String, bold: Boolean = false) {
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; paint.textSize = 10f
            paint.textAlign = Paint.Align.LEFT; canvas.drawText(label, boxLeft + 8f, sy, paint)
            paint.textAlign = Paint.Align.RIGHT; canvas.drawText(value, RIGHT - 8f, sy, paint)
            sy += 16f
        }
        summary("Amount paid", "₹ ${money.format(t.paid)}", bold = true)
        summary("Cash discount", "₹ ${money.format(t.cashDiscount)}")
        summary("Goods return", "₹ ${money.format(t.goodsReturn)}")
        summary("Rate difference", "₹ ${money.format(t.rateDiff)}")
        if (t.claim != 0.0) summary("Claim / defect", "₹ ${money.format(t.claim)}")
        paint.style = Paint.Style.STROKE; canvas.drawLine(boxLeft, sy - 11f, RIGHT, sy - 11f, paint); paint.style = Paint.Style.FILL
        sy += 2f
        summary("Total adjusted", "₹ ${money.format(t.settled)}", bold = true)
        val balance = advice.balanceAmount?.let { "₹ ${money.format(it)} ${advice.balanceSide?.label.orEmpty()}".trim() } ?: "—"
        summary("Balance (Dr/Cr)", balance, bold = true)

        // Left of the summary box: count of bills.
        paint.textAlign = Paint.Align.LEFT; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f
        canvas.drawText("Bills covered: ${advice.lines.size}", LEFT, y + 16f, paint)
        y = sy + 10f
        remarksLayout?.let {
            paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 11f; paint.textAlign = Paint.Align.LEFT
            canvas.drawText("REMARKS:", LEFT, y + 10f, paint)
            drawLayout(canvas, it, LEFT, y + 16f)
            y += it.height + 30f
        }

        // ---- Signature
        val sigY = y + 110f
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 10f; paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.STROKE; canvas.drawLine(415f, sigY, RIGHT, sigY, paint); paint.style = Paint.Style.FILL
        canvas.drawText("Agent's Signature", 445f, sigY + 15f, paint)
        PdfMarks.drawStamp(context, canvas, RectF(410f, sigY - 105f, 510f, sigY - 5f))

        doc.finishPage(page)
        drawnImages?.forEachIndexed { i, image ->
            pageNo++
            val imagePage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            drawImagePage(imagePage.canvas, image, caption(advice, i, drawnImages.size))
            doc.finishPage(imagePage)
        }
        try {
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun drawImagePage(canvas: android.graphics.Canvas, image: File, caption: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        canvas.drawText(caption, LEFT, 51f, paint)
        val bitmap = AdviceImageImport.thumbnail(image, AdviceImageImport.MAX_EDGE) ?: return
        try {
            val boxW = RIGHT - LEFT
            val boxH = PAGE_H - 80f - 40f
            val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = LEFT + (boxW - w) / 2
            canvas.drawBitmap(bitmap, null, RectF(left, 70f, left + w, 70f + h), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    /** Opens the share sheet for a written PDF (WhatsApp, email, …). */
    fun share(context: Context, file: File, adviceNo: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Payment Advice $adviceNo")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, "Share payment advice"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app to share the PDF with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trimPercent(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun layout(text: String, width: Int, paint: TextPaint, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else android.text.TextUtils.TruncateAt.END)
            .build()

    private fun drawLayout(canvas: android.graphics.Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }
}
