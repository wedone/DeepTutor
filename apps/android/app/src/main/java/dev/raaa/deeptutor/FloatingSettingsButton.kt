package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private val fadeOut = Runnable { animate().alpha(0.3f).setDuration(300).start() }

    init {
        inflate(context, R.layout.view_floating_settings, this)
        alpha = 0.85f
        scheduleFade()
    }

    /** 操作时恢复可见，并重置闲置计时。 */
    private fun wake() {
        handler.removeCallbacks(fadeOut)
        animate().cancel()
        alpha = 0.85f
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fadeOut)
        handler.postDelayed(fadeOut, IDLE_FADE_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(fadeOut)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
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
                scheduleFade()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragged) snapToEdge()
                scheduleFade()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge() {
        val parentView = parent as? View ?: return
        val targetX = if (x + width / 2 < parentView.width / 2f) 0f else (parentView.width - width).toFloat()
        animate().x(targetX).setDuration(180).start()
        store.fabX = targetX.toInt()
        store.fabY = y.toInt()
    }

    override fun performClick(): Boolean {
        wake()
        onClick()
        super.performClick()
        return true
    }

    private companion object {
        const val IDLE_FADE_MS = 2_000L
    }
}
