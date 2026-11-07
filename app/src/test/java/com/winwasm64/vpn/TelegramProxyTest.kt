package com.winwasm64.vpn

import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.14.0: сводка «перебора прокси» должна объяснять, что проверка реально делалась. */
class TelegramProxyTest {

    private val ok = TelegramProxy.Step("lv115.servefaststatic.work:443", true, 120)
    private val bad = TelegramProxy.Step("de58.rapidstaticserve.cc:443", false, 0, "нет ответа")

    @Test
    fun `рабочий сервер называется в сводке`() {
        val s = TelegramProxy.summary(listOf(bad, ok), ok.server)
        assertTrue(s.contains("работает сервер lv115"))
        assertTrue(s.contains("проверено 2"))
    }

    @Test
    fun `если не пустил никто - честно говорим про Psiphon`() {
        val s = TelegramProxy.summary(listOf(bad), null)
        assertTrue(s.contains("ни один не открыл Telegram"))
        assertTrue(s.contains("Psiphon"))
    }

    @Test
    fun `пустой пул серверов не выдаётся за проверку`() {
        assertTrue(TelegramProxy.summary(emptyList(), null).contains("не получены"))
    }

    @Test
    fun `ссылка на прокси ведёт на локальный порт`() {
        assertTrue(TelegramProxy.tgUri(8911).startsWith("tg://socks?server=127.0.0.1&port=8911"))
    }
}
