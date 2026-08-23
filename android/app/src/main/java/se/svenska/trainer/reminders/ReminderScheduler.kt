package se.svenska.trainer.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.svenska.trainer.MainActivity
import se.svenska.trainer.R
import java.time.LocalDateTime
import java.time.ZoneId

private const val CHANNEL_ID = "practice_reminders"
const val EXTRA_REMINDER_ID = "reminder_id"

/**
 * Schedules one alarm per reminder, for its next occurrence only.
 *
 * Android's repeating alarms cannot express "Mon, Tue and Fri" or "every other
 * day", so each firing computes and schedules the following one. That also
 * means a changed schedule takes effect immediately rather than at the next
 * repeat boundary.
 */
object ReminderScheduler {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Practice reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Nudges to listen at the times you chose"
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            // A distinct action per reminder keeps PendingIntents from being
            // treated as equal and overwriting each other.
            action = "monoglot.reminder.${reminder.id}"
        }
        return PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun schedule(context: Context, reminder: Reminder) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, reminder)
        alarms.cancel(pi)

        val next = reminder.nextAfter(LocalDateTime.now()) ?: return
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Exact alarms need a special permission on Android 12+. A reminder
        // that lands a few minutes late is fine, so fall back rather than
        // demanding it.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, reminder: Reminder) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, reminder))
    }

    fun rescheduleAll(context: Context, reminders: List<Reminder>) {
        ensureChannel(context)
        reminders.forEach { if (it.enabled) schedule(context, it) else cancel(context, it) }
    }

    fun notify(context: Context, reminder: Reminder) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = reminder.label.ifBlank { "Time to listen to some Swedish." }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monoglot)
            .setContentTitle("Monoglot")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(context)
                    .notify(reminder.id.hashCode(), notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; nothing useful to do here.
            }
        }
    }
}

/** Fires the notification, then queues the following occurrence. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ReminderStore(context.applicationContext)
                val reminder = store.all().firstOrNull { it.id == id } ?: return@launch
                if (!reminder.enabled) return@launch
                ReminderScheduler.notify(context.applicationContext, reminder)
                ReminderScheduler.schedule(context.applicationContext, reminder)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms do not survive a reboot; re-arm them all on boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ReminderStore(context.applicationContext)
                ReminderScheduler.rescheduleAll(context.applicationContext, store.all())
            } finally {
                pending.finish()
            }
        }
    }
}
