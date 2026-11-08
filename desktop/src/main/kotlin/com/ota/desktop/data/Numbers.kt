package com.ota.desktop.data

import com.example.otaapplication.AdviceNumber
import com.example.otaapplication.OrderNumber
import com.ota.desktop.db.Db
import com.ota.desktop.db.Kv
import com.ota.desktop.db.query
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-staff order and advice numbers, the same allocators as the phones:
 * {digit}{yyyyMMdd}{001…} and PA{digit}{yyyyMMdd}{001…}. A number is taken from the persisted
 * counter and every number already stored (deleted rows and rows pulled from phones included),
 * so it is never reused; the cloud's unique index catches the rest and the row is renumbered.
 */
class Numbers(private val db: Db, private val kv: Kv, private val today: () -> LocalDate = LocalDate::now) {

    enum class Kind(val table: String, val column: String, val key: String) {
        ORDER("orders", "order_no", "counter.order"),
        ADVICE("payment_advices", "advice_no", "counter.advice");

        fun prefix(code: Int, day: LocalDate) = if (this == ORDER) OrderNumber.prefix(code, day) else AdviceNumber.prefix(code, day)
        fun format(prefix: String, serial: Int) = if (this == ORDER) OrderNumber.format(prefix, serial) else AdviceNumber.format(prefix, serial)
        fun prefixOf(no: String) = if (this == ORDER) OrderNumber.prefixOf(no) else AdviceNumber.prefixOf(no)
        fun next(prefix: String, storedPrefix: String?, storedSerial: Int, used: List<String>) =
            if (this == ORDER) OrderNumber.nextSerial(prefix, storedPrefix, storedSerial, used) else AdviceNumber.nextSerial(prefix, storedPrefix, storedSerial, used)
    }

    private val lock = ReentrantLock()

    fun peek(kind: Kind, staffCode: Int): String = lock.withLock {
        val prefix = kind.prefix(staffCode, today())
        kind.format(prefix, nextSerial(kind, prefix))
    }

    /** Picks the next number and calls [save] with it; the number is used up only if [save] succeeds. */
    fun <T> assign(kind: Kind, staffCode: Int, save: (String) -> T): T = lock.withLock {
        val prefix = kind.prefix(staffCode, today())
        val serial = nextSerial(kind, prefix)
        val result = save(kind.format(prefix, serial))
        remember(kind, prefix, serial)
        result
    }

    /** A free number for the same staff member and day, after the cloud said [no] is taken. */
    fun replacementFor(kind: Kind, no: String): String? = lock.withLock {
        val prefix = kind.prefixOf(no) ?: return null
        val serial = nextSerial(kind, prefix)
        remember(kind, prefix, serial)
        kind.format(prefix, serial)
    }

    fun <T> locked(block: () -> T): T = lock.withLock(block)

    private fun nextSerial(kind: Kind, prefix: String): Int {
        val stored = kv[kind.key]?.split('|')
        val used = db.read { c -> c.query("select ${kind.column} from ${kind.table} where left(${kind.column}, ?) = ?", prefix.length, prefix) { it.getString(1) } }
        return kind.next(prefix, stored?.getOrNull(0), stored?.getOrNull(1)?.toIntOrNull() ?: 0, used)
    }

    private fun remember(kind: Kind, prefix: String, serial: Int) {
        val storedPrefix = kv[kind.key]?.substringBefore('|')
        if (storedPrefix == null || storedPrefix == prefix || prefix > storedPrefix) kv[kind.key] = "$prefix|$serial"
    }
}
