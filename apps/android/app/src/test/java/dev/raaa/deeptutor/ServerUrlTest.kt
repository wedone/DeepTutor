package dev.raaa.deeptutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `补齐缺失的 https 前缀`() {
        assertEquals("https://eu.raaa.fun:4433", ServerUrl.normalize("eu.raaa.fun:4433"))
    }

    @Test
    fun `保留显式 http 前缀`() {
        assertEquals("http://192.168.1.10:3782", ServerUrl.normalize("http://192.168.1.10:3782"))
    }

    @Test
    fun `去掉末尾斜杠`() {
        assertEquals("https://eu.raaa.fun:4433", ServerUrl.normalize("https://eu.raaa.fun:4433/"))
    }

    @Test
    fun `拒绝空字符串`() {
        assertNull(ServerUrl.normalize("   "))
    }

    @Test
    fun `拒绝不支持的非 http 协议`() {
        assertNull(ServerUrl.normalize("ftp://example.com"))
    }

    @Test
    fun `识别明文 http`() {
        assertEquals(true, ServerUrl.isInsecure("http://10.0.0.2:3782"))
        assertEquals(false, ServerUrl.isInsecure("https://eu.raaa.fun:4433"))
    }
}
