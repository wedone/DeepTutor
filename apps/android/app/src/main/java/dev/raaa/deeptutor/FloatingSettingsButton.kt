package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** 可拖动、自动贴边的设置入口，不遮挡正文时几乎无感。 */
@SuppressLint("ViewConstructor")
class FloatingSettingsButton(
    context: Context,
    private val store: ServerStore,
    private val onClick: () -> Unit,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragged = false

    init {
        inflate(context, R.layout.view_floating_settings, this)
        alpha = 0.85f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged &&
                    (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)
                ) {
                    dragged = true
                }
                if (dragged) {
                    x = event.rawX - width / 2f
                    y = event.rawY - height / 2f
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) snapToEdge() else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragged) snapToEdge()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge() {
        val parentView = parent as? View ?: return
        val targetX = if (x + width / 2 < parentView.width / 2f) 0f else (parentView.width - width).toFloat()
        animate().x(targetX).setDuration(180).withEndAction {
            animate().alpha(0.3f).setDuration(200).start()
        }.start()
        store.fabX = targetX.toInt()
        store.fabY = y.toInt()
    }

    override fun performClick(): Boolean {
        animate().alpha(0.85f).setDuration(120).start()
        onClick()
        super.performClick()
        return true
    }
}
