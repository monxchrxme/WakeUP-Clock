package ru.wakeup.clock

import android.app.Activity
import android.widget.LinearLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WakeCheckUi {
    fun row(activity: Activity, session: RingSession, cancel: (RingSession) -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(activity, "Проверка подъёма · ${time(session.dueAt)}", 14f, true))
            val availableAt = FollowUpRules.earlyCancelAt(session)
            if (FollowUpRules.canCancelEarly(session, System.currentTimeMillis())) {
                addView(Ui.button(activity, "Отменить проверку") { cancel(session) }.apply {
                    contentDescription = "Отменить проверку ${session.alarm.label.ifBlank { Ui.time(session.alarm) }} в ${time(session.dueAt)}"
                })
            } else if (availableAt != null) addView(Ui.text(activity, "Отмена доступна с ${time(availableAt, true)}", 13f, true))
        }

    private fun time(at: Long, seconds: Boolean = false) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm"))
}
