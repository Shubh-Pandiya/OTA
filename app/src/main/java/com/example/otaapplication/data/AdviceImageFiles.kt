package com.example.otaapplication.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Cheque photos and transaction screenshots attached to payment advices, kept as compressed
 * JPEGs under `files/advice_images/<advice id>/<image id>.jpg`. The same relative path is the
 * object name in the cloud's `advice-images` Storage bucket.
 */
class AdviceImageFiles(private val root: File) {
    fun file(path: String): File = File(root, path)

    fun file(adviceId: String, imageId: String): File = file(path(adviceId, imageId))

    fun exists(path: String): Boolean = file(path).isFile

    fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    /** Writes via a temp file so a half-written image is never picked up. */
    fun write(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun delete(path: String) {
        val target = file(path)
        target.delete()
        target.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    /** Images picked in the editor before the advice is saved (a new advice has no id yet). */
    fun staging(imageId: String): File = File(root, "$STAGING/$imageId.jpg").also { it.parentFile?.mkdirs() }

    /** Moves a staged image to its advice; true when the image is (now) in place. */
    fun commit(adviceId: String, imageId: String): Boolean {
        val target = file(adviceId, imageId)
        if (target.isFile) return true
        val staged = File(root, "$STAGING/$imageId.jpg")
        if (!staged.isFile) return false
        target.parentFile?.mkdirs()
        return staged.renameTo(target) || runCatching { staged.copyTo(target, overwrite = true); staged.delete() }.isSuccess
    }

    /** Clears staged images left behind by an editor that was closed without saving. */
    fun clearStaging() {
        File(root, STAGING).listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val STAGING = "staging"

        fun path(adviceId: String, imageId: String) = "$adviceId/$imageId.jpg"

        fun newId(): String = UUID.randomUUID().toString()

        fun forContext(context: Context) = AdviceImageFiles(File(context.applicationContext.filesDir, "advice_images"))
    }
}
