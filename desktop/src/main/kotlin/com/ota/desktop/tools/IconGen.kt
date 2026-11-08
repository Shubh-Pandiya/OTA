package com.ota.desktop.tools

import com.ota.desktop.pdf.Marks
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Writes the app icons (packaging/ota.ico, ota.icns, ota.png) from the vector logo. Run: ./gradlew generateIcons */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "packaging").apply { mkdirs() }
    File(dir, "ota.png").writeBytes(png(512))
    writeIco(File(dir, "ota.ico"), listOf(16, 20, 24, 32, 40, 48, 64, 128, 256))
    writeIcns(File(dir, "ota.icns"))
    println("Icons written to ${dir.absolutePath}")
}

fun renderIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val s = size.toDouble()
    val inset = s * 0.04
    val box = RoundRectangle2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, s * 0.42, s * 0.42)
    g.color = Color.WHITE
    g.fill(box)
    g.color = Color(0xFF, 0x6F, 0x00, 70)
    g.stroke = java.awt.BasicStroke((s / 64).toFloat().coerceAtLeast(1f))
    g.draw(box)
    val logo = s * 0.84
    g.color = Color(Marks.LOGO_RGB)
    g.fill(Marks.awtPath(Marks.logo, (s - logo) / 2, (s - logo) / 2, logo))
    g.dispose()
    return img
}

private fun png(size: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(renderIcon(size), "png", it) }.toByteArray()

/** ICO with PNG-compressed entries (supported since Windows Vista). */
private fun writeIco(file: File, sizes: List<Int>) {
    val images = sizes.map { it to png(it) }
    val out = ByteArrayOutputStream()
    fun le16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
    le16(0); le16(1); le16(images.size)
    var offset = 6 + 16 * images.size
    for ((size, data) in images) {
        out.write(if (size >= 256) 0 else size); out.write(if (size >= 256) 0 else size)
        out.write(0); out.write(0); le16(1); le16(32); le32(data.size); le32(offset)
        offset += data.size
    }
    images.forEach { out.write(it.second) }
    file.writeBytes(out.toByteArray())
}

/** ICNS with PNG entries. */
private fun writeIcns(file: File) {
    val entries = listOf("icp4" to 16, "icp5" to 32, "icp6" to 64, "ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024)
        .map { (type, size) -> type to png(size) }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { d ->
        d.writeBytes("icns")
        d.writeInt(8 + entries.sumOf { 8 + it.second.size })
        for ((type, data) in entries) {
            d.writeBytes(type); d.writeInt(8 + data.size); d.write(data)
        }
    }
    file.writeBytes(bytes.toByteArray())
}
