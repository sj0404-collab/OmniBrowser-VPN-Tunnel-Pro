package com.winwasm64.torrent

import com.winwasm64.ui.browser.NetDirect
import com.winwasm64.vpn.VpnLog
import org.json.JSONObject

/**
 * v4.76.0: настоящий встроенный плеер.
 *
 * Раньше карточка открывала страницу YouTube во встроенном WebView — отсюда «открывает плеер ютуба,
 * а не встроенный в приложение». Теперь мы сами достаём прямой поток и отдаём его NativeVideoPlayer
 * (Media3/ExoPlayer, умеет mp4 и HLS), поэтому видео играет внутри приложения.
 *
 *  • YouTube — innertube `player` (клиент ANDROID): берём progressive-поток (itag 18/22) со звуком;
 *  • RuTube  — `api/video/<id>/` → `video_balancer.m3u8` (HLS);
 *  • VK      — `video_ext.php` → прямой mp4 (`url360/url480/…`).
 *
 * Все запросы идут через канал приложения (VPN браузера), а не напрямую.
 */
object VideoPlay {

    data class Play(val url: String, val referer: String?, val title: String = "", val quality: String = "")

    private val UA_YT = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
    private val UA_WEB =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** Последняя причина отказа — показываем человеку. */
    @Volatile
    var lastError: String = ""
        private set

    fun youtubeId(link: String): String? {
        val u = link.trim()
        val patterns = listOf(
            Regex("""(?:v=|/watch\?.*?v=)([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/live/([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) p.find(u)?.let { return it.groupValues[1] }
        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(u)) return u
        return null
    }

    /** Достать играбельный поток для любой ссылки. null — не получилось, причина в [lastError]. */
    fun resolve(link: String): Play? {
        lastError = ""
        val url = link.trim()
        if (url.isBlank()) { lastError = "пустая ссылка"; return null }
        val yt = youtubeId(url)
        if (yt != null) return youtube(yt)
        if (url.contains("rutube.ru/video/", true)) return rutube(url)
        if (url.contains("vk.com/video", true) || url.contains("vkvideo.ru/video", true) || url.contains("vk.com/clip", true))
            return vk(url)
        // прямой файл/HLS — играем как есть
        if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true) || url.endsWith(".webm", true))
            return Play(url, null)
        lastError = "неизвестная площадка: " + url.take(60)
        return null
    }

    /**
     * v4.79.0: один провайдер может ответить 400 (у YouTube это обычное дело — клиент устарел или
     * требует аттестацию). Поэтому перебираем несколько независимых путей и берём первый живой:
     * innertube ANDROID → innertube WEB → HTML страницы (ytInitialPlayerResponse) →
     * Piped → Invidious. Это ключевые пути без ключей и без чужого аккаунта.
     */
    private fun youtube(id: String): Play? {
        var reason = ""
        val steps: List<Pair<String, () -> Play?>> = listOf(
            "innertube ANDROID" to { innertube(id, "ANDROID") },
            "innertube WEB" to { innertube(id, "WEB") },
            "страница YouTube" to { ytPage(id) },
            "Piped" to { piped(id) },
            "Invidious" to { invidious(id) }
        )
        for ((name, step) in steps) {
            val r = try {
                step()
            } catch (e: Throwable) {
                lastError = name + ": " + (e.message ?: "сеть")
                null
            }
            if (r != null) {
                if (name != "innertube ANDROID") lastError = ""
                VpnLog.i("Плеер", "YouTube $id через $name, ${r.quality}")
                return r
            }
            if (lastError.isNotBlank()) reason = lastError
            VpnLog.w("Плеер", "YouTube $id: $name не дал поток — $lastError")
        }
        lastError = reason.ifBlank { "YouTube не отдал поток" }
        return null
    }

    private fun innertube(id: String, client: String): Play? {
        val ctx = when (client) {
            "ANDROID" -> JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.09.37")
                .put("androidSdkVersion", 30)
                .put("hl", "ru").put("gl", "RU")
            else -> JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20260101.00.00")
                .put("hl", "ru").put("gl", "RU")
        }
        val body = JSONObject()
            .put("videoId", id)
            .put("context", JSONObject().put("client", ctx))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "X-Goog-Api-Format-Version" to "2",
            "Accept-Language" to "ru"
        )
        if (client == "ANDROID") headers["User-Agent"] = UA_YT else headers["User-Agent"] = UA_WEB
        val (code, text) = try {
            NetDirect.requestText("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", headers, "POST", body, timeoutMs = 12_000)
        } catch (e: Throwable) {
            lastError = "YouTube ($client): " + (e.message ?: "сеть")
            return null
        }
        if (code !in 200..299) {
            lastError = "YouTube ответил $code"
            return null
        }
        val json = try { JSONObject(text) } catch (_: Throwable) {
            lastError = "YouTube: непонятный ответ"
            return null
        }
        val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotEmpty() && status != "OK") {
            lastError = "YouTube: " + json.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().ifBlank { status }
            return null
        }
        return fromStreamingData(json)
    }

    /** Достать поток из объекта, где есть streamingData (player-ответ или ytInitialPlayerResponse). */
    private fun fromStreamingData(json: JSONObject): Play? {
        val sd = json.optJSONObject("streamingData") ?: return null
        val title = json.optJSONObject("videoDetails")?.optString("title").orEmpty()
        var best: String? = null
        var bestLabel = ""
        var bestRate = -1L
        sd.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val u = f.optString("url")
                if (u.isBlank()) continue
                val rate = f.optLong("bitrate", 0L)
                if (rate > bestRate) {
                    bestRate = rate; best = u
                    bestLabel = f.optString("qualityLabel").ifBlank { f.optString("quality") }
                }
            }
        }
        if (best != null) return Play(best, "https://www.youtube.com/", title, bestLabel)
        // только видео без звука — играем, но честно предупреждаем
        sd.optJSONArray("adaptiveFormats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                if (f.optString("mimeType").startsWith("audio", true)) continue
                val u = f.optString("url")
                if (u.isBlank()) continue
                val rate = f.optLong("bitrate", 0L)
                if (rate > bestRate) {
                    bestRate = rate; best = u
                    bestLabel = f.optString("qualityLabel")
                }
            }
        }
        if (best != null) {
            lastError = "у этого ролика нет потока со звуком — играю видео без звука"
            return Play(best, "https://www.youtube.com/", title, bestLabel)
        }
        lastError = "в ответе нет прямых ссылок (нужна расшифровка подписи)"
        return null
    }

    /** Второй путь: обычная страница просмотра, из неё ytInitialPlayerResponse. */
    private fun ytPage(id: String): Play? {
        val (code, html) = NetDirect.requestText(
            "https://m.youtube.com/watch?v=$id&hl=ru",
            mapOf("User-Agent" to UA_WEB, "Accept-Language" to "ru", "Accept" to "text/html"),
            timeoutMs = 14_000
        )
        if (code !in 200..299) {
            lastError = "страница YouTube: HTTP $code"
            return null
        }
        val marker = Regex("""ytInitialPlayerResponse\s*=\s*""").find(html)
            ?: run { lastError = "страница YouTube без данных плеера"; return null }
        val startIdx = marker.range.last + 1
        val json = extractJson(html, startIdx) ?: run { lastError = "не разобрал данные плеера"; return null }
        val obj = try { JSONObject(json) } catch (_: Throwable) {
            lastError = "данные плеера не JSON"
            return null
        }
        val status = obj.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotEmpty() && status != "OK") {
            lastError = "YouTube: " + obj.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().ifBlank { status }
            return null
        }
        return fromStreamingData(obj)
    }

    /** Вырезать сбалансированный JSON-объект, начиная с позиции (строки внутри учитываются). */
    private fun extractJson(text: String, from: Int): String? {
        val start = text.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Публичные Piped-серверы: отдают прямые ссылки без ключа. */
    private fun piped(id: String): Play? {
        val hosts = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.reallyaweso.me",
            "https://api.piped.yt"
        )
        for (h in hosts) {
            val (code, text) = try {
                NetDirect.requestText("$h/streams/$id", mapOf("User-Agent" to UA_WEB, "Accept" to "application/json"), timeoutMs = 12_000)
            } catch (_: Throwable) {
                continue
            }
            if (code !in 200..299) continue
            val json = try { JSONObject(text) } catch (_: Throwable) { continue }
            val arr = json.optJSONArray("videoStreams") ?: continue
            var best: String? = null
            var label = ""
            var rate = -1L
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("videoOnly", false)) continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                val br = o.optLong("bitrate", 0L)
                if (br > rate) {
                    rate = br; best = u
                    label = o.optString("quality")
                }
            }
            if (best != null) return Play(best, "https://www.youtube.com/", json.optString("title"), label)
        }
        lastError = "Piped не ответил"
        return null
    }

    /** Публичные Invidious-серверы: formatStreams — потоки со звуком. */
    private fun invidious(id: String): Play? {
        val hosts = listOf("https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be", "https://invidious.f5.si")
        for (h in hosts) {
            val (code, text) = try {
                NetDirect.requestText("$h/api/v1/videos/$id", mapOf("User-Agent" to UA_WEB, "Accept" to "application/json"), timeoutMs = 12_000)
            } catch (_: Throwable) {
                continue
            }
            if (code !in 200..299) continue
            val json = try { JSONObject(text) } catch (_: Throwable) { continue }
            val arr = json.optJSONArray("formatStreams") ?: continue
            val o = arr.optJSONObject(0) ?: continue
            val u = o.optString("url")
            if (u.isNotBlank()) return Play(u, "https://www.youtube.com/", json.optString("title"), o.optString("resolution"))
        }
        lastError = "Invidious не ответил"
        return null
    }

    /* ===================== v4.81.0: варианты качества для скачивания ===================== */

    data class Variant(
        val label: String,
        val url: String,
        val referer: String?,
        val mime: String,
        val isHls: Boolean = false,
        val bytes: Long = 0L
    )

    /**
     * Все доступные потоки для выбора качества: у YouTube — progressive и видео-дорожки,
     * у VK — прямые mp4 240…2160, у RuTube — HLS и mp4. Пустой список — не удалось получить.
     */
    fun variants(link: String): List<Variant> {
        lastError = ""
        val url = link.trim()
        val out = ArrayList<Variant>()
        val yt = youtubeId(url)
        if (yt != null) {
            for (client in listOf("ANDROID", "WEB")) {
                val json = playerJson(yt, client) ?: continue
                val sd = json.optJSONObject("streamingData") ?: continue
                sd.optJSONArray("formats")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        val u = f.optString("url")
                        if (u.isBlank()) continue
                        val label = f.optString("qualityLabel").ifBlank { f.optString("quality") }
                        out.add(Variant(label + " · mp4", u, "https://www.youtube.com/", "video/mp4", bytes = f.optLong("contentLength", 0L)))
                    }
                }
                sd.optJSONArray("adaptiveFormats")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        if (f.optString("mimeType").startsWith("audio", true)) continue
                        val u = f.optString("url")
                        if (u.isBlank()) continue
                        val label = f.optString("qualityLabel")
                        out.add(Variant(label + " · без звука", u, "https://www.youtube.com/", "video/mp4", bytes = f.optLong("contentLength", 0L)))
                    }
                }
                if (out.isNotEmpty()) break
            }
            return out.distinctBy { it.url }
        }
        if (url.contains("rutube.ru/video/", true)) {
            val id = Regex("""/video/([0-9a-f]{8,})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            if (id != null) {
                val (code, text) = try {
                    NetDirect.requestText(
                        "https://rutube.ru/api/video/$id/",
                        mapOf("User-Agent" to UA_WEB, "Accept" to "application/json", "Referer" to "https://rutube.ru/"),
                        timeoutMs = 12_000
                    )
                } catch (_: Throwable) {
                    0 to ""
                }
                if (code in 200..299) {
                    try {
                        val json = JSONObject(text)
                        val mp4 = json.optString("video_url")
                        if (mp4.isNotBlank()) out.add(Variant("mp4", mp4, "https://rutube.ru/", "video/mp4"))
                        val hls = json.optJSONObject("video_balancer")?.optString("m3u8").orEmpty()
                        if (hls.isNotBlank()) out.add(Variant("HLS (плейлист)", hls, "https://rutube.ru/", "application/x-mpegURL", isHls = true))
                    } catch (_: Throwable) {
                    }
                }
            }
            return out
        }
        if (url.contains("vk.com/video", true) || url.contains("vkvideo.ru/video", true) || url.contains("vk.com/clip", true)) {
            val m = Regex("""(?:video|clip)(-?\d+)_(\d+)""").find(url)
            if (m != null) {
                val oid = m.groupValues[1]
                val vid = m.groupValues[2]
                val (code, text) = try {
                    NetDirect.requestText(
                        "https://vk.com/video_ext.php?oid=$oid&id=$vid&hd=3",
                        mapOf("User-Agent" to UA_WEB, "Accept-Language" to "ru", "Referer" to "https://vk.com/"),
                        timeoutMs = 12_000
                    )
                } catch (_: Throwable) {
                    0 to ""
                }
                if (code in 200..299) {
                    Regex(""""url(240|360|480|720|1080|1440|2160)":"(https:[^"]+)"""")
                        .findAll(text)
                        .forEach { mm ->
                            out.add(
                                Variant(
                                    mm.groupValues[1] + "p · mp4",
                                    mm.groupValues[2].replace("\\/", "/"),
                                    "https://vk.com/",
                                    "video/mp4"
                                )
                            )
                        }
                }
            }
            return out.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 }
        }
        if (url.endsWith(".mp4", true)) return listOf(Variant("mp4", url, null, "video/mp4"))
        if (url.endsWith(".m3u8", true)) return listOf(Variant("HLS (плейлист)", url, null, "application/x-mpegURL", isHls = true))
        return out
    }

    /** Сырой player-ответ YouTube (для разбора вариантов). */
    private fun playerJson(id: String, client: String): JSONObject? {
        val ctx = if (client == "ANDROID") JSONObject()
            .put("clientName", "ANDROID").put("clientVersion", "19.09.37").put("androidSdkVersion", 30)
            .put("hl", "ru").put("gl", "RU")
        else JSONObject().put("clientName", "WEB").put("clientVersion", "2.20260101.00.00").put("hl", "ru").put("gl", "RU")
        val body = JSONObject().put("videoId", id).put("context", JSONObject().put("client", ctx))
            .put("contentCheckOk", true).put("racyCheckOk", true).toString()
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "X-Goog-Api-Format-Version" to "2",
            "Accept-Language" to "ru",
            "User-Agent" to if (client == "ANDROID") UA_YT else UA_WEB
        )
        val (code, text) = try {
            NetDirect.requestText("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", headers, "POST", body, timeoutMs = 12_000)
        } catch (_: Throwable) {
            return null
        }
        if (code !in 200..299) return null
        return try { JSONObject(text) } catch (_: Throwable) { null }
    }

    private fun rutube(link: String): Play? {
        val id = Regex("""/video/([0-9a-f]{8,})/?""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)
        if (id == null) { lastError = "не понял id RuTube"; return null }
        val (code, text) = try {
            NetDirect.requestText(
                "https://rutube.ru/api/video/$id/",
                mapOf(
                    "User-Agent" to UA_WEB,
                    "Accept" to "application/json",
                    "Referer" to "https://rutube.ru/"
                ),
                timeoutMs = 12_000
            )
        } catch (e: Throwable) {
            lastError = "RuTube недоступен: " + (e.message ?: "сеть")
            return null
        }
        if (code !in 200..299) { lastError = "RuTube ответил $code"; return null }
        val json = try { JSONObject(text) } catch (_: Throwable) { lastError = "RuTube: непонятный ответ"; return null }
        val title = json.optString("title")
        val hls = json.optJSONObject("video_balancer")?.optString("m3u8").orEmpty()
        if (hls.isNotBlank()) return Play(hls, "https://rutube.ru/", title, "HLS")
        val mp4 = json.optString("video_url").orEmpty()
        if (mp4.isNotBlank()) return Play(mp4, "https://rutube.ru/", title, "mp4")
        lastError = "RuTube не отдал поток"
        return null
    }

    private fun vk(link: String): Play? {
        val m = Regex("""(?:video|clip)(-?\d+)_(\d+)""").find(link)
        val oid = m?.groupValues?.get(1)
        val vid = m?.groupValues?.get(2)
        if (oid == null || vid == null) { lastError = "не понял id видео VK"; return null }
        val headers = mapOf("User-Agent" to UA_WEB, "Accept-Language" to "ru", "Referer" to "https://vk.com/")
        val pages = listOf(
            "https://vk.com/video_ext.php?oid=$oid&id=$vid&hd=3",
            "https://vk.com/video_ext.php?oid=$oid&id=$vid"
        )
        for (page in pages) {
            val (code, text) = try {
                NetDirect.requestText(page, headers, timeoutMs = 12_000)
            } catch (_: Throwable) {
                continue
            }
            if (code !in 200..299) continue
            val best = Regex(""""url(240|360|480|720|1080|1440|2160)":"(https:[^"]+)"""")
                .findAll(text)
                .map { it.groupValues[1].toInt() to it.groupValues[2].replace("\\/", "/") }
                .sortedByDescending { it.first }
                .firstOrNull()
            if (best != null) return Play(best.second, "https://vk.com/", "", best.first.toString() + "p")
        }
        lastError = "VK не отдал прямой поток (закрытое или приватное видео)"
        return null
    }
}
