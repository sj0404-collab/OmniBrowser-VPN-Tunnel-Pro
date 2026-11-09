package com.winwasm64.vpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * v4.17.0: Журнал соединений — «кто, куда, по какому протоколу, через что и зачем».
 *
 * Раньше журнал писал только итог по приложению (`VpnLog.conn`: приложение → хост:порт · профиль)
 * и служебные сообщения. По нему нельзя было ответить на вопросы «какой именно запрос это вызвал»,
 * «это TCP или UDP», «это через Browsec, Psiphon или обход DPI» и «зачем» — то есть для чего
 * страница/приложение это соединение открывало.
 *
 * Здесь на каждое соединение пишется одна короткая строка:
 * `19:31:02 🌐 Браузер · play.geforcenow.com → 92.223.105.5:443 · TCP · Browsec lv210 · медиа · ↓24 КБ · 180 мс`
 *
 * Категории (они же фильтры): браузер, приложения, UDP/игры, обход DPI, DNS, ошибки.
 * Журнал живёт в памяти кольцом, ничего не грузит на диск и не тормозит трафик:
 * запись — это добавление в очередь под коротким локом, отрисовка — по требованию.
 */
object ConnTrace {

    const val CAT_BROWSER = "Браузер"
    const val CAT_APPS = "Приложения"
    const val CAT_UDP = "UDP"
    const val CAT_BYPASS = "Обход DPI"
    const val CAT_DNS = "DNS"
    const val CAT_TORRENT = "Торренты"

    private const val MAX = 3000
    private const val DEDUP_MS = 2_000L
    /** v4.31.0: отсечённые запросы пишем в журнал не чаще раза в минуту. */
    private const val BLOCKED_DEDUP_MS = 60_000L

    /** Одна запись журнала. Храним поля, а не готовую строку: нужны фильтры и статистика. */
    data class Entry(
        val at: Long,
        val cat: String,
        val who: String,
        val target: String,
        val proto: String,
        val route: String,
        val purpose: String,
        val ok: Boolean,
        val ms: Long,
        val downBytes: Long,
        val err: String?,
        val repeats: Int
    )

    /** Запрос страницы, который ещё не превратился в соединение (ждёт своей строки маршрута). */
    private class Pending(val initiator: String, val purpose: String, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val entries = ArrayDeque<Entry>()
    private val dedup = ConcurrentHashMap<String, Long>()

    /** Сколько всего прошло — для строки статуса в панели VPN. */
    val totalConnections = AtomicLong(0L)
    val failedConnections = AtomicLong(0L)
    private val byRoute = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    var version: Int = 0
        private set

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * v4.18.0: запрос страницы/подресурса из WebView.
     *
     * Раньше он сразу писал отдельную строку, и в журнале каждый адрес появлялся ДВАЖДЫ:
     * «маршрут браузера» из WebView и «Browsec lv210» из прокси. Теперь запрос запоминается
     * и «прилипает» к ближайшему реальному соединению с тем же адресом — в одной строке сразу
     * видно и кто вызвал, и зачем, и через что пошло, и сколько заняло.
     * Если соединения так и не было (кэш, отсечено, service worker) — строка появится сама
     * через 2,5 с, чтобы запрос не пропал из журнала.
     */
    fun pendingRequest(initiator: String, host: String, port: Int, purpose: String) {
        if (host.isBlank()) return
        flushStale()
        pending["$host:$port"] = Pending(initiator, purpose, System.currentTimeMillis())
    }

    private fun takePending(host: String, port: Int): Pending? {
        pending.remove("$host:$port")?.let { return it }
        // порт мог отличаться (443 против 80) — ищем по имени
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith("$host:")) {
                it.remove()
                return e.value
            }
        }
        return null
    }

    private fun flushStale() {
        val now = System.currentTimeMillis()
        if (pending.isEmpty()) return
        val it = pending.entries.iterator()
        var released = 0
        while (it.hasNext() && released < 20) {
            val e = it.next()
            if (now - e.value.at > 2_500L) {
                it.remove()
                released++
                val parts = e.key.split(":")
                val host = parts.getOrNull(0).orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isNotBlank() && !isNoise(host)) {
                    push(
                        Entry(
                            at = e.value.at,
                            cat = CAT_BROWSER,
                            who = if (e.value.initiator.isBlank()) "Браузер" else "Браузер (${e.value.initiator})",
                            target = "$host:$port",
                            proto = "TCP",
                            route = "из кэша / соединение не открывалось",
                            purpose = e.value.purpose,
                            ok = true,
                            ms = -1L,
                            downBytes = -1L,
                            err = null,
                            repeats = 0
                        )
                    )
                }
            }
        }
    }

    /** Служебный мусор, который не должен засорять журнал (PAC-заглушка расширения и т.п.). */
    fun isNoise(host: String): Boolean =
        host.endsWith(".invalid", true) || host.contains("omni-ext", true) || host == "localhost"

    /** Запрос страницы/подресурса из WebView: кто вызвал, для кого и зачем. */
    fun request(initiator: String, host: String, port: Int, purpose: String, blocked: Boolean) {
        if (isNoise(host)) return
        if (!blocked) {
            pendingRequest(initiator, host, port, purpose)
            return
        }
        val who = if (initiator.isBlank()) "Браузер" else "Браузер ($initiator)"
        push(
            Entry(
                at = System.currentTimeMillis(),
                cat = CAT_BROWSER,
                who = who,
                target = "$host:$port",
                proto = "TCP",
                route = if (blocked) "заблокировано в браузере" else "маршрут браузера",
                purpose = purpose,
                ok = !blocked,
                ms = -1L,
                downBytes = -1L,
                err = null,
                repeats = 0
            )
        )
    }

    /** Завершённое соединение через прокси/туннель: маршрут, время, объём, результат. */
    fun connect(
        who: String,
        host: String,
        port: Int,
        route: String,
        ms: Long,
        ok: Boolean,
        downBytes: Long = -1L,
        err: String? = null,
        proto: String = "TCP",
        purpose: String = ""
    ) {
        if (isNoise(host)) return
        val pend = takePending(host, port)
        val whoFull = if (who.startsWith("Браузер") && pend != null && pend.initiator.isNotBlank()) "Браузер (${pend.initiator})" else who
        val purposeFull = if (purpose.isNotBlank()) purpose else (pend?.purpose ?: "")
        val isBypass = route.contains("обход", ignoreCase = true)
        val cat = when {
            who.startsWith("Браузер") -> CAT_BROWSER
            else -> CAT_APPS
        }
        totalConnections.incrementAndGet()
        byRoute.computeIfAbsent(shortRoute(route)) { AtomicLong(0L) }.incrementAndGet()
        if (!ok) failedConnections.incrementAndGet()
        push(
            Entry(
                at = System.currentTimeMillis(),
                cat = if (isBypass && cat == CAT_BROWSER) CAT_BYPASS else cat,
                who = whoFull,
                target = "$host:$port",
                proto = proto,
                route = route,
                purpose = purposeFull,
                ok = ok,
                ms = ms,
                downBytes = downBytes,
                err = err,
                repeats = 0
            )
        )
    }

    /** UDP-поток (игры, звонки, WebRTC): что за пакеты и куда. */
    fun udp(who: String, target: String, kind: String, route: String = "UDP-релей (вне туннеля)") {
        totalConnections.incrementAndGet()
        byRoute.computeIfAbsent(shortRoute(route)) { AtomicLong(0L) }.incrementAndGet()
        push(
            Entry(
                at = System.currentTimeMillis(),
                cat = CAT_UDP,
                who = who,
                target = target,
                proto = "UDP",
                route = route,
                purpose = kind,
                ok = true,
                ms = -1L,
                downBytes = -1L,
                err = null,
                repeats = 0
            )
        )
    }

    /**
     * v4.18.0: события торрент-движка — трекер, пир, webseed.
     * Без этого нельзя ответить на вопрос «почему 0% и 0 пиров»: видно, что именно не ответило.
     */
    fun torrent(target: String, what: String, ok: Boolean, note: String = "") {
        if (!ok) failedConnections.incrementAndGet()
        totalConnections.incrementAndGet()
        byRoute.computeIfAbsent("Торренты") { AtomicLong(0L) }.incrementAndGet()
        push(
            Entry(
                at = System.currentTimeMillis(),
                cat = CAT_TORRENT,
                who = "Торрент",
                target = target,
                proto = if (what.contains("webseed", true)) "HTTP" else if (what.contains("udp", true)) "UDP" else "TCP",
                route = "BitTorrent P2P",
                purpose = what,
                ok = ok,
                ms = -1L,
                downBytes = -1L,
                err = note.takeIf { it.isNotBlank() },
                repeats = 0
            )
        )
    }

    /** DNS-запрос: как имя превратилось в адрес (и через что). */
    fun dns(host: String, answer: String, via: String) {
        push(
            Entry(
                at = System.currentTimeMillis(),
                cat = CAT_DNS,
                who = "DNS",
                target = host,
                proto = "DoH/UDP",
                route = via,
                purpose = answer,
                ok = answer.isNotBlank() && !answer.startsWith("не "),
                ms = -1L,
                downBytes = -1L,
                err = null,
                repeats = 0
            )
        )
    }

    private fun shortRoute(route: String): String = when {
        route.contains("Psiphon") -> "Psiphon"
        route.contains("обход", true) || route.contains("напрямую", true) -> "Обход DPI"
        route.contains("UDP-релей", true) -> "UDP-релей"
        route.contains("Browsec", true) -> "Browsec"
        else -> "прочее"
    }

    private fun push(e: Entry) {
        // Одинаковые записи (страница тянет десятки картинок с одного хоста) — не чаще раза в 2 с,
        // иначе журнал превращается в мусор и его невозможно читать.
        //
        // v4.31.0: у ОТСЕЧЁННЫХ записей окно другое. Телеметрия GeForce NOW стучится каждые 2–3 с,
        // и журнал из журнала с устройства был наполовину из одинаковых строк «заблокировано».
        // Теперь такая строка возвращается раз в 60 с — факт остаётся виден, шум уходит.
        val blocked = !e.ok && (e.route.contains("заблокировано", true) || (e.err?.contains("заблокирован", true) == true))
        val window = if (blocked) BLOCKED_DEDUP_MS else DEDUP_MS
        val key = e.cat + "|" + e.who + "|" + e.target + "|" + e.purpose + "|" + e.ok
        val now = System.currentTimeMillis()
        val prev = dedup[key]
        if (prev != null && now - prev < window) return
        dedup[key] = now
        if (dedup.size > 800) {
            dedup.entries.removeIf { now - it.value > 120_000L }
        }
        synchronized(entries) {
            entries.addLast(e)
            while (entries.size > MAX) entries.removeFirst()
            version++
        }
    }

    fun snapshot(filter: String?): List<String> {
        flushStale()
        val list = synchronized(entries) { entries.toList() }
        val filtered = when (filter) {
            null -> list
            "err" -> list.filter { !it.ok }
            "browser" -> list.filter { it.cat == CAT_BROWSER }
            "apps" -> list.filter { it.cat == CAT_APPS }
            "udp" -> list.filter { it.cat == CAT_UDP }
            "bypass" -> list.filter { it.cat == CAT_BYPASS || it.route.contains("обход", true) }
            "dns" -> list.filter { it.cat == CAT_DNS }
            "torrent" -> list.filter { it.cat == CAT_TORRENT }
            else -> list.filter { it.cat == filter }
        }
        return filtered.map { render(it) }
    }

    fun text(filter: String?): String = snapshot(filter).joinToString("\n")

    fun clear() {
        synchronized(entries) { entries.clear() }
        dedup.clear()
        byRoute.clear()
        totalConnections.set(0L)
        failedConnections.set(0L)
        version++
    }

    private fun render(e: Entry): String {
        val time = fmt.format(Date(e.at))
        val mark = when {
            !e.ok -> "❌"
            e.cat == CAT_UDP -> "🎮"
            e.cat == CAT_DNS -> "🔎"
            e.cat == CAT_TORRENT -> "🧲"
            e.cat == CAT_BYPASS -> "🔀"
            e.cat == CAT_BROWSER -> "🌐"
            else -> "📱"
        }
        val sb = StringBuilder()
        sb.append(time).append(' ').append(mark).append(' ').append(e.who)
        sb.append(" → ").append(e.target)
        sb.append(" · ").append(e.proto)
        if (e.route.isNotBlank()) sb.append(" · ").append(e.route)
        if (e.purpose.isNotBlank()) sb.append(" · ").append(e.purpose)
        if (e.ms >= 0) sb.append(" · ").append(if (e.ms >= 1000) String.format(Locale.US, "%.1f с", e.ms / 1000.0) else "${e.ms} мс")
        if (e.downBytes > 0) sb.append(" · ↓").append(size(e.downBytes))
        if (!e.ok && !e.err.isNullOrBlank()) sb.append(" · ошибка: ").append(e.err.take(120))
        return sb.toString()
    }

    private fun size(b: Long): String = when {
        b < 1024 -> "$b Б"
        b < 1048576 -> String.format(Locale.US, "%.0f КБ", b / 1024.0)
        b < 1073741824L -> String.format(Locale.US, "%.1f МБ", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f ГБ", b / 1073741824.0)
    }

    /** Строка для панели VPN: сколько соединений, ошибок и по каким маршрутам. */
    fun statsLine(): String {
        val total = totalConnections.get()
        if (total == 0L) return "🔗 Соединений пока не было"
        val failed = failedConnections.get()
        val parts = byRoute.entries.sortedByDescending { it.value.get() }.take(4)
            .joinToString(" · ") { "${it.key}: ${it.value.get()}" }
        return "🔗 соединений $total" + (if (failed > 0) ", ошибок $failed" else "") + (if (parts.isNotBlank()) " · $parts" else "")
    }
}
