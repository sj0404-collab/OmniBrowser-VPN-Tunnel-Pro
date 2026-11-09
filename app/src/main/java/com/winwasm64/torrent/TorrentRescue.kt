package com.winwasm64.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.38.0: спасение мёртвой раздачи.
 *
 * Проблема с устройства: раздача «Elliot Quest [NSZ]» стоит на 0 из 111 МБ, пиры 0/8 — трекеры знают
 * о пирах, но соединений нет. Дальше качается пустышка либо ничего. Человеку нужно не «попробуйте
 * ещё раз», а чтобы приложение само поискало ту же самую вещь там, где она живая.
 *
 * Что делает этот объект:
 *  1) достаёт из имени раздачи осмысленный запрос («[catorrent.net]-elliot-quest-nsz.torrent» →
 *     «elliot quest»);
 *  2) ищет такую же вещь в публичных каталогах (apibay/TPB, torrents-csv, nyaa);
 *  3) выбирает кандидата по трём условиям сразу: похожее имя, сопоставимый размер, ЕСТЬ сиды;
 *  4) возвращает magnet — движок запускает уже его, а мёртвую задачу останавливает.
 *
 * Разбор и выбор — чистые функции, их видно тестами без сети. Сеть — отдельно, с короткими таймаутами
 * и без падений: не нашлось — значит не нашлось, и об этом будет сказано прямо.
 */
object TorrentRescue {

    /** Кандидат на замену. */
    data class Candidate(
        val name: String,
        val magnet: String,
        val sizeBytes: Long,
        val seeders: Int,
        val source: String
    )

    /** Дополнительные публичные трекеры: их много, и они часто знают о пирах больше, чем список в .torrent. */
    val EXTRA_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "https://tracker.tamersunion.org:443/announce",
        "http://bt1.archive.org:6969/announce",
        "http://bt2.archive.org:6969/announce"
    )

    /* ===================== чистая логика ===================== */

    private val NOISE = setOf(
        "torrent", "torrents", "nsp", "nsz", "xci", "cia", "3ds", "switch", "pc", "ps4", "ps5",
        "repack", "rip", "webrip", "web", "dl", "rus", "eng", "multi", "iso", "exe", "apk",
        "каторрент", "catorrent", "nnm", "club", "net", "com", "org", "www",
        // служебные слова — в поиске по каталогам только мешают
        "the", "of", "and", "for", "with", "und", "der", "die", "das"
    )

    /**
     * Имя раздачи → поисковый запрос.
     * `[catorrent.net]-elliot-quest-nsz.torrent` → `elliot quest`
     */
    fun cleanQuery(raw: String): String {
        var s = raw.trim()
        // расширения отрезаем по одному: substringBeforeLast с общим missingDelimiterValue
        // сбрасывал строку к исходной, если расширения не было
        for (ext in listOf(".torrent", ".nsp", ".nsz", ".xci", ".zip", ".rar")) {
            if (s.endsWith(ext, ignoreCase = true)) s = s.dropLast(ext.length)
        }
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ")          // [NSZ], [catorrent.net]
        s = s.replace(Regex("\\([^)]*\\)"), " ")           // (2023), (RU)
        s = s.replace('_', ' ').replace('.', ' ').replace('-', ' ').replace('+', ' ')
        val words = s.split(Regex("\\s+"))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it !in NOISE && it.any { c -> c.isLetter() } }
        return words.joinToString(" ").take(80)
    }

    /**
     * Похожесть названий по общим словам: 0 — ничего общего, 1 — те же слова.
     *
     * v4.40.0: делим на МЕНЬШИЙ набор слов, а не на больший. Иначе запрос «stalker» против
     * «STALKER Тень Чернобыля (PC) Repack» давал 0.33 и годная раздача отбрасывалась как «не похожая».
     * Для нас важно, покрыты ли слова запроса, а не сколько лишнего в названии раздачи.
     * Одиночное совпадение всё же считается слабым доказательством и слегка занижается.
     */
    fun similarity(a: String, b: String): Double {
        val wa = cleanQuery(a).split(' ').filter { it.length >= 2 }.toSet()
        val wb = cleanQuery(b).split(' ').filter { it.length >= 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        var hits = 0
        for (w in wa) {
            if (wb.any { it == w || (w.length >= 4 && (it.startsWith(w) || w.startsWith(it))) }) hits++
        }
        if (hits == 0) return 0.0
        val denom = minOf(wa.size, wb.size).toDouble()
        val raw = hits / denom
        return if (hits == 1 && minOf(wa.size, wb.size) == 1) raw * 0.8 else raw
    }

    /**
     * Выбрать лучшего кандидата.
     * Условия жёсткие, потому что цена ошибки — скачать не то:
     *  · сидов не меньше [minSeeders];
     *  · имя похоже не меньше чем на [minSimilarity];
     *  · размер (если известен ожидаемый) в пределах 40 %…250 % — иначе это другая вещь.
     */
    fun pickBest(
        candidates: List<Candidate>,
        expectedName: String,
        expectedBytes: Long,
        minSeeders: Int = 3,
        minSimilarity: Double = 0.5
    ): Candidate? {
        val expectedQ = cleanQuery(expectedName)
        return candidates
            .filter { it.seeders >= minSeeders && it.magnet.startsWith("magnet:", true) }
            .filter { similarity(expectedQ, it.name) >= minSimilarity }
            .filter {
                if (expectedBytes <= 0L || it.sizeBytes <= 0L) true
                else it.sizeBytes in (expectedBytes * 4 / 10)..(expectedBytes * 25 / 10)
            }
            .maxWithOrNull(
                compareBy<Candidate> { it.seeders }
                    .thenBy { similarity(expectedQ, it.name) }
                    .thenBy { -it.sizeBytes }
            )
    }

    /* ===================== сеть ===================== */

    /** Поиск по всем источникам. Блокирующий вызов — звать из фонового потока. */
    fun searchBlocking(query: String, timeoutMs: Int = 6000): List<Candidate> {
        val q = cleanQuery(query)
        if (q.isBlank()) return emptyList()
        val out = ArrayList<Candidate>()
        out.addAll(searchApibay(q, timeoutMs))
        out.addAll(searchTorrentsCsv(q, timeoutMs))
        out.addAll(searchNyaa(q, timeoutMs))
        return out.distinctBy { it.magnet.lowercase() }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OmniBrowser")
                setRequestProperty("Accept", "application/json, text/xml, */*")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun magnetOf(hash: String, name: String): String {
        val tr = "&tr=" + java.net.URLEncoder.encode("udp://tracker.opentrackr.org:1337/announce", "UTF-8")
        return "magnet:?xt=urn:btih:$hash&dn=" + URLEncoder.encode(name, "UTF-8") + tr
    }

    /** apibay (The Pirate Bay). Часть зеркал недоступна — пробуем по очереди. */
    private fun searchApibay(query: String, timeoutMs: Int): List<Candidate> {
        val q = URLEncoder.encode(query, "UTF-8")
        for (host in listOf("https://apibay.org", "https://tpb.party/apibay", "https://thepiratebay10.org/apibay")) {
            val body = httpGet("$host/q.php?q=$q&cat=0", timeoutMs) ?: continue
            val arr = try {
                JSONArray(body)
            } catch (_: Throwable) {
                continue
            }
            val out = ArrayList<Candidate>()
            for (i in 0 until minOf(arr.length(), 40)) {
                val o = arr.optJSONObject(i) ?: continue
                val hash = o.optString("info_hash")
                val name = o.optString("name")
                if (hash.isBlank() || name.isBlank() || name == "No results returned") continue
                out.add(
                    Candidate(
                        name = name,
                        magnet = magnetOf(hash, name),
                        sizeBytes = o.optString("size").toLongOrNull() ?: 0L,
                        seeders = o.optString("seeders").toIntOrNull() ?: 0,
                        source = "apibay"
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** torrents-csv — открытая база magnet-ссылок. */
    private fun searchTorrentsCsv(query: String, timeoutMs: Int): List<Candidate> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://torrents-csv.com/service/search?q=$q&size=30", timeoutMs) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("torrents") ?: return emptyList()
            val out = ArrayList<Candidate>()
            for (i in 0 until minOf(arr.length(), 30)) {
                val o = arr.optJSONObject(i) ?: continue
                val hash = o.optString("infohash")
                val name = o.optString("name")
                if (hash.isBlank() || name.isBlank()) continue
                out.add(
                    Candidate(
                        name = name,
                        magnet = magnetOf(hash, name),
                        sizeBytes = o.optLong("size_bytes", 0L),
                        seeders = o.optInt("seeders", 0),
                        source = "torrents-csv"
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** nyaa.si — там много игр и образов, отдаёт RSS с числом сидов и infoHash. */
    private fun searchNyaa(query: String, timeoutMs: Int): List<Candidate> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://nyaa.si/?page=rss&q=$q&c=0_0&f=0", timeoutMs) ?: return emptyList()
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(body)
        val out = ArrayList<Candidate>()
        for (m in items) {
            val block = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: continue
            val hash = Regex("<nyaa:infoHash>(.*?)</nyaa:infoHash>").find(block)?.groupValues?.get(1)?.trim()
            val seeders = Regex("<nyaa:seeders>(.*?)</nyaa:seeders>").find(block)?.groupValues?.get(1)
                ?.trim()?.toIntOrNull() ?: 0
            val size = Regex("<nyaa:size>(.*?)</nyaa:size>").find(block)?.groupValues?.get(1)?.trim() ?: ""
            if (hash.isNullOrBlank()) continue
            out.add(
                Candidate(
                    name = title,
                    magnet = magnetOf(hash, title),
                    sizeBytes = parseSizeLabel(size),
                    seeders = seeders,
                    source = "nyaa"
                )
            )
        }
        return out
    }

    /** «1.4 GiB» → байты. */
    fun parseSizeLabel(raw: String): Long {
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([KMGT]?i?B)", RegexOption.IGNORE_CASE).find(raw) ?: return 0L
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024
            "GB", "GIB" -> 1024.0 * 1024 * 1024
            "TB", "TIB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (value * mult).toLong()
    }
}
