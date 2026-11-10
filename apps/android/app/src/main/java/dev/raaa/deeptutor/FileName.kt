package dev.raaa.deeptutor

object FileName {
    /**
     * 去掉路径分隔符、`.`/`..` 段与控制字符，避免写入 Downloads 时穿越目录。
     * 逐段过滤后直接拼接，因此不会留下分隔符或上跳段。
     */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .split('/', '\\')
            .filter { it != "." && it != ".." }
            .joinToString("")
            .filter { !it.isISOControl() }
            .trim()
        return cleaned.ifEmpty { "download" }
    }
}
