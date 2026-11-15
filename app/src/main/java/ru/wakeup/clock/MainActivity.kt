package ru.wakeup.clock

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
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
    private var editor = false
    private var draftSound = ""
    private var soundLabel: TextView? = null
    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            draftSound = uri?.toString() ?: ""
            soundLabel?.text = soundName(draftSound)
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val pairScanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            if (AlarmStore(this).sessions().isNotEmpty()) toast("Во время звонка менять привязку нельзя")
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
        AlarmNotifications.channel(this)
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.restore(this, recompute = false)
        if (!editor) render()
    }

    private fun ready(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return AlarmScheduler.permitted(this) && manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(AlarmNotifications.CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
    }

    private fun render() {
        editor = false
        soundLabel = null
        val store = AlarmStore(this)
        val root = Ui.root(this)
        root.addView(Ui.text(this, "ДОБРОЕ УТРО НАЧИНАЕТСЯ ВЕЧЕРОМ", 11f, true))
        root.addView(Ui.title(this, "Пора вставать."))
        root.addView(Ui.text(this, "Будильник с причиной встать с кровати.", 17f, true))
        if (store.sessions().isNotEmpty()) root.addView(Ui.button(this, "Вернуться к звонку", true) {
            startActivity(Intent(this, RingActivity::class.java))
        })

        val permissions = Ui.card(this)
        permissions.addView(Ui.text(this, if (ready()) "● Всё готово к звонку" else "Сначала настрой разрешения", 18f))
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
        permissions.addView(Ui.text(this, "На Xiaomi / MIUI: включи автозапуск и выбери для приложения режим батареи без ограничений. Отключи «Не беспокоить» либо разреши будильники. Названия пунктов зависят от прошивки.", 13f, true))
        permissions.addView(Ui.button(this, "Настройки приложения и батареи") {
            openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(permissions)

        val station = StationStore(this).get()
        val pairing = Ui.card(this)
        pairing.addView(Ui.text(this, "Ноутбук", 18f))
        pairing.addView(Ui.text(this, station?.let { "Связан браузер · ${it.id.takeLast(6)}" }
            ?: "Открой сайт на ноутбуке → «Привязать телефон». Затем сканируй здесь.", 14f, true))
        pairing.addView(Ui.button(this, if (station == null) "Связать с браузером" else "Связать другой браузер") {
            if (store.sessions().isNotEmpty()) toast("Во время звонка менять привязку нельзя")
            else pairScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR привязки с сайта на ноутбуке").setBeepEnabled(false).setOrientationLocked(false))
        })
        root.addView(pairing)

        root.addView(Ui.text(this, "Будильники", 23f))
        val alarms = store.alarms().sortedWith(compareBy({ it.hour }, { it.minute }))
        if (alarms.isEmpty()) root.addView(Ui.text(this, "Пока пусто. Добавь время первого подъёма.", 15f, true))
        for (alarm in alarms) {
            val card = Ui.card(this)
            val switch = Switch(this).apply {
                text = Ui.time(alarm); textSize = 36f; setTextColor(Ui.INK); isChecked = alarm.enabled
                setOnCheckedChangeListener { _, checked ->
                    if (checked && !canArm(alarm.dismissMode)) { isChecked = false; return@setOnCheckedChangeListener }
                    if (!AlarmScheduler.schedule(this@MainActivity, alarm.copy(enabled = checked))) toast("Нет разрешения на точный будильник")
                    render()
                }
            }
            card.addView(switch)
            card.addView(Ui.text(this, Ui.daysLabel(alarm.days), 14f, true))
            card.addView(Ui.text(this, "${if (alarm.dismissMode == DismissMode.QR) "QR с ноутбука" else "Кнопка"} · ${alarm.volume}% · ${if (alarm.persistence == Persistence.STRONG) "усиленный" else "обычный"}", 14f, true))
            if (alarm.enabled && alarm.scheduledAt > 0) card.addView(Ui.text(this, "Следующий: ${nextLabel(alarm.scheduledAt)}", 13f, true))
            card.addView(Ui.button(this, "Изменить") { edit(alarm) })
            root.addView(card)
        }
        root.addView(Ui.button(this, "＋ Добавить будильник", true) {
            val id = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }.first { candidate -> alarms.none { it.id == candidate } }
            edit(Alarm(id = id, dismissMode = if (station == null) DismissMode.BUTTON else DismissMode.QR))
        })
        root.addView(Ui.text(this, "Проверь пробный звонок и отдельный будильник через пару минут с заблокированным экраном. После принудительной остановки приложения открой его снова.", 13f, true))
    }

    private fun canArm(mode: DismissMode): Boolean {
        if (!ready()) { toast("Сначала настрой разрешения на главном экране"); return false }
        if (mode == DismissMode.QR && StationStore(this).get() == null) { toast("Сначала свяжи телефон с браузером"); return false }
        return true
    }

    private fun edit(original: Alarm) {
        editor = true
        draftSound = original.sound
        val root = Ui.root(this)
        root.addView(Ui.title(this, "Время подъёма"))
        val time = TimePicker(this).apply { setIs24HourView(true); hour = original.hour; minute = original.minute }
        root.addView(time)
        root.addView(Ui.text(this, "Дни недели · без выбора — один раз", 15f))
        val dayChecks = Ui.days.mapIndexed { index, label ->
            CheckBox(this).apply { text = label; isChecked = original.days and (1 shl index) != 0 }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Two rows remain usable on narrow screens and with large system text.
        dayChecks.take(4).forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dayChecks.drop(4).forEach { row2.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(row2)

        root.addView(Ui.text(this, "Звук", 18f))
        soundLabel = Ui.text(this, soundName(draftSound), 14f, true)
        root.addView(soundLabel)
        root.addView(Ui.button(this, "Выбрать мелодию") {
            ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, draftSound.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
        })
        val volumeText = Ui.text(this, "Громкость · ${original.volume}%", 18f)
        root.addView(volumeText)
        val volume = SeekBar(this).apply {
            min = 20; max = 100; progress = original.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { volumeText.text = "Громкость · $value%" }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        root.addView(volume)
        root.addView(Ui.text(this, "Настойчивость", 18f))
        val persistence = radio(root, listOf("Обычная · постоянная громкость", "Усиленная · нарастание, вибрация, возврат громкости"),
            if (original.persistence == Persistence.STRONG) 1 else 0)
        root.addView(Ui.text(this, "Как отключить", 18f))
        val dismiss = radio(root, listOf("Кнопка «Я встал»", "Свежий QR с ноутбука"), if (original.dismissMode == DismissMode.QR) 1 else 0)
        root.addView(Ui.text(this, "В QR-режиме нет кнопки остановки и отсрочки. Перед сном проверь доступ к связанному браузеру. Принудительная остановка и выключение телефона остаются доступны в Android.", 14f, true))

        fun draft() = original.copy(hour = time.hour, minute = time.minute,
            days = dayChecks.withIndex().fold(0) { mask, item -> if (item.value.isChecked) mask or (1 shl item.index) else mask },
            sound = draftSound, volume = volume.progress,
            persistence = if (persistence.checkedRadioButtonId == persistence.getChildAt(1).id) Persistence.STRONG else Persistence.NORMAL,
            dismissMode = if (dismiss.checkedRadioButtonId == dismiss.getChildAt(1).id) DismissMode.QR else DismissMode.BUTTON)

        root.addView(Ui.button(this, "Сохранить и включить", true) {
            val alarm = draft().copy(enabled = true)
            if (!canArm(alarm.dismissMode)) return@button
            if (AlarmScheduler.schedule(this, alarm)) render() else toast("Не удалось запланировать будильник")
        })
        root.addView(Ui.button(this, "Пробный звонок") {
            if (!ready()) { toast("Сначала настрой разрешения на главном экране"); return@button }
            if (AlarmStore(this).sessions().isNotEmpty()) { toast("Уже есть активный звонок"); return@button }
            AlarmStore(this).enqueue(draft(), test = true)
            RingService.start(this)
            startActivity(Intent(this, RingActivity::class.java))
        })
        if (AlarmStore(this).alarms().any { it.id == original.id }) root.addView(Ui.button(this, "Удалить будильник") {
            AlertDialog.Builder(this).setTitle("Удалить ${Ui.time(original)}?")
                .setMessage("Уже начавшийся звонок продолжится до отключения.")
                .setPositiveButton("Удалить") { _, _ -> AlarmScheduler.cancel(this, original.id); AlarmStore(this).delete(original.id); render() }
                .setNegativeButton("Отмена", null).show()
        })
        root.addView(Ui.button(this, "Назад без сохранения") { render() })
    }

    private fun radio(root: LinearLayout, labels: List<String>, selected: Int): RadioGroup {
        val group = RadioGroup(this)
        labels.forEach { label -> group.addView(RadioButton(this).apply { id = View.generateViewId(); text = label; textSize = 15f }) }
        group.check(group.getChildAt(selected).id)
        root.addView(group)
        return group
    }

    private fun soundName(value: String): String = runCatching {
        RingtoneManager.getRingtone(this, if (value.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(value))
            ?.getTitle(this) ?: "Встроенный сигнал"
    }.getOrDefault("Встроенный сигнал")

    private fun nextLabel(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM · HH:mm"))

    private fun openSetting(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
