package ru.wakeup.clock

import android.content.Context
import android.content.Intent

object RingActions {
    /** Persist the follow-up before stopping sound, so process death cannot lose it. */
    fun snooze(context: Context, session: RingSession): Boolean {
        val replacement = FollowUpRules.snooze(session, System.currentTimeMillis()) ?: return false
        return transition(context, session, replacement)
    }

    fun dismiss(context: Context, session: RingSession): Boolean = transition(context, session,
        FollowUpRules.wakeCheck(session, System.currentTimeMillis()))

    private fun transition(context: Context, session: RingSession, replacement: RingSession?): Boolean {
        val store = AlarmStore(context)
        if (store.activeSession()?.token != session.token) return false
        if (replacement != null && !AlarmScheduler.scheduleSession(context, replacement)) return false
        store.replaceSession(session.token, replacement)
        AlarmScheduler.cancelSession(context, session)
        if (store.activeSession() == null) context.stopService(Intent(context, RingService::class.java))
        else RingService.start(context)
        context.sendBroadcast(Intent(RingService.STATE_CHANGED).setPackage(context.packageName))
        return true
    }
}
