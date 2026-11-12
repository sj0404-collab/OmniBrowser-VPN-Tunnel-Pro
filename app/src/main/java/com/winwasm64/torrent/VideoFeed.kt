package com.winwasm64.torrent

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.71.0: лента видео для отдельной вкладки «Видео» (внешний вид как у обычного видеоприложения).
 *
 * Раньше поиск видео жил внутри вкладки «Торренты» и был строкой текста с кнопками. Теперь это
 * самостоятельный раздел: обложки, длительность, автор, просмотры, дата — как в обычном клиенте.
 *
 * Источники:
 *  • YouTube — внутренний JSON-эндпоинт (тот же, что у [YouTubeSearch]), без ключей;
 *  • RuTube — публичный поиск `/api/search/video/` (может отвечать пусто из-за гео-фильтра,
 *    тогда честно сообщаем и предлагаем открыть сайт/включить VPN);
 *  • VK Видео — открытого поиска без входа нет, поэтому возвращаем подсказку.
 */
object VideoFeed {

    const val SRC_YOUTUBE = "YouTube"
    const val SRC_RUTUBE = "RuTube"
    const val SRC_VK = "VK"

    data class Clip(
        val id: String,
        /** video | playlist | channel */
        val kind: String,
        val title: String,
        val author: String,
        val lengthText: String,
        val viewsText: String,
        val publishedText: String,
        val thumb: String,
        val url: String,
        val live: Boolean = false,
        val source: String = SRC_YOUTUBE,
        /** числовые поля — для сортировки «новые / популярные / старые» */
        val views: Long = 0L,
        val days: Int = 0
    )

    data class Page(
        val clips: List<Clip> = emptyList(),
        val continuation: String? = null,
        /** Человеческое пояснение, если источник ничего не отдал. */
        val note: String? = null
    )

    fun search(source: String, query: String, sort: Int = 0, continuation: String? = null): Page {
        val q = query.trim()
        if (q.isBlank()) return Page(note = "Введите запрос")
        return when (source) {
            SRC_RUTUBE -> rutube(q, continuation)
            SRC_VK -> Page(
                note = "VK Видео не отдаёт поиск без входа в аккаунт. Откройте поиск VK во встроенном окне — " +
                    "там разбор идёт через WebView."
            )
            else -> youtube(q, sort, continuation)
        }
    }

    /* ------------------------------- YouTube ------------------------------- */

    private fun youtube(query: String, sort: Int, continuation: String?): Page {
        val page = try {
            YouTubeSearch.search(query, "Все", sort, "Любая", continuation)
        } catch (t: Throwable) {
            return Page(note = "YouTube не ответил: " + (t.message ?: "сеть").take(90))
        }
        val clips = page.items.map { it.toClip() }
        return Page(
            clips = clips,
            continuation = page.continuation,
            note = if (clips.isEmpty() && continuation == null) "По запросу «$query» ничего не нашлось" else null
        )
    }

    private fun YouTubeSearch.Item.toClip(): Clip {
        val thumbUrl = thumb.ifBlank {
            if (id.length == 11) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else ""
        }
        return Clip(
            id = id,
            kind = if (kind == YouTubeSearch.KIND_PLAYLIST) "playlist"
            else if (kind == YouTubeSearch.KIND_CHANNEL) "channel" else "video",
            title = title,
            author = author,
            lengthText = lengthText,
            viewsText = viewsText,
            publishedText = publishedText,
            thumb = thumbUrl,
            url = url,
            live = extra == "live",
            source = SRC_YOUTUBE,
            views = views,
            days = daysAgo
        )
    }

    /* -------------------------------- RuTube ------------------------------- */

    private const val RUTUBE_PAGE = 20

    private fun rutube(query: String, continuation: String?): Page {
        val offset = continuation?.removePrefix("off:")?.toIntOrNull() ?: 0
        val url = "https://rutube.ru/api/search/video/?query=" + enc(query) +
            "&limit=$RUTUBE_PAGE&offset=$offset"
        val raw = try {
            // requestText возвращает пару (код ответа, тело)
            val (code, text) = com.winwasm64.ui.browser.NetDirect.requestText(
                url,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
                    "Accept" to "application/json",
                    "Referer" to "https://rutube.ru/search/?query=" + enc(query)
                ),
                timeoutMs = 20_000
            )
            if (code !in 200..299) {
                return Page(note = "RuTube ответил кодом $code — обычно это гео-фильтр: включите VPN и повторите")
            }
            text
        } catch (t: Throwable) {
            return Page(note = "RuTube не ответил: " + (t.message ?: "сеть").take(90))
        }
        if (raw.isBlank()) return Page(note = "RuTube не ответил (пустой ответ)")
        val results = try {
            JSONObject(raw).optJSONArray("results")
        } catch (_: Throwable) {
            null
        } ?: return Page(note = "RuTube вернул непонятный ответ")
        val clips = ArrayList<Clip>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title").ifBlank { "видео $id" }
            val videoUrl = o.optString("video_url").ifBlank { if (id.isNotBlank()) "https://rutube.ru/video/$id/" else "" }
            if (videoUrl.isBlank()) continue
            val sec = o.optInt("duration", 0)
            val created = o.optLong("created_ts", 0L)
            clips.add(
                Clip(
                    id = id,
                    kind = "video",
                    title = title,
                    author = o.optJSONObject("author")?.optString("name").orEmpty(),
                    lengthText = if (sec > 0) String.format(Locale.US, "%d:%02d", sec / 60, sec % 60) else "",
                    viewsText = o.optLong("view_count", 0L).takeIf { it > 0 }
                        ?.let { "$it просмотров" }.orEmpty(),
                    publishedText = if (created > 0L) ago(created * 1000L) else "",
                    thumb = o.optString("thumbnail_url"),
                    url = videoUrl,
                    source = SRC_RUTUBE,
                    views = o.optLong("view_count", 0L),
                    days = if (created > 0L) ((System.currentTimeMillis() - created * 1000L) / 86_400_000L).toInt() else 0
                )
            )
        }
        val hasNext = try {
            JSONObject(raw).optBoolean("has_next", false)
        } catch (_: Throwable) {
            false
        }
        return Page(
            clips = clips,
            continuation = if (hasNext) "off:${offset + RUTUBE_PAGE}" else null,
            note = if (clips.isEmpty()) {
                if (offset == 0) "RuTube не отдал результаты по «$query» — часто это гео-фильтр: " +
                    "включите VPN (щит) и повторите" else null
            } else null
        )
    }

    /* ------------------------------- утилиты ------------------------------- */

    private fun enc(s: String): String = try {
        java.net.URLEncoder.encode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun ago(ts: Long): String {
        val days = ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "сегодня"
            days == 1 -> "вчера"
            days < 30 -> "$days дн. назад"
            days < 365 -> (days / 30).toString() + " мес. назад"
            else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))
        }
    }
}
