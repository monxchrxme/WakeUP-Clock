package ru.wakeup.clock

import android.app.Activity
import android.app.ActivityManager
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
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class IrritationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private var activity: Activity? = null

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
        instrumentation.runOnMainSync { clearSessions(); activity?.finish() }
    }
    private fun song(name: String) = CustomSoundImporter.import(context, Uri.parse("content://ru.wakeup.clock.test.audio/$name"))
    private fun session(song: SoundChoice) = RingSession("test-layer", Alarm(901, sound = song.uri, soundTitle = song.title,
        dismissMode = DismissMode.BUTTON, irritationMinutes = 1), System.currentTimeMillis(), selectedSound = song)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        fun check(): Boolean {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            return ready
        }
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for sound layers", check())
    }

    @Test fun editorSavesMinutePrecisionAndRestoresItAfterRotation() {
        activity = instrumentation.startActivitySync(Intent(context, AlarmEditorActivity::class.java)
            .putExtra("id", 901).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onView(withText("Раздражение")).check(matches(isNotChecked())).perform(scrollTo(), click())
        onView(withContentDescription("После начала звонка: Через 1 мин")).perform(scrollTo(), click())
        onView(withContentDescription("Задержка раздражения в минутах")).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "Choose a 37 minute delay"
            override fun perform(ui: UiController, view: View) {
                val picker = view as NumberPicker
                assertEquals(1, picker.minValue); assertEquals(60, picker.maxValue)
                picker.value = 37
            }
        })
        onView(withText("Готово")).inRoot(isDialog()).perform(click())
        val monitor = instrumentation.addMonitor(AlarmEditorActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity!!.recreate() }
        activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
        instrumentation.removeMonitor(monitor)
        assertNotNull(activity)
        instrumentation.waitForIdleSync()
        onView(withContentDescription("После начала звонка: Через 37 мин")).perform(scrollTo()).check(matches(isDisplayed()))
        SystemClock.sleep(6_000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "irritation-settings.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        onView(withText("Готово")).perform(click())
        assertEquals(37, store.alarms().single().irritationMinutes)
        assertEquals(0, SoundDefaults(context).newAlarm(902).irritationMinutes)
    }

    @Test fun singleSongAddsAnOutOfPhaseCopyWithoutRestartingTheMainPlayer() {
        val session = session(song("long-tone.wav"))
        val layers = AlarmSoundLayers(context, Random(42))
        try {
            instrumentation.runOnMainSync {
                layers.ensure(session, 59_999)
                assertTrue(layers.main!!.player.isPlaying)
                assertNull(layers.irritation)
                val main = layers.main
                layers.ensure(session, 60_000)
                assertSame(main, layers.main)
                assertEquals(main!!.uri, layers.irritation!!.uri)
                assertNotSame(main.player, layers.irritation!!.player)
                assertTrue(layers.irritation!!.player.isLooping)
            }
            waitUntil {
                val main = layers.main!!.player
                val extra = layers.irritation!!.player
                val duration = main.duration
                val distance = (extra.currentPosition - main.currentPosition + duration) % duration
                main.isPlaying && extra.isPlaying && distance in 2_500..duration - 2_500
            }
            instrumentation.runOnMainSync {
                val extra = layers.irritation
                repeat(20) { layers.ensure(session, 120_000) }
                assertSame(extra, layers.irritation)
            }
        } finally { instrumentation.runOnMainSync { layers.release() } }
        assertNull(layers.main); assertNull(layers.irritation)
    }

    @Test fun randomModeUsesAnotherSongAndBothPlayersPauseAndResumeTogether() {
        val first = song("custom-tone.wav")
        val second = song("other-tone.wav")
        val session = session(first).let { it.copy(alarm = it.alarm.copy(randomSound = true, randomSounds = listOf(first, second))) }
        val layers = AlarmSoundLayers(context)
        try {
            instrumentation.runOnMainSync {
                layers.ensure(session, 60_000)
                assertEquals(first.uri, layers.main!!.uri.toString())
                assertEquals(second.uri, layers.irritation!!.uri.toString())
                assertTrue(layers.main!!.player.isPlaying); assertTrue(layers.irritation!!.player.isPlaying)
                layers.pause()
            }
            waitUntil { !layers.main!!.player.isPlaying && !layers.irritation!!.player.isPlaying }
            instrumentation.runOnMainSync {
                val extra = layers.irritation
                layers.ensure(session, 180_000)
                assertSame(extra, layers.irritation)
                assertFalse(layers.main!!.player.isPlaying); assertFalse(layers.irritation!!.player.isPlaying)
                layers.resume()
            }
            waitUntil { layers.main!!.player.isPlaying && layers.irritation!!.player.isPlaying }
        } finally { instrumentation.runOnMainSync { layers.release() } }
    }

    @Test fun serviceTimerSurvivesRecoveryAndSnoozeStopsBothLayersWithAFreshClock() {
        val sound = song("long-tone.wav")
        lateinit var original: RingSession
        instrumentation.runOnMainSync {
            val seed = store.enqueue(session(sound).alarm.copy(snoozeMinutes = 10, vibrate = false))
            original = seed.copy(ringingSince = System.currentTimeMillis() - 55_000)
            store.replaceSession(seed.token, original)
            RingService.start(context)
        }
        waitUntil { isRinging() }
        assertFalse(store.irritationActive())
        waitUntil { store.irritationActive() }
        instrumentation.runOnMainSync { context.stopService(Intent(context, RingService::class.java)) }
        waitUntil { !isRinging() }
        instrumentation.runOnMainSync { RingService.start(context) }
        waitUntil { isRinging() && store.irritationActive() }
        assertEquals(original.ringingSince, store.activeSession()!!.ringingSince)
        instrumentation.runOnMainSync { assertTrue(RingActions.snooze(context, store.activeSession()!!)) }
        waitUntil { !isRinging() && !store.irritationActive() }
        val postponed = store.sessions().single()
        assertEquals(0L, postponed.ringingSince)
        instrumentation.runOnMainSync {
            AlarmScheduler.cancelSession(context, postponed)
            store.replaceSession(postponed.token, postponed.copy(dueAt = 0))
            RingService.start(context)
        }
        waitUntil { isRinging() && store.activeSession()!!.ringingSince > original.ringingSince }
        assertFalse(store.irritationActive())
        instrumentation.runOnMainSync { assertTrue(RingActions.dismiss(context, store.activeSession()!!)) }
        waitUntil { !isRinging() }
        assertTrue(store.sessions().isEmpty())
    }

    @Test fun queuedAndDeferredSessionsStartTheirClockOnlyWhenTheyActuallyRing() {
        instrumentation.runOnMainSync {
            val first = store.enqueue(Alarm(910))
            val second = store.enqueue(Alarm(911))
            val now = System.currentTimeMillis()
            assertEquals(0L, store.beginRinging(second, now).ringingSince)
            assertEquals(now, store.beginRinging(first, now).ringingSince)
            store.removeSession(first.token)
            assertEquals(now + 10_000, store.beginRinging(second, now + 10_000).ringingSince)
            assertEquals(now + 10_000, AlarmStore(context).sessions().single().ringingSince)
            val check = FollowUpRules.wakeCheck(second.copy(alarm = second.alarm.copy(wakeCheckMinutes = 20)), now)!!
            store.replaceSession(second.token, check)
            assertEquals(0L, store.beginRinging(check, now).ringingSince)
        }
    }

    @Test fun wakeCheckWaitsFiveExtraMinutesAndDisabledModeNeverAddsALayer() {
        val session = session(song("long-tone.wav")).copy(isWakeCheck = true)
        val layers = AlarmSoundLayers(context)
        try {
            instrumentation.runOnMainSync {
                layers.ensure(session, 359_999)
                assertNull(layers.irritation)
                layers.ensure(session, 360_000)
                assertTrue(layers.main!!.player.isPlaying)
                assertTrue(layers.irritation!!.player.isPlaying)
                layers.release()
                layers.ensure(session.copy(alarm = session.alarm.copy(irritationMinutes = 0)), 3600_000)
                assertNotNull(layers.main)
                assertNull(layers.irritation)
            }
        } finally { instrumentation.runOnMainSync { layers.release() } }
    }
    @Suppress("DEPRECATION")
    private fun isRinging() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == RingService::class.java.name && it.foreground }
}
