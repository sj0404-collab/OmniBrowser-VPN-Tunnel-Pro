package com.winwasm64.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.51.0: нормальный поиск по YouTube вместо парсинга выдачи в WebView.
 *
 * Почему так. В 4.50.0 ролики вытаскивались из HTML страницы поиска: названия путались с таймингами,
 * «прокрутка» не подгружала новые, плейлистов не было вовсе, а фильтров не было никаких.
 * Здесь используется тот же внутренний интерфейс, что и у самого сайта (InnerTube): он отдаёт
 * аккуратный JSON — название, автор, длительность, просмотры, дата, обложка, плейлисты и каналы,
 * а для «показать ещё» — токен продолжения, поэтому список пополняется бесконечно.
 */
object YouTubeSearch {

    const val KIND_VIDEO = "video"
    const val KIND_PLAYLIST = "playlist"
    const val KIND_CHANNEL = "channel"

    data class Item(
        val id: String,
        val kind: String,
        val title: String,
        val author: String,
        val lengthText: String,
        val lengthSec: Int,
        val views: Long,
        val viewsText: String,
        val publishedText: String,
        val daysAgo: Int,
        val thumb: String,
        val url: String,
        val extra: String = ""
    )

    data class Page(val items: List<Item>, val continuation: String?)

    // эндпоинт работает и без ключа — ключ не нужен, поэтому никаких секретов в коде
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun context(): JSONObject = JSONObject().apply {
        put(
            "client", JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20240101.00.00")
                put("hl", "ru")
                put("gl", "RU")
            }
        )
    }

    /** Категория → серверный параметр фильтра (как у сайта). */
    private fun categoryParam(category: String): String? = when (category) {
        "🎬 Видео" -> "EgIQAQ%3D%3D"
        "📺 Каналы" -> "EgIQAg%3D%3D"
        "🎞 Плейлисты", "▶ Прямые эфиры", "🔴 Трансляции" -> "EgJAAQ%3D%3D"
        else -> null
    }

    /**
     * Поиск страницы. Если передан [continuation] — догружаем следующую порцию
     * («показать ещё», бесконечная прокрутка).
     */
    fun search(
        query: String,
        category: String = "Все",
        sort: Int = 0,
        duration: String = "Любая",
        continuation: String? = null
    ): Page {
        val body = JSONObject().apply { put("context", context()) }
        if (continuation.isNullOrBlank()) {
            // v4.52.0: категорию НЕ отправляем серверу — один запрос сразу отдаёт видео, плейлисты
            // и каналы, а фильтрация по категориям идёт локально и мгновенно (без нового сканирования)
            body.put("query", query)
        } else {
            body.put("continuation", continuation)
        }
        val raw = post(ENDPOINT, body.toString()) ?: return Page(emptyList(), null)
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return Page(emptyList(), null)
        }
        val items = mutableListOf<Item>()
        val playlists = mutableListOf<Item>()

        val videos = mutableListOf<JSONObject>()
        val channels = mutableListOf<JSONObject>()
        val lockups = mutableListOf<JSONObject>()
        walk(root, "videoRenderer", videos)
        walk(root, "channelRenderer", channels)
        walk(root, "lockupViewModel", lockups)

        for (v in videos) {
            val id = v.optString("videoId")
            if (id.isBlank()) continue
            val title = v.optJSONObject("title")?.let { runsText(it) }.orEmpty()
            val length = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
            val owner = v.optJSONObject("ownerText")?.let { runsText(it) }
                ?: v.optJSONObject("longBylineText")?.let { runsText(it) }.orEmpty()
            val views = v.optJSONObject("viewCountText")?.optString("simpleText").orEmpty()
            val published = v.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty()
            // прямой эфир: значок LIVE в разметке
            val live = v.toString().contains("LIVE", true) ||
                v.toString().contains("прямой эфир", true)
            val thumb = bestThumb(v.optJSONObject("thumbnail"))
                .ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
            items.add(
                Item(
                    id = id,
                    kind = KIND_VIDEO,
                    title = TorrentSearchAgent.decodeEntities(title),
                    author = owner,
                    lengthText = length,
                    lengthSec = parseDuration(length),
                    views = parseViews(views),
                    viewsText = views,
                    publishedText = published,
                    daysAgo = parseDays(published),
                    thumb = thumb,
                    url = "https://m.youtube.com/watch?v=$id",
                    extra = if (live) "live" else ""
                )
            )
        }

        for (c in channels) {
            val id = c.optString("channelId")
            val title = c.optJSONObject("title")?.optString("simpleText").orEmpty()
            if (id.isBlank() || title.isBlank()) continue
            val subs = c.optJSONObject("videoCountText")?.optString("simpleText")
                ?: c.optJSONObject("subscriberCountText")?.optString("simpleText").orEmpty()
            val thumb = bestThumb(c.optJSONObject("thumbnail"))
            items.add(
                Item(
                    id = id,
                    kind = KIND_CHANNEL,
                    title = TorrentSearchAgent.decodeEntities(title),
                    author = "канал",
                    lengthText = "",
                    lengthSec = 0,
                    views = 0,
                    viewsText = subs,
                    publishedText = "",
                    daysAgo = 0,
                    thumb = thumb,
                    url = "https://m.youtube.com/channel/$id",
                    extra = title
                )
            )
        }

        for (l in lockups) {
            val ctype = l.optString("contentType")
            if (!ctype.contains("PLAYLIST", true)) continue
            val id = l.optString("contentId")
            if (id.isBlank()) continue
            val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
                .ifBlank { l.optJSONObject("metadata")?.optString("title").orEmpty() }
            val thumb = bestThumb(l.optJSONObject("contentImage"))
            val count = meta?.optJSONObject("metadata")?.let { json ->
                val parts = mutableListOf<String>()
                walkStrings(json, parts)
                parts.firstOrNull { it.contains("видео", true) || it.contains("video", true) }.orEmpty()
            }.orEmpty()
            playlists.add(
                Item(
                    id = id,
                    kind = KIND_PLAYLIST,
                    title = TorrentSearchAgent.decodeEntities(title.ifBlank { "Плейлист $id" }),
                    author = "плейлист",
                    lengthText = "",
                    lengthSec = 0,
                    views = 0,
                    viewsText = count,
                    publishedText = "",
                    daysAgo = 0,
                    thumb = thumb,
                    url = "https://m.youtube.com/playlist?list=$id"
                )
            )
        }

        // v4.52.0: категория — локальный фильтр, а не параметр запроса
        val merged = items + playlists
        val filtered = applyDuration(merged, duration)
        val sorted = applySort(filtered, sort)

        val contTokens = mutableListOf<JSONObject>()
        walk(root, "continuationCommand", contTokens)
        val cont = contTokens.firstOrNull { it.optString("token").isNotBlank() }?.optString("token")
        return Page(sorted, cont)
    }

    private fun applySort(list: List<Item>, sort: Int): List<Item> = when (sort) {
        1 -> list.sortedBy { it.daysAgo }            // сначала новые
        2 -> list.sortedByDescending { it.views }    // сначала популярные
        3 -> list.sortedByDescending { it.daysAgo }  // сначала старые
        else -> list
    }

    private fun applyDuration(list: List<Item>, duration: String): List<Item> = when (duration) {
        "До 4 мин" -> list.filter { it.kind != KIND_VIDEO || (it.lengthSec in 1..240) }
        "4–20 мин" -> list.filter { it.kind != KIND_VIDEO || (it.lengthSec in 241..1200) }
        "20+ мин" -> list.filter { it.kind != KIND_VIDEO || it.lengthSec > 1200 }
        else -> list
    }

    /* ===================== разбор значений ===================== */

    fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        var total = 0
        for (part in text.trim().split(':').mapNotNull { it.trim().toIntOrNull() }) {
            total = total * 60 + part
        }
        return total
    }

    fun parseViews(text: String): Long {
        val digits = text.replace('\u00A0', ' ')
        val m = Regex("""([\d\s.,]+)\s*(млн|тыс|m|k)?""", RegexOption.IGNORE_CASE).find(digits) ?: return 0
        val num = m.groupValues[1].replace(" ", "").replace(",", ".").toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].lowercase()) {
            "млн", "m" -> 1_000_000.0
            "тыс", "k" -> 1_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    fun parseDays(text: String): Int {
        val t = text.lowercase()
        val num = Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 9999
        return when {
            t.contains("сек") || t.contains("second") -> 0
            t.contains("мин") || t.contains("minute") -> 0
            t.contains("час") || t.contains("hour") -> 0
            t.contains("дн") || t.contains("день") || t.contains("day") -> num
            t.contains("недел") || t.contains("week") -> num * 7
            t.contains("мес") || t.contains("month") -> num * 30
            t.contains("год") || t.contains("year") -> num * 365
            else -> 9999
        }
    }

    private fun runsText(obj: JSONObject): String {
        val runs = obj.optJSONArray("runs")
        if (runs != null) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            if (sb.isNotBlank()) return sb.toString()
        }
        return obj.optString("simpleText")
    }

    private fun bestThumb(obj: JSONObject?): String {
        if (obj == null) return ""
        val arrays = mutableListOf<JSONArray>()
        obj.optJSONArray("thumbnails")?.let { arrays.add(it) }
        obj.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.optJSONArray("sources")?.let { arrays.add(it) }
        obj.optJSONObject("collectionThumbnailViewModel")?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.optJSONArray("sources")?.let { arrays.add(it) }
        var best = ""
        var bestWidth = -1
        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val w = o.optInt("width", 0)
                if (u.isNotBlank() && w >= bestWidth) {
                    best = u
                    bestWidth = w
                }
            }
        }
        // 720-й размер тяжеловат для списка — хватит 360
        return best.replace("maxresdefault", "mqdefault")
    }

    private fun walk(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v)
                    walk(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), key, out)
        }
    }

    private fun walkStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) walkStrings(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walkStrings(node.opt(i), out)
            is String -> if (node.length < 80) out.add(node)
        }
    }

    private fun post(url: String, body: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Origin", "https://www.youtube.com")
                setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Throwable) {
            null
        }
    }
}
