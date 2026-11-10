package com.winwasm64.torrent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.48.0: поиск картинок БЕЗ открытия Google/Яндекса — прямо в приложении.
 *
 * Раньше кнопка «Фото» открывала страницу поиска картинок в WebView: приходилось листать выдачу
 * поисковика и разбираться с чужими баннерами. Теперь картинки берутся из открытых API, которые
 * отдают готовый список URL — галерея показывается в самой вкладке.
 *
 * Источники (без ключей и регистрации):
 *  • iTunes Search API — обложки альбомов, постеры сериалов, обложки книг/комиксов, иконки приложений
 *    и игр. Для каждого фильтра — свой тип (`entity`), поэтому в «Обложках книг» не будет скриншотов.
 *  • Wikimedia Commons — обычные фотографии по теме (люди, техника, кадры, логотипы).
 */
data class GalleryImage(
    val url: String,
    val title: String,
    val source: String,
    val pageUrl: String
)

object ImageSearch {

    private val ALIASES = listOf(
        listOf("черепашк", "tmnt", "teenage mutant ninja turtles"),
        listOf("паук", "spider-man", "spiderman"),
        listOf("бэтмен", "batman"),
        listOf("супермен", "superman"),
        listOf("железный человек", "iron man"),
        listOf("мстител", "avengers"),
        listOf("дэдпул", "deadpool"),
        listOf("гарри поттер", "harry potter"),
        listOf("властелин колец", "lord of the rings"),
        listOf("звездные войны", "star wars"),
        listOf("терминатор", "terminator"),
        listOf("матрица", "the matrix"),
        listOf("чужой", "alien"),
        listOf("хищник", "predator"),
        listOf("форсаж", "fast and furious"),
        listOf("марио", "super mario"),
        listOf("соник", "sonic"),
        listOf("покемон", "pokemon"),
        listOf("наруто", "naruto"),
        listOf("ван пис", "one piece"),
        listOf("атака титанов", "attack on titan"),
        listOf("ведьмак", "witcher"),
        listOf("сталкер", "stalker"),
        listOf("шерлок", "sherlock"),
        listOf("дюна", "dune")
    )

    /** То же название латиницей/по-английски — чтобы «черепашки» находили TMNT. */
    fun aliasesFor(query: String): List<String> {
        val q = query.lowercase()
        val extra = mutableListOf<String>()
        for (group in ALIASES) {
            if (group.any { q.contains(it) }) {
                group.filter { it != q }.forEach { if (!extra.contains(it)) extra.add(it) }
            }
        }
        return extra.take(2)
    }

    fun search(query: String, filter: String): List<GalleryImage> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<GalleryImage>()
        val variants = listOf(clean) + aliasesFor(clean)
        for (v in variants) {
            out.addAll(itunes(v, filter))
        }
        for (v in variants.take(2)) {
            out.addAll(commons(v, filter))
        }
        return out.distinctBy { it.url }.take(60)
    }

    /* ===================== iTunes (обложки и постеры) ===================== */

    private fun entityFor(filter: String): List<String> = when (filter) {
        "Обложки игр" -> listOf("software", "macSoftware", "iPadSoftware")
        "Постеры фильмов" -> listOf("movie")
        "Обложки книг" -> listOf("ebook")
        "Кадры из видео" -> listOf("tvSeason", "movie")
        "Альбомы музыки" -> listOf("album")
        else -> listOf("album", "ebook", "software", "tvSeason")
    }

    private fun itunes(query: String, filter: String): List<GalleryImage> {
        val out = mutableListOf<GalleryImage>()
        val q = URLEncoder.encode(query, "UTF-8")
        for (entity in entityFor(filter).take(2)) {
            val body = httpGet("https://itunes.apple.com/search?term=$q&entity=$entity&limit=12&country=us")
                ?: continue
            try {
                val results = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val art = r.optString("artworkUrl100")
                    if (art.isBlank()) continue
                    // 100x100 → 600x600: та же картинка, но пригодная для просмотра
                    val big = art.replace("100x100bb", "600x600bb").replace("/100x100/", "/600x600/")
                    val name = r.optString("trackName").ifBlank {
                        r.optString("collectionName").ifBlank { r.optString("artistName", query) }
                    }
                    out.add(
                        GalleryImage(
                            url = big,
                            title = TorrentSearchAgent.decodeEntities(name),
                            source = "iTunes · $entity",
                            pageUrl = r.optString("trackViewUrl").ifBlank { "https://itunes.apple.com" }
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /* ===================== Wikimedia Commons (обычные фото) ===================== */

    private fun commons(query: String, filter: String): List<GalleryImage> {
        val suffix = when (filter) {
            "Обложки игр" -> " video game cover"
            "Постеры фильмов" -> " film poster"
            "Обложки книг" -> " book cover"
            "Кадры из видео" -> " screenshot"
            "Альбомы музыки" -> " album cover"
            else -> ""
        }
        val q = URLEncoder.encode(query + suffix, "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=$q&gsrnamespace=6&gsrlimit=24&prop=imageinfo&iiprop=url&iiurlwidth=600&format=json"
        val body = httpGet(url) ?: return emptyList()
        val out = mutableListOf<GalleryImage>()
        try {
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val title = page.optString("title").removePrefix("File:")
                val ext = title.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif")) continue
                val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val thumb = info.optString("thumburl").ifBlank {
                    info.optString("url")
                }
                if (thumb.isBlank()) continue
                out.add(
                    GalleryImage(
                        url = thumb,
                        title = TorrentSearchAgent.decodeEntities(title),
                        source = "Wikimedia Commons",
                        pageUrl = info.optString("descriptionurl").ifBlank { "https://commons.wikimedia.org" }
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniBrowser/4.48 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Throwable) {
            null
        }
    }
}
