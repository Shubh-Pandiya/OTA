package com.example.otaapplication

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Opens the Android share sheet for a PDF this app wrote under Documents/OTA Orders (WhatsApp, email, …). */
object PdfShare {
    fun share(context: Context, file: File, subject: String, chooserTitle: String) {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "This PDF can't be shared from here", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app to share the PDF with", Toast.LENGTH_SHORT).show()
        }
    }
}
