package dev.raaa.deeptutor

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView

/** 覆盖在 WebView 上的加载失败视图。 */
class LoadErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val messageView: TextView

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_load_error, this, true)
        messageView = findViewById(R.id.errorMessage)
    }

    fun bind(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
        messageView.text = message
        findViewById<android.widget.Button>(R.id.retryButton).setOnClickListener { onRetry() }
        findViewById<android.widget.Button>(R.id.settingsButton).setOnClickListener { onSettings() }
    }
}
