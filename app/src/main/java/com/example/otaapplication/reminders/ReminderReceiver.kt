package com.example.otaapplication.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** A reminder's alarm rang, or "Mark done" was tapped on its notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, 0L).takeIf { it > 0 } ?: return
        val pending = goAsync()
        thread(name = "reminder") {
            try {
                when (intent.action) {
                    Reminders.ACTION_FIRE -> Reminders.fire(context, id)
                    Reminders.ACTION_DONE -> Reminders.setDone(context, id, true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Sets reminder alarms again after a reboot, app update, clock change or a new exact-alarm permission. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        thread(name = "reminder-reschedule") {
            try {
                Reminders.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
