package com.example.otaapplication

import java.io.File
import java.io.FileOutputStream

/**
 * Files under Documents/ that were created by an earlier install of the app (before it was
 * uninstalled and installed again, e.g. from another computer) are hidden from this install and
 * can't be overwritten. These helpers pick a name this install can actually write.
 */
object PublicFiles {
    /** True if [file] can be written; creates it empty when missing, never truncates it. */
    fun claim(file: File): Boolean = runCatching { FileOutputStream(file, true).close() }.isSuccess

    /** [file] itself if writable, else "name (2).ext", "name (3).ext", … */
    fun writable(file: File): File {
        if (claim(file)) return file
        for (n in 2..99) {
            val alt = File(file.parentFile, "${file.nameWithoutExtension} ($n).${file.extension}")
            if (claim(alt)) return alt
        }
        return file
    }
}
