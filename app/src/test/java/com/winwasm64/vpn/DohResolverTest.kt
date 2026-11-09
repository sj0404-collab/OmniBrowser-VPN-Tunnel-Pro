package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v4.35.0: разбор DNS-ответов для многопутевого резолвера.
 * Проверяем самое хрупкое — чтение A-записи и указатели сжатия имён.
 */
class DohResolverTest {

    /** Ответ для example.com: вопрос + одна A-запись 93.184.216.34. */
    private fun answerWithA(): ByteArray {
        val out = ArrayList<Byte>(64)
        fun b(v: Int) = out.add((v and 0xFF).toByte())
        // заголовок
        b(0x12); b(0x34); b(0x81); b(0x80); b(0x00); b(0x01); b(0x00); b(0x01); b(0); b(0); b(0); b(0)
        // вопрос: example.com A IN
        val name = "example.com"
        for (label in name.split(".")) {
            b(label.length)
            label.forEach { b(it.code) }
        }
        b(0)
        b(0x00); b(0x01); b(0x00); b(0x01)
        // ответ: имя — указатель на вопрос (0xC00C), A, IN, ttl 60, rdlength 4
        b(0xC0); b(0x0C)
        b(0x00); b(0x01)
        b(0x00); b(0x01)
        b(0); b(0); b(0); b(60)
        b(0x00); b(0x04)
        b(93); b(184); b(216); b(34)
        return out.toByteArray()
    }

    @Test
    fun `читает A-запись через указатель сжатия`() {
        val ip = DohResolver::class.java.getDeclaredMethod("parseFirstA", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(DohResolver, answerWithA()) as String?
        assertEquals("93.184.216.34", ip)
    }

    @Test
    fun `петлевой адрес не считается ответом`() {
        val bytes = answerWithA()
        bytes[bytes.size - 4] = 127
        val ip = DohResolver::class.java.getDeclaredMethod("parseFirstA", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(DohResolver, bytes) as String?
        assertNull(ip)
    }

    @Test
    fun `мусор не роняет разбор`() {
        val ip = DohResolver::class.java.getDeclaredMethod("parseFirstA", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(DohResolver, ByteArray(5)) as String?
        assertNull(ip)
    }
}
