package com.winwasm64.torrent

import android.content.Context

/**
 * v4.54.0: ручная «глубина поиска» — сколько запросов/страниц реально отправлять.
 *
 * Просьба: дать возможность самому выбирать количество запросов от 1 до N во всех вкладках.
 * Один общий регулятор на приложение (и отдельные значения по вкладкам, если понадобится):
 *  • 1 — быстро и мало: один запрос, одна страница;
 *  • 3 — обычный режим (по умолчанию): запрос + синонимы/ассоциации, 2–3 страницы;
 *  • до 8 — «копать глубоко»: все варианты запроса и много страниц (дольше, но больше ссылок).
 */
object SearchSettings {

    const val MIN = 1
    const val MAX = 8
    const val DEFAULT = 3

    private const val PREFS = "omni_search_settings"
    private const val KEY_DEPTH = "depth"
    private const val KEY_LAST = "last_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сколько запросов/страниц использовать во вкладке [scope] (torrents/files/photo/video). */
    fun depth(context: Context, scope: String = "global"): Int {
        val p = prefs(context)
        val scoped = p.getInt("depth_$scope", -1)
        if (scoped in MIN..MAX) return scoped
        return p.getInt(KEY_DEPTH, DEFAULT).coerceIn(MIN, MAX)
    }

    fun setDepth(context: Context, value: Int, scope: String = "global") {
        prefs(context).edit().putInt("depth_$scope", value.coerceIn(MIN, MAX)).apply()
    }

    /** Не запоминать ли последний запрос — на будущее (сейчас не используется). */
    fun lastQuery(context: Context): String = prefs(context).getString(KEY_LAST, "").orEmpty()

    fun setLastQuery(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST, value).apply()
    }
}
