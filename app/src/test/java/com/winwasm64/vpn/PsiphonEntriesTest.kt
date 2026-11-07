package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.15.0: проверка подсчёта пригодных записей встроенного списка серверов Psiphon.
 *
 * Формат записи: одна строка = одна запись, строка целиком в hex, после раскодирования —
 * четыре поля через пробел и JSON с "ipAddress". Если считать просто переводы строк,
 * «встроенных серверов: 429» показывается даже когда ни одна запись не разбирается,
 * и в журнале непонятно, почему Psiphon не подключается.
 */
class PsiphonEntriesTest {

    private fun entryLine(ip: String): String {
        val text = "0 0 0 0 {\"ipAddress\":\"$ip\",\"webServerPort\":\"443\"," +
            "\"webServerSecret\":\"00ff00ff00ff00ff\",\"region\":\"DE\",\"capabilities\":[\"SSH\",\"OSSH\"]}"
        return text.toByteArray(Charsets.UTF_8).joinToString("") { String.format("%02x", it) }
    }

    @Test
    fun `считает только настоящие записи`() {
        val list = entryLine("1.2.3.4") + "\n" + entryLine("5.6.7.8") + "\n"
        assertEquals(2, PsiphonEntries.count(list))
    }

    @Test
    fun `мусор и пустые строки не считаются`() {
        val list = "\n\nне запись\n" + "0 0 0 0 {".toByteArray().joinToString("") { String.format("%02x", it) } + "\n"
        assertEquals(0, PsiphonEntries.count(list))
    }

    @Test
    fun `строка записи распознаётся, обычный hex без ipAddress нет`() {
        assertTrue(PsiphonEntries.isEntryLine(entryLine("9.9.9.9")))
        val hexOnly = "abcdef0123456789".repeat(6)
        assertFalse(PsiphonEntries.isEntryLine(hexOnly))
        assertFalse(PsiphonEntries.isEntryLine(""))
    }
}
