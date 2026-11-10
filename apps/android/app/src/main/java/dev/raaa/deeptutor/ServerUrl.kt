package dev.raaa.deeptutor

import java.net.URI

/** 服务器地址的规范化与校验。所有输入都经过这里，避免各处重复实现。 */
object ServerUrl {

    /** 返回规范化后的地址；非法输入返回 null。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 显式写了协议就必须是 http(s)，否则 "ftp://host" 会被当成 https 主机名
        val explicitScheme = SCHEME_PATTERN.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") return null
        val withScheme = if (explicitScheme == null) "https://$trimmed" else trimmed
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            buildString {
                append(scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
                val path = uri.path.orEmpty().trimEnd('/')
                if (path.isNotEmpty()) append(path)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 明文 HTTP 视为不安全连接，需要在界面上提示。 */
    fun isInsecure(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

    /** 判断一个导航目标是否与当前服务器同源（同 scheme + host + port）。 */
    fun isSameOrigin(serverUrl: String, target: String): Boolean = try {
        val a = URI(serverUrl)
        val b = URI(target)
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    } catch (_: Exception) {
        false
    }

    private val SCHEME_PATTERN = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")
}
