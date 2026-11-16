package ru.wakeup.clock

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RandomSoundsTest {
    private val songs = listOf(SoundChoice("file:///a", "A"), SoundChoice("file:///b", "B"), SoundChoice("file:///c", "C"))
    private val alarm = Alarm(1, sound = "file:///ordinary", soundTitle = "Обычная", randomSound = true, randomSounds = songs)

    @Test fun disabledModeKeepsTheOrdinarySongEvenWithARememberedPool() {
        assertEquals(SoundChoice(alarm.sound, alarm.soundTitle), RandomSounds.pick(alarm.copy(randomSound = false), songs[0].uri))
    }
    @Test fun consecutiveRingsNeverRepeatThePreviousSong() {
        val random = Random(42)
        var previous: SoundChoice? = null
        repeat(200) {
            val next = RandomSounds.pick(alarm, previous?.uri, random)
            assertTrue(next in songs)
            assertNotEquals(previous, next)
            previous = next
        }
    }
    @Test fun everyOtherSongCanBeSelected() {
        val random = Random(13)
        val picked = (0 until 100).map { RandomSounds.pick(alarm, songs[0].uri, random) }.toSet()
        assertEquals(songs.drop(1).toSet(), picked)
    }
    @Test fun duplicateEntriesCannotCauseImmediateRepeats() {
        val duplicated = alarm.copy(randomSounds = listOf(songs[0], songs[0], songs[1]))
        repeat(20) { assertEquals(songs[1], RandomSounds.pick(duplicated, songs[0].uri)) }
    }
    @Test fun incompleteStoredPoolsStillHaveAPlayableChoice() {
        assertEquals(songs[0], RandomSounds.pick(alarm.copy(randomSounds = listOf(songs[0])), songs[0].uri))
        assertEquals(SoundChoice(alarm.sound, alarm.soundTitle), RandomSounds.pick(alarm.copy(randomSounds = emptyList()), null))
    }
}
