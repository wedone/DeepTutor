package dev.raaa.deeptutor

import android.webkit.JavascriptInterface

/**
 * 页面到原生的最小桥接面：只接受布尔值、下载分块串与 id，
 * 无路径参数、无命令执行。构造参数名不能与 @JavascriptInterface 方法同名，
 * 否则 Kotlin 生成的 JVM 签名会冲突。
 */
class ShellBridge(
    private val themeCallback: (Boolean) -> Unit,
    private val scrollCallback: (Boolean) -> Unit,
    private val blobBegin: (String, String, String) -> Boolean,
    private val blobChunk: (String, Int, String) -> Boolean,
    private val blobFinish: (String) -> Unit,
    private val blobFailed: (String) -> Unit,
    private val hideKeyboardCallback: () -> Unit,
) {
    @JavascriptInterface
    fun onThemeChanged(dark: Boolean) = themeCallback(dark)

    @JavascriptInterface
    fun onScrollStateChanged(atTop: Boolean) = scrollCallback(atTop)

    @JavascriptInterface
    fun beginBlob(id: String, name: String, mime: String): Boolean = blobBegin(id, name, mime)

    @JavascriptInterface
    fun appendBlob(id: String, index: Int, base64: String): Boolean =
        blobChunk(id, index, base64)

    @JavascriptInterface
    fun finishBlob(id: String) = blobFinish(id)

    @JavascriptInterface
    fun onDownloadFailed(reason: String) = blobFailed(reason)

    @JavascriptInterface
    fun hideKeyboard() = hideKeyboardCallback()
}
