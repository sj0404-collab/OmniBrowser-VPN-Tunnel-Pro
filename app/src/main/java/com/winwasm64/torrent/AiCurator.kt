package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    private const val KEY_PROVIDER = "provider"

    /** Состояние агента — то, что человек видит строкой в интерфейсе. */
    enum class State { OFF, SLEEPING, THINKING, ASKING, DONE, ERROR }

    private val state = AtomicReference(State.SLEEPING)
    // v4.60.0: значения настроек держим в памяти — строка состояния перерисовывается 2 раза в секунду
    @Volatile private var cachedEnabled: Boolean? = null
    @Volatile private var cachedStatusText: String? = null
    @Volatile private var cachedModelId: String? = null
    /** Готовые варианты запроса: посчитали один раз — используем сразу в следующий раз. */
    private val variantsCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** Запросы, по которым расчёт уже идёт. */
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val stateNote = AtomicReference("")
    private val stateSince = AtomicLong(System.currentTimeMillis())

    /**
     * v4.66.0: «бери модели те, что в ИИ-чате браузера — OpenRouter и другие там работают исправно».
     *
     * Агент больше не ходит в один-единственный шлюз Pollinations (там 402/лимиты и 20+ секунд
     * ожидания). Теперь он спрашивает те же провайдеры и модели, что и ИИ-ассистент
     * ([com.winwasm64.ui.browser.AiAssistant]): OpenCode, Pollinations, LLM7, OpenRouter, Groq,
     * Gemini, Mistral… — по порядку «авто», пока кто-нибудь не ответит.
     */
    @Volatile private var cachedProvider: String? = null

    fun isEnabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        cachedEnabled = value
        return value
    }

    /** Что было в прошлый раз: «OK · вариантов 3» или текст ошибки. Видно в интерфейсе. */
    fun status(context: Context): String {
        cachedStatusText?.let { return it }
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "ещё не проверялся").orEmpty()
        cachedStatusText = value
        return value
    }

    private fun setStatus(context: Context, value: String) {
        cachedStatusText = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
    }

    fun setEnabled(context: Context, value: Boolean) {
        cachedEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) mark(State.SLEEPING, "включён — ждёт запроса") else mark(State.OFF, "выключен человеком")
    }

    /** «auto» или id провайдера из ИИ-чата (opencode, llm7, openrouter…). */
    fun providerId(context: Context): String {
        cachedProvider?.let { return it }
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER, "auto").orEmpty().ifBlank { "auto" }
        cachedProvider = value
        return value
    }

    /** Для совместимости со старым интерфейсом: «модель» агента — это выбранный провайдер. */
    fun model(context: Context): String = providerId(context)

    /** Человеческое имя: «авто · OpenCode Zen», «OpenRouter · …:free». */
    fun modelLabel(context: Context): String {
        val id = providerId(context)
        return try {
            if (id == "auto") {
                val first = com.winwasm64.ui.browser.AiAssistant.autoOrder(context)
                    .firstOrNull { com.winwasm64.ui.browser.AiAssistant.configured(context, it) }
                if (first == null) "авто · нет провайдеров" else "авто · ${first.title}"
            } else {
                val p = com.winwasm64.ui.browser.AiAssistant.providers.firstOrNull { it.id == id }
                if (p == null) id else "${p.title} · ${com.winwasm64.ui.browser.AiAssistant.model(context, p.id)}"
            }
        } catch (_: Throwable) {
            id
        }
    }

    fun setProvider(context: Context, id: String) {
        cachedProvider = id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDER, id).apply()
        mark(State.SLEEPING, "агент переключён: ${modelLabel(context)}")
    }

    /** Заменить агента на другого: переключаем провайдера по кругу (авто → провайдеры из чата). */
    fun cycleModel(context: Context): String {
        val ids = mutableListOf("auto")
        try {
            ids.addAll(com.winwasm64.ui.browser.AiAssistant.autoOrder(context).map { it.id })
        } catch (_: Throwable) {
        }
        val cur = ids.indexOf(providerId(context)).let { if (it < 0) 0 else it }
        setProvider(context, ids[(cur + 1) % ids.size])
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

    /** Проверка связи с ИИ: спрашиваем провайдеров ИИ-чата и честно пишем, кто ответил. */
    fun selfTest(context: Context): Boolean {
        mark(State.ASKING, "проверка связи · ${modelLabel(context)}")
        val answer = ask("Ответь одним словом: работает", context, 20_000)
        val ok = !answer.isNullOrBlank()
        setStatus(context, if (ok) "OK · " + answer!!.take(40) + " · " + modelLabel(context) else "нет ответа ни от одного провайдера")
        mark(
            if (ok) State.DONE else State.ERROR,
            if (ok) "проверка пройдена · ${modelLabel(context)}" else "все провайдеры молчат"
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
        val raw = ask(prompt, context, 14_000)
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
     * v4.60.0: варианты запроса для поиска — БЕЗ риска «приложение не отвечает».
     *
     * Как это работает теперь:
     *  1) если варианты для этого запроса уже считались — отдаём из памяти мгновенно;
     *  2) иначе спрашиваем «быструю» модель (обычно 0,3–1 с) с дедлайном [deadlineMs];
     *  3) если быстрая не ответила — «умная» продолжает думать В ФОНЕ (её ответ попадёт в память
     *     и применится при следующем поиске), а поиск идёт дальше на своих синонимах и транслите.
     *
     * Раньше поиск стоял и ждал модель по 20–25 секунд — из-за этого список пустовал и система
     * показывала «OmniBrowser & VPN Pro не отвечает».
     */
    fun expandWithin(context: Context, query: String, limit: Int = 2, deadlineMs: Long = 6_000): List<String> {
        if (!isEnabled(context)) return emptyList()
        val key = query.trim().lowercase()
        variantsCache[key]?.let {
            mark(State.DONE, "вариантов ${it.size} · взял готовые")
            return it
        }
        mark(State.THINKING, "придумываю варианты запроса")
        val fast = java.util.concurrent.FutureTask {
            askVariants(query, limit, listOf("openai-fast"), 5_000)
        }
        Thread(fast, "AiFast").apply { isDaemon = true; start() }
        val quick = try {
            fast.get(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            emptyList<String>()
        }
        if (quick.isNotEmpty()) {
            variantsCache[key] = quick
            setStatus(context, "OK · вариантов ${quick.size}")
            mark(State.DONE, "вариантов ${quick.size} · ${modelLabel(context)}")
            return quick
        }
        // умная модель — в фоне: её ответ пригодится в следующий раз
        Thread({
            val slow = askVariants(query, limit, listOf(model(context), "openai"), 45_000)
            if (slow.isNotEmpty()) {
                variantsCache[key] = slow
                setStatus(context, "OK · вариантов ${slow.size}")
                mark(State.DONE, "вариантов ${slow.size} · ${modelLabel(context)} (в фоне)")
            } else {
                setStatus(context, "нет ответа · ищу без вариантов ИИ")
                mark(State.ERROR, "модель не ответила — ищу без её вариантов")
            }
        }, "AiSmart").apply { isDaemon = true; start() }
        mark(State.THINKING, "модель думает в фоне · ищу без её вариантов")
        return emptyList()
    }

    /**
     * v4.61.0: «где-то процесс не в том потоке» — и это была правда.
     *
     * expandWithin() ждал быструю модель до 6 секунд. В «Файлах» он вызывался в рабочем потоке, а
     * в «Торрентах» — прямо в главном: интерфейс стоял 6 секунд, и Android показывал
     * «OmniBrowser & VPN Pro не отвечает». Теперь ожидания в главном потоке НЕТ вообще:
     *  • [cachedVariants] — мгновенно отдаёт готовые варианты из памяти (или пустой список);
     *  • [prefetch] — запускает расчёт в фоне и сразу возвращает управление.
     * Поиск никогда не ждёт ИИ: варианты, посчитанные в прошлый раз, применяются сразу.
     */
    fun cachedVariants(query: String): List<String> =
        variantsCache[query.trim().lowercase()] ?: emptyList()

    /** Считать варианты в фоне (не блокирует вызывающий поток ни на миллисекунду). */
    fun prefetch(context: Context, query: String, limit: Int = 2) {
        if (!isEnabled(context)) return
        val key = query.trim().lowercase()
        if (key.isBlank() || variantsCache.containsKey(key) || inflight.contains(key)) return
        inflight.add(key)
        Thread({
            try {
                mark(State.THINKING, "варианты для «${query.take(18)}»")
                mark(State.ASKING, "спрашиваю ${modelLabel(context)}")
                val variants = askVariants(query, limit, context, 25_000)
                if (variants.isNotEmpty()) {
                    variantsCache[key] = variants
                    setStatus(context, "OK · вариантов ${variants.size} · ${modelLabel(context)}")
                    mark(State.DONE, "вариантов ${variants.size} · ${modelLabel(context)}")
                } else {
                    setStatus(context, "нет ответа · ищу без вариантов ИИ")
                    mark(State.ERROR, "провайдеры ИИ не ответили — ищу без их вариантов")
                }
            } catch (_: Throwable) {
                mark(State.ERROR, "сбой агента")
            } finally {
                inflight.remove(key)
            }
        }, "AiPrefetch").apply { isDaemon = true; start() }
    }

    /** Запрос вариантов у конкретных моделей и разбор JSON-массива. Без изменения состояния. */
    private fun askVariants(
        query: String,
        limit: Int,
        context: Context?,
        perModelMs: Int
    ): List<String> {
        val prompt = """
            Ты помощник поиска. Для запроса «$query» предложи до $limit коротких поисковых вариантов
            (синонимы, оригинальное/английское название, уточнение по типу: манга, фильм, игра, книга).
            Ответ строго JSON-массивом строк, без пояснений.
        """.trimIndent()
        val raw = ask(prompt, context, perModelMs) ?: return emptyList()
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
        val raw = ask(prompt, context, 20_000)
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
     * v4.66.0: один вопрос модели — через провайдеров ИИ-чата браузера (AiAssistant.quickAsk).
     * Порядок «авто»: сначала те, у кого есть ключ, затем бесплатные без ключа. Первый ответивший
     * и выигрывает, поэтому один медленный/лимитированный шлюз больше не блокирует весь поиск.
     */
    private fun ask(prompt: String, context: Context?, readTimeoutMs: Int = 20_000): String? {
        val ctx = context ?: return null
        return try {
            com.winwasm64.ui.browser.AiAssistant.quickAsk(
                ctx,
                system = "Ты помощник поиска внутри приложения. Отвечай ровно тем, что просят: " +
                    "если нужен JSON — только JSON, без пояснений и разметки.",
                user = prompt,
                maxTokens = 280,
                timeoutMs = readTimeoutMs,
                maxProviders = 4
            )
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
