package ru.wakeup.clock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlarmJsonTest {
    @Test fun newAlarmsStartAtFullVolume() { assertEquals(100, Alarm(1).volume) }
    @Test fun savedCustomVolumeIsPreserved() {
        val alarm = Alarm(15, volume = 35)
        assertEquals(35, AlarmJson.decode(AlarmJson.encode(alarm)).volume)
    }
    private fun legacy(persistence: String) = JSONObject("""{
        "id":12,"hour":7,"minute":15,"days":31,"enabled":true,"sound":"content://music/1",
        "volume":75,"persistence":"$persistence","dismissMode":"QR","scheduledAt":1794740400000
    }""")

    @Test fun updatePreservesStrongAlarmWithoutEnablingSnooze() {
        val alarm = AlarmJson.decode(legacy("STRONG"))
        assertEquals(12, alarm.id)
        assertEquals(31, alarm.days)
        assertEquals(75, alarm.volume)
        assertEquals(1794740400000, alarm.scheduledAt)
        assertTrue(alarm.vibrate)
        assertEquals(30, alarm.fadeInSeconds)
        assertEquals(0, alarm.snoozeMinutes)
        assertEquals(0, alarm.wakeCheckMinutes)
    }
    @Test fun updatePreservesNormalAlarmSoundBehaviour() {
        val alarm = AlarmJson.decode(legacy("NORMAL"))
        assertFalse(alarm.vibrate)
        assertEquals(0, alarm.fadeInSeconds)
        assertEquals("content://music/1", alarm.sound)
    }
    @Test fun extendedSettingsSurviveStorageAndRotation() {
        val expected = Alarm(42, label = "Встать на работу", soundTitle = "Утро.mp3", snoozeMinutes = 10,
            maxSnoozes = 2, wakeCheckMinutes = 15, vibrate = false, fadeInSeconds = 60)
        assertEquals(expected, AlarmJson.decode(JSONObject(AlarmJson.encode(expected).toString())))
    }
}
