package com.winwasm64.torrent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * v4.40.0: поисковый агент по всем живым каталогам сразу.
 *
 * Источники (проверены запросом с этой машины — не «на глазок»):
 *  · catorrent.net — игры для всех платформ (оттуда же .torrent у человека);
 *  · torrent-igruha.org — ПК-игры, репаки, «для слабых ПК»;
 *  · brotorrent.net — ПК-игры, полные версии и репаки;
 *  · byxatab.com — репаки Xatab (поиск у них на `?s=`);
 *  · rutor.info — всё сразу: видео, музыка, программы, игры (есть счётчик «Раздают»);
 *  · nnmclub.to — трекер, всё сразу, magnet на странице;
 *  · apibay (The Pirate Bay), torrents-csv, nyaa — открытые индексы.
 * freetp.org проверен — это сервис кооперативных игр, торрентов там нет, поэтому в список не входит.
 *
 * Все источники опрашиваются ПАРАЛЛЕЛЬНО с общим дедлайном: один медленный сайт не задерживает
 * остальные. По каждому пишется строка в журнал — видно, кто ответил, а кто нет.
 *
 * Отбор: имя (50 %), сиды (25 %), размер (15 %), формат/платформа (10 %). Человеку показывается
 * причина выбора словами, а не «магия». Сам агент запускает только то, во что верит (см.
 * [bestForAutoStart]); остальное — аналогами на выбор.
 */
object TorrentSearchAgent {

    /** Найденный вариант. magnet ИЛИ torrentUrl+referer. */
    data class Found(
        val name: String,
        val magnet: String = "",
        val torrentUrl: String = "",
        val referer: String = "",
        val sizeBytes: Long = 0L,
        val seeders: Int = 0,
        val source: String = "",
        val platform: String = "",
        var score: Double = 0.0,
        var reason: String = ""
    ) {
        val sizeLabel: String
            get() = when {
                sizeBytes <= 0L -> "размер неизвестен"
                sizeBytes >= 1024L * 1024 * 1024 -> String.format("%.2f ГБ", sizeBytes / (1024.0 * 1024 * 1024))
                sizeBytes >= 1024L * 1024 -> String.format("%.1f МБ", sizeBytes / (1024.0 * 1024))
                else -> "${sizeBytes / 1024} КБ"
            }
    }

    /** Что человек хочет: название, формат, платформа. */
    data class Intent(val title: String, val format: String, val platform: String)

    private val FORMAT_WORDS = mapOf(
        "apk" to "apk", "android" to "apk",
        "nsz" to "nsz", "nsp" to "nsp", "xci" to "xci", "switch" to "switch",
        "iso" to "iso", "exe" to "exe", "repack" to "repack", "репак" to "repack",
        "flac" to "flac", "mp3" to "mp3", "lossless" to "flac",
        "mkv" to "mkv", "mp4" to "mp4", "bdrip" to "mkv", "webrip" to "mkv",
        "epub" to "epub", "pdf" to "pdf", "fb2" to "fb2"
    )

    private val PLATFORM_WORDS = mapOf(
        "switch" to "Nintendo Switch", "н-switch" to "Nintendo Switch",
        "pc" to "PC", "пк" to "PC", "ps4" to "PlayStation 4", "ps5" to "PlayStation 5",
        "xbox" to "Xbox", "android" to "Android", "ios" to "iOS",
        "3ds" to "Nintendo 3DS", "wii" to "Nintendo Wii", "gamecube" to "GameCube"
    )

    /* ===================== описания источников ===================== */

    /** DLE-сайт: поиск отдаёт ссылки на страницы раздач, файл берётся по `do=download&id=`. */
    private class DleSite(
        val name: String,
        val searchUrl: (String) -> String,
        val itemRe: Regex,
        val needsReferer: Boolean = true
    )

    /** Трекер: magnet лежит на странице раздачи, поиск даёт только ссылки. */
    private class MagnetSite(
        val name: String,
        val searchUrl: (String) -> String,
        val itemRe: Regex,
        val base: String
    )

    private val DLE_SITES = listOf(
        DleSite(
            "catorrent",
            { q -> "https://catorrent.net/index.php?do=search&subaction=search&story=$q" },
            Regex(
                "href=\"(https://catorrent\\.net/[a-z0-9/\\-]*\\d+-[a-z0-9\\-]+\\.html)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
        ),
        DleSite(
            "igruha",
            { q -> "https://torrent-igruha.org/index.php?do=search&subaction=search&story=$q" },
            Regex(
                "href=\"(https://torrent-igruha\\.org/\\d+-[a-z0-9\\-]+\\.html)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
        ),
        DleSite(
            "brotorrent",
            { q -> "https://brotorrent.net/index.php?do=search&subaction=search&story=$q" },
            Regex(
                "href=\"(https://brotorrent\\.net/\\d+-[a-z0-9\\-]+\\.html)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
        ),
        DleSite(
            "byxatab",
            { q -> "https://byxatab.com/?s=$q" },
            Regex(
                "href=\"(https://byxatab\\.com/games/[a-z0-9\\-/]+/\\d+-\\d+-\\d+-\\d+)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
        )
    )

    private val MAGNET_SITES = listOf(
        MagnetSite(
            "rutor",
            { q -> "https://rutor.info/search/0/0/000/0/?q=$q" },
            Regex("href=\"(/torrent/\\d+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            "https://rutor.info"
        ),
        MagnetSite(
            "nnmclub",
            { q -> "https://nnmclub.to/forum/tracker.php?nm=$q" },
            Regex(
                "href=\"(viewtopic\\.php\\?t=\\d+)[^\"]*\"[^>]*>(?:<[^>]+>)*([^<]{3,120})<",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ),
            "https://nnmclub.to/forum/"
        )
    )

    /* ===================== разбор запроса ===================== */

    fun parseIntent(text: String): Intent {
        val lower = text.lowercase()
        var format = ""
        var platform = ""
        for ((word, fmt) in FORMAT_WORDS) {
            if (Regex("(^|[^a-zа-я])" + Regex.escape(word) + "($|[^a-zа-я])").containsMatchIn(lower)) {
                format = fmt
                break
            }
        }
        for ((word, p) in PLATFORM_WORDS) {
            if (Regex("(^|[^a-zа-я])" + Regex.escape(word) + "($|[^a-zа-я])").containsMatchIn(lower)) {
                platform = p
                break
            }
        }
        val title = TorrentRescue.cleanQuery(lower.replace(Regex("[^\\p{L}\\p{N} .]+"), " "))
        return Intent(title, format, platform)
    }

    /* ===================== поиск ===================== */

    /** Ищем везде сразу, параллельно. Блокирующий вызов — из фонового потока. */
    fun searchAll(query: String, timeoutMs: Int = 7000): List<Found> {
        val intent = parseIntent(query)
        val q = intent.title.ifBlank { query.trim() }
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val out = java.util.Collections.synchronizedList(ArrayList<Found>())

        val exec = Executors.newFixedThreadPool(8)
        val tasks = ArrayList<Pair<String, Future<List<Found>>>>()
        try {
            for (site in DLE_SITES) {
                tasks.add(site.name to exec.submit(Callable { searchDle(site, encoded, q, timeoutMs) }))
            }
            for (site in MAGNET_SITES) {
                tasks.add(site.name to exec.submit(Callable { searchMagnet(site, encoded, q, timeoutMs) }))
            }
            tasks.add("индексы" to exec.submit(Callable { searchOpenIndexes(q, timeoutMs) }))

            val deadline = System.currentTimeMillis() + timeoutMs * 2L + 3000L
            for ((name, f) in tasks) {
                val left = deadline - System.currentTimeMillis()
                val got = try {
                    if (left > 500) f.get(left, TimeUnit.MILLISECONDS) else emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
                out.addAll(got)
                com.winwasm64.vpn.ConnTrace.torrent(
                    "агент: $name",
                    if (got.isEmpty()) "ничего не нашёл" else "находок: ${got.size}",
                    got.isNotEmpty()
                )
            }
        } finally {
            exec.shutdownNow()
        }
        return out.distinctBy { (it.magnet + it.torrentUrl).lowercase() }
    }

    /** Открытые индексы: apibay (TPB), torrents-csv, nyaa — через готовый поиск TorrentRescue. */
    private fun searchOpenIndexes(query: String, timeoutMs: Int): List<Found> =
        TorrentRescue.searchBlocking(query, timeoutMs).map {
            Found(it.name, magnet = it.magnet, sizeBytes = it.sizeBytes, seeders = it.seeders, source = it.source)
        }

    private fun searchDle(site: DleSite, encoded: String, query: String, timeoutMs: Int): List<Found> {
        val html = httpGet(site.searchUrl(encoded), timeoutMs) ?: return emptyList()
        val pages = collectItems(html, site.itemRe, query, limit = 3)
        if (pages.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        for ((pageUrl, title) in pages) {
            // Referer — корень того же сайта (у разных источников разные домены: .net, .org, .com)
            val hostRoot = "https://" + pageUrl.substringAfter("://").substringBefore('/') + "/"
            val page = httpGet(pageUrl, timeoutMs, referer = hostRoot) ?: continue
            val id = Regex("do=download&(?:amp;)?id=(\\d+)").find(page)?.groupValues?.get(1) ?: continue
            val sizeText = Regex("Размер[:\\s][^0-9]{0,20}([0-9]+[.,]?[0-9]*\\s*[KMGТ]?[Bb])")
                .find(page)?.groupValues?.get(1) ?: ""
            val platform = Regex("Платформа[^>]*>(?:\\s*<[^>]+>)*\\s*([A-Za-zА-Яа-я][A-Za-zА-Яа-я0-9 \\-]{2,24})")
                .find(page)?.groupValues?.get(1)?.trim() ?: ""
            val host = pageUrl.substringAfter("://").substringBefore('/')
            out.add(
                Found(
                    name = title,
                    torrentUrl = "https://$host/index.php?do=download&id=$id",
                    referer = if (site.needsReferer) pageUrl else "",
                    sizeBytes = TorrentRescue.parseSizeLabel(sizeText),
                    seeders = 0, // эти сайты не публикуют число сидов — не выдумываем
                    source = site.name,
                    platform = platform
                )
            )
        }
        return out
    }

    private fun searchMagnet(site: MagnetSite, encoded: String, query: String, timeoutMs: Int): List<Found> {
        val html = httpGet(site.searchUrl(encoded), timeoutMs) ?: return emptyList()
        val pages = collectItems(html, site.itemRe, query, limit = 3)
        val out = ArrayList<Found>()
        for ((path, title) in pages) {
            val pageUrl = if (path.startsWith("http")) path else site.base + path
            val page = httpGet(pageUrl, timeoutMs) ?: continue
            val magnet = Regex("magnet:\\?xt=urn:btih:[A-Fa-f0-9]{20,}[^\"'<\\s]*")
                .find(page)?.value ?: continue
            val sizeText = Regex("Размер[:\\s][^0-9]{0,20}([0-9]+[.,]?[0-9]*\\s*[KMGT]?[Bb])")
                .find(page)?.groupValues?.get(1) ?: ""
            val seeders = Regex("Раздают</td>\\s*<td>\\s*(\\d+)").find(page)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add(
                Found(
                    name = title,
                    magnet = magnet,
                    sizeBytes = TorrentRescue.parseSizeLabel(sizeText.replace('\u00A0', ' ')),
                    seeders = seeders,
                    source = site.name
                )
            )
        }
        return out
    }

    /** Собрать ссылки на страницы раздач: (url, заголовок), только те, что вообще похожи на запрос. */
    private fun collectItems(html: String, re: Regex, query: String, limit: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (m in re.findAll(html)) {
            val url = m.groupValues[1].trim()
            val title = m.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (url.isBlank() || title.length < 3) continue
            if (!seen.add(url)) continue
            if (!sharesWord(title, query)) continue
            out.add(url to title)
            if (out.size >= limit) break
        }
        return out
    }

    /** Есть ли в заголовке хоть одно значимое слово запроса — отсекает «похожее на всё подряд». */
    fun sharesWord(title: String, query: String): Boolean {
        val words = query.lowercase().split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return true
        val lower = title.lowercase()
        return words.any { lower.contains(it) }
    }

    private fun httpGet(url: String, timeoutMs: Int, referer: String? = null): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            // часть трекеров отдаёт windows-1251 — иначе русские названия превращаются в мусор
            val utf = bytes.toString(Charsets.UTF_8)
            if (utf.contains('\uFFFD')) bytes.toString(charset("windows-1251")) else utf
        } catch (_: Throwable) {
            null
        }
    }

    /** Скачать .torrent по найденной ссылке (с обязательным Referer, где он нужен). */
    fun downloadTorrent(found: Found, timeoutMs: Int = 12000): ByteArray? {
        if (found.torrentUrl.isBlank()) return null
        return try {
            val conn = (URL(found.torrentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
                )
                setRequestProperty("Accept", "application/x-bittorrent,*/*")
                if (found.referer.isNotBlank()) setRequestProperty("Referer", found.referer)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            // настоящий .torrent — bencode-словарь, начинается с 'd'
            if (bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) bytes else null
        } catch (_: Throwable) {
            null
        }
    }

    /* ===================== отбор и объяснение ===================== */

    /**
     * Упорядочить найденное и подписать причину.
     * Оценка: имя (50 %), сиды (25 %), размер (15 %), формат/платформа (10 %).
     */
    fun rank(
        found: List<Found>,
        intent: Intent,
        expectedName: String = "",
        expectedBytes: Long = 0L,
        wantSized: Boolean = expectedBytes > 0L
    ): List<Found> {
        val needle = intent.title.ifBlank { expectedName }
        for (f in found) {
            val sim = TorrentRescue.similarity(needle, f.name)
            val seedScore = when {
                f.seeders <= 0 -> 0.0
                f.seeders >= 50 -> 1.0
                else -> f.seeders / 50.0
            }
            val sizeScore = when {
                expectedBytes <= 0L || f.sizeBytes <= 0L -> 0.5
                f.sizeBytes in (expectedBytes * 7 / 10)..(expectedBytes * 14 / 10) -> 1.0
                f.sizeBytes in (expectedBytes * 4 / 10)..(expectedBytes * 25 / 10) -> 0.6
                else -> 0.0
            }
            val fmtScore = when {
                intent.format.isBlank() && intent.platform.isBlank() -> 0.5
                intent.platform.isNotBlank() && f.platform.contains(intent.platform, true) -> 1.0
                intent.format.isNotBlank() && f.name.contains(intent.format, true) -> 1.0
                else -> 0.3
            }
            f.score = 0.5 * sim + 0.25 * seedScore + 0.15 * sizeScore + 0.10 * fmtScore
            f.reason = buildReason(f, sim, sizeScore, wantSized)
        }
        return found.sortedByDescending { it.score }
    }

    private fun buildReason(f: Found, sim: Double, sizeScore: Double, wantSized: Boolean): String {
        val parts = ArrayList<String>()
        parts.add("имя ${(sim * 100).toInt()} %")
        if (wantSized) {
            parts.add(
                when (sizeScore) {
                    1.0 -> "размер совпадает (${f.sizeLabel})"
                    0.6 -> "размер близкий (${f.sizeLabel})"
                    else -> "размер отличается (${f.sizeLabel})"
                }
            )
        }
        parts.add(if (f.seeders > 0) "${f.seeders} сидов" else "сиды не показаны")
        if (f.platform.isNotBlank()) parts.add(f.platform)
        parts.add(f.source)
        return parts.joinToString(" · ")
    }

    /**
     * Кого можно запускать сразу: похоже на правду и есть откуда качать.
     * Сайты-каталоги (catorrent, igruha, brotorrent, byxatab) не публикуют число сидов — для них
     * достаточно точного имени и совпавшего размера.
     */
    private val CATALOG_SOURCES = setOf("catorrent", "igruha", "brotorrent", "byxatab")

    fun bestForAutoStart(
        ranked: List<Found>,
        intent: Intent,
        expectedName: String,
        expectedBytes: Long
    ): Found? = ranked.firstOrNull { f ->
        val sim = TorrentRescue.similarity(intent.title.ifBlank { expectedName }, f.name)
        val sizeOk = expectedBytes <= 0L || f.sizeBytes <= 0L ||
            f.sizeBytes in (expectedBytes * 4 / 10)..(expectedBytes * 25 / 10)
        val seedersOk = f.seeders >= 3 || (f.source in CATALOG_SOURCES && f.seeders == 0)
        sim >= 0.5 && sizeOk && seedersOk && (f.magnet.startsWith("magnet:", true) || f.torrentUrl.isNotBlank())
    }

    /** Аналоги: «не то же самое, но с пирами» — показываем человеку, а не запускаем молча. */
    fun analogs(ranked: List<Found>, expectedName: String, limit: Int = 3): List<Found> =
        ranked
            .filter { TorrentRescue.similarity(expectedName, it.name) >= 0.34 }
            .filter { it.seeders >= 1 || it.source in CATALOG_SOURCES }
            .filter { it.magnet.startsWith("magnet:", true) || it.torrentUrl.isNotBlank() }
            .take(limit)
}
