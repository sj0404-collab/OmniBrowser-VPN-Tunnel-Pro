package com.winwasm64.torrent

/**
 * v4.49.0: память поиска между вкладками.
 *
 * Жалоба: «переходя между вкладками сбрасывается и найденное, и само слово». Причина была в том, что
 * состояние жило в `remember` внутри composable — при уходе с вкладки оно уничтожалось. Теперь запрос,
 * фильтры, страница и найденные результаты лежат здесь и переживают любые переключения вкладок.
 */
object SearchCache {

    // 0 = Торренты, 1 = Файлы, 2 = Фото, 3 = Видео
    var mode: Int = 0

    // ---- Торренты ----
    var torrentQuery: String = ""
    var torrentKind: String = "Все"
    var torrentQuality: String = ""
    var torrentSort: Int = 0

    // ---- Файлы ----
    var directQuery: String = ""
    var directKind: String = "Все"
    var directFormat: String = ""
    var directSort: Int = 0
    var directPage: Int = 0

    // ---- Фото ----
    var photoQuery: String = ""
    var photoFilter: String = "Фото"
    var photoPage: Int = 0

    // ---- Видео ----
    var videoQuery: String = ""
    var videoSource: String = "Все"

    fun clearAll() {
        torrentQuery = ""
        directQuery = ""
        photoQuery = ""
        videoQuery = ""
    }
}
