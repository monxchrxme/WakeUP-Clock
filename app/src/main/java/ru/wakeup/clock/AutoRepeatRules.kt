package ru.wakeup.clock

object AutoRepeatRules {
    const val MAX_MINUTES = 60

    fun shouldPause(session: RingSession, elapsedMillis: Long): Boolean = !session.test &&
        session.alarm.autoRepeatMinutes > 0 && elapsedMillis >= session.alarm.ringLimitMinutes.coerceAtLeast(1) * 60_000L

    /** This is still the same unconfirmed alarm, so a QR opened before the pause remains usable. */
    fun next(session: RingSession, now: Long, elapsedMillis: Long): RingSession? {
        if (!shouldPause(session, elapsedMillis)) return null
        return session.copy(dueAt = now + session.alarm.autoRepeatMinutes * 60_000L,
            selectedSound = null, ringingSince = 0, automaticRepeat = true)
    }
}
