package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * v4.55.0: «кто всё это время ищет» — теперь есть ИИ-куратор.
 *
 * Раньше поиск был набором эвристик: собрали всё, что отдали сайты, и показали как есть — отсюда
 * битые ссылки, мусор и «плохое качество». Теперь можно включить куратора на бесплатном
 * открытом шлюзе Pollinations (без ключа и регистрации):
 *  • он придумывает дополнительные варианты запроса (синонимы, оригинальные названия);
 *  • он проверяет найденное и отбраковывает явно не то («пятая стража» YouTube ≠ манга «Страж»).
 *
 * v4.58.0: «нету индикации — работает агент, спит или размышляет, и нет кнопки выключить/заменить».
 * Теперь у агента есть настоящее состояние, которое видно в интерфейсе каждые полсекунды:
 *  💤 спит (ждёт запроса) → 🤔 размышляет (придумывает варианты) → ⏳ спрашивает модель →
 *  🟢 работал (что именно сделал) → 🔴 ошибка; и ⛔ выключен, если человек его выключил.
 * Модель можно заменить прямо из интерфейса: «умный» (openai) ↔ «быстрый» (openai-fast).
 *
 * Куратор необязателен: если сеть/шлюз недоступны, поиск работает по-старому, без пауз.
 */
object AiCurator {

    private const val PREFS = "omni_ai"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STATUS = "status"
    private const val KEY_MODEL = "model"
    private const val TEXT_ENDPOINT = "https://text.pollinations.ai/openai"

    /** Состояние агента — то, что человек видит строкой в интерфейсе. */
    enum class State { OFF, SLEEPING, THINKING, ASKING, DONE, ERROR }

    private val state = AtomicReference(State.SLEEPING)
    private val stateNote = AtomicReference("")
    private val stateSince = AtomicLong(System.currentTimeMillis())

    /** Доступные модели на бесплатном шлюзе (проверены: отвечают без ключа). */
    val MODELS = listOf("openai" to "умный", "openai-fast" to "быстрый")

    // v4.56.0: по умолчанию ВКЛ — раньше человек не понимал, включился ли куратор вообще
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Что было в прошлый раз: «OK · вариантов 3» или текст ошибки. Видно в интерфейсе. */
    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, "ещё не проверялся")
            .orEmpty()

    private fun setStatus(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) mark(State.SLEEPING, "включён — ждёт запроса") else mark(State.OFF, "выключен человеком")
    }

    /** Текущая модель (id для шлюза). */
    fun model(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, "openai")
            .orEmpty().ifBlank { "openai" }

    /** Человеческое имя модели: «умный» / «быстрый». */
    fun modelLabel(context: Context): String {
        val id = model(context)
        return MODELS.firstOrNull { it.first == id }?.second ?: id
    }

    fun setModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, id).apply()
        mark(State.SLEEPING, "модель заменена: ${modelLabel(context)}")
    }

    /** Заменить агента на другого: переключаем модель по кругу и возвращаем её имя. */
    fun cycleModel(context: Context): String {
        val ids = MODELS.map { it.first }
        val next = ids[(ids.indexOf(model(context)).let { if (it < 0) 0 else it } + 1) % ids.size]
        setModel(context, next)
        return modelLabel(context)
    }

    /* ===================== состояние агента ===================== */

    fun mark(value: State, note: String) {
        state.set(value)
        stateNote.set(note)
        stateSince.set(System.currentTimeMillis())
    }

    fun currentState(): State = state.get()

    /**
     * Живая строка состояния — её рисует интерфейс (обновляется каждые 0.5 с).
     * Именно этого не хватало: «работает / спит / размышляет» было не видно.
     */
    fun liveLine(context: Context, now: Long = System.currentTimeMillis()): String {
        if (!isEnabled(context)) return "⛔ выключен — нажмите «Включить»"
        val st = state.get()
        val note = stateNote.get()
        val secs = ((now - stateSince.get()) / 1000L).coerceAtLeast(0L)
        fun tail(): String = if (secs >= 3) " · ${secs} с" else ""
        return when (st) {
            State.THINKING -> "🤔 размышляет · ${note.ifBlank { "готовит запрос" }}" + tail()
            State.ASKING -> "⏳ спрашивает модель · ${note.ifBlank { "запрос к ИИ" }}" + tail()
            State.DONE -> if (secs < 120) {
                "🟢 работал · ${note.ifBlank { "готово" }}"
            } else {
                "💤 спит · последний ответ ${secs / 60} мин назад"
            }
            State.ERROR -> if (secs < 120) {
                "🔴 ошибка · ${note.ifBlank { "нет связи" }}"
            } else {
                "💤 спит · последняя ошибка ${secs / 60} мин назад"
            }
            State.SLEEPING -> "💤 спит · ждёт запроса" + if (note.isNotBlank()) " ($note)" else ""
            State.OFF -> "⛔ выключен"
        }
    }

    /** Проверка связи с ИИ: возвращает true, если шлюз ответил. */
    fun selfTest(context: Context): Boolean {
        mark(State.ASKING, "проверка связи")
        val answer = ask("Ответь одним словом: работает", context)
        val ok = !answer.isNullOrBlank()
        setStatus(context, if (ok) "OK · " + answer!!.take(40) else "нет ответа от шлюза")
        mark(
            if (ok) State.DONE else State.ERROR,
            if (ok) "проверка пройдена · ${modelLabel(context)}" else "шлюз не ответил"
        )
        return ok
    }

    /**
     * Дополнительные варианты запроса от модели: «страж» → «Страж (The Guardian)», «страж манга»…
     * Возвращает пустой список, если выключено или шлюз не ответил.
     */
    fun expand(context: Context, query: String, limit: Int = 3): List<String> {
        if (!isEnabled(context)) {
            mark(State.OFF, "выключен")
            return emptyList()
        }
        mark(State.THINKING, "придумываю варианты запроса")
        val prompt = """
            Ты помощник поиска. Для запроса «$query» предложи до $limit коротких поисковых вариантов
            (синонимы, оригинальное/английское название, уточнение по типу: манга, фильм, игра, книга).
            Ответ строго JSON-массивом строк, без пояснений.
        """.trimIndent()
        mark(State.ASKING, "варианты для «${query.take(24)}»")
        // v4.59.0: сначала «быстрая» — она отвечает мгновенно; «умная» думает ~23 с и не успевала
        val raw = askWith(prompt, listOf("openai-fast", model(context), "openai"), 8_000)
        if (raw == null) {
            setStatus(context, "нет ответа · варианты не получены")
            mark(State.ERROR, "нет ответа от шлюза")
            return emptyList()
        }
        val arr = extractJsonArray(raw) ?: run {
            mark(State.ERROR, "ответ не разобран")
            return emptyList()
        }
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.length in 3..60 && out.none { it.equals(v, true) } && !v.equals(query, true)) out.add(v)
        }
        setStatus(context, "OK · вариантов ${out.size}")
        mark(State.DONE, "вариантов ${out.size} · ${modelLabel(context)}")
        return out
    }

    /**
     * v4.59.0: варианты с жёстким дедлайном.
     *
     * Раньше поиск ЖДАЛ модель: пока «умная» думала 20–25 с, список раздач оставался пустым и
     * человек видел «Список раздач пуст». Теперь ждём не дольше [deadlineMs]; не успела — ищем без
     * её вариантов (свои синонимы/транслит остаются), а модель договаривает уже в фоне.
     */
    fun expandWithin(context: Context, query: String, limit: Int = 2, deadlineMs: Long = 7_000): List<String> {
        if (!isEnabled(context)) return emptyList()
        val task = java.util.concurrent.FutureTask { expand(context, query, limit) }
        val thread = Thread(task, "AiExpand").apply { isDaemon = true; start() }
        return try {
            task.get(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            task.cancel(true)
            mark(State.DONE, "работал · модель думает дольше ${deadlineMs / 1000} с — ищу без её вариантов")
            emptyList()
        }
    }

    /**
     * Какие из найденных названий действительно относятся к запросу.
     * Возвращает набор индексов «оставить». При любой проблеме — null (значит «ничего не фильтруем»).
     */
    fun keepIndices(context: Context, query: String, titles: List<String>, limit: Int = 30): Set<Int>? {
        if (!isEnabled(context) || titles.isEmpty()) return null
        mark(State.THINKING, "проверяю найденное (${titles.size})")
        val indexed = titles.take(limit).mapIndexed { i, t -> "$i. ${t.take(120)}" }.joinToString("\n")
        val prompt = """
            Запрос пользователя: «$query».
            Ниже пронумерованные найденные названия. Оставь только те, что действительно про это
            (тот же персонаж/произведение/тема; мусор, чужие фильмы, политика, музыка не по теме — отбрось).
            Ответ строго JSON-массивом номеров, которые оставить, без пояснений.
            $indexed
        """.trimIndent()
        mark(State.ASKING, "отбираю по теме из ${minOf(titles.size, limit)}")
        val raw = ask(prompt, context)
        if (raw == null) {
            setStatus(context, "нет ответа · фильтр не применён")
            mark(State.ERROR, "нет ответа · фильтр не применён")
            return null
        }
        val arr = extractJsonArray(raw) ?: run {
            mark(State.ERROR, "ответ не разобран · фильтр не применён")
            return null
        }
        val out = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val idx = arr.optInt(i, -1)
            if (idx >= 0 && idx < titles.size) out.add(idx)
        }
        val result = out.ifEmpty { null }
        setStatus(
            context,
            if (result == null) "OK · всё релевантно" else "OK · оставил ${result.size} из ${minOf(titles.size, limit)}"
        )
        mark(
            State.DONE,
            if (result == null) "всё релевантно" else "оставил ${result.size} из ${minOf(titles.size, limit)}"
        )
        return result
    }

    /* ===================== транспорт ===================== */

    /**
     * Запрос к модели: выбранная первой, при отказе — запасная.
     * v4.59.0: таймаут на чтение стал параметром. Раньше было жёстко 20 с, а «умная» модель на
     * длинном промпте отвечает ~23 с — отсюда «🔴 нет ответа от шлюза» на каждом поиске.
     */
    private fun ask(prompt: String, context: Context? = null, readTimeoutMs: Int = 30_000): String? {
        val preferred = context?.let { model(it) } ?: "openai"
        val order = (listOf(preferred) + MODELS.map { it.first }).distinct()
        val budget = (readTimeoutMs / order.size).coerceAtLeast(6_000)
        return askWith(prompt, order, budget)
    }

    /** Спросить модели по порядку: первая ответившая и выигрывает. */
    private fun askWith(prompt: String, ids: List<String>, perModelMs: Int): String? {
        for (id in ids.distinct()) {
            val answer = post(id, prompt, perModelMs)
            if (!answer.isNullOrBlank()) return answer
        }
        return null
    }

    private fun post(modelId: String, prompt: String, readTimeoutMs: Int = 30_000): String? {
        val body = JSONObject().apply {
            put("model", modelId)
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
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "OmniBrowser/4.58 (Android)")
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) {
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val msg = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = msg?.optString("content").orEmpty()
                if (content.isNotBlank()) content else msg?.optString("reasoning").orEmpty().ifBlank { null }
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
