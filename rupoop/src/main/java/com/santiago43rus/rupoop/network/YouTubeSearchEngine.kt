package com.santiago43rus.rupoop.network

import android.util.Log
import com.santiago43rus.rupoop.data.Author
import com.santiago43rus.rupoop.data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v4.91.0: поиск YouTube — свой движок внутри `:rupoop`, как у RuTube/VK/OK.
 *
 * Что важно для пользователя:
 *  • карточки приходят с настоящими обложками, названиями, каналом, длительностью и датой;
 *  • у карточки сразу есть аватар канала (не серая заглушка) и id канала — поэтому
 *    подписка и переход в канал ведут именно в этот канал;
 *  • если свой запрос ничего не дал — идём через мост приложения (канал/VPN).
 */
object YouTubeSearchEngine {

    private const val TAG = "YouTubeSearchEngine"

    private const val ANDROID_UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        var own = try {
            innertubeSearch(q, android = true).ifEmpty { innertubeSearch(q, android = false) }
        } catch (e: Exception) {
            Log.w(TAG, "innertube search failed: ${e.message}")
            emptyList()
        }
        if (own.isEmpty()) {
            own = try {
                OmniYouTubeBridge.search(q)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val distinct = own.distinctBy { it.videoUrl }
        if (distinct.isEmpty()) return@withContext distinct

        // настоящие аватары каналов (кэш внутри движка)
        try {
            YouTubeChannelEngine.fillAvatars(distinct)
        } catch (e: Exception) {
            Log.w(TAG, "avatar fill failed: ${e.message}")
            distinct
        }
    }

    // ── видео канала ──

    /**
     * Видео конкретного канала. Канал определяется точно: если по названию нельзя
     * однозначно понять, что это тот самый канал, — НЕ подставляем чужие ролики,
     * а ищем по названию канала и оставляем только его видео.
     */
    suspend fun channelVideos(ref: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val channelId = YouTubeChannelEngine.resolveChannelId(ref)
        if (channelId != null) {
            val byRss = channelVideosByRss(channelId, ref)
            if (byRss.isNotEmpty()) {
                return@withContext YouTubeChannelEngine.fillAvatars(byRss)
            }
            // RSS не отдал (редкость) — берём вкладку «Видео» канала
            val byBrowse = channelVideosByBrowse(channelId)
            if (byBrowse.isNotEmpty()) return@withContext YouTubeChannelEngine.fillAvatars(byBrowse)
        }
        // совсем без id: ищем и жёстко фильтруем по совпадению названия канала
        val searched = try {
            innertubeSearch(ref, android = false)
        } catch (e: Exception) {
            emptyList()
        }
        val norm = normalize(ref)
        searched.filter { r ->
            val n = normalize(r.author?.name.orEmpty())
            n.isNotEmpty() && (n == norm || n.contains(norm) || norm.contains(n))
        }
    }

    private fun channelVideosByRss(channelId: String, ref: String): List<SearchResult> {
        val xml = try {
            val request = Request.Builder()
                .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                .header("User-Agent", WEB_UA)
                .get()
                .build()
            http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
        } catch (e: Exception) {
            Log.w(TAG, "RSS error: ${e.message}")
            ""
        }
        if (xml.isBlank()) return emptyList()

        val channelTitle = Regex("<title>([^<]*)</title>").find(xml)?.groupValues?.get(1)?.let { decode(it) }
            ?: ref
        val out = ArrayList<SearchResult>()
        for (entry in Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val e = entry.groupValues[1]
            val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(e)?.groupValues?.get(1) ?: continue
            val title = Regex("<title[^>]*>([^<]*)</title>").find(e)?.groupValues?.get(1)?.let { decode(it) }
                ?: continue
            val published = Regex("<published>([^<]+)</published>").find(e)?.groupValues?.get(1)
            val views = Regex("views=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull()
            val thumb = Regex("<media:thumbnail[^>]*url=\"([^\"]+)\"").find(e)?.groupValues?.get(1)
                ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
            out.add(
                SearchResult(
                    videoUrl = "https://www.youtube.com/watch?v=$id",
                    title = title,
                    thumbnailUrl = thumb,
                    author = Author(
                        id = null,
                        name = channelTitle,
                        avatarUrl = YouTubeChannelEngine.avatar(channelId),
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

    private fun channelVideosByBrowse(channelId: String): List<SearchResult> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", jsonClient()))
            .put("browseId", channelId)
            .put("params", "EgZ2aWRlb3PyBgQKAjoA") // вкладка «Видео»
        val root = postJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body) ?: return emptyList()
        return parseVideoNodes(root)
    }

    // ── innertube search ──

    private fun innertubeSearch(query: String, android: Boolean): List<SearchResult> {
        val client = if (android) {
            JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.09.37")
                .put("androidSdkVersion", 30)
                .put("hl", "ru")
                .put("gl", "RU")
        } else jsonClient()
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)
            .put("params", "EgIQAQ%3D%3D") // только видео

        val root = postJson(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
            body,
            if (android) ANDROID_UA else WEB_UA
        ) ?: return emptyList()
        return parseVideoNodes(root)
    }

    /** Разбор всех videoRenderer/compactVideoRenderer из любого ответа innertube. */
    fun parseVideoNodes(root: JSONObject): List<SearchResult> {
        val nodes = ArrayList<JSONObject>()
        collect(root, "videoRenderer", nodes)
        if (nodes.isEmpty()) collect(root, "compactVideoRenderer", nodes)
        if (nodes.isEmpty()) collect(root, "gridVideoRenderer", nodes)
        if (nodes.isEmpty()) collect(root, "playlistVideoRenderer", nodes)

        val out = ArrayList<SearchResult>(nodes.size)
        for (v in nodes) {
            val id = v.optString("videoId")
            if (id.length != 11) continue
            val title = textOf(v.opt("title"))
            if (title.isBlank()) continue
            val lengthText = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
            val viewsText = v.optJSONObject("viewCountText")?.optString("simpleText").orEmpty()
            val published = v.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty()
            val thumb = bestThumb(v.optJSONObject("thumbnail"))
                .ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
            val ownerRuns = v.optJSONObject("ownerText")?.optJSONArray("runs")
                ?: v.optJSONObject("longBylineText")?.optJSONArray("runs")
                ?: v.optJSONObject("shortBylineText")?.optJSONArray("runs")
            val owner = ownerRuns?.optJSONObject(0)?.optString("text").orEmpty()
            val channelId = ownerRuns?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty()

            out.add(
                SearchResult(
                    videoUrl = "https://www.youtube.com/watch?v=$id",
                    title = title,
                    thumbnailUrl = thumb,
                    author = Author(
                        id = null,
                        name = owner.ifBlank { "YouTube" },
                        avatarUrl = null,   // дозаполним пачкой в fillAvatars (без 20 запросов подряд)
                        platform = "YOUTUBE",
                        url = channelId.takeIf { it.startsWith("UC") }
                            ?.let { "https://www.youtube.com/channel/$it" }
                    ),
                    duration = parseLength(lengthText).takeIf { it > 0 },
                    tags = if (v.toString().contains("LIVE", true)) listOf("live") else emptyList(),
                    createdTs = null,
                    publicationTs = publishedToIso(published),
                    hits = parseViews(viewsText)
                )
            )
        }
        return out.distinctBy { it.videoUrl }
    }

    // ── helpers ──

    fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == key) continue
                    collect(node.opt(k), key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    fun textOf(obj: Any?): String = when (obj) {
        is JSONObject -> obj.optString("simpleText").ifBlank {
            obj.optString("content").ifBlank {
                val runs = obj.optJSONArray("runs")
                if (runs == null) "" else (0 until runs.length()).joinToString("") {
                    runs.optJSONObject(it)?.optString("text").orEmpty()
                }
            }
        }
        is String -> obj
        else -> ""
    }

    private fun bestThumb(obj: JSONObject?): String {
        val arr = obj?.optJSONArray("thumbnails") ?: return ""
        if (arr.length() == 0) return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
    }

    /** «11:58:51» / «3:07» → секунды. */
    fun parseLength(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.trim().split(":")
        if (parts.size !in 2..3) return 0
        var seconds = 0
        for (p in parts) {
            val n = p.filter { it.isDigit() }.toIntOrNull() ?: return 0
            seconds = seconds * 60 + n
        }
        return seconds
    }

    /** «762 тыс. просмотров» → число. */
    fun parseViews(text: String): Int? {
        val t = text.lowercase().replace("\u00a0", " ")
        val m = Regex("""([\d\s.,]+)\s*(тыс|млн|млрд|k|m|b)?""").find(t) ?: return null
        val raw = m.groupValues[1].replace(" ", "").replace(",", ".").trim()
        var value = raw.toDoubleOrNull() ?: return null
        when (m.groupValues[2]) {
            "тыс", "k" -> value *= 1_000
            "млн", "m" -> value *= 1_000_000
            "млрд", "b" -> value *= 1_000_000_000
        }
        val v = value.toLong()
        if (v <= 0) return null
        return if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    /** «3 года назад» → ISO-строка (для подписи «когда» и сортировки). */
    private fun publishedToIso(text: String): String? {
        if (text.isBlank()) return null
        val t = text.lowercase()
        val n = Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val days = when {
            t.contains("секунд") || t.contains("минут") || t.contains("час") -> 0
            t.contains("вчера") -> 1
            t.contains("день") || t.contains("дня") || t.contains("дней") -> n
            t.contains("недел") -> n * 7
            t.contains("месяц") -> n * 30
            t.contains("год") || t.contains("лет") -> n * 365
            else -> return null
        }
        val at = System.currentTimeMillis() - days.toLong() * 24L * 3600L * 1000L
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(at))
    }

    private fun jsonClient(): JSONObject = JSONObject()
        .put("clientName", "WEB")
        .put("clientVersion", "2.20240726.00.00")
        .put("hl", "ru")
        .put("gl", "RU")

    private fun postJson(url: String, body: JSONObject, ua: String = WEB_UA): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", ua)
            .header("X-Goog-Api-Format-Version", "2")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "POST $url -> ${r.code}")
                null
            } else {
                val text = r.body?.string().orEmpty()
                if (text.isBlank()) null else JSONObject(text)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "POST $url failed: ${e.message}")
        null
    }

    private fun decode(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun normalize(s: String): String = s.lowercase()
        .replace("ё", "е")
        .replace(Regex("[^а-яa-z0-9]+"), "")
}
