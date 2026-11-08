package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.30.0: разбор публичных списков прокси и ранжирование.
 * Смысл тестов: мусор из списков не должен превращаться в «резерв»,
 * иначе браузер будет по 10 секунд ждать мёртвый адрес.
 */
class ReservePoolTest {

    @Test
    fun `обычная строка ip порт`() {
        val p = ReservePool.parseLine("1.2.3.4:8080", ReservePool.Kind.HTTP)
        assertEquals("1.2.3.4", p?.host)
        assertEquals(8080, p?.port)
        assertEquals(ReservePool.Kind.HTTP, p?.kind)
    }

    @Test
    fun `схема socks5 меняет тип`() {
        val p = ReservePool.parseLine("socks5://5.6.7.8:1080", ReservePool.Kind.HTTP)
        assertEquals(ReservePool.Kind.SOCKS5, p?.kind)
        assertEquals(1080, p?.port)
    }

    @Test
    fun `логин и пароль отбрасываются`() {
        val p = ReservePool.parseLine("http://user:pass@9.9.9.9:3128", ReservePool.Kind.SOCKS5)
        assertEquals("9.9.9.9", p?.host)
        assertEquals(3128, p?.port)
        assertEquals(ReservePool.Kind.HTTP, p?.kind)
    }

    @Test
    fun `мусор не проходит`() {
        assertNull(ReservePool.parseLine("", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("# комментарий", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("{\"ip\":\"1.2.3.4\"}", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("1.2.3.4", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("1.2.3.4:0", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("1.2.3.4:99999", ReservePool.Kind.HTTP))
        assertNull(ReservePool.parseLine("ftp://1.2.3.4:21", ReservePool.Kind.HTTP))
    }

    @Test
    fun `разбор многострочного списка`() {
        val text = buildString {
            appendLine("1.1.1.1:8080")
            appendLine("")
            appendLine("2.2.2.2:3128")
            appendLine("# мусор")
            appendLine("3.3.3.3:1080")
        }
        val list = ReservePool.parse(text, ReservePool.Kind.HTTP, "test")
        assertEquals(3, list.size)
        assertEquals("1.1.1.1", list[0].host)
        assertEquals("3.3.3.3", list[2].host)
    }

    @Test
    fun `локальные адреса выбрасываются`() {
        assertTrue(ReservePool.isReserved("127.0.0.1"))
        assertTrue(ReservePool.isReserved("10.0.0.5"))
        assertTrue(ReservePool.isReserved("192.168.1.10"))
        assertTrue(ReservePool.isReserved("172.20.3.4"))
        assertTrue(ReservePool.isReserved("169.254.1.1"))
        assertTrue(ReservePool.isReserved("224.0.0.1"))
        assertTrue(!ReservePool.isReserved("8.8.8.8"))
        assertTrue(!ReservePool.isReserved("proxy.example.com"))
    }

    @Test
    fun `дубли убираются а порядок сохраняется`() {
        val list = listOf(
            ReservePool.Proxy("1.1.1.1", 8080, ReservePool.Kind.HTTP),
            ReservePool.Proxy("1.1.1.1", 8080, ReservePool.Kind.HTTP),
            ReservePool.Proxy("1.1.1.1", 8080, ReservePool.Kind.SOCKS5),
            ReservePool.Proxy("127.0.0.1", 9050, ReservePool.Kind.SOCKS5),
            ReservePool.Proxy("2.2.2.2", 3128, ReservePool.Kind.HTTP)
        )
        val clean = ReservePool.sanitize(list)
        assertEquals(3, clean.size)
        assertEquals("1.1.1.1", clean[0].host)
        assertEquals(ReservePool.Kind.SOCKS5, clean[1].kind)
        assertEquals("2.2.2.2", clean[2].host)
    }

    @Test
    fun `ранжирование ставит быстрых вперёд`() {
        val a = ReservePool.Proxy("1.1.1.1", 1, ReservePool.Kind.HTTP)
        val b = ReservePool.Proxy("2.2.2.2", 2, ReservePool.Kind.HTTP)
        val c = ReservePool.Proxy("3.3.3.3", 3, ReservePool.Kind.HTTP)
        val ranked = ReservePool.rank(listOf(c, b, a), mapOf("2.2.2.2:2" to 90, "3.3.3.3:3" to 30))
        assertEquals(listOf("3.3.3.3", "2.2.2.2", "1.1.1.1"), ranked.map { it.host })
    }
}
