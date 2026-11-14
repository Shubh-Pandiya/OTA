package com.example.otaapplication.cloud

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeltaPullTest {
    private data class Row(val id: String, val at: String)

    /** A cloud table answering the sync's keyset query: after (updated_at, id), oldest first. */
    private class Table(val rows: List<Row>) {
        val reads = mutableListOf<Pair<String?, String?>>()
        var failAt = -1

        fun read(after: String?, afterId: String?, limit: Int): List<Row> {
            reads += after to afterId
            if (reads.size == failAt) throw java.io.IOException("link dropped")
            val t = after?.let(Instant::parse)
            return rows.sortedWith(compareBy({ Instant.parse(it.at) }, { it.id })).filter { r ->
                t == null || Instant.parse(r.at) > t || (afterId != null && Instant.parse(r.at) == t && r.id > afterId)
            }.take(limit)
        }
    }

    private class Local {
        val applied = mutableListOf<Row>()
        var cursor: String? = null
    }

    private suspend fun pull(table: Table, local: Local, serverStart: String? = null, ahead: PullAhead? = null) =
        DeltaPull.run(
            saved = local.cursor, serverStart = serverStart, pageSize = 2,
            fetch = { after, afterId ->
                val read: suspend (String?, String?) -> List<Row> = { a, i -> table.read(a, i, 2) }
                ahead?.fetch("t", after, afterId, read) ?: read(after, afterId)
            },
            updatedAt = Row::at, id = Row::id,
            apply = { page -> local.applied += page; page.size },
            save = { local.cursor = it }
        )

    private val tenMinutesAgo = Instant.now().minusSeconds(600)
    private fun at(plusSeconds: Long) = tenMinutesAgo.plusSeconds(plusSeconds).toString()

    @Test
    fun rowsSharingATimestampAcrossPagesAreAllRead() = runBlocking {
        val table = Table(List(5) { Row("id-$it", at(0)) })
        val local = Local()
        assertEquals(5, pull(table, local))
        assertEquals(table.rows.map { it.id }, local.applied.map { it.id }.sorted())
        assertEquals(listOf(null to null, at(0) to "id-1", at(0) to "id-3"), table.reads)
        assertEquals(at(0), local.cursor)
    }

    @Test
    fun cursorAdvancesPerPageSoAFailedPullResumes() = runBlocking {
        val table = Table(List(5) { Row("id-$it", at(60L * it)) })
        val local = Local()
        table.failAt = 3
        runCatching { pull(table, local) }
        assertEquals(4, local.applied.size)
        assertEquals("cursor is the last applied page's newest row", at(180), local.cursor)

        table.reads.clear(); table.failAt = -1; local.applied.clear()
        pull(table, local)
        assertEquals(listOf("id-3", "id-4"), local.applied.map { it.id })
    }

    @Test
    fun settledRowsAreNotReadAgain() = runBlocking {
        val table = Table(List(5) { Row("id-$it", at(0)) })
        val local = Local()
        val serverStart = Instant.now().toString()
        pull(table, local, serverStart)
        assertEquals(Instant.parse(serverStart).minusSeconds(DeltaPull.CLOCK_SLACK_SECONDS).toString(), local.cursor)

        local.applied.clear()
        pull(table, local, Instant.now().toString())
        assertTrue(local.applied.isEmpty())

        // Without the cloud clock the cursor stays at the newest row and the overlap is read again.
        val legacy = Local()
        pull(table, legacy)
        legacy.applied.clear()
        pull(table, legacy)
        assertEquals(5, legacy.applied.size)
    }

    @Test
    fun pagesReadAheadAreUsedOnceAndAStaleStreamFallsBack() = runBlocking {
        val table = Table(List(5) { Row("id-$it", at(60L * it)) })
        val local = Local()
        coroutineScope {
            val ahead = PullAhead(this, null)
            ahead.start("t", local.cursor, 2, { a, i -> table.read(a, i, 2) }, Row::at, Row::id)
            pull(table, local, ahead = ahead)
            ahead.stop()
        }
        assertEquals(5, local.applied.size)
        assertEquals("each page is read once", 3, table.reads.size)

        coroutineScope {
            // Read ahead from the start, but the cursor has moved since: the stream is dropped.
            val ahead = PullAhead(this, null)
            ahead.start("t", null, 2, { a, i -> table.read(a, i, 2) }, Row::at, Row::id)
            local.applied.clear()
            pull(table, local, ahead = ahead)
            ahead.stop()
        }
        assertEquals(listOf("id-4"), local.applied.map { it.id })
    }
}
