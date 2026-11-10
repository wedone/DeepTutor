package dev.raaa.deeptutor

import android.webkit.JavascriptInterface

/** 页面到原生的最小桥接面：只接受布尔值，无路径参数，无命令执行。 */
class ShellBridge(
    private val themeCallback: (Boolean) -> Unit,
    private val scrollCallback: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onThemeChanged(dark: Boolean) = themeCallback(dark)

    @JavascriptInterface
    fun onScrollStateChanged(atTop: Boolean) = scrollCallback(atTop)
}
