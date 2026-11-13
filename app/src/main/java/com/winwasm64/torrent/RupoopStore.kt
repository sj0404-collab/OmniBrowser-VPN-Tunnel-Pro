package com.winwasm64.torrent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v4.89.0: читаем то, что уже хранит сам Rupoop.
 *
 * Раньше наши экраны истории/плейлистов держали собственный список — он был отдельным от «Кино»
 * и пустел, как только видео смотрели внутри Rupoop. Теперь единственный источник правды —
 * реестр Rupoop (`user_registry.json`): подписки, история просмотров, история поиска, плейлисты.
 */
object RupoopStore {

    data class Sub(val name: String, val platform: String, val url: String, val avatar: String)

    private fun registryFile(ctx: Context) = File(ctx.filesDir, "user_registry.json")

    private fun root(ctx: Context): JSONObject? = runCatching {
        val f = registryFile(ctx)
        if (!f.exists() || f.length() == 0L) null else JSONObject(f.readText())
    }.getOrNull()

    /** Подписки Rupoop — включая YouTube-каналы (у них есть ссылка на канал). */
    fun subscriptions(ctx: Context): List<Sub> {
        val arr = root(ctx)?.optJSONArray("subscriptions") ?: return emptyList()
        val out = ArrayList<Sub>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            out.add(
                Sub(
                    name = name,
                    platform = o.optString("platform").orEmpty(),
                    url = o.optString("url").orEmpty(),
                    avatar = o.optString("avatar_url").orEmpty()
                )
            )
        }
        return out.distinctBy { it.name.lowercase() }
    }

    /** История поиска Rupoop (последние запросы). */
    fun searchQueries(ctx: Context, limit: Int = 40): List<String> {
        val arr = root(ctx)?.optJSONArray("searchHistory") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val q = arr.optString(i).trim()
            if (q.isNotEmpty()) out.add(q)
            if (out.size >= limit) break
        }
        return out
    }
}
