package com.winwasm64.vpn

/**
 * v4.16.0: чем на самом деле является UDP-датаграмма.
 *
 * До этой версии прозрачный туннель отвечал ICMP «порт недоступен» на ВЕСЬ UDP 443 и 80.
 * Смысл был верный для браузера (QUIC не проходит через HTTP-прокси, поэтому Chrome/YouTube
 * лучше сразу переводить на TCP), но под ту же гребёнку попадали игры и звонки:
 * GeForce NOW, Discord, WhatsApp и любые WebRTC-звонки стучат в UDP 443 пакетами DTLS/STUN/RTP.
 * Получая «порт недоступен», они не «переходят на TCP» — им просто некуда переходить, и
 * соединение не устанавливается вовсе.
 *
 * Теперь решение принимается по содержимому первого пакета потока (дальше поток идёт по
 * выбранному пути целиком, см. [TransparentTunEngine]):
 *  - QUIC (настоящий HTTP/3) → по-прежнему ICMP «порт недоступен», сайт быстро берёт TCP;
 *  - DTLS / STUN / RTP / прочее → обычный UDP-релей через сокет вне туннеля.
 */
object UdpClassifier {

    const val QUIC = "QUIC"
    const val DTLS = "DTLS"
    const val STUN = "STUN"
    const val RTP = "RTP"
    const val OTHER = "UDP"

    /** Версии QUIC, которые реально встречаются: v1 (RFC 9000) и v2 (RFC 9369). */
    private val QUIC_VERSIONS = setOf(0x00000001L, 0x6b3343cfL)

    /** STUN/TURN magic cookie (RFC 5389). */
    private const val STUN_MAGIC = 0x2112A442L

    fun kind(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0 || off < 0 || off + len > b.size) return OTHER
        val b0 = b[off].toInt() and 0xFF
        // 1. QUIC long header: старший бит 1 и известная версия в следующих 4 байтах.
        if (len >= 5 && (b0 and 0x80) != 0) {
            val version = u32(b, off + 1)
            if (version in QUIC_VERSIONS) return QUIC
        }
        // 2. DTLS: record type 20..25 и версия 0xFE.. (DTLS 1.0/1.2/1.3)
        if (len >= 3 && b0 in 20..25 && (b[off + 1].toInt() and 0xFF) == 0xFE) return DTLS
        // 3. STUN/TURN: два старших бита нули и magic cookie
        if (len >= 8 && (b0 and 0xC0) == 0 && u32(b, off + 4) == STUN_MAGIC) return STUN
        // 4. RTP/RTCP: версия 2 (два старших бита 10)
        if (len >= 12 && (b0 and 0xC0) == 0x80) return RTP
        return OTHER
    }

    /**
     * Только настоящий QUIC: его имеет смысл «выключать» через ICMP, чтобы приложение/браузер
     * перешло на TCP. Всё остальное (игры, звонки, WebRTC) — релеим как есть.
     */
    fun looksLikeQuic(b: ByteArray, off: Int, len: Int): Boolean = kind(b, off, len) == QUIC

    /** Короткое пояснение для журнала. */
    fun title(kind: String): String = when (kind) {
        QUIC -> "QUIC (HTTP/3) → TCP"
        DTLS -> "DTLS (игра/звонок) → UDP-релей"
        STUN -> "STUN/TURN (WebRTC) → UDP-релей"
        RTP -> "RTP (медиа) → UDP-релей"
        else -> "UDP → релей"
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
}
