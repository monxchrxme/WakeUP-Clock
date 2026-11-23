package ru.wakeup.clock

import android.content.Context
import android.widget.NumberPicker

/** Give the native wheel a little more travel after release, retaining its drag and snap behavior. */
class InertialNumberPicker(context: Context) : NumberPicker(context) {
    private var flinging = false
    private var remainder = 0f
    private var scrollListener: OnScrollListener? = null

    init {
        super.setOnScrollListener { picker, state ->
            flinging = state == OnScrollListener.SCROLL_STATE_FLING
            remainder = 0f
            scrollListener?.onScrollStateChange(picker, state)
        }
    }

    override fun setOnScrollListener(listener: OnScrollListener?) {
        scrollListener = listener
    }

    override fun scrollBy(x: Int, y: Int) {
        if (!flinging) {
            super.scrollBy(x, y)
            return
        }
        // Carry sub-pixel travel between frames, including slow movement near the end of a fling.
        val distance = y * 1.2f + remainder
        val pixels = distance.toInt()
        remainder = distance - pixels
        super.scrollBy(x, pixels)
    }
}
