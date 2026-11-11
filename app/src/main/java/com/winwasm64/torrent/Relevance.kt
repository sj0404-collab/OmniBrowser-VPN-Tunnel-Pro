package com.winwasm64.torrent

/**
 * v4.56.0: «это вообще про то, что я просил?»
 *
 * Жалобы: по «трали» в торрентах выдавало Truck World и Echoes of Mystralia, а VK показывал «первые
 * попавшиеся видео». Причина — поиск разворачивал запрос синонимами и ассоциациями, а вот проверить,
 * относится ли найденное к запросу, было некому: всё валилось в список как есть.
 *
 * Здесь простая, но строгая проверка: считаем, сколько значимых слов запроса (и его вариантов) есть
 * в названии. 0 — это чужое; 1 и больше — своё.
 */
object Relevance {

    private val STOP = setOf(
        "the", "and", "for", "with", "mp4", "mkv", "avi", "webm", "mp3", "flac", "pdf", "epub", "fb2",
        "cbr", "cbz", "zip", "rar", "torrent", "скачать", "торрент", "смотреть", "онлайн", "сезон",
        "серия", "серии", "часть", "фильм", "игра", "книга", "манга", "web", "rip", "bdrip", "webrip",
        "x264", "x265", "hevc", "aac", "ac3", "dts", "рус", "rus", "eng", "лицензия", "пиратка",
        // v4.57.0: служебные слова ввода — «найди рпг» должно искать «рпг», а не «найди»
        "найди", "найти", "покажи", "поиск", "ищи", "мне", "пожалуйста", "ещё", "еще"
    )

    /**
     * v4.60.0: КЭШ. Раньше на КАЖДУЮ карточку списка вызывался QueryExpander.variants(),
     * а тот ходил в Википедию за ассоциациями — то есть на 200 найденных файлов уходило 200
     * сетевых запросов по 8–10 секунд. Отсюда «приложение не отвечает» и ползущая выдача.
     * Теперь список слов считается один раз на запрос и берётся из памяти.
     */
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Значимые слова запроса и его вариантов. Без сети: только основа слова, транслит, синонимы. */
    fun tokens(query: String, variants: List<String> = emptyList()): List<String> {
        val key = query.trim().lowercase() + "|" + variants.joinToString(",")
        tokenCache[key]?.let { return it }
        // синонимы из локального словаря (черепашки → tmnt) — тоже без сети
        val all = (listOf(query) + variants + ImageSearch.aliasesFor(query)).distinct()
        val out = mutableSetOf<String>()
        for (v in all) {
            for (raw in v.lowercase().split(Regex("""[^a-zа-яё0-9]+"""))) {
                if (raw.length < 3) continue
                if (raw in STOP) continue
                out.add(raw)
                // основа слова: «трали» → «трал»
                QueryExpander.stem(raw)?.let { if (it.length >= 3) out.add(it) }
            }
        }
        // латиница для кириллицы (рпг → rpg) — без сети
        QueryExpander.translit(query)?.let { t ->
            for (raw in t.lowercase().split(Regex("""[^a-zа-яё0-9]+"""))) {
                if (raw.length >= 3 && raw !in STOP) out.add(raw)
            }
        }
        val result = out.toList()
        if (tokenCache.size > 200) tokenCache.clear()
        tokenCache[key] = result
        return result
    }

    /** 0 — не по теме, >0 — сколько слов запроса нашлось в названии. */
    fun score(query: String, title: String, variants: List<String> = emptyList()): Int {
        val t = title.lowercase()
        if (t.isBlank()) return 0
        var hits = 0
        for (token in tokens(query, variants)) {
            if (t.contains(token)) hits++
        }
        return hits
    }

    /**
     * v4.57.0: «он не ищет описанием и прочим».
     *
     * Смотрим не только название, но и все текстовые поля карточки: описание, канал/автор, теги,
     * источник. Иногда нужного слова в заголовке нет, а в описании оно есть — раньше такой результат
     * считался «не по теме» и выбрасывался.
     */
    fun scoreFields(query: String, variants: List<String> = emptyList(), vararg fields: String): Int {
        val text = fields.joinToString(" ").lowercase()
        if (text.isBlank()) return 0
        var hits = 0
        for (token in tokens(query, variants)) {
            if (text.contains(token)) hits++
        }
        return hits
    }

    /** Оставить только релевантное. Если совсем ничего не нашлось — вернуть исходный список. */
    fun filter(query: String, items: List<String>, variants: List<String> = emptyList()): List<Int> {
        val keep = items.mapIndexedNotNull { idx, title ->
            if (score(query, title, variants) > 0) idx else null
        }
        return if (keep.isEmpty()) items.indices.toList() else keep
    }
}
