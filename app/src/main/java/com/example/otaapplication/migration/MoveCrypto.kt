package com.example.otaapplication.migration

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted file that carries this phone's data to the new app. A random recovery code (100
 * bits, shown to the user as XXXXX-XXXXX-XXXXX-XXXXX) is the key. The data is split into 1 MiB
 * AES-256-GCM chunks, each bound to its position and to whether it is the last one, so a file that
 * was cut short, reordered or edited is refused instead of restoring half the data.
 */
object MoveCrypto {
    private val MAGIC = "OTAMOVE1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val CHUNK = 1 shl 20
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I, O, 0 or 1 to misread
    private const val CODE_CHARS = 20

    private val random = SecureRandom()

    fun newCode(): String =
        (1..CODE_CHARS).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(5).joinToString("-")

    /** The code as typed (case, spaces and dashes don't matter); null if it can't be a code. */
    fun normalize(code: String): String? =
        code.uppercase().filter { it.isLetterOrDigit() }.takeIf { s -> s.length == CODE_CHARS && s.all { it in ALPHABET } }

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val normalized = normalize(code) ?: throw IOException("That is not a valid recovery code")
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(normalized.toByteArray(Charsets.US_ASCII))
        return SecretKeySpec(md.digest(), "AES")
    }

    fun encrypt(out: OutputStream, code: String): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = key(code, salt)
        out.write(MAGIC)
        out.write(salt)
        return EncryptingStream(out, key)
    }

    fun decrypt(input: InputStream, code: String): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (_: EOFException) {
            throw IOException("This is not an OTA Business move file")
        }
        if (!magic.contentEquals(MAGIC)) throw IOException("This is not an OTA Business move file")
        val salt = ByteArray(SALT_BYTES).also { data.readFully(it) }
        return DecryptingStream(data, key(code, salt))
    }

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private class EncryptingStream(private val out: OutputStream, private val key: SecretKeySpec) : OutputStream() {
        private val buffer = ByteArrayOutputStream(CHUNK)
        private var index = 0L
        private var closed = false

        override fun write(b: Int) {
            buffer.write(b)
            if (buffer.size() >= CHUNK) flushChunk(false)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - buffer.size())
                buffer.write(b, o, n)
                o += n
                left -= n
                if (buffer.size() >= CHUNK) flushChunk(false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(index++, last))
            }
            val ct = cipher.doFinal(buffer.toByteArray())
            buffer.reset()
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(iv)
            out.write(ct)
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(true)
            out.close()
        }
    }

    private class DecryptingStream(private val input: DataInputStream, private val key: SecretKeySpec) : InputStream() {
        private var chunk = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var finished = false

        private fun next(): Boolean {
            if (finished) return false
            val size = try {
                input.readInt()
            } catch (_: EOFException) {
                throw IOException("The move file is incomplete (cut short)")
            }
            if (size < 16 || size > CHUNK + 16) throw IOException("The move file is damaged")
            val iv = ByteArray(IV_BYTES)
            val ct = ByteArray(size)
            try {
                input.readFully(iv)
                input.readFully(ct)
            } catch (_: EOFException) {
                throw IOException("The move file is incomplete (cut short)")
            }
            // Only the last chunk is authenticated as "last": try "not last" first, then "last".
            for (last in listOf(false, true)) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                        updateAAD(aad(index, last))
                    }
                    chunk = cipher.doFinal(ct)
                    pos = 0
                    index++
                    finished = last
                    return true
                } catch (_: AEADBadTagException) {
                }
            }
            throw IOException(if (index == 0L) "Wrong recovery code, or the file is damaged" else "The move file is damaged")
        }

        override fun read(): Int {
            while (pos >= chunk.size) if (!next()) return -1
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!next()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }
}
