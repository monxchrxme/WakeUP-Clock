package ru.wakeup.clock

import java.util.UUID

/** The follow-up uses the original alarm snapshot, even if its saved settings are edited later. */
object FollowUpRules {
    /** Infer the waiting interval from the immutable snapshot, including checks saved by older versions. */
    fun earlyCancelAt(session: RingSession): Long? {
        if (session.test || !session.isWakeCheck || session.dueAt <= 0) return null
        val minutes = if (session.snoozeCount > 0) session.alarm.snoozeMinutes else session.alarm.wakeCheckMinutes
        if (minutes <= 0) return null
        return session.dueAt - minutes * 30_000L
    }

    fun canCancelEarly(session: RingSession, now: Long): Boolean =
        earlyCancelAt(session)?.let { now >= it && now < session.dueAt } ?: false

    fun canSnooze(session: RingSession): Boolean = !session.test &&
        session.alarm.snoozeMinutes > 0 && session.snoozeCount < session.alarm.maxSnoozes

    fun snooze(session: RingSession, now: Long): RingSession? {
        if (!canSnooze(session)) return null
        val due = now + session.alarm.snoozeMinutes * 60_000L
        return session.copy(token = UUID.randomUUID().toString(), startedAt = due, dueAt = due,
            snoozeCount = session.snoozeCount + 1, selectedSound = null)
    }

    fun wakeCheck(session: RingSession, now: Long): RingSession? {
        if (session.test || session.isWakeCheck || session.alarm.wakeCheckMinutes <= 0) return null
        val due = now + session.alarm.wakeCheckMinutes * 60_000L
        return RingSession(UUID.randomUUID().toString(), session.alarm, due,
            dueAt = due, isWakeCheck = true)
    }
}
