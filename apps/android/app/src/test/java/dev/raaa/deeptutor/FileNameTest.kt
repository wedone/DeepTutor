package dev.raaa.deeptutor

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameTest {
    @Test
    fun `移除路径分隔符`() {
        assertEquals("etcpasswd.txt", FileName.sanitize("../../etc/passwd.txt"))
    }

    @Test
    fun `保留中文与扩展名`() {
        assertEquals("错题本.pdf", FileName.sanitize("错题本.pdf"))
    }

    @Test
    fun `空名回落为 download`() {
        assertEquals("download", FileName.sanitize("   "))
    }
}
