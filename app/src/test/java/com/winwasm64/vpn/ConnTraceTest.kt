package com.winwasm64.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v4.17.0: журнал соединений — «кто, куда, протокол, маршрут, зачем».
 *
 * Проверяем то, на что пользователь смотрит глазами: строки читаемы, фильтры работают,
 * одинаковые записи не забивают журнал, а статистика считает маршруты (Browsec / Psiphon /
 * обход DPI / UDP-релей) — именно по ней видно, что делает туннель.
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
    fun `фильтры разделяют браузер приложения UDP и обход DPI`() {
        ConnTrace.connect("Браузер", "a.example", 443, "Browsec lv1", 10, true)
        ConnTrace.connect("Telegram", "b.example", 443, "Psiphon", 20, true)
        ConnTrace.udp("GeForce NOW", "92.223.105.5:443", "DTLS (игра/звонок) → UDP-релей")
        ConnTrace.connect("Браузер", "c.example", 443, "напрямую (обход DPI, IP не меняется)", 30, true)

        // запись с маршрутом «напрямую (обход DPI)» относится к категории «Обход DPI», а не к браузеру
        assertEquals(1, ConnTrace.snapshot("browser").size)
        assertEquals(1, ConnTrace.snapshot("apps").size)
        assertEquals(1, ConnTrace.snapshot("udp").size)
        assertEquals(1, ConnTrace.snapshot("bypass").size)
        assertTrue(ConnTrace.snapshot("udp").single().contains("UDP"))
        assertTrue(ConnTrace.snapshot("udp").single().contains("DTLS"))
    }

    @Test
    fun `неудачные соединения попадают в ошибки`() {
        ConnTrace.connect("Браузер", "x.example", 443, "Browsec lv1", 5000, false, err = "маршрут не ответил")
        val err = ConnTrace.snapshot("err").single()
        assertTrue(err.contains("❌"))
        assertTrue(err.contains("маршрут не ответил"))
    }

    @Test
    fun `одинаковые записи подряд не дублируются`() {
        repeat(5) { ConnTrace.request("site.ru", "cdn.site.ru", 443, "картинка", false) }
        assertEquals(1, ConnTrace.snapshot("browser").size)
    }

    @Test
    fun `отсечённая реклама видна в журнале как заблокированная`() {
        ConnTrace.request("site.ru", "ads.example", 443, "реклама/трекер — отсечено", true)
        val line = ConnTrace.snapshot(null).single()
        assertTrue(line.contains("заблокировано"))
        assertEquals(1, ConnTrace.snapshot("err").size)
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
}
