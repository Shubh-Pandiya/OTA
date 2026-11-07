package com.example.otaapplication.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.otaapplication.MainActivity
import kotlin.math.roundToInt

/** System notification when cloud storage is almost full or full. Repeats at most once a day. */
internal object StorageAlert {
    private const val CHANNEL = "cloud_storage"
    private const val NOTIFICATION_ID = 4201
    private const val KEY_LEVEL = "storage_alert_level"
    private const val KEY_AT = "storage_alert_at"
    private const val REPEAT_MS = 24 * 60 * 60 * 1000L

    const val ALMOST_FULL_HINT = "Open the desktop app to archive orders and free space."
    const val FULL_HINT = "Changes are kept safely on this phone and upload automatically once space is freed. " +
        "Open the desktop app to archive orders."
    const val FULL = "Cloud storage is full. $FULL_HINT"

    fun update(context: Context, usage: CloudUsage?, full: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val level = when {
            full -> 2
            usage?.warning == true -> 1
            else -> 0
        }
        val manager = NotificationManagerCompat.from(context)
        if (level == 0) {
            if (prefs.getInt(KEY_LEVEL, 0) != 0) {
                manager.cancel(NOTIFICATION_ID)
                prefs.edit { remove(KEY_LEVEL).remove(KEY_AT) }
            }
            return
        }
        val now = System.currentTimeMillis()
        val due = level > prefs.getInt(KEY_LEVEL, 0) || now - prefs.getLong(KEY_AT, 0) >= REPEAT_MS
        if (!due || !canNotify(context)) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cloud storage", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warns when the shared cloud database is running out of space"
            }
        )
        val title = if (level == 2) "Cloud storage full" else "Cloud storage almost full"
        val detail = usage?.let { "${it.percent.roundToInt()}% of ${it.limitMb} MB used. " }.orEmpty()
        val text = detail + if (level == 2) FULL.substringAfter(". ") else "Open the desktop app to archive orders and free space."
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit { putInt(KEY_LEVEL, level).putLong(KEY_AT, now) }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; the in-app banner still shows.
        }
    }

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private const val PREFS = "ota_cloud"
}
