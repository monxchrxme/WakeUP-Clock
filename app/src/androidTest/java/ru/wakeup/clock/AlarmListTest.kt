package ru.wakeup.clock

import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
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

@RunWith(AndroidJUnit4::class)
class AlarmListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private val fixtures get() = store.alarms().sortedBy { it.id }
    private var activity: Activity? = null

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, RingService::class.java))
            store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            AlarmNotifications.channel(context)
            for (i in 1..3) assertTrue(AlarmScheduler.schedule(context, Alarm(900 + i, hour = 6 + i,
                label = "Будильник $i", dismissMode = DismissMode.BUTTON)))
        }
    }
    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    @After fun closeActivity() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
    }
    private fun select(time: String) { onView(withContentDescription("Выбрать будильник $time")).perform(scrollTo(), click()) }
    private fun row(time: String) = onView(withContentDescription("Будильник $time"))
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun disableSelectedLeavesTheOtherAlarmEnabledAndRejectsStaleBroadcasts() {
        val before = fixtures
        launch()
        onView(withText("Выбрать")).perform(scrollTo(), click())
        select("07:00"); select("08:00")
        screenshot("alarm-selection.png")
        onView(withContentDescription("Отключить выбранные")).perform(click())
        assertFalse(fixtures[0].enabled); assertEquals(0L, fixtures[0].scheduledAt)
        assertFalse(fixtures[1].enabled)
        assertEquals(before[2], fixtures[2])
        instrumentation.runOnMainSync { AlarmScheduler.fire(context, before[0].id, before[0].scheduledAt, startService = false) }
        assertTrue(store.sessions().isEmpty())
    }

    @Test fun selectAllDeleteAndUndoRestoreBothSettingsAndScheduledAlarms() {
        val before = fixtures
        launch()
        onView(withText("Выбрать")).perform(scrollTo(), click())
        onView(withContentDescription("Выбрать все")).perform(click())
        onView(withContentDescription("Удалить выбранные")).perform(click())
        assertTrue(store.alarms().isEmpty())
        assertNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock)
        onView(withText("Отменить")).perform(click())
        assertEquals(before, fixtures)
        assertNotNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock)
    }

    @Test fun leftSwipeDeletesOnlyItsAlarmAndUndoReturnsIt() {
        val before = fixtures
        launch()
        row("07:00").perform(scrollTo(), swipeRight())
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        assertEquals(before, fixtures)
        row("07:00").perform(swipeLeft())
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        assertEquals(before.drop(1), fixtures)
        screenshot("alarm-delete-undo.png")
        onView(withText("Отменить")).perform(click())
        assertEquals(before, fixtures)
    }

    @Test fun longPressSelectionSurvivesRecreationAndBackExitsWithoutEditing() {
        launch()
        row("07:00").perform(scrollTo(), longClick())
        onView(withContentDescription("Выбрать будильник 07:00")).check(matches(isChecked()))
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity!!.recreate() }
        activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
        instrumentation.removeMonitor(monitor)
        assertNotNull(activity)
        instrumentation.waitForIdleSync()
        onView(withContentDescription("Выбрать будильник 07:00")).check(matches(isChecked()))
        pressBack()
        onView(withText("Выбрать")).check(matches(isDisplayed()))
        assertEquals(3, fixtures.size)
    }

    @Test fun selectionSwipesAndSwitchDragsDoNotDeleteAlarms() {
        launch()
        onView(withContentDescription("Включён будильник 07:00")).perform(scrollTo(), swipeLeft())
        assertEquals(3, fixtures.size)
        onView(withText("Выбрать")).perform(scrollTo(), click())
        row("07:00").perform(scrollTo(), swipeLeft())
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        assertEquals(3, fixtures.size)
        select("07:00")
        onView(withContentDescription("Удалить выбранные")).perform(click())
        assertEquals(setOf(902, 903), fixtures.map { it.id }.toSet())
    }

    @Test fun listActionsDoNotBypassAnAlreadyStartedQrSession() {
        val alarm = fixtures.first().copy(dismissMode = DismissMode.QR)
        val session = store.enqueue(alarm)
        instrumentation.runOnMainSync {
            AlarmBatchActions.disable(context, setOf(alarm.id))
            AlarmBatchActions.delete(context, setOf(alarm.id))
        }
        assertEquals(session, store.activeSession())
        assertEquals(setOf(902, 903), fixtures.map { it.id }.toSet())
        store.removeSession(session.token)
    }
}
