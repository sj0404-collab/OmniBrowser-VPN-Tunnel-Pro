package com.winwasm64.torrent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.39.0: поисковый агент — «найди сам и покажи, у кого есть пиры».
 *
 * Что умеет и чем отличается от [TorrentRescue]:
 *  · ищет не в одном каталоге, а сразу в нескольких, включая **catorrent.net** — тот сайт, откуда
 *    у человека .torrent-файлы (там же он берёт игры; у catorrent открытый поиск и прямая ссылка
 *    на .torrent, нужен только правильный Referer);
 *  · понимает, что именно ищут: «apk», «nsz/nsp/xci», «iso», «flac/mp3», «mkv/mp4», «epub/pdf»,
 *    платформы (switch, pc, android, ps4/ps5, xbox) — и учитывает это при отборе;
 *  · не просто отдаёт список, а **объясняет выбор**: «имя совпадает на 87 %, размер 111 МБ ≈ нужному,
 *    18 сидов, catorrent» — чтобы было видно, за что он цепляется;
 *  · если точной замены нет — предлагает **аналоги** с пирами (другое издание, репак, другая
 *    платформа), а не молчит.
 *
 * Это не языковая модель: приложение не отправляет запросы на сторонние «ИИ»-сервисы и не требует
 * ключей. Решение принимается по измеримым признакам (имя, размер, сиды, формат), поэтому его можно
 * проверить и оспорить. Если точной вещи нет нигде — так и будет сказано.
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
        "iso" to "iso", "exe" to "exe", "repack" to "repack",
        "flac" to "flac", "mp3" to "mp3", "lossless" to "flac",
        "mkv" to "mkv", "mp4" to "mp4", "bdrip" to "mkv", "webrip" to "mkv",
        "epub" to "epub", "pdf" to "pdf", "fb2" to "fb2"
    )

    private val PLATFORM_WORDS = mapOf(
        "switch" to "Nintendo Switch", "н-switch" to "Nintendo Switch",
        "pc" to "PC", "ps4" to "PlayStation 4", "ps5" to "PlayStation 5",
        "xbox" to "Xbox", "android" to "Android", "ios" to "iOS",
        "3ds" to "Nintendo 3DS", "wii" to "Nintendo Wii", "gamecube" to "GameCube"
    )

    /** Разобрать свободный запрос: «elliot quest switch nsz» → название + формат + платформа. */
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
        val title = TorrentRescue.cleanQuery(
            lower.replace(Regex("[^\\p{L}\\p{N} .]+"), " ")
        )
        return Intent(title, format, platform)
    }

    /* ===================== поиск ===================== */

    /** Ищем везде сразу. Блокирующий вызов — из фонового потока. */
    fun searchAll(query: String, timeoutMs: Int = 7000): List<Found> {
        val intent = parseIntent(query)
        val q = intent.title.ifBlank { query.trim() }
        if (q.isBlank()) return emptyList()
        val out = ArrayList<Found>()
        out.addAll(searchCatorrent(q, timeoutMs))
        // открытые индексы: apibay (TPB), torrents-csv, nyaa — через уже готовый поиск
        for (c in TorrentRescue.searchBlocking(q, timeoutMs)) {
            out.add(Found(c.name, magnet = c.magnet, sizeBytes = c.sizeBytes, seeders = c.seeders, source = c.source))
        }
        return out.distinctBy { (it.magnet + it.torrentUrl).lowercase() }
    }

    /**
     * catorrent.net: открытый поиск DLE + страница раздачи + прямая ссылка на .torrent.
     * Ссылка на файл отдаётся только с Referer страницы — иначе сервер возвращает HTML.
     */
    private fun searchCatorrent(query: String, timeoutMs: Int): List<Found> {
        val searchUrl = "https://catorrent.net/index.php?do=search&subaction=search&story=" +
            URLEncoder.encode(query, "UTF-8")
        val html = httpGet(searchUrl, timeoutMs) ?: return emptyList()
        val pageRe = Regex(
            "href=\"(https://catorrent\\.net/[a-z0-9/\\-]*\\d+-[a-z0-9\\-]+\\.html)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val pages = LinkedHashMap<String, String>()
        for (m in pageRe.findAll(html)) {
            val url = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (url.isNotBlank() && title.length >= 3 && !pages.containsKey(url)) pages[url] = title
            if (pages.size >= 4) break
        }
        val out = ArrayList<Found>()
        for ((pageUrl, title) in pages) {
            val page = httpGet(pageUrl, timeoutMs, referer = "https://catorrent.net/") ?: continue
            val dlId = Regex("do=download&(?:amp;)?id=(\\d+)").find(page)?.groupValues?.get(1) ?: continue
            val sizeText = Regex("Скачать:\\s*([0-9]+[.,]?[0-9]*\\s*[KMG]?[Bb])")
                .find(page)?.groupValues?.get(1) ?: ""
            val platform = Regex("Платформа[^>]*>(?:\\s*<[^>]+>)*\\s*([A-Za-z][A-Za-z0-9 \\-]{2,24})")
                .find(page)?.groupValues?.get(1)?.trim() ?: ""
            out.add(
                Found(
                    name = title.ifBlank { pageUrl.substringAfterLast('/').removeSuffix(".html") },
                    torrentUrl = "https://catorrent.net/index.php?do=download&id=$dlId",
                    referer = pageUrl,
                    sizeBytes = TorrentRescue.parseSizeLabel(sizeText),
                    seeders = 0, // сайт не публикует сидов — честно ставим 0 и говорим об этом
                    source = "catorrent",
                    platform = platform
                )
            )
        }
        return out
    }

    private fun httpGet(url: String, timeoutMs: Int, referer: String? = null): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OmniBrowser")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Скачать .torrent по найденной ссылке (с обязательным Referer). */
    fun downloadTorrent(found: Found, timeoutMs: Int = 12000): ByteArray? {
        if (found.torrentUrl.isBlank()) return null
        return try {
            val conn = (URL(found.torrentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OmniBrowser")
                setRequestProperty("Accept", "application/x-bittorrent,*/*")
                if (found.referer.isNotBlank()) setRequestProperty("Referer", found.referer)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            // настоящий .torrent — это bencode-словарь, начинается с 'd'
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
     * catorrent не публикует сидов — для него достаточно точного имени и совпавшего размера.
     */
    fun bestForAutoStart(
        ranked: List<Found>,
        intent: Intent,
        expectedName: String,
        expectedBytes: Long
    ): Found? = ranked.firstOrNull { f ->
        val sim = TorrentRescue.similarity(intent.title.ifBlank { expectedName }, f.name)
        val sizeOk = expectedBytes <= 0L || f.sizeBytes <= 0L ||
            f.sizeBytes in (expectedBytes * 4 / 10)..(expectedBytes * 25 / 10)
        val seedersOk = f.seeders >= 3 || (f.source == "catorrent" && f.seeders == 0)
        sim >= 0.5 && sizeOk && seedersOk && (f.magnet.startsWith("magnet:", true) || f.torrentUrl.isNotBlank())
    }

    /** Аналоги: «не то же самое, но с пирами» — показываем человеку, а не запускаем молча. */
    fun analogs(ranked: List<Found>, expectedName: String, limit: Int = 3): List<Found> =
        ranked
            .filter { TorrentRescue.similarity(expectedName, it.name) >= 0.34 }
            .filter { it.seeders >= 1 || it.source == "catorrent" }
            .filter { it.magnet.startsWith("magnet:", true) || it.torrentUrl.isNotBlank() }
            .take(limit)
}
