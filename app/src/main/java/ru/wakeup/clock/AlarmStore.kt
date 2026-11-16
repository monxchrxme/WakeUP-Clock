package ru.wakeup.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Alarm state must remain available during Direct Boot. No station key is stored here. */
class AlarmStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun alarms(): List<Alarm> = array("alarms").let { values ->
        (0 until values.length()).map { decodeAlarm(values.getJSONObject(it)) }
    }

    fun save(alarm: Alarm) {
        writeAlarms(alarms().filterNot { it.id == alarm.id } + alarm)
    }

    fun delete(id: Int) = writeAlarms(alarms().filterNot { it.id == id })
    fun deleteAll(ids: Set<Int>): List<Alarm> {
        val before = alarms()
        writeAlarms(before.filterNot { it.id in ids })
        return before.filter { it.id in ids }
    }
    fun disableAll(ids: Set<Int>): List<Alarm> {
        val before = alarms()
        writeAlarms(before.map { if (it.id in ids) it.copy(enabled = false, scheduledAt = 0) else it })
        return before.filter { it.id in ids }
    }
    private fun writeAlarms(alarms: List<Alarm>) {
        prefs.edit().putString("alarms", JSONArray().apply { alarms.forEach { put(encodeAlarm(it)) } }.toString()).commit()
    }

    fun sessions(): List<RingSession> = array("sessions").let { values ->
        (0 until values.length()).map {
            val json = values.getJSONObject(it)
            RingSession(json.getString("token"), decodeAlarm(json.getJSONObject("alarm")),
                json.getLong("startedAt"), json.optBoolean("test"), json.optLong("dueAt"),
                json.optInt("snoozeCount"), json.optBoolean("isWakeCheck"),
                json.optJSONObject("selectedSound")?.let(SoundJson::decode))
        }
    }

    fun enqueue(alarm: Alarm, test: Boolean = false): RingSession {
        val session = RingSession(UUID.randomUUID().toString(), alarm, System.currentTimeMillis(), test)
        writeSessions(sessions() + session)
        return session
    }

    fun removeSession(token: String) = writeSessions(sessions().filterNot { it.token == token })
    fun replaceSession(token: String, replacement: RingSession?) {
        writeSessions(sessions().mapNotNull { if (it.token == token) replacement else it })
    }
    fun activeSession(now: Long = System.currentTimeMillis()): RingSession? = sessions().firstOrNull { it.dueAt <= now }

    /** Freeze the draw and history in one write, before the service starts playback. */
    fun resolveSound(session: RingSession): RingSession {
        val all = sessions()
        val saved = all.firstOrNull { it.token == session.token } ?: return session
        if (saved.selectedSound != null) return saved
        val historyKey = "random-last-${saved.alarm.id}"
        val choice = RandomSounds.pick(saved.alarm, prefs.getString(historyKey, null))
        val resolved = saved.copy(selectedSound = choice)
        val editor = prefs.edit().putString("sessions", encodeSessions(all.map { if (it.token == saved.token) resolved else it }).toString())
        if (saved.alarm.randomSound) editor.putString(historyKey, choice.uri)
        editor.commit()
        return resolved
    }
    private fun writeSessions(sessions: List<RingSession>) {
        prefs.edit().putString("sessions", encodeSessions(sessions).toString()).commit()
    }
    private fun encodeSessions(sessions: List<RingSession>) = JSONArray().apply {
        sessions.forEach { put(JSONObject().put("token", it.token).put("alarm", encodeAlarm(it.alarm))
            .put("startedAt", it.startedAt).put("test", it.test).put("dueAt", it.dueAt)
            .put("snoozeCount", it.snoozeCount).put("isWakeCheck", it.isWakeCheck)
            .put("selectedSound", it.selectedSound?.let(SoundJson::encode))) }
    }

    // If the process is killed while ringing, the next process restores the original stream volume.
    fun originalVolume(): Int? = if (prefs.contains("originalVolume")) prefs.getInt("originalVolume", 0) else null
    fun rememberVolume(value: Int) { prefs.edit().putInt("originalVolume", value).commit() }
    fun forgetVolume() { prefs.edit().remove("originalVolume").commit() }
    fun soundFallback() = prefs.getBoolean("soundFallback", false)
    fun setSoundFallback(value: Boolean) { prefs.edit().putBoolean("soundFallback", value).apply() }

    private fun array(key: String): JSONArray = JSONArray(prefs.getString(key, "[]"))

    private fun encodeAlarm(a: Alarm) = AlarmJson.encode(a)
    private fun decodeAlarm(j: JSONObject) = AlarmJson.decode(j)
}

object AlarmJson {
    fun encode(a: Alarm) = JSONObject().put("id", a.id).put("hour", a.hour)
        .put("minute", a.minute).put("days", a.days).put("enabled", a.enabled)
        .put("sound", a.sound).put("volume", a.volume).put("persistence", a.persistence.name)
        .put("dismissMode", a.dismissMode.name).put("scheduledAt", a.scheduledAt)
        .put("label", a.label).put("soundTitle", a.soundTitle).put("vibrate", a.vibrate)
        .put("fadeInSeconds", a.fadeInSeconds).put("snoozeMinutes", a.snoozeMinutes)
        .put("maxSnoozes", a.maxSnoozes).put("wakeCheckMinutes", a.wakeCheckMinutes)
        .put("randomSound", a.randomSound).put("randomSounds", SoundJson.encodeList(a.randomSounds))

    fun decode(j: JSONObject): Alarm {
        val persistence = Persistence.valueOf(j.getString("persistence"))
        return Alarm(
            id = j.getInt("id"), hour = j.getInt("hour"), minute = j.getInt("minute"), days = j.getInt("days"),
            enabled = j.getBoolean("enabled"), sound = j.optString("sound"), volume = j.optInt("volume", 80).coerceIn(20, 100),
            persistence = persistence, dismissMode = DismissMode.valueOf(j.getString("dismissMode")),
            scheduledAt = j.optLong("scheduledAt"), label = j.optString("label"), soundTitle = j.optString("soundTitle"),
            vibrate = j.optBoolean("vibrate", persistence == Persistence.STRONG),
            fadeInSeconds = j.optInt("fadeInSeconds", if (persistence == Persistence.STRONG) 30 else 0).coerceIn(0, 120),
            snoozeMinutes = j.optInt("snoozeMinutes").coerceIn(0, 30),
            maxSnoozes = j.optInt("maxSnoozes", 1).coerceIn(1, 3),
            wakeCheckMinutes = j.optInt("wakeCheckMinutes").coerceIn(0, 30),
            randomSound = j.optBoolean("randomSound"), randomSounds = SoundJson.decodeList(j.optJSONArray("randomSounds")),
        )
    }
}

object SoundJson {
    fun encode(sound: SoundChoice) = JSONObject().put("uri", sound.uri).put("title", sound.title)
    fun decode(json: JSONObject) = SoundChoice(json.optString("uri"), json.optString("title"))
    fun encodeList(sounds: List<SoundChoice>) = JSONArray().apply { sounds.forEach { put(encode(it)) } }
    fun decodeList(json: JSONArray?): List<SoundChoice> = if (json == null) emptyList() else
        (0 until json.length()).mapNotNull { json.optJSONObject(it)?.let(::decode) }
            .filter { it.uri.isNotBlank() }.distinctBy { it.uri }.take(RandomSounds.MAX_SONGS)
}

/** Credential-encrypted storage: pairing is only accessible after the user unlocks the phone. */
class StationStore(context: Context) {
    private val prefs = context.getSharedPreferences("station", Context.MODE_PRIVATE)
    fun get(): Station? {
        val id = prefs.getString("id", null) ?: return null
        val secret = prefs.getString("secret", null) ?: return null
        return QrProtocol.parsePair("wakeup://pair/v1/$id/$secret")
    }
    fun save(station: Station) {
        prefs.edit().putString("id", station.id).putString("secret", station.secret).commit()
    }
}
