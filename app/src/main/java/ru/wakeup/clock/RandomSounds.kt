package ru.wakeup.clock

import kotlin.random.Random

object RandomSounds {
    const val MAX_SONGS = 50

    fun pick(alarm: Alarm, previousUri: String?, random: Random = Random.Default): SoundChoice {
        val songs = alarm.randomSounds.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        if (!alarm.randomSound || songs.isEmpty()) return SoundChoice(alarm.sound, alarm.soundTitle)
        val alternatives = songs.filterNot { it.uri == previousUri }
        return (alternatives.ifEmpty { songs }).random(random)
    }
}
