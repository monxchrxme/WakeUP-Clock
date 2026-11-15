package ru.wakeup.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object AlarmScheduler {
    const val RECOVER = "ru.wakeup.clock.RECOVER"
    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun operation(context: Context, id: Int, at: Long) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, AlarmReceiver::class.java).setData(Uri.parse("wakeup://alarm/$id"))
            .putExtra("id", id).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, alarm: Alarm, preserveFuture: Boolean = false): Boolean {
        val store = AlarmStore(context)
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            store.save(alarm.copy(scheduledAt = 0))
            return true
        }
        if (!permitted(context)) return false
        val now = System.currentTimeMillis()
        val at = if (preserveFuture && alarm.scheduledAt > now) alarm.scheduledAt else NextAlarm.at(alarm, now)
        val show = PendingIntent.getActivity(context, alarm.id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            context.getSystemService(AlarmManager::class.java)
                .setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation(context, alarm.id, at))
            store.save(alarm.copy(scheduledAt = at))
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context, id, 0))
    }

    fun restore(context: Context, recompute: Boolean) {
        val store = AlarmStore(context)
        val now = System.currentTimeMillis()
        for (alarm in store.alarms().filter { it.enabled }) {
            // Ring recently missed alarms after reboot rather than silently moving a one-shot to tomorrow.
            if (!recompute && alarm.scheduledAt > 0 && alarm.scheduledAt <= now && now - alarm.scheduledAt < 6 * 3600_000L) {
                fire(context, alarm.id, alarm.scheduledAt, startService = false)
            } else schedule(context, alarm, preserveFuture = !recompute)
        }
        if (store.sessions().isNotEmpty()) scheduleRecovery(context)
    }

    fun scheduleRecovery(context: Context) {
        if (!permitted(context)) return
        val intent = PendingIntent.getBroadcast(context, -1, Intent(context, AlarmReceiver::class.java).setAction(RECOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = PendingIntent.getActivity(context, -1, Intent(context, RingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2_000, show), intent) }
    }

    fun fire(context: Context, id: Int, at: Long, startService: Boolean = true) {
        val store = AlarmStore(context)
        val alarm = store.alarms().find { it.id == id && it.enabled && it.scheduledAt == at } ?: return
        if (store.sessions().none { !it.test && it.alarm.id == id && it.alarm.scheduledAt == at }) store.enqueue(alarm)
        if (alarm.days == 0) store.save(alarm.copy(enabled = false, scheduledAt = 0))
        else schedule(context, alarm)
        if (startService) RingService.start(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.RECOVER) {
            if (AlarmStore(context).sessions().isNotEmpty()) RingService.start(context)
        } else AlarmScheduler.fire(context, intent.getIntExtra("id", -1), intent.getLongExtra("at", 0))
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recompute = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        AlarmScheduler.restore(context, recompute)
    }
}
