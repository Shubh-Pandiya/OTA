package com.example.otaapplication

import java.io.ByteArrayOutputStream

/**
 * Appends JPEG pages to a finished PDF with an incremental update, embedding each JPEG as is
 * (DCTDecode) instead of re-encoding it, so a 150 KB photo adds about 150 KB to the file.
 *
 * Handles the classic-xref PDFs Android's PdfDocument writes: the root page tree gets the new
 * pages as extra kids, and a new xref section with /Prev points back at the original.
 * Throws [IllegalArgumentException] for anything it doesn't understand (callers fall back).
 */
object PdfAppend {
    class Page(val jpeg: ByteArray, val caption: String)

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CAPTION_GAP = 30f

    fun appendJpegPages(pdf: ByteArray, pages: List<Page>): ByteArray {
        if (pages.isEmpty()) return pdf
        val text = String(pdf, Charsets.ISO_8859_1)
        val startXref = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(text)?.groupValues?.get(1)?.toInt()
            ?: throw IllegalArgumentException("No startxref")
        val offsets = readXref(text, startXref)
        val trailer = Regex("""trailer\s*<<(.*?)>>\s*startxref""", RegexOption.DOT_MATCHES_ALL).find(text, startXref)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No trailer")
        val size = Regex("""/Size\s+(\d+)""").find(trailer)?.groupValues?.get(1)?.toInt() ?: throw IllegalArgumentException("No /Size")
        val root = ref(trailer, "Root")
        val info = Regex("""/Info\s+\d+\s+\d+\s+R""").find(trailer)?.value
        val id = Regex("""/ID\s*\[[^\]]*]""").find(trailer)?.value

        val catalog = objectBody(text, offsets, root)
        val pagesRef = ref(catalog, "Pages")
        val pagesDict = objectBody(text, offsets, pagesRef)
        val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(pagesDict) ?: throw IllegalArgumentException("No /Kids")
        val count = Regex("""/Count\s+(\d+)""").find(pagesDict) ?: throw IllegalArgumentException("No /Count")

        val out = ByteArrayOutputStream(pdf.size + pages.sumOf { it.jpeg.size + 600 })
        out.write(pdf)
        if (pdf.last() != '\n'.code.toByte()) out.write('\n'.code)
        val written = sortedMapOf<Int, Pair<Int, Int>>() // object number to (generation, offset)
        fun obj(number: Int, generation: Int = 0, body: (ByteArrayOutputStream) -> Unit) {
            written[number] = generation to out.size()
            out.ascii("$number $generation obj\n")
            body(out)
            out.ascii("\nendobj\n")
        }

        var next = size
        val font = next++
        obj(font) { it.ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>") }
        val newPages = ArrayList<Int>()
        pages.forEach { page ->
            val (w, h, components) = jpegInfo(page.jpeg)
            val image = next++
            val content = next++
            val pageObj = next++
            newPages += pageObj
            obj(image) {
                val space = when (components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
                it.ascii("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n")
                it.write(page.jpeg)
                it.ascii("\nendstream")
            }
            // Fit the image below the caption, keeping its shape, centred and top-aligned.
            val boxW = PAGE_W - 2 * MARGIN
            val boxH = PAGE_H - 2 * MARGIN - CAPTION_GAP
            val scale = minOf(boxW / w, boxH / h)
            val dw = w * scale
            val dh = h * scale
            val x = MARGIN + (boxW - dw) / 2
            val y = PAGE_H - MARGIN - CAPTION_GAP - dh
            val stream = "BT /F1 11 Tf ${f(MARGIN)} ${f(PAGE_H - MARGIN - 11f)} Td (${escape(page.caption)}) Tj ET\n" +
                "q ${f(dw)} 0 0 ${f(dh)} ${f(x)} ${f(y)} cm /Im0 Do Q\n"
            obj(content) { it.ascii("<< /Length ${stream.length} >>\nstream\n$stream\nendstream") }
            obj(pageObj) {
                it.ascii(
                    "<< /Type /Page /Parent ${pagesRef.first} ${pagesRef.second} R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
                        "/Resources << /Font << /F1 $font 0 R >> /XObject << /Im0 $image 0 R >> /ProcSet [/PDF /Text /ImageC /ImageB] >> " +
                        "/Contents $content 0 R >>"
                )
            }
        }
        // The root page tree, now with the new pages as its last kids.
        val newKids = kids.groupValues[1].trim() + newPages.joinToString("") { " $it 0 R" }
        val newCount = count.groupValues[1].toInt() + newPages.size
        val updated = pagesDict
            .replaceRange(kids.range, "/Kids [${newKids.trim()}]")
            .let { d -> Regex("""/Count\s+\d+""").replaceFirst(d, "/Count $newCount") }
        obj(pagesRef.first, pagesRef.second) { it.ascii(updated) }

        val xrefAt = out.size()
        out.ascii("xref\n")
        written.forEach { (number, entry) ->
            out.ascii("$number 1\n")
            out.ascii("%010d %05d n \n".format(java.util.Locale.ROOT, entry.second, entry.first))
        }
        out.ascii(
            "trailer\n<< /Size $next /Root ${root.first} ${root.second} R" +
                (info?.let { " $it" } ?: "") + (id?.let { " $it" } ?: "") +
                " /Prev $startXref >>\nstartxref\n$xrefAt\n%%EOF\n"
        )
        return out.toByteArray()
    }

    /** Width, height and colour components from the JPEG's start-of-frame marker. */
    internal fun jpegInfo(jpeg: ByteArray): Triple<Int, Int, Int> {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        var i = 2
        while (i + 9 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD7) { i += if (marker == 0xFF) 1 else 2; continue }
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                val components = jpeg[i + 9].toInt() and 0xFF
                require(w > 0 && h > 0) { "Bad JPEG size" }
                return Triple(w, h, components)
            }
            i += 2 + length
        }
        throw IllegalArgumentException("No JPEG frame header")
    }

    private fun readXref(text: String, at: Int): Map<Int, Pair<Int, Int>> {
        require(text.startsWith("xref", at)) { "Only classic xref tables are supported" }
        val result = HashMap<Int, Pair<Int, Int>>()
        val lines = text.substring(at + 4).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
        while (lines.hasNext()) {
            val header = lines.next()
            if (header.startsWith("trailer")) break
            val (start, count) = header.split(Regex("\\s+")).map { it.toInt() }
            repeat(count) { k ->
                val parts = lines.next().split(Regex("\\s+"))
                if (parts[2] == "n") result[start + k] = parts[1].toInt() to parts[0].toInt()
            }
        }
        return result
    }

    private fun objectBody(text: String, offsets: Map<Int, Pair<Int, Int>>, ref: Pair<Int, Int>): String {
        val offset = offsets[ref.first]?.second ?: throw IllegalArgumentException("Object ${ref.first} not in xref")
        // Anchored on a substring rather than \G: Android's regex engine treats \G differently.
        val match = Regex("""^\s*${ref.first}\s+${ref.second}\s+obj\s*(<<.*?>>)\s*endobj""", RegexOption.DOT_MATCHES_ALL)
            .find(text.substring(offset)) ?: throw IllegalArgumentException("Object ${ref.first} unreadable")
        return match.groupValues[1]
    }

    private fun ref(dict: String, key: String): Pair<Int, Int> {
        val m = Regex("""/$key\s+(\d+)\s+(\d+)\s+R""").find(dict) ?: throw IllegalArgumentException("No /$key")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun escape(s: String) = buildString {
        s.forEach { c ->
            when {
                c == '(' || c == ')' || c == '\\' -> append('\\').append(c)
                c.code in 32..126 -> append(c)
                else -> append('-')
            }
        }
    }

    private fun f(v: Float) = "%.2f".format(java.util.Locale.ROOT, v)

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
}
