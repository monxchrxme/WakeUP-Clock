package ru.wakeup.clock

import kotlin.random.Random

object IrritationRules {
    const val MAX_MINUTES = 60

    fun delayMinutes(session: RingSession): Int = if (session.alarm.irritationMinutes <= 0) 0
        else session.alarm.irritationMinutes + if (session.isWakeCheck) 5 else 0

    fun shouldStart(session: RingSession, elapsedMillis: Long): Boolean =
        delayMinutes(session).let { it > 0 && elapsedMillis >= it * 60_000L }

    fun elapsed(session: RingSession, now: Long): Long =
        if (session.ringingSince <= 0) 0 else (now - session.ringingSince).coerceAtLeast(0)

    fun song(alarm: Alarm, main: SoundChoice, random: Random = Random.Default): SoundChoice =
        if (alarm.randomSound) RandomSounds.pick(alarm, main.uri, random) else main

    /** Keep the copy out of phase, including for short looping tracks and positions near the end. */
    fun offset(duration: Int, mainPosition: Int, random: Random = Random.Default): Int {
        if (duration <= 1) return 0
        val gap = (duration / 4).coerceIn(1, 5_000)
        val distance = random.nextInt(gap, duration - gap + 1)
        return ((mainPosition.coerceAtLeast(0).toLong() + distance) % duration).toInt()
    }
}
