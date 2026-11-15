package ru.wakeup.clock

import java.time.Instant
import java.time.ZoneId

enum class DismissMode { BUTTON, QR }
enum class Persistence { NORMAL, STRONG }

data class Alarm(
    val id: Int,
    val hour: Int = 7,
    val minute: Int = 0,
    /** ISO weekdays: Monday = bit 0; zero means one-shot. */
    val days: Int = 0,
    val enabled: Boolean = true,
    val sound: String = "",
    val volume: Int = 80,
    val persistence: Persistence = Persistence.STRONG,
    val dismissMode: DismissMode = DismissMode.QR,
    val scheduledAt: Long = 0,
    val label: String = "",
    val soundTitle: String = "",
    val vibrate: Boolean = true,
    val fadeInSeconds: Int = 30,
    /** Zero disables snooze / wake-up check. Existing alarms retain the strict behaviour. */
    val snoozeMinutes: Int = 0,
    val maxSnoozes: Int = 1,
    val wakeCheckMinutes: Int = 0,
)

data class RingSession(
    val token: String,
    val alarm: Alarm,
    val startedAt: Long,
    val test: Boolean = false,
    val dueAt: Long = 0,
    val snoozeCount: Int = 0,
    val isWakeCheck: Boolean = false,
)

object NextAlarm {
    fun at(alarm: Alarm, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(alarm.hour in 0..23 && alarm.minute in 0..59 && alarm.days in 0..127)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val matches = alarm.days == 0 || (alarm.days and (1 shl (date.dayOfWeek.value - 1))) != 0
            val candidate = date.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant().toEpochMilli()
            if (matches && candidate > nowMillis) return candidate
        }
        error("No next alarm")
    }
}
