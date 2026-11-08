package com.example.otaapplication

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfAppendTest {
    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, width, height); color = Color.BLACK; drawLine(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** A classic-xref PDF like the one Android's PdfDocument writes: one A4 page of text. */
    private fun advicePdf(): ByteArray = PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use {
            it.beginText(); it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f); it.newLineAtOffset(40f, 780f)
            it.showText("PAYMENT ADVICE PA420261120001"); it.endText()
        }
        ByteArrayOutputStream().also { doc.save(it, CompressParameters.NO_COMPRESSION) }.toByteArray()
    }

    @Test
    fun appendsOnePagePerImageAfterTheAdvice() {
        val base = advicePdf()
        val out = PdfAppend.appendJpegPages(base, listOf(
            PdfAppend.Page(jpeg(1600, 900), "Attachment 1 of 2 (cheque 004512)"),
            PdfAppend.Page(jpeg(720, 1600), "Attachment 2 of 2")
        ))
        // Incremental update: the original bytes are kept as they were.
        assertTrue(out.copyOfRange(0, base.size).contentEquals(base))
        Loader.loadPDF(out).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertTrue(PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc).contains("PAYMENT ADVICE"))
            listOf(1600 to 900, 720 to 1600).forEachIndexed { i, (w, h) ->
                val page = doc.getPage(i + 1)
                val images = page.resources.xObjectNames.map { page.resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                assertEquals(1, images.size)
                assertEquals(w, images[0].width)
                assertEquals(h, images[0].height)
                assertTrue(page.mediaBox.width > 590f && page.mediaBox.height > 840f)
            }
            assertTrue(PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(doc).contains("Attachment 1 of 2 (cheque 004512)"))
        }
    }

    @Test
    fun readsJpegSize() {
        val (w, h, _) = PdfAppend.jpegInfo(jpeg(321, 123))
        assertEquals(321, w)
        assertEquals(123, h)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWhatItCannotParse() {
        PdfAppend.appendJpegPages("not a pdf".toByteArray(), listOf(PdfAppend.Page(jpeg(10, 10), "x")))
    }
}
