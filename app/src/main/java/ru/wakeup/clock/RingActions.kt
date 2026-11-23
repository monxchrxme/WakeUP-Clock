package ru.wakeup.clock

import android.content.Context
import android.content.Intent

object RingActions {
    /** Recheck both the time window and the original dismissal mode after the scanner returns. */
    fun cancelWakeCheck(context: Context, token: String, qr: String? = null): Boolean {
        val store = AlarmStore(context)
        val session = store.sessions().firstOrNull { it.token == token } ?: return false
        val now = System.currentTimeMillis()
        if (!FollowUpRules.canCancelEarly(session, now)) return false
        if (session.alarm.dismissMode == DismissMode.QR) {
            val station = StationStore(context).get() ?: return false
            if (qr == null || !QrProtocol.verify(qr, station, now, FollowUpRules.earlyCancelAt(session)!!)) return false
        }
        // Remove first so an already queued alarm broadcast cannot resurrect this check.
        store.removeSession(token)
        AlarmScheduler.cancelSession(context, session)
        context.sendBroadcast(Intent(RingService.STATE_CHANGED).setPackage(context.packageName))
        return true
    }

    /** Persist the follow-up before stopping sound, so process death cannot lose it. */
    fun snooze(context: Context, session: RingSession): Boolean {
        val replacement = FollowUpRules.snooze(session, System.currentTimeMillis()) ?: return false
        return transition(context, session, replacement)
    }

    fun dismiss(context: Context, session: RingSession): Boolean = transition(context, session,
        FollowUpRules.wakeCheck(session, System.currentTimeMillis()), allowWaitingAutomatic = true)

    fun automaticRepeat(context: Context, session: RingSession, elapsedMillis: Long): Boolean {
        val saved = AlarmStore(context).activeSession()?.takeIf { it.token == session.token } ?: return false
        val replacement = AutoRepeatRules.next(saved, System.currentTimeMillis(), elapsedMillis) ?: return false
        return transition(context, saved, replacement)
    }

    private fun transition(context: Context, session: RingSession, replacement: RingSession?, allowWaitingAutomatic: Boolean = false): Boolean {
        val store = AlarmStore(context)
        val saved = store.sessions().firstOrNull { it.token == session.token } ?: return false
        if (store.activeSession()?.token != session.token &&
            !(allowWaitingAutomatic && saved.automaticRepeat && saved.dueAt > System.currentTimeMillis())) return false
        if (replacement != null && !AlarmScheduler.scheduleSession(context, replacement)) return false
        store.replaceSession(session.token, replacement)
        // A recurring automatic pause updates the same PendingIntent; cancelling it would cancel the new due time.
        if (replacement?.token != session.token) AlarmScheduler.cancelSession(context, session)
        if (store.activeSession() == null) context.stopService(Intent(context, RingService::class.java))
        else RingService.start(context)
        context.sendBroadcast(Intent(RingService.STATE_CHANGED).setPackage(context.packageName))
        return true
    }
}
