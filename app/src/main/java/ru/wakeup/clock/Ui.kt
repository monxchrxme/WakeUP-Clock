package ru.wakeup.clock

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Ui {
    const val BG = 0xFF282828.toInt()
    const val INK = 0xFFD4BE98.toInt()
    const val MUTED = 0xFFA89984.toInt()
    const val GREEN = 0xFFA9B665.toInt()
    const val SURFACE = 0xFF32302F.toInt()
    const val LINE = 0xFF504945.toInt()
    const val ORANGE = 0xFFE78A4E.toInt()
    const val RED = 0xFFEA6962.toInt()
    fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun root(activity: Activity, footer: View? = null): LinearLayout {
        val frame = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val scroll = ScrollView(activity).apply { setBackgroundColor(BG); isFillViewport = true }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 24))
        }
        scroll.addView(column)
        // Handle Android 15+ edge-to-edge and the scanner returning with the navigation bar visible.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (footer != null) frame.addView(footer, LinearLayout.LayoutParams(-1, -2))
        activity.setContentView(frame)
        frame.requestApplyInsets()
        return column
    }

    fun text(activity: Activity, value: String, size: Float = 16f, muted: Boolean = false): TextView =
        TextView(activity).apply {
            text = value; textSize = size; setTextColor(if (muted) MUTED else INK)
            setPadding(0, dp(activity, 7), 0, dp(activity, 7))
        }

    fun title(activity: Activity, value: String) = text(activity, value, 32f).apply {
        setTypeface(null, Typeface.BOLD)
    }

    fun button(activity: Activity, label: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(activity).apply {
            text = label; isAllCaps = false; textSize = 16f
            minHeight = dp(activity, 52)
            setTextColor(if (primary) BG else GREEN)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 16).toFloat()
                setColor(if (primary) GREEN else 0xFF45403D.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(activity, 8); bottomMargin = dp(activity, 8)
            }
            setOnClickListener { action() }
        }

    fun card(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12))
        background = GradientDrawable().apply { cornerRadius = dp(activity, 20).toFloat(); setColor(SURFACE) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) }
    }

    fun time(alarm: Alarm) = "%02d:%02d".format(alarm.hour, alarm.minute)
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    fun daysLabel(mask: Int): String = if (mask == 0) "Один раз" else
        days.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" · ")
}
