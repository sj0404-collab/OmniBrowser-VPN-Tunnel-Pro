package com.santiago43rus.rupoop.util

/**
 * Shared genre/category constants used across onboarding, settings, feed and search.
 * Consolidated from the three duplicate copies that previously lived in
 * Dialogs.kt, SettingsScreen.kt and TvSettingsScreen.kt.
 */
object Genres {
    val ALL: List<String> = listOf(
        "аниме", "боевики", "комедии", "фантастика", "ужасы",
        "драма", "документальные", "мультфильмы", "сериалы", "мультсериалы"
    )

    /** Genres that map to the "фильм" suffix when building Rutube search queries. */
    val FILM_GENRES: List<String> = listOf(
        "боевик", "комедия", "драма", "ужасы", "фантастика", "триллер", "детектив", "мелодрама"
    )

    fun toQuery(genre: String): String =
        if (FILM_GENRES.any { it.equals(genre, ignoreCase = true) }) "$genre фильм" else genre
}
