package com.ota.desktop.pdf

import com.example.otaapplication.data.PaymentAdvice
import com.ota.desktop.AppPaths
import com.ota.desktop.Money
import com.ota.desktop.pdf.PdfKit.Companion.letterhead
import java.awt.Color
import java.io.File
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/** Payment advice PDF: a port of the phone's AdvicePdf, same layout and content. */
object AdvicePdf {
    private const val LEFT = 40f
    private const val RIGHT = 555f
    private const val BOTTOM = 780f
    private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val darkGray = Color(0x44, 0x44, 0x44)

    private data class Col(val title: String, val x0: Float, val x1: Float, val numeric: Boolean)
    private val cols = listOf(
        // Same columns as the phone's advice PDF.
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

    /** Documents\OTA Orders\{year}\{Month}\Payment Advices\{advice no}.pdf; replaced when regenerated. */
    fun fileFor(advice: PaymentAdvice, root: File = AppPaths.documents): File {
        val month = advice.payDate.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return File(root, "${advice.payDate.year}/$month/Payment Advices/${advice.adviceNo}.pdf")
    }

    fun write(advice: PaymentAdvice, file: File = fileFor(advice)): File = PdfKit.create { pdf ->
        pdf.newPage()
        pdf.letterhead("PAYMENT ADVICE")
        pdf.text("Advice No: ${advice.adviceNo}", LEFT, 135f, 11f, bold = true)
        pdf.text("Date: ${advice.payDate.format(dateFormat)}", RIGHT, 135f, 11f, bold = true, align = PdfKit.Align.RIGHT)

        pdf.strokeRect(LEFT, 145f, 297.5f, 195f, 1f); pdf.strokeRect(297.5f, 145f, RIGHT, 195f, 1f)
        pdf.text("PAID BY (BUYER):", 45f, 160f, 10f, bold = true)
        pdf.text("PAID TO (SUPPLIER):", 302.5f, 160f, 10f, bold = true)
        pdf.block(advice.buyer, 245f, 11f, maxLines = 2).draw(45f, 166f)
        pdf.block(advice.supplier, 245f, 11f, maxLines = 2).draw(302.5f, 166f)

        pdf.text("PAYMENT MODE:", LEFT, 214f, 10f, bold = true)
        val modeText = buildString {
            append(advice.mode.label)
            if (advice.reference.isNotBlank()) append("   ${advice.mode.referenceLabel.substringBefore(" (")}: ${advice.reference}")
        }
        pdf.text(modeText, LEFT + 88f, 214f, 10f)

        val tableTop = 226f
        var y = tableTop
        fun header() {
            pdf.fillRect(LEFT, y, RIGHT, y + 22f, PdfKit.ORANGE)
            cols.forEach { c ->
                if (c.numeric) pdf.text(c.title, c.x1 - 4f, y + 15f, 8.5f, bold = true, color = Color.WHITE, align = PdfKit.Align.RIGHT)
                else pdf.text(c.title, c.x0 + 4f, y + 15f, 8.5f, bold = true, color = Color.WHITE)
            }
            y += 22f
        }
        var gridTop = y
        fun grid(bottom: Float) {
            pdf.strokeRect(LEFT, gridTop, RIGHT, bottom, 1f)
            cols.drop(1).forEach { pdf.line(it.x0, gridTop, it.x0, bottom, 1f) }
        }
        fun newPage() {
            grid(y)
            pdf.newPage()
            y = 50f; gridTop = y
            header()
        }
        header()
        gridTop = tableTop
        val billW = cols[1].x1 - cols[1].x0 - 8
        advice.lines.forEachIndexed { i, line ->
            val bill = line.bill
            val a = line.amounts
            val billNo = pdf.block(bill.billNo, billW, 9f)
            val lr = bill.lrNo.takeIf { it.isNotBlank() }?.let { pdf.block("LR: $it", billW, 7.5f, color = darkGray) }
            val cdNote = a.cdPercent?.let { "(${BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}%)" }
            val rowH = maxOf(20f, billNo.height + (lr?.height ?: 0f) + 8f, if (cdNote != null) 26f else 0f)
            if (y + rowH > BOTTOM) newPage()
            pdf.text("${i + 1}", cols[0].x0 + 4f, y + 14f, 9f)
            billNo.draw(cols[1].x0 + 4f, y + 5f)
            lr?.draw(cols[1].x0 + 4f, y + 5f + billNo.height)
            pdf.text(bill.billDate?.format(dateFormat).orEmpty(), cols[2].x0 + 4f, y + 14f, 9f)
            fun num(col: Int, value: Double?) {
                if (value != null) pdf.text(Money.format(value), cols[col].x1 - 4f, y + 14f, 9f, align = PdfKit.Align.RIGHT)
            }
            num(3, bill.amount); num(4, a.paid); num(5, a.cdAmount); num(6, a.goodsReturn); num(7, a.rateDiff); num(8, a.claim); num(9, a.settled)
            if (cdNote != null) pdf.text(cdNote, cols[5].x1 - 4f, y + 23f, 7.5f, color = darkGray, align = PdfKit.Align.RIGHT)
            y += rowH
            pdf.line(LEFT, y, RIGHT, y, 1f)
        }
        val t = advice.totals
        if (y + 22f > BOTTOM) newPage()
        pdf.fillRect(LEFT, y, RIGHT, y + 22f, Color(255, 111, 0, 28))
        pdf.text("TOTAL", cols[1].x0 + 4f, y + 15f, 9f, bold = true)
        listOf(4 to t.paid, 5 to t.cashDiscount, 6 to t.goodsReturn, 7 to t.rateDiff, 8 to t.claim, 9 to t.settled).forEach { (col, v) ->
            pdf.text(Money.format(v), cols[col].x1 - 4f, y + 15f, 9f, bold = true, align = PdfKit.Align.RIGHT)
        }
        y += 22f
        grid(y)

        // Claim reasons, under the table.
        val claimNotes = advice.lines.filter { it.amounts.claim != null && it.amounts.claimNote.isNotBlank() }
            .joinToString("\n") { "Bill ${it.bill.billNo}: ${it.amounts.claimNote.trim()}" }
        if (claimNotes.isNotEmpty()) {
            val notes = pdf.block("Claim / defect\n$claimNotes", RIGHT - LEFT, 9f, color = darkGray)
            if (y + notes.height + 12f > BOTTOM) { pdf.newPage(); y = 50f }
            notes.draw(LEFT, y + 8f)
            y += notes.height + 8f
        }

        val remarks = advice.remarks.takeIf { it.isNotBlank() }?.let { pdf.block(it, RIGHT - LEFT, 10f) }
        val rows = if (t.claim != 0.0) 6 else 5
        val summaryH = 30f + rows * 16f + 20f + (remarks?.let { it.height + 30f } ?: 0f) + 130f
        if (y + summaryH > BOTTOM) { pdf.newPage(); y = 60f } else y += 24f
        val boxLeft = 330f
        pdf.strokeRect(boxLeft, y, RIGHT, y + rows * 16f + 30f, 1f)
        var sy = y + 16f
        fun summary(label: String, value: String, bold: Boolean = false) {
            pdf.text(label, boxLeft + 8f, sy, 10f, bold)
            pdf.text(value, RIGHT - 8f, sy, 10f, bold, align = PdfKit.Align.RIGHT)
            sy += 16f
        }
        summary("Amount paid", "₹ ${Money.format(t.paid)}", bold = true)
        summary("Cash discount", "₹ ${Money.format(t.cashDiscount)}")
        summary("Goods return", "₹ ${Money.format(t.goodsReturn)}")
        summary("Rate difference", "₹ ${Money.format(t.rateDiff)}")
        if (t.claim != 0.0) summary("Claim / defect", "₹ ${Money.format(t.claim)}")
        pdf.line(boxLeft, sy - 11f, RIGHT, sy - 11f, 1f)
        sy += 2f
        summary("Total adjusted", "₹ ${Money.format(t.settled)}", bold = true)
        val balance = advice.balanceAmount?.let { "₹ ${Money.format(it)} ${advice.balanceSide?.label.orEmpty()}".trim() } ?: "—"
        summary("Balance (Dr/Cr)", balance, bold = true)
        pdf.text("Bills covered: ${advice.lines.size}", LEFT, y + 16f, 10f, bold = true)
        y = sy + 10f
        remarks?.let {
            pdf.text("REMARKS:", LEFT, y + 10f, 11f, bold = true)
            it.draw(LEFT, y + 16f)
            y += it.height + 30f
        }

        val sigY = y + 110f
        pdf.line(415f, sigY, RIGHT, sigY, 1f)
        pdf.text("Agent's Signature", 445f, sigY + 15f, 10f, bold = true)
        pdf.mark(Marks.stamp, 410f, sigY - 105f, 100f, Color.BLACK)
        pdf.save(file)
        file
    }
}
