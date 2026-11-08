package com.example.otaapplication.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.otaapplication.MainActivity
import com.example.otaapplication.R
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Saves to-dos and keeps their alarms in step. Call from a background thread (it touches the database).
 *
 * Reminders ring on time when the phone allows exact alarms (Settings > Alarms & reminders); without
 * that permission Android may deliver them a few minutes late, and the Home card offers to turn it on.
 */
object Reminders {
    const val EXTRA_OPEN_HOME = "com.example.otaapplication.OPEN_HOME"
    internal const val ACTION_FIRE = "com.example.otaapplication.REMINDER_FIRE"
    internal const val ACTION_DONE = "com.example.otaapplication.REMINDER_DONE"
    internal const val EXTRA_ID = "todo_id"
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_BASE = 100_000

    fun save(context: Context, todo: Todo): Todo {
        val now = System.currentTimeMillis()
        // A time that has already passed doesn't ring; editing it to a later time arms it again.
        val armed = todo.copy(notified = todo.done || TodoLists.isPast(todo, now, ZoneId.systemDefault()))
        val saved = TodoStore.get(context).save(armed)
        schedule(context, saved)
        if (saved.done) cancelNotification(context, saved.id)
        return saved
    }

    fun setDone(context: Context, id: Long, done: Boolean) {
        val todo = TodoStore.get(context).get(id) ?: return
        save(context, todo.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null))
    }

    fun delete(context: Context, id: Long) {
        cancelAlarm(context, id)
        cancelNotification(context, id)
        TodoStore.get(context).delete(id)
    }

    /** After a reboot, app update, clock change or force stop, alarms are gone: set them again. */
    fun rescheduleAll(context: Context) {
        val store = TodoStore.get(context)
        store.pruneDone(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TodoLists.KEEP_DONE_DAYS))
        store.all().forEach { schedule(context, it) }
    }

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canRingOnTime(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))

    private fun schedule(context: Context, todo: Todo) {
        cancelAlarm(context, todo.id)
        if (!TodoLists.needsAlarm(todo)) return
        val at = todo.remindAt(ZoneId.systemDefault()) ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent(context, todo.id)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun cancelAlarm(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }

    private fun alarmIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Called when the alarm rings: shows the reminder unless it was ticked or already shown. */
    internal fun fire(context: Context, id: Long) {
        val store = TodoStore.get(context)
        val todo = store.get(id) ?: return
        if (todo.done || todo.notified) return
        store.markNotified(id)
        if (!canNotify(context)) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "To-dos you asked to be reminded about on the Home screen"
            }
        )
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markDone = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = listOfNotNull(todo.minute?.let { "Reminder at ${TodoLists.timeLabel(it)}" }, "Tap to open Home").joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(todo.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(0, "Mark done", markDone)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + id.toInt(), notification)
        } catch (_: SecurityException) {
            // Notifications turned off between the check and the call.
        }
    }

    internal fun cancelNotification(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + id.toInt())
    }
}
