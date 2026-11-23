package ru.wakeup.clock

import org.junit.Assert.*
import org.junit.Test

class AutoRepeatRulesTest {
    private val now = 1_800_000_000_000L
    private val alarm = Alarm(1, autoRepeatMinutes = 25, ringLimitMinutes = 10, irritationMinutes = 2,
        wakeCheckMinutes = 20, snoozeMinutes = 10, randomSound = true,
        randomSounds = listOf(SoundChoice("file:///a", "A"), SoundChoice("file:///b", "B")))
    private val session = RingSession("same-alarm", alarm, now - 600_000, selectedSound = alarm.randomSounds.first(), ringingSince = now - 600_000)

    @Test fun disabledAndTestAlarmsNeverScheduleAutomaticRepeats() {
        assertNull(AutoRepeatRules.next(session.copy(test = true), now, 900_000))
        assertNull(AutoRepeatRules.next(session.copy(alarm = alarm.copy(autoRepeatMinutes = 0)), now, 900_000))
    }
    @Test fun automaticPauseUsesTheConfiguredRingLimit() {
        assertFalse(AutoRepeatRules.shouldPause(session, 599_999))
        assertTrue(AutoRepeatRules.shouldPause(session, 600_000))
    }
    @Test fun repeatKeepsItsIdentityAndSettingsButResetsPlaybackAndDoesNotCreateAWakeCheck() {
        val next = AutoRepeatRules.next(session, now, 600_000)!!
        assertEquals(session.token, next.token)
        assertEquals(session.startedAt, next.startedAt)
        assertEquals(alarm, next.alarm)
        assertTrue(next.automaticRepeat)
        assertFalse(next.isWakeCheck)
        assertEquals(now + 25 * 60_000, next.dueAt)
        assertEquals(0L, next.ringingSince)
        assertNull(next.selectedSound)
    }
    @Test fun repeatedWakeCheckStillGetsFiveExtraMinutesOfIrritationDelay() {
        val next = AutoRepeatRules.next(session.copy(isWakeCheck = true), now, 600_000)!!
        assertTrue(next.isWakeCheck)
        assertEquals(7, IrritationRules.delayMinutes(next))
        assertNull(FollowUpRules.wakeCheck(next, now))
    }
    @Test fun explicitConfirmationCreatesTheCheckAndManualSnoozeRetainsItsOwnLimit() {
        val next = AutoRepeatRules.next(session, now, 600_000)!!
        val snoozed = FollowUpRules.snooze(next, now)!!
        assertFalse(snoozed.automaticRepeat)
        assertEquals(1, snoozed.snoozeCount)
        val confirmed = FollowUpRules.wakeCheck(next, now)!!
        assertTrue(confirmed.isWakeCheck)
        assertFalse(confirmed.automaticRepeat)
        assertEquals(now + 20 * 60_000, confirmed.dueAt)
    }
    @Test fun automaticPauseOfAnAlreadyRingingCheckCanBeConfirmedThroughoutThePause() {
        val next = AutoRepeatRules.next(session.copy(isWakeCheck = true), now, 600_000)!!
        assertTrue(FollowUpRules.canCancelEarly(next, now))
        assertFalse(FollowUpRules.canCancelEarly(next, next.dueAt))
    }
}
