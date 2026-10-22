package com.winwasm64.vpn

/**
 * Минимальный разбор/сборка DNS-сообщений для DNS-форвардера туннеля (без Android-зависимостей, покрыто тестами).
 *
 * A-запросы приложений отвечаем через DoH-резолвер сервиса (как у браузера), AAAA/HTTPS — пустым NOERROR,
 * чтобы приложения шли по IPv4 через туннель, а не по IPv6 мимо него.
 */
object DnsPackets {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    class Question(val id: Int, val name: String, val type: Int, val clazz: Int, val questionEnd: Int)

    fun parseQuestion(q: ByteArray, len: Int): Question? {
        if (len < 17) return null
        val qr = (q[2].toInt() and 0x80) != 0
        val opcode = (q[2].toInt() ushr 3) and 0x0F
        val qd = ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF)
        if (qr || opcode != 0 || qd != 1) return null
        val sb = StringBuilder()
        var p = 12
        var hops = 0
        while (true) {
            if (p >= len) return null
            val l = q[p].toInt() and 0xFF
            if (l == 0) {
                p++
                break
            }
            if ((l and 0xC0) != 0) return null // сжатие в вопросе не используется
            if (p + 1 + l > len || hops++ > 64) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, p + 1, l, Charsets.ISO_8859_1))
            p += 1 + l
        }
        if (p + 4 > len) return null
        val type = ((q[p].toInt() and 0xFF) shl 8) or (q[p + 1].toInt() and 0xFF)
        val clazz = ((q[p + 2].toInt() and 0xFF) shl 8) or (q[p + 3].toInt() and 0xFF)
        val id = ((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF)
        return Question(id, sb.toString().lowercase(), type, clazz, p + 4)
    }

    /** Ответ с A-записями (или пустой NOERROR, если [ipv4s] пуст) на запрос [q]. */
    fun buildResponse(q: ByteArray, question: Question, ipv4s: List<String>, ttlSec: Int = 60): ByteArray {
        val answers = ArrayList<ByteArray>()
        if (question.type == TYPE_A) {
            for (ip in ipv4s) {
                val parts = ip.split('.')
                if (parts.size != 4) continue
                val a = ByteArray(16)
                a[0] = 0xC0.toByte(); a[1] = 0x0C // указатель на имя в вопросе
                a[2] = 0; a[3] = TYPE_A.toByte()
                a[4] = 0; a[5] = 1
                a[6] = (ttlSec ushr 24).toByte(); a[7] = (ttlSec ushr 16).toByte(); a[8] = (ttlSec ushr 8).toByte(); a[9] = ttlSec.toByte()
                a[10] = 0; a[11] = 4
                for (i in 0 until 4) a[12 + i] = (parts[i].toIntOrNull() ?: return buildResponse(q, question, emptyList(), ttlSec)).toByte()
                answers.add(a)
            }
        }
        val total = question.questionEnd + answers.sumOf { it.size }
        val r = ByteArray(total)
        System.arraycopy(q, 0, r, 0, question.questionEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte() // QR=1, RD как в запросе
        r[3] = 0x80.toByte() // RA=1, RCODE=0
        r[6] = (answers.size ushr 8).toByte(); r[7] = answers.size.toByte()
        r[8] = 0; r[9] = 0
        r[10] = 0; r[11] = 0 // без EDNS в ответе
        var p = question.questionEnd
        for (a in answers) {
            System.arraycopy(a, 0, r, p, a.size)
            p += a.size
        }
        return r
    }
}
