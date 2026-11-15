package ru.wakeup.clock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class RingActivity : ComponentActivity() {
    private var scanningToken: String? = null
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val token = scanningToken
        scanningToken = null
        val session = AlarmStore(this).sessions().firstOrNull()
        if (result.contents != null && session != null && session.token == token) {
            val station = StationStore(this).get()
            if (station != null && QrProtocol.verify(result.contents, station, System.currentTimeMillis(), session.startedAt))
                dismiss(session)
            else Toast.makeText(this, "Код чужой или устарел. Обнови страницу и проверь автоматическое время на обоих устройствах.", Toast.LENGTH_LONG).show()
        }
        render()
    }
    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanningToken = savedInstanceState?.getString("scanningToken")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { Toast.makeText(this@RingActivity, "Будильник продолжает звонить", Toast.LENGTH_SHORT).show() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scanningToken", scanningToken)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, changed, IntentFilter(RingService.STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (AlarmStore(this).sessions().isNotEmpty()) RingService.start(this)
        render()
    }
    override fun onStop() { unregisterReceiver(changed); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); render() }

    private fun render() {
        val session = AlarmStore(this).sessions().firstOrNull() ?: run { finish(); return }
        val root = Ui.root(this)
        root.addView(Ui.text(this, if (session.test) "ПРОБНЫЙ ЗВОНОК" else "НОВЫЙ ДЕНЬ", 13f, true))
        root.addView(Ui.text(this, Ui.time(session.alarm), 76f))
        root.addView(Ui.title(this, "Пора вставать."))
        root.addView(Ui.text(this, if (session.alarm.dismissMode == DismissMode.QR)
            "Дойди до ноутбука, открой сайт и отсканируй свежий QR-код. Звук продолжится во время сканирования."
            else "Отключи будильник, когда будешь готов начать день.", 18f, true))

        if (session.alarm.dismissMode == DismissMode.QR) {
            root.addView(Ui.button(this, "Сканировать QR", true) { unlockAndScan(session) })
            root.addView(Ui.text(this, "Чужой код и экран привязки не отключат звонок.", 14f, true))
        } else root.addView(Ui.button(this, "Я встал · отключить", true) { dismiss(session) })
        if (session.test) root.addView(Ui.button(this, "Остановить проверку") { dismiss(session) })
        val count = AlarmStore(this).sessions().size
        if (count > 1) root.addView(Ui.text(this, "Ещё в очереди: ${count - 1}", 14f, true))
    }

    private fun unlockAndScan(session: RingSession) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchScanner(session)
            })
        } else launchScanner(session)
    }

    private fun launchScanner(session: RingSession) {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        scanningToken = session.token
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Отсканируй свежий QR с ноутбука").setBeepEnabled(false).setOrientationLocked(false))
    }

    private fun dismiss(session: RingSession) {
        val store = AlarmStore(this)
        if (store.sessions().firstOrNull()?.token != session.token) return
        store.removeSession(session.token)
        if (store.sessions().isEmpty()) {
            stopService(Intent(this, RingService::class.java))
            finish()
        } else { RingService.start(this); render() }
    }
}
