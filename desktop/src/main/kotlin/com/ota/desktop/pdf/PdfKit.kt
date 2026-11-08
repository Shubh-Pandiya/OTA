package com.ota.desktop.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.awt.Color
import java.io.File

/**
 * A small y-down drawing surface over PDFBox, so the desktop PDFs can use the same coordinates as
 * the Android `PdfDocument` canvas code they are ported from (A4 = 595 x 842 points, origin top-left).
 * Text uses embedded, subset Roboto (the Android default face), so files stay small.
 */
class PdfKit private constructor(private val doc: PDDocument) : AutoCloseable {
    private val regular = load("fonts/Roboto-Regular.ttf")
    private val bold = load("fonts/Roboto-Bold.ttf")
    private var page: PDPage? = null
    private var cs: PDPageContentStream? = null
    var pageCount = 0
        private set

    enum class Align { LEFT, CENTER, RIGHT }

    private fun load(name: String): PDType0Font =
        requireNotNull(PdfKit::class.java.classLoader.getResourceAsStream(name)) { "missing $name" }.use { PDType0Font.load(doc, it) }

    private val out: PDPageContentStream get() = requireNotNull(cs) { "no page" }

    fun newPage() {
        cs?.close()
        val p = PDPage(PDRectangle(PAGE_W, PAGE_H))
        doc.addPage(p)
        page = p
        cs = PDPageContentStream(doc, p)
        pageCount++
    }

    private fun fy(y: Float) = PAGE_H - y

    fun font(bold: Boolean) = if (bold) this.bold else regular

    /** Replaces characters the font can't draw, so a stray emoji never fails a PDF. */
    fun clean(text: String, bold: Boolean = false): String {
        val f = font(bold)
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            sb.append(
                when {
                    cp == '\t'.code -> " "
                    cp < 32 -> ""
                    runCatching { f.encode(s) }.isSuccess -> s
                    cp == 0x20B9 -> "Rs."
                    else -> "?"
                }
            )
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun width(text: String, size: Float, bold: Boolean = false): Float =
        font(bold).getStringWidth(clean(text, bold)) / 1000f * size

    /** Draws [text] with its baseline at [y]. */
    fun text(text: String, x: Float, y: Float, size: Float, bold: Boolean = false, color: Color = Color.BLACK, align: Align = Align.LEFT) {
        val t = clean(text, bold)
        if (t.isEmpty()) return
        val w = font(bold).getStringWidth(t) / 1000f * size
        val left = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2f; Align.RIGHT -> x - w }
        out.beginText()
        out.setFont(font(bold), size)
        out.setNonStrokingColor(color)
        out.newLineAtOffset(left, fy(y))
        out.showText(t)
        out.endText()
    }

    /** Text wrapped to [width], like an Android StaticLayout (no include-pad). */
    inner class Block(val lines: List<String>, val size: Float, val bold: Boolean, val color: Color) {
        val lineHeight = size * LINE
        val height: Float get() = lines.size * lineHeight

        /** Draws with the block's top edge at [top]. */
        fun draw(x: Float, top: Float) {
            lines.forEachIndexed { i, line -> text(line, x, top + size * ASCENT + i * lineHeight, size, bold, color) }
        }
    }

    fun block(text: String, width: Float, size: Float, bold: Boolean = false, color: Color = Color.BLACK, maxLines: Int = Int.MAX_VALUE): Block {
        val lines = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) { lines += ""; continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (width(candidate, size, bold) <= width) { line = StringBuilder(candidate); continue }
                if (line.isNotEmpty()) lines += line.toString()
                line = StringBuilder()
                var rest = word
                while (width(rest, size, bold) > width && rest.length > 1) {
                    var n = rest.length - 1
                    while (n > 1 && width(rest.substring(0, n), size, bold) > width) n--
                    lines += rest.substring(0, n)
                    rest = rest.substring(n)
                }
                line.append(rest)
            }
            lines += line.toString()
        }
        if (lines.isEmpty()) lines += ""
        if (lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            var last = kept.last()
            while (last.isNotEmpty() && width("$last…", size, bold) > width) last = last.dropLast(1)
            kept[kept.lastIndex] = "$last…"
            return Block(kept, size, bold, color)
        }
        return Block(lines, size, bold, color)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = STROKE, color: Color = Color.BLACK) {
        out.setStrokingColor(color)
        out.setLineWidth(width)
        out.moveTo(x0, fy(y0)); out.lineTo(x1, fy(y1)); out.stroke()
    }

    fun strokeRect(l: Float, t: Float, r: Float, b: Float, width: Float = STROKE) {
        out.setStrokingColor(Color.BLACK)
        out.setLineWidth(width)
        out.addRect(l, fy(b), r - l, b - t); out.stroke()
    }

    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Color) {
        out.saveGraphicsState()
        if (color.alpha < 255) out.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = color.alpha / 255f })
        out.setNonStrokingColor(Color(color.red, color.green, color.blue))
        out.addRect(l, fy(b), r - l, b - t); out.fill()
        out.restoreGraphicsState()
    }

    /** A vector mark (logo/stamp) filled into the square [l, t]–[l+size, t+size]. */
    fun mark(ops: List<PathOp>, l: Float, t: Float, size: Float, color: Color) {
        val k = size / Marks.VIEWPORT
        fun px(v: Float) = l + v * k
        fun py(v: Float) = fy(t + v * k)
        out.saveGraphicsState()
        out.setNonStrokingColor(color)
        for (op in ops) {
            val c = op.coords
            when (op.cmd) {
                'M' -> out.moveTo(px(c[0]), py(c[1]))
                'L' -> out.lineTo(px(c[0]), py(c[1]))
                'C' -> out.curveTo(px(c[0]), py(c[1]), px(c[2]), py(c[3]), px(c[4]), py(c[5]))
                'Z' -> out.closePath()
            }
        }
        out.fill()
        out.restoreGraphicsState()
    }

    fun save(file: File) {
        cs?.close(); cs = null
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".part")
        doc.save(tmp)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.path}. Close it in your PDF viewer and try again." }
        }
    }

    override fun close() {
        runCatching { cs?.close() }
        doc.close()
    }

    companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val STROKE = 0.75f
        /** Roboto's ascent and line height (ascent + descent) per point of text size. */
        private const val ASCENT = 0.928f
        private const val LINE = 1.172f

        val ORANGE = Color(0xFF, 0x6F, 0x00)
        val LOGO = Color(Marks.LOGO_RGB)

        fun <T> create(block: (PdfKit) -> T): T = PdfKit(PDDocument()).use(block)

        /** The letterhead shared by the order form and the payment advice. */
        fun PdfKit.letterhead(title: String) {
            mark(Marks.logo, 40f, 40f, 60f, LOGO)
            text("Mob.: 94250-57961", 555f, 45f, 9f, bold = true, align = Align.RIGHT)
            text("94068-03061", 555f, 58f, 9f, bold = true, align = Align.RIGHT)
            val cx = PAGE_W / 2f
            text("ONKAR TEXTILE AGENCY", cx, 55f, 18f, bold = true, color = ORANGE, align = Align.CENTER)
            text("TEXTILE INDENTING AGENT — SINCE 1991", cx, 72f, 11f, bold = true, align = Align.CENTER)
            text("290, M.T. Cloth Market, INDORE - 452 002 (M.P.)", cx, 88f, 10f, align = Align.CENTER)
            text(title, cx, 112f, 12f, bold = true, align = Align.CENTER)
        }
    }
}
