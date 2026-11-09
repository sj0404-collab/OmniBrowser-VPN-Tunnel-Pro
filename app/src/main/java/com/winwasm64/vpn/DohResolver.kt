package com.winwasm64.vpn

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * v4.35.0: DNS с несколькими независимыми путями.
 *
 * Раньше резолв держался на двух адресах (dns.google и 1.1.1.1). Когда оба перестают отвечать —
 * а в этот день именно так и было, — браузер показывает «нет сети», хотя интернет есть.
 * Здесь восемь DoH-провайдеров плюс обычный UDP-DNS: пока жив хотя бы один путь, имена резолвятся.
 *
 * Порядок: JSON-API (быстро разобрать) → DoH в бинарном формате (так работают Quad9, Mullvad,
 * ControlD, dns.sb) → обычный DNS по UDP. Первый успешный ответ и выигрывает.
 */
object DohResolver {

    /** DoH-эндпоинты в бинарном формате (application/dns-message). */
    private val WIRE_PROVIDERS = listOf(
        "Cloudflare" to "https://cloudflare-dns.com/dns-query",
        "Google" to "https://dns.google/dns-query",
        "Quad9" to "https://dns.quad9.net/dns-query",
        "AdGuard" to "https://dns.adguard-dns.com/dns-query",
        "Mullvad" to "https://dns.mullvad.net/dns-query",
        "ControlD" to "https://freedns.controld.com/p0",
        "dns.sb" to "https://doh.sb/dns-query",
        "Cloudflare-IP" to "https://1.1.1.1/dns-query"
    )

    /** Обычный DNS по UDP — последний рубеж, работает, когда HTTPS-пути режут. */
    private val PLAIN_DNS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private const val CACHE_MS = 10 * 60_000L

    /** Последний провайдер, который ответил — его пробуем первым в следующий раз. */
    @Volatile
    var lastGood: String = ""
        private set

    fun resolve(host: String, timeoutMs: Int = 2200, onTraffic: ((Long, Long) -> Unit)? = null): String? {
        val name = host.trim().lowercase()
        if (name.isEmpty()) return null
        cache[name]?.let { if (System.currentTimeMillis() - it.second < CACHE_MS) return it.first }

        // 1) JSON-API: быстро и без бинарного разбора
        jsonLookup("https://dns.google/resolve?name=$name&type=A", timeoutMs, onTraffic)?.let { return remember(name, it) }
        jsonLookup("https://cloudflare-dns.com/dns-query?name=$name&type=A", timeoutMs, onTraffic)?.let { return remember(name, it) }

        val query = buildQuery(name)

        // 2) бинарный DoH — по порядку, начиная с прошлого удачного
        val ordered = WIRE_PROVIDERS.sortedByDescending { it.first == lastGood }
        for ((label, base) in ordered) {
            try {
                val url = URL("$base?dns=" + base64Url(query))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Accept", "application/dns-message")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.readBytes()
                    onTraffic?.invoke(query.size.toLong(), body.size.toLong())
                    parseFirstA(body)?.let { lastGood = label; return remember(name, it) }
                }
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }

        // 3) обычный DNS по UDP
        for (dns in PLAIN_DNS) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    sock.send(DatagramPacket(query, query.size, InetAddress.getByName(dns), 53))
                    val buf = ByteArray(1500)
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    onTraffic?.invoke(query.size.toLong(), resp.length.toLong())
                    parseFirstA(buf.copyOf(resp.length))?.let { lastGood = "UDP $dns"; return remember(name, it) }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun remember(name: String, ip: String): String {
        cache[name] = Pair(ip, System.currentTimeMillis())
        if (cache.size > 2000) cache.entries.removeIf { System.currentTimeMillis() - it.value.second > CACHE_MS }
        return ip
    }

    /** Разбор JSON-ответа DoH (формат Google/Cloudflare). */
    private fun jsonLookup(url: String, timeoutMs: Int, onTraffic: ((Long, Long) -> Unit)?): String? {
        return try {
            val conn = (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/dns-json")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            onTraffic?.invoke(0L, body.length.toLong())
            val answers = org.json.JSONObject(body).optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                if (ans.optInt("type") == 1) {
                    val ip = ans.optString("data").trim()
                    if (isUsableIpv4(ip)) return ip
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isUsableIpv4(ip: String): Boolean {
        if (!ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
        if (ip.startsWith("127.") || ip == "0.0.0.0" || ip.startsWith("10.")) return false
        return true
    }

    /** DNS-запрос A-записи в бинарном виде. */
    private fun buildQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream(64)
        val id = (0..65535).random()
        out.write((id shr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // рекурсия нужна
        out.write(0x00); out.write(0x01) // вопросов 1
        repeat(6) { out.write(0x00) }
        for (label in name.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return out.toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write(0x00); out.write(0x01) // тип A
        out.write(0x00); out.write(0x01) // класс IN
        return out.toByteArray()
    }

    /** Первый IPv4 из ответа (учитывает сжатие имён и пропускает CNAME). */
    private fun parseFirstA(packet: ByteArray): String? {
        if (packet.size < 12) return null
        val qd = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val an = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        var pos = 12
        repeat(qd) {
            pos = skipName(packet, pos) ?: return null
            pos += 4
        }
        repeat(an) {
            pos = skipName(packet, pos) ?: return null
            if (pos + 10 > packet.size) return null
            val type = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            val rdLength = ((packet[pos + 8].toInt() and 0xFF) shl 8) or (packet[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > packet.size) return null
            if (type == 1 && rdLength == 4) {
                val ip = "${packet[pos].toInt() and 0xFF}.${packet[pos + 1].toInt() and 0xFF}." +
                    "${packet[pos + 2].toInt() and 0xFF}.${packet[pos + 3].toInt() and 0xFF}"
                if (isUsableIpv4(ip)) return ip
            }
            pos += rdLength
        }
        return null
    }

    /** Пропустить имя с учётом указателей сжатия. */
    private fun skipName(packet: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2 // указатель — конец имени
            pos += 1 + len
        }
        return null
    }

    private fun base64Url(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}
