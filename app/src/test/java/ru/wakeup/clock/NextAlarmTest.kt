package ru.wakeup.clock

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class NextAlarmTest {
    private val utc = ZoneId.of("UTC")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun next(alarm: Alarm, now: String, zone: ZoneId = utc) = Instant.ofEpochMilli(NextAlarm.at(alarm, millis(now), zone)).toString()

    @Test fun oneShotToday() { assertEquals("2026-11-16T07:00:00Z", next(Alarm(1), "2026-11-16T06:59:00Z")) }
    @Test fun exactMinuteMovesToTomorrow() { assertEquals("2026-11-17T07:00:00Z", next(Alarm(1), "2026-11-16T07:00:00Z")) }
    @Test fun oneShotAfterMidnight() { assertEquals("2026-11-17T00:00:00Z", next(Alarm(1, hour = 0), "2026-11-16T23:59:00Z")) }
    @Test fun mondayRepeatsNextWeekAfterFiring() { assertEquals("2026-11-23T07:00:00Z", next(Alarm(1, days = 1), "2026-11-16T07:00:00Z")) }
    @Test fun weekdaysSkipWeekend() { assertEquals("2026-11-16T07:00:00Z", next(Alarm(1, days = 31), "2026-11-13T08:00:00Z")) }
    @Test fun respectsMoscowTime() {
        assertEquals("2026-11-16T04:00:00Z", next(Alarm(1), "2026-11-15T22:00:00Z", ZoneId.of("Europe/Moscow")))
    }
    @Test fun daylightSavingGapMovesIntoValidTime() {
        assertEquals("2026-03-29T01:30:00Z", next(Alarm(1, hour = 2, minute = 30), "2026-03-28T23:00:00Z", ZoneId.of("Europe/Berlin")))
    }
    @Test fun daylightSavingOverlapRingsOnce() {
        val alarm = Alarm(1, hour = 2, minute = 30, days = 127)
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals("2026-10-25T00:30:00Z", next(alarm, "2026-10-24T22:00:00Z", berlin))
        assertEquals("2026-10-26T01:30:00Z", next(alarm, "2026-10-25T00:30:01Z", berlin))
    }
}
