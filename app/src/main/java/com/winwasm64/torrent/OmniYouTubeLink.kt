package com.winwasm64.torrent

import com.santiago43rus.rupoop.data.Author
import com.santiago43rus.rupoop.data.SearchResult
import com.santiago43rus.rupoop.network.OmniYouTubeBridge
import com.winwasm64.ui.browser.NetDirect

/**
 * v4.89.0: YouTube внутри «Кино» (Rupoop).
 *
 * Пользователь просил не отдельный «наш поиск», а ютуб прямо в интерфейсе Rupoop —
 * там же, где RuTube, ВК и ОК. Этот файл подключает к модулю `:rupoop` наши уже
 * работающие механизмы YouTube: поиск (innertube через канал/VPN приложения),
 * ленту канала (официальный RSS канала) и получение прямого потока для плеера.
 */
object OmniYouTubeLink {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var installed = false

    /** Вызывается один раз при старте приложения. */
    fun install() {
        if (installed) return
        installed = true
        OmniYouTubeBridge.install(
            search = { query -> search(query) },
            channel = { ref -> channelVideos(ref) },
            stream = { url -> stream(url) }
        )
    }

    // ── поиск ──
    private fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val page = try {
            YouTubeSearch.search(q, "Все", 0, "Любая")
        } catch (_: Throwable) {
            return emptyList()
        }
        return page.items.mapNotNull { item ->
            if (item.kind != YouTubeSearch.KIND_VIDEO) return@mapNotNull null
            val id = item.id
            if (id.length != 11) return@mapNotNull null
            SearchResult(
                videoUrl = "https://www.youtube.com/watch?v=$id",
                title = item.title.ifBlank { "YouTube" },
                thumbnailUrl = item.thumb.ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" },
                author = Author(
                    id = null,
                    name = item.author.ifBlank { "YouTube" },
                    avatarUrl = null,
                    platform = "YOUTUBE",
                    url = item.channelId.takeIf { it.startsWith("UC") }
                        ?.let { "https://www.youtube.com/channel/$it" }
                ),
                duration = item.lengthSec.takeIf { it > 0 },
                tags = if (item.extra == "live") listOf("live") else emptyList(),
                createdTs = null,
                publicationTs = null,
                hits = item.views.takeIf { it > 0 }?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
            )
        }
    }

    // ── видео канала ──
    /** Публично: этим пользуется и проверка подписок (🔔). */
    fun channelVideos(ref: String): List<SearchResult> {
        val channelId = channelIdOf(ref)
        if (channelId.isNullOrBlank()) return emptyList()
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val (code, xml) = try {
            NetDirect.requestText(url, mapOf("User-Agent" to UA), timeoutMs = 9_000)
        } catch (_: Throwable) {
            return emptyList()
        }
        if (code !in 200..299 || xml.isBlank()) return emptyList()

        val channelTitle = Regex("""<title>([^<]*)</title>""").find(xml)
            ?.groupValues?.get(1)?.let { decode(it) } ?: ref
        val out = ArrayList<SearchResult>()
        for (entry in Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val e = entry.groupValues[1]
            val id = Regex("""<yt:videoId>([^<]+)</yt:videoId>""").find(e)?.groupValues?.get(1) ?: continue
            val title = Regex("""<title[^>]*>([^<]*)</title>""").find(e)?.groupValues?.get(1)
                ?.let { decode(it) } ?: continue
            val published = Regex("""<published>([^<]+)</published>""").find(e)?.groupValues?.get(1)
            val views = Regex("""views="(\d+)"""").find(e)?.groupValues?.get(1)?.toLongOrNull()
            val thumb = Regex("""<media:thumbnail[^>]*url="([^"]+)"""").find(e)?.groupValues?.get(1)
                ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
            out.add(
                SearchResult(
                    videoUrl = "https://www.youtube.com/watch?v=$id",
                    title = title,
                    thumbnailUrl = thumb,
                    author = Author(
                        id = null,
                        name = channelTitle,
                        avatarUrl = null,
                        platform = "YOUTUBE",
                        url = "https://www.youtube.com/channel/$channelId"
                    ),
                    duration = null,
                    tags = emptyList(),
                    createdTs = published,
                    publicationTs = published,
                    hits = views?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
                )
            )
        }
        return out
    }

    /** Ссылка/id/название канала → UC-идентификатор. */
    private fun channelIdOf(ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        Regex("""UC[A-Za-z0-9_-]{22}""").find(r)?.let { return it.value }
        if (r.contains("youtube.com/") || r.contains("youtu.be/")) {
            // @handle, /c/name, /user/name — вытаскиваем id со страницы канала
            val page = try {
                NetDirect.requestText(r, mapOf("User-Agent" to UA), timeoutMs = 9_000).second
            } catch (_: Throwable) {
                ""
            }
            Regex("""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""").find(page)?.let { return it.groupValues[1] }
            Regex("""channel/(UC[A-Za-z0-9_-]{22})""").find(page)?.let { return it.groupValues[1] }
            return null
        }
        // обычное название канала — ищем канал через YouTube и берём его id
        val page = try {
            YouTubeSearch.search(r, "Все", 0, "Любая")
        } catch (_: Throwable) {
            return null
        }
        val channel = page.items.firstOrNull { it.kind == YouTubeSearch.KIND_CHANNEL }
            ?: return null
        return channel.id.takeIf { it.startsWith("UC") }
    }

    // ── прямой поток для встроенного плеера ──
    private fun stream(url: String): OmniYouTubeBridge.Stream? {
        val play = VideoPlay.resolve(url) ?: return null
        if (play.url.isBlank()) return null
        return OmniYouTubeBridge.Stream(
            streamUrl = play.url,
            headers = play.referer?.let { mapOf("Referer" to it, "User-Agent" to UA) } ?: mapOf("User-Agent" to UA),
            title = play.title
        )
    }

    private fun decode(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
