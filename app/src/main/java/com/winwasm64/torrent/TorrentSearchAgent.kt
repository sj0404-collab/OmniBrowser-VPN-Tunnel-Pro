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
        val poster: String = "",
        var category: String = "other",
        /** v4.43.0: 2160p/1080p/720p/480p/360p (если в названии указано). */
        var quality: String = "",
        /** v4.43.0: кодек — x264/x265(hevc)/av1; влияет на воспроизведение. */
        var codec: String = "",
        /** v4.43.0: звук — flac/mp3/ac3/dts/aac. */
        var audio: String = "",
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
    data class Intent(
        val title: String,
        val format: String,
        val platform: String,
        /** Запрошенное качество («360p», «1080p», «4K»); пусто — любое. */
        val quality: String = "",
        /** Запрошенный звук для музыки («flac», «mp3»). */
        val audio: String = ""
    )

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
            "kickass",
            { q -> "https://kickasstorrents.to/usearch/$q/" },
            Regex("href=\"(/[a-z0-9\\-]+-t\\d+\\.html)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            "https://kickasstorrents.to"
        ),
        MagnetSite(
            "torrentdownloads",
            { q -> "https://www.torrentdownloads.pro/search/?search=$q" },
            Regex("href=\"(/torrent/\\d+/[^\"#]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            "https://www.torrentdownloads.pro"
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
        val words = lower.replace(Regex("[^\\p{L}\\p{N} .]+"), " ").split(Regex("\\s+"))
        val quality = qualityFromQuery(words)
        val audio = when {
            lower.contains("flac") || lower.contains("lossless") -> "flac"
            lower.contains("mp3") -> "mp3"
            else -> ""
        }
        val title = TorrentRescue.cleanQuery(lower.replace(Regex("[^\\p{L}\\p{N} .]+"), " "))
        return Intent(title, format, platform, quality, audio)
    }

    /* ===================== поиск ===================== */

    /** Ищем везде сразу, параллельно. Блокирующий вызов — из фонового потока. */
    fun searchAll(query: String, timeoutMs: Int = 7000): List<Found> {
        val intent = parseIntent(query)
        // v4.44.0: сайтам отдаём название БЕЗ качества/формата («шерлок», а не «шерлок 360p»),
        // иначе поиск по трекерам возвращает пустоту, а фильтр по качеству убивает всё остальное
        val q = stripQueryNoise(intent.title.ifBlank { query.trim() })
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
            val poster = extractPoster(page, pageUrl)
            val host = pageUrl.substringAfter("://").substringBefore('/')
            out.add(
                Found(
                    name = title,
                    torrentUrl = "https://$host/index.php?do=download&id=$id",
                    referer = if (site.needsReferer) pageUrl else "",
                    sizeBytes = TorrentRescue.parseSizeLabel(sizeText),
                    seeders = 0, // эти сайты не публикуют число сидов — не выдумываем
                    source = site.name,
                    platform = platform,
                    poster = poster
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
            val poster = extractPoster(page, pageUrl)
            out.add(
                Found(
                    name = title,
                    magnet = magnet,
                    poster = poster,
                    sizeBytes = TorrentRescue.parseSizeLabel(sizeText.replace('\u00A0', ' ')),
                    seeders = seeders,
                    source = site.name
                )
            )
        }
        return out
    }

    /**
     * v4.41.0: обложка со страницы раздачи — og:image, постер DLE или первая крупная картинка.
     * Отдаём абсолютный URL; пустая строка, если картинки нет (тогда рисуется заглушка по букве).
     */
    fun extractPoster(html: String, pageUrl: String): String {
        val candidates = listOf(
            Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", RegexOption.IGNORE_CASE),
            Regex("<img[^>]+class=[\"'][^\"']*(?:poster|xfieldimage|poster_new)[^\"']*[\"'][^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("<img[^>]+src=[\"']([^\"']+)[\"'][^>]+class=[\"'][^\"']*poster[^\"']*[\"']", RegexOption.IGNORE_CASE)
        )
        for (re in candidates) {
            val raw = re.find(html)?.groupValues?.get(1)?.trim() ?: continue
            if (raw.isBlank() || raw.startsWith("data:")) continue
            return when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.startsWith("//") -> "https:" + raw
                raw.startsWith("/") -> "https://" + pageUrl.substringAfter("://").substringBefore('/') + raw
                else -> pageUrl.substringBeforeLast('/') + "/" + raw
            }
        }
        return ""
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
            f.category = classify(f.name, f.platform, f.source)
            if (f.quality.isBlank()) f.quality = qualityOf(f.name)
            if (f.codec.isBlank()) f.codec = codecOf(f.name)
            if (f.audio.isBlank()) f.audio = audioOf(f.name)
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
            // v4.43.0: качество учитывается отдельно — «хочу 360p» больше не значит «любое»
            val qualityScore = when {
                intent.quality.isBlank() && intent.audio.isBlank() -> 0.5
                intent.quality.isNotBlank() && f.quality == intent.quality -> 1.0
                intent.audio.isNotBlank() && f.audio.equals(intent.audio, true) -> 1.0
                f.quality.isBlank() && f.audio.isBlank() -> 0.4
                else -> 0.2
            }
            f.score = 0.45 * sim + 0.20 * seedScore + 0.15 * sizeScore + 0.10 * fmtScore + 0.10 * qualityScore
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

    /* ===================== категории ===================== */

    /** Категории для фильтра в списке результатов. */
    val CATEGORIES = listOf("Все", "🎮 Игры", "🎬 Видео", "🎵 Музыка", "📚 Книги", "💾 Софт")

    private val VIDEO_EXT = listOf("mkv", "mp4", "avi", "bdrip", "webrip", "web-dl", "dvdrip", "hdtv", "x264", "x265", "hevc", "1080p", "720p", "2160p", "camrip", "bdremux")
    private val MUSIC_EXT = listOf("flac", "mp3", "lossless", "aac", "wav", "album", "discography", "ost", "soundtrack", "m4a", "opus")
    private val BOOK_EXT = listOf("epub", "fb2", "pdf", "mobi", "djvu", "cbz", "cbr", "manga", "манга", "книга", "novel", "роман", "аудиокнига", "lib")
    private val SOFT_EXT = listOf("exe", "msi", "apk", "iso", "dmg", "portable", "лицензия", "office", "windows", "photoshop", "kms")
    // «game»/«игра» из признаков убраны: в названиях фильмов они встречаются чаще, чем в играх
    private val GAME_EXT = listOf("repack", "репак", "nsz", "nsp", "xci", "switch", "ps4", "ps5", "xbox", "steam", "gog", "лицензия")

    /**
     * v4.42.0: к какой категории отнести раздачу.
     * Решаем по расширениям/словам в названии и по платформе. Это эвристика — но она объяснима,
     * и её всегда можно переопределить фильтром «Все».
     */
    fun classify(name: String, platform: String = "", source: String = ""): String {
        val n = name.lowercase()
        fun has(list: List<String>) = list.any { n.contains(it) }
        // v4.44.0: сначала признаки ФИЛЬМА/МУЗЫКИ/КНИГИ. Раньше слово «Game» в названии фильма
        // («Шерлок Холмс: Игра теней») отправляло видео в «Игры». Признак формата важнее слов.
        return when {
            has(VIDEO_EXT) -> "video"
            has(MUSIC_EXT) -> "music"
            has(BOOK_EXT) -> "book"
            has(GAME_EXT) || platform.isNotBlank() || source in CATALOG_SOURCES -> "game"
            has(SOFT_EXT) -> "soft"
            else -> "other"
        }
    }

    /**
     * v4.47.0: тип содержимого для записей Archive.org. Сначала смотрим на название,
     * и только если оно ничего не сказало — на mediatype самого сайта.
     */
    fun kindForArchive(title: String, mediaType: String): String {
        val byName = classify(title)
        if (byName != "other") return byName
        return when (mediaType.lowercase()) {
            "movies", "video", "tv" -> "video"
            "audio", "etree", "podcast" -> "music"
            "texts", "text" -> "book"
            "image", "images" -> "photo"
            "software", "games" -> "soft"
            else -> "other"
        }
    }

    /** Подпись и иконка категории для карточки: у каждой категории своя. */
    fun kindEmoji(kind: String): String = when (kind) {
        "game" -> "🎮"
        "video" -> "🎬"
        "music" -> "🎵"
        "book" -> "📚"
        "soft" -> "💾"
        "photo" -> "🖼"
        else -> "📦"
    }

    /** Категория → подпись чипа. */
    fun categoryMatches(chip: String, category: String): Boolean = when (chip) {
        "Все" -> true
        "🎮 Игры" -> category == "game"
        "🎬 Видео" -> category == "video"
        "🎵 Музыка" -> category == "music"
        "📚 Книги" -> category == "book"
        "💾 Софт" -> category == "soft"
        else -> true
    }

    /* ===================== качество и форматы ===================== */

    /** Разрешения, которые реально встречаются в названиях раздач. */
    val QUALITY_ORDER = listOf("2160p", "1080p", "720p", "480p", "360p", "240p")

    private fun qualityRank(q: String): Int = when (q) {
        "2160p" -> 2160
        "1080p" -> 1080
        "720p" -> 720
        "480p" -> 480
        "360p" -> 360
        "240p" -> 240
        else -> 0
    }

    /** Качество из названия раздачи: «…WEB-DL 720p…» → 720p. Пусто — если не указано. */
    fun qualityOf(name: String): String {
        val n = name.lowercase()
        val m = Regex("(\\d{3,4})\\s*p\\b").find(n)
        if (m != null) {
            val v = m.groupValues[1].toIntOrNull() ?: 0
            return when {
                v >= 2000 -> "2160p"
                v >= 1000 -> "1080p"
                v >= 700 -> "720p"
                v >= 460 -> "480p"
                v >= 340 -> "360p"
                v >= 200 -> "240p"
                else -> ""
            }
        }
        return when {
            Regex("\\b(4k|uhd)\\b").containsMatchIn(n) -> "2160p"
            Regex("\\bfull\\s?hd\\b").containsMatchIn(n) -> "1080p"
            Regex("\\bhd\\b").containsMatchIn(n) -> "720p"
            else -> ""
        }
    }

    /** Кодек из названия. Важно для воспроизведения: x265/HEVC декодируется не на всех телефонах. */
    fun codecOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("x265") || n.contains("h265") || n.contains("hevc") -> "x265 (HEVC)"
            n.contains("x264") || n.contains("h264") || n.contains("avc") -> "x264"
            n.contains("av1") -> "AV1"
            else -> ""
        }
    }

    /** Звук из названия: для музыки это важнее разрешения. */
    fun audioOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("flac") || n.contains("lossless") || n.contains("без потерь") -> "FLAC"
            n.contains("mp3") -> "MP3"
            n.contains("ac3") || n.contains("dolby digital") -> "AC3"
            n.contains("dts") -> "DTS"
            n.contains("aac") -> "AAC"
            else -> ""
        }
    }

    /** Запрос «360», «360p», «720p 60fps» → «360p». */
    private fun qualityFromQuery(words: List<String>): String {
        for (w in words) {
            val clean = w.removeSuffix("p").removeSuffix("р")
            val v = clean.toIntOrNull() ?: continue
            if (v !in 200..4320) continue
            return when {
                v >= 2000 -> "2160p"
                v >= 1000 -> "1080p"
                v >= 700 -> "720p"
                v >= 460 -> "480p"
                v >= 340 -> "360p"
                else -> "240p"
            }
        }
        for (w in words) {
            if (w == "4k" || w == "uhd") return "2160p"
            if (w == "hd" || w == "720") return "720p"
            if (w == "fullhd" || w == "fhd") return "1080p"
        }
        return ""
    }

    /**
     * Подобрать раздачи под запрошенное качество.
     * Сначала точное совпадение; если такого нет — ближайшие по разрешению (вверх и вниз),
     * чтобы человек видел, что реально есть в раздачах, а не пустой экран.
     */
    fun filterByQuality(items: List<Found>, wanted: String): List<Found> {
        if (wanted.isBlank()) return items
        val exact = items.filter { it.quality == wanted }
        if (exact.isNotEmpty()) return exact
        val target = qualityRank(wanted)
        return items
            .filter { it.quality.isNotBlank() }
            .sortedBy { kotlin.math.abs(qualityRank(it.quality) - target) }
            .ifEmpty { items }
    }

    /** Какие качества вообще есть в текущем списке — для подсказки «в наличии: …». */
    fun availableQualities(items: List<Found>): List<String> =
        items.map { it.quality }.filter { it.isNotBlank() }.distinct()
            .sortedByDescending { qualityRank(it) }

    /** Подпись формата для карточки: «720p · x264 · FLAC». */
    fun formatLabel(f: Found): String = listOf(f.quality, f.codec, f.audio)
        .filter { it.isNotBlank() }.joinToString(" · ")

    /** Убрать из запроса к сайтам разрешение и формат: сайты ищут по названию. */
    fun stripQueryNoise(query: String): String {
        val words = query.split(' ').filter { w ->
            val clean = w.removeSuffix("p").removeSuffix("р")
            val num = clean.toIntOrNull()
            num == null || num !in 200..4320
        }.filter { w ->
            w.lowercase() !in setOf("4k", "uhd", "fhd", "fullhd", "hd", "flac", "mp3", "lossless", "x264", "x265", "hevc", "av1", "ac3", "dts", "aac")
        }
        return words.joinToString(" ").trim().ifBlank { query }
    }

    /* ===================== страница раздачи → magnet/.torrent ===================== */

    /**
     * v4.45.0: универсальный разбор ЛЮБОЙ страницы раздачи, найденной где угодно (в том числе
     * через поисковик). Понимает:
     *   • magnet: прямо в странице раздачи;
     *   • .torrent по ссылке (в том числе протокол-относительной //host/file.torrent);
     *   • DLE-сайты: index.php?do=download&id=N (byxatab, torrent-igruha, tapochek…);
     *   • download.php / dl.php / d.rutor.info/download/N / gettorrent (rutor, kinozal, nnmclub).
     * Ссылки-редиректы поисковиков (google /url?q=, DDG uddg=, Bing a1…base64) разворачиваются.
     */
    fun fromPageUrl(pageUrl: String, title: String = "", timeoutMs: Int = 8000, depth: Int = 0): Found? {
        val url = resolveSearchRedirect(pageUrl)
        if (!url.startsWith("http")) return null
        val page = httpGet(url, timeoutMs) ?: return null
        val host = url.substringAfter("://").substringBefore('/')

        val magnet = Regex("""magnet:\?xt=urn:btih:[A-Fa-f0-9]{20,}[^"'<\s]*""").find(page)?.value
        val torrentUrl = if (magnet != null) "" else (findTorrentLink(page, url) ?: "")
        // v4.46.0: поисковик часто приводит на СТРАНИЦУ-СПИСОК сайта (mbkino, catorrent, byxatab),
        // где ссылок на .torrent ещё нет — они на странице самой раздачи. Заходим на один уровень глубже.
        if (magnet.isNullOrBlank() && torrentUrl.isBlank()) {
            if (depth >= 1) return null
            for (topic in topicLinks(page, url, title, 2)) {
                val deeper = try {
                    fromPageUrl(topic, title, timeoutMs, depth + 1)
                } catch (_: Throwable) {
                    null
                }
                if (deeper != null) return deeper
            }
            return null
        }

        // v4.45.0: «11 КБ» на странице — это размер самой страницы, а не раздачи.
        // Меньше 1 МБ считаем «размер неизвестен», чтобы не показывать выдуманные цифры.
        val sizeText = Regex("""(?:Размер|Раздают|Size)[^0-9]{0,30}([0-9]+[.,]?[0-9]*\s*(?:[KMGT]?[Bb]|Гб|Мб|Кб))""")
            .find(page)?.groupValues?.get(1) ?: ""
        val sizeBytes = TorrentRescue.parseSizeLabel(sizeText).takeIf { it >= 1_000_000L } ?: 0L

        val realTitle = title.ifBlank {
            Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(page)
                ?.groupValues?.get(1)?.trim().orEmpty()
        }
        return Found(
            name = cleanPageTitle(realTitle).ifBlank { url.substringAfterLast('/') },
            magnet = magnet.orEmpty(),
            torrentUrl = torrentUrl,
            referer = url,
            sizeBytes = sizeBytes,
            seeders = 0,
            source = host,
            platform = Regex("""Платформа[^>]*>(?:\s*<[^>]+>)*\s*([A-Za-zА-Яа-я][A-Za-zА-Яа-я0-9 \-]{2,24})""")
                .find(page)?.groupValues?.get(1)?.trim() ?: "",
            poster = extractPoster(page, url)
        )
    }

    /**
     * Ссылки на страницы раздач внутри страницы-списка. Берём те, чей текст похож на наш заголовок
     * (по словам), — чтобы не уйти на «соседний» фильм или на архив новостей.
     */
    private fun topicLinks(page: String, pageUrl: String, title: String, limit: Int): List<String> {
        val host = pageUrl.substringAfter("://").substringBefore('/')
        val topicPattern = Regex(
            """href=["']([^"']*(?:/torrent/\d+|viewtopic\.php\?t=\d+|/topic/\d+|/view/\d+|/\d+-[a-z0-9-]+\.html|do=view&id=\d+)[^"']*)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val wanted = title.lowercase().split(Regex("""[^a-zа-я0-9]+"""))
            .filter { it.length > 3 }.toSet()
        if (wanted.isEmpty()) return emptyList()
        val scored = mutableListOf<Pair<Int, String>>()
        for (m in topicPattern.findAll(page)) {
            val href = m.groupValues[1].replace("&amp;", "&")
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), " ").lowercase()
            val hits = wanted.count { text.contains(it) }
            if (hits < 2) continue
            val abs = when {
                href.startsWith("http") -> href
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://$host$href"
                else -> "https://$host/$href"
            }
            scored.add(hits to abs)
        }
        return scored.sortedByDescending { it.first }.map { it.second }.distinct().take(limit)
    }

    /**
     * Найти на странице ссылку на сам .torrent. v4.46.0: кандидаты оцениваются — прямой адрес
     * `.../file.torrent` лучше, чем `download.php?id=N`, а «обёртки» вида `?u=<ссылка>` хуже всех
     * (по ним сначала идёт редирект через чужой сайт и часто отдаётся HTML вместо торрента).
     */
    private fun findTorrentLink(page: String, pageUrl: String): String? {
        val host = pageUrl.substringAfter("://").substringBefore('/')
        val hrefs = Regex("""(?:href|data-href|data-url)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(page).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        val topic = Regex(
            """\.torrent(?:[?#]|$)|do=download|download\.php|dl\.php|gettorrent|/download/\d+""",
            RegexOption.IGNORE_CASE
        )
        var best: String? = null
        var bestScore = -1
        for (h in hrefs) {
            if (h.startsWith("javascript") || h.startsWith("#")) continue
            if (!topic.containsMatchIn(h)) continue
            var score = 0
            val path = h.substringBefore('?').substringBefore('#')
            if (path.endsWith(".torrent", true)) score += 3
            if (Regex("""do=download|download\.php|dl\.php|gettorrent""", RegexOption.IGNORE_CASE).containsMatchIn(h)) score += 2
            if (Regex("""[?&]u=https?://""").containsMatchIn(h)) {
                score -= 2
                // в обёртке может лежать прямая ссылка — достаём её
                val inner = Regex("""[?&]u=(https?://[^&]+)""").find(h)?.groupValues?.get(1)
                if (inner != null) {
                    val dec = try { java.net.URLDecoder.decode(inner, "UTF-8") } catch (_: Throwable) { inner }
                    if (dec.endsWith(".torrent", true)) return dec
                }
            }
            if (h.startsWith("magnet:")) continue
            val abs = when {
                h.startsWith("http") -> h
                h.startsWith("//") -> "https:$h"
                h.startsWith("/") -> "https://$host$h"
                else -> "https://$host/$h"
            }
            if (score > bestScore) {
                bestScore = score
                best = abs
            }
        }
        return best
    }

    /** Развернуть ссылку-редирект поисковика в настоящий адрес. */
    fun resolveSearchRedirect(link: String): String {
        try {
            if (link.contains("duckduckgo.com/l/")) {
                val target = Regex("""[?&]uddg=([^&]+)""").find(link)?.groupValues?.get(1)
                if (target != null) return java.net.URLDecoder.decode(target, "UTF-8")
            }
            if (link.contains("/url?")) {
                for (key in listOf("q", "url")) {
                    val target = Regex("""[?&]$key=([^&]+)""").find(link)?.groupValues?.get(1) ?: continue
                    val dec = java.net.URLDecoder.decode(target, "UTF-8")
                    if (dec.startsWith("http")) return dec
                }
            }
            if (link.contains("bing.com/ck/a") && link.contains("u=a1")) {
                val b64 = Regex("""[?&]u=a1([^&]+)""").find(link)?.groupValues?.get(1)
                if (b64 != null) {
                    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                    val dec = String(
                        android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
                    )
                    if (dec.startsWith("http")) return dec
                }
            }
        } catch (_: Throwable) {
        }
        return link
    }

    private fun cleanPageTitle(raw: String): String =
        raw.replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\s*[|—–-]\s*(скачать|торрент|torrent|бесплатно).*$"""), "")
            .trim()
            .take(110)
}
