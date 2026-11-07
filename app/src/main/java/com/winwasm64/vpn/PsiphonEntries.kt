package com.winwasm64.vpn

/**
 * v4.15.0: разбор встроенного списка серверов Psiphon (`assets/psiphon/servers.txt`).
 *
 * Формат записи в psiphon-tunnel-core (`protocol.NewStreamingServerEntryDecoder`):
 * одна строка = одна запись, строка целиком в hex, после раскодирования —
 * четыре поля через пробел и JSON сервера с полем `ipAddress`.
 *
 * Зачем считать именно так: раньше в журнал писалось `count { it == '\n' }`, то есть
 * число переводов строк. Оно совпадало с числом записей только если записи разбираются,
 * и «встроенных серверов: 429» ничего не говорило о том, есть ли у Psiphon хоть один
 * пригодный кандидат. Теперь в журнале видно реальное число пригодных записей.
 */
internal object PsiphonEntries {

    fun count(embedded: String): Int = embedded.lineSequence().count { isEntryLine(it) }

    fun isEntryLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 40 || t.length % 2 != 0) return false
        if (t.any { it !in "0123456789abcdefABCDEF" }) return false
        val raw = try {
            hexToBytes(t)
        } catch (_: Throwable) {
            return false
        }
        val text = String(raw, Charsets.UTF_8)
        // четыре legacy-поля и JSON сервера
        return text.count { it == ' ' } >= 4 && text.contains("\"ipAddress\"")
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((hexVal(s[i * 2]) shl 4) or hexVal(s[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("не hex: $c")
    }
}
