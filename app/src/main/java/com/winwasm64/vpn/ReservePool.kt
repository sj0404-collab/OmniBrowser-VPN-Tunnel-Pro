package com.winwasm64.vpn

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.30.0: пул резервных прокси.
 *
 * Задача: когда у основного канала кончился лимит или он лёг, браузеру нужен ещё один выход.
 * Публичные списки прокси — самый доступный «последний рубеж»: без аккаунтов, без подписок,
 * без обещаний скорости. Мы их скачиваем, чистим от мусора, проверяем живыми соединениями
 * и держим про запас.
 *
 * Здесь только разбор, чистка и ранжирование — их легко проверять тестами. Скачивание и кеш
 * тоже здесь, но вынесены в отдельные функции.
 */
object ReservePool {

    enum class Kind { HTTP, SOCKS5 }

    /** Один резервный прокси. */
    data class Proxy(
        val host: String,
        val port: Int,
        val kind: Kind,
        val source: String = ""
    ) {
        val key: String get() = "$host:$port"
        val label: String get() = "$host:$port"
    }

    /** Публичные списки, которые отдают простой «ip:port» или «scheme://ip:port» построчно. */
    val LISTS: List<Pair<String, Kind>> = listOf(
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt" to Kind.HTTP,
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt" to Kind.SOCKS5,
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt" to Kind.HTTP,
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt" to Kind.SOCKS5,
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt" to Kind.HTTP,
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt" to Kind.SOCKS5
    )

    private val HOST_RE = Regex("^[0-9a-zA-Z.\\-]{4,80}$")

    /**
     * Разбор одной строки. Понимает форматы:
     *   `1.2.3.4:8080`, `http://1.2.3.4:8080`, `socks5://1.2.3.4:1080`,
     *   `socks5://user:pass@1.2.3.4:1080` (логин/пароль отбрасываем — они не наши).
     */
    fun parseLine(line: String, defaultKind: Kind, source: String = ""): Proxy? {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return null
        if (s.startsWith("{") || s.startsWith("[")) return null // json-подобный мусор

        var kind = defaultKind
        val scheme = s.substringBefore("://", "")
        if (scheme.isNotEmpty()) {
            kind = when (scheme.lowercase()) {
                "socks5", "socks", "socks5h" -> Kind.SOCKS5
                "http", "https" -> Kind.HTTP
                else -> return null
            }
            s = s.substringAfter("://")
        }
        s = s.substringAfter('@')                       // user:pass@
        s = s.substringBefore('/').substringBefore(' ').substringBefore('\t').trim()

        val host = s.substringBeforeLast(':', "")
        val portStr = s.substringAfterLast(':', "")
        if (host.isEmpty() || portStr.isEmpty()) return null
        if (!HOST_RE.matches(host)) return null
        val port = portStr.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Proxy(host, port, kind, source)
    }

    fun parse(text: String, defaultKind: Kind, source: String = "", limit: Int = 800): List<Proxy> {
        val out = ArrayList<Proxy>()
        for (line in text.lineSequence()) {
            val p = parseLine(line, defaultKind, source) ?: continue
            out.add(p)
            if (out.size >= limit) break
        }
        return out
    }

    /** Локальные и служебные адреса в публичных списках — всегда мусор. */
    fun isReserved(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        parts.forEach { if ((it.toIntOrNull() ?: -1) !in 0..255) return false }
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a >= 224 -> true
            else -> false
        }
    }

    /** Убрать дубли и мусор, сохранив порядок и ограничив длину. */
    fun sanitize(list: List<Proxy>, limit: Int = 80): List<Proxy> {
        val seen = HashSet<String>()
        val out = ArrayList<Proxy>(limit)
        for (p in list) {
            if (isReserved(p.host)) continue
            if (p.host.isBlank()) continue
            val k = p.kind.name + "|" + p.key
            if (!seen.add(k)) continue
            out.add(p)
            if (out.size >= limit) break
        }
        return out
    }

    /** Сначала проверенные (по возрастанию задержки), затем непроверенные — как есть. */
    fun rank(list: List<Proxy>, latencies: Map<String, Int>): List<Proxy> {
        val known = ArrayList<Proxy>()
        val unknown = ArrayList<Proxy>()
        for (p in list) if (latencies.containsKey(p.key)) known.add(p) else unknown.add(p)
        known.sortBy { latencies[it.key] ?: Int.MAX_VALUE }
        return known + unknown
    }

    /* ================= кеш и скачивание ================= */

    private const val PREFS = "omni_reserve_pool"
    private const val KEY_LIST = "pool"

    fun load(ctx: Context): List<Proxy> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val port = parts[2].toIntOrNull() ?: return@mapNotNull null
            Proxy(parts[1], port, kind, parts.getOrElse(3) { "" })
        }.toList()
    }

    fun save(ctx: Context, list: List<Proxy>) {
        val text = list.joinToString("\n") { "${it.kind.name}|${it.host}|${it.port}|${it.source}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, text).apply()
    }

    /**
     * Скачать списки и собрать пул. Возвращает количество записей до проверки.
     * Сеть может быть недоступна — тогда просто пусто, без падений.
     */
    fun refresh(ctx: Context, perListTimeoutMs: Int = 7000, limit: Int = 80): Int {
        val collected = ArrayList<Proxy>()
        for ((url, kind) in LISTS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = perListTimeoutMs
                    readTimeout = perListTimeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "OmniBrowser/4.30")
                }
                conn.inputStream.use { stream ->
                    val text = stream.bufferedReader().readText()
                    collected.addAll(parse(text, kind, url.substringAfterLast('/')))
                }
                conn.disconnect()
            } catch (_: Throwable) {
                // один список не отдался — не беда, есть другие
            }
        }
        val clean = sanitize(collected, limit)
        if (clean.isNotEmpty()) save(ctx, clean)
        VpnLog.i("Резерв", "пул прокси: собрано ${collected.size}, оставлено ${clean.size}")
        return clean.size
    }
}
