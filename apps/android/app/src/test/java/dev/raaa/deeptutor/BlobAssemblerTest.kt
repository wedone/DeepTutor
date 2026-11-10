package dev.raaa.deeptutor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobAssemblerTest {
    @Test
    fun `按序拼接分块`() {
        val assembler = BlobAssembler(maxBytes = 1024)
        assertTrue(assembler.append(0, byteArrayOf(1, 2)))
        assertTrue(assembler.append(1, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), assembler.finish())
    }

    @Test
    fun `超出上限时拒绝`() {
        val assembler = BlobAssembler(maxBytes = 3)
        assertTrue(assembler.append(0, byteArrayOf(1, 2)))
        assertFalse(assembler.append(1, byteArrayOf(3, 4)))
    }

    @Test
    fun `乱序分块返回失败`() {
        val assembler = BlobAssembler(maxBytes = 1024)
        assertFalse(assembler.append(1, byteArrayOf(1)))
    }
}
