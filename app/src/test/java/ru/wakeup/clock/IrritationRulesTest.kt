package ru.wakeup.clock

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class IrritationRulesTest {
    private val main = SoundChoice("file:///main", "Основная")
    private val other = SoundChoice("file:///other", "Другая")
    private val alarm = Alarm(1, irritationMinutes = 2)
    private val session = RingSession("ring", alarm, 1_000)

    @Test fun disabledFeatureStaysOffIncludingWakeChecks() {
        for (check in listOf(false, true)) {
            val disabled = session.copy(alarm = alarm.copy(irritationMinutes = 0), isWakeCheck = check)
            assertEquals(0, IrritationRules.delayMinutes(disabled))
            assertFalse(IrritationRules.shouldStart(disabled, 10 * 3600_000))
        }
    }
    @Test fun firstRingUsesExactlyTheConfiguredMinuteBoundary() {
        assertFalse(IrritationRules.shouldStart(session, 119_999))
        assertTrue(IrritationRules.shouldStart(session, 120_000))
        assertTrue(IrritationRules.shouldStart(session, 180_000))
    }
    @Test fun wakeChecksAndTheirSnoozesGetFiveExtraMinutes() {
        val check = session.copy(isWakeCheck = true)
        assertEquals(7, IrritationRules.delayMinutes(check))
        assertFalse(IrritationRules.shouldStart(check, 419_999))
        assertTrue(IrritationRules.shouldStart(check, 420_000))
        assertEquals(7, IrritationRules.delayMinutes(check.copy(snoozeCount = 1)))
        assertEquals(2, IrritationRules.delayMinutes(session.copy(snoozeCount = 1)))
    }
    @Test fun recoveryCountsFromActualRingingAndCannotProduceNegativeElapsedTime() {
        assertEquals(0L, IrritationRules.elapsed(session, 999_999))
        val started = session.copy(ringingSince = 100_000)
        assertEquals(123_456L, IrritationRules.elapsed(started, 223_456))
        assertEquals(0L, IrritationRules.elapsed(started, 99_999))
    }
    @Test fun singleSongKeepsItsSourceAndRandomModeChoosesAnotherSong() {
        assertEquals(main, IrritationRules.song(alarm, main))
        val randomAlarm = alarm.copy(randomSound = true, randomSounds = listOf(main, other))
        repeat(20) { assertEquals(other, IrritationRules.song(randomAlarm, main)) }
    }
    @Test fun offsetVariesAndStaysAwayFromTheMainTrackAcrossLoopBoundaries() {
        val random = Random(42)
        val positions = mutableSetOf<Int>()
        for (position in listOf(0, 180_000, 179_999, 360_000)) repeat(100) {
            val offset = IrritationRules.offset(180_000, position, random)
            assertTrue(offset in 0 until 180_000)
            val delta = (offset - position % 180_000 + 180_000) % 180_000
            assertTrue(delta in 5_000..175_000)
            positions.add(offset)
        }
        assertTrue(positions.size > 300)
    }
    @Test fun shortOrUnknownDurationCannotCrashOffsetSelection() {
        assertEquals(0, IrritationRules.offset(0, 0))
        assertEquals(0, IrritationRules.offset(1, 0))
        for (duration in 2..20) for (position in 0 until duration) {
            val offset = IrritationRules.offset(duration, position)
            assertTrue(offset in 0 until duration)
            assertNotEquals(position, offset)
        }
    }
}
