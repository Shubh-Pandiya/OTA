package com.ota.desktop.db

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque

/**
 * Small JDBC helper over the local database: a handful of pooled connections, transactions and
 * typed parameter binding. No ORM: the SQL is plain and lives next to the code that uses it.
 */
class Db(private val open: () -> Connection, private val poolSize: Int = 4) : AutoCloseable {
    private val idle = LinkedBlockingDeque<Connection>()
    @Volatile private var created = 0

    private fun borrow(): Connection {
        idle.pollFirst()?.let { c -> if (runCatching { c.isValid(2) }.getOrDefault(false)) return c else runCatching { c.close() } }
        synchronized(this) {
            if (created < poolSize) {
                created++
                return runCatching(open).getOrElse { created--; throw it }
            }
        }
        return idle.takeFirst()
    }

    private fun release(c: Connection, broken: Boolean) {
        if (broken || c.isClosed) {
            runCatching { c.close() }
            synchronized(this) { created-- }
        } else {
            idle.offerFirst(c)
        }
    }

    fun <T> read(block: (Connection) -> T): T {
        val c = borrow()
        var broken = false
        try {
            c.autoCommit = true
            return block(c)
        } catch (e: Throwable) {
            broken = runCatching { !c.isValid(1) }.getOrDefault(true)
            throw e
        } finally {
            release(c, broken)
        }
    }

    fun <T> tx(block: (Connection) -> T): T {
        val c = borrow()
        var broken = false
        try {
            c.autoCommit = false
            val result = block(c)
            c.commit()
            return result
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            broken = runCatching { !c.isValid(1) }.getOrDefault(true)
            throw e
        } finally {
            runCatching { c.autoCommit = true }
            release(c, broken)
        }
    }

    /** Closes the idle connections. The pool stays usable and opens new ones when needed. */
    override fun close() {
        while (true) {
            val c = idle.pollFirst() ?: return
            runCatching { c.close() }
            synchronized(this) { created-- }
        }
    }
}

// ---------------------------------------------------------------- statement helpers

fun PreparedStatement.bind(args: Array<out Any?>): PreparedStatement {
    args.forEachIndexed { i, v ->
        val n = i + 1
        when (v) {
            null -> setNull(n, Types.NULL)
            is String -> setString(n, v)
            is Int -> setInt(n, v)
            is Long -> setLong(n, v)
            is Double -> setBigDecimal(n, BigDecimal.valueOf(v))
            is BigDecimal -> setBigDecimal(n, v)
            is Boolean -> setBoolean(n, v)
            is LocalDate -> setObject(n, v)
            is UUID -> setObject(n, v)
            is Json -> setObject(n, org.postgresql.util.PGobject().apply { type = "jsonb"; value = v.text })
            is SqlArray -> setArray(n, connection.createArrayOf(v.type, v.values.toTypedArray()))
            else -> setObject(n, v)
        }
    }
    return this
}

/** A jsonb parameter. */
@JvmInline value class Json(val text: String)

/** An array parameter, e.g. `= any(?)`. */
class SqlArray(val type: String, val values: List<Any>)

fun uuidArray(ids: Collection<String>) = SqlArray("uuid", ids.map(UUID::fromString))

fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { it.bind(args).executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args).executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

fun ResultSet.str(col: String): String = getString(col).orEmpty()
fun ResultSet.strOrNull(col: String): String? = getString(col)
fun ResultSet.dbl(col: String): Double? = getBigDecimal(col)?.toDouble()
fun ResultSet.date(col: String): LocalDate? = getObject(col, LocalDate::class.java)
fun ResultSet.id(col: String = "id"): String = getObject(col).toString()

/** Edit time for a changed row: now, but always later than its previous edit (clocks can go back). */
fun nextEditTime(previous: Long?): Long = maxOf(System.currentTimeMillis(), (previous ?: 0L) + 1)
