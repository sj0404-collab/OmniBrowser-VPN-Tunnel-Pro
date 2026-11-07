package com.winwasm64.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
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
 * Совместимо с Android 5.0+ (API 21+):
 *  - API 23+: `ConnectivityManager.bindProcessToNetwork(n)`
 *  - API 21–22: `ConnectivityManager.setProcessDefaultNetwork(n)`
 */
object NetworkBypass {

    @Volatile private var ctx: Context? = null

    /** Обход включён по умолчанию: чужой VPN не должен ломать наш туннель. */
    @Volatile var enabled: Boolean = true

    @Volatile private var cachedNet: Network? = null
    @Volatile private var cachedAt: Long = 0L

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
    }

    /** Сеть без VPN-транспорта: Wi-Fi или мобильная, с доступом в интернет. */
    @Suppress("DEPRECATION")
    private fun pick(): Network? {
        val now = System.currentTimeMillis()
        cachedNet?.let { if (now - cachedAt < 30_000L) return it }
        val app = ctx ?: return null
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Throwable) { null } ?: return null
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
        } catch (_: Throwable) {
        }
        cachedNet = best
        cachedAt = now
        return best
    }

    private fun cm(): ConnectivityManager? {
        val app = ctx ?: return null
        return try {
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Throwable) {
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
        } catch (e: Throwable) {
            lastBindError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** Весь процесс — на физическую сеть (работает там, где bindSocket даёт EPERM). */
    @Suppress("DEPRECATION")
    fun bindProcess(): Boolean {
        if (!enabled) return false
        val manager = cm() ?: return false
        val n = pick() ?: return false
        return try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Api23Net.bindProcess(manager, n)
            } else {
                ConnectivityManager.setProcessDefaultNetwork(n)
            }
            processBound = ok
            if (ok) method = "процесс"
            VpnLogBypass.log(if (ok) "обход чужого VPN: процесс привязан к физической сети" else "обход чужого VPN: привязка процесса вернула false")
            ok
        } catch (e: Throwable) {
            lastBindError = e.message ?: e.javaClass.simpleName
            unsupported = (lastBindError?.contains("EPERM", true) == true)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun unbindProcess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm()?.let { Api23Net.bindProcess(it, null) }
            } else {
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
        } catch (_: Throwable) {}
        processBound = false
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private object Api23Net {
        fun bindProcess(cm: ConnectivityManager, n: Network?): Boolean = cm.bindProcessToNetwork(n)
    }

    /** Держим обход включённым: сокет-привязка → при EPERM привязка процесса. */
    fun ensure(): String {
        if (!enabled) {
            if (processBound) unbindProcess()
            method = "выключен"
            return method
        }
        if (processBound) return method
        val n = pick() ?: return "нет физической сети".also { method = it }
        if (bindProcess()) return method
        method = "сокет"
        return method
    }

    /** HTTP-соединение в обход чужого VPN (для честной проверки IP). null — обход недоступен. */
    fun openConnection(url: String): HttpURLConnection? {
        if (!enabled) return null
        val n = pick() ?: return null
        return try {
            val conn = n.openConnection(URL(url)) as? HttpURLConnection ?: return null
            ModernTls.applyTo(conn)
            conn
        } catch (e: Throwable) {
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
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Throwable) { null }
        val c = try { cm?.getNetworkCapabilities(n) } catch (_: Throwable) { null }
        val kind = when {
            c == null -> "физическая сеть"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "физическая сеть"
        }
        val how = when (method) {
            "процесс" -> "весь процесс привязан к $kind"
            "сокет" -> "наши сокеты привязаны к $kind"
            else -> "способ привязки ещё не выбран"
        }
        val err = lastBindError?.let { " · привязка сокета не разрешена системой ($it)" } ?: ""
        return "обход чужого VPN: включён — $how, минуя VPN-интерфейс$err"
    }
}
