package com.winwasm64.ui.browser

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.7.0: каталог моделей с автообновлением. Списки берутся с сайтов провайдеров (GET /models) и с models.dev,
 * чтобы временные/демо-модели (`:free` на OpenRouter, `-free` в OpenCode Zen, tier=turbo у LLM7, tier=free у api.airforce,
 * premium=false у api.navy) появлялись и исчезали автоматически. Каждая запись помечена уровнем доступа:
 *  • anon — отвечает без ключа; • free — бесплатно с бесплатным ключом; • paid — платная (в список не попадает).
 */
object AiCatalog {
    private const val PREFS = "omni_ai_catalog"
    private const val STALE_MS = 6 * 3600_000L
    private const val MODELS_DEV_STALE_MS = 7 * 24 * 3600_000L

    class Entry(val id: String, val tier: String, val vision: Boolean = false) { val anon get() = tier == "anon" }

    /** Эвристика «понимает картинки» по имени модели (когда провайдер не сообщает модальности). */
    fun looksVision(id: String): Boolean {
        val l = id.lowercase()
        if (l.contains("embed") || l.contains("tts") || l.contains("whisper") || l.contains("audio") || l.contains("image-") || l.contains("-image")) return false
        return listOf("vision", "-vl", "vl-", "gemini", "gpt-4o", "gpt-4.1", "gpt-5", "gpt-6", "claude", "pixtral", "llama-4", "scout", "maverick", "gemma-3", "gemma-4", "gemma3", "gemma4", "qwen3-vl", "qwen2.5-vl", "qwen3.5", "qwen3.6", "qwen3.8", "mistral-small-3", "mistral-small-2", "mistral-medium", "mistral-large-4", "mistral-large-2512", "glm-4.5v", "glm-4.6v", "glm-5v", "kimi-k2.5", "kimi-k2.6", "kimi-k3", "nemotron-3.5-super-vl", "grok-4", "grok-3", "deepseek-v4", "mimo-v2", "internvl", "llava", "phi-4-multimodal", "o3", "o4").any { l.contains(it) }
    }

    val models = mutableStateOf<Map<String, List<Entry>>>(emptyMap())
    val lastRefresh = mutableStateOf(0L)
    val refreshing = mutableStateOf(false)
    val status = mutableStateOf<Map<String, String>>(emptyMap())
    private val running = AtomicBoolean(false)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastRefresh.value = p.getLong("last", 0L)
        val raw = p.getString("json", null) ?: return
        try {
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, List<Entry>>()
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                m[k] = (0 until arr.length()).map { i -> val e = arr.getJSONObject(i); Entry(e.getString("id"), e.optString("tier", "free"), e.optBoolean("vision")) }
            }
            models.value = m
        } catch (_: Exception) {}
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in models.value) o.put(k, JSONArray().also { a -> v.forEach { a.put(JSONObject().put("id", it.id).put("tier", it.tier).put("vision", it.vision)) } })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", o.toString()).putLong("last", lastRefresh.value).apply()
    }

    fun entries(providerId: String): List<Entry> = models.value[providerId] ?: emptyList()

    /** Модель со зрением для провайдера: выбранная, если она «видит», иначе первая подходящая из каталога (или по имени). */
    fun visionModel(providerId: String, chosen: String): String? {
        val list = entries(providerId)
        val e = list.firstOrNull { it.id == chosen }
        if (e?.vision == true || (e == null && looksVision(chosen))) return chosen
        return list.firstOrNull { it.vision && it.anon } ?.id ?: list.firstOrNull { it.vision }?.id ?: when (providerId) {
            "gemini" -> "gemini-flash-latest"; "groq" -> "meta-llama/llama-4-scout-17b-16e-instruct"; "navy" -> "gemini-2.5-flash"; "mistral" -> "mistral-small-latest"
            "openrouter" -> "openrouter/free"; "opencode" -> "nemotron-3.5-super-vl-free"; "airforce" -> "mistral-large-4"; "custom" -> chosen; else -> null
        }
    }

    fun refreshIfStale(ctx: Context) {
        load(ctx)
        if (System.currentTimeMillis() - lastRefresh.value > STALE_MS) refreshAll(ctx)
    }

    fun refreshAll(ctx: Context, onDone: (() -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post { refreshing.value = true }
        Thread({
            val result = LinkedHashMap<String, List<Entry>>(models.value)
            val st = LinkedHashMap<String, String>()
            var total = 0
            for (p in AiAssistant.providers) {
                if (p.id == "custom") continue
                try {
                    val list = fetchProvider(app, p)
                    if (list != null) { result[p.id] = list; total += list.size; st[p.id] = "${list.size}" }
                    else st[p.id] = "—"
                } catch (e: Exception) { st[p.id] = "✗ ${e.message?.take(60)}" }
            }
            // models.dev — запасной источник для провайдеров, которые без ключа не отдают /models
            try {
                val pr = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val need = listOf("groq" to "groq", "gemini" to "google", "mistral" to "mistral", "cerebras" to "cerebras", "huggingface" to "huggingface").filter { (pid, _) -> (result[pid]?.size ?: 0) == 0 }
                if (need.isNotEmpty() && System.currentTimeMillis() - pr.getLong("models_dev", 0L) > MODELS_DEV_STALE_MS) {
                    val c = MediaGrabber.open("https://models.dev/api.json", emptyMap(), "GET", 40_000)
                    if (c.responseCode == 200) {
                        val root = JSONObject(c.inputStream.bufferedReader().readText())
                        for ((pid, devId) in need) {
                            val ms = root.optJSONObject(devId)?.optJSONObject("models") ?: continue
                            val list = ArrayList<Entry>()
                            for (id in ms.keys()) {
                                val m = ms.getJSONObject(id)
                                val mod = m.optJSONObject("modalities")?.optJSONArray("output")?.toString() ?: "[\"text\"]"
                                if (!mod.contains("text")) continue
                                val cost = m.optJSONObject("cost")?.optDouble("input", 0.0) ?: 0.0
                                val vis = m.optJSONObject("modalities")?.optJSONArray("input")?.toString()?.contains("image") ?: looksVision(id)
                                list.add(Entry(id, if (cost == 0.0 || pid != "huggingface") "free" else "paid", vis))
                            }
                            val filtered = list.filter { it.tier != "paid" && (AiAssistant.providers.first { p -> p.id == pid }.modelFilter?.invoke(it.id) != false) }.sortedBy { it.id }
                            if (filtered.isNotEmpty()) { result[pid] = filtered; st[pid] = "${filtered.size} (models.dev)"; total += filtered.size }
                        }
                        pr.edit().putLong("models_dev", System.currentTimeMillis()).apply()
                    }
                    c.disconnect()
                }
            } catch (e: Exception) { st["models.dev"] = "✗ ${e.message?.take(60)}" }
            VpnLog.i(AiAssistant.TAG, "каталог моделей обновлён: $total моделей · " + st.entries.joinToString(", ") { "${it.key}=${it.value}" })
            main.post {
                models.value = result
                status.value = st
                lastRefresh.value = System.currentTimeMillis()
                refreshing.value = false
                save(app)
                running.set(false)
                onDone?.invoke()
            }
        }, "omni-ai-catalog").start()
    }

    /** Обновить список одного провайдера (кнопка «Список»). */
    fun refreshProvider(ctx: Context, p: AiAssistant.Provider, onDone: (List<Entry>, String?) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            try {
                val list = fetchProvider(app, p)
                main.post {
                    if (list != null) { models.value = models.value + (p.id to list); save(app); onDone(list, null) }
                    else onDone(entries(p.id), "провайдер не отдаёт список без ключа")
                }
            } catch (e: Exception) { main.post { onDone(entries(p.id), e.message ?: e.javaClass.simpleName) } }
        }, "omni-ai-models").start()
    }

    private fun getJson(ctx: Context, url: String, p: AiAssistant.Provider): Pair<Int, String> {
        val h = LinkedHashMap<String, String>()
        val k = AiAssistant.key(ctx, p.id)
        if (k.isNotBlank()) h["Authorization"] = "Bearer $k" else if (p.id == "llm7") h["Authorization"] = "Bearer unused"
        val c = MediaGrabber.open(url, h, "GET", 40_000)
        val code = c.responseCode
        val raw = try { (if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)).bufferedReader().readText() } catch (_: Exception) { "" }
        c.disconnect()
        return code to raw
    }

    private fun dataArray(raw: String): JSONArray? {
        val t = raw.trim()
        return when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> JSONObject(t).let { it.optJSONArray("data") ?: it.optJSONArray("models") }
            else -> null
        }
    }

    /** null — список недоступен (нет ключа и т.п.). */
    private fun fetchProvider(ctx: Context, p: AiAssistant.Provider): List<Entry>? {
        val hasKey = AiAssistant.key(ctx, p.id).isNotBlank()
        val url = when (p.id) {
            "pollinations" -> "https://text.pollinations.ai/models"
            else -> p.url.substringBefore("/chat/completions") + "/models"
        }
        val (code, raw) = getJson(ctx, url, p)
        if (code !in 200..299) return null
        val arr = dataArray(raw) ?: return null
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("name") }
            if (id.isBlank()) continue
            val e: Entry? = when (p.id) {
                "pollinations" -> if (o.optString("output_modalities", o.optJSONArray("output_modalities")?.toString() ?: "text").contains("text")) Entry(id, if (hasKey) "free" else "anon", o.optJSONArray("input_modalities")?.toString()?.contains("image") ?: (o.optBoolean("vision") || looksVision(id))) else null
                "opencode" -> if (p.modelFilter?.invoke(id) != false) Entry(id, "free", looksVision(id)) else null
                "openrouter" -> {
                    val pr = o.optJSONObject("pricing")
                    val free = id.endsWith(":free") || id == "openrouter/free" || (pr != null && pr.optString("prompt") in setOf("0", "0.0") && pr.optString("completion") in setOf("0", "0.0"))
                    val vis = o.optJSONObject("architecture")?.optJSONArray("input_modalities")?.toString()?.contains("image") ?: looksVision(id)
                    if (free) Entry(id, "free", vis) else null
                }
                "llm7" -> (if (o.optString("tier") == "turbo") Entry(id, "anon", o.optJSONObject("modalities")?.optJSONArray("input")?.toString()?.contains("image") ?: false) else Entry(id, "free", o.optJSONObject("modalities")?.optJSONArray("input")?.toString()?.contains("image") ?: looksVision(id)))
                    .takeIf { o.optString("model_type", "chat").let { t -> t == "chat" || t.isBlank() || t == "text" } }
                "airforce" -> if (o.optString("tier") == "free" && o.optBoolean("supports_chat", true)) Entry(id, "free", o.optBoolean("supports_images", looksVision(id))) else null
                "navy" -> if (!o.optBoolean("premium", true) && (o.optJSONArray("output_modalities")?.toString() ?: "[\"text\"]").contains("text") && !id.contains("embedding") && !id.contains("whisper") && !id.contains("tts") && !id.contains("eleven") && !id.contains("moderation")) Entry(id, "free", o.optBoolean("supports_vision", looksVision(id))) else null
                "nvidia" -> Entry(id, "free", looksVision(id))
                "huggingface" -> Entry(id, "free", looksVision(id))
                "gemini" -> if (p.modelFilter?.invoke(id.removePrefix("models/")) != false) Entry(id.removePrefix("models/"), "free", true) else null
                else -> if (p.modelFilter?.invoke(id) != false) Entry(id, "free", looksVision(id)) else null
            }
            if (e != null) out.add(e)
        }
        return out.distinctBy { it.id }.sortedWith(compareBy({ !it.anon }, { it.id }))
    }
}
