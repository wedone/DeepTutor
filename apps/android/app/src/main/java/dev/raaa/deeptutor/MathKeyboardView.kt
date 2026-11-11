package dev.raaa.deeptutor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * 原生数学键盘面板：内嵌 MathLive 编辑器页面，布局与交互参考 zulip-flutter 的 mathlive_studio。
 * 只负责显示键盘并把 LaTeX 回传给宿主，插入逻辑由主 WebView 执行。
 */
class MathKeyboardView(
    context: Context,
    private val assetLoader: WebViewAssetLoader,
    private val insertCallback: (latex: String, mode: String) -> Unit,
    private val heightCallback: (cssHeight: Int) -> Unit,
) : FrameLayout(context) {

    private val webView: WebView = WebView(context)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = OVER_SCROLL_NEVER
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }
        webView.addJavascriptInterface(Bridge(), "DeepTutorMath")
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl("https://${MathLiveAssetHandler.DOMAIN}${MathLiveAssetHandler.PATH_PREFIX}mathlive_editor.html")
    }

    fun setDark(dark: Boolean) {
        webView.evaluateJavascript("mlMixedSetTheme($dark)", null)
    }

    fun destroy() {
        removeAllViews()
        webView.destroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onInsert(payload: String) {
            val json = try {
                JSONObject(payload)
            } catch (_: Exception) {
                return
            }
            val latex = json.optString("latex")
            val mode = json.optString("mode", "inline")
            if (latex.isBlank()) return
            post { insertCallback(latex, mode) }
        }

        @JavascriptInterface
        fun onHeight(cssHeight: Int) {
            post { heightCallback(cssHeight) }
        }
    }
}
