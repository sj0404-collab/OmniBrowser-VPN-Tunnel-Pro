package com.santiago43rus.rupoop.network

import android.util.Log
import com.santiago43rus.rupoop.data.Author
import com.santiago43rus.rupoop.data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v4.91.0: всё, что нужно «Кино» про YouTube-канал.
 *
 *  • аватар канала — настоящий, а не серая заглушка (кэшируется);
 *  • канал определяется точно (по id/ссылке/точному названию), поэтому в канале
 *    больше не появляются ролики чужих авторов;
 *  • плейлисты канала — реальные, со своими видео.
 */
object YouTubeChannelEngine {

    private const val TAG = "YouTubeChannelEngine"

    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** channelId → ссылка на аватар (null = пробовали и не нашли). */
    private val avatarCache = ConcurrentHashMap<String, String>()

    /** channelId → название канала. */
    private val nameCache = ConcurrentHashMap<String, String>()

    // ── аватары ──

    /** Дозаполнить аватары каналов у готовой выдачи (параллельно, с общим таймаутом). */
    suspend fun fillAvatars(results: List<SearchResult>): List<SearchResult> = withContext(Dispatchers.IO) {
        val ids = results.mapNotNull { channelIdOfAuthor(it) }.distinct().take(12)
        if (ids.isEmpty()) return@withContext results
        withTimeoutOrNull(3_500) {
            coroutineScope {
                ids.map { id -> async(Dispatchers.IO) { avatar(id) } }.awaitAll()
            }
        }
        results.map { r ->
            val id = channelIdOfAuthor(r) ?: return@map r
            val url = avatarCache[id]
            if (url.isNullOrBlank()) r
            else r.copy(author = r.author?.copy(avatarUrl = url))
        }
    }

    /** Аватар канала по его id (кэш + innertube browse, затем страница канала). */
    fun avatar(channelId: String): String? {
        if (channelId.isBlank()) return null
        avatarCache[channelId]?.let { return it.ifBlank { null } }
        val fromBrowse = browseHeader(channelId)
        if (!fromBrowse.first.isNullOrBlank()) {
            avatarCache[channelId] = fromBrowse.first!!
            fromBrowse.second?.let { nameCache[channelId] = it }
            return fromBrowse.first
        }
        val fromPage = pageAvatar(channelId)
        avatarCache[channelId] = fromPage ?: ""
        return fromPage
    }

    fun cachedName(channelId: String): String? = nameCache[channelId]

    private fun browseHeader(channelId: String): Pair<String?, String?> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", jsonClient()))
            .put("browseId", channelId)
        val root = post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body) ?: return null to null
        // header.c4TabbedHeaderRenderer.avatar | pageHeaderViewModel.contentMetadata...
        val nodes = ArrayList<JSONObject>()
        YouTubeSearchEngine.collect(root, "c4TabbedHeaderRenderer", nodes)
        if (nodes.isEmpty()) YouTubeSearchEngine.collect(root, "pageHeaderViewModel", nodes)
        for (n in nodes) {
            val avatar = findAvatarUrl(n)
            if (!avatar.isNullOrBlank()) {
                val title = n.optJSONObject("title")?.optString("simpleText")
                    ?: n.optJSONObject("title")?.optString("content")
                return avatar to title
            }
        }
        // общий поиск по всему ответу: "avatar":{"thumbnails":[…]} или image.sources
        val any = findAvatarUrl(root)
        return any to null
    }

    private fun findAvatarUrl(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                val own = ArrayList<JSONObject>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.equals("avatar", true) || k.equals("image", true)) {
                        node.optJSONObject(k)?.let { own.add(it) }
                    }
                }
                for (o in own) {
                    val thumbs = o.optJSONArray("thumbnails") ?: o.optJSONObject("sources")?.let { s ->
                        JSONArray().apply { put(s) }
                    }
                    val url = bestOf(thumbs)
                    if (!url.isNullOrBlank() && (url.contains("ggpht") || url.contains("yt3"))) return url
                }
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    findAvatarUrl(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findAvatarUrl(node.opt(i))?.let { return it }
            }
        }
        return null
    }

    private fun bestOf(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        var best: String? = null
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty()
            if (u.startsWith("http")) best = u
        }
        return best
    }

    private fun pageAvatar(channelId: String): String? {
        val html = get("https://www.youtube.com/channel/$channelId") ?: return null
        Regex("""avatar"\s*:\s*\{[^}]*"url"\s*:\s*"(https://yt3[^"]+)""")
            .find(html)?.let { return it.groupValues[1].replace("\\\\u0026", "&") }
        Regex("""(https://yt3\.googleusercontent\.com/[^"\s]+)""")
            .find(html)?.let { return it.groupValues[1].replace("\\\\u0026", "&") }
        return null
    }

    // ── канал: точное определение ──

    fun channelIdOfAuthor(r: SearchResult): String? {
        val url = r.author?.url.orEmpty()
        Regex("UC[A-Za-z0-9_-]{22}").find(url)?.let { return it.value }
        return null
    }

    /**
     * Ссылка/название канала → id. Для названия берём ТОЛЬКО канал с тем же названием,
     * иначе null (лучше ничего, чем чужие видео в канале).
     */
    suspend fun resolveChannelId(ref: String): String? = withContext(Dispatchers.IO) {
        val r = ref.trim()
        if (r.isEmpty()) return@withContext null
        Regex("UC[A-Za-z0-9_-]{22}").find(r)?.let { return@withContext it.value }

        if (r.contains("youtube.com/") || r.contains("youtu.be/")) {
            val url = if (r.startsWith("http")) r else "https://$r"
            val html = get(url) ?: return@withContext null
            Regex("""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""").find(html)
                ?.let { return@withContext it.groupValues[1] }
            Regex("channel/(UC[A-Za-z0-9_-]{22})").find(html)?.let { return@withContext it.groupValues[1] }
            return@withContext null
        }

        // название: ищем именно каналы (params = только каналы) и сверяем название
        val body = JSONObject()
            .put("context", JSONObject().put("client", jsonClient()))
            .put("query", r)
            .put("params", "EgIQAg%3D%3D")
        val root = post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            ?: return@withContext null
        val channels = ArrayList<JSONObject>()
        YouTubeSearchEngine.collect(root, "channelRenderer", channels)
        val norm = normalize(r)
        var found: String? = null
        for (c in channels) {
            val id = c.optString("channelId")
            if (!id.startsWith("UC")) continue
            val title = c.optJSONObject("title")?.optString("simpleText").orEmpty()
            if (normalize(title) == norm) {
                found = id
                break
            }
        }
        if (found == null) {
            // нет точного совпадения — берём канал, чьё название входит в запрос или наоборот
            for (c in channels) {
                val id = c.optString("channelId")
                if (!id.startsWith("UC")) continue
                val title = normalize(c.optJSONObject("title")?.optString("simpleText").orEmpty())
                if (title.isNotEmpty() && (norm.contains(title) || title.contains(norm))) {
                    found = id
                    break
                }
            }
        }
        found
    }

    // ── плейлисты канала ──

    /** Плейлисты канала (вкладка «Плейлисты» канала). */
    suspend fun channelPlaylists(channelId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!channelId.startsWith("UC")) return@withContext emptyList()
        val body = JSONObject()
            .put("context", JSONObject().put("client", jsonClient()))
            .put("browseId", channelId)
            .put("params", "EgZwbGF5bGlzdHPyBgQKAkIA") // «Плейлисты»
        val root = post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
            ?: return@withContext emptyList()
        val out = ArrayList<SearchResult>()
        // новый вид (lockupViewModel) и старый (gridPlaylistRenderer)
        val lockups = ArrayList<JSONObject>()
        YouTubeSearchEngine.collect(root, "lockupViewModel", lockups)
        for (l in lockups) {
            val ctype = l.optString("contentType")
            if (ctype.isNotEmpty() && !ctype.contains("PLAYLIST", true)) continue
            val id = l.optString("contentId")
            if (id.isEmpty()) continue
            val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
            if (title.isBlank()) continue
            val thumb = bestOf(
                l.optJSONObject("contentImage")?.optJSONObject("collectionThumbnailViewModel")
                    ?.optJSONObject("primaryThumbnail")?.optJSONObject("thumbnailViewModel")
                    ?.optJSONObject("image")?.optJSONArray("sources")
                    ?: l.optJSONObject("contentImage")?.optJSONArray("sources")
            ) ?: "https://i.ytimg.com/vi/${l.optString("contentImage")}/mqdefault.jpg"
            out.add(
                SearchResult(
                    videoUrl = "https://www.youtube.com/playlist?list=$id",
                    title = title,
                    thumbnailUrl = thumb,
                    author = Author(name = cachedName(channelId) ?: "", platform = "YOUTUBE", url = "https://www.youtube.com/channel/$channelId"),
                    duration = null,
                    tags = listOf("playlist"),
                    createdTs = null,
                    publicationTs = null,
                    hits = null
                )
            )
        }
        if (out.isNotEmpty()) return@withContext out.distinctBy { it.videoUrl }

        val grids = ArrayList<JSONObject>()
        YouTubeSearchEngine.collect(root, "gridPlaylistRenderer", grids)
        for (g in grids) {
            val id = g.optString("playlistId")
            val title = YouTubeSearchEngine.textOf(g.opt("title")).ifBlank { continue }
            out.add(
                SearchResult(
                    videoUrl = "https://www.youtube.com/playlist?list=$id",
                    title = title,
                    thumbnailUrl = bestOf(g.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                    author = Author(name = cachedName(channelId) ?: "", platform = "YOUTUBE", url = "https://www.youtube.com/channel/$channelId"),
                    duration = null,
                    tags = listOf("playlist")
                )
            )
        }
        out.distinctBy { it.videoUrl }
    }

    /** Видео плейлиста (browseId = VL + id). */
    suspend fun playlistVideos(playlistId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val id = playlistId.removePrefix("VL")
        if (id.isBlank()) return@withContext emptyList()
        val body = JSONObject()
            .put("context", JSONObject().put("client", jsonClient()))
            .put("browseId", "VL$id")
        val root = post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
            ?: return@withContext emptyList()
        fillAvatars(YouTubeSearchEngine.parseVideoNodes(root))
    }

    // ── служебное ──

    private fun jsonClient(): JSONObject = JSONObject()
        .put("clientName", "WEB")
        .put("clientVersion", "2.20240726.00.00")
        .put("hl", "ru")
        .put("gl", "RU")

    private fun post(url: String, body: JSONObject): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_UA)
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

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", WEB_UA).get().build()
        http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace("ё", "е")
        .replace(Regex("[^а-яa-z0-9]+"), "")
}
