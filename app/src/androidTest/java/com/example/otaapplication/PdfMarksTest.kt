package com.example.otaapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfMarksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inkShare(draw: (Canvas) -> Unit): Double {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        val px = IntArray(200 * 200).also { bmp.getPixels(it, 0, 200, 0, 0, 200, 200) }
        return px.count { Color.alpha(it) > 128 }.toDouble() / px.size
    }

    @Test
    fun logoAndStampParseAndDraw() {
        // Catches truncated or oversized path data (e.g. aapt's STRING_TOO_LARGE placeholder).
        val logo = inkShare { PdfMarks.drawLogo(context, it, RectF(0f, 0f, 200f, 200f)) }
        val stamp = inkShare { PdfMarks.drawStamp(context, it, RectF(0f, 0f, 200f, 200f)) }
        assertTrue("logo ink $logo", logo in 0.05..0.6)
        assertTrue("stamp ink $stamp", stamp in 0.05..0.6)
    }

    @Test
    fun marksKeepPdfSmall() {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        PdfMarks.drawLogo(context, page.canvas, RectF(40f, 40f, 100f, 100f))
        PdfMarks.drawStamp(context, page.canvas, RectF(410f, 600f, 510f, 700f))
        doc.finishPage(page)
        val bytes = ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.size()
        assertTrue("pdf $bytes bytes", bytes < 100_000)
    }
}
