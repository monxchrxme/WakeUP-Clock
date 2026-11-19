package ru.wakeup.clock

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EarlyWakeCheckTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private val station = Station("0123456789abcdef", "12".repeat(32))
    private var activity: Activity? = null

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            clearSessions()
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            StationStore(context).save(station)
            AlarmNotifications.channel(context)
        }
    }
    private fun clearSessions() {
        store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
        context.stopService(Intent(context, RingService::class.java))
    }
    @After fun finish() {
        instrumentation.runOnMainSync {
            clearSessions()
            activity?.finish()
            context.getSharedPreferences("station", 0).edit().clear().commit()
        }
    }
    private fun base(mode: DismissMode = DismissMode.QR): Alarm {
        assertTrue(AlarmScheduler.schedule(context, Alarm(801, days = 127, label = "Работа", dismissMode = mode, wakeCheckMinutes = 20)))
        return store.alarms().single()
    }
    private fun check(alarm: Alarm, dueAt: Long = System.currentTimeMillis() + 9 * 60_000L): RingSession {
        val session = RingSession(UUID.randomUUID().toString(), alarm, dueAt, dueAt = dueAt, isWakeCheck = true)
        val seed = store.enqueue(alarm)
        store.replaceSession(seed.token, session)
        assertTrue(AlarmScheduler.scheduleSession(context, session))
        return session
    }
    private fun qr(at: Long = System.currentTimeMillis(), signer: Station = station): String {
        val seconds = at / 30_000 * 30
        return "wakeup://dismiss/v1/${signer.id}/$seconds/${QrProtocol.signature(signer, seconds)}"
    }
    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for the wake check", condition())
    }
    @Suppress("DEPRECATION")
    private fun isRinging() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == RingService::class.java.name && it.foreground }

    @Test fun validQrRemovesOnlyItsCheckAndLateBroadcastCannotRestoreIt() {
        lateinit var alarm: Alarm
        lateinit var target: RingSession
        lateinit var other: RingSession
        instrumentation.runOnMainSync {
            alarm = base()
            target = check(alarm, System.currentTimeMillis() + 2_000)
            other = check(alarm)
            assertTrue(RingActions.cancelWakeCheck(context, target.token, qr()))
            assertEquals(listOf(other), store.sessions())
            assertEquals(alarm, store.alarms().single())
        }
        SystemClock.sleep((target.dueAt - System.currentTimeMillis() + 100).coerceAtLeast(0))
        instrumentation.runOnMainSync {
            AlarmReceiver().onReceive(context, Intent(AlarmScheduler.SESSION_DUE)
                .putExtra("token", target.token).putExtra("dueAt", target.dueAt))
            AlarmScheduler.restore(context, recompute = false)
        }
        assertEquals(listOf(other), AlarmStore(context).sessions())
        assertEquals(alarm, store.alarms().single())
        assertFalse(isRinging())
    }

    @Test fun timeWindowAndOriginalQrModeCannotBeBypassed() {
        instrumentation.runOnMainSync {
            val alarm = base()
            val waiting = check(alarm, System.currentTimeMillis() + 15 * 60_000L)
            assertFalse(RingActions.cancelWakeCheck(context, waiting.token, qr()))
            store.removeSession(waiting.token); AlarmScheduler.cancelSession(context, waiting)
            val eligible = check(alarm)
            store.save(alarm.copy(dismissMode = DismissMode.BUTTON, wakeCheckMinutes = 0))
            for (code in listOf(null, qr(System.currentTimeMillis() - 120_000), qr(signer = station.copy(secret = "34".repeat(32))),
                "wakeup://pair/v1/${station.id}/${station.secret}")) {
                assertFalse(RingActions.cancelWakeCheck(context, eligible.token, code))
            }
            assertEquals(eligible, store.sessions().single())
            val active = eligible.copy(dueAt = System.currentTimeMillis() - 1)
            store.replaceSession(eligible.token, active)
            assertFalse(RingActions.cancelWakeCheck(context, active.token, qr()))
            assertEquals(active, store.activeSession())
        }
    }

    @Test fun listButtonScansFreshQrSilentlyAndPreservesTheSchedule() {
        lateinit var alarm: Alarm
        instrumentation.runOnMainSync { alarm = base(); check(alarm) }
        val monitor = scanner { qr() }
        try {
            launch()
            onView(withText("Отменить проверку")).perform(scrollTo())
            // Let the preceding test's heads-up notification and toast leave the screen.
            SystemClock.sleep(6_000)
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(context.filesDir, "early-wake-check.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            onView(withText("Отменить проверку")).perform(click())
            waitUntil { store.sessions().isEmpty() }
            assertTrue(monitor.hits > 0)
            assertEquals(alarm, store.alarms().single())
            assertFalse(isRinging())
            onView(withText("Отменить проверку")).check(doesNotExist())
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun buttonAppearsAtHalfTimeWithoutReopeningTheApp() {
        lateinit var alarm: Alarm
        lateinit var waiting: RingSession
        instrumentation.runOnMainSync { alarm = base(DismissMode.BUTTON); waiting = check(alarm, System.currentTimeMillis() + 15 * 60_000L) }
        launch()
        onView(withText("Отменить проверку")).check(doesNotExist())
        instrumentation.runOnMainSync {
            AlarmScheduler.cancelSession(context, waiting)
            val nearHalf = waiting.copy(dueAt = System.currentTimeMillis() + 10 * 60_000L + 2_000)
            store.replaceSession(waiting.token, nearHalf)
            assertTrue(AlarmScheduler.scheduleSession(context, nearHalf))
            context.sendBroadcast(Intent(RingService.STATE_CHANGED).setPackage(context.packageName))
        }
        SystemClock.sleep(2_500)
        instrumentation.waitForIdleSync()
        onView(withText("Отменить проверку")).perform(scrollTo(), click())
        assertTrue(store.sessions().isEmpty())
        assertEquals(alarm, store.alarms().single())
        assertFalse(isRinging())
    }

    @Test fun pendingCheckRemainsAccessibleAfterItsBaseAlarmWasDeletedAndScreenRecreated() {
        instrumentation.runOnMainSync {
            val alarm = base(DismissMode.BUTTON)
            check(alarm)
            AlarmScheduler.cancel(context, alarm.id); store.delete(alarm.id)
        }
        launch()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity!!.recreate() }
        activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
        instrumentation.removeMonitor(monitor)
        assertNotNull(activity)
        instrumentation.waitForIdleSync()
        onView(withText("Работа")).check(matches(isDisplayed()))
        onView(withText("Отменить проверку")).perform(scrollTo(), click())
        assertTrue(store.sessions().isEmpty())
        assertTrue(store.alarms().isEmpty())
    }

    @Test fun scanResultCannotSilenceACheckThatAlreadyStarted() {
        lateinit var session: RingSession
        instrumentation.runOnMainSync { session = check(base()) }
        val monitor = scanner {
            // The check becomes due while the camera is open; the callback must read the current session again.
            AlarmScheduler.cancelSession(context, session)
            val due = System.currentTimeMillis() - 1
            store.replaceSession(session.token, session.copy(dueAt = due, startedAt = due))
            qr()
        }
        try {
            launch()
            onView(withText("Отменить проверку")).perform(scrollTo(), click())
            waitUntil { isRinging() }
            assertEquals(session.token, store.activeSession()?.token)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun scanner(contents: () -> String): Instrumentation.ActivityMonitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className != "com.journeyapps.barcodescanner.CaptureActivity") return null
            return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()
                .putExtra("SCAN_RESULT", contents()).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
        }
    }.also { instrumentation.addMonitor(it) }
}
