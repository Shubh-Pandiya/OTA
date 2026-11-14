package com.example.otaapplication.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class MoveCryptoTest {
    private val code = MoveCrypto.newCode()
    private val data = Random(7).nextBytes(MoveCrypto.CHUNK * 2 + 123)

    private fun seal(bytes: ByteArray = data): ByteArray {
        val out = ByteArrayOutputStream()
        MoveCrypto.encrypt(out, code).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, with: String = code) =
        MoveCrypto.decrypt(ByteArrayInputStream(sealed), with).use { it.readBytes() }

    private fun fails(block: () -> Unit): String =
        try { block(); throw AssertionError("expected a failure") } catch (e: IOException) { e.message.orEmpty() }

    @Test
    fun roundTripsAcrossChunksAndEmpty() {
        assertArrayEquals(data, open(seal()))
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
        assertArrayEquals(ByteArray(MoveCrypto.CHUNK) { 1 }, open(seal(ByteArray(MoveCrypto.CHUNK) { 1 })))
    }

    @Test
    fun codeIsForgivingToTypeButStrict() {
        assertEquals(23, code.length)
        assertArrayEquals(data, open(seal(), code.lowercase().replace("-", " ")))
        assertNull(MoveCrypto.normalize("ABCDE-ABCDE-ABCDE-ABCD0"))
        assertNull(MoveCrypto.normalize("short"))
    }

    @Test
    fun refusesWrongCodeTruncationAndTampering() {
        val sealed = seal()
        var other = MoveCrypto.newCode()
        while (other == code) other = MoveCrypto.newCode()
        assertTrue(fails { open(sealed, other) }.contains("code"))
        // Cut exactly at a chunk boundary: every remaining chunk is valid but the last one is missing.
        val firstChunkEnd = 8 + 16 + 4 + 12 + MoveCrypto.CHUNK + 16
        fails { open(sealed.copyOf(firstChunkEnd)) }
        fails { open(sealed.copyOf(sealed.size - 5)) }
        fails { open(sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }) }
        fails { open("not a move file".toByteArray()) }
    }
}
