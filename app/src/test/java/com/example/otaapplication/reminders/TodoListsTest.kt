package com.example.otaapplication.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TodoListsTest {
    private val zone: ZoneId = ZoneOffset.ofHoursMinutes(5, 30)
    private val today = LocalDate.of(2026, 11, 12)
    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun todo(id: Long, day: LocalDate, minute: Int? = null, done: Boolean = false, doneAt: Long? = null, created: Long = id) =
        Todo(id = id, title = "t$id", day = day.toEpochDay(), minute = minute, done = done, doneAt = doneAt, createdAt = created)

    @Test
    fun todayCarriesOverOpenItemsAndKeepsOnlyTodaysTicks() {
        val all = listOf(
            todo(1, today, minute = 17 * 60),
            todo(2, today),
            todo(3, today, minute = 9 * 60),
            todo(4, today.minusDays(3)),                                            // carried over
            todo(5, today.minusDays(1), done = true, doneAt = at(today.minusDays(1), 18)), // ticked yesterday
            todo(6, today.minusDays(1), done = true, doneAt = at(today, 10)),        // ticked today
            todo(7, today.plusDays(1), minute = 600)                                 // upcoming
        )
        assertEquals(listOf(4L, 3L, 1L, 2L, 6L), TodoLists.today(all, today, zone).map { it.id })
        assertEquals(listOf(7L), TodoLists.upcoming(all, today).map { it.id })
        assertEquals(listOf(6L, 5L), TodoLists.done(all).map { it.id })
        assertTrue(TodoLists.carriedOver(all[3], today))
        assertFalse(TodoLists.carriedOver(all[0], today))
        assertFalse(TodoLists.carriedOver(all[4], today))
    }

    @Test
    fun alarmsOnlyForOpenUnrungReminders() {
        assertTrue(TodoLists.needsAlarm(todo(1, today, minute = 600)))
        assertFalse(TodoLists.needsAlarm(todo(1, today)))
        assertFalse(TodoLists.needsAlarm(todo(1, today, minute = 600, done = true)))
        assertFalse(TodoLists.needsAlarm(todo(1, today, minute = 600).copy(notified = true)))
    }

    @Test
    fun reminderTimeUsesTheDayAndZone() {
        val t = todo(1, today, minute = 16 * 60 + 30)
        assertEquals(at(today, 16) + 30 * 60_000, t.remindAt(zone))
        assertTrue(TodoLists.isPast(t, at(today, 17), zone))
        assertFalse(TodoLists.isPast(t, at(today, 16), zone))
        assertFalse(TodoLists.isPast(todo(2, today), at(today, 23), zone))
    }

    @Test
    fun timeLabels() {
        assertEquals("12:00 am", TodoLists.timeLabel(0))
        assertEquals("9:05 am", TodoLists.timeLabel(9 * 60 + 5))
        assertEquals("12:30 pm", TodoLists.timeLabel(12 * 60 + 30))
        assertEquals("6:00 pm", TodoLists.timeLabel(18 * 60))
        assertEquals("11:59 pm", TodoLists.timeLabel(23 * 60 + 59))
    }
}
