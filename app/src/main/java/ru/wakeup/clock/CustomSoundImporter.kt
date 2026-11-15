package ru.wakeup.clock

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class SoundChoice(val uri: String, val title: String)
class SoundImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

object CustomSoundImporter {
    private const val MAX_BYTES = 100L * 1024 * 1024

    /** Copy while the picker grant is valid. A seekable private file also works before first unlock. */
    fun import(context: Context, source: Uri): SoundChoice {
        val title = runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Своя мелодия"
        val directory = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "alarm-sounds")
        if (!directory.isDirectory && !directory.mkdirs()) throw SoundImportException("Не удалось сохранить мелодию: проверь свободное место.")
        val temporary = File.createTempFile("import-", ".audio", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            (context.contentResolver.openInputStream(source) ?: throw SoundImportException("Файл недоступен. Выбери его заново.")).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_BYTES) throw SoundImportException("Выбери аудиофайл размером до 100 МБ.")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw SoundImportException("Выбранный файл пуст.")
            try { AlarmAudio.prepare(context, Uri.fromFile(temporary)).release() }
            catch (error: Exception) { throw SoundImportException("Не удалось воспроизвести этот файл. Выбери поддерживаемый аудиофайл, например MP3 или WAV.", error) }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val saved = File(directory, "$hash.audio")
            if (!saved.exists() && !temporary.renameTo(saved)) throw SoundImportException("Не удалось сохранить мелодию. Проверь свободное место.")
            return SoundChoice(Uri.fromFile(saved).toString(), title)
        } finally { temporary.delete() }
    }
}

/** Remember the last successful selection; already saved alarms retain their own sound and volume. */
class SoundDefaults(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("sound-defaults", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun current(): SoundChoice? {
        if (prefs.contains("uri")) return SoundChoice(prefs.getString("uri", "") ?: "", prefs.getString("title", "") ?: "")
        // Alarms were saved in edit order before this preference existed.
        return AlarmStore(appContext).alarms().lastOrNull()?.let { SoundChoice(it.sound, it.soundTitle) }
    }
    fun remember(choice: SoundChoice) { prefs.edit().putString("uri", choice.uri).putString("title", choice.title).commit() }

    fun newAlarm(id: Int): Alarm {
        val sound = current()
        return Alarm(id, sound = sound?.uri ?: "", soundTitle = sound?.title ?: "",
            dismissMode = if (StationStore(appContext).get() == null) DismissMode.BUTTON else DismissMode.QR)
    }
}
