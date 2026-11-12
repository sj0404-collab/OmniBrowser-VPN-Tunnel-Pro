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
    /** v4.67.0: сеть, к которой реально привязан процесс. Если она пропала — привязка «убивает» весь трафик. */
    @Volatile private var boundNet: Network? = null
    @Volatile private var callbackRegistered = false

    /**
     * v4.13.0: на многих прошивках `Network.bindSocket()` отвечает EPERM (Operation not permitted),
     * и привязать отдельный сокет нельзя. Тогда работает другой путь: `bindProcessToNetwork` —
     * он привязывает весь процесс к физической сети (то же самое делает сам Android для «обхода VPN»).
     */
    @Volatile private var processBound = false
    @Volatile var lastBindError: String? = null
        private set
    @Volatile var method: String = "нет"
        private set

    /** v4.13.1: прошивка запретила и сокет, и процесс — больше не пытаемся, пишем честно один раз. */
    @Volatile var unsupported: Boolean = false
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        registerNetworkCallback()
    }

    /**
     * v4.67.0: «OpenCode Zen: нет соединения: socket failed: ENONET (Machine is not on the network)».
     *
     * Причина: `bindProcessToNetwork()` привязывает ВЕСЬ процесс к конкретной физической сети.
     * Если эта сеть исчезает (переключение Wi-Fi → мобильная, включение/выключение другого VPN,
     * смена вышки), ядро рвёт маршрут, и любой новый сокет падает с ENONET — «машина не в сети»,
     * хотя интернет есть. Раньше привязка жила до перезапуска приложения, и весь трафик стоял.
     * Теперь на любое изменение сети привязка снимается и кэш сети сбрасывается.
     */
    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        val manager = cm() ?: return
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = reset()
                override fun onLost(network: Network) = reset()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // сеть осталась, но потеряла интернет (например, Wi-Fi без выхода) — тоже сбрасываем
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) reset()
                }
            })
            callbackRegistered = true
        } catch (_: Exception) {
        }
    }

    /** Сбросить кэш сети и снять привязку процесса: следующий запрос пойдёт по актуальной сети. */
    fun reset() {
        cachedNet = null
        cachedAt = 0L
        if (processBound) unbindProcess()
        boundNet = null
    }

    /** Физическая сеть без VPN — для тех, кто хочет привязать к ней отдельное соединение. */
    fun physicalNetwork(): Network? = pick()

    /** Привязка процесса ещё жива? Проверяем, что сеть существует и у неё есть интернет. */
    fun isProcessBoundValid(): Boolean {
        if (!processBound) return false
        val n = boundNet ?: return false
        val manager = cm() ?: return false
        val caps = try { manager.getNetworkCapabilities(n) } catch (_: Exception) { null }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            reset()
            return false
        }
        return true
    }

    /** Сеть без VPN-транспорта: Wi-Fi или мобильная, с доступом в интернет. */
    private fun pick(): Network? {
        // v4.69.0: свой VPN (Browsec) — не «обход», а нужный канал. Привязка к физической сети
        // увела бы трафик мимо собственного туннеля, и внешний IP был бы домашним.
        try {
            if (BrowsecVpnService.isRunning) return null
        } catch (_: Throwable) {
        }
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

    private fun cm(): ConnectivityManager? {
        val app = ctx ?: return null
        return try {
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Exception) {
            null
        }
    }

    /** Привязать сокет к физической сети ДО connect(). true — привязка удалась. */
    fun bind(sock: Socket): Boolean {
        if (!enabled) return false
        val n = pick() ?: return false
        return try {
            n.bindSocket(sock)
            method = "сокет"
            true
        } catch (e: Exception) {
            lastBindError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** Весь процесс — на физическую сеть (работает там, где bindSocket даёт EPERM). */
    fun bindProcess(): Boolean {
        if (!enabled) return false
        val manager = cm() ?: return false
        val n = pick() ?: return false
        return try {
            val ok = manager.bindProcessToNetwork(n)
            processBound = ok
            boundNet = if (ok) n else null
            if (ok) method = "процесс"
            VpnLogBypass.log(if (ok) "обход чужого VPN: процесс привязан к физической сети (bindProcessToNetwork)" else "обход чужого VPN: bindProcessToNetwork вернул false")
            ok
        } catch (e: Exception) {
            lastBindError = e.message ?: e.javaClass.simpleName
            unsupported = (lastBindError?.contains("EPERM", true) == true)
            false
        }
    }

    fun unbindProcess() {
        try { cm()?.bindProcessToNetwork(null) } catch (_: Exception) {}
        processBound = false
        boundNet = null
    }

    /** Держим обход включённым: сокет-привязка → при EPERM привязка процесса. */
    fun ensure(): String {
        if (!enabled) {
            if (processBound) unbindProcess()
            method = "выключен"
            return method
        }
        if (processBound && isProcessBoundValid()) return method
        val n = pick() ?: return "нет физической сети".also { method = it }
        // пробуем привязать процесс — это надёжнее на прошивках с EPERM
        if (bindProcess()) return method
        method = "сокет"
        return method
    }

    /** HTTP-соединение в обход чужого VPN (для честной проверки IP). null — обход недоступен. */
    fun openConnection(url: String): HttpURLConnection? {
        if (!enabled) return null
        val n = pick() ?: return null
        return try {
            n.openConnection(URL(url)) as? HttpURLConnection
        } catch (e: Exception) {
            lastBindError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private object VpnLogBypass {
        fun log(msg: String) = com.winwasm64.vpn.VpnLog.i("Обход", msg)
    }

    /** Для журнала и интерфейса: что сейчас с обходом. */
    fun statusLine(): String {
        if (!enabled) return "обход чужого VPN: ВЫКЛ (наш трафик идёт так же, как весь остальной)"
        if (unsupported) return "обход чужого VPN: эта прошивка не разрешает привязку (EPERM) — работаем изолированно, каждый VPN сам по себе"
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
        val how = when (method) {
            "процесс" -> "весь процесс привязан к $kind (bindProcessToNetwork)"
            "сокет" -> "наши сокеты привязаны к $kind"
            else -> "способ привязки ещё не выбран"
        }
        val err = lastBindError?.let { " · привязка сокета не разрешена системой ($it)" } ?: ""
        return "обход чужого VPN: включён — $how, минуя VPN-интерфейс$err"
    }
}
