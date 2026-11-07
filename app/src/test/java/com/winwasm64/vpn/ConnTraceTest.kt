package com.winwasm64.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v4.17.0 + 4.18.0: журнал соединений — «кто, куда, протокол, маршрут, зачем».
 *
 * Проверяем то, на что пользователь смотрит глазами: строки читаемы, фильтры работают,
 * запрос страницы и реальное соединение дают ОДНУ строку (а не две, как в 4.17.0),
 * служебный мусор не засоряет журнал, статистика считает маршруты.
 */
class ConnTraceTest {

    @Before
    fun setUp() = ConnTrace.clear()

    @After
    fun tearDown() = ConnTrace.clear()

    @Test
    fun `строка соединения содержит кто куда протокол маршрут и зачем`() {
        ConnTrace.connect(
            who = "Браузер",
            host = "play.geforcenow.com",
            port = 443,
            route = "Browsec lv210",
            ms = 180,
            ok = true,
            purpose = "страница (переход)"
        )
        val line = ConnTrace.snapshot(null).single()
        assertTrue(line.contains("Браузер"))
        assertTrue(line.contains("play.geforcenow.com:443"))
        assertTrue(line.contains("TCP"))
        assertTrue(line.contains("Browsec lv210"))
        assertTrue(line.contains("страница (переход)"))
        assertTrue(line.contains("180 мс"))
    }

    @Test
    fun `запрос страницы сливается с реальным соединением в одну строку`() {
        // WebView сообщил «кто и зачем», затем прокси сообщил «через что и сколько»
        ConnTrace.pendingRequest("play.geforcenow.com", "login.nvidia.com", 443, "подресурс")
        ConnTrace.connect("Браузер", "login.nvidia.com", 443, "Browsec lv210", 850, true)

        val lines = ConnTrace.snapshot("browser")
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("play.geforcenow.com"))   // кто вызвал
        assertTrue(line.contains("login.nvidia.com:443"))  // куда
        assertTrue(line.contains("Browsec lv210"))         // через что
        assertTrue(line.contains("подресурс"))             // зачем
    }

    @Test
    fun `фильтры разделяют браузер приложения UDP торренты и обход DPI`() {
        ConnTrace.connect("Браузер", "a.example", 443, "Browsec lv1", 10, true)
        ConnTrace.connect("Telegram", "b.example", 443, "Psiphon", 20, true)
        ConnTrace.udp("GeForce NOW", "92.223.105.5:443", "DTLS (игра/звонок) → UDP-релей")
        ConnTrace.connect("Браузер", "c.example", 443, "напрямую (обход DPI, IP не меняется)", 30, true)
        ConnTrace.torrent("tracker.opentrackr.org:1337", "трекер (UDP) → 9 пиров", true)

        assertEquals(1, ConnTrace.snapshot("browser").size)
        assertEquals(1, ConnTrace.snapshot("apps").size)
        assertEquals(1, ConnTrace.snapshot("udp").size)
        assertEquals(1, ConnTrace.snapshot("bypass").size)
        assertEquals(1, ConnTrace.snapshot("torrent").size)
        assertTrue(ConnTrace.snapshot("udp").single().contains("DTLS"))
        assertTrue(ConnTrace.snapshot("torrent").single().contains("9 пиров"))
    }

    @Test
    fun `неудачные соединения попадают в ошибки`() {
        ConnTrace.connect("Браузер", "x.example", 443, "Browsec lv1", 5000, false, err = "маршрут не ответил")
        val err = ConnTrace.snapshot("err").single()
        assertTrue(err.contains("❌"))
        assertTrue(err.contains("маршрут не ответил"))
    }

    @Test
    fun `отсечённая реклама видна сразу и помечена как блокировка`() {
        ConnTrace.request("site.ru", "ads.example", 443, "реклама/трекер — отсечено", true)
        val line = ConnTrace.snapshot(null).single()
        assertTrue(line.contains("заблокировано"))
        assertEquals(1, ConnTrace.snapshot("err").size)
    }

    @Test
    fun `служебная заглушка PAC не попадает в журнал`() {
        assertTrue(ConnTrace.isNoise("pac.omni-ext.invalid"))
        ConnTrace.connect("Браузер", "pac.omni-ext.invalid", 443, "Browsec lv210", 2000, true)
        assertTrue(ConnTrace.snapshot(null).isEmpty())
    }

    @Test
    fun `статистика считает маршруты`() {
        ConnTrace.connect("Браузер", "a.example", 443, "Browsec lv1", 10, true)
        ConnTrace.connect("Браузер", "b.example", 443, "Browsec lv2", 10, true)
        ConnTrace.connect("Браузер", "c.example", 443, "напрямую (обход DPI, IP не меняется)", 10, false)
        val stats = ConnTrace.statsLine()
        assertTrue(stats.contains("соединений 3"))
        assertTrue(stats.contains("ошибок 1"))
        assertTrue(stats.contains("Browsec"))
    }

    @Test
    fun `DNS-запись показывает как имя стало адресом`() {
        ConnTrace.dns("example.com", "93.184.216.34", "DoH через туннель Browsec")
        val line = ConnTrace.snapshot("dns").single()
        assertTrue(line.contains("example.com"))
        assertTrue(line.contains("93.184.216.34"))
    }

    @Test
    fun `очистка обнуляет журнал и статистику`() {
        ConnTrace.connect("Браузер", "a.example", 443, "Browsec lv1", 10, true)
        assertTrue(ConnTrace.snapshot(null).isNotEmpty())
        ConnTrace.clear()
        assertTrue(ConnTrace.snapshot(null).isEmpty())
        assertTrue(ConnTrace.statsLine().contains("пока не было"))
    }

    @Test
    fun `торрент-событие с ошибкой считается неудачным`() {
        ConnTrace.torrent("archive.org/download/...", "WebSeed ответил HTTP 503", false, "сервер занят")
        val line = ConnTrace.snapshot("torrent").single()
        assertTrue(line.contains("503"))
        assertFalse(line.contains("✅"))
        assertEquals(1, ConnTrace.snapshot("err").size)
    }
}
