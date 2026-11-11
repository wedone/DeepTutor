package dev.raaa.deeptutor

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.IOException

/**
 * 把 assets/mathlive 暴露为 https://appassets.androidplatform.net/assets/mathlive/，
 * 并补上 CORS 头，使远端页面可以 import 该模块与字体。
 */
class MathLiveAssetHandler(private val assets: AssetManager) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.trimStart('/')
        if (name.isEmpty() || name.contains("..")) return null
        return try {
            val stream = assets.open("mathlive/$name")
            WebResourceResponse(
                mimeOf(name),
                null,
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                stream,
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
        name.endsWith(".mjs") || name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val PATH_PREFIX = "/assets/mathlive/"
    }
}
