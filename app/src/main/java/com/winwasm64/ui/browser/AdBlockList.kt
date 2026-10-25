package com.winwasm64.ui.browser

import android.content.Context
import com.winwasm64.vpn.VpnLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * v4.4.0: полноценный список блокировки рекламы и трекеров (AdGuard DNS filter, ~60 тыс. правил `||host^`)
 * в дополнение к встроенному короткому списку. Скачивается раз в 7 дней (напрямую или через туннель), хранится
 * в files/adblock/hosts.txt, в памяти — HashSet доменов; проверка по суффиксам (`a.b.c` → `a.b.c`, `b.c`, `c`).
 */
object AdBlockList {
    private const val TAG = "Браузер"
    private const val URL_PRIMARY = "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt"
    private const val URL_FALLBACK = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    private const val REFRESH_MS = 7L * 24 * 3600_000L

    @Volatile private var hosts: HashSet<String> = HashSet()
    @Volatile private var allow: HashSet<String> = HashSet()
    @Volatile var size: Int = 0
        private set
    @Volatile var updatedAt: Long = 0L
        private set
    @Volatile private var loading = false

    fun isBlocked(host: String): Boolean {
        if (hosts.isEmpty()) return false
        var h = host.lowercase()
        if (h.isEmpty()) return false
        while (true) {
            if (allow.contains(h)) return false
            if (hosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "adblock").apply { mkdirs() }, "hosts.txt")

    /** Загрузить из кэша и при необходимости обновить из сети (в фоне). */
    fun init(ctx: Context, enabled: Boolean) {
        if (!enabled || loading) return
        loading = true
        thread(name = "omni-adblock", isDaemon = true) {
            val app = ctx.applicationContext
            try {
                val f = file(app)
                if (f.isFile && f.length() > 10_000) {
                    parse(f.readText())
                    updatedAt = f.lastModified()
                    VpnLog.i(TAG, "список блокировки рекламы: $size доменов из кэша")
                }
                if (System.currentTimeMillis() - updatedAt > REFRESH_MS) refresh(app)
            } catch (e: Exception) {
                VpnLog.w(TAG, "список блокировки: ${e.message ?: e.javaClass.simpleName}")
            } finally { loading = false }
        }
    }

    fun refreshNow(ctx: Context, onDone: (String) -> Unit) {
        thread(name = "omni-adblock-refresh", isDaemon = true) {
            val r = try { refresh(ctx.applicationContext); "Список обновлён: $size доменов" } catch (e: Exception) { "Не удалось обновить: ${e.message}" }
            onDone(r)
        }
    }

    private fun refresh(ctx: Context) {
        val text = try { fetch(URL_PRIMARY) } catch (_: Exception) { fetch(URL_FALLBACK) }
        if (text.length < 10_000) throw IllegalStateException("список слишком короткий (${text.length})")
        parse(text)
        try { file(ctx).writeText(text) } catch (_: Exception) {}
        updatedAt = System.currentTimeMillis()
        VpnLog.i(TAG, "список блокировки рекламы обновлён: $size доменов (${text.length / 1024} КБ)")
    }

    private fun fetch(url: String): String {
        try {
            val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "OmniBrowser")
            if (c.responseCode == 200) return c.inputStream.bufferedReader().readText()
            throw IllegalStateException("HTTP ${c.responseCode}")
        } catch (e: Exception) {
            return TunnelHttp().getText(url, mapOf("User-Agent" to "OmniBrowser"), 8 * 1024 * 1024)
        }
    }

    /** Понимает `||host^`, `@@||host^` (исключение) и формат hosts (`0.0.0.0 host`). Правила с путями/модификаторами пропускаются. */
    private fun parse(text: String) {
        val block = HashSet<String>(70_000)
        val white = HashSet<String>(512)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) continue
            var s = line
            var isAllow = false
            if (s.startsWith("@@")) { isAllow = true; s = s.substring(2) }
            if (s.startsWith("||")) {
                s = s.substring(2)
                val caret = s.indexOf('^')
                val host = if (caret >= 0) s.substring(0, caret) else s
                val mods = if (caret >= 0) s.substring(caret + 1) else ""
                if (mods.isNotEmpty() && mods != "\$important") continue
                if (host.isEmpty() || host.contains('/') || host.contains('*') || host.contains(':') || !host.contains('.')) continue
                (if (isAllow) white else block).add(host.lowercase())
            } else if (s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ")) {
                val host = s.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
                if (host.contains('.') && host != "0.0.0.0") block.add(host)
            }
        }
        hosts = block
        allow = white
        size = block.size
    }
}
