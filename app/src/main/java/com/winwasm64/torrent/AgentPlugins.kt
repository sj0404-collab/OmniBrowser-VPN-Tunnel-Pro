package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.74.0: плагины агента.
 *
 * Пользователь просил «плагины с дипсик харнесс». Сделано честно: плагин — это обычный JSON-файл
 * в рабочей папке агента, который описывает инструмент так же, как это делают харнессы DeepSeek
 * (OpenAI-совместимая схема функции) плюс HTTP-вызов, которым этот инструмент исполняется.
 *
 * Файл плагина: Download/OmniBrowser/agent/workspace/plugins/&lt;имя&gt;.json
 * {
 *   "name": "deepseek_harness",                    // имя инструмента для модели
 *   "description": "что делает инструмент",
 *   "endpoint": "https://api.deepseek.com/chat/completions",
 *   "method": "POST",
 *   "auth": "deepseek",                            // какой ключ подставить (deepseek/openai/github/…)
 *   "params": {"prompt": "string", "model": "string"},
 *   "template": {"model": "{{model}}", "messages": [{"role":"user","content":"{{prompt}}"}]}
 * }
 *
 * При вызове: подставляем аргументы модели в "template" ({{имя}}), подставляем ключ из настроек
 * провайдера или из файла-секрета, отправляем HTTP-запрос и возвращаем текст ответа.
 * Никаких заглушек: если endpoint не отвечает — инструмент честно вернёт ошибку.
 *
 * При первом обращении создаются готовые плагины: deepseek_harness (DeepSeek как «харнесс»
 * для длинных рассуждений) и github_issue (создать issue по токену из файла-секрета).
 */
object AgentPlugins {

    private const val SECRETS_DIR = "secrets"

    data class Plugin(
        val name: String,
        val description: String,
        val endpoint: String = "",
        val method: String = "POST",
        val auth: String = "",
        val params: Map<String, String> = emptyMap(),
        val template: JSONObject? = null,
        val kind: String = "http",              // http | github_issue
        val builtin: Boolean = false
    )

    fun dir(ctx: Context): File = File(AgentWorkspace.root(ctx), "plugins").also { it.mkdirs() }

    /** Разложить готовые плагины при первом запуске (не перетирая пользовательские). */
    fun ensureDefaults(ctx: Context) {
        try {
            val d = dir(ctx)
            val deepseek = File(d, "deepseek_harness.json")
            if (!deepseek.exists()) deepseek.writeText(
                """
                {
                  "name": "deepseek_harness",
                  "description": "Спросить DeepSeek (харнесс длинных рассуждений): глубокий разбор задачи, план, ревью кода. Нужен ключ DeepSeek.",
                  "endpoint": "https://api.deepseek.com/chat/completions",
                  "method": "POST",
                  "auth": "deepseek",
                  "params": {"prompt": "string", "system": "string"},
                  "template": {
                    "model": "deepseek-chat",
                    "stream": false,
                    "messages": [
                      {"role": "system", "content": "{{system}}"},
                      {"role": "user", "content": "{{prompt}}"}
                    ]
                  }
                }
                """.trimIndent()
            )
            val issue = File(d, "github_issue.json")
            if (!issue.exists()) issue.writeText(
                """
                {
                  "name": "github_issue",
                  "description": "Создать issue в репозитории GitHub по токену из файла-секрета.",
                  "kind": "github_issue",
                  "params": {"repo": "string", "title": "string", "body": "string"}
                }
                """.trimIndent()
            )
        } catch (_: Throwable) {
        }
    }

    fun all(ctx: Context): List<Plugin> {
        ensureDefaults(ctx)
        val out = ArrayList<Plugin>()
        val files = dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                val params = HashMap<String, String>()
                o.optJSONObject("params")?.let { p ->
                    val keys = p.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        params[k] = p.optString(k, "string")
                    }
                }
                out.add(
                    Plugin(
                        name = o.optString("name", f.nameWithoutExtension),
                        description = o.optString("description", ""),
                        endpoint = o.optString("endpoint", ""),
                        method = o.optString("method", "POST"),
                        auth = o.optString("auth", ""),
                        params = params,
                        template = o.optJSONObject("template"),
                        kind = o.optString("kind", "http"),
                        builtin = f.name in setOf("deepseek_harness.json", "github_issue.json")
                    )
                )
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** Схемы для OpenAI function calling — ровно как в харнессе DeepSeek. */
    fun toolsJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (p in all(ctx)) {
            if (p.params.isEmpty()) continue
            val props = JSONObject()
            val req = JSONArray()
            for ((k, v) in p.params) {
                props.put(k, JSONObject().put("type", if (v == "integer") "integer" else "string").put("description", k))
                req.put(k)
            }
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", "plugin_" + p.name)
                        .put("description", "[плагин] " + p.description)
                        .put(
                            "parameters",
                            JSONObject().put("type", "object").put("properties", props).put("required", req)
                        )
                )
            )
        }
        return arr
    }

    fun names(ctx: Context): List<String> = all(ctx).filter { it.params.isNotEmpty() }.map { "plugin_" + it.name }

    /** Выполнить плагин. */
    fun call(ctx: Context, name: String, args: JSONObject): String {
        val p = all(ctx).firstOrNull { "plugin_" + it.name == name } ?: return "плагин не найден: $name"
        return try {
            when (p.kind) {
                "github_issue" -> {
                    val repo = args.optString("repo").trim()
                    val title = args.optString("title").trim()
                    val body = args.optString("body")
                    if (repo.isBlank() || title.isBlank()) return "нужны repo и title"
                    val token = com.winwasm64.torrent.GitHubBridge.token(ctx)
                    if (token.isBlank()) return "нет токена GitHub: положите его в Download/OmniBrowser/secrets/github.token"
                    val (code, text) = com.winwasm64.ui.browser.NetDirect.requestText(
                        "https://api.github.com/repos/$repo/issues",
                        mapOf(
                            "Authorization" to "Bearer $token",
                            "Accept" to "application/vnd.github+json",
                            "Content-Type" to "application/json"
                        ),
                        method = "POST",
                        body = JSONObject().put("title", title).put("body", body).toString(),
                        timeoutMs = 30_000
                    )
                    if (code in 200..299) "✓ issue создан: " + JSONObject(text).optString("html_url") else "GitHub ответил $code: " + text.take(300)
                }
                else -> {
                    if (p.endpoint.isBlank()) return "у плагина ${p.name} не задан endpoint"
                    var body = p.template?.toString().orEmpty()
                    for ((k, _) in p.params) body = body.replace("{{$k}}", escape(args.optString(k)))
                    if (body.isBlank()) body = args.toString()
                    val headers = HashMap<String, String>()
                    headers["Content-Type"] = "application/json"
                    val key = keyFor(ctx, p.auth)
                    if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
                    val (code, text) = com.winwasm64.ui.browser.NetDirect.requestText(
                        p.endpoint, headers, method = p.method,
                        body = if (p.method == "GET") null else body, timeoutMs = 90_000
                    )
                    if (code !in 200..299) return "плагин ${p.name}: код $code — " + text.take(300)
                    // вытаскиваем текст ответа, если это OpenAI-совместимый JSON
                    try {
                        val o = JSONObject(text)
                        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        val content = msg?.optString("content").orEmpty()
                        if (content.isNotBlank()) content.take(9000) else text.take(9000)
                    } catch (_: Throwable) {
                        text.take(9000)
                    }
                }
            }
        } catch (t: Throwable) {
            "плагин ${p.name}: ошибка — " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Ключ для плагина: сначала настройки провайдера с тем же id, потом файл-секрет. */
    private fun keyFor(ctx: Context, auth: String): String {
        if (auth.isBlank()) return ""
        val fromProvider = try {
            com.winwasm64.ui.browser.AiAssistant.key(ctx, auth)
        } catch (_: Throwable) {
            ""
        }
        if (fromProvider.isNotBlank()) return fromProvider
        val f = File(File(AgentWorkspace.root(ctx), SECRETS_DIR), "$auth.token")
        return try {
            if (f.isFile) f.readText().trim().lines().first() else ""
        } catch (_: Throwable) {
            ""
        }
    }
}
