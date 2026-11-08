package com.example.otaapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a cheque photo or payment screenshot into a small JPEG for the advice PDF: upright
 * (EXIF rotation applied), at most [MAX_EDGE] px on the long edge and about 100–200 KB.
 */
object AdviceImageImport {
    const val MAX_EDGE = 1600
    private const val TARGET_BYTES = 200 * 1024
    private val QUALITIES = intArrayOf(82, 74, 66, 58)

    /** Writes the compressed image to [target]; false if the picture couldn't be read. */
    fun compress(context: Context, source: Uri, target: File): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = resolver.openInputStream(source) ?: return false
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // Decode at the smallest power-of-two size still at least MAX_EDGE, then scale exactly.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val orientation = runCatching {
            resolver.openInputStream(source)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        val upright = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }

        // Screenshots may have transparency; JPEG needs an opaque white background.
        val opaque = if (!upright.hasAlpha()) upright else {
            Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888).also { out ->
                android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(upright, 0f, 0f, null) }
                upright.recycle()
            }
        }
        try {
            val bytes = ByteArrayOutputStream()
            for (quality in QUALITIES) {
                bytes.reset()
                opaque.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
                if (bytes.size() <= TARGET_BYTES) break
            }
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".part")
            temp.writeBytes(bytes.toByteArray())
            if (!temp.renameTo(target)) { temp.delete(); return false }
            return true
        } finally {
            opaque.recycle()
        }
    }

    /** A fresh file + content uri for the camera app to write its photo into. */
    fun cameraTarget(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** A thumbnail-sized decode for previews (never the full image). */
    fun thumbnail(file: File, edgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = max(1, (max(bounds.outWidth, bounds.outHeight) / edgePx.toFloat()).roundToInt().takeHighestOneBit())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
