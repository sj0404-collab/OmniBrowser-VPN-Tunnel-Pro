package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.55.0: «кто всё это время ищет» — теперь есть ИИ-куратор.
 *
 * Раньше поиск был набором эвристик: собрали всё, что отдали сайты, и показали как есть — отсюда
 * битые ссылки, мусор и «плохое качество». Теперь можно включить куратора на бесплатном
 * открытом шлюзе Pollinations (без ключа и регистрации):
 *  • он придумывает дополнительные варианты запроса (синонимы, оригинальные названия);
 *  • он проверяет найденное и отбраковывает явно не то («пятая стража» YouTube ≠ манга «Страж»).
 *
 * Куратор необязателен: если сеть/шлюз недоступны, поиск работает по-старому, без пауз.
 */
object AiCurator {

    private const val PREFS = "omni_ai"
    private const val KEY_ENABLED = "enabled"
    private const val TEXT_ENDPOINT = "https://text.pollinations.ai/openai"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * Дополнительные варианты запроса от модели: «страж» → «Страж (The Guardian)», «страж манга»…
     * Возвращает пустой список, если выключено или шлюз не ответил.
     */
    fun expand(context: Context, query: String, limit: Int = 3): List<String> {
        if (!isEnabled(context)) return emptyList()
        val prompt = """
            Ты помощник поиска. Для запроса «$query» предложи до $limit коротких поисковых вариантов
            (синонимы, оригинальное/английское название, уточнение по типу: манга, фильм, игра, книга).
            Ответ строго JSON-массивом строк, без пояснений.
        """.trimIndent()
        val raw = ask(prompt) ?: return emptyList()
        val arr = extractJsonArray(raw) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.length in 3..60 && out.none { it.equals(v, true) } && !v.equals(query, true)) out.add(v)
        }
        return out
    }

    /**
     * Какие из найденных названий действительно относятся к запросу.
     * Возвращает набор индексов «оставить». При любой проблеме — null (значит «ничего не фильтруем»).
     */
    fun keepIndices(context: Context, query: String, titles: List<String>, limit: Int = 30): Set<Int>? {
        if (!isEnabled(context) || titles.isEmpty()) return null
        val indexed = titles.take(limit).mapIndexed { i, t -> "$i. ${t.take(120)}" }.joinToString("\n")
        val prompt = """
            Запрос пользователя: «$query».
            Ниже пронумерованные найденные названия. Оставь только те, что действительно про это
            (тот же персонаж/произведение/тема; мусор, чужие фильмы, политика, музыка не по теме — отбрось).
            Ответ строго JSON-массивом номеров, которые оставить, без пояснений.
            $indexed
        """.trimIndent()
        val raw = ask(prompt) ?: return null
        val arr = extractJsonArray(raw) ?: return null
        val out = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val idx = arr.optInt(i, -1)
            if (idx >= 0 && idx < titles.size) out.add(idx)
        }
        return out.ifEmpty { null }
    }

    /* ===================== транспорт ===================== */

    private fun ask(prompt: String): String? {
        val body = JSONObject().apply {
            put("model", "openai")
            put(
                "messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
        }.toString()
        return try {
            val conn = (URL(TEXT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "OmniBrowser/4.55 (Android)")
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) {
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractJsonArray(raw: String): JSONArray? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            JSONArray(raw.substring(start, end + 1))
        } catch (_: Throwable) {
            null
        }
    }
}
