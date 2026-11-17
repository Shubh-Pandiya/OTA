package com.example.otaapplication

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.otaapplication.data.AdviceLine
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OrderHistoryStore
import com.example.otaapplication.data.OrderRepository
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PaymentMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Long buyer / supplier names wrap inside their boxes, which grow so nothing spills out. */
@RunWith(AndroidJUnit4::class)
class PdfPartyBoxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.getExternalFilesDir(null), "pdf-boxes").apply { mkdirs() }

    private val longBuyer = "Shree Shubh Laxmi Ganesh Garments and Textile Manufacturing Private Limited\n" +
        "12-A, Second Floor, Rajwada Main Road, Near Krishnapura Chhatri, Indore 452002, Madhya Pradesh\nGSTIN 23ABCDE1234F1Z5"
    private val longSupplier = "Raymond Limited (Textile Division) Suiting and Shirting Fabrics Wholesale Depot\n" +
        "Plot 156/H No. 2, Village Zadgaon, Ratnagiri Industrial Area, Thane, Maharashtra 400606"

    /** Renders page 1 at 2x to a PNG in [out]. */
    private fun renderFirstPage(pdf: File, name: String): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { p ->
                Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).also { bmp ->
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }

    /** No text between the party box's bottom border and the orange table header (beyond [maxStrayPt]). */
    private fun assertTextInsideBox(bmp: Bitmap, x0: Int, x1: Int, maxStrayPt: Float) {
        val s = bmp.width / 595f
        fun dark(y: Int) = ((x0 * s).toInt() until (x1 * s).toInt()).any { x -> bmp.getPixel(x, y).let { Color.red(it) < 100 && Color.green(it) < 100 } }
        fun orange(y: Int) = (0 until bmp.width step 4).count { x -> bmp.getPixel(x, y).let { Color.red(it) > 200 && Color.green(it) in 80..150 && Color.blue(it) < 60 } } > bmp.width / 8
        val top = (150 * s).toInt()
        val header = (top until bmp.height).first { orange(it) }
        // The box's bottom border: the last full-width dark row above the table header (skipping the grid's top line on the header).
        val border = (header - (3 * s).toInt() downTo top).first { y -> ((x0 * s).toInt() until (x1 * s).toInt()).all { x -> Color.red(bmp.getPixel(x, y)) < 160 } }
        val strayInk = (border + 2 until header - (3 * s).toInt()).filter { dark(it) }
        // Only the advice's one "PAYMENT MODE" line may sit between the box and the table.
        assertTrue("text below the box border at rows $strayInk", strayInk.isEmpty() || strayInk.last() - strayInk.first() < maxStrayPt * s)
    }

    @Test
    fun orderBoxesGrowForLongNames() {
        val db = OtaDatabase(context, name = null)
        val files = OrderFiles(context, LocalOrders(OrderHistoryStore(File(context.cacheDir, "pdf-box")), OrderRepository(db)))
        val pdf = File(out, "order-long-names.pdf")
        files.writePdf(
            OrderFormData(date = "08-11-2026", offerNo = "26270001", buyer = longBuyer, supplier = longSupplier,
                items = listOf(OrderItem("1", "Cotton shirting", "250", "145", "M")), remarks = "Long names check."),
            pdf
        )
        assertTextInsideBox(renderFirstPage(pdf, "phone-order"), 45, 285, 0f)
        db.close()
    }

    @Test
    fun adviceBoxesGrowForLongNames() {
        val advice = PaymentAdvice(
            id = "a", adviceNo = "26270002", payDate = LocalDate.of(2026, 11, 8), buyer = longBuyer, supplier = longSupplier,
            mode = PaymentMode.CHEQUE, reference = "004512", balanceAmount = null, balanceSide = null, remarks = "",
            lines = listOf(AdviceLine("l1", Bill("b1", "INV-2041", LocalDate.of(2026, 10, 2), 1000.0), LineAmounts(1000.0, null, null, null, null)))
        )
        val render = AdvicePdf::class.java.getDeclaredMethod("render", android.content.Context::class.java, PaymentAdvice::class.java, List::class.java)
            .apply { isAccessible = true }
        val bytes = render.invoke(AdvicePdf, context, advice, emptyList<ByteArray>()) as ByteArray
        val pdf = File(out, "advice-long-names.pdf").apply { writeBytes(bytes) }
        val bmp = renderFirstPage(pdf, "phone-advice")
        // Every wrapped line is drawn: the box must be taller than the old fixed 2-line box (145–195 pt).
        val s = bmp.width / 595f
        val border = ((147 * s).toInt() until bmp.height).firstOrNull { y -> ((50 * s).toInt() until (290 * s).toInt()).all { x -> Color.red(bmp.getPixel(x, y)) < 160 } }
        assertTrue("advice box did not grow (border at ${border?.div(s)})", border != null && border / s > 215)
        assertTextInsideBox(bmp, 45, 290, 14f)
    }
}
