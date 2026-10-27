package com.winwasm64.ui.browser

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.7.0: минимальный MCP-клиент (Model Context Protocol, транспорт Streamable HTTP, JSON-RPC 2.0).
 * Поддерживает initialize → notifications/initialized → tools/list → tools/call; ответы JSON или SSE (`data:`-строки).
 * Проверено 27.10.2026 на публичных серверах DeepWiki (mcp.deepwiki.com/mcp) и Context7 (mcp.context7.com/mcp).
 */
object McpClient {
    private const val PREFS = "omni_ai_mcp"
    private const val PROTOCOL = "2025-03-26"

    class Server(var name: String, var url: String, var token: String = "", var enabled: Boolean = false)
    class Tool(val server: Server, val name: String, val description: String, val schema: JSONObject) {
        /** имя для OpenAI tools: латиница/цифры/_/- до 64 символов */
        val fnName: String = ("mcp_" + server.name + "_" + name).replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(64)
    }

    val servers = mutableStateOf<List<Server>>(emptyList())
    val tools = mutableStateOf<List<Tool>>(emptyList())
    val status = mutableStateOf<Map<String, String>>(emptyMap())
    private val sessions = ConcurrentHashMap<String, String>()
    private var loaded = false

    val defaults = listOf(
        Server("DeepWiki", "https://mcp.deepwiki.com/mcp", "", false),
        Server("Context7", "https://mcp.context7.com/mcp", "", false)
    )

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("servers", null)
        if (raw == null) { servers.value = defaults; return }
        try {
            val arr = JSONArray(raw)
            servers.value = (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Server(o.getString("name"), o.getString("url"), o.optString("token"), o.optBoolean("enabled")) }
        } catch (_: Exception) { servers.value = defaults }
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        servers.value.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token).put("enabled", it.enabled)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("servers", arr.toString()).apply()
    }

    fun add(ctx: Context, s: Server) { servers.value = servers.value + s; save(ctx) }
    fun remove(ctx: Context, s: Server) { servers.value = servers.value - s; tools.value = tools.value.filter { it.server !== s }; save(ctx) }
    fun setEnabled(ctx: Context, s: Server, on: Boolean) {
        s.enabled = on
        servers.value = servers.value.toList()
        save(ctx)
        if (on) refresh(ctx, s) else tools.value = tools.value.filter { it.server !== s }
    }

    /** Обновить список инструментов всех включённых серверов. */
    fun refreshAll(ctx: Context) { load(ctx); servers.value.filter { it.enabled }.forEach { refresh(ctx, it) } }

    fun refresh(ctx: Context, s: Server, onDone: ((String) -> Unit)? = null) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun st(v: String) = main.post { status.value = status.value + (s.url to v); onDone?.invoke(v) }
        st("⏳ подключение…")
        Thread({
            try {
                val list = listTools(s)
                main.post { tools.value = tools.value.filter { it.server !== s } + list }
                VpnLog.i(AiAssistant.TAG, "MCP ${s.name}: инструментов ${list.size}: " + list.joinToString(", ") { it.name }.take(200))
                st("✓ ${list.size} инстр.: " + list.joinToString(", ") { it.name }.take(120))
            } catch (e: Exception) {
                VpnLog.w(AiAssistant.TAG, "MCP ${s.name}: ${e.message}")
                st("✗ ${e.message?.take(120)}")
            }
        }, "omni-mcp").start()
    }

    private fun listTools(s: Server): List<Tool> {
        sessions.remove(s.url)
        val init = rpc(s, "initialize", JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "OmniBrowser").put("version", "4.7")), 1)
        if (init.has("error")) throw IllegalStateException(init.getJSONObject("error").optString("message"))
        try { notify(s, "notifications/initialized") } catch (_: Exception) {}
        val res = rpc(s, "tools/list", JSONObject(), 2)
        if (res.has("error")) throw IllegalStateException(res.getJSONObject("error").optString("message"))
        val arr = res.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Tool(s, t.getString("name"), t.optString("description").take(600), t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()))
        }
    }

    /** Вызов инструмента. Возвращает текст результата (или текст ошибки). */
    fun call(tool: Tool, args: JSONObject): String {
        var res = try { rpc(tool.server, "tools/call", JSONObject().put("name", tool.name).put("arguments", args), 3) } catch (e: Exception) { return "Ошибка MCP: ${e.message}" }
        if (res.has("error") && res.getJSONObject("error").optInt("code") == -32000 || res.optString("_http") == "404") {
            // сессия протухла — переинициализируем
            try { listTools(tool.server); res = rpc(tool.server, "tools/call", JSONObject().put("name", tool.name).put("arguments", args), 4) } catch (e: Exception) { return "Ошибка MCP: ${e.message}" }
        }
        if (res.has("error")) return "Ошибка MCP: " + res.getJSONObject("error").optString("message")
        val r = res.optJSONObject("result") ?: return res.toString().take(4000)
        val content = r.optJSONArray("content")
        val sb = StringBuilder()
        if (content != null) for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            when (c.optString("type")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "resource" -> sb.append(c.optJSONObject("resource")?.optString("text").orEmpty()).append('\n')
                else -> sb.append("[").append(c.optString("type")).append("]\n")
            }
        }
        if (r.optBoolean("isError")) sb.insert(0, "Инструмент вернул ошибку: ")
        return sb.toString().ifBlank { r.toString() }
    }

    private fun notify(s: Server, method: String) {
        post(s, JSONObject().put("jsonrpc", "2.0").put("method", method).toString())
    }

    private fun rpc(s: Server, method: String, params: JSONObject, id: Int): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()
        val (code, raw) = post(s, body)
        if (code == 404 || code == 400 && sessions.containsKey(s.url)) return JSONObject().put("_http", code.toString()).put("error", JSONObject().put("code", -32000).put("message", "HTTP $code (сессия сброшена)"))
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${raw.take(160)}")
        return parse(raw, id) ?: throw IllegalStateException("нет ответа JSON-RPC: ${raw.take(120)}")
    }

    private fun post(s: Server, body: String): Pair<Int, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        h["MCP-Protocol-Version"] = PROTOCOL
        sessions[s.url]?.let { h["Mcp-Session-Id"] = it }
        if (s.token.isNotBlank()) h["Authorization"] = if (s.token.contains(' ')) s.token else "Bearer ${s.token}"
        val c = MediaGrabber.open(s.url, h, "POST", 90_000)
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.getHeaderField("Mcp-Session-Id")?.let { sessions[s.url] = it }
        val raw = try { (if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream))?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
        c.disconnect()
        return code to raw
    }

    private fun parse(raw: String, id: Int): JSONObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) return JSONObject(t)
        var last: JSONObject? = null
        for (line in t.lines()) {
            if (!line.startsWith("data:")) continue
            val o = try { JSONObject(line.substring(5).trim()) } catch (_: Exception) { continue }
            if (o.optInt("id", -1) == id) return o
            if (o.has("result") || o.has("error")) last = o
        }
        return last
    }
}
