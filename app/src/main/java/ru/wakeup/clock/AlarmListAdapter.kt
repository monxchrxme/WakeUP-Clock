package ru.wakeup.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class AlarmListAdapter(
    private val activity: android.app.Activity,
    private val click: (Alarm) -> Unit,
    private val longClick: (Alarm) -> Unit,
    private val toggle: (Alarm, Boolean) -> Unit,
) : RecyclerView.Adapter<AlarmListAdapter.Holder>() {
    var alarms: List<Alarm> = emptyList()
        private set
    var selecting = false
        private set
    private var selected: Set<Int> = emptySet()

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = alarms[position].id.toLong()
    override fun getItemCount() = alarms.size

    fun update(alarms: List<Alarm>, selecting: Boolean, selected: Set<Int>) {
        this.alarms = alarms; this.selecting = selecting; this.selected = selected.toSet()
        notifyDataSetChanged()
    }

    class Holder(val card: LinearLayout, val check: CheckBox, val time: TextView, val detail: TextView,
                 val mode: TextView, val next: TextView, val enabled: Switch) : RecyclerView.ViewHolder(card) {
        var alarm: Alarm? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = Ui.card(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 12) }
        }
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                        moved = true; view.isPressed = false
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> moved.also { if (it) view.isPressed = false; moved = false }
                MotionEvent.ACTION_CANCEL -> { moved = false; view.isPressed = false; false }
                else -> false
            }
        }
        val check = CheckBox(activity).apply { minimumWidth = Ui.dp(activity, 44) }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val time = Ui.text(activity, "", 40f).apply { setPadding(0, 0, 0, Ui.dp(activity, 4)) }
        val detail = Ui.text(activity, "", 15f, true)
        val mode = Ui.text(activity, "", 14f, true)
        val next = Ui.text(activity, "", 13f, true)
        listOf(detail, mode, next).forEach { it.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2)) }
        body.addView(time); body.addView(detail); body.addView(mode); body.addView(next)
        val enabled = Switch(activity).apply {
            minimumWidth = Ui.dp(activity, 48)
            var startX = 0f
            var startY = 0f
            // Short thumb drags operate the switch. A full-row swipe can take over once it leaves the control.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    startX = event.rawX; startY = event.rawY
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                    kotlin.math.abs(event.rawX - startX) > maxOf(view.width, Ui.dp(activity, 56)) &&
                    kotlin.math.abs(event.rawX - startX) > kotlin.math.abs(event.rawY - startY))
                    view.parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    view.parent.requestDisallowInterceptTouchEvent(false)
                false
            }
        }
        card.addView(check)
        card.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(enabled)
        return Holder(card, check, time, detail, mode, next, enabled)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val alarm = alarms[position]
        holder.alarm = alarm
        holder.card.contentDescription = "Будильник ${Ui.time(alarm)}"
        holder.card.background = GradientDrawable().apply {
            cornerRadius = Ui.dp(activity, 20).toFloat()
            setColor(Ui.SURFACE)
            if (alarm.id in selected) setStroke(Ui.dp(activity, 2), Ui.GREEN)
        }
        holder.card.setOnClickListener { click(alarm) }
        holder.card.setOnLongClickListener { longClick(alarm); true }
        holder.time.text = Ui.time(alarm)
        holder.time.setTextColor(if (alarm.enabled) Ui.INK else Ui.MUTED)
        holder.detail.text = "${alarm.label.ifBlank { "Будильник" }} · ${Ui.daysLabel(alarm.days)}"
        holder.mode.text = "${if (alarm.dismissMode == DismissMode.QR) "QR с ноутбука" else "Кнопка"} · ${alarm.volume}%"
        holder.next.visibility = if (alarm.enabled && alarm.scheduledAt > 0) View.VISIBLE else View.GONE
        if (alarm.enabled && alarm.scheduledAt > 0) holder.next.text = "Следующий: ${java.time.Instant.ofEpochMilli(alarm.scheduledAt)
            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM · HH:mm"))}"
        holder.enabled.visibility = if (selecting) View.GONE else View.VISIBLE
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = alarm.enabled
        holder.enabled.contentDescription = "Включён будильник ${Ui.time(alarm)}"
        holder.enabled.setOnCheckedChangeListener { _, checked -> toggle(alarm, checked) }
        holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = alarm.id in selected
        holder.check.contentDescription = "Выбрать будильник ${Ui.time(alarm)}"
        holder.check.setOnCheckedChangeListener { _, _ -> click(alarm) }
    }

    fun attachSwipe(recycler: RecyclerView, remove: (Alarm) -> Unit) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun getSwipeDirs(view: RecyclerView, holder: RecyclerView.ViewHolder): Int = if (selecting) 0 else super.getSwipeDirs(view, holder)
            override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                (holder as Holder).alarm?.let(remove)
            }
            override fun onChildDraw(canvas: Canvas, view: RecyclerView, holder: RecyclerView.ViewHolder,
                                     dx: Float, dy: Float, state: Int, active: Boolean) {
                val item = holder.itemView
                if (dx < 0) {
                    paint.color = Ui.RED
                    canvas.drawRoundRect(item.left.toFloat(), item.top.toFloat(), item.right.toFloat(), item.bottom.toFloat(),
                        Ui.dp(activity, 20).toFloat(), Ui.dp(activity, 20).toFloat(), paint)
                    paint.color = Ui.BG; paint.textSize = Ui.dp(activity, 15).toFloat(); paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("Удалить", item.right - Ui.dp(activity, 18).toFloat(), item.top + item.height / 2f, paint)
                }
                super.onChildDraw(canvas, view, holder, dx, dy, state, active)
            }
        }).attachToRecyclerView(recycler)
    }
}
