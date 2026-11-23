package ru.wakeup.clock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.ceil

class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: AlarmStore
    private lateinit var audio: AudioManager
    private lateinit var layers: AlarmSoundLayers
    private var current: RingSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var beganElapsed = 0L
    private var focusPaused = false
    private val attributes = AlarmAudio.attributes

    private val pulse = object : Runnable {
        override fun run() {
            val session = current ?: return
            val elapsed = SystemClock.elapsedRealtime() - beganElapsed
            if (!focusPaused && pauseAutomatically(session, elapsed)) return
            if (session.alarm.persistence == Persistence.STRONG ||
                SystemClock.elapsedRealtime() - beganElapsed <= session.alarm.fadeInSeconds * 1000L) updateVolume(session)
            if (!focusPaused) play(session)
            if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60_000L)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = AlarmStore(this)
        audio = getSystemService(AudioManager::class.java)
        layers = AlarmSoundLayers(this)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "wakeup:ringing").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = store.activeSession()?.let(store::resolveSound)?.let { store.beginRinging(it) }
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = AlarmNotifications.ringing(this, session)
        if (Build.VERSION.SDK_INT >= 29) startForeground(AlarmNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(AlarmNotifications.ID, notification)

        if (current?.token != session.token) {
            handler.removeCallbacks(pulse)
            layers.release()
            current = session
            focusPaused = false
            beganElapsed = SystemClock.elapsedRealtime() - IrritationRules.elapsed(session, System.currentTimeMillis())
            val elapsed = SystemClock.elapsedRealtime() - beganElapsed
            if (pauseAutomatically(session, elapsed)) return START_STICKY
            if (store.originalVolume() == null) store.rememberVolume(audio.getStreamVolume(AudioManager.STREAM_ALARM))
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener {
                    // Alarms pause for a call; resume when Android returns audio focus.
                    focusPaused = it != AudioManager.AUDIOFOCUS_GAIN
                    if (!focusPaused) { layers.resume(); current?.let { play(it) } } else layers.pause()
                }.build()
            audio.requestAudioFocus(focus!!)
            updateVolume(session)
            play(session)
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator.cancel()
            if (session.alarm.vibrate) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attributes)
            }
            wakeLock?.acquire(10 * 60_000L)
            handler.post(pulse)
            sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
        }
        return START_STICKY
    }

    private fun pauseAutomatically(session: RingSession, elapsed: Long): Boolean {
        if (!AutoRepeatRules.shouldPause(session, elapsed) || !RingActions.automaticRepeat(this, session, elapsed)) return false
        current = null
        layers.release()
        return true
    }

    private fun updateVolume(session: RingSession) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val fraction = if (session.alarm.fadeInSeconds > 0)
            (0.25 + 0.75 * ((SystemClock.elapsedRealtime() - beganElapsed) / (session.alarm.fadeInSeconds * 1000.0)).coerceIn(0.0, 1.0)) else 1.0
        val volume = ceil(max * (session.alarm.volume / 100.0) * fraction).toInt().coerceIn(1, max)
        // Some DND policies prohibit stream changes. Keep trying sound without crashing the service.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
    }

    private fun play(session: RingSession) {
        layers.ensure(session, SystemClock.elapsedRealtime() - beganElapsed)
        val irritating = layers.irritation != null
        val fallbackChanged = store.soundFallback() != layers.usedFallback
        val irritationChanged = store.irritationActive() != irritating
        val changed = fallbackChanged || irritationChanged
        if (fallbackChanged) store.setSoundFallback(layers.usedFallback)
        if (irritationChanged) store.setIrritationActive(irritating)
        if (changed) sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping away the app is not a dismissal. A pending exact alarm also recovers a killed service.
        if (store.activeSession() != null) AlarmScheduler.scheduleRecovery(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        current = null
        layers.release()
        getSystemService(Vibrator::class.java).cancel()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        store.originalVolume()?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        store.forgetVolume()
        store.setSoundFallback(false)
        store.setIrritationActive(false)
        if (store.activeSession() != null) AlarmScheduler.scheduleRecovery(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STATE_CHANGED = "ru.wakeup.clock.STATE_CHANGED"
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RingService::class.java))
        }
    }
}
