package ru.wakeup.clock

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run on a disposable emulator. Tests clear this application's state, never a user's real phone. */
@RunWith(AndroidJUnit4::class)
class AlarmSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private var activity: Activity? = null

    @Before fun setUp() {
        assumeTrue("Only run these destructive app-state tests on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, RingService::class.java))
            store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            AlarmNotifications.channel(context)
        }
    }

    private fun launch(type: Class<out Activity>, id: Int? = null): Activity {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        id?.let { intent.putExtra("id", it) }
        return instrumentation.startActivitySync(intent).also { activity = it; instrumentation.waitForIdleSync() }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun all() = views(activity!!.window.decorView)
    private fun click(text: String) {
        instrumentation.runOnMainSync { (all().find { it is TextView && it.text.toString() == text } ?: error("Missing $text")).performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Wait for SurfaceFlinger to present the new hierarchy rather than capturing a transition frame.
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun editorHasWheelsGroupedSettingsAndSavesFollowUps() {
        launch(AlarmEditorActivity::class.java, 42)
        instrumentation.runOnMainSync {
            val wheels = all().filterIsInstance<NumberPicker>()
            assertEquals(2, wheels.size)
        }
        click("Отложить")
        click("Проверка подъёма")
        screenshot("editor-gruvbox.png")
        click("Готово")
        val saved = store.alarms().single()
        assertEquals(10, saved.snoozeMinutes)
        assertEquals(1, saved.maxSnoozes)
        assertEquals(10, saved.wakeCheckMinutes)
        assertTrue(saved.enabled)
        assertTrue(saved.scheduledAt > System.currentTimeMillis())
    }

    @Test fun permissionsCollapseAndExpandWithoutHidingAlarms() {
        launch(MainActivity::class.java)
        instrumentation.runOnMainSync {
            assertTrue(all().filterIsInstance<TextView>().any { it.text.toString().contains("Всё готово к звонку") })
            assertFalse(all().filterIsInstance<TextView>().any { it.text.toString().startsWith("Xiaomi / MIUI") })
        }
        val caption = all().filterIsInstance<TextView>().first { it.text.toString().contains("Всё готово к звонку") }.text.toString()
        click(caption)
        instrumentation.runOnMainSync { assertTrue(all().filterIsInstance<TextView>().any { it.text.toString().startsWith("Xiaomi / MIUI") }) }
        val expanded = all().filterIsInstance<TextView>().first { it.text.toString().contains("Всё готово к звонку") }.text.toString()
        click(expanded)
        screenshot("main-gruvbox.png")
    }

    @Test fun deferredRingWaitsThenStartsAndDismissesOnce() {
        launch(MainActivity::class.java)
        val now = System.currentTimeMillis()
        val deferred = RingSession("smoke-due", Alarm(77, dismissMode = DismissMode.BUTTON), now + 2500,
            dueAt = now + 2500, isWakeCheck = true)
        instrumentation.runOnMainSync {
            val seed = store.enqueue(deferred.alarm)
            store.replaceSession(seed.token, deferred)
            assertNull(store.activeSession())
            assertTrue(AlarmScheduler.scheduleSession(context, deferred))
        }
        val deadline = SystemClock.elapsedRealtime() + 12_000
        fun isRinging(): Boolean = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(50).any { it.service.className == RingService::class.java.name && it.foreground }
        while (SystemClock.elapsedRealtime() < deadline && !isRinging()) SystemClock.sleep(100)
        assertTrue("Exact alarm must start the foreground ringing service", isRinging())
        assertEquals(deferred.token, store.activeSession()?.token)
        // Let the exact-alarm receiver create the foreground service before opening its screen.
        SystemClock.sleep(6000) // Let the initial Android heads-up notification leave the screenshot.
        launch(RingActivity::class.java)
        screenshot("ring-gruvbox.png")
        instrumentation.runOnMainSync { assertTrue(RingActions.dismiss(context, store.activeSession()!!)) }
        assertTrue(store.sessions().isEmpty())
    }

    @Test fun snoozePersistsDelayAndLimitAndFinalDismissalCreatesSingleCheck() {
        launch(MainActivity::class.java)
        instrumentation.runOnMainSync {
            val alarm = Alarm(80, dismissMode = DismissMode.BUTTON, snoozeMinutes = 10, wakeCheckMinutes = 10)
            val original = store.enqueue(alarm)
            assertTrue(RingActions.snooze(context, original))
            assertNull(store.activeSession())
            val postponed = store.sessions().single()
            assertEquals(1, postponed.snoozeCount)
            assertTrue(postponed.dueAt - System.currentTimeMillis() in 590_000L..600_000L)
            // Simulate delivery without waiting ten real minutes; the previous test exercises actual exact delivery.
            val due = postponed.copy(dueAt = 0)
            AlarmScheduler.cancelSession(context, postponed)
            store.replaceSession(postponed.token, due)
            assertFalse(RingActions.snooze(context, due))
            assertTrue(RingActions.dismiss(context, due))
            val check = store.sessions().single()
            assertTrue(check.isWakeCheck)
            assertTrue(check.dueAt > System.currentTimeMillis())
            AlarmScheduler.cancelSession(context, check)
            store.replaceSession(check.token, check.copy(dueAt = 0))
            assertTrue(RingActions.dismiss(context, store.activeSession()!!))
            assertTrue(store.sessions().isEmpty())
        }
    }
}
