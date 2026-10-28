package com.winwasm64.vpn

import java.util.Locale

/**
 * v4.10.7: диагностика UDP-потока WebRTC (STUN / DTLS / RTP) в «VPN для приложений».
 *
 * Нужна для сравнения GeForce NOW в обычном Chrome (через наш VPN) и во встроенном браузере (WebView):
 * видно на уровне датаграмм, доходит ли DTLS-рукопожатие — сколько ушло ClientHello, какого размера,
 * предлагается ли DTLS 1.3 / пост-квантовый ключ ML-KEM, что и когда ответил сервер.
 * Пишет в журнал только потоки, где встретились STUN или DTLS (обычный игровой/голосовой UDP не трогает).
 */
class UdpFlowProbe(private val remote: String, private val owner: () -> String) {
    private val createdAt = System.currentTimeMillis()
    private var webrtc = false
    private var who: String? = null

    private var txPkts = 0
    private var rxPkts = 0
    private var txBytes = 0L
    private var rxBytes = 0L
    private var stunReqTx = 0
    private var stunRespRx = 0
    private var stunErrRx = 0
    private var stunReqRx = 0
    private var stunRespTx = 0
    private var dtlsTx = 0
    private var dtlsRx = 0
    private var dtlsRxBytes = 0L
    private var rtpRx = 0
    private var rtpTx = 0
    private var firstStunAt = 0L
    private var firstStunRespAt = 0L
    private var firstDtlsTxAt = 0L
    private var firstDtlsRxAt = 0L
    private var lastRxAt = 0L
    private var lastDtlsTxAt = 0L
    private var dtlsRxLogged = 0
    private var stage = 0

    // Сборка ClientHello из фрагментов (BoringSSL дробит рукопожатие по MTU ~1200 Б)
    private var chBuf: ByteArray? = null
    private var chGot = 0
    private var chFrags = 0
    private var chSizes = StringBuilder()
    private var chLogged = false
    private var chRetx = 0
    private var chSummary: String? = null

    private fun t(ms: Long): String = String.format(Locale.US, "%.1f с", ms / 1000.0)
    private fun kb(b: Long): String = if (b >= 1048576) String.format(Locale.US, "%.1f МБ", b / 1048576.0) else String.format(Locale.US, "%.1f КБ", b / 1024.0)

    private fun label(): String {
        val w = who ?: owner().also { who = it }
        return "📡 UDP $w → $remote"
    }

    @Synchronized
    fun onTx(b: ByteArray, off: Int, len: Int) {
        txPkts++; txBytes += len
        val now = System.currentTimeMillis()
        when (classify(b, off, len)) {
            K_STUN_REQ -> { stunReqTx++; if (firstStunAt == 0L) firstStunAt = now; webrtc = true }
            K_STUN_RESP, K_STUN_ERR -> { stunRespTx++; webrtc = true }
            K_STUN_IND -> webrtc = true
            K_DTLS -> {
                dtlsTx++; webrtc = true; lastDtlsTxAt = now
                if (firstDtlsTxAt == 0L) firstDtlsTxAt = now
                if ((b[off].toInt() and 0xFF) == 22) collectClientHello(b, off, len)
            }
            K_RTP -> rtpTx++
            else -> {}
        }
    }

    @Synchronized
    fun onRx(b: ByteArray, off: Int, len: Int) {
        rxPkts++; rxBytes += len
        val now = System.currentTimeMillis()
        lastRxAt = now
        when (classify(b, off, len)) {
            K_STUN_REQ -> { stunReqRx++; webrtc = true }
            K_STUN_RESP -> { stunRespRx++; webrtc = true; if (firstStunRespAt == 0L) firstStunRespAt = now }
            K_STUN_ERR -> { stunErrRx++; webrtc = true }
            K_STUN_IND -> webrtc = true
            K_DTLS -> {
                dtlsRx++; dtlsRxBytes += len; webrtc = true
                if (firstDtlsRxAt == 0L) firstDtlsRxAt = now
                if (dtlsRxLogged < 3) {
                    dtlsRxLogged++
                    val since = if (firstDtlsTxAt > 0) " · через ${t(now - firstDtlsTxAt)} после нашего ClientHello" else ""
                    VpnLog.i("Прил", "${label()} · DTLS от сервера: ${describeDtls(b, off, len)} ($len Б)$since")
                }
            }
            K_RTP -> rtpRx++
            else -> {}
        }
    }

    /** Вызывается периодически (из потока приёма, раз в ≤1 с): отчёты через 3 / 10 / 30 с после первого STUN. */
    @Synchronized
    fun tick() {
        if (!webrtc || firstStunAt == 0L) return
        val age = System.currentTimeMillis() - firstStunAt
        val due = when (stage) { 0 -> 3_000L; 1 -> 10_000L; 2 -> 30_000L; else -> Long.MAX_VALUE }
        if (age >= due) {
            stage++
            report("через ${t(age)}")
        }
    }

    @Synchronized
    fun onClose() {
        if (!webrtc) return
        report("поток закрыт через ${t(System.currentTimeMillis() - createdAt)}")
    }

    private fun report(head: String) {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append(label()).append(" · ").append(head)
        sb.append(" · ↑ ").append(txPkts).append(" пак/").append(kb(txBytes))
        sb.append(" ↓ ").append(rxPkts).append(" пак/").append(kb(rxBytes))
        sb.append(" · STUN: наших запросов ").append(stunReqTx).append(" → ответов ").append(stunRespRx)
        if (stunErrRx > 0) sb.append(" (ошибок ").append(stunErrRx).append(')')
        sb.append(", запросов от сервера ").append(stunReqRx).append(" → наших ответов ").append(stunRespTx)
        sb.append(" · DTLS: ↑ ").append(dtlsTx).append(" пак")
        if (chRetx > 0) sb.append(" (ClientHello повторён ×").append(chRetx).append(')')
        sb.append(" ↓ ").append(dtlsRx).append(" пак/").append(kb(dtlsRxBytes))
        sb.append(" · RTP/медиа ↓ ").append(rtpRx).append(" ↑ ").append(rtpTx)
        if (lastRxAt > 0) sb.append(" · последний пакет от сервера ").append(t(now - lastRxAt)).append(" назад") else sb.append(" · от сервера ничего не приходило")
        if (chSummary == null && chBuf != null) sb.append(" · ClientHello собран частично: ").append(chGot).append('/').append(chBuf!!.size).append(" Б из ").append(chFrags).append(" фрагментов")
        VpnLog.i("Прил", sb.toString())
        if (!chLogged && chSummary != null) { chLogged = true; VpnLog.i("Прил", "${label()} · $chSummary") }
    }

    // ---------------- DTLS ClientHello ----------------

    private fun collectClientHello(b: ByteArray, off: Int, len: Int) {
        if (len < 25) return
        val hsType = b[off + 13].toInt() and 0xFF
        if (hsType != 1) return
        val total = u24(b, off + 14)
        val fragOff = u24(b, off + 19)
        val fragLen = u24(b, off + 22)
        if (total <= 0 || total > 65536 || fragOff + fragLen > total || 25 + fragLen > len) return
        if (fragOff == 0 && chGot > 0) {
            // Повторная отправка ClientHello (ответа нет)
            chRetx++
            if (chBuf != null && chGot >= total) return
            chBuf = null; chGot = 0; chFrags = 0; chSizes = StringBuilder()
        }
        val buf = chBuf ?: ByteArray(total).also { chBuf = it }
        if (buf.size != total) return
        System.arraycopy(b, off + 25, buf, fragOff, fragLen)
        chGot += fragLen
        chFrags++
        if (chSizes.isNotEmpty()) chSizes.append('+')
        chSizes.append(len)
        if (chGot >= total && chSummary == null) {
            chSummary = "наш DTLS ClientHello: $total Б в $chFrags датаграммах ($chSizes Б) · ${parseClientHello(buf)}"
            VpnLog.i("Прил", "${label()} · ICE: STUN ответов ${stunRespRx}/${stunReqTx}, запросов от сервера $stunReqRx → отправляем $chSummary")
            chLogged = true
        }
    }

    /** Версии и группы ключей из ClientHello: отличает DTLS 1.2 (как Chromium 127 в Lemur) от DTLS 1.3 + ML-KEM (свежий Chromium/WebView). */
    private fun parseClientHello(h: ByteArray): String {
        try {
            var p = 0
            val legacy = ((h[p].toInt() and 0xFF) shl 8) or (h[p + 1].toInt() and 0xFF); p += 2
            p += 32 // random
            p += 1 + (h[p].toInt() and 0xFF) // session id
            p += 1 + (h[p].toInt() and 0xFF) // cookie
            val csLen = u16(h, p); p += 2 + csLen
            p += 1 + (h[p].toInt() and 0xFF) // compression
            if (p + 2 > h.size) return "DTLS ${verName(legacy)} (без расширений)"
            val extLen = u16(h, p); p += 2
            val end = minOf(h.size, p + extLen)
            var v13 = false
            var useSrtp = false
            val groups = ArrayList<String>()
            val shares = ArrayList<String>()
            while (p + 4 <= end) {
                val type = u16(h, p); val l = u16(h, p + 2); p += 4
                if (p + l > end) break
                when (type) {
                    43 -> { // supported_versions
                        var q = p + 1
                        while (q + 2 <= p + l) { val v = u16(h, q); if (v == 0xFEFC) v13 = true; q += 2 }
                    }
                    14 -> useSrtp = true
                    10 -> { // supported_groups
                        var q = p + 2
                        while (q + 2 <= p + l) { groups.add(groupName(u16(h, q))); q += 2 }
                    }
                    51 -> { // key_share
                        var q = p + 2
                        while (q + 4 <= p + l) { val g = u16(h, q); val gl = u16(h, q + 2); shares.add(groupName(g) + "=" + gl + " Б"); q += 4 + gl }
                    }
                }
                p += l
            }
            val sb = StringBuilder()
            sb.append(if (v13) "предлагает DTLS 1.3 (и 1.2)" else "DTLS ${verName(legacy)}, без 1.3")
            if (shares.isNotEmpty()) sb.append(" · key_share: ").append(shares.joinToString(", "))
            if (groups.isNotEmpty()) sb.append(" · группы: ").append(groups.take(5).joinToString(","))
            sb.append(if (useSrtp) " · use_srtp есть" else " · use_srtp НЕТ")
            return sb.toString()
        } catch (_: Throwable) {
            return "разобрать не удалось"
        }
    }

    private fun verName(v: Int): String = when (v) { 0xFEFD -> "1.2"; 0xFEFF -> "1.0"; 0xFEFC -> "1.3"; else -> String.format(Locale.US, "0x%04X", v) }

    private fun groupName(g: Int): String = when (g) {
        0x11EC -> "X25519MLKEM768"; 0x6399 -> "X25519Kyber768"; 0x001D -> "X25519"; 0x0017 -> "P-256"; 0x0018 -> "P-384"; 0x0019 -> "P-521"
        else -> String.format(Locale.US, "0x%04X", g)
    }

    private fun describeDtls(b: ByteArray, off: Int, len: Int): String {
        val b0 = b[off].toInt() and 0xFF
        if (b0 in 32..63) return "зашифрованная запись DTLS 1.3"
        val ver = if (len >= 3) verName(((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)) else "?"
        return when (b0) {
            20 -> "ChangeCipherSpec (запись $ver)"
            21 -> "ALERT (запись $ver)" + (if (len >= 15) " уровень ${b[off + 13].toInt() and 0xFF}, код ${b[off + 14].toInt() and 0xFF}" else "")
            22 -> {
                if (len < 25) "рукопожатие (обрезано)" else {
                    val hs = b[off + 13].toInt() and 0xFF
                    val name = when (hs) {
                        1 -> "ClientHello"; 2 -> "ServerHello"; 3 -> "HelloVerifyRequest"; 11 -> "Certificate"; 12 -> "ServerKeyExchange"
                        13 -> "CertificateRequest"; 14 -> "ServerHelloDone"; 15 -> "CertificateVerify"; 16 -> "ClientKeyExchange"; 20 -> "Finished"
                        else -> "рукопожатие тип $hs"
                    }
                    "$name (запись $ver, сообщение ${u24(b, off + 14)} Б, фрагмент ${u24(b, off + 19)}+${u24(b, off + 22)})"
                }
            }
            23 -> "данные приложения (запись $ver)"
            else -> "запись типа $b0"
        }
    }

    companion object {
        private const val K_OTHER = 0
        private const val K_STUN_REQ = 1
        private const val K_STUN_RESP = 2
        private const val K_STUN_ERR = 3
        private const val K_STUN_IND = 4
        private const val K_DTLS = 5
        private const val K_RTP = 6

        private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        private fun u24(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)

        /** Классификация датаграммы по первому байту (RFC 7983): STUN 0–3, DTLS 20–63, RTP/RTCP 128–191. */
        fun classify(b: ByteArray, off: Int, len: Int): Int {
            if (len < 4) return K_OTHER
            val b0 = b[off].toInt() and 0xFF
            if (b0 < 4) {
                if (len < 20 || b[off + 4] != 0x21.toByte() || b[off + 5] != 0x12.toByte() || b[off + 6] != 0xA4.toByte() || b[off + 7] != 0x42.toByte()) return K_OTHER
                return when (((b0 shl 8) or (b[off + 1].toInt() and 0xFF)) and 0x0110) {
                    0x0000 -> K_STUN_REQ
                    0x0100 -> K_STUN_RESP
                    0x0110 -> K_STUN_ERR
                    else -> K_STUN_IND
                }
            }
            if (b0 in 20..23) return if (len >= 13 && (b[off + 1].toInt() and 0xFF) == 0xFE) K_DTLS else K_OTHER
            if (b0 in 32..63) return K_DTLS
            if (b0 in 128..191) return K_RTP
            return K_OTHER
        }
    }
}
