package com.winwasm64.torrent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.55.0: картинки напрямую из Яндекса.
 *
 * Жалоба: «почему не берёт с Яндекса, а ищет только плохое качество и битые». Причина — раньше
 * источники были только iTunes/Wikimedia/Википедия, а для русских запросов («манга страж») там
 * попадались обложки низкого разрешения или чужие картинки.
 *
 * Яндекс на страницу выдачи кладёт JSON с полями `origUrl` (полноразмерная картинка) и `url`.
 * Берём именно `origUrl` — это исходник, а не превью 100×100.
 */
object YandexImages {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Mobile Safari/537.36"

    fun search(query: String, page: Int = 0): List<GalleryImage> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val url = "https://yandex.ru/images/search?text=" + URLEncoder.encode(clean, "UTF-8") +
            "&p=" + page
        val html = httpGet(url) ?: return emptyList()
        val out = mutableListOf<GalleryImage>()
        // полноразмерные ссылки
        val orig = Regex(""""origUrl":"(https?://[^"]+)"""").findAll(html).map { it.groupValues[1] }
        // обычные ссылки — как резерв, если origUrl не оказалось
        val plain = Regex(""""url":"(https?://[^"]+\.(?:jpg|jpeg|png|webp))"""").findAll(html)
            .map { it.groupValues[1] }
        val titles = Regex(""""title":"([^"]{3,120})"""").findAll(html).map { it.groupValues[1] }.toList()

        var idx = 0
        for (link in (orig + plain)) {
            if (out.any { it.url == link }) continue
            // отбрасываем служебные пиксели и заглушки
            if (link.contains("captcha", true) || link.contains("favicon", true)) continue
            if (link.endsWith(".svg") || link.endsWith(".gif")) continue
            val title = titles.getOrNull(idx) ?: clean
            idx++
            out.add(
                GalleryImage(
                    url = link,
                    title = TorrentSearchAgent.decodeEntities(title),
                    source = "Яндекс Картинки",
                    pageUrl = "https://yandex.ru/images/search?text=" + URLEncoder.encode(clean, "UTF-8")
                )
            )
            if (out.size >= 40) break
        }
        return out
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 14_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Throwable) {
        null
    }
}
