package dev.raaa.deeptutor

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程自定义层：从用户配置的目录拉取 custom.css / custom.js，缓存到私有目录，
 * 页面加载时注入。改样式或补丁脚本无需重新部署 Web 端，也无需重装 APK。
 */
class CustomLayer(context: Context) {

    private val dir = File(context.filesDir, "custom-layer").apply { mkdirs() }
    private val cssFile = File(dir, "custom.css")
    private val jsFile = File(dir, "custom.js")

    fun css(): String? = read(cssFile)

    fun script(): String? = read(jsFile)

    /** 下载失败时保留旧缓存；两个文件都不存在视为失败。 */
    fun refresh(baseUrl: String): Boolean {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return false
        val css = download("$base/custom.css")
        val script = download("$base/custom.js")
        if (css == null && script == null) return false
        if (css != null) cssFile.writeText(css) else cssFile.delete()
        if (script != null) jsFile.writeText(script) else jsFile.delete()
        return true
    }

    fun clear() {
        cssFile.delete()
        jsFile.delete()
    }

    private fun read(file: File): String? =
        if (file.isFile && file.length() in 1..MAX_BYTES) file.readText() else null

    private fun download(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024

        /** 把样式文本包成可注入的脚本，document-start 阶段就能生效。 */
        fun cssScript(css: String): String {
            val encoded = android.util.Base64.encodeToString(css.toByteArray(), android.util.Base64.NO_WRAP)
            return """
                (function(){
                  try {
                    var bytes = Uint8Array.from(atob('$encoded'), function(c){ return c.charCodeAt(0); });
                    var css = new TextDecoder('utf-8').decode(bytes);
                    var apply = function(){
                      var style = document.createElement('style');
                      style.setAttribute('data-dt-custom-layer', '');
                      style.textContent = css;
                      (document.head || document.documentElement).appendChild(style);
                    };
                    if (document.head) apply(); else document.addEventListener('DOMContentLoaded', apply);
                  } catch (e) { console.warn('[DeepTutor] custom.css', e); }
                })();
            """.trimIndent()
        }

        /** 用户脚本在 DOM 就绪后以函数作用域执行，避免变量泄漏和顶层 return 报错。 */
        fun jsScript(source: String): String = """
            document.addEventListener('DOMContentLoaded', function(){
              try {
                (function(){
            $source
                })();
              } catch (e) { console.warn('[DeepTutor] custom.js', e); }
            });
        """.trimIndent()
    }
}
