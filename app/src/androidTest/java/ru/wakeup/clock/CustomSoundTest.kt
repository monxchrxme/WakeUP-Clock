package ru.wakeup.clock

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomSoundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AlarmStore(context)

    @Before fun setUp() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        instrumentation.runOnMainSync {
            store.alarms().forEach { AlarmScheduler.cancel(context, it.id); store.delete(it.id) }
            context.createDeviceProtectedStorageContext().getSharedPreferences("sound-defaults", 0).edit().clear().commit()
            AlarmNotifications.channel(context)
        }
        context.contentResolver.call(Uri.parse("content://ru.wakeup.clock.test.audio"), "enable", null, null)
    }

    private fun launch(id: Int) = instrumentation.startActivitySync(
        Intent(context, AlarmEditorActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun texts(root: View): List<String> = (if (root is TextView) listOf(root.text.toString()) else emptyList()) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { texts(root.getChildAt(it)) } else emptyList()

    @Test fun filePickerAcceptsReadableFileWithoutPersistentGrant() {
        val uri = Uri.parse("content://ru.wakeup.clock.test.audio/custom-tone.wav")
        val header = context.contentResolver.openInputStream(uri)!!.use { input -> ByteArray(4).also { assertEquals(4, input.read(it)) } }
        assertEquals("RIFF", String(header))
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)) else null
        }
        instrumentation.addMonitor(monitor)
        val activity = launch(201)
        try {
            onView(withText("Мелодия")).perform(click())
            onView(withText("Аудиофайл с телефона")).perform(click())
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var selected = false
            while (!selected && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { selected = texts(activity.window.decorView).contains("custom-tone.wav") }
                if (!selected) SystemClock.sleep(100)
            }
            assertTrue("The selected file must replace the default melody even without a persistent URI grant", selected)
            onView(withText("Готово")).perform(click())
            instrumentation.waitForIdleSync()
            val saved = store.alarms().single()
            assertTrue(saved.sound.startsWith("file://"))
            assertEquals("custom-tone.wav", saved.soundTitle)
            assertEquals(100, saved.volume)
            assertTrue(File(Uri.parse(saved.sound).path!!).canonicalPath.startsWith(
                context.createDeviceProtectedStorageContext().noBackupFilesDir.canonicalPath + "/alarm-sounds/"))
            // The provider can disappear after selection; the real alarm must still play the chosen audio.
            context.contentResolver.call(uri, "disable", null, null)
            val playing = AlarmAudio.start(context, saved.sound)
            try {
                assertFalse("A valid custom song must never silently fall back", playing.usedFallback)
                assertEquals(saved.sound, playing.uri.toString())
                assertTrue(playing.player.isPlaying)
                assertTrue(playing.player.isLooping)
                assertTrue("Must decode the 700 ms test song, not a system ringtone", playing.player.duration in 650..750)
            } finally { playing.player.release() }
            val next = launch(202)
            try {
                var selectedInNext = false
                instrumentation.runOnMainSync { selectedInNext = texts(next.window.decorView).contains("custom-tone.wav") }
                assertTrue("The next editor must inherit the last selected song", selectedInNext)
            } finally { instrumentation.runOnMainSync { next.finish() } }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun lastSoundPersistsAndAnotherSelectionReplacesItWithoutChangingExistingAlarms() {
        val song = CustomSoundImporter.import(context, Uri.parse("content://ru.wakeup.clock.test.audio/custom-tone.wav"))
        val defaults = SoundDefaults(context)
        defaults.remember(song)
        val existing = Alarm(301, sound = "", volume = 35)
        store.save(existing)
        val next = SoundDefaults(context).newAlarm(302)
        assertEquals(song.uri, next.sound)
        assertEquals(song.title, next.soundTitle)
        assertEquals(100, next.volume)
        assertEquals(existing, store.alarms().single())
        // The preference distinguishes an explicitly chosen system default from no saved selection.
        defaults.remember(SoundChoice("", ""))
        assertEquals("", SoundDefaults(context).newAlarm(303).sound)
        assertEquals(existing, store.alarms().single())
    }

    @Test fun invalidFileIsRejectedWithoutReplacingTheLastMelody() {
        val good = SoundChoice("android.resource://${context.packageName}/${R.raw.fallback_alarm}", "Встроенный сигнал")
        SoundDefaults(context).remember(good)
        val result = runCatching { CustomSoundImporter.import(context, Uri.parse("content://ru.wakeup.clock.test.audio/invalid")) }
        assertTrue("The file must be decoded before being accepted", result.isFailure)
        assertEquals(good, SoundDefaults(context).current())
        val directory = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "alarm-sounds")
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("import-") })
    }

    @Test fun missingCustomFileUsesExplicitlyReportedFallback() {
        val playback = AlarmAudio.start(context, Uri.fromFile(File(context.filesDir, "missing-audio.wav")).toString())
        try { assertTrue(playback.usedFallback); assertTrue(playback.player.isPlaying) }
        finally { playback.player.release() }
    }
}
