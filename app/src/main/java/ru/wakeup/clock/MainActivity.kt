package ru.wakeup.clock

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var permissionsExpanded: Boolean? = null
    private var lastReady: Boolean? = null
    private var selecting = false
    private val selectedIds = mutableSetOf<Int>()
    private var selectionMode: ActionMode? = null
    private var alarmAdapter: AlarmListAdapter? = null
    private var emptyLabel: TextView? = null
    private var selectButton: TextView? = null
    private var undoBar: LinearLayout? = null
    private var undoLabel: TextView? = null
    private var deletedAlarms: List<Alarm> = emptyList()
    private var undoExpiresAt = 0L
    private var cancelCheckToken: String? = null
    private var orphanChecks: LinearLayout? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private val hideUndo = Runnable { refreshUndoBar() }
    private val refreshChecks = Runnable { render() }
    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { render() }
    }
    private val normalOnlyViews = mutableListOf<View>()
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { selectionMode?.finish() }
    }
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, 1, 0, "Выбрать все").setIcon(R.drawable.ic_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 1, "Отключить выбранные").setIcon(R.drawable.ic_alarm_off).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 3, 2, "Удалить выбранные").setIcon(R.drawable.ic_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = "Выбрано: ${selectedIds.size}"
            menu.findItem(1).title = if (selectedIds.size == alarmAdapter?.alarms?.size) "Снять выделение" else "Выбрать все"
            menu.findItem(2).isEnabled = selectedIds.isNotEmpty()
            menu.findItem(3).isEnabled = selectedIds.isNotEmpty()
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                1 -> {
                    val ids = alarmAdapter?.alarms.orEmpty().map { it.id }.toSet()
                    if (selectedIds.containsAll(ids)) selectedIds.clear() else selectedIds.addAll(ids)
                    refreshSelection()
                }
                2 -> {
                    val count = AlarmBatchActions.disable(this@MainActivity, selectedIds.toSet()).size
                    mode.finish(); refreshAlarms(); toast("Отключено будильников: $count")
                }
                3 -> deleteAlarms(selectedIds.toSet())
                else -> return false
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = null; selecting = false; selectedIds.clear(); selectionBack.isEnabled = false
            selectButton?.text = "Выбрать"
            updateSelectionChrome()
            alarmAdapter?.let { it.update(it.alarms, false, emptySet()) }
        }
    }
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
    private val cancelCheckScanner = registerForActivityResult(ScanContract()) { result ->
        val token = cancelCheckToken
        cancelCheckToken = null
        if (token != null && result.contents != null) finishCancelCheck(token, result.contents)
        else render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("permissionsExpanded") == true)
            permissionsExpanded = savedInstanceState.getBoolean("permissionsExpanded")
        selecting = savedInstanceState?.getBoolean("selecting") ?: false
        savedInstanceState?.getIntArray("selectedIds")?.let { selectedIds.addAll(it.toList()) }
        deletedAlarms = savedInstanceState?.getStringArrayList("deletedAlarms")?.map { AlarmJson.decode(JSONObject(it)) }.orEmpty()
        undoExpiresAt = savedInstanceState?.getLong("undoExpiresAt") ?: 0L
        cancelCheckToken = savedInstanceState?.getString("cancelCheckToken")
        onBackPressedDispatcher.addCallback(this, selectionBack)
        AlarmNotifications.channel(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        permissionsExpanded?.let { outState.putBoolean("permissionsExpanded", it) }
        outState.putBoolean("selecting", selecting)
        outState.putIntArray("selectedIds", selectedIds.toIntArray())
        outState.putStringArrayList("deletedAlarms", ArrayList(deletedAlarms.map { AlarmJson.encode(it).toString() }))
        outState.putLong("undoExpiresAt", undoExpiresAt)
        outState.putString("cancelCheckToken", cancelCheckToken)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, changed, IntentFilter(RingService.STATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.restore(this, recompute = false)
        if (lastReady == false && ready()) permissionsExpanded = false
        lastReady = ready()
        render()
    }

    override fun onStop() {
        undoHandler.removeCallbacks(hideUndo)
        undoHandler.removeCallbacks(refreshChecks)
        unregisterReceiver(changed)
        super.onStop()
    }

    override fun onDestroy() {
        undoHandler.removeCallbacks(hideUndo)
        undoHandler.removeCallbacks(refreshChecks)
        super.onDestroy()
    }

    private fun ready(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return AlarmScheduler.permitted(this) && manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(AlarmNotifications.CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
    }

    private fun render() {
        val store = AlarmStore(this)
        val root = Ui.root(this, makeUndoBar())
        normalOnlyViews.clear()
        root.addView(Ui.title(this, "WakeUP Clock").also { normalOnlyViews.add(it) })
        root.addView(Ui.text(this, "Утро начинается с одного шага.", 16f, true).also { normalOnlyViews.add(it) })
        if (store.activeSession() != null) root.addView(Ui.button(this, "Вернуться к звонку", true) { startActivity(Intent(this, RingActivity::class.java)) })
        store.sessions().filter { !it.isWakeCheck && it.dueAt > System.currentTimeMillis() }.sortedBy { it.dueAt }.forEach {
            root.addView(Ui.text(this, "Отложенный звонок · ${nextLabel(it.dueAt)}", 15f))
        }
        val complete = ready()
        val expanded = permissionsExpanded ?: !complete
        root.addView(Ui.button(this, "${if (complete) "● Всё готово к звонку" else "Нужны разрешения"}  ${if (expanded) "⌃" else "⌄"}") {
            permissionsExpanded = !expanded; render()
        }.also { normalOnlyViews.add(it) })
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
            normalOnlyViews.add(permissions)
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(this, "Будильники", 24f), LinearLayout.LayoutParams(0, -2, 1f))
        selectButton = TextView(this).apply {
            text = if (selecting) "Готово" else "Выбрать"; textSize = 16f; setTextColor(Ui.ORANGE)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14))
            isFocusable = true
            setOnClickListener { if (selecting) selectionMode?.finish() else beginSelection() }
        }
        heading.addView(selectButton)
        root.addView(heading)
        normalOnlyViews.add(heading)
        val alarms = store.alarms().sortedWith(compareBy({ it.hour }, { it.minute }))
        emptyLabel = Ui.text(this, "Добавь время первого подъёма.", 16f, true)
        root.addView(emptyLabel)
        alarmAdapter = AlarmListAdapter(this,
            click = { if (selecting) { if (!selectedIds.add(it.id)) selectedIds.remove(it.id); refreshSelection() } else edit(it) },
            longClick = { beginSelection(it.id) },
            toggle = { alarm, enabled ->
                if (!enabled || canArm(alarm.dismissMode)) {
                    if (!AlarmScheduler.schedule(this, alarm.copy(enabled = enabled))) toast("Нет разрешения на точный будильник")
                }
                refreshAlarms()
            }, cancelCheck = { requestCancelCheck(it) })
        val list = RecyclerView(this).apply {
            id = android.R.id.list; layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alarmAdapter; isNestedScrollingEnabled = false; itemAnimator = null
        }
        alarmAdapter!!.update(alarms, selecting, selectedIds)
        alarmAdapter!!.attachSwipe(list) { deleteAlarms(setOf(it.id)) }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        orphanChecks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(orphanChecks)
        normalOnlyViews.add(orphanChecks!!)
        refreshAlarms()
        root.addView(Ui.button(this, "＋ Добавить будильник", true) {
            val existingIds = store.alarms().map { it.id }.toSet()
            val id = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }.first { it !in existingIds }
            selectionMode?.finish(); edit(Alarm(id = id))
        }.also { normalOnlyViews.add(it) })
        val station = StationStore(this).get()
        val pairing = Ui.card(this)
        pairing.addView(Ui.text(this, station?.let { "Ноутбук · браузер ${it.id.takeLast(6)}" } ?: "Свяжи ноутбук для отключения QR-кодом", 16f))
        pairing.addView(Ui.button(this, if (station == null) "Связать с браузером" else "Изменить привязку браузера") {
            if (store.sessions().isNotEmpty()) toast("Во время звонка или ожидания повтора менять привязку нельзя")
            else pairScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR привязки с сайта на ноутбуке").setBeepEnabled(false).setOrientationLocked(false))
        })
        root.addView(pairing)
        normalOnlyViews.add(pairing)
        if (selecting && selectionMode == null && alarms.isNotEmpty()) beginSelection()
        updateSelectionChrome()
    }

    private fun beginSelection(id: Int? = null) {
        if (id != null) selectedIds.add(id)
        selecting = true
        if (selectionMode == null) selectionMode = startActionMode(selectionCallback)
        if (selectionMode == null) { selecting = false; selectedIds.clear() }
        selectionBack.isEnabled = selecting
        selectButton?.text = if (selecting) "Готово" else "Выбрать"
        updateSelectionChrome()
        refreshSelection()
    }

    private fun refreshSelection() {
        alarmAdapter?.let { it.update(it.alarms, selecting, selectedIds) }
        selectionMode?.invalidate()
    }

    private fun updateSelectionChrome() {
        normalOnlyViews.forEach { it.visibility = if (selecting) View.GONE else View.VISIBLE }
    }

    private fun refreshAlarms() {
        val store = AlarmStore(this)
        val alarms = store.alarms().sortedWith(compareBy({ it.hour }, { it.minute }))
        val checks = store.sessions().filter { it.isWakeCheck && it.dueAt > System.currentTimeMillis() }.sortedBy { it.dueAt }
        selectedIds.retainAll(alarms.map { it.id }.toSet())
        if (alarms.isEmpty()) { selectionMode?.finish(); selecting = false }
        alarmAdapter?.update(alarms, selecting, selectedIds, checks)
        orphanChecks?.removeAllViews()
        checks.filter { check -> alarms.none { it.id == check.alarm.id } }.groupBy { it.alarm.id }.forEach { (_, sessions) ->
            orphanChecks?.addView(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, Ui.time(sessions.first().alarm), 40f))
                addView(Ui.text(this@MainActivity, sessions.first().alarm.label.ifBlank { "Будильник" }, 15f, true))
                sessions.forEach { addView(WakeCheckUi.row(this@MainActivity, it) { session -> requestCancelCheck(session) }) }
            })
        }
        emptyLabel?.visibility = if (alarms.isEmpty() && checks.isEmpty()) View.VISIBLE else View.GONE
        selectButton?.visibility = if (alarms.isEmpty()) View.GONE else View.VISIBLE
        selectionMode?.invalidate()
        scheduleCheckRefresh(checks)
    }

    private fun scheduleCheckRefresh(checks: List<RingSession>) {
        undoHandler.removeCallbacks(refreshChecks)
        val now = System.currentTimeMillis()
        val next = checks.flatMap { listOfNotNull(FollowUpRules.earlyCancelAt(it), it.dueAt) }.filter { it > now }.minOrNull()
        if (next != null) undoHandler.postDelayed(refreshChecks, next - now)
    }

    private fun requestCancelCheck(session: RingSession) {
        val saved = AlarmStore(this).sessions().firstOrNull { it.token == session.token }
        if (saved == null || !FollowUpRules.canCancelEarly(saved, System.currentTimeMillis())) {
            toast("Сейчас эту проверку нельзя отменить заранее"); render(); return
        }
        if (saved.alarm.dismissMode == DismissMode.QR) {
            if (StationStore(this).get() == null) { toast("Для отмены нужен QR связанного браузера"); return }
            cancelCheckToken = saved.token
            cancelCheckScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Свежий QR с ноутбука для отмены проверки").setBeepEnabled(false).setOrientationLocked(false))
        } else finishCancelCheck(saved.token)
    }

    private fun finishCancelCheck(token: String, qr: String? = null) {
        if (RingActions.cancelWakeCheck(this, token, qr)) toast("Проверка отменена. Будильник по расписанию сохранён")
        else {
            val session = AlarmStore(this).sessions().firstOrNull { it.token == token }
            if (session != null && session.dueAt <= System.currentTimeMillis()) {
                toast("Проверка уже началась. Отключи её на экране звонка")
                startActivity(Intent(this, RingActivity::class.java))
            } else toast("Не удалось отменить проверку. Нужен свежий QR связанного браузера и доступное время отмены")
        }
        render()
    }

    private fun deleteAlarms(ids: Set<Int>) {
        val deleted = AlarmBatchActions.delete(this, ids)
        if (deleted.isNotEmpty()) {
            deletedAlarms = deleted
            val timeout = if (Build.VERSION.SDK_INT >= 29)
                getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(5_000,
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
            else 5_000
            undoExpiresAt = SystemClock.elapsedRealtime() + timeout
        }
        selectionMode?.finish()
        refreshAlarms()
        refreshUndoBar()
    }

    private fun makeUndoBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.SURFACE)
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 6))
        }
        undoLabel = Ui.text(this, "", 15f)
        bar.addView(undoLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "Отменить"; textSize = 16f; setTextColor(Ui.ORANGE); isFocusable = true
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 14))
            setOnClickListener {
                val unavailable = AlarmBatchActions.restore(this@MainActivity, deletedAlarms)
                deletedAlarms = emptyList(); undoExpiresAt = 0L; refreshAlarms(); refreshUndoBar()
                if (unavailable > 0) toast("Часть будильников восстановлена выключенной. Разреши точные звонки.")
            }
        })
        undoBar = bar; refreshUndoBar()
        return bar
    }

    private fun refreshUndoBar() {
        undoHandler.removeCallbacks(hideUndo)
        val remaining = undoExpiresAt - SystemClock.elapsedRealtime()
        if (remaining <= 0L) { deletedAlarms = emptyList(); undoExpiresAt = 0L }
        undoBar?.visibility = if (deletedAlarms.isEmpty()) View.GONE else View.VISIBLE
        undoLabel?.text = if (deletedAlarms.size == 1) "Удалён будильник ${Ui.time(deletedAlarms.first())}" else "Удалено будильников: ${deletedAlarms.size}"
        if (deletedAlarms.isNotEmpty()) undoHandler.postDelayed(hideUndo, remaining)
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
