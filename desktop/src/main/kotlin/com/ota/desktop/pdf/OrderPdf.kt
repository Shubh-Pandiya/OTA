package com.ota.desktop.pdf

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.Units
import com.ota.desktop.AppPaths
import com.ota.desktop.pdf.PdfKit.Companion.letterhead
import java.awt.Color
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The order form PDF: a line-for-line port of the phone's MainActivity.writeOrderPdf layout. */
object OrderPdf {
    private const val BOTTOM = 780f
    private val headers = listOf("S.No" to 45f, "PARTICULARS" to 150f, "QTY" to 330f, "RATES" to 400f, "REMARKS / TERMS" to 450f)
    private const val WARNING = "Self Bilty not accepted, compulsorily attach duplicate with R.R. or M.R."
    private const val RULES = "1. Indent once given shall under no circumstances be cancelled by the Indenters.\n" +
        "2. The Rates and time stated in Indore are subject to fluctuation and confirmation of suppliers who have right to alter the indent also.\n" +
        "3. Executing or not executing the whole part of indent goods depend upon choice of the supplier, or agent of the company.\n" +
        "4. Every consignment refused or not taken delivery of will be sold by publication and the indenter is responsible for this loss, damage and other charges must be incurred by the concerned."

    /** Documents\OTA Orders\{year}\{Month}\Order_{no}.pdf; regenerating replaces the file. */
    fun fileFor(data: OrderFormData, root: File = AppPaths.documents): File {
        val day = runCatching { LocalDate.parse(data.date, DateTimeFormatter.ofPattern("dd-MM-yyyy")) }.getOrNull() ?: LocalDate.now()
        val month = day.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val name = "Order_${data.offerNo.ifBlank { "draft" }.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pdf"
        return File(root, "${day.year}/$month/$name")
    }

    fun write(data: OrderFormData, file: File = fileFor(data)): File = PdfKit.create { pdf ->
        pdf.newPage()
        pdf.letterhead("ORDER FORM")
        pdf.text("Offer No: ${data.offerNo}", 40f, 135f, 11f, bold = true)
        pdf.text("Date: ${data.date}", 450f, 135f, 11f, bold = true)
        pdf.strokeRect(40f, 145f, 290f, 215f); pdf.strokeRect(290f, 145f, 555f, 215f)
        pdf.text("BUYER:", 45f, 160f, 11f, bold = true); pdf.text("SUPPLIER:", 295f, 160f, 11f, bold = true)
        pdf.block(data.buyer, 240f, 10f).draw(45f, 165f)
        pdf.block(data.supplier, 255f, 10f).draw(295f, 165f)

        var tableTop = 230f
        var y = tableTop
        fun header() {
            pdf.fillRect(40f, y, 555f, y + 22f, PdfKit.ORANGE)
            headers.forEach { (t, x) -> pdf.text(t, x, y + 15f, 10f, bold = true, color = Color.WHITE) }
            y += 22f
        }
        fun grid(top: Float, bottom: Float) {
            pdf.strokeRect(40f, top, 555f, bottom)
            listOf(85f, 300f, 380f, 445f).forEach { pdf.line(it, top, it, bottom) }
        }
        header()

        // Logistics sidebar on page 1.
        val sideTop = y
        val cell = 40f
        val sideHeaders = listOf("DELIVERY READY", "TRANSPORT TO", "CASH PAYMENT", "LESS / DISC", "SIDE")
        val sideValues = listOf(data.deliveryReady, data.transportTo, data.cashPayment, data.lessDisc, data.side)
        for (i in 1..4) pdf.line(445f, sideTop + i * cell, 555f, sideTop + i * cell)
        for (i in 0..4) {
            val cy = sideTop + i * cell
            pdf.text(sideHeaders[i], 448f, cy + 10f, 8f, bold = true)
            pdf.block(sideValues[i], 100f, 9f).draw(448f, cy + 12f)
        }
        val sideEnd = sideTop + 5 * cell

        for (item in data.items) {
            val part = pdf.block(item.particulars, 210f, 10f)
            val qty = pdf.block(Units.display(item), 75f, 10f)
            val rate = pdf.block(item.rate, 60f, 10f)
            val rowH = maxOf(20f, part.height, qty.height, rate.height) + 10f
            if (y + rowH > BOTTOM) {
                grid(tableTop, BOTTOM)
                pdf.newPage()
                tableTop = 50f; y = tableTop
                header()
            }
            pdf.text(item.serialNo, 45f, y + 15f, 10f)
            part.draw(90f, y + 5f); qty.draw(305f, y + 5f); rate.draw(385f, y + 5f)
            y += rowH
            pdf.line(40f, y, 445f, y)
        }
        if (pdf.pageCount == 1) y = maxOf(y, sideEnd)
        grid(tableTop, y)
        y += 20f

        val remarks = pdf.block(data.remarks, 500f, 10f)
        val rules = pdf.block(RULES, 515f, 10f)
        val footer = 25f + 25f + remarks.height + 25f + rules.height + 120f
        if (y + footer > BOTTOM) { pdf.newPage(); y = 60f }
        pdf.text(WARNING, PdfKit.PAGE_W / 2f, y, 11f, bold = true, align = PdfKit.Align.CENTER)
        y += 25f
        pdf.text("REMARKS / TERMS:", 40f, y, 11f, bold = true)
        y += 10f
        remarks.draw(40f, y); y += remarks.height + 25f
        pdf.text("Terms and Conditions :", 40f, y, 10f, bold = true)
        y += 5f
        rules.draw(40f, y); y += rules.height

        val sigY = y + 120f
        pdf.line(40f, sigY, 180f, sigY); pdf.text("Signature of Indenter", 40f, sigY + 15f, 10f, bold = true)
        pdf.line(415f, sigY, 555f, sigY); pdf.text("Agent's Signature", 445f, sigY + 15f, 10f, bold = true)
        pdf.mark(Marks.stamp, 410f, sigY - 105f, 100f, Color.BLACK)
        pdf.save(file)
        file
    }
}
