package ru.wakeup.clock

import org.junit.Assert.*
import org.junit.Test

class FollowUpRulesTest {
    private val now = 1_794_740_400_000L
    private val alarm = Alarm(7, dismissMode = DismissMode.QR, snoozeMinutes = 10, wakeCheckMinutes = 10)
    private val session = RingSession("first", alarm, now)

    @Test fun snoozeUsesTenMinutesAndKeepsQrRequirement() {
        val next = FollowUpRules.snooze(session, now)!!
        assertEquals(now + 600_000, next.dueAt)
        assertEquals(next.dueAt, next.startedAt)
        assertEquals(DismissMode.QR, next.alarm.dismissMode)
        assertNotEquals(session.token, next.token)
    }
    @Test fun snoozeLimitCannotBeResetByRepeatedRinging() {
        val next = FollowUpRules.snooze(session, now)!!
        assertEquals(1, next.snoozeCount)
        assertFalse(FollowUpRules.canSnooze(next))
        assertNull(FollowUpRules.snooze(next, now + 600_000))
    }
    @Test fun configuredThreeSnoozesAreFinite() {
        var next = session.copy(alarm = alarm.copy(maxSnoozes = 3))
        repeat(3) { next = FollowUpRules.snooze(next, now)!! }
        assertNull(FollowUpRules.snooze(next, now))
    }
    @Test fun strictAlarmsKeepAllFollowUpsDisabled() {
        val strict = session.copy(alarm = Alarm(1))
        assertNull(FollowUpRules.snooze(strict, now))
        assertNull(FollowUpRules.wakeCheck(strict, now))
    }
    @Test fun wakeCheckRunsOnceAfterFinalDismissal() {
        val next = FollowUpRules.wakeCheck(session, now)!!
        assertTrue(next.isWakeCheck)
        assertEquals(now + 600_000, next.dueAt)
        assertEquals(DismissMode.QR, next.alarm.dismissMode)
        assertNull(FollowUpRules.wakeCheck(next, next.dueAt))
    }
    @Test fun snoozingWakeCheckDoesNotCreateAnotherWakeCheck() {
        val check = FollowUpRules.wakeCheck(session, now)!!
        val postponed = FollowUpRules.snooze(check, check.dueAt)!!
        assertTrue(postponed.isWakeCheck)
        assertNull(FollowUpRules.wakeCheck(postponed, postponed.dueAt))
    }
    @Test fun testRingsNeverScheduleUnexpectedFollowUps() {
        val test = session.copy(test = true)
        assertNull(FollowUpRules.snooze(test, now))
        assertNull(FollowUpRules.wakeCheck(test, now))
    }
    @Test fun snapshotsRetainUserSettingsAndDoNotMoveTheRegularAlarm() {
        val original = session.copy(alarm = alarm.copy(scheduledAt = now + 86_400_000, label = "Работа", sound = "content://music/42"))
        val next = FollowUpRules.snooze(original, now)!!
        assertEquals(original.alarm, next.alarm)
        assertEquals(now, original.startedAt)
        assertEquals(0, original.snoozeCount)
    }
    @Test fun followUpsKeepTheirPoolButDrawAnotherSongWhenTheyStart() {
        val original = session.copy(alarm = alarm.copy(randomSound = true,
            randomSounds = listOf(SoundChoice("file:///a", "A"), SoundChoice("file:///b", "B"))),
            selectedSound = SoundChoice("file:///a", "A"))
        for (next in listOf(FollowUpRules.snooze(original, now)!!, FollowUpRules.wakeCheck(original, now)!!)) {
            assertEquals(original.alarm, next.alarm)
            assertNull(next.selectedSound)
        }
    }
}
