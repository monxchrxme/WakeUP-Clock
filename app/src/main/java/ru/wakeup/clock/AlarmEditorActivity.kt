package ru.wakeup.clock

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject

/** iPhone-style interaction: time wheels and compact grouped rows, with a persistent navigation bar. */
class AlarmEditorActivity : ComponentActivity() {
    private lateinit var draft: Alarm
    private lateinit var initial: Alarm
    private var isExisting = false
    private var scrollY = 0
    private lateinit var content: LinearLayout
    private var scroller: ScrollView? = null
    private lateinit var soundImport: SoundImportModel
    private var importDialog: AlertDialog? = null

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri == null || RingtoneManager.isDefault(uri)) selectSound(SoundChoice("", ""))
            else soundImport.import(this, uri)
        }
    }
    private val audioFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) soundImport.import(this, uri)
    }
    private val randomAudioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) soundImport.importRandom(this, uris, draft.randomSounds)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soundImport = ViewModelProvider(this)[SoundImportModel::class.java]
        val id = intent.getIntExtra("id", -1)
        val saved = AlarmStore(this).alarms().find { it.id == id }
        isExisting = saved != null
        initial = saved ?: SoundDefaults(this).newAlarm(id)
        draft = savedInstanceState?.getString("draft")?.let { AlarmJson.decode(JSONObject(it)) } ?: initial
        scrollY = savedInstanceState?.getInt("scrollY") ?: 0
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })
        render()
    }

    override fun onStart() {
        super.onStart()
        soundImport.observe { state ->
            if (state == SoundImportModel.State.Loading) {
                if (importDialog == null) importDialog = AlertDialog.Builder(this)
                    .setTitle("Подготовка музыки").setMessage("Сохраняю и проверяю аудиофайлы…")
                    .setView(ProgressBar(this).apply { setPadding(dp(24), dp(12), dp(24), dp(24)) })
                    .setCancelable(false).show()
            } else {
                importDialog?.dismiss(); importDialog = null
                when (state) {
                    is SoundImportModel.State.Ready -> { soundImport.consume(); selectSound(state.choice) }
                    is SoundImportModel.State.RandomReady -> { soundImport.consume(); selectRandomSounds(state.choices) }
                    is SoundImportModel.State.Failed -> {
                        soundImport.consume()
                        AlertDialog.Builder(this).setTitle("Музыка не выбрана").setMessage(state.message)
                            .setPositiveButton("Понятно", null).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStop() {
        soundImport.detach()
        importDialog?.dismiss(); importDialog = null
        super.onStop()
    }

    private fun selectSound(choice: SoundChoice) {
        draft = draft.copy(sound = choice.uri, soundTitle = choice.title)
        SoundDefaults(this).remember(choice)
        render()
    }

    private fun selectRandomSounds(choices: List<SoundChoice>) {
        draft = draft.copy(randomSounds = choices)
        SoundDefaults(this).rememberRandom(choices)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", AlarmJson.encode(draft).toString())
        outState.putInt("scrollY", scroller?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        scrollY = scroller?.scrollY ?: scrollY
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(58)
        }
        navigation.addView(navButton("Отмена") { cancel() })
        navigation.addView(TextView(this).apply {
            text = if (isExisting) "Будильник" else "Новый будильник"
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Ui.INK)
            setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(navButton("Готово") { save() }.apply { setTypeface(null, Typeface.BOLD) })
        root.addView(navigation)
        scroller = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(24)) }
        scroller!!.addView(content)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val wheels = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun wheel(max: Int, value: Int, description: String, changed: (Int) -> Unit) = NumberPicker(this).apply {
            minValue = 0; maxValue = max; wrapSelectorWheel = true
            setFormatter { "%02d".format(it) }; this.value = value
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            contentDescription = description
            if (Build.VERSION.SDK_INT >= 29) textSize = 38f * resources.displayMetrics.scaledDensity
            else for (i in 0 until childCount) (getChildAt(i) as? EditText)?.apply { textSize = 38f; setTextColor(Ui.INK) }
            setOnValueChangedListener { _, _, number -> changed(number) }
        }
        wheels.addView(wheel(23, draft.hour, "Часы") { draft = draft.copy(hour = it) }, LinearLayout.LayoutParams(dp(110), dp(190)))
        wheels.addView(TextView(this).apply { text = ":"; textSize = 34f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(28), -2))
        wheels.addView(wheel(59, draft.minute, "Минуты") { draft = draft.copy(minute = it) }, LinearLayout.LayoutParams(dp(110), dp(190)))
        content.addView(wheels)

        group {
            row("Повтор", Ui.daysLabel(draft.days)) { chooseDays() }
            row("Название", draft.label.ifBlank { "Будильник" }) { chooseLabel() }
            toggle("Случайная мелодия", draft.randomSound) {
                draft = draft.copy(randomSound = it)
                if (it && draft.randomSounds.isEmpty()) draft = draft.copy(randomSounds = SoundDefaults(this@AlarmEditorActivity).randomChoices())
                render()
            }
            if (draft.randomSound) row("Песни", if (draft.randomSounds.isEmpty()) "Выбрать файлы" else "Выбрано: ${draft.randomSounds.size}") { chooseRandomSounds() }
            else row("Мелодия", soundName()) { chooseSound() }
        }
        footer(if (draft.randomSound) "От 2 до ${RandomSounds.MAX_SONGS} песен. При каждом звонке выбирается новая, без повтора предыдущей. Она играет по кругу до отключения. Обычная мелодия и этот набор запоминаются отдельно."
            else "Мелодия повторяется по кругу до отключения будильника.")
        group {
            row("Отключение", if (draft.dismissMode == DismissMode.QR) "QR с ноутбука" else "Кнопка") {
                choice("Отключение", listOf("Кнопка «Я встал»", "Свежий QR с ноутбука"), if (draft.dismissMode == DismissMode.QR) 1 else 0) {
                    draft = draft.copy(dismissMode = if (it == 1) DismissMode.QR else DismissMode.BUTTON); render()
                }
            }
            row("Настойчивость", if (draft.persistence == Persistence.STRONG) "Усиленная" else "Обычная") {
                choice("Настойчивость", listOf("Обычная — громкость можно изменить", "Усиленная — возвращать выбранную громкость"), if (draft.persistence == Persistence.STRONG) 1 else 0) {
                    draft = draft.copy(persistence = if (it == 1) Persistence.STRONG else Persistence.NORMAL); render()
                }
            }
            toggle("Вибрация", draft.vibrate) { draft = draft.copy(vibrate = it); render() }
            row("Нарастание звука", if (draft.fadeInSeconds == 0) "Нет" else "${draft.fadeInSeconds} сек") {
                val values = listOf(0, 10, 30, 60)
                choice("Нарастание звука", values.map { if (it == 0) "Без нарастания" else "$it секунд" }, values.indexOf(draft.fadeInSeconds)) {
                    draft = draft.copy(fadeInSeconds = values[it]); render()
                }
            }
            separator()
            val volumeLabel = TextView(this@AlarmEditorActivity).apply { text = "${draft.volume}%"; textSize = 16f; setTextColor(Ui.MUTED) }
            addView(LinearLayout(this@AlarmEditorActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(15), dp(16), 0)
                addView(TextView(this@AlarmEditorActivity).apply { text = "Громкость"; textSize = 16f; setTextColor(Ui.INK) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(volumeLabel)
            })
            addView(SeekBar(this@AlarmEditorActivity).apply {
                min = 20; max = 100; progress = draft.volume
                setPadding(dp(16), dp(10), dp(16), dp(18))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        draft = draft.copy(volume = progress); volumeLabel.text = "$progress%"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(-1, dp(58)))
        }
        section("РАЗДРАЖЕНИЕ")
        group {
            toggle("Раздражение", draft.irritationMinutes > 0) {
                draft = draft.copy(irritationMinutes = if (it) 1 else 0); render()
            }
            if (draft.irritationMinutes > 0) row("После начала звонка", "Через ${draft.irritationMinutes} мин") { chooseIrritationDelay() }
        }
        footer(if (draft.irritationMinutes > 0)
            "Через ${draft.irritationMinutes} мин поверх песни начнёт играть второй звук. Для одной песни — её копия со случайного места; для набора — другая песня. На проверке подъёма — через ${draft.irritationMinutes + 5} мин. Отсрочка и отключение останавливают оба звука."
            else "Добавляет второй звук поверх основной песни, чтобы было сложнее остаться в кровати. Задержка настраивается с шагом в одну минуту.")
        section("АВТОПОВТОР")
        group {
            toggle("Автоповтор", draft.autoRepeatMinutes > 0) {
                draft = draft.copy(autoRepeatMinutes = if (it) 5 else 0); render()
            }
            if (draft.autoRepeatMinutes > 0) {
                row("Длительность звонка", "${draft.ringLimitMinutes} мин") {
                    chooseMinuteValue("Длительность звонка", draft.ringLimitMinutes, 1, "Длительность звонка в минутах") {
                        draft = draft.copy(ringLimitMinutes = it); render()
                    }
                }
                row("Пауза перед повтором", "${draft.autoRepeatMinutes} мин") {
                    chooseMinuteValue("Пауза перед повтором", draft.autoRepeatMinutes, 5, "Пауза автоповтора в минутах") {
                        draft = draft.copy(autoRepeatMinutes = it); render()
                    }
                }
            }
        }
        footer("Если не подтвердить подъём, звук сам остановится после выбранного времени и включится снова после паузы. Повторы продолжаются до отключения по QR или кнопкой. Проверка подъёма назначается только после такого подтверждения.")
        section("ПОВТОРНЫЙ ЗВОНОК")
        group {
            toggle("Отложить", draft.snoozeMinutes > 0) { draft = draft.copy(snoozeMinutes = if (it) 10 else 0); render() }
            if (draft.snoozeMinutes > 0) {
                row("Интервал отсрочки", "${draft.snoozeMinutes} мин") { chooseMinutes("Интервал отсрочки", draft.snoozeMinutes) { draft = draft.copy(snoozeMinutes = it); render() } }
                row("Число отсрочек", draft.maxSnoozes.toString()) {
                    choice("Число отсрочек", listOf("1 раз", "2 раза", "3 раза"), draft.maxSnoozes - 1) { draft = draft.copy(maxSnoozes = it + 1); render() }
                }
            }
            toggle("Проверка подъёма", draft.wakeCheckMinutes > 0) { draft = draft.copy(wakeCheckMinutes = if (it) 10 else 0); render() }
            if (draft.wakeCheckMinutes > 0) row("После отключения", "Через ${draft.wakeCheckMinutes} мин") {
                chooseMinutes("Проверка подъёма", draft.wakeCheckMinutes) { draft = draft.copy(wakeCheckMinutes = it); render() }
            }
        }
        footer("«Отложить» временно выключает звук. Проверка подъёма звонит ещё один раз после отключения. В QR-режиме для окончательного отключения снова потребуется свежий QR.")
        content.addView(Ui.button(this, "Проверить звук и отключение") { test() })
        if (isExisting) content.addView(Ui.button(this, "Удалить будильник") {
            AlertDialog.Builder(this).setTitle("Удалить ${Ui.time(draft)}?")
                .setMessage("Уже начавшийся или отложенный звонок продолжится по своим настройкам.")
                .setPositiveButton("Удалить") { _, _ -> AlarmScheduler.cancel(this, draft.id); AlarmStore(this).delete(draft.id); finish() }
                .setNegativeButton("Отмена", null).show()
        }.apply { setTextColor(Ui.RED) })
        root.requestApplyInsets()
        scroller!!.post { scroller?.scrollTo(0, scrollY) }
    }

    private fun group(build: LinearLayout.() -> Unit) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Ui.SURFACE) }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        view.build(); content.addView(view)
    }

    private fun LinearLayout.separator() {
        if (childCount > 0) addView(View(this@AlarmEditorActivity).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) })
    }

    private fun LinearLayout.row(label: String, value: String, action: () -> Unit) {
        separator()
        val row = LinearLayout(this@AlarmEditorActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(55); setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true; isFocusable = true
            contentDescription = "$label: $value"
            setOnClickListener { action() }
        }
        row.addView(TextView(this@AlarmEditorActivity).apply { text = label; textSize = 16f; setTextColor(Ui.INK) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this@AlarmEditorActivity).apply {
            text = value; textSize = 15f; setTextColor(Ui.MUTED); gravity = Gravity.END
            maxLines = 1; maxWidth = dp(145); ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(TextView(this@AlarmEditorActivity).apply { text = "  ›"; textSize = 25f; setTextColor(Ui.MUTED) })
        addView(row)
    }

    private fun LinearLayout.toggle(label: String, checked: Boolean, action: (Boolean) -> Unit) {
        separator()
        addView(Switch(this@AlarmEditorActivity).apply {
            text = label; textSize = 16f; setTextColor(Ui.INK); isChecked = checked
            minimumHeight = dp(55); setPadding(dp(16), dp(8), dp(16), dp(8)); switchPadding = dp(14)
            setOnCheckedChangeListener { _, enabled -> action(enabled) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun section(label: String) { content.addView(Ui.text(this, label, 12f, true).apply { setPadding(dp(16), dp(22), 0, 0) }) }
    private fun footer(label: String) { content.addView(Ui.text(this, label, 13f, true).apply { setPadding(dp(16), dp(9), dp(16), 0) }) }
    private fun navButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(Ui.ORANGE); gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14)); isFocusable = true; setOnClickListener { action() }
    }

    private fun choice(title: String, values: List<String>, selected: Int, action: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(), selected) { dialog, which ->
            dialog.dismiss(); action(which)
        }.setNegativeButton("Отмена", null).show()
    }

    private fun chooseDays() {
        val values = arrayOf("Каждый понедельник", "Каждый вторник", "Каждую среду", "Каждый четверг", "Каждую пятницу", "Каждую субботу", "Каждое воскресенье")
        val checked = BooleanArray(7) { draft.days and (1 shl it) != 0 }
        AlertDialog.Builder(this).setTitle("Повтор").setMultiChoiceItems(values, checked) { _, which, enabled -> checked[which] = enabled }
            .setNeutralButton("Будни") { _, _ -> draft = draft.copy(days = 31); render() }
            .setPositiveButton("Готово") { _, _ -> draft = draft.copy(days = checked.indices.fold(0) { mask, i -> if (checked[i]) mask or (1 shl i) else mask }); render() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun chooseLabel() {
        val input = EditText(this).apply { setText(draft.label); hint = "Будильник"; isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(60)) }
        AlertDialog.Builder(this).setTitle("Название").setView(input).setPositiveButton("Готово") { _, _ -> draft = draft.copy(label = input.text.toString().trim()); render() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun chooseSound() {
        AlertDialog.Builder(this).setTitle("Мелодия").setItems(arrayOf("Системные мелодии", "Аудиофайл с телефона", "Встроенный сигнал")) { _, which ->
            when (which) {
                0 -> ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, draft.sound.takeIf { it.isNotBlank() }?.let(Uri::parse)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
                1 -> audioFilePicker.launch(arrayOf("audio/*"))
                else -> selectSound(SoundChoice("android.resource://$packageName/${R.raw.fallback_alarm}", "Встроенный сигнал"))
            }
        }.show()
    }

    private fun chooseRandomSounds() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this).setTitle("Песни для случайного звонка")
            .setView(column).setPositiveButton("Готово", null).create()
        column.addView(Ui.button(this, "Добавить песни", true) {
            dialog.dismiss(); randomAudioPicker.launch(arrayOf("audio/*"))
        })
        column.addView(Ui.text(this, "В файлах можно выбрать одну или несколько песен за раз. Для случайного звонка нужны хотя бы две.", 14f, true))
        column.addView(scroll)
        val clear = Ui.button(this, "Очистить список") {}
        column.addView(clear)
        fun updateList() {
            list.removeAllViews()
            scroll.layoutParams = LinearLayout.LayoutParams(-1, dp(minOf(280, maxOf(1, draft.randomSounds.size) * 56)))
            clear.isEnabled = draft.randomSounds.isNotEmpty()
            clear.alpha = if (clear.isEnabled) 1f else 0.45f
            if (draft.randomSounds.isEmpty()) list.addView(Ui.text(this, "Список пуст", 16f, true))
            draft.randomSounds.forEach { song ->
                list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.text(this@AlarmEditorActivity, song.title, 15f).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, dp(56), 1f))
                    addView(navButton("×") {
                        selectRandomSounds(draft.randomSounds.filterNot { it.uri == song.uri }); updateList()
                    }.apply { contentDescription = "Удалить песню ${song.title}"; minimumWidth = dp(48); minimumHeight = dp(48) })
                })
            }
        }
        clear.setOnClickListener { selectRandomSounds(emptyList()); updateList() }
        updateList()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Ui.SURFACE) })
    }

    private fun chooseMinutes(title: String, selected: Int, action: (Int) -> Unit) {
        val values = listOf(5, 10, 15, 20, 30)
        choice(title, values.map { "$it минут" }, values.indexOf(selected)) { action(values[it]) }
    }

    private fun chooseIrritationDelay() {
        chooseMinuteValue("Через сколько минут?", draft.irritationMinutes, 1, "Задержка раздражения в минутах") {
            draft = draft.copy(irritationMinutes = it); render()
        }
    }

    private fun chooseMinuteValue(title: String, selected: Int, step: Int, description: String, action: (Int) -> Unit) {
        val picker = NumberPicker(this).apply {
            minValue = 1; maxValue = 60 / step; value = (selected / step).coerceIn(1, maxValue)
            if (step == 1) setFormatter { it.toString() }
            else displayedValues = (1..maxValue).map { (it * step).toString() }.toTypedArray()
            wrapSelectorWheel = false
            contentDescription = description
        }
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(picker) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Готово") { _, _ ->
                picker.clearFocus(); action(picker.value * step)
            }.setNegativeButton("Отмена", null).show()
    }

    private fun soundName(): String = draft.soundTitle.ifBlank {
        runCatching { RingtoneManager.getRingtone(this, if (draft.sound.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(draft.sound))?.getTitle(this) }
            .getOrNull() ?: "Встроенный сигнал"
    }

    private fun permissionsReady(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return AlarmScheduler.permitted(this) && manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(AlarmNotifications.CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
    }

    private fun save() {
        if (soundImport.state == SoundImportModel.State.Loading) { toast("Дождись подготовки мелодии"); return }
        if (!validRandomSounds()) return
        if (!permissionsReady()) { toast("Сначала настрой разрешения на главном экране"); return }
        if (draft.dismissMode == DismissMode.QR && StationStore(this).get() == null) { toast("Сначала свяжи телефон с браузером"); return }
        if (AlarmScheduler.schedule(this, draft.copy(enabled = true))) finish() else toast("Не удалось запланировать будильник")
    }

    private fun test() {
        if (soundImport.state == SoundImportModel.State.Loading) { toast("Дождись подготовки мелодии"); return }
        if (!validRandomSounds()) return
        if (!permissionsReady()) { toast("Сначала настрой разрешения на главном экране"); return }
        if (AlarmStore(this).sessions().isNotEmpty()) { toast("Уже есть активный или отложенный звонок"); return }
        AlarmStore(this).enqueue(draft, test = true)
        RingService.start(this)
        startActivity(Intent(this, RingActivity::class.java))
    }

    private fun cancel() {
        if (soundImport.state == SoundImportModel.State.Loading) { toast("Дождись подготовки мелодии"); return }
        if (draft == initial) finish()
        else AlertDialog.Builder(this).setTitle("Отменить изменения?").setPositiveButton("Не сохранять") { _, _ -> finish() }
            .setNegativeButton("Продолжить", null).show()
    }
    private fun validRandomSounds(): Boolean {
        if (draft.randomSound && draft.randomSounds.size < 2) { toast("Выбери хотя бы две песни для случайного режима"); return false }
        return true
    }
    private fun dp(value: Int) = Ui.dp(this, value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
