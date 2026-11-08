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
    val moneyColumns: Set<Int> = emptySet()
)

/** Writes [SheetTable]s to an .xlsx with styled, frozen, filterable headers. Streams, so big exports stay light. */
object XlsxExport {
    private const val MONEY_FORMAT = "[>=10000000]##\\,##\\,##\\,##0.00;[>=100000]##\\,##\\,##0.00;##,##0.00"
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
                t.headers.forEachIndexed { c, h -> ws.value(0, c, h) }
                if (t.headers.isNotEmpty()) {
                    ws.range(0, 0, 0, t.headers.lastIndex).style().bold().fontColor("FFFFFF").fillColor("FF6F00").set()
                    ws.freezePane(0, 1)
                }
                val widths = IntArray(t.headers.size) { t.headers[it].length }
                t.rows.forEachIndexed { i, row ->
                    val r = i + 1
                    row.forEachIndexed { c, v ->
                        when (v) {
                            null -> Unit
                            is LocalDate -> { ws.value(r, c, v); ws.style(r, c).format(DATE_FORMAT).set() }
                            is Number -> {
                                ws.value(r, c, v)
                                if (c in t.moneyColumns) ws.style(r, c).format(MONEY_FORMAT).set()
                            }
                            is Boolean -> ws.value(r, c, if (v) "Yes" else "No")
                            else -> ws.value(r, c, v.toString())
                        }
                        if (c < widths.size) widths[c] = maxOf(widths[c], cellWidth(v))
                    }
                }
                widths.forEachIndexed { c, w -> ws.width(c, (w + 2).coerceIn(6, 60).toDouble()) }
                if (t.headers.isNotEmpty() && t.rows.isNotEmpty()) ws.setAutoFilter(0, 0, t.headers.lastIndex)
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
