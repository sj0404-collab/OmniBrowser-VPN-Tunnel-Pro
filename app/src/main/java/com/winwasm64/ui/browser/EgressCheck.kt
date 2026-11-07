package com.winwasm64.ui.browser

import com.winwasm64.vpn.VpnLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * v4.11.0: честная проверка «какой у меня IP» — напрямую и через VPN/прокси браузера.
 *
 * Зачем: в приложении нигде не было видно внешний IP, поэтому «меняется он или нет» приходилось гадать.
 * Здесь два независимых запроса (без прокси и через локальный прокси браузера), результат сравнивается
 * в [com.winwasm64.vpn.VpnScope.ipCompare].
 */
object EgressCheck {

    data class Info(val ip: String = "", val where: String = "", val org: String = "", val error: String? = null, val hosting: Boolean = false) {
        val ok: Boolean get() = ip.isNotBlank()
        fun label(): String = when {
            !ok -> error ?: "нет ответа"
            where.isBlank() -> ip
            else -> "$ip · $where" + (if (org.isNotBlank()) " · $org" else "")
        }
    }

    private const val PRIMARY = "https://ipinfo.io/json"
    private const val FALLBACK = "https://api.ipify.org?format=text"

    /**
     * IP без прокси — «домашний». v4.12.0: сначала пробуем в ОБХОД чужого VPN
     * (сокет/соединение привязано к физической сети), иначе показывался бы выход чужого VPN.
     */
    fun direct(): Info {
        val bypassed = fetch(useProxy = false, bypassVpn = true)
        val info = if (bypassed.ok) bypassed else fetch(useProxy = false)
        VpnLog.i("IP", "без прокси: " + info.label() + (if (bypassed.ok) " (в обход чужого VPN)" else ""))
        return info
    }

    /** IP через локальный прокси браузера (тот, куда смотрит WebView и VPN). */
    fun viaBrowserProxy(): Info {
        val info = fetch(useProxy = true)
        VpnLog.i("IP", "через VPN/прокси: " + info.label())
        return info
    }

    private fun fetch(useProxy: Boolean, bypassVpn: Boolean = false): Info {
        try {
            val text = if (useProxy) {
                MediaGrabber.getText(PRIMARY, emptyMap(), 8192)
            } else {
                readText(PRIMARY, bypassVpn)
            }
            parse(text)?.let { return it }
        } catch (e: Exception) {
            VpnLog.w("IP", "ipinfo.io не ответил (${e.message})")
        }
        // запасной сервис — только адрес
        return try {
            val ip = (if (useProxy) MediaGrabber.getText(FALLBACK, emptyMap(), 4096) else readText(FALLBACK)).trim()
            if (ip.isNotBlank()) Info(ip = ip) else Info(error = "пустой ответ")
        } catch (e: Exception) {
            Info(error = e.message ?: "нет ответа")
        }
    }

    private fun readText(url: String, bypassVpn: Boolean = false): String {
        val c = (if (bypassVpn) com.winwasm64.vpn.NetworkBypass.openConnection(url) else null)
            ?: (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection)
        return try {
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", MediaGrabber.DEFAULT_UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: String): Info? = try {
        val o = JSONObject(json)
        val ip = o.optString("ip").trim()
        if (ip.isBlank()) null else Info(
            ip = ip,
            where = countryRu(o.optString("country").trim().ifBlank { o.optString("region").trim() }),
            org = cleanOrg(o.optString("org").trim()),
            hosting = looksLikeHosting(o.optString("org") + " " + o.optString("hostname"))
        )
    } catch (_: Exception) {
        null
    }

    /** «Sabiedrība ar ierobežotu atbildību DELSKA Latvia» → «DELSKA». */
    private fun cleanOrg(raw: String): String {
        var s = raw
        for (junk in listOf(
            "Sabiedrība ar ierobežotu atbildību", "Sabiedriba ar ierobezotu atbildibu",
            "SIA ", "LLC ", "Ltd ", "GmbH ", "Inc. ", "Inc "
        )) s = s.replace(junk, " ")
        s = s.replace(Regex("\\bAS\\d{3,8}\\b"), " ")
        s = s.replace(Regex("\\bAS\\d{3,8}\\b"), " ").replace(Regex("\\s+"), " ").trim()
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
     * значит на телефоне включён ЕЩЁ ОДИН VPN (не OmniBrowser), и путаница «какой VPN сейчас работает» идёт оттуда.
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
