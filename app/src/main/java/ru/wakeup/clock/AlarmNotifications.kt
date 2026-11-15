package ru.wakeup.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object AlarmNotifications {
    const val CHANNEL = "ringing-v1"
    const val ID = 7001

    fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Звонок будильника", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Открывает экран активного будильника"
            setSound(null, null) // The service owns the loop, not the notification channel.
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun ringing(context: Context, session: RingSession): Notification {
        channel(context)
        val show = PendingIntent.getActivity(context, 0,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (session.test) "Проверка будильника" else if (session.alarm.dismissMode == DismissMode.QR)
            "Встань и отсканируй QR на ноутбуке" else "Пора начинать день"
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(ru.wakeup.clock.R.drawable.ic_alarm)
            .setContentTitle("Пора вставать")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
