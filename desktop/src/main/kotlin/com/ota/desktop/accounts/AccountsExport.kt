package com.ota.desktop.accounts

import com.ota.desktop.Money
import com.ota.desktop.export.SheetTable
import com.ota.desktop.export.XlsxExport
import com.ota.desktop.pdf.PdfKit
import com.ota.desktop.pdf.PdfKit.Companion.letterhead
import java.awt.Color
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Accounts reports to XLSX (one sheet per table) and PDF (letterhead, ₹ headers, 2 decimals). */
object AccountsExport {
    private val DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun xlsx(file: File, tables: List<ReportTable>): File = XlsxExport.write(file, tables.map { t ->
        SheetTable(
            t.title.take(31).replace(Regex("[\\[\\]*?/\\\\:]"), " "),
            t.columns.map { it.title },
            t.rows.map { r -> r.cells.map { if (it is Long) it.toDouble() else it } },
            t.moneyColumns
        )
    })

    /** Text of a cell as shown on screen and in the PDF. */
    fun cellText(value: Any?, kind: ColKind): String = when (value) {
        null -> ""
        is LocalDate -> value.format(DMY)
        is Double -> if (kind == ColKind.MONEY) Money.format(value) else value.toString()
        is Number -> value.toString()
        else -> value.toString()
    }

    fun pdf(file: File, table: ReportTable): File = PdfKit.create { pdf ->
        val left = 40f
        val right = 555f
        val size = if (table.columns.size > 8) 6.8f else 7.8f
        val pad = 3f
        // Money columns get the width their widest figure needs; text columns share the rest by weight.
        val widths = FloatArray(table.columns.size)
        table.columns.forEachIndexed { i, c ->
            if (c.kind == ColKind.MONEY || c.kind == ColKind.DATE || c.kind == ColKind.NUMBER) {
                val texts = table.rows.map { cellText(it.cells.getOrNull(i), c.kind) } + c.title.substringBefore(" (₹)")
                widths[i] = texts.maxOf { pdf.width(it, size, bold = true) } + 2 * pad + 2f
            }
        }
        val fixed = widths.sum()
        val textWeight = table.columns.filter { it.kind == ColKind.TEXT }.sumOf { it.weight.toDouble() }.toFloat()
        val free = (right - left - fixed).coerceAtLeast(60f)
        table.columns.forEachIndexed { i, c -> if (c.kind == ColKind.TEXT) widths[i] = free * c.weight / textWeight }
        val scale = (right - left) / widths.sum()
        for (i in widths.indices) widths[i] *= scale
        val xs = FloatArray(widths.size + 1).also { x -> x[0] = left; for (i in widths.indices) x[i + 1] = x[i] + widths[i] }

        var y = 0f
        var page = 0
        fun footer() {
            pdf.text("Onkar Textile Agency · ${table.title} · printed ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))}", left, 822f, 7f, color = Color.GRAY)
            pdf.text("Page $page", right, 822f, 7f, color = Color.GRAY, align = PdfKit.Align.RIGHT)
        }
        fun header() {
            val h = 24f
            pdf.fillRect(left, y, right, y + h, PdfKit.ORANGE)
            table.columns.forEachIndexed { i, c ->
                val money = c.kind == ColKind.MONEY
                val title = if (money) c.title.substringBefore(" (₹)") else c.title
                val alignRight = c.kind != ColKind.TEXT && c.kind != ColKind.DATE
                val x = if (alignRight) xs[i + 1] - pad else xs[i] + pad
                val align = if (alignRight) PdfKit.Align.RIGHT else PdfKit.Align.LEFT
                pdf.text(title, x, y + 10f, size, bold = true, color = Color.WHITE, align = align)
                // Rupee columns: the title, with "(₹)" under it.
                if (money) pdf.text("(₹)", x, y + 19.5f, size, bold = true, color = Color.WHITE, align = align)
            }
            y += h
        }
        fun newPage() {
            pdf.newPage(); page++
            if (page == 1) {
                pdf.letterhead(table.title.uppercase())
                y = 122f
                pdf.block(table.subtitle, right - left, 8.5f, color = Color.DARK_GRAY).let { it.draw(left, y); y += it.height + 8f }
            } else {
                pdf.text(table.title, left, 48f, 10f, bold = true)
                y = 58f
            }
            footer()
            header()
        }
        newPage()
        if (table.rows.isEmpty()) pdf.text("Nothing to show for this period.", left + pad, y + 14f, 9f, color = Color.DARK_GRAY)
        table.rows.forEachIndexed { r, row ->
            val bold = row.style != RowStyle.NORMAL
            val blocks = table.columns.mapIndexed { i, c ->
                pdf.block(cellText(row.cells.getOrNull(i), c.kind), widths[i] - 2 * pad, size, bold, maxLines = if (c.kind == ColKind.TEXT) 3 else 1)
            }
            val h = blocks.maxOf { it.height } + 2 * pad + 1f
            if (y + h > 805f) newPage()
            when (row.style) {
                RowStyle.HEADING -> pdf.fillRect(left, y, right, y + h, Color(0xFF, 0xF4, 0xEA))
                RowStyle.TOTAL -> pdf.fillRect(left, y, right, y + h, Color(0xF4, 0xEE, 0xE9))
                RowStyle.NORMAL -> if (r % 2 == 1) pdf.fillRect(left, y, right, y + h, Color(0xFB, 0xF9, 0xF7))
            }
            blocks.forEachIndexed { i, b ->
                val c = table.columns[i]
                if (c.kind == ColKind.TEXT || c.kind == ColKind.DATE) b.draw(xs[i] + pad, y + pad)
                else b.lines.firstOrNull()?.let { pdf.text(it, xs[i + 1] - pad, y + pad + size * 0.928f, size, bold, align = PdfKit.Align.RIGHT) }
            }
            y += h
            pdf.line(left, y, right, y, 0.3f, if (row.style == RowStyle.TOTAL) Color.GRAY else Color(0xE0, 0xD8, 0xD0))
        }
        pdf.save(file)
        file
    }
}
