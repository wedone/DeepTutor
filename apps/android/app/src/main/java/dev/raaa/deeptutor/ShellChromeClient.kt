package dev.raaa.deeptutor

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

open class ShellChromeClient(
    private val uploadPicker: UploadPicker,
    private val onProgress: (Int) -> Unit,
) : WebChromeClient() {

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = filePathCallback
        uploadPicker.show(
            acceptTypes = fileChooserParams?.acceptTypes,
            captureEnabled = fileChooserParams?.isCaptureEnabled == true,
            allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
            onResult = { uris ->
                fileCallback?.onReceiveValue(uris.toTypedArray())
                fileCallback = null
            },
            onCancel = {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
            },
        )
        return true
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
}
