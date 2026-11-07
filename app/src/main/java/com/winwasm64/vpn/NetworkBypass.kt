package com.winwasm64.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * v4.12.0: «работать в обход чужого VPN».
 *
 * Если на телефоне включён ЕЩЁ ОДИН VPN (не наш), он забирает себе всю сеть приложения, и наши сокеты
 * уходят сначала в него, а потом уже в Browsec: два прыжка, «двойной» IP, а проверка IP показывает
 * не домашний адрес, а выход чужого VPN.
 *
 * Лечится так же, как это делает сам `VpnService.protect()`: сокет привязывается к ФИЗИЧЕСКОЙ сети
 * (Wi-Fi/мобильная) до `connect()`, и ядро ведёт его мимо VPN-интерфейса. Тот же приём для HTTP-проверки IP —
 * через `Network.openConnection()`.
 */
object NetworkBypass {

    @Volatile private var ctx: Context? = null

    /** Обход включён по умолчанию: чужой VPN не должен ломать наш туннель. */
    @Volatile var enabled: Boolean = true

    @Volatile private var cachedNet: Network? = null
    @Volatile private var cachedAt: Long = 0L

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    /** Сеть без VPN-транспорта: Wi-Fi или мобильная, с доступом в интернет. */
    private fun pick(): Network? {
        val now = System.currentTimeMillis()
        cachedNet?.let { if (now - cachedAt < 30_000L) return it }
        val app = ctx ?: return null
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Exception) { null } ?: return null
        var best: Network? = null
        try {
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val physical = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!physical) continue
                // предпочитаем Wi-Fi, иначе мобильную
                if (best == null || c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) best = n
            }
        } catch (_: Exception) {
        }
        cachedNet = best
        cachedAt = now
        return best
    }

    /** Привязать сокет к физической сети ДО connect(). true — привязка удалась. */
    fun bind(sock: Socket): Boolean {
        if (!enabled) return false
        val n = pick() ?: return false
        return try {
            n.bindSocket(sock)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** HTTP-соединение в обход чужого VPN (для честной проверки IP). null — обход недоступен. */
    fun openConnection(url: String): HttpURLConnection? {
        if (!enabled) return null
        val n = pick() ?: return null
        return try {
            n.openConnection(URL(url)) as? HttpURLConnection
        } catch (_: Exception) {
            null
        }
    }

    /** Для журнала и интерфейса: что сейчас с обходом. */
    fun statusLine(): String {
        if (!enabled) return "обход чужого VPN: ВЫКЛ (наш трафик идёт так же, как весь остальной)"
        val n = pick() ?: return "обход чужого VPN: включён, но физической сети не нашлось — идём через VPN-интерфейс"
        val app = ctx ?: return "обход чужого VPN: включён"
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Exception) { null }
        val c = try { cm?.getNetworkCapabilities(n) } catch (_: Exception) { null }
        val kind = when {
            c == null -> "физическая сеть"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "физическая сеть"
        }
        return "обход чужого VPN: включён — наши сокеты идут через $kind, минуя VPN-интерфейс"
    }
}
