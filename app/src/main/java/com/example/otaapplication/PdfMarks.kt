package com.example.otaapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * The letterhead logo and the agency stamp, drawn as vector paths. On a PdfDocument canvas this
 * writes a few KB of path operators instead of an embedded multi-megabyte bitmap, and stays sharp
 * at any zoom. Paths are parsed once per process.
 */
object PdfMarks {
    private const val VIEWPORT = 3264f
    private val LOGO_COLOR = Color.rgb(0xF8, 0x94, 0x3A)

    @Volatile private var logo: Path? = null
    @Volatile private var stamp: Path? = null

    private fun parse(data: String): Path = PathParser.createPathFromPathData(data)

    fun drawLogo(context: Context, canvas: Canvas, dst: RectF) {
        val path = logo ?: parse(context.getString(R.string.logo_path)).also { logo = it }
        draw(canvas, path, dst, LOGO_COLOR)
    }

    fun drawStamp(context: Context, canvas: Canvas, dst: RectF) {
        val path = stamp ?: parse(context.resources.openRawResource(R.raw.stamp_path).bufferedReader().use { it.readText() }).also { stamp = it }
        draw(canvas, path, dst, Color.BLACK)
    }

    private fun draw(canvas: Canvas, path: Path, dst: RectF, color: Int) {
        val m = Matrix().apply {
            setScale(dst.width() / VIEWPORT, dst.height() / VIEWPORT)
            postTranslate(dst.left, dst.top)
        }
        canvas.save()
        canvas.concat(m)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        canvas.restore()
    }
}
