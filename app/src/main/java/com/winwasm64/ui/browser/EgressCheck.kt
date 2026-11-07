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

    data class Info(val ip: String = "", val where: String = "", val org: String = "", val error: String? = null) {
        val ok: Boolean get() = ip.isNotBlank()
        fun label(): String = when {
            !ok -> error ?: "нет ответа"
            where.isBlank() -> ip
            else -> "$ip · $where" + (if (org.isNotBlank()) " · $org" else "")
        }
    }

    private const val PRIMARY = "https://ipinfo.io/json"
    private const val FALLBACK = "https://api.ipify.org?format=text"

    /** IP без прокси — «домашний». */
    fun direct(): Info {
        val info = fetch(useProxy = false)
        VpnLog.i("IP", "напрямую: " + info.label())
        return info
    }

    /** IP через локальный прокси браузера (тот, куда смотрит WebView и VPN). */
    fun viaBrowserProxy(): Info {
        val info = fetch(useProxy = true)
        VpnLog.i("IP", "через VPN/прокси: " + info.label())
        return info
    }

    private fun fetch(useProxy: Boolean): Info {
        try {
            val text = if (useProxy) {
                MediaGrabber.getText(PRIMARY, emptyMap(), 8192)
            } else {
                readText(PRIMARY)
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

    private fun readText(url: String): String {
        val c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
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
            where = o.optString("country").trim().ifBlank { o.optString("region").trim() },
            org = o.optString("org").trim()
        )
    } catch (_: Exception) {
        null
    }
}
