package dev.raaa.deeptutor

import java.io.ByteArrayOutputStream

/** 校验并合并页面分块传来的 blob 数据，强制顺序与大小上限。 */
class BlobAssembler(private val maxBytes: Int) {

    private val buffer = ByteArrayOutputStream()
    private var expectedIndex = 0

    @Synchronized
    fun append(index: Int, chunk: ByteArray): Boolean {
        if (index != expectedIndex) return false
        if (buffer.size() + chunk.size > maxBytes) return false
        buffer.write(chunk)
        expectedIndex += 1
        return true
    }

    @Synchronized
    fun finish(): ByteArray = buffer.toByteArray()
}
