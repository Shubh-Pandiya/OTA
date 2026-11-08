package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.data.AdviceLine
import com.example.otaapplication.data.BalanceSide
import com.example.otaapplication.data.Bill
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.PaymentAdvice
import com.example.otaapplication.data.PaymentMode
import com.ota.desktop.pdf.AdvicePdf
import com.ota.desktop.pdf.OrderPdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTest {
    private val out = File("build/pdf-samples").apply { mkdirs() }

    private fun render(pdf: File) = Loader.loadPDF(pdf).use { doc ->
        val r = PDFRenderer(doc)
        repeat(doc.numberOfPages) { i -> ImageIO.write(r.renderImageWithDPI(i, 110f), "png", File(out, "${pdf.nameWithoutExtension}-p${i + 1}.png")) }
        PDFTextStripper().getText(doc) to doc.numberOfPages
    }

    @Test
    fun orderPdfIsSmallAndComplete() {
        val data = OrderFormData(
            date = "08-11-2026", offerNo = "420261108001",
            buyer = "Shree Ganesh Garments\n12, Rajwada, Indore\nGSTIN 23ABCDE1234F1Z5",
            supplier = "Raymond Ltd\nThane, Maharashtra",
            items = listOf(
                OrderItem("1", "Cotton shirting 58\" white, design 4012", "250", "₹ 145", "M"),
                OrderItem("2", "Poly-viscose suiting, navy", "10", "3200", "T"),
                OrderItem("3", "Linen blend", "100 kg", "90", "")
            ),
            remarks = "Deliver before Diwali. Packing in bales.",
            deliveryReady = "15 days", transportTo = "Indore", cashPayment = "30 days", lessDisc = "2%", side = "Right"
        )
        val file = OrderPdf.write(data, File(out, "order-sample.pdf"))
        val (text, pages) = render(file)
        assertEquals(1, pages)
        assertTrue(file.length() < 200_000, "order PDF is ${file.length()} bytes")
        listOf("ORDER FORM", "Offer No: 420261108001", "250 Mtr", "10 Than", "100 kg", "Shree Ganesh Garments", "Agent's Signature", "₹ 145", "SINCE 1991")
            .forEach { assertTrue(it in text, "missing '$it'") }
    }

    @Test
    fun longOrderBreaksPages() {
        val items = (1..60).map { OrderItem("$it", "Item $it particulars that are long enough to wrap onto a second line in the cell", "${it * 10}", "100", "P") }
        val (_, pages) = render(OrderPdf.write(OrderFormData(offerNo = "4202611080099", items = items), File(out, "order-long.pdf")))
        assertTrue(pages >= 3)
    }

    @Test
    fun advicePdf() {
        val advice = PaymentAdvice(
            id = "a", adviceNo = "PA420261108001", payDate = LocalDate.of(2026, 11, 8),
            buyer = "Shree Ganesh Garments", supplier = "Raymond Ltd", mode = PaymentMode.CHEQUE, reference = "004512",
            balanceAmount = 12500.0, balanceSide = BalanceSide.DR, remarks = "Balance next month.",
            lines = listOf(
                AdviceLine("l1", Bill("b1", "INV-2041", LocalDate.of(2026, 10, 2), 125000.0, lrNo = "LR 7781"), LineAmounts(100000.0, 2.0, 2500.0, 1000.0, null)),
                AdviceLine("l2", Bill("b2", "INV-2077", LocalDate.of(2026, 10, 20), 48000.0), LineAmounts(40000.0, null, null, null, 500.0))
            )
        )
        val file = AdvicePdf.write(advice, File(out, "advice-sample.pdf"))
        val (text, pages) = render(file)
        assertEquals(1, pages)
        assertTrue(file.length() < 200_000, "advice PDF is ${file.length()} bytes")
        listOf("PAYMENT ADVICE", "PA420261108001", "INV-2041", "LR: LR 7781", "(2%)", "1,00,000.00", "₹ 12,500.00 Dr", "Cheque number: 004512")
            .forEach { assertTrue(it in text, "missing '$it'") }
    }
}
