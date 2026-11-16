package ru.wakeup.clock

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
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
class RandomSoundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)
    private var activity: Activity? = null
    private fun uri(name: String) = Uri.parse("content://ru.wakeup.clock.test.audio/$name")

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
            context.stopService(Intent(context, RingService::class.java))
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            context.createDeviceProtectedStorageContext().getSharedPreferences("sound-defaults", 0).edit().clear().commit()
            AlarmNotifications.channel(context)
        }
        context.contentResolver.call(uri("custom-tone.wav"), "enable", null, null)
    }

    @After fun finish() {
        instrumentation.runOnMainSync {
            store.sessions().forEach { AlarmScheduler.cancelSession(context, it); store.removeSession(it.token) }
            context.stopService(Intent(context, RingService::class.java))
            activity?.finish()
        }
    }

    private fun launch(id: Int) {
        activity = instrumentation.startActivitySync(Intent(context, AlarmEditorActivity::class.java)
            .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for random music", condition())
    }
    private fun waitForText(text: String) = waitUntil {
        var found = false
        instrumentation.runOnMainSync { found = text in texts(activity!!.window.decorView) }
        found
    }
    private fun picker(vararg files: String): Instrumentation.ActivityMonitor {
        val selection = ClipData.newRawUri("Audio", uri(files.first()))
        files.drop(1).forEach { selection.addItem(ClipData.Item(uri(it))) }
        return object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                assertTrue("The file picker must allow selecting multiple songs", intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { clipData = selection })
            }
        }.also { instrumentation.addMonitor(it) }
    }
    private fun pool() = listOf("custom-tone.wav", "other-tone.wav").map { CustomSoundImporter.import(context, uri(it)) }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun multiFileSelectionSurvivesRotationAndKeepsTheTwoMemoriesSeparate() {
        val ordinary = CustomSoundImporter.import(context, uri("regular-tone.wav"))
        SoundDefaults(context).remember(ordinary)
        val monitor = picker("custom-tone.wav", "other-tone.wav")
        try {
            launch(601)
            onView(withText("Случайная мелодия")).check(matches(isNotChecked())).perform(scrollTo(), click())
            onView(withContentDescription("Песни: Выбрать файлы")).perform(scrollTo(), click())
            onView(withText("Добавить песни")).perform(click())
            waitForText("Выбрано: 2")
            val rotation = instrumentation.addMonitor(AlarmEditorActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity!!.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(rotation, 5_000)
            instrumentation.removeMonitor(rotation)
            assertNotNull(activity)
            instrumentation.waitForIdleSync()
            onView(withText("Случайная мелодия")).check(matches(isChecked()))
            onView(withContentDescription("Песни: Выбрано: 2")).check(matches(isDisplayed()))
            screenshot("random-music-editor.png")
            onView(withText("Готово")).perform(click())
            val saved = store.alarms().single()
            assertTrue(saved.randomSound)
            assertEquals(2, saved.randomSounds.size)
            assertEquals(ordinary, SoundChoice(saved.sound, saved.soundTitle))
            assertEquals(ordinary, SoundDefaults(context).current())
            assertEquals(saved.randomSounds, SoundDefaults(context).randomChoices())
            launch(602)
            onView(withText("Случайная мелодия")).check(matches(isNotChecked()))
            onView(withText(ordinary.title)).check(matches(isDisplayed()))
            onView(withText("Случайная мелодия")).perform(click())
            onView(withContentDescription("Песни: Выбрано: 2")).perform(click())
            for (song in saved.randomSounds) onView(withText(song.title)).check(matches(isDisplayed()))
            screenshot("random-music-list.png")
            onView(withText("Готово")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun invalidBatchKeepsThePreviousPoolAndOrdinarySong() {
        val original = pool()
        val ordinary = CustomSoundImporter.import(context, uri("regular-tone.wav"))
        SoundDefaults(context).remember(ordinary)
        SoundDefaults(context).rememberRandom(original)
        launch(603)
        onView(withText("Случайная мелодия")).perform(scrollTo(), click())
        val monitor = picker("regular-tone.wav", "invalid")
            try {
                onView(withContentDescription("Песни: Выбрано: 2")).perform(scrollTo(), click())
                onView(withText("Добавить песни")).perform(click())
                waitUntil { instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Музыка не выбрана")?.isNotEmpty() == true }
                onView(withText("Музыка не выбрана")).check(matches(isDisplayed()))
                onView(withText("Понятно")).perform(click())
                assertEquals(original, SoundDefaults(context).randomChoices())
                assertEquals(ordinary, SoundDefaults(context).current())
            } finally { instrumentation.removeMonitor(monitor) }
        onView(withText("Готово")).perform(click())
        assertEquals(original, store.alarms().single().randomSounds)
    }

    @Test fun songsCanBeAddedOneAtATimeRemovedOrClearedAndDuplicatesAreIgnored() {
        val original = pool()
        val ordinary = CustomSoundImporter.import(context, uri("regular-tone.wav"))
        SoundDefaults(context).remember(ordinary)
        SoundDefaults(context).rememberRandom(original)
        launch(604)
        onView(withText("Случайная мелодия")).perform(scrollTo(), click())
        onView(withContentDescription("Песни: Выбрано: 2")).perform(scrollTo(), click())
        onView(withContentDescription("Удалить песню custom-tone.wav")).perform(click())
        assertEquals(original.drop(1), SoundDefaults(context).randomChoices())
        val monitor = picker("regular-tone.wav")
        try { onView(withText("Добавить песни")).perform(click()); waitForText("Выбрано: 2") }
        finally { instrumentation.removeMonitor(monitor) }
        assertEquals(setOf(original[1], ordinary), SoundDefaults(context).randomChoices().toSet())
        onView(withContentDescription("Песни: Выбрано: 2")).perform(click())
        val duplicate = picker("other-tone.wav", "regular-tone.wav")
        try {
            onView(withText("Добавить песни")).perform(click())
            waitUntil {
                var ready = false
                instrumentation.runOnMainSync { ready = androidx.lifecycle.ViewModelProvider(activity as AlarmEditorActivity)[SoundImportModel::class.java].state == SoundImportModel.State.Idle }
                ready
            }
        } finally { instrumentation.removeMonitor(duplicate) }
        assertEquals(2, SoundDefaults(context).randomChoices().size)
        assertEquals(ordinary, SoundDefaults(context).current())
        onView(withContentDescription("Песни: Выбрано: 2")).perform(click())
        onView(withText("Очистить список")).perform(click())
        onView(withText("Список пуст")).check(matches(isDisplayed()))
        assertTrue(SoundDefaults(context).randomChoices().isEmpty())
        assertEquals(ordinary, SoundDefaults(context).current())
        onView(withText("Готово")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        onView(withText("Случайная мелодия")).perform(scrollTo(), click())
        onView(withText(ordinary.title)).check(matches(isDisplayed()))
        onView(withText("Готово")).perform(click())
        assertFalse(store.alarms().single().randomSound)
        assertTrue(store.alarms().single().randomSounds.isEmpty())
    }

    @Test fun serviceRecoveryKeepsTheDrawAndTheNextRingSelectsAnotherLocalSong() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val songs = pool()
        val ordinary = SoundChoice("android.resource://${context.packageName}/${R.raw.fallback_alarm}", "Обычная")
        val alarm = Alarm(701, sound = ordinary.uri, soundTitle = ordinary.title, dismissMode = DismissMode.BUTTON,
            randomSound = true, randomSounds = songs, vibrate = false)
        instrumentation.runOnMainSync { store.save(alarm); store.enqueue(alarm); RingService.start(context) }
        waitUntil { store.activeSession()?.selectedSound != null && isRinging() }
        val first = store.activeSession()!!
        assertTrue(first.selectedSound in songs)
        // Loss of the original provider cannot affect playback of the imported copies.
        context.contentResolver.call(uri("custom-tone.wav"), "disable", null, null)
        val playback = AlarmAudio.start(context, first.selectedSound!!.uri)
        try {
            assertFalse(playback.usedFallback)
            assertTrue(playback.player.isLooping)
            assertTrue(playback.player.isPlaying)
            val expected = if (first.selectedSound.title == "custom-tone.wav") 700 else 1_100
            assertTrue(playback.player.duration in expected - 50..expected + 50)
        } finally { playback.player.release() }
        instrumentation.runOnMainSync { context.stopService(Intent(context, RingService::class.java)) }
        waitUntil { !isRinging() }
        instrumentation.runOnMainSync { RingService.start(context) }
        waitUntil { isRinging() }
        assertEquals(first, AlarmStore(context).activeSession())
        instrumentation.runOnMainSync {
            assertTrue(RingActions.dismiss(context, store.activeSession()!!))
            store.enqueue(alarm)
            RingService.start(context)
        }
        waitUntil { store.activeSession()?.selectedSound != null && store.activeSession()?.token != first.token }
        assertNotEquals(first.selectedSound, store.activeSession()!!.selectedSound)
        assertEquals(alarm, store.alarms().single())
    }

    @Suppress("DEPRECATION")
    private fun isRinging() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == RingService::class.java.name && it.foreground }

    @Test fun snoozeAndWakeCheckDrawAgainFromTheOriginalSnapshot() {
        val songs = pool()
        val alarm = Alarm(702, randomSound = true, randomSounds = songs, snoozeMinutes = 10, wakeCheckMinutes = 10)
        instrumentation.runOnMainSync {
            val first = store.resolveSound(store.enqueue(alarm))
            store.save(alarm.copy(randomSound = false, randomSounds = emptyList()))
            val snoozed = FollowUpRules.snooze(first, System.currentTimeMillis())!!
            assertNull(snoozed.selectedSound)
            store.replaceSession(first.token, snoozed)
            val second = store.resolveSound(snoozed)
            assertNotEquals(first.selectedSound, second.selectedSound)
            val check = FollowUpRules.wakeCheck(second, System.currentTimeMillis())!!
            store.replaceSession(second.token, check)
            val third = store.resolveSound(check)
            assertNotEquals(second.selectedSound, third.selectedSound)
            assertEquals(alarm, third.alarm)
        }
    }
}
