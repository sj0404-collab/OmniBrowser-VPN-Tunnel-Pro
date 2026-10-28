package com.winwasm64.extensions

import android.content.Context

/**
 * v4.10.1: сайты, которые идут мимо VPN браузера (напрямую), даже если PAC расширения велит через прокси.
 * Нужно для стриминга игр (GeForce NOW: сессия создаётся по HTTPS, а поток — WebRTC/UDP напрямую; если HTTPS-сторона
 * видит IP дата-центра Browsec, а поток приходит с домашнего IP, сервер отказывает — код 0x00000157; к тому же
 * сигналинг идёт на IP-адрес:порт вроде 49100, куда бесплатные прокси CONNECT не пускают).
 * Встроенный список + пользовательский (меню → «Сайт напрямую, без VPN браузера»).
 */
object DirectSites {
    private const val PREFS = "omni_browser_v21_prefs"
    private const val KEY = "direct_sites"

    /** Встроенные: GeForce NOW и серверы NVIDIA. */
    private val builtIn = setOf("geforcenow.com", "nvidiagrid.net", "nvidia.com", "gfn.run")

    @Volatile private var user: Set<String> = emptySet()
    @Volatile private var appContext: Context? = null
    private val logged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        appContext = context.applicationContext
        user = try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
    }

    /** Ключ сайта: домен второго уровня (или третьего для com.ua / co.uk и т.п.). */
    fun siteKey(host: String?): String {
        val h = (host ?: "").lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.all { it.isDigit() || it == '.' } || h.contains(':')) return ""
        val parts = h.split('.')
        if (parts.size < 2) return ""
        return if (parts.size >= 3 && parts[parts.size - 2].length <= 3 && parts.last().length <= 2) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    fun isBuiltIn(site: String): Boolean = site in builtIn
    fun isUser(site: String): Boolean = site in user

    /** Идти ли к host:port напрямую. IP-адрес с нестандартным портом — это сигналинг/стрим (игры, звонки), тоже напрямую. */
    fun isDirect(host: String, port: Int): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h.all { it.isDigit() || it == '.' }) return port != 80 && port != 443
        for (d in builtIn) if (h == d || h.endsWith(".$d")) return true
        val u = user
        for (d in u) if (h == d || h.endsWith(".$d")) return true
        return false
    }

    fun note(host: String, port: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val k = "$host:$port"
        val prev = logged[k] ?: 0L
        if (now - prev > 120_000L) {
            logged[k] = now
            com.winwasm64.vpn.VpnLog.i("Браузер", "$k → напрямую, минуя VPN браузера (сайт из списка «напрямую»)")
        }
    }

    fun set(context: Context, site: String, direct: Boolean) {
        if (site.isEmpty()) return
        val next = user.toMutableSet()
        if (direct) next.add(site) else next.remove(site)
        user = next
        try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply() } catch (_: Exception) {}
        com.winwasm64.vpn.VpnLog.i("Браузер", if (direct) "сайт $site добавлен в список «напрямую, без VPN браузера»" else "сайт $site снова идёт через VPN браузера")
    }
}
