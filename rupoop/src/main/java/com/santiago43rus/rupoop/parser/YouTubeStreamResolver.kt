package com.santiago43rus.rupoop.parser

import android.util.Log
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
 * v4.90.0: свой разбор прямой ссылки YouTube внутри `:rupoop` — как у RuTube (api/video) и VK (video_ext).
 *
 * Порядок: innertube ANDROID → innertube WEB → обычная страница (ytInitialPlayerResponse) →
 * Piped → Invidious. Берём поток, который реально играет во встроенном Media3: сначала
 * обычный mp4 со звуком (itag 18/22), иначе HLS-манифест, иначе adaptive-видео с HLS.
 * Без этого ссылка YouTube не открывалась: у площадки нет m3u8 в открытом виде.
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
    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val PIPED = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee"
    )

    private val INVIDIOUS = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be"
    )

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

        // перебираем клиентов: у каждого свой набор форматов, какой-то даст mp4 со звуком
        for (kind in listOf(ClientKind.ANDROID, ClientKind.IOS, ClientKind.WEB)) {
            playerEndpoint(id, kind)?.let { return@withContext it }
            Log.w(TAG, "${kind.name} player failed for $id")
        }
        Log.w(TAG, "WEB player failed for $id, trying page")
        fromPage(id)?.let { return@withContext it }
        Log.w(TAG, "page failed for $id, trying Piped")
        fromPiped(id)?.let { return@withContext it }
        Log.w(TAG, "Piped failed for $id, trying Invidious")
        fromInvidious(id)?.let { return@withContext it }

        // последний шанс: мост приложения (там свой набор путей через канал/VPN)
        val viaBridge = OmniYouTubeBridgeCompat.stream("https://www.youtube.com/watch?v=$id")
        if (viaBridge != null && viaBridge.first.isNotBlank()) {
            return@withContext Stream(
                streamUrl = viaBridge.first,
                headers = viaBridge.second,
                title = null
            )
        }
        Log.e(TAG, "no playable stream for $id")
        null
    }

    enum class ClientKind { ANDROID, IOS, WEB }

    // ── 1/2. innertube player ──
    private fun playerEndpoint(id: String, kind: ClientKind): Stream? {
        val client = when (kind) {
            ClientKind.ANDROID -> JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.09.37")
                .put("androidSdkVersion", 30)
                .put("hl", "ru").put("gl", "RU")
            ClientKind.IOS -> JSONObject()
                .put("clientName", "IOS")
                .put("clientVersion", "19.29.1")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "17.5.1.21F90")
                .put("hl", "ru").put("gl", "RU")
            ClientKind.WEB -> JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20240726.00.00")
                .put("hl", "ru").put("gl", "RU")
        }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", if (kind == ClientKind.ANDROID) ANDROID_UA else WEB_UA)
            .header("X-Goog-Api-Format-Version", "2")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val text = try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "player ${kind.name} code ${r.code}")
                    return null
                }
                r.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "player request error: ${e.message}")
            return null
        }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return fromPlayerJson(root, kind)
    }

    private fun fromPlayerJson(root: JSONObject, kind: ClientKind): Stream? {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val details = root.optJSONObject("videoDetails")
        val streaming = root.optJSONObject("streamingData")
        if (streaming == null) {
            Log.w(TAG, "no streamingData (status=$status)")
            return null
        }

        val headers = mutableMapOf(
            "User-Agent" to (if (kind == ClientKind.ANDROID) ANDROID_UA else WEB_UA),
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
        )

        // 1) обычный mp4 со звуком — играет без дополнительных дорожек
        val formats = streaming.optJSONArray("formats")
        val muxed = bestMuxed(formats)
        // 2) HLS-манифест
        val hls = streaming.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        // adaptive-форматы (видео отдельно от звука) НЕ берём: получилось бы «кино без звука».
        val url = muxed ?: hls ?: return null
        return Stream(
            streamUrl = url,
            headers = headers,
            title = details?.optString("title"),
            durationSeconds = details?.optString("lengthSeconds")?.toLongOrNull(),
            thumbnailUrl = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
        )
    }

    private fun bestMuxed(formats: JSONArray?): String? {
        if (formats == null || formats.length() == 0) return null
        var best: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("url").isEmpty()) continue          // защищённые (signatureCipher) пропускаем
            val mime = f.optString("mimeType")
            if (!mime.startsWith("video/mp4")) continue
            val height = f.optInt("height")
            if (best == null || height > best.optInt("height")) best = f
        }
        return best?.optString("url")
    }

    private fun bestAdaptive(formats: JSONArray?): String? {
        if (formats == null) return null
        var best: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("video/mp4")) continue
            if (f.optString("url").isEmpty()) continue
            if (best == null || f.optInt("height") > best.optInt("height")) best = f
        }
        return best?.optString("url")
    }

    // ── 3. обычная страница ──
    private fun fromPage(id: String): Stream? {
        val request = Request.Builder()
            .url("https://www.youtube.com/watch?v=$id&hl=ru")
            .header("User-Agent", WEB_UA)
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .get()
            .build()
        val html = try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "page error: ${e.message}")
            return null
        }
        if (html.isBlank()) return null
        val marker = "ytInitialPlayerResponse"
        val at = html.indexOf(marker)
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
                c == '\\' && inString -> escaped = true
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
        return fromPlayerJson(json, ClientKind.WEB)
    }

    // ── 4. Piped ──
    private fun fromPiped(id: String): Stream? {
        for (base in PIPED) {
            val text = get("$base/streams/$id") ?: continue
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                continue
            }
            val streams = root.optJSONArray("videoStreams") ?: continue
            var best: JSONObject? = null
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                if (!s.optBoolean("videoOnly", false) && s.optString("url").isNotEmpty()) {
                    if (best == null || s.optInt("height") > best.optInt("height")) best = s
                }
            }
            val url = best?.optString("url") ?: root.optString("hls").takeIf { it.startsWith("http") }
            if (!url.isNullOrBlank()) {
                return Stream(
                    streamUrl = url,
                    headers = mapOf("User-Agent" to WEB_UA),
                    title = root.optString("title").takeIf { it.isNotBlank() },
                    durationSeconds = root.optLong("duration").takeIf { it > 0 },
                    thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    // ── 5. Invidious ──
    private fun fromInvidious(id: String): Stream? {
        for (base in INVIDIOUS) {
            val text = get("$base/api/v1/videos/$id") ?: continue
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                continue
            }
            val formats = root.optJSONArray("formatStreams")
            val url = if (formats != null && formats.length() > 0) {
                // formatStreams у Invidious отсортированы от лучшего качества, берём первый живой
                var best: String? = null
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val u = f.optString("url")
                    if (u.isNotEmpty()) {
                        best = if (u.startsWith("http")) u else base + u
                        break
                    }
                }
                best
            } else null
            if (!url.isNullOrBlank()) {
                return Stream(
                    streamUrl = url,
                    headers = mapOf("User-Agent" to WEB_UA),
                    title = root.optString("title").takeIf { it.isNotBlank() },
                    durationSeconds = root.optLong("lengthSeconds").takeIf { it > 0 },
                    thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", WEB_UA).get().build()
        http.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET $url failed: ${e.message}")
        null
    }
}

/** Тонкая совместимость: мост живёт в network, а разбор — в parser. */
private object OmniYouTubeBridgeCompat {
    fun stream(url: String): Pair<String, Map<String, String>>? {
        val s = com.santiago43rus.rupoop.network.OmniYouTubeBridge.stream(url) ?: return null
        return s.streamUrl to s.headers
    }
}
