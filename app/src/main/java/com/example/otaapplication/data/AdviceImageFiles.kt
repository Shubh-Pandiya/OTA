package com.example.otaapplication.data

import android.content.Context
import com.example.otaapplication.security.LocalKeys
import java.io.File
import java.util.UUID

/** Encrypts image bytes at rest. */
interface ImageSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with this phone's Android Keystore key. */
object KeystoreImageSealer : ImageSealer {
    override fun seal(plain: ByteArray) = LocalKeys.seal(plain)
    override fun open(sealed: ByteArray) = LocalKeys.open(sealed)
}

/**
 * Cheque photos and transaction screenshots attached to payment advices, kept as compressed
 * JPEGs under `files/advice_images/<advice id>/<image id>.jpg`. With a [sealer] the files are
 * encrypted (a short magic header, then the sealed JPEG); always read them through [readFile].
 * Files written by older versions (plain JPEG) still read, and [sealExisting] encrypts them.
 */
class AdviceImageFiles(private val root: File, private val sealer: ImageSealer? = null) {
    fun file(path: String): File = File(root, path)

    fun file(adviceId: String, imageId: String): File = file(path(adviceId, imageId))

    /** Removes every stored image (used when this phone's data is cleared). */
    fun clear() {
        root.deleteRecursively()
    }

    fun exists(path: String): Boolean = file(path).isFile

    fun read(path: String): ByteArray? = readFile(file(path))

    /** The JPEG bytes of [file] (decrypted), or null if it is missing or unreadable. */
    fun readFile(file: File): ByteArray? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (!isSealed(bytes)) return bytes
        return runCatching { (sealer ?: KeystoreImageSealer).open(bytes.copyOfRange(MAGIC.size, bytes.size)) }.getOrNull()
    }

    fun write(path: String, bytes: ByteArray) = writeFile(file(path), bytes)

    /** Writes (encrypted when sealing) via a temp file so a half-written image is never picked up. */
    fun writeFile(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(sealer?.let { MAGIC + it.seal(bytes) } ?: bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }

    /** Encrypts images saved by a version that kept them plain. Returns how many were converted. */
    fun sealExisting(): Int {
        if (sealer == null) return 0
        var count = 0
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") }.forEach { f ->
            val bytes = f.readBytes()
            if (!isSealed(bytes)) {
                writeFile(f, bytes)
                count++
            }
        }
        return count
    }

    /** Every stored image as (relative path, decrypted bytes); staged images are skipped. */
    fun exportAll(): Sequence<Pair<String, ByteArray>> =
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") && it.parentFile?.name != STAGING }
            .mapNotNull { f -> readFile(f)?.let { f.relativeTo(root).invariantSeparatorsPath to it } }

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
        private val MAGIC = "OTAIMG1".toByteArray(Charsets.US_ASCII)

        fun isSealed(bytes: ByteArray) = bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        fun path(adviceId: String, imageId: String) = "$adviceId/$imageId.jpg"

        fun newId(): String = UUID.randomUUID().toString()

        fun forContext(context: Context) = AdviceImageFiles(File(context.applicationContext.filesDir, "advice_images"), KeystoreImageSealer)
    }
}
