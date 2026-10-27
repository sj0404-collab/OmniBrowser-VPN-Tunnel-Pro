package com.winwasm64.ui.browser

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.6.0: встроенный ИИ-ассистент. Все провайдеры — OpenAI-совместимый `chat/completions` (поток SSE).
 *
 * Проверено 2026-10-27 из песочницы:
 *  • Pollinations `text.pollinations.ai/openai` — работает БЕЗ ключа (gpt-oss-20b, 1 ответ в 30 с на IP);
 *    с бесплатным ключом (enter.pollinations.ai) — без лимита и больше моделей.
 *  • OpenCode Zen `opencode.ai/zen/v1` — free-модели (big-pickle, glm-5.3-flash-free, deepseek-v4.1-flash-free…);
 *    без ключа — лимит на IP («Free usage limit reached»), с ключом аккаунта OpenCode — работает.
 *  • OpenRouter / Groq / Gemini / Mistral / Cerebras / Hugging Face — нужен бесплатный ключ (без ключа 401, проверено).
 * Список моделей подтягивается с `/models` провайдера, когда есть ключ.
 */
object AiAssistant {
    const val TAG = "ИИ"
    private const val PREFS = "omni_ai"

    class Provider(
        val id: String,
        val title: String,
        val url: String,
        val defaultModel: String,
        val needsKey: Boolean,
        val keyUrl: String,
        val note: String,
        /** фильтр для списка моделей с /models (null — все) */
        val modelFilter: ((String) -> Boolean)? = null
    )

    val providers: List<Provider> = listOf(
        Provider("pollinations", "Pollinations", "https://text.pollinations.ai/openai", "openai", false,
            "https://enter.pollinations.ai/keys", "без ключа: 1 ответ в 30 с, модель gpt-oss-20b · с бесплатным ключом — без лимита"),
        Provider("opencode", "OpenCode Zen", "https://opencode.ai/zen/v1/chat/completions", "big-pickle", false,
            "https://opencode.ai/auth", "free-модели (big-pickle, glm-5.3-flash, deepseek-v4.1-flash…) · без ключа лимит на IP, с ключом аккаунта — стабильно",
            modelFilter = { it.endsWith("-free") || it == "big-pickle" }),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free", true,
            "https://openrouter.ai/keys", "десятки free-моделей по бесплатному ключу", modelFilter = { it.endsWith(":free") || it == "openrouter/free" }),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", true,
            "https://console.groq.com/keys", "бесплатный ключ, очень быстрые ответы"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-flash-latest", true,
            "https://aistudio.google.com/apikey", "бесплатный ключ в AI Studio", modelFilter = { it.contains("gemini") && !it.contains("embedding") && !it.contains("image") && !it.contains("tts") && !it.contains("live") }),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", true,
            "https://console.mistral.ai/api-keys", "бесплатный тариф Experiment", modelFilter = { !it.contains("embed") && !it.contains("ocr") && !it.contains("moderation") && !it.contains("voxtral") }),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1/chat/completions", "gpt-oss-120b", true,
            "https://cloud.cerebras.ai", "бесплатный ключ, быстрые модели"),
        Provider("huggingface", "Hugging Face", "https://router.huggingface.co/v1/chat/completions", "zai-org/GLM-5.3-Flash", true,
            "https://huggingface.co/settings/tokens", "токен HF (read), бесплатная квота"),
        Provider("custom", "Свой сервер", "", "", true, "", "любой OpenAI-совместимый адрес …/v1/chat/completions (LM Studio, Ollama, прокси)")
    )

    class Msg(val role: String, text: String, val provider: String = "") {
        val textState = mutableStateOf(text)
        var text: String
            get() = textState.value
            set(v) { textState.value = v }
    }

    val messages = mutableStateListOf<Msg>()
    val busy = mutableStateOf(false)
    val status = mutableStateOf("")
    /** результаты проверки: provider id → строка */
    val testResults = mutableStateOf<Map<String, String>>(emptyMap())
    private val cancel = AtomicBoolean(false)

    // ------------------------------------------------------------------ настройки
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(ctx: Context, id: String): String = prefs(ctx).getString("key_$id", "")?.trim().orEmpty()
    fun setKey(ctx: Context, id: String, v: String) = prefs(ctx).edit().putString("key_$id", v.trim()).apply()
    fun model(ctx: Context, id: String): String = prefs(ctx).getString("model_$id", "")?.ifBlank { null } ?: providers.first { it.id == id }.defaultModel
    fun setModel(ctx: Context, id: String, v: String) = prefs(ctx).edit().putString("model_$id", v.trim()).apply()
    fun customUrl(ctx: Context): String = prefs(ctx).getString("custom_url", "").orEmpty()
    fun setCustomUrl(ctx: Context, v: String) = prefs(ctx).edit().putString("custom_url", v.trim()).apply()
    fun selected(ctx: Context): String = prefs(ctx).getString("selected", "auto") ?: "auto"
    fun setSelected(ctx: Context, v: String) = prefs(ctx).edit().putString("selected", v).apply()
    fun cachedModels(ctx: Context, id: String): List<String> = prefs(ctx).getString("models_$id", "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun urlOf(ctx: Context, p: Provider): String = if (p.id == "custom") customUrl(ctx) else p.url
    fun configured(ctx: Context, p: Provider): Boolean = if (p.id == "custom") customUrl(ctx).startsWith("http") else (!p.needsKey || key(ctx, p.id).isNotBlank())

    /** Порядок попыток в режиме «Авто»: провайдеры с ключом, затем бесплатные без ключа. */
    fun autoOrder(ctx: Context): List<Provider> {
        val withKey = providers.filter { it.id != "custom" && key(ctx, it.id).isNotBlank() }
        val custom = providers.filter { it.id == "custom" && configured(ctx, it) }
        val keyless = providers.filter { !it.needsKey && key(ctx, it.id).isBlank() }
        return (withKey + custom + keyless).distinctBy { it.id }
    }

    // ------------------------------------------------------------------ HTTP
    private fun headers(ctx: Context, p: Provider): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "text/event-stream, application/json"
        val k = key(ctx, p.id)
        if (k.isNotBlank()) h["Authorization"] = "Bearer $k"
        if (p.id == "openrouter") { h["HTTP-Referer"] = "https://github.com/sj0404-collab/OmniBrowser-VPN-Tunnel-Pro"; h["X-Title"] = "OmniBrowser" }
        return h
    }

    private fun post(ctx: Context, url: String, headers: Map<String, String>, body: String, timeoutMs: Int): java.net.HttpURLConnection {
        val c = MediaGrabber.open(url, headers, "POST", timeoutMs)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c
    }

    private fun errorText(code: Int, raw: String): String {
        val t = raw.trim()
        val msg = try { val o = JSONObject(t); (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).ifBlank { null } } catch (_: Exception) { null }
        val m = msg ?: t.take(160).ifBlank { "пустой ответ" }
        return when {
            code == 401 || code == 403 -> "нет доступа (HTTP $code): нужен ключ или ключ неверный · $m"
            code == 402 -> "лимит бесплатного тарифа: $m"
            code == 429 -> "слишком часто (HTTP 429): $m"
            code >= 500 -> "сервер провайдера недоступен (HTTP $code): $m"
            m.contains("FreeUsageLimit", true) || m.contains("Free usage limit", true) -> "лимит free-моделей на этот IP исчерпан — нужен ключ аккаунта OpenCode"
            m.contains("Free limit", true) -> "лимит без ключа: 1 ответ в 30 с (ключ снимает лимит)"
            else -> "HTTP $code: $m"
        }
    }

    /** Один запрос к провайдеру с потоковым выводом. Возвращает null при успехе или текст ошибки. */
    private fun complete(ctx: Context, p: Provider, model: String, msgs: JSONArray, stream: Boolean, maxTokens: Int, onDelta: (String) -> Unit): String? {
        val url = urlOf(ctx, p)
        if (!url.startsWith("http")) return "адрес сервера не задан"
        val body = JSONObject().apply {
            put("model", model); put("messages", msgs); put("stream", stream)
            if (maxTokens > 0) put("max_tokens", maxTokens)
        }.toString()
        val c = try { post(ctx, url, headers(ctx, p), body, 120_000) } catch (e: Exception) { return "нет соединения: ${e.message ?: e.javaClass.simpleName}" }
        try {
            val code = try { c.responseCode } catch (e: Exception) { return "нет ответа: ${e.message}" }
            if (code !in 200..299) {
                val raw = try { (c.errorStream ?: c.inputStream).bufferedReader().readText() } catch (_: Exception) { "" }
                return errorText(code, raw)
            }
            val ctype = c.contentType ?: ""
            val ins = c.inputStream
            if (!stream || !ctype.contains("event-stream")) {
                val raw = ins.bufferedReader().readText()
                if (raw.trimStart().startsWith("data:")) return parseSse(raw.lineSequence().iterator(), onDelta)
                val o = try { JSONObject(raw) } catch (_: Exception) { return "непонятный ответ: ${raw.take(120)}" }
                val ch = o.optJSONArray("choices")
                val text = ch?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) {
                    val err = o.optJSONObject("error")?.optString("message")
                    return if (!err.isNullOrBlank()) err else "пустой ответ"
                }
                onDelta(text)
                return null
            }
            val r = BufferedReader(InputStreamReader(ins, Charsets.UTF_8))
            return parseSse(generateSequence { r.readLine() }.iterator(), onDelta)
        } catch (e: IOException) {
            return "обрыв: ${e.message}"
        } finally { try { c.disconnect() } catch (_: Exception) {} }
    }

    /** Разбор SSE-потока OpenAI: `data: {...}` → delta.content. null — успех, иначе текст ошибки. */
    private fun parseSse(lines: Iterator<String>, onDelta: (String) -> Unit): String? {
        var got = false
        var lastErr: String? = null
        while (lines.hasNext()) {
            if (cancel.get()) break
            val line = lines.next()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val o = try { JSONObject(data) } catch (_: Exception) { continue }
            o.optJSONObject("error")?.let { lastErr = it.optString("message") }
            val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val t = ch.optJSONObject("delta")?.optString("content").orEmpty().ifEmpty { ch.optJSONObject("message")?.optString("content").orEmpty() }
            if (t.isNotEmpty()) { got = true; onDelta(t) }
        }
        if (!got && !cancel.get()) return lastErr ?: "пустой ответ"
        return null
    }

    // ------------------------------------------------------------------ диалог
    private const val SYSTEM = "Ты — встроенный помощник браузера OmniBrowser. Отвечай на русском языке (если пользователь не пишет на другом), кратко и по делу, без вступлений. " +
        "Если дан текст страницы — опирайся на него и не выдумывай. Форматируй ответ простым текстом: короткие абзацы, списки через «•», без markdown-заголовков."

    private fun buildMessages(pageContext: String?): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", SYSTEM + (if (!pageContext.isNullOrBlank()) "\n\nКонтекст текущей страницы:\n$pageContext" else "")))
        val hist = messages.filter { it.role == "user" || (it.role == "assistant" && it.text.isNotBlank() && !it.text.startsWith("⚠")) }.takeLast(12)
        for (m in hist) arr.put(JSONObject().put("role", m.role).put("content", m.text))
        return arr
    }

    fun stop() { cancel.set(true) }
    fun clear() { messages.clear(); status.value = "" }

    /** Отправляет вопрос; ответ пишется в последнее сообщение ассистента по мере прихода. */
    fun ask(ctx: Context, userText: String, pageContext: String?) {
        if (busy.value) return
        val app = ctx.applicationContext
        messages.add(Msg("user", userText))
        val reply = Msg("assistant", "")
        messages.add(reply)
        busy.value = true
        cancel.set(false)
        status.value = ""
        val msgs = buildMessages(pageContext)
        Thread({
            val sel = selected(app)
            val order = if (sel == "auto") autoOrder(app) else providers.filter { it.id == sel }
            val errors = ArrayList<String>()
            var ok = false
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            for (p in order) {
                if (cancel.get()) break
                val model = model(app, p.id)
                main.post { status.value = "${p.title} · $model…" }
                val t0 = SystemClock.elapsedRealtime()
                val sb = StringBuilder()
                val err = complete(app, p, model, msgs, stream = true, maxTokens = 0) { d ->
                    sb.append(d)
                    val s = sb.toString()
                    main.post { reply.text = s }
                }
                val ms = SystemClock.elapsedRealtime() - t0
                if (err == null) {
                    ok = true
                    VpnLog.i(TAG, "${p.title} · $model · ответ ${sb.length} зн. за $ms мс")
                    main.post { status.value = "${p.title} · $model · $ms мс" }
                    break
                }
                if (cancel.get()) break
                errors.add("${p.title}: $err")
                VpnLog.w(TAG, "${p.title} · $model: $err ($ms мс)" + (if (order.size > 1) " — пробуем следующего" else ""))
                main.post { reply.text = ""; status.value = "${p.title}: $err" }
            }
            main.post {
                if (!ok && !cancel.get()) {
                    reply.text = "⚠ Не удалось получить ответ.\n" + errors.joinToString("\n") { "• $it" } +
                        (if (order.none { it.needsKey && key(app, it.id).isNotBlank() }) "\n\nСовет: добавьте бесплатный ключ (⚙ Провайдеры) — Groq, OpenRouter или Gemini выдают его за минуту." else "")
                } else if (cancel.get() && reply.text.isBlank()) reply.text = "⏹ остановлено"
                busy.value = false
            }
        }, "omni-ai").start()
    }

    /** Проверка провайдера: короткий запрос без потока. Пишет результат в [testResults] и журнал. */
    fun test(ctx: Context, p: Provider, onDone: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun put(s: String) { main.post { testResults.value = testResults.value + (p.id to s); onDone?.invoke(s) } }
        put("⏳ проверяем…")
        Thread({
            val model = model(app, p.id)
            val t0 = SystemClock.elapsedRealtime()
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "Ответь ровно одним словом: готов"))
            val sb = StringBuilder()
            val err = complete(app, p, model, msgs, stream = false, maxTokens = 64) { sb.append(it) }
            val ms = SystemClock.elapsedRealtime() - t0
            val res = if (err == null) "✓ $model · $ms мс · «${sb.toString().trim().take(40)}»" else "✗ $err"
            VpnLog.i(TAG, "проверка ${p.title}: $res")
            put(res)
        }, "omni-ai-test").start()
    }

    fun testAll(ctx: Context) {
        for (p in providers) if (configured(ctx, p)) test(ctx, p)
    }

    /** Список моделей провайдера (GET …/models), кэшируется в prefs. */
    fun fetchModels(ctx: Context, p: Provider, onDone: (List<String>, String?) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            try {
                val base = urlOf(app, p).substringBefore("/chat/completions")
                val url = if (p.id == "pollinations") "https://text.pollinations.ai/models" else "$base/models"
                val c = MediaGrabber.open(url, headers(app, p).filterKeys { it != "Content-Type" }, "GET", 30_000)
                val code = c.responseCode
                val raw = (if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)).bufferedReader().readText()
                if (code !in 200..299) { main.post { onDone(emptyList(), errorText(code, raw)) }; return@Thread }
                val ids = ArrayList<String>()
                val trimmed = raw.trim()
                val arr: JSONArray? = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("data") ?: JSONObject(trimmed).optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").ifBlank { o.optString("name") }
                    if (id.isNotBlank() && (p.modelFilter?.invoke(id) != false)) ids.add(id)
                }
                val list = ids.distinct().sorted()
                prefs(app).edit().putString("models_${p.id}", list.joinToString("\n")).apply()
                VpnLog.i(TAG, "${p.title}: моделей доступно ${list.size}")
                main.post { onDone(list, null) }
            } catch (e: Exception) {
                main.post { onDone(emptyList(), e.message ?: e.javaClass.simpleName) }
            }
        }, "omni-ai-models").start()
    }

    /** JS для сбора контекста страницы: выделение, заголовок, адрес и текст (до 7000 знаков). */
    const val PAGE_JS = "(function(){try{var s=window.getSelection?String(window.getSelection()):'';var t=document.body?document.body.innerText:'';" +
        "return JSON.stringify({sel:s.slice(0,4000),text:t.replace(/\\s+/g,' ').slice(0,7000),title:document.title,url:location.href});}catch(e){return '{}';}})()"

    class PageInfo(val title: String, val url: String, val text: String, val selection: String)

    fun parsePage(raw: String?): PageInfo? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val str = org.json.JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(str)
            PageInfo(o.optString("title"), o.optString("url"), o.optString("text"), o.optString("sel"))
        } catch (_: Exception) { null }
    }

    fun contextOf(p: PageInfo?): String? {
        if (p == null || p.text.isBlank()) return null
        return "Заголовок: ${p.title}\nАдрес: ${p.url}\n" + (if (p.selection.isNotBlank()) "Выделенный пользователем фрагмент: ${p.selection}\n" else "") + "Текст: ${p.text}"
    }
}
