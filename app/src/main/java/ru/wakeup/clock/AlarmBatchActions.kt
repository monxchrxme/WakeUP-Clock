package ru.wakeup.clock

import android.content.Context

object AlarmBatchActions {
    fun disable(context: Context, ids: Set<Int>): List<Alarm> {
        val changed = AlarmStore(context).disableAll(ids)
        changed.forEach { AlarmScheduler.cancel(context, it.id) }
        return changed
    }
    fun delete(context: Context, ids: Set<Int>): List<Alarm> {
        // Persist removal first: a stale broadcast is then harmless even if the process stops before cancel().
        val deleted = AlarmStore(context).deleteAll(ids)
        deleted.forEach { AlarmScheduler.cancel(context, it.id) }
        return deleted
    }
    /** Restore settings without overwriting a newly created alarm that happens to have the same ID. */
    fun restore(context: Context, deleted: List<Alarm>): Int {
        val store = AlarmStore(context)
        var notScheduled = 0
        for (alarm in deleted) {
            if (store.alarms().any { it.id == alarm.id }) continue
            if (!AlarmScheduler.schedule(context, alarm, preserveFuture = true)) {
                store.save(alarm.copy(enabled = false, scheduledAt = 0))
                notScheduled++
            }
        }
        return notScheduled
    }
}
