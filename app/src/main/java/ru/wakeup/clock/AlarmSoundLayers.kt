package ru.wakeup.clock

import android.content.Context
import kotlin.random.Random

/** At most two looping players; only the extra player is created when irritation becomes due. */
class AlarmSoundLayers(private val context: Context, private val random: Random = Random.Default) {
    var main: AlarmPlayback? = null
        private set
    var irritation: AlarmPlayback? = null
        private set
    private var paused = false
    val usedFallback get() = main?.usedFallback == true || irritation?.usedFallback == true

    fun ensure(session: RingSession, elapsedMillis: Long) {
        if (paused) return
        val choice = session.selectedSound ?: SoundChoice(session.alarm.sound, session.alarm.soundTitle)
        if (!playing(main)) {
            main?.player?.release()
            main = runCatching { AlarmAudio.start(context, choice.uri) }.getOrNull()
        }
        val primary = main ?: return
        if (IrritationRules.shouldStart(session, elapsedMillis) && !playing(irritation)) {
            irritation?.player?.release()
            val extra = IrritationRules.song(session.alarm, choice, random)
            val sameSong = !session.alarm.randomSound || extra.uri == choice.uri
            val source = if (sameSong) primary.uri.toString() else extra.uri
            irritation = runCatching {
                AlarmAudio.start(context, source) { duration ->
                    if (sameSong) IrritationRules.offset(duration, primary.player.currentPosition, random) else 0
                }
            }.getOrNull()
        }
    }

    fun pause() {
        paused = true
        listOfNotNull(main, irritation).forEach { runCatching { it.player.pause() } }
    }
    fun resume() {
        paused = false
        listOfNotNull(main, irritation).forEach { runCatching { it.player.start() } }
    }
    fun release() {
        listOfNotNull(main, irritation).forEach { it.player.release() }
        main = null; irritation = null; paused = false
    }
    private fun playing(playback: AlarmPlayback?) = runCatching { playback?.player?.isPlaying == true }.getOrDefault(false)
}
