package com.ota.desktop.export

import org.dhatim.fastexcel.Workbook
import java.io.File
import java.time.LocalDate

/** One sheet of an export: a header row and typed cells (String, Number, LocalDate, Boolean or null). */
data class SheetTable(
    val name: String,
    val headers: List<String>,
    val rows: List<List<Any?>>,
    /** Columns holding rupee amounts, formatted with 2 decimals and Indian grouping. */
    val moneyColumns: Set<Int> = emptySet(),
    /** Optional title and subtitle above the header row (the header stays frozen and filterable). */
    val title: String? = null,
    val subtitle: String? = null,
    /** Money cells show "₹" before the amount. */
    val currency: Boolean = false,
    /** Indexes into [rows] shown bold on a light fill (totals, group headings). */
    val boldRows: Set<Int> = emptySet()
)

/** Writes [SheetTable]s to an .xlsx with styled, frozen, filterable headers. Streams, so big exports stay light. */
object XlsxExport {
    private const val MONEY_FORMAT = "[>=10000000]##\\,##\\,##\\,##0.00;[>=100000]##\\,##\\,##0.00;##,##0.00"
    // fastexcel writes the format code into an XML attribute unescaped, so it must not contain '"' or '<'.
    private const val RUPEE_FORMAT = "[>=10000000]\\₹##\\,##\\,##\\,##0.00;[>=100000]\\₹##\\,##\\,##0.00;\\₹##,##0.00"
    private const val DATE_FORMAT = "dd-mm-yyyy"

    fun write(file: File, tables: List<SheetTable>): File {
        require(tables.isNotEmpty()) { "Nothing to export" }
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".part")
        tmp.outputStream().buffered().use { out ->
            val wb = Workbook(out, "OTA Business", "1.0")
            wb.properties().setTitle("OTA Business export")
            val used = HashSet<String>()
            for (t in tables) {
                val ws = wb.newWorksheet(uniqueName(t.name, used))
                // Rows above the header: title, subtitle and a blank line.
                val top = if (t.title != null) 3 else 0
                t.title?.let { ws.value(0, 0, it); ws.style(0, 0).bold().fontSize(14).set() }
                t.subtitle?.let { ws.value(1, 0, it); ws.style(1, 0).italic().fontColor("555555").set() }
                t.headers.forEachIndexed { c, h -> ws.value(top, c, h) }
                if (t.headers.isNotEmpty()) {
                    ws.range(top, 0, top, t.headers.lastIndex).style().bold().fontColor("FFFFFF").fillColor("FF6F00").set()
                    ws.freezePane(0, top + 1)
                }
                val money = if (t.currency) RUPEE_FORMAT else MONEY_FORMAT
                val widths = IntArray(t.headers.size) { t.headers[it].length }
                t.rows.forEachIndexed { i, row ->
                    val r = top + i + 1
                    val bold = i in t.boldRows
                    row.forEachIndexed { c, v ->
                        val format = when {
                            v is LocalDate -> DATE_FORMAT
                            v is Number && c in t.moneyColumns -> money
                            else -> null
                        }
                        when (v) {
                            null -> Unit
                            is LocalDate -> ws.value(r, c, v)
                            is Number -> ws.value(r, c, v)
                            is Boolean -> ws.value(r, c, if (v) "Yes" else "No")
                            else -> ws.value(r, c, v.toString())
                        }
                        // One style per cell: a second set() would replace the first.
                        if (bold || format != null) ws.style(r, c).apply {
                            format?.let(::format)
                            if (bold) bold().fillColor("F4EEE9")
                        }.set()
                        if (c < widths.size) widths[c] = maxOf(widths[c], cellWidth(v) + if (format == RUPEE_FORMAT) 2 else 0)
                    }
                    if (bold) for (c in row.size until t.headers.size) ws.style(r, c).bold().fillColor("F4EEE9").set()
                }
                widths.forEachIndexed { c, w -> ws.width(c, (w + 2).coerceIn(6, 60).toDouble()) }
                if (t.headers.isNotEmpty() && t.rows.isNotEmpty()) ws.setAutoFilter(top, 0, t.headers.lastIndex)
            }
            wb.finish()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.path}. Close it in Excel and try again." }
        }
        return file
    }

    private fun cellWidth(v: Any?): Int = when (v) {
        null -> 0
        is LocalDate -> 10
        is Double -> 14
        is Number -> v.toString().length
        else -> v.toString().lineSequence().maxOfOrNull { it.length } ?: 0
    }

    /** Excel sheet names: max 31 chars, no []:*?/\ and unique (case-insensitive). */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val base = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Sheet" }.take(31)
        var candidate = base
        var n = 2
        while (!used.add(candidate.lowercase())) candidate = base.take(31 - " ($n)".length) + " (${n++})"
        return candidate
    }
}
