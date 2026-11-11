package dev.raaa.deeptutor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class ShellWebViewClient(
    private val context: Context,
    private val serverUrl: () -> String,
    private val onMainFrameError: (String) -> Unit,
    private val fallbackScript: String? = null,
    private val assetLoader: WebViewAssetLoader? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        assetLoader?.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // 旧内核不支持 DOCUMENT_START_SCRIPT 时，在这里补注入
        fallbackScript?.let { view?.evaluateJavascript(it, null) }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        // 内置 MathLive 资源由 WebViewAssetLoader 处理，不当作外链
        if (request.url.host == MathLiveAssetHandler.DOMAIN) return false
        if (ServerUrl.isSameOrigin(serverUrl(), target)) return false
        openExternally(target)
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onMainFrameError(error.description.toString())
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            onMainFrameError("HTTP ${errorResponse.statusCode}")
        }
    }

    private fun openExternally(target: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // 没有可处理该协议的 App 时静默忽略
        }
    }
}
