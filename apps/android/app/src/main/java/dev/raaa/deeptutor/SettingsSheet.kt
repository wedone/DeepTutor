package dev.raaa.deeptutor

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 原生设置面板：改地址、清站点数据、重新加载、关于。 */
class SettingsSheet(
    private val context: Context,
    private val store: ServerStore,
    private val webView: WebView,
    private val onServerChanged: (String) -> Unit,
    private val onImmersiveChanged: (Boolean) -> Unit,
    private val onCustomLayerChanged: (String) -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.sheet_settings, null)
        val layout = view.findViewById<TextInputLayout>(R.id.serverLayout)
        val input = view.findViewById<TextInputEditText>(R.id.serverInput)
        input.setText(store.serverUrl.orEmpty())

        val immersive = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.immersiveSwitch,
        )
        immersive.isChecked = store.immersive
        immersive.setOnCheckedChangeListener { _, checked -> onImmersiveChanged(checked) }

        val customLayout = view.findViewById<TextInputLayout>(R.id.customLayerLayout)
        val customInput = view.findViewById<TextInputEditText>(R.id.customLayerInput)
        customInput.setText(store.customLayerUrl.orEmpty())
        view.findViewById<android.view.View>(R.id.applyCustomLayer).setOnClickListener {
            val raw = customInput.text?.toString().orEmpty().trim()
            val normalized = if (raw.isEmpty()) "" else ServerUrl.normalize(raw)
            if (normalized == null) {
                customLayout.error = context.getString(R.string.connect_invalid)
                return@setOnClickListener
            }
            dialog.dismiss()
            onCustomLayerChanged(normalized)
        }

        view.findViewById<android.view.View>(R.id.saveServer).setOnClickListener {
            val normalized = ServerUrl.normalize(input.text?.toString().orEmpty())
            if (normalized == null) {
                layout.error = context.getString(R.string.connect_invalid)
                return@setOnClickListener
            }
            store.serverUrl = normalized
            dialog.dismiss()
            onServerChanged(normalized)
        }

        view.findViewById<android.view.View>(R.id.reloadPage).setOnClickListener {
            dialog.dismiss()
            webView.reload()
        }

        view.findViewById<android.view.View>(R.id.clearData).setOnClickListener {
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
        dialog.show()
    }
}
