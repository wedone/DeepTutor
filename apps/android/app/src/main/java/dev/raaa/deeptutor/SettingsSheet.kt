package dev.raaa.deeptutor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 原生设置面板，配色与 Web 端的 Notion 风格保持一致。 */
class SettingsSheet(
    private val context: Context,
    private val store: ServerStore,
    private val webView: WebView,
    private val onServerChanged: (String) -> Unit,
    private val onImmersiveChanged: (Boolean) -> Unit,
    private val onCustomLayerChanged: (String) -> Unit,
    private val onMathKeyboardChanged: (Boolean) -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_settings, null)
        val serverError = view.findViewById<TextView>(R.id.serverError)
        val input = view.findViewById<EditText>(R.id.serverInput)
        input.setText(store.serverUrl.orEmpty())

        val immersive = view.findViewById<SwitchCompat>(R.id.immersiveSwitch)
        immersive.isChecked = store.immersive
        immersive.setOnCheckedChangeListener { _, checked -> onImmersiveChanged(checked) }

        val mathSwitch = view.findViewById<SwitchCompat>(R.id.mathKeyboardSwitch)
        mathSwitch.isChecked = store.mathKeyboardEnabled
        mathSwitch.setOnCheckedChangeListener { _, checked -> onMathKeyboardChanged(checked) }

        val customError = view.findViewById<TextView>(R.id.customLayerError)
        val customInput = view.findViewById<EditText>(R.id.customLayerInput)
        customInput.setText(store.customLayerUrl.orEmpty())
        view.findViewById<View>(R.id.applyCustomLayer).setOnClickListener {
            val raw = customInput.text?.toString().orEmpty().trim()
            val normalized = if (raw.isEmpty()) "" else ServerUrl.normalize(raw)
            if (normalized == null) {
                customError.text = context.getString(R.string.connect_invalid)
                customError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            customError.visibility = View.GONE
            dialog.dismiss()
            onCustomLayerChanged(normalized)
        }

        view.findViewById<View>(R.id.saveServer).setOnClickListener {
            val normalized = ServerUrl.normalize(input.text?.toString().orEmpty())
            if (normalized == null) {
                serverError.text = context.getString(R.string.connect_invalid)
                serverError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            serverError.visibility = View.GONE
            store.serverUrl = normalized
            dialog.dismiss()
            onServerChanged(normalized)
        }

        view.findViewById<View>(R.id.reloadPage).setOnClickListener {
            dialog.dismiss()
            webView.reload()
        }

        view.findViewById<View>(R.id.clearData).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            store.serverUrl = null
            dialog.dismiss()
            onServerChanged("")
        }

        view.findViewById<android.widget.TextView>(R.id.aboutLine).text =
            context.getString(R.string.settings_about, BuildConfig.VERSION_NAME, store.serverUrl.orEmpty())

        dialog.setContentView(view)
        // 覆盖 BottomSheet 默认背景，改用与 Web 一致的圆角面板
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.bg_sheet)
            hideStatusBarIfImmersive(dialog)
        }
        dialog.show()
    }

    /** 沉浸模式下，对话框自身的 window 也需要隐藏状态栏，否则弹出时状态栏会重新出现。 */
    private fun hideStatusBarIfImmersive(dialog: BottomSheetDialog) {
        if (!store.immersive) return
        val window = dialog.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
