package ru.wakeup.clock

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.NumberPicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutoRepeatTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private var activity: Activity? = null
    private val station = Station("0123456789abcdef", "12".repeat(32))

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            clearSessions()
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            context.getSharedPreferences("station", 0).edit().clear().commit()
            AlarmNotifications.channel(context)
        }
        context.contentResolver.call(Uri.parse("content://ru.wakeup.clock.test.audio"), "enable", null, null)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    private fun clearSessions() {
        store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
        context.stopService(Intent(context, RingService::class.java))
    }
    @After fun finish() {
        instrumentation.runOnMainSync {
            clearSessions(); activity?.finish()
            context.getSharedPreferences("station", 0).edit().clear().commit()
        }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        fun ready(): Boolean { var result = false; instrumentation.runOnMainSync { result = condition() }; return result }
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for automatic repeat", ready())
    }
    private fun setPicker(description: String, value: Int, max: Int) {
        onView(withContentDescription(description)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "Choose a repeat interval"
            override fun perform(ui: UiController, view: View) {
                val picker = view as NumberPicker
                assertEquals(1, picker.minValue); assertEquals(max, picker.maxValue); picker.value = value
            }
        })
        onView(withText("Готово")).inRoot(isDialog()).perform(click())
    }
    @Test fun editorSavesOneMinuteRingDurationAndFiveMinutePauseSteps() {
        activity = instrumentation.startActivitySync(Intent(context, AlarmEditorActivity::class.java)
            .putExtra("id", 950).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onView(withText("Автоповтор")).check(matches(isNotChecked())).perform(scrollTo(), click())
        onView(withContentDescription("Длительность звонка: 10 мин")).perform(scrollTo(), click())
        setPicker("Длительность звонка в минутах", 17, 60)
        onView(withContentDescription("Пауза перед повтором: 5 мин")).perform(scrollTo(), click())
        setPicker("Пауза автоповтора в минутах", 5, 12)
        onView(withContentDescription("Пауза перед повтором: 25 мин")).perform(scrollTo()).check(matches(isDisplayed()))
        SystemClock.sleep(6_000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "auto-repeat-settings.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        onView(withText("Готово")).perform(click())
        val saved = store.alarms().single()
        assertEquals(17, saved.ringLimitMinutes); assertEquals(25, saved.autoRepeatMinutes)
        assertEquals(0, SoundDefaults(context).newAlarm(951).autoRepeatMinutes)
    }

    @Test fun automaticPauseStopsBothSoundsAndOnlyConfirmationCreatesTheWakeCheck() {
        val songs = listOf("custom-tone.wav", "other-tone.wav").map {
            CustomSoundImporter.import(context, Uri.parse("content://ru.wakeup.clock.test.audio/$it"))
        }
        lateinit var original: RingSession
        instrumentation.runOnMainSync {
            val alarm = Alarm(952, dismissMode = DismissMode.BUTTON, randomSound = true, randomSounds = songs,
                autoRepeatMinutes = 5, ringLimitMinutes = 3, irritationMinutes = 1, wakeCheckMinutes = 20, vibrate = false)
            val seed = store.enqueue(alarm)
            original = seed.copy(ringingSince = System.currentTimeMillis() - 175_000)
            store.replaceSession(seed.token, original)
            RingService.start(context)
        }
        waitUntil { isRinging() && store.irritationActive() }
        val firstSong = store.activeSession()!!.selectedSound
        waitUntil { store.sessions().single().automaticRepeat && !isRinging() }
        val waiting = store.sessions().single()
        assertFalse(waiting.isWakeCheck)
        assertEquals(original.token, waiting.token)
        assertEquals(original.alarm, waiting.alarm)
        assertEquals(0L, waiting.ringingSince); assertNull(waiting.selectedSound)
        assertEquals(waiting.dueAt, context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
        assertTrue(waiting.dueAt - System.currentTimeMillis() in 290_000L..300_000L)
        instrumentation.runOnMainSync {
            AlarmReceiver().onReceive(context, Intent(AlarmScheduler.SESSION_DUE)
                .putExtra("token", original.token).putExtra("dueAt", original.dueAt))
        }
        assertNull(store.activeSession())
        instrumentation.runOnMainSync {
            AlarmScheduler.cancelSession(context, waiting)
            store.replaceSession(waiting.token, waiting.copy(dueAt = 0))
            RingService.start(context)
        }
        waitUntil { isRinging() && store.activeSession()!!.selectedSound != null }
        assertNotEquals(firstSong, store.activeSession()!!.selectedSound)
        assertFalse(store.irritationActive())
        instrumentation.runOnMainSync { assertTrue(RingActions.dismiss(context, store.activeSession()!!)) }
        waitUntil { !isRinging() }
        assertTrue(store.sessions().single().isWakeCheck)
        assertFalse(store.sessions().single().automaticRepeat)
    }

    @Test fun expiredServiceRecoverySchedulesQuietRepeatAndButtonCanConfirmDuringPause() {
        instrumentation.runOnMainSync {
            val seed = store.enqueue(Alarm(953, dismissMode = DismissMode.BUTTON, autoRepeatMinutes = 5, ringLimitMinutes = 10))
            store.replaceSession(seed.token, seed.copy(ringingSince = System.currentTimeMillis() - 601_000))
            RingService.start(context)
        }
        waitUntil { store.sessions().single().automaticRepeat && !isRinging() }
        assertNull(store.originalVolume())
        assertFalse(store.irritationActive())
        onView(withText("Я уже встал · подтвердить")).perform(scrollTo(), click())
        onView(withText("Я встал · отключить")).perform(scrollTo(), click())
        assertTrue(store.sessions().isEmpty())
        assertFalse(isRinging())
    }

    @Test fun qrResultOpenedBeforeAutomaticPauseStillConfirmsAndThenCreatesTheWakeCheck() {
        lateinit var original: RingSession
        instrumentation.runOnMainSync {
            StationStore(context).save(station)
            original = store.enqueue(Alarm(954, dismissMode = DismissMode.QR, autoRepeatMinutes = 5, ringLimitMinutes = 10, wakeCheckMinutes = 20))
        }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != "com.journeyapps.barcodescanner.CaptureActivity") return null
                assertTrue(RingActions.automaticRepeat(context, store.activeSession()!!, 600_000))
                assertFalse(store.sessions().single().isWakeCheck)
                val seconds = System.currentTimeMillis() / 30_000 * 30
                val qr = "wakeup://dismiss/v1/${station.id}/$seconds/${QrProtocol.signature(station, seconds)}"
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().putExtra("SCAN_RESULT", qr).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            activity = instrumentation.startActivitySync(Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onView(withText("Сканировать QR")).perform(scrollTo(), click())
            waitUntil { store.sessions().singleOrNull()?.isWakeCheck == true }
            assertFalse(store.sessions().single().automaticRepeat)
            assertNotEquals(original.token, store.sessions().single().token)
            assertFalse(isRinging())
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Suppress("DEPRECATION")
    private fun isRinging() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == RingService::class.java.name && it.foreground }
}
