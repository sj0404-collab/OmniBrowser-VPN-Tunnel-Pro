package com.winwasm64.torrent

import android.content.Context

/**
 * v4.50.0: подписки на каналы и поиск ВНУТРИ них.
 *
 * Просьба: «добавь подписки на существующие каналы и поиск в них — например Айвори, lightfox manga».
 * Каналы хранятся локально (можно добавить свой), поиск идёт по выдаче площадки с уточнением названия
 * канала, поэтому не нужны ни ключи, ни вход в аккаунт.
 */
object VideoChannels {

    data class Channel(
        val name: String,
        /** Поисковый запрос, который открывает сам канал. */
        val query: String,
        val platform: String = "YouTube",
        /** Пользовательский канал — можно удалить. */
        val custom: Boolean = false
    )

    /** Каналы про мангу/аниме/игры, которые чаще всего ищут по-русски. */
    val defaults: List<Channel> = listOf(
        Channel("Айвори", "Айвори", "YouTube"),
        Channel("LightFox Manga", "LightFox Manga", "YouTube"),
        Channel("Just Manga", "Just Manga манга", "YouTube"),
        Channel("Манга-тян", "Манга-тян", "YouTube"),
        Channel("Anilibria", "Anilibria аниме", "YouTube"),
        Channel("AniPlay", "Aniplay аниме обзор", "YouTube"),
        Channel("IXBT Games", "iXBT Games", "YouTube"),
        Channel("StopGame", "StopGame обзор", "YouTube"),
        Channel("Кино Поиск", "Кинопоиск трейлер", "YouTube")
    )

    private const val PREFS = "omni_video_channels"
    private const val KEY_CUSTOM = "custom"

    /** Свои каналы в формате «Название|||запрос». */
    fun custom(context: Context): List<Channel> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CUSTOM, emptySet()) ?: emptySet()
        return raw.mapNotNull { line ->
            val parts = line.split("|||")
            if (parts.size < 2 || parts[0].isBlank()) null
            else Channel(parts[0], parts[1].ifBlank { parts[0] }, "YouTube", custom = true)
        }.sortedBy { it.name.lowercase() }
    }

    fun all(context: Context): List<Channel> = custom(context) + defaults

    fun add(context: Context, name: String, query: String = "") {
        val clean = name.trim()
        if (clean.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CUSTOM, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add("$clean|||${query.trim().ifBlank { clean }}")
        prefs.edit().putStringSet(KEY_CUSTOM, current).apply()
    }

    fun remove(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CUSTOM, emptySet())?.toMutableSet() ?: return
        current.removeAll { it.substringBefore("|||") == name }
        prefs.edit().putStringSet(KEY_CUSTOM, current).apply()
    }

    /** Поиск внутри канала: уточняем запрос названием канала. */
    fun scopedQuery(query: String, channel: Channel): String {
        val q = query.trim()
        return if (q.isBlank()) channel.query else "$q ${channel.query}".trim()
    }
}
