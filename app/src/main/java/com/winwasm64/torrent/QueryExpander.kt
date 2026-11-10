package com.winwasm64.torrent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.53.0: «искать то, о чём есть упоминания», как это делает галерея.
 *
 * Жалоба: по слову «винт» находилось 16 раздач, хотя в браузере их куда больше. Причина — поиск шёл
 * ровно по одной словоформе. Теперь запрос разворачивается:
 *  • основа слова (винт → «винт», «винты», «винтовой» — срезаем окончания);
 *  • известные синонимы/латиница (черепашки → tmnt);
 *  • ассоциации из Википедии (винт → «гребной винт», «воздушный винт»);
 *  • транслитерация (винтер → winter) для сайтов, которые индексируют латиницей.
 *
 * Каждый дополнительный вариант помечается в карточке как «по ассоциации», чтобы было видно,
 * откуда взялся результат.
 */
object QueryExpander {

    /** Все варианты запроса: первый — сам запрос. */
    fun variants(query: String, max: Int = 4): List<String> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf(clean)
        fun add(value: String?) {
            val v = value?.trim().orEmpty()
            if (v.length < 3) return
            if (out.none { it.equals(v, ignoreCase = true) }) out.add(v)
        }
        add(stem(clean))
        ImageSearch.aliasesFor(clean).forEach { add(it) }
        add(translit(clean))
        related(clean).forEach { add(it) }
        return out.take(max)
    }

    /** Есть ли вариант «ассоциацией» (для пометки в карточке). */
    fun isAssociation(original: String, variant: String): Boolean =
        !variant.equals(original.trim(), ignoreCase = true)

    /* ===================== основа слова ===================== */

    private val ENDINGS = listOf(
        "ами", "ями", "ов", "ев", "ые", "ая", "ое", "ый", "ий", "ой", "ам", "ям", "ах", "ях",
        "ии", "ия", "ей", "ом", "ем", "ы", "и", "а", "я", "у", "ю", "е", "о", "ь"
    )

    /** Грубый русский стеммер: без словарей, но снимает падежи и числа. */
    fun stem(word: String): String? {
        val lower = word.lowercase()
        for (end in ENDINGS) {
            if (lower.length > end.length + 3 && lower.endsWith(end)) {
                return lower.dropLast(end.length).trim()
            }
        }
        return null
    }

    /* ===================== транслитерация ===================== */

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    fun translit(text: String): String? {
        if (text.none { it in 'а'..'я' || it == 'ё' }) return null
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch in TRANSLIT) sb.append(TRANSLIT[ch]) else if (ch.isLetterOrDigit() || ch == ' ') sb.append(ch)
        }
        val res = sb.toString().trim()
        return if (res.length >= 3 && res != text.lowercase()) res else null
    }

    /* ===================== ассоциации из Википедии ===================== */

    /** Статьи, которые Википедия считает связанными с запросом — бесплатный «словарь ассоциаций». */
    fun related(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://ru.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=$encoded&srlimit=8&format=json&utf8=1"
        val body = httpGet(url) ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            val arr = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val title = arr.getJSONObject(i).optString("title")
                // «Винт (деталь)» → «Винт»; «Чип и Дейл спешат на помощь» оставляем как есть
                val cleaned = title.substringBefore('(').trim().ifBlank { title.trim() }
                if (cleaned.length < 3) continue
                if (cleaned.equals(query.trim(), ignoreCase = true)) continue
                if (out.none { it.equals(cleaned, ignoreCase = true) }) out.add(cleaned)
                if (out.size >= 2) break
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "OmniBrowser/4.53 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Throwable) {
        null
    }
}
