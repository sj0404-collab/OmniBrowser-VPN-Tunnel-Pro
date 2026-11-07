package com.winwasm64.ui.browser

import com.winwasm64.vpn.VpnLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * v4.11.0: честная проверка «какой у меня IP» — без прокси и через VPN/прокси браузера.
 *
 * v4.12.1: запрос «без прокси» по умолчанию идёт В ОБХОД чужого VPN (сокет привязан к физической сети),
 * пробуется несколько сервисов, и в результат пишется, каким путём получен ответ и почему не вышло,
 * если не вышло. Без этого на экране оставался адрес чужого VPN без единого намёка на причину.
 */
object EgressCheck {

    data class Info(
        val ip: String = "",
        val where: String = "",
        val org: String = "",
        val error: String? = null,
        val hosting: Boolean = false,
        /** Каким путём получен ответ: «в обход чужого VPN», «напрямую», «через VPN/прокси». */
        val source: String = ""
    ) {
        val ok: Boolean get() = ip.isNotBlank()
        fun label(): String = when {
            !ok -> error ?: "нет ответа"
            else -> ip + (if (where.isNotBlank()) " · $where" else "") + (if (org.isNotBlank()) " · $org" else "")
        }
    }

    private val ENDPOINTS = listOf(
        "https://ipinfo.io/json",
        "http://ip-api.com/json/?fields=status,countryCode,country,isp,query",
        "https://api.ipify.org?format=text"
    )

    /**
     * «Домашний» IP: сначала в обход чужого VPN (иначе виден выход чужого VPN), при неудаче — обычным путём,
     * и тогда в [Info.source] честно написано, что обход не сработал.
     */
    fun direct(): Info {
        val bypassed = fetch(useProxy = false, bypassVpn = true)
        if (bypassed.ok) {
            VpnLog.i("IP", "без прокси (в обход чужого VPN): " + bypassed.label())
            return bypassed
        }
        val plain = fetch(useProxy = false, bypassVpn = false)
        val out = plain.copy(
            error = if (plain.ok) null else ("обход не сработал: ${bypassed.error ?: "нет ответа"}; напрямую: ${plain.error ?: "нет ответа"}"),
            source = if (plain.ok) "напрямую (обход чужого VPN не удался: ${bypassed.error ?: "нет ответа"})" else plain.source
        )
        VpnLog.w("IP", "без прокси: " + out.label() + " · " + out.source)
        return out
    }

    /** IP через локальный прокси браузера (тот, куда смотрят WebView и VPN). */
    fun viaBrowserProxy(): Info {
        val info = fetch(useProxy = true)
        VpnLog.i("IP", "через VPN/прокси: " + info.label())
        return info
    }

    private fun fetch(useProxy: Boolean, bypassVpn: Boolean = false): Info {
        val source = when {
            useProxy -> "через VPN/прокси"
            bypassVpn -> "в обход чужого VPN"
            else -> "напрямую"
        }
        val errors = ArrayList<String>()
        for (url in ENDPOINTS) {
            try {
                val text = if (useProxy) MediaGrabber.getText(url, emptyMap(), 8192) else readText(url, bypassVpn)
                val parsed = parse(url, text)
                if (parsed != null && parsed.ip.isNotBlank()) return parsed.copy(source = source)
                errors += "${hostOf(url)}: пустой ответ"
            } catch (e: Exception) {
                errors += "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return Info(error = errors.joinToString("; "), source = source)
    }

    private fun hostOf(url: String): String = try { URL(url).host } catch (_: Exception) { url }

    private fun readText(url: String, bypassVpn: Boolean = false): String {
        val bypassed = if (bypassVpn) com.winwasm64.vpn.NetworkBypass.openConnection(url) else null
        val c = bypassed ?: (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection)
        return try {
            c.connectTimeout = if (bypassVpn) 8_000 else 12_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", MediaGrabber.DEFAULT_UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Разбор ответа любого из сервисов: ipinfo.io, ip-api.com, ipify. */
    private fun parse(url: String, text: String): Info? {
        val body = text.trim()
        if (!body.startsWith("{") && !body.startsWith("[")) {
            // ipify отдаёт просто адрес
            return if (body.length in 7..45 && body.none { it.isWhitespace() }) Info(ip = body) else null
        }
        return try {
            val o = JSONObject(body)
            val ip = o.optString("ip").ifBlank { o.optString("query") }.trim()
            if (ip.isBlank()) return null
            val countryCode = o.optString("country").ifBlank { o.optString("countryCode") }.trim()
            val org = o.optString("org").ifBlank { o.optString("isp") }.trim()
            Info(
                ip = ip,
                where = countryRu(countryCode),
                org = cleanOrg(org),
                hosting = looksLikeHosting(org + " " + o.optString("hostname"))
            )
        } catch (_: Exception) {
            null
        }
    }

    /** «Sabiedrība ar ierobežotu atbildību DELSKA Latvia» → «DELSKA». */
    private fun cleanOrg(raw: String): String {
        var s = raw
        for (junk in listOf(
            "Sabiedrība ar ierobežotu atbildību", "Sabiedriba ar ierobezotu atbildibu",
            "SIA ", "LLC ", "Ltd ", "GmbH ", "Inc. ", "Inc "
        )) s = s.replace(junk, " ")
        s = s.replace(Regex("\\bAS\\d{3,8}\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Коды стран → по-русски (частые у серверов Browsec). */
    private fun countryRu(code: String): String = when (code.uppercase()) {
        "LV" -> "Латвия"; "LT" -> "Литва"; "EE" -> "Эстония"; "DE" -> "Германия"; "NL" -> "Нидерланды"
        "FR" -> "Франция"; "GB", "UK" -> "Британия"; "US" -> "США"; "SE" -> "Швеция"; "FI" -> "Финляндия"
        "PL" -> "Польша"; "CH" -> "Швейцария"; "SG" -> "Сингапур"; "JP" -> "Япония"; "CA" -> "Канада"
        "IT" -> "Италия"; "ES" -> "Испания"; "TR" -> "Турция"; "RO" -> "Румыния"; "CZ" -> "Чехия"
        "UA" -> "Украина"; "KZ" -> "Казахстан"; else -> code
    }

    /**
     * Похоже на дата-центр/хостинг, а не на мобильного оператора. Если «домашний» IP такой —
     * значит на телефоне (или на роутере) включён ЕЩЁ ОДИН VPN, и путаница «какой VPN сейчас работает» идёт оттуда.
     */
    fun looksLikeHosting(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "host", "vpn", "cloud", "server", "datacenter", "data center", "colo", "vps", "dedicated",
            "gthost", "vultr", "digitalocean", "hetzner", "ovh", "m247", "datacamp", "choopa", "leaseweb",
            "contabo", "linode", "amazon", "google", "microsoft", "azure", "oracle", "aeza", "timeweb"
        ).any { t.contains(it) }
    }

    // ---- кэш «IP через VPN» для строки состояния (без запроса на каждый кадр) ----

    @Volatile private var cachedVpn: Info? = null
    @Volatile private var cachedVpnAt: Long = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Последний известный IP через VPN; если данных нет/устарели — обновляет в фоне. */
    fun cachedVpnIp(): Info? {
        if (System.currentTimeMillis() - cachedVpnAt > 120_000L && refreshing.compareAndSet(false, true)) {
            Thread({
                try {
                    cachedVpn = viaBrowserProxy()
                } catch (_: Exception) {
                } finally {
                    cachedVpnAt = System.currentTimeMillis()
                    refreshing.set(false)
                }
            }, "egress-cache").apply { isDaemon = true; start() }
        }
        return cachedVpn
    }
}
