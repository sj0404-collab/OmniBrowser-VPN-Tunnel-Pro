package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.78.0: подписки на каналы внутри приложения.
 *
 * Раньше подписаться было негде: карточка канала просто открывала сайт. Теперь канал хранится
 * на устройстве (имя, площадка, адрес), показывается в разделе «📌 Подписки», а его видео
 * открываются внутри приложения — никаких переходов на сайт.
 */
object VideoSubs {

    private const val PREFS = "omni_video_subs"
    private const val KEY = "channels"

    data class Channel(
        val name: String,
        val source: String,
        val url: String = "",
        /** сколько подписчиков показывала площадка (для подписи) */
        val subs: String = ""
    )

    fun list(ctx: Context): List<Channel> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Channel(
                    name = o.optString("name"),
                    source = o.optString("source", VideoFeed.SRC_YOUTUBE),
                    url = o.optString("url"),
                    subs = o.optString("subs")
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isSubscribed(ctx: Context, name: String, source: String): Boolean =
        list(ctx).any { it.name.equals(name, true) && it.source == source }

    fun add(ctx: Context, ch: Channel) {
        val cur = list(ctx).toMutableList()
        cur.removeAll { it.name.equals(ch.name, true) && it.source == ch.source }
        cur.add(0, ch)
        save(ctx, cur)
    }

    fun remove(ctx: Context, name: String, source: String) {
        val cur = list(ctx).toMutableList()
        cur.removeAll { it.name.equals(name, true) && it.source == source }
        save(ctx, cur)
    }

    /** true — теперь подписан. */
    fun toggle(ctx: Context, ch: Channel): Boolean {
        return if (isSubscribed(ctx, ch.name, ch.source)) {
            remove(ctx, ch.name, ch.source); false
        } else {
            add(ctx, ch); true
        }
    }

    private fun save(ctx: Context, list: List<Channel>) {
        val arr = JSONArray()
        for (c in list.take(200)) {
            arr.put(JSONObject().put("name", c.name).put("source", c.source).put("url", c.url).put("subs", c.subs))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Адрес страницы канала — для чтения выдачи встроенным браузером. */
    fun channelUrl(source: String, name: String, url: String = ""): String {
        if (url.startsWith("http")) return url
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        return when (source) {
            VideoFeed.SRC_VK -> "https://m.vk.com/video?q=$q"
            VideoFeed.SRC_RUTUBE -> "https://rutube.ru/search/?query=$q"
            else -> "https://m.youtube.com/results?search_query=$q"
        }
    }
}
