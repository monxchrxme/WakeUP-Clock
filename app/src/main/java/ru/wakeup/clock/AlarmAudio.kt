package ru.wakeup.clock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import java.io.File

data class AlarmPlayback(val player: MediaPlayer, val uri: Uri, val usedFallback: Boolean)

/** File imports and actual alarms use the same decoder and alarm audio attributes. */
object AlarmAudio {
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun prepare(context: Context, uri: Uri): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            if (uri.scheme == "file") player.setDataSource(File(requireNotNull(uri.path)).absolutePath)
            else player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            return player
        } catch (error: Exception) {
            player.release()
            throw error
        }
    }

    fun start(context: Context, sound: String): AlarmPlayback {
        val uris = listOfNotNull(
            sound.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            Uri.parse("android.resource://${context.packageName}/${R.raw.fallback_alarm}"),
        ).distinct()
        var lastError: Exception? = null
        for (uri in uris) {
            val candidate = try { prepare(context, uri) } catch (error: Exception) { lastError = error; continue }
            try {
                candidate.start()
                return AlarmPlayback(candidate, uri, sound.isNotBlank() && uri.toString() != sound)
            } catch (error: Exception) {
                candidate.release()
                lastError = error
            }
        }
        throw IllegalStateException("Ни одна мелодия будильника недоступна", lastError)
    }
}
