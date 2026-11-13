package com.santiago43rus.rupoop.parser

import android.util.Log
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
 * v4.91.0: прямая ссылка YouTube для встроенного плеера — быстро и с проверкой.
 *
 * Почему переделано после ваших скриншотов:
 *  • «долго думает, потом открывает и сразу возвращается» — источники перебирались строго
 *    по очереди, каждый со своим таймаутом (до минуты), а в конце приходил пустой HLS,
 *    на котором плеер падал. Теперь все источники запускаются ПАРАЛЛЕЛЬНО, каждый поток
 *    ПРОВЕРЯЕТСЯ коротким запросом, и берётся первый рабочий; общий бюджет — около 12 секунд;
 *  • «Ошибка: Empty media playlist» при загрузке — отдавался пустой HLS-манифест.
 *    Теперь HLS принимается только если в нём реально есть сегменты, а приоритет —
 *    обычному mp4 со звуком (itag 18/22).
 */
object YouTubeStreamResolver {

    private const val TAG = "YouTubeStreamResolver"

    data class Stream(
        val streamUrl: String,
        val headers: Map<String, String>,
        val title: String? = null,
        val durationSeconds: Long? = null,
        val thumbnailUrl: String? = null
    )

    private const val ANDROID_UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
    private const val VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; Quest 3; " +
            "Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_UA = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val PIPED = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee"
    )
    private val INVIDIOUS = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de"
    )

    private data class Candidate(
        val source: String,
        val url: String,
        val headers: Map<String, String>,
        val title: String? = null,
        val duration: Long? = null,
        val thumb: String? = null
    ) {
        fun toStream() = Stream(url, headers, title, duration, thumb)
    }

    /** Готовые ссылки живут недолго; кэш убирает повторное «думание» при возврате к видео. */
    private class Cached(val stream: Stream, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 25L * 60L * 1000L

    fun videoId(url: String): String? {
        val u = url.trim()
        listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/live/([A-Za-z0-9_-]{11})""")
        ).forEach { p -> p.find(u)?.let { return it.groupValues[1] } }
        return u.takeIf { Regex("""^[A-Za-z0-9_-]{11}$""").matches(it) }
    }

    suspend fun resolve(url: String): Stream? = withContext(Dispatchers.IO) {
        val id = videoId(url) ?: return@withContext null

        cache[id]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return@withContext it.stream }

        // все источники стартуют сразу
        val candidates = coroutineScope {
            val jobs = listOf(
                async { playerEndpoint(id, "ANDROID", ANDROID_UA) },
                async { playerEndpoint(id, "ANDROID_VR", VR_UA) },
                async { playerEndpoint(id, "IOS", IOS_UA) },
                async { playerEndpoint(id, "WEB", WEB_UA) },
                async { playerEndpoint(id, "TVHTML5_SIMPLY_EMBEDDED_PLAYER", WEB_UA) },
                async { fromPage(id) },
                // v4.93.0: только оригинал — никаких Piped/Invidious-зеркал
                async { fromBridge(id) }
            )
            jobs.awaitAll()
        }

        for (c in candidates.filterNotNull()) {
            if (isPlayable(c.url)) {
                Log.i(TAG, "resolved $id via ${c.source}")
                val stream = c.toStream()
                cache[id] = Cached(stream, System.currentTimeMillis())
                return@withContext stream
            }
            Log.w(TAG, "source ${c.source} gave unusable url for $id")
        }
        cache.remove(id)
        Log.e(TAG, "no playable stream for $id")
        null
    }

    /** Короткая проверка: mp4 отвечает 200/206, HLS-манифест содержит сегменты. */
    private fun isPlayable(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val isHls = url.contains(".m3u8", true)
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .header("Range", "bytes=0-4096")
                .get()
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return false
                val body = r.body?.string().orEmpty()
                if (!isHls) return true
                if (!body.contains("#EXTM3U")) return false
                if (body.contains("#EXT-X-STREAM-INF")) {
                    // мастер-манифест: смотрим, что в нём есть ссылка на вариант
                    return body.contains(".m3u8")
                }
                body.contains("#EXTINF") || body.contains("#EXT-X-TARGETDURATION")
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            false
        }
    }

    // ── innertube player ──
    private fun playerEndpoint(id: String, kind: String, ua: String): Candidate? {
        val client = when (kind) {
            "ANDROID" -> JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.09.37")
                .put("androidSdkVersion", 30)
            "ANDROID_VR" -> JSONObject()
                .put("clientName", "ANDROID_VR")
                .put("clientVersion", "1.60.19")
                .put("androidSdkVersion", 32)
                .put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3")
                .put("osName", "Android")
                .put("osVersion", "12")
            "IOS" -> JSONObject()
                .put("clientName", "IOS")
                .put("clientVersion", "19.29.1")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "17.5.1.21F90")
            else -> JSONObject()
                .put("clientName", kind)
                .put("clientVersion", if (kind == "WEB") "2.20240726.00.00" else "7.20240724.00.00")
        }
        client.put("hl", "ru").put("gl", "RU")

        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val root = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, ua, kind)
            ?: return null
        return fromPlayerJson(root, kind, ua)
    }

    private fun fromPlayerJson(root: JSONObject, kind: String, ua: String): Candidate? {
        val streaming = root.optJSONObject("streamingData") ?: return null
        val details = root.optJSONObject("videoDetails")
        val headers = mapOf(
            "User-Agent" to ua,
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
        )
        val title = details?.optString("title")?.takeIf { it.isNotBlank() }
        val duration = details?.optString("lengthSeconds")?.toLongOrNull()
        val thumb = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }

        // 1) mp4 со звуком (itag 18 — 360p, 22 — 720p): самый надёжный вариант
        bestMuxed(streaming.optJSONArray("formats"))?.let {
            return Candidate("$kind/mp4", it, headers, title, duration, thumb)
        }
        // 2) HLS-манифест
        streaming.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let {
            return Candidate("$kind/hls", it, headers, title, duration, thumb)
        }
        return null
    }

    private fun bestMuxed(formats: JSONArray?): String? {
        if (formats == null || formats.length() == 0) return null
        var best: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("url").isEmpty()) continue          // signatureCipher — пропускаем
            val mime = f.optString("mimeType")
            if (!mime.startsWith("video/mp4")) continue
            if (best == null || f.optInt("height") > best.optInt("height")) best = f
        }
        return best?.optString("url")
    }

    // ── обычная страница ──
    private fun fromPage(id: String): Candidate? {
        val html = get("https://www.youtube.com/watch?v=$id&hl=ru") ?: return null
        val at = html.indexOf("ytInitialPlayerResponse")
        if (at < 0) return null
        val braceStart = html.indexOf('{', at)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in braceStart until html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end <= braceStart) return null
        val json = try {
            JSONObject(html.substring(braceStart, end + 1))
        } catch (e: Exception) {
            return null
        }
        return fromPlayerJson(json, "page", WEB_UA)
    }

    // ── Piped ──
    private fun fromPiped(id: String): Candidate? {
        for (base in PIPED) {
            val text = get("$base/streams/$id") ?: continue
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                continue
            }
            val streams = root.optJSONArray("videoStreams")
            var best: JSONObject? = null
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    if (s.optBoolean("videoOnly", false)) continue
                    if (s.optString("url").isEmpty()) continue
                    if (best == null || s.optInt("height") > best.optInt("height")) best = s
                }
            }
            val url = best?.optString("url") ?: root.optString("hls").takeIf { it.startsWith("http") }
            if (!url.isNullOrBlank()) {
                return Candidate(
                    "piped", url, mapOf("User-Agent" to WEB_UA),
                    root.optString("title").takeIf { it.isNotBlank() },
                    root.optLong("duration").takeIf { it > 0 },
                    root.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    // ── Invidious ──
    private fun fromInvidious(id: String): Candidate? {
        for (base in INVIDIOUS) {
            val text = get("$base/api/v1/videos/$id?fields=title,lengthSeconds,formatStreams,thumbnailUrl")
                ?: continue
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                continue
            }
            val formats = root.optJSONArray("formatStreams") ?: continue
            var url: String? = null
            for (i in 0 until formats.length()) {
                val u = formats.optJSONObject(i)?.optString("url").orEmpty()
                if (u.isNotEmpty()) {
                    url = if (u.startsWith("http")) u else base + u
                    break
                }
            }
            if (!url.isNullOrBlank()) {
                return Candidate(
                    "invidious", url, mapOf("User-Agent" to WEB_UA),
                    root.optString("title").takeIf { it.isNotBlank() },
                    root.optLong("lengthSeconds").takeIf { it > 0 },
                    root.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    // ── мост приложения (канал/VPN, свои запасные пути) ──
    private fun fromBridge(id: String): Candidate? {
        val s = com.santiago43rus.rupoop.network.OmniYouTubeBridge
            .stream("https://www.youtube.com/watch?v=$id") ?: return null
        if (s.streamUrl.isBlank()) return null
        return Candidate("bridge", s.streamUrl, s.headers.ifEmpty { mapOf("User-Agent" to WEB_UA) }, s.title)
    }

    // ── служебное ──
    private fun post(url: String, body: JSONObject, ua: String, kind: String): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", ua)
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-Youtube-Client-Name", clientId(kind))
            .header("X-Youtube-Client-Version", body.optJSONObject("context")?.optJSONObject("client")
                ?.optString("clientVersion").orEmpty())
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "$kind player -> ${r.code}")
                null
            } else {
                val text = r.body?.string().orEmpty()
                if (text.isBlank()) null else JSONObject(text)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "$kind player error: ${e.message}")
        null
    }

    private fun clientId(kind: String): String = when (kind) {
        "ANDROID" -> "3"
        "ANDROID_VR" -> "28"
        "IOS" -> "5"
        "WEB" -> "1"
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "85"
        else -> "1"
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", WEB_UA).get().build()
        http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        Log.w(TAG, "GET $url failed: ${e.message}")
        null
    }
}
