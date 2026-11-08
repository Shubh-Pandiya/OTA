package com.example.otaapplication.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A to-do on the Home screen, optionally with a reminder time. Phone-only; never synced. */
data class Todo(
    val id: Long = 0,
    val title: String,
    /** Epoch day the to-do is planned for. */
    val day: Long,
    /** Minute of the day the reminder rings, or null for a plain to-do. */
    val minute: Int? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    /** The reminder has rung (or its time had already passed when it was saved). */
    val notified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(day)

    fun remindAt(zone: ZoneId): Long? =
        minute?.let { date.atTime(it / 60, it % 60).atZone(zone).toInstant().toEpochMilli() }
}

/** Which to-dos show where. Pure logic so it can be unit tested. */
object TodoLists {
    private val order = compareBy<Todo>({ it.day }, { it.minute ?: Int.MAX_VALUE }, { it.createdAt })

    /** Open to-dos up to today (older ones carry over, oldest first), then the ones ticked today. */
    fun today(all: List<Todo>, today: LocalDate, zone: ZoneId): List<Todo> {
        val day = today.toEpochDay()
        val open = all.filter { !it.done && it.day <= day }.sortedWith(order)
        val doneToday = all.filter { it.done && it.doneAt?.let { at -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == today } == true }
            .sortedBy { it.doneAt }
        return open + doneToday
    }

    fun upcoming(all: List<Todo>, today: LocalDate): List<Todo> =
        all.filter { !it.done && it.day > today.toEpochDay() }.sortedWith(order)

    fun done(all: List<Todo>): List<Todo> = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0 }

    fun carriedOver(todo: Todo, today: LocalDate): Boolean = !todo.done && todo.day < today.toEpochDay()

    /** An alarm is needed for an open to-do whose reminder hasn't rung yet. */
    fun needsAlarm(todo: Todo): Boolean = !todo.done && !todo.notified && todo.minute != null

    /** True when the reminder time is already past, e.g. saved for 9 am at 11 am: no alert then. */
    fun isPast(todo: Todo, now: Long, zone: ZoneId): Boolean = todo.remindAt(zone)?.let { it <= now } ?: false

    /** Ticked to-dos older than this are cleared, so the list stays small. */
    const val KEEP_DONE_DAYS = 60L

    fun timeLabel(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.ROOT, "%d:%02d %s", h12, m, if (h < 12) "am" else "pm")
    }
}
