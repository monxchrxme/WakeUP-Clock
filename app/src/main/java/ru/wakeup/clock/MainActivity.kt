package ru.wakeup.clock

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private var permissionsExpanded: Boolean? = null
    private var lastReady: Boolean? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val pairScanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            if (AlarmStore(this).sessions().isNotEmpty()) toast("Во время звонка или ожидания повтора менять привязку нельзя")
            else {
                val station = QrProtocol.parsePair(result.contents)
                if (station == null) toast("Нужен QR из раздела «Привязать телефон» на сайте")
                else AlertDialog.Builder(this).setTitle("Связать этот браузер?")
                    .setMessage("Станция ${station.id.takeLast(6)}. QR-будильники будут отключаться кодами только из этого браузера.")
                    .setPositiveButton("Связать") { _, _ -> StationStore(this).save(station); render() }
                    .setNegativeButton("Отмена", null).show()
            }
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("permissionsExpanded") == true)
            permissionsExpanded = savedInstanceState.getBoolean("permissionsExpanded")
        AlarmNotifications.channel(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        permissionsExpanded?.let { outState.putBoolean("permissionsExpanded", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.restore(this, recompute = false)
        if (lastReady == false && ready()) permissionsExpanded = false
        lastReady = ready()
        render()
    }

    private fun ready(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return AlarmScheduler.permitted(this) && manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(AlarmNotifications.CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
    }

    private fun render() {
        val store = AlarmStore(this)
        val root = Ui.root(this)
        root.addView(Ui.title(this, "WakeUP Clock"))
        root.addView(Ui.text(this, "Утро начинается с одного шага.", 16f, true))
        if (store.activeSession() != null) root.addView(Ui.button(this, "Вернуться к звонку", true) { startActivity(Intent(this, RingActivity::class.java)) })
        store.sessions().filter { it.dueAt > System.currentTimeMillis() }.sortedBy { it.dueAt }.forEach {
            root.addView(Ui.text(this, "${if (it.isWakeCheck) "Проверка подъёма" else "Отложенный звонок"} · ${nextLabel(it.dueAt)}", 15f))
        }
        val complete = ready()
        val expanded = permissionsExpanded ?: !complete
        root.addView(Ui.button(this, "${if (complete) "● Всё готово к звонку" else "Нужны разрешения"}  ${if (expanded) "⌃" else "⌄"}") {
            permissionsExpanded = !expanded; render()
        })
        if (expanded) {
            val permissions = Ui.card(this)
            val manager = getSystemService(NotificationManager::class.java)
            if (!AlarmScheduler.permitted(this)) permissions.addView(Ui.button(this, "Разрешить точные будильники") {
                openSetting(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
            if (!manager.areNotificationsEnabled()) permissions.addView(Ui.button(this, "Разрешить уведомления") {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            })
            if ((manager.getNotificationChannel(AlarmNotifications.CHANNEL)?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH)
                permissions.addView(Ui.button(this, "Включить всплывающие уведомления звонка") {
                    openSetting(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifications.CHANNEL))
                })
            if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) permissions.addView(Ui.button(this, "Разрешить показ поверх экрана блокировки") {
                openSetting(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
            permissions.addView(Ui.text(this, "Xiaomi / MIUI: включи автозапуск и выбери режим батареи без ограничений. В режиме «Не беспокоить» разреши будильники. Названия пунктов зависят от прошивки.", 15f, true))
            permissions.addView(Ui.button(this, "Настройки приложения и батареи") { openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
            root.addView(permissions)
        }
        root.addView(Ui.text(this, "Будильники", 24f))
        val alarms = store.alarms().sortedWith(compareBy({ it.hour }, { it.minute }))
        if (alarms.isEmpty()) root.addView(Ui.text(this, "Добавь время первого подъёма.", 16f, true))
        for (alarm in alarms) {
            val card = Ui.card(this)
            card.setOnClickListener { edit(alarm) }
            card.addView(Switch(this).apply {
                text = Ui.time(alarm); textSize = 46f; setTextColor(if (alarm.enabled) Ui.INK else Ui.MUTED); isChecked = alarm.enabled
                setOnCheckedChangeListener { _, checked ->
                    if (checked && !canArm(alarm.dismissMode)) { setOnCheckedChangeListener(null); isChecked = false; return@setOnCheckedChangeListener }
                    if (!AlarmScheduler.schedule(this@MainActivity, alarm.copy(enabled = checked))) toast("Нет разрешения на точный будильник")
                    render()
                }
            })
            card.addView(Ui.text(this, "${alarm.label.ifBlank { "Будильник" }} · ${Ui.daysLabel(alarm.days)}", 16f, true))
            card.addView(Ui.text(this, "${if (alarm.dismissMode == DismissMode.QR) "QR с ноутбука" else "Кнопка"} · ${alarm.volume}%", 14f, true))
            if (alarm.enabled && alarm.scheduledAt > 0) card.addView(Ui.text(this, "Следующий: ${nextLabel(alarm.scheduledAt)}", 14f, true))
            root.addView(card)
        }
        root.addView(Ui.button(this, "＋ Добавить будильник", true) {
            val id = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }.first { candidate -> alarms.none { it.id == candidate } }
            edit(Alarm(id = id))
        })
        val station = StationStore(this).get()
        val pairing = Ui.card(this)
        pairing.addView(Ui.text(this, station?.let { "Ноутбук · браузер ${it.id.takeLast(6)}" } ?: "Свяжи ноутбук для отключения QR-кодом", 16f))
        pairing.addView(Ui.button(this, if (station == null) "Связать с браузером" else "Изменить привязку браузера") {
            if (store.sessions().isNotEmpty()) toast("Во время звонка или ожидания повтора менять привязку нельзя")
            else pairScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR привязки с сайта на ноутбуке").setBeepEnabled(false).setOrientationLocked(false))
        })
        root.addView(pairing)
    }

    private fun canArm(mode: DismissMode): Boolean {
        if (!ready()) { toast("Сначала настрой разрешения"); permissionsExpanded = true; render(); return false }
        if (mode == DismissMode.QR && StationStore(this).get() == null) { toast("Сначала свяжи телефон с браузером"); return false }
        return true
    }
    private fun edit(alarm: Alarm) { startActivity(Intent(this, AlarmEditorActivity::class.java).putExtra("id", alarm.id)) }
    private fun nextLabel(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM · HH:mm"))
    private fun openSetting(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
