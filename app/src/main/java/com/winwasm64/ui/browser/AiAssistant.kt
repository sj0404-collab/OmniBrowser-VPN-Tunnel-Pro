package com.winwasm64.ui.browser

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный ИИ-ассистент. Все провайдеры — OpenAI-совместимый `chat/completions` (поток SSE, function calling).
 *
 * Проверено из песочницы 27.10.2026 (реальные запросы):
 *  • Pollinations `text.pollinations.ai/openai` — без ключа (gpt-oss-20b, 1 ответ/30 с на IP), tools работают; ключ снимает лимит.
 *  • LLM7 `api.llm7.io/v1` — без ключа модели tier=turbo (gpt-oss:20b, minimax-m2.7, codestral-latest, mistral-Nemo…), ~1 запрос/с;
 *    tier=pro (gemini/claude/gpt/deepseek…) — по бесплатному ключу dash.llm7.io. tools работают.
 *  • OpenCode Zen `opencode.ai/zen/v1` — free-модели, без ключа лимит IP, с ключом аккаунта — стабильно.
 *  • api.airforce (tier=free: mistral-large, gpt-oss-20b, llama-instant…), api.navy (premium=false: gemini-2.5-flash,
 *    deepseek-v4-flash, gpt-oss-120b…), NVIDIA NIM — списки открыты, ответы по бесплатному ключу.
 *  • OpenRouter / Groq / Gemini / Mistral / Cerebras / Hugging Face — бесплатный ключ (без ключа 401).
 * Каталог моделей обновляется автоматически — см. [AiCatalog]. Чаты сохраняются — см. [AiChats]. Агенты/MCP — [AiTools], [McpClient].
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
        val modelFilter: ((String) -> Boolean)? = null
    )

    val providers: List<Provider> = listOf(
        Provider("pollinations", "Pollinations", "https://text.pollinations.ai/openai", "openai", false,
            "https://enter.pollinations.ai/keys", "без ключа: 1 ответ в 30 с (gpt-oss-20b) · с бесплатным ключом — без лимита и больше моделей"),
        Provider("llm7", "LLM7", "https://api.llm7.io/v1/chat/completions", "gpt-oss:20b", false,
            "https://dash.llm7.io/#/api-keys", "без ключа: turbo-модели (gpt-oss:20b, minimax-m2.7, codestral) · с бесплатным ключом: gemini, claude, gpt, deepseek, kimi…"),
        Provider("opencode", "OpenCode Zen", "https://opencode.ai/zen/v1/chat/completions", "big-pickle", false,
            "https://opencode.ai/auth", "free-модели (big-pickle, glm-5.3-flash, deepseek-v4.1-flash…) · без ключа лимит на IP, с ключом аккаунта — стабильно",
            modelFilter = { it.endsWith("-free") || it == "big-pickle" }),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free", true,
            "https://openrouter.ai/keys", "десятки временных :free-моделей по бесплатному ключу", modelFilter = { it.endsWith(":free") || it == "openrouter/free" }),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", true,
            "https://console.groq.com/keys", "бесплатный ключ, очень быстрые ответы"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-flash-latest", true,
            "https://aistudio.google.com/apikey", "бесплатный ключ в AI Studio", modelFilter = { it.contains("gemini") && !it.contains("embedding") && !it.contains("image") && !it.contains("tts") && !it.contains("live") }),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", true,
            "https://console.mistral.ai/api-keys", "бесплатный тариф Experiment", modelFilter = { !it.contains("embed") && !it.contains("ocr") && !it.contains("moderation") && !it.contains("voxtral") }),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1/chat/completions", "gpt-oss-120b", true,
            "https://cloud.cerebras.ai", "бесплатный ключ, быстрые модели"),
        Provider("navy", "api.navy", "https://api.navy/v1/chat/completions", "gemini-2.5-flash", true,
            "https://api.navy", "бесплатный ключ (Discord): gemini-2.5-flash, deepseek-v4, gpt-oss-120b, mistral-large…"),
        Provider("airforce", "api.airforce", "https://api.airforce/v1/chat/completions", "mistral-large-latest", true,
            "https://api.airforce/dashboard", "бесплатный ключ: mistral-large, gpt-oss-20b, llama-instant, glm-4.7-flash…"),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/chat/completions", "nvidia/nemotron-3-super", true,
            "https://build.nvidia.com", "бесплатные кредиты по ключу build.nvidia.com"),
        Provider("huggingface", "Hugging Face", "https://router.huggingface.co/v1/chat/completions", "zai-org/GLM-5.3-Flash", true,
            "https://huggingface.co/settings/tokens", "токен HF (read), бесплатная квота"),
        Provider("custom", "Свой сервер", "", "", true, "", "любой OpenAI-совместимый адрес …/v1/chat/completions (LM Studio, Ollama, прокси)")
    )

    /** Сообщение чата. kind: text | tool (шаг агента). */
    class Msg(val role: String, text: String, val kind: String = "text", val provider: String = "") {
        val textState = mutableStateOf(text)
        var text: String
            get() = textState.value
            set(v) { textState.value = v }
    }

    class Chat(val id: String, title: String, agent: String, val created: Long) {
        val titleState = mutableStateOf(title)
        var title: String
            get() = titleState.value
            set(v) { titleState.value = v }
        val agentState = mutableStateOf(agent)
        var agent: String
            get() = agentState.value
            set(v) { agentState.value = v }
        val messages = mutableStateListOf<Msg>()
        var updated: Long = created
    }

    val chats = mutableStateListOf<Chat>()
    val current = mutableStateOf<Chat?>(null)
    val busy = mutableStateOf(false)
    val status = mutableStateOf("")
    val testResults = mutableStateOf<Map<String, String>>(emptyMap())
    private val cancel = AtomicBoolean(false)
    private var loaded = false

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
    fun mcpEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("mcp", true)
    fun setMcpEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("mcp", v).apply()

    private fun urlOf(ctx: Context, p: Provider): String = if (p.id == "custom") customUrl(ctx) else p.url
    fun configured(ctx: Context, p: Provider): Boolean = if (p.id == "custom") customUrl(ctx).startsWith("http") else (!p.needsKey || key(ctx, p.id).isNotBlank())

    /** Порядок попыток в режиме «Авто»: провайдеры с ключом, затем бесплатные без ключа. */
    fun autoOrder(ctx: Context): List<Provider> {
        val withKey = providers.filter { it.id != "custom" && key(ctx, it.id).isNotBlank() }
        val custom = providers.filter { it.id == "custom" && configured(ctx, it) }
        val keyless = providers.filter { !it.needsKey && key(ctx, it.id).isBlank() }
        return (withKey + custom + keyless).distinctBy { it.id }
    }

    // ------------------------------------------------------------------ чаты (все сохраняются)
    private fun chatsFile(ctx: Context) = File(File(ctx.filesDir, "ai").also { it.mkdirs() }, "chats.json")

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        AiCatalog.load(ctx)
        McpClient.load(ctx)
        try {
            val f = chatsFile(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = Chat(o.getString("id"), o.optString("title"), o.optString("agent", "chat"), o.optLong("created"))
                    c.updated = o.optLong("updated", c.created)
                    val ms = o.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until ms.length()) { val m = ms.getJSONObject(j); c.messages.add(Msg(m.getString("role"), m.optString("text"), m.optString("kind", "text"), m.optString("provider"))) }
                    chats.add(c)
                }
            }
        } catch (_: Exception) {}
        val cur = prefs(ctx).getString("current_chat", null)
        current.value = chats.firstOrNull { it.id == cur } ?: chats.firstOrNull() ?: newChat(ctx)
    }

    fun save(ctx: Context) {
        val app = ctx.applicationContext
        val arr = JSONArray()
        for (c in chats) {
            val ms = JSONArray()
            for (m in c.messages) ms.put(JSONObject().put("role", m.role).put("text", m.text).put("kind", m.kind).put("provider", m.provider))
            arr.put(JSONObject().put("id", c.id).put("title", c.title).put("agent", c.agent).put("created", c.created).put("updated", c.updated).put("messages", ms))
        }
        val json = arr.toString()
        Thread({ try { chatsFile(app).writeText(json) } catch (_: Exception) {} }, "omni-ai-save").start()
    }

    fun newChat(ctx: Context, agent: String = current.value?.agent ?: "chat"): Chat {
        val c = Chat(System.currentTimeMillis().toString(36), "Новый чат", agent, System.currentTimeMillis())
        chats.add(0, c)
        select(ctx, c)
        return c
    }

    fun select(ctx: Context, c: Chat) {
        current.value = c
        prefs(ctx).edit().putString("current_chat", c.id).apply()
    }

    fun deleteChat(ctx: Context, c: Chat) {
        chats.remove(c)
        if (current.value === c) current.value = chats.firstOrNull() ?: newChat(ctx)
        save(ctx)
    }

    fun clearCurrent(ctx: Context) { current.value?.messages?.clear(); current.value?.title = "Новый чат"; status.value = ""; save(ctx) }

    // ------------------------------------------------------------------ HTTP
    private fun headers(ctx: Context, p: Provider): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        val k = key(ctx, p.id)
        if (k.isNotBlank()) h["Authorization"] = "Bearer $k" else if (p.id == "llm7") h["Authorization"] = "Bearer unused"
        if (p.id == "openrouter") { h["HTTP-Referer"] = "https://github.com/sj0404-collab/OmniBrowser-VPN-Tunnel-Pro"; h["X-Title"] = "OmniBrowser" }
        return h
    }

    private fun post(ctx: Context, url: String, headers: Map<String, String>, body: String, timeoutMs: Int): java.net.HttpURLConnection {
        val c = MediaGrabber.open(url, headers, "POST", timeoutMs)
        c.setRequestProperty("Accept", "text/event-stream, application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c
    }

    private fun errorText(code: Int, raw: String): String {
        val t = raw.trim()
        val msg = try { val o = JSONObject(t); (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).ifBlank { null } } catch (_: Exception) { null }
        val m = msg ?: t.take(160).ifBlank { "пустой ответ" }
        return when {
            m.contains("FreeUsageLimit", true) || m.contains("Free usage limit", true) -> "лимит free-моделей на этот IP исчерпан — нужен ключ аккаунта OpenCode"
            m.contains("Free limit", true) -> "лимит без ключа: 1 ответ в 30 с (ключ снимает лимит)"
            code == 401 || code == 403 -> "нет доступа (HTTP $code): нужен ключ или ключ неверный · $m"
            code == 402 -> "лимит бесплатного тарифа: $m"
            code == 429 -> "слишком часто (HTTP 429): $m"
            code == 404 && m.contains("model", true) -> "модель не найдена: $m"
            code >= 500 -> "сервер провайдера недоступен (HTTP $code): $m"
            else -> "HTTP $code: $m"
        }
    }

    private class Reply(val content: String, val reasoning: String, val toolCalls: JSONArray?)

    /**
     * Один запрос. При stream=true текст идёт в onDelta; при tools≠null запрос без потока (нужны tool_calls целиком).
     * Возвращает Reply или бросает текст ошибки через [err].
     */
    private fun complete(ctx: Context, p: Provider, model: String, msgs: JSONArray, tools: JSONArray?, stream: Boolean, maxTokens: Int, onDelta: (String) -> Unit, err: (String) -> Unit): Reply? {
        val url = urlOf(ctx, p)
        if (!url.startsWith("http")) { err("адрес сервера не задан"); return null }
        val useStream = stream && tools == null
        val body = JSONObject().apply {
            put("model", model); put("messages", msgs); put("stream", useStream)
            if (maxTokens > 0) put("max_tokens", maxTokens)
            if (tools != null && tools.length() > 0) { put("tools", tools); put("tool_choice", "auto") }
        }.toString()
        val c = try { post(ctx, url, headers(ctx, p), body, 180_000) } catch (e: Exception) { err("нет соединения: ${e.message ?: e.javaClass.simpleName}"); return null }
        try {
            val code = try { c.responseCode } catch (e: Exception) { err("нет ответа: ${e.message}"); return null }
            if (code !in 200..299) {
                val raw = try { (c.errorStream ?: c.inputStream).bufferedReader().readText() } catch (_: Exception) { "" }
                err(errorText(code, raw)); return null
            }
            val ctype = c.contentType ?: ""
            val ins = c.inputStream
            if (!useStream || !ctype.contains("event-stream")) {
                val raw = ins.bufferedReader().readText()
                if (raw.trimStart().startsWith("data:")) return parseSse(raw.lineSequence().iterator(), onDelta, err)
                val o = try { JSONObject(raw) } catch (_: Exception) { err("непонятный ответ: ${raw.take(120)}"); return null }
                val m = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                if (m == null) { err(o.optJSONObject("error")?.optString("message") ?: "пустой ответ"); return null }
                val content = m.optString("content").takeIf { !m.isNull("content") }.orEmpty()
                val reasoning = m.optString("reasoning").ifBlank { m.optString("reasoning_content") }
                val tc = m.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }
                if (content.isBlank() && tc == null && reasoning.isBlank()) { err("пустой ответ"); return null }
                if (content.isNotBlank()) onDelta(content)
                return Reply(content, reasoning, tc)
            }
            val r = BufferedReader(InputStreamReader(ins, Charsets.UTF_8))
            return parseSse(generateSequence { r.readLine() }.iterator(), onDelta, err)
        } catch (e: IOException) {
            err("обрыв: ${e.message}"); return null
        } finally { try { c.disconnect() } catch (_: Exception) {} }
    }

    private fun parseSse(lines: Iterator<String>, onDelta: (String) -> Unit, err: (String) -> Unit): Reply? {
        val sb = StringBuilder()
        val rs = StringBuilder()
        var lastErr: String? = null
        val toolCalls = LinkedHashMap<Int, JSONObject>()
        while (lines.hasNext()) {
            if (cancel.get()) break
            val line = lines.next()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val o = try { JSONObject(data) } catch (_: Exception) { continue }
            o.optJSONObject("error")?.let { lastErr = it.optString("message") }
            val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val delta = ch.optJSONObject("delta") ?: ch.optJSONObject("message") ?: continue
            val t = if (delta.isNull("content")) "" else delta.optString("content")
            if (t.isNotEmpty()) { sb.append(t); onDelta(t) }
            val rr = delta.optString("reasoning").ifBlank { delta.optString("reasoning_content") }
            if (rr.isNotEmpty() && rr != "null") rs.append(rr)
            delta.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val idx = tc.optInt("index", i)
                    val cur = toolCalls.getOrPut(idx) { JSONObject().put("id", tc.optString("id")).put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")) }
                    if (tc.has("id") && tc.optString("id").isNotBlank()) cur.put("id", tc.optString("id"))
                    tc.optJSONObject("function")?.let { f ->
                        val cf = cur.getJSONObject("function")
                        if (f.optString("name").isNotBlank()) cf.put("name", cf.optString("name") + f.optString("name"))
                        cf.put("arguments", cf.optString("arguments") + f.optString("arguments"))
                    }
                }
            }
        }
        val tc = if (toolCalls.isEmpty()) null else JSONArray().also { a -> toolCalls.values.forEach { a.put(it) } }
        if (sb.isEmpty() && tc == null && rs.isEmpty() && !cancel.get()) { err(lastErr ?: "пустой ответ"); return null }
        return Reply(sb.toString(), rs.toString(), tc)
    }

    // ------------------------------------------------------------------ диалог
    private const val SYSTEM = "Ты — встроенный помощник браузера OmniBrowser. Отвечай на русском языке (если пользователь не пишет на другом), кратко и по делу, без вступлений. " +
        "Если дан текст страницы — опирайся на него и не выдумывай. Форматируй ответ простым текстом: короткие абзацы, списки через «•», без markdown-заголовков."

    private fun buildMessages(chat: Chat, agent: AiAgent, pageContext: String?): JSONArray {
        val arr = JSONArray()
        val sys = SYSTEM + (if (agent.system.isNotBlank()) "\n\n" + agent.system else "") +
            (if (!pageContext.isNullOrBlank()) "\n\nКонтекст текущей страницы:\n$pageContext" else "")
        arr.put(JSONObject().put("role", "system").put("content", sys))
        val hist = chat.messages.filter { it.kind == "text" && (it.role == "user" || (it.role == "assistant" && it.text.isNotBlank() && !it.text.startsWith("⚠"))) }.takeLast(14)
        for (m in hist) arr.put(JSONObject().put("role", m.role).put("content", m.text.take(12000)))
        return arr
    }

    fun stop() { cancel.set(true) }

    /** Отправляет вопрос; ответ пишется в сообщение ассистента по мере прихода. Агенты с инструментами работают циклом до 8 шагов. */
    fun ask(ctx: Context, userText: String, pageContext: String?, host: AiHost?) {
        if (busy.value) return
        val app = ctx.applicationContext
        val chat = current.value ?: newChat(ctx)
        val agent = AiTools.agent(chat.agent)
        if (chat.messages.none { it.role == "user" }) chat.title = userText.trim().replace('\n', ' ').take(48)
        chat.messages.add(Msg("user", userText))
        val reply = Msg("assistant", "")
        chat.messages.add(reply)
        chat.updated = System.currentTimeMillis()
        busy.value = true
        cancel.set(false)
        status.value = ""
        val api = buildMessages(chat, agent, pageContext)
        val mcpTools = if (mcpEnabled(app) && agent.hasTools) McpClient.tools.value else emptyList()
        val tools = AiTools.toolsFor(agent, mcpTools)
        Thread({
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val sel = selected(app)
            val order = if (sel == "auto") autoOrder(app) else providers.filter { it.id == sel }
            val errors = ArrayList<String>()
            var ok = false
            for (p in order) {
                if (cancel.get()) break
                val model = model(app, p.id)
                main.post { status.value = "${p.title} · $model…" }
                val t0 = SystemClock.elapsedRealtime()
                val r = runWithProvider(app, p, model, api, tools, reply, chat, host, mcpTools, main)
                val ms = SystemClock.elapsedRealtime() - t0
                if (r == null) {
                    ok = true
                    VpnLog.i(TAG, "${p.title} · $model · ${agent.title} · ответ ${reply.text.length} зн. за $ms мс")
                    main.post { status.value = "${p.title} · $model · $ms мс" }
                    break
                }
                if (cancel.get()) break
                errors.add("${p.title}: $r")
                VpnLog.w(TAG, "${p.title} · $model: $r ($ms мс)" + (if (order.size > 1) " — пробуем следующего" else ""))
                main.post { reply.text = ""; status.value = "${p.title}: $r" }
            }
            main.post {
                if (!ok && !cancel.get()) {
                    reply.text = "⚠ Не удалось получить ответ.\n" + errors.joinToString("\n") { "• $it" } +
                        (if (order.none { it.needsKey && key(app, it.id).isNotBlank() }) "\n\nСовет: добавьте бесплатный ключ (⚙ Провайдеры) — LLM7, Groq, OpenRouter или Gemini выдают его за минуту." else "")
                } else if (cancel.get() && reply.text.isBlank()) reply.text = "⏹ остановлено"
                busy.value = false
                chat.updated = System.currentTimeMillis()
                save(app)
            }
        }, "omni-ai").start()
    }

    /** null — успех (текст уже в reply), иначе текст ошибки. */
    private fun runWithProvider(app: Context, p: Provider, model: String, api0: JSONArray, tools: JSONArray?, reply: Msg, chat: Chat, host: AiHost?, mcpTools: List<McpClient.Tool>, main: android.os.Handler): String? {
        val api = JSONArray(api0.toString())
        var useTools = tools
        var err: String? = null
        var steps = 0
        while (true) {
            if (cancel.get()) return null
            err = null
            val sb = StringBuilder()
            val r = complete(app, p, model, api, useTools, stream = true, maxTokens = 0, onDelta = { d ->
                sb.append(d); val s = sb.toString(); main.post { reply.text = s }
            }, err = { err = it })
            if (r == null) {
                val e = err ?: "ошибка"
                // модель/провайдер не умеет инструменты — повторяем без них
                if (useTools != null && (e.contains("tool", true) || e.contains("function", true) || e.contains("400"))) { useTools = null; VpnLog.w(TAG, "${p.title}: без инструментов ($e)"); continue }
                return e
            }
            val tc = r.toolCalls
            if (tc == null || useTools == null || steps >= 8) {
                var text = r.content
                if (text.isBlank() && r.reasoning.isNotBlank()) text = r.reasoning.trim().lines().last { it.isNotBlank() }
                if (text.isBlank()) return "пустой ответ"
                main.post { reply.text = text }
                return null
            }
            steps++
            // шаг агента: выполняем инструменты
            val assistantMsg = JSONObject().put("role", "assistant").put("tool_calls", tc)
            if (r.content.isNotBlank()) assistantMsg.put("content", r.content)
            api.put(assistantMsg)
            for (i in 0 until tc.length()) {
                if (cancel.get()) return null
                val call = tc.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val argsRaw = fn.optString("arguments")
                val args = try { if (argsRaw.isBlank()) JSONObject() else JSONObject(argsRaw) } catch (_: Exception) { JSONObject().put("input", argsRaw) }
                val step = Msg("tool", "🔧 $name ${argsRaw.take(160)}", kind = "tool")
                main.post { chat.messages.add(chat.messages.size - 1, step); status.value = "$name…" }
                val t0 = SystemClock.elapsedRealtime()
                val result = AiTools.execute(app, host, name, args, mcpTools)
                val ms = SystemClock.elapsedRealtime() - t0
                VpnLog.i(TAG, "инструмент $name → ${result.length} зн. за $ms мс")
                main.post { step.text = "🔧 $name ${argsRaw.take(160)}\n→ ${result.lines().firstOrNull()?.take(120) ?: ""} (${result.length} зн., $ms мс)" }
                api.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id").ifBlank { "call_$i" }).put("name", name).put("content", result.take(AiTools.TOOL_RESULT_LIMIT)))
            }
            main.post { reply.text = "" }
        }
    }

    /** Проверка провайдера: короткий запрос без потока. */
    fun test(ctx: Context, p: Provider, onDone: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun put(s: String) { main.post { testResults.value = testResults.value + (p.id to s); onDone?.invoke(s) } }
        put("⏳ проверяем…")
        Thread({
            val model = model(app, p.id)
            val t0 = SystemClock.elapsedRealtime()
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "Ответь ровно одним словом: готов"))
            var err: String? = null
            val r = complete(app, p, model, msgs, null, stream = false, maxTokens = 64, onDelta = {}, err = { err = it })
            val ms = SystemClock.elapsedRealtime() - t0
            val text = r?.let { it.content.ifBlank { it.reasoning } }?.trim()?.take(40).orEmpty()
            val res = if (r != null) "✓ $model · $ms мс · «$text»" else "✗ ${err ?: "ошибка"}"
            VpnLog.i(TAG, "проверка ${p.title}: $res")
            put(res)
        }, "omni-ai-test").start()
    }

    fun testAll(ctx: Context) { for (p in providers) if (configured(ctx, p)) test(ctx, p) }

    /** JS для сбора контекста страницы: выделение, заголовок, адрес и текст (до 9000 знаков). */
    const val PAGE_JS = "(function(){try{var s=window.getSelection?String(window.getSelection()):'';var t=document.body?document.body.innerText:'';" +
        "return JSON.stringify({sel:s.slice(0,4000),text:t.replace(/\\s+/g,' ').slice(0,9000),title:document.title,url:location.href});}catch(e){return '{}';}})()"

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
        return "Заголовок: ${p.title}\nАдрес: ${p.url}\n" + (if (p.selection.isNotBlank()) "Выделенный пользователем фрагмент: ${p.selection}\n" else "") + "Текст: ${p.text.take(7000)}"
    }
}
