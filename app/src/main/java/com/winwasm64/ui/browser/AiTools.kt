package com.winwasm64.ui.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Мост к браузеру для инструментов агента (все вызовы — из главного потока). */
interface AiHost {
    fun currentPage(cb: (AiAssistant.PageInfo?) -> Unit)
    fun runJs(js: String, cb: (String?) -> Unit)
    fun openUrl(url: String)
    fun download(url: String)
}

/** Агент = системная инструкция + набор инструментов. */
class AiAgent(val id: String, val title: String, val emoji: String, val hint: String, val system: String, val tools: List<String>) {
    val hasTools get() = tools.isNotEmpty()
}

/**
 * v4.7.0: встроенные инструменты и агенты. Инструменты вызываются моделью через OpenAI function calling
 * (проверено на Pollinations openai, LLM7 gpt-oss:20b / minimax-m2.7 / codestral). К агентам с инструментами
 * добавляются инструменты включённых MCP-серверов.
 */
object AiTools {
    const val TOOL_RESULT_LIMIT = 9000

    val agents = listOf(
        AiAgent("chat", "Помощник", "💬", "обычный чат, видит открытую страницу",
            "", emptyList()),
        AiAgent("browser", "Браузер-агент", "🧭", "открывает сайты, читает страницы, ищет, скачивает, выполняет JS",
            "Ты — агент, управляющий браузером пользователя. Действуй пошагово: сначала используй инструменты (search_web, open_url, read_page, page_links, fetch_url, run_js, download_url), затем дай краткий итог со ссылками. Не выдумывай содержимое страниц — читай их инструментами. Если страница не загрузилась, попробуй read_page ещё раз или другой источник.",
            listOf("read_page", "page_links", "open_url", "search_web", "fetch_url", "run_js", "download_url")),
        AiAgent("research", "Исследователь", "🔎", "ищет в интернете и сверяет несколько источников",
            "Ты — исследователь. На вопрос пользователя сначала найди информацию (search_web), открой 2–4 лучших источника (fetch_url), сопоставь факты и дай ответ с краткими ссылками на источники. Отмечай, если источники противоречат друг другу.",
            listOf("search_web", "fetch_url", "read_page", "open_url")),
        AiAgent("coder", "Программист", "💻", "код, документация, разбор ошибок; умеет читать ссылки",
            "Ты — опытный программист. Отвечай точным кодом с короткими пояснениями. При необходимости читай документацию по ссылкам (fetch_url) или текущую страницу (read_page). Код оформляй в блоках ```.",
            listOf("fetch_url", "read_page", "search_web")),
        AiAgent("translator", "Переводчик", "🌐", "переводит страницу или выделение, сохраняя структуру",
            "Ты — профессиональный переводчик. Переводи на русский (или на язык, который попросит пользователь), сохраняя структуру, термины и форматирование. Без комментариев, если не просят.",
            emptyList())
    )

    fun agent(id: String): AiAgent = agents.firstOrNull { it.id == id } ?: agents[0]

    private fun fn(name: String, desc: String, vararg params: Triple<String, String, String>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for ((n, t, d) in params) { props.put(n, JSONObject().put("type", t).put("description", d)); req.put(n) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req)))
    }

    private val builtin: Map<String, JSONObject> = mapOf(
        "read_page" to fn("read_page", "Прочитать текущую открытую страницу браузера: заголовок, адрес, текст (до 9000 знаков) и выделение пользователя."),
        "page_links" to fn("page_links", "Список ссылок текущей страницы (текст — адрес), до 80 штук. Полезно, чтобы выбрать, куда перейти."),
        "open_url" to fn("open_url", "Открыть адрес в новой вкладке браузера (пользователь увидит страницу). Возвращает заголовок и начало текста после загрузки.", Triple("url", "string", "полный адрес http(s)")),
        "search_web" to fn("search_web", "Поиск в интернете (DuckDuckGo). Возвращает до 8 результатов: заголовок, адрес, фрагмент.", Triple("query", "string", "поисковый запрос")),
        "fetch_url" to fn("fetch_url", "Скачать страницу по адресу в фоне (без открытия вкладки) и вернуть её текст без HTML, до 9000 знаков.", Triple("url", "string", "полный адрес http(s)")),
        "run_js" to fn("run_js", "Выполнить JavaScript на текущей странице (клик, прокрутка, ввод текста, извлечение данных). Верни значение через return внутри выражения-функции. Результат — строка до 3000 знаков.", Triple("code", "string", "JS-код, например (function(){document.querySelector('button').click();return 'ok'})()")),
        "download_url" to fn("download_url", "Поставить файл/видео по адресу в очередь загрузок браузера.", Triple("url", "string", "адрес файла или страницы с видео"))
    )

    /** Массив tools для запроса: встроенные инструменты агента + MCP. null — у агента инструментов нет. */
    fun toolsFor(agent: AiAgent, mcp: List<McpClient.Tool>): JSONArray? {
        if (!agent.hasTools) return null
        val arr = JSONArray()
        for (t in agent.tools) builtin[t]?.let { arr.put(it) }
        for (t in mcp) arr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.fnName).put("description", "[MCP ${t.server.name}] " + t.description).put("parameters", t.schema)))
        return arr
    }

    /** Выполнить инструмент (фоновый поток). */
    fun execute(ctx: Context, host: AiHost?, name: String, args: JSONObject, mcp: List<McpClient.Tool>): String {
        mcp.firstOrNull { it.fnName == name }?.let { return McpClient.call(it, args).take(TOOL_RESULT_LIMIT) }
        return try {
            when (name) {
                "read_page" -> readPage(host, retries = 3)
                "page_links" -> onMain<String?>(host) { h, cb -> h.runJs(LINKS_JS, cb) }?.let { unquote(it) }?.take(TOOL_RESULT_LIMIT) ?: "Страница недоступна"
                "open_url" -> {
                    val url = args.optString("url").trim()
                    if (!url.startsWith("http")) return "Нужен полный адрес http(s)"
                    onMain<Unit>(host) { h, cb -> h.openUrl(url); cb(Unit) }
                    Thread.sleep(3500)
                    "Открыто: $url\n" + readPage(host, retries = 4).take(3000)
                }
                "search_web" -> searchWeb(args.optString("query"))
                "fetch_url" -> fetchUrl(args.optString("url").trim())
                "run_js" -> onMain<String?>(host) { h, cb -> h.runJs(args.optString("code"), cb) }?.let { unquote(it) }?.take(3000) ?: "null"
                "download_url" -> {
                    val url = args.optString("url").trim()
                    onMain<Unit>(host) { h, cb -> h.download(url); cb(Unit) }
                    "Загрузка поставлена в очередь: $url"
                }
                else -> "Неизвестный инструмент $name"
            }
        } catch (e: Exception) { "Ошибка инструмента $name: ${e.message}" }
    }

    private fun readPage(host: AiHost?, retries: Int): String {
        var last: AiAssistant.PageInfo? = null
        repeat(retries) { i ->
            val p = onMain<AiAssistant.PageInfo?>(host) { h, cb -> h.currentPage(cb) }
            last = p
            if (p != null && p.text.length > 200) return format(p)
            if (i < retries - 1) Thread.sleep(1500)
        }
        return last?.let { format(it) } ?: "Страница не открыта или недоступна"
    }

    private fun format(p: AiAssistant.PageInfo) = buildString {
        append("Заголовок: ").append(p.title).append("\nАдрес: ").append(p.url).append('\n')
        if (p.selection.isNotBlank()) append("Выделение: ").append(p.selection).append('\n')
        append("Текст: ").append(p.text)
    }.take(TOOL_RESULT_LIMIT)

    private fun <T> onMain(host: AiHost?, block: (AiHost, (T) -> Unit) -> Unit): T? {
        val h = host ?: return null
        val latch = CountDownLatch(1)
        var result: T? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block(h) { r -> result = r; latch.countDown() } } catch (_: Exception) { latch.countDown() }
        }
        latch.await(20, TimeUnit.SECONDS)
        return result
    }

    private fun unquote(raw: String): String = try { (org.json.JSONTokener(raw).nextValue() as? String) ?: raw } catch (_: Exception) { raw }

    private const val LINKS_JS = "(function(){try{var a=document.querySelectorAll('a[href]');var out=[];var seen={};for(var i=0;i<a.length&&out.length<80;i++){var h=a[i].href;var t=(a[i].innerText||a[i].title||'').replace(/\\s+/g,' ').trim();if(!h||seen[h]||h.indexOf('javascript:')==0||t.length<2)continue;seen[h]=1;out.push(t.slice(0,80)+' — '+h);}return out.join('\\n');}catch(e){return 'err '+e;}})()"

    // ------------------------------------------------------------------ сеть
    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun get(url: String, timeout: Int = 30_000): Pair<Int, String> {
        val c = MediaGrabber.open(url, mapOf("Accept" to "text/html,application/json;q=0.9,*/*;q=0.8", "Accept-Language" to "ru,en;q=0.8"), "GET", timeout)
        val code = c.responseCode
        val ct = c.contentType ?: ""
        val ins = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val bytes = ins.use { s -> val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(16384); var n = 0; var total = 0; while (s.read(b).also { n = it } > 0) { buf.write(b, 0, n); total += n; if (total > 1_500_000) break }; buf.toByteArray() }
        c.disconnect()
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.let { try { charset(it) } catch (_: Exception) { null } } ?: Charsets.UTF_8
        return code to (if (ct.contains("html") || ct.contains("xml")) stripHtml(String(bytes, cs)) else String(bytes, cs))
    }

    fun fetchUrl(url: String): String {
        if (!url.startsWith("http")) return "Нужен полный адрес http(s)"
        val (code, text) = get(url)
        if (code !in 200..299) return "HTTP $code при загрузке $url\n" + text.take(500)
        return ("Адрес: $url\n" + text).take(TOOL_RESULT_LIMIT)
    }

    fun searchWeb(query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val results = ArrayList<String>()
        fun add(title: String, href: String, snip: String) {
            if (href.startsWith("http") && title.isNotBlank() && results.none { it.contains(href) }) results.add("${results.size + 1}. $title\n   $href" + (if (snip.isNotBlank()) "\n   $snip" else ""))
        }
        // 1) Brave Search (проверено: отдаёт HTML без капчи)
        try {
            val (code, h) = getRaw("https://search.brave.com/search?q=$q&source=web")
            if (code in 200..299) for (blk in h.split("data-type=\"web\"").drop(1)) {
                val b = blk.take(6000)
                val href = Regex("<a href=\"(http[^\"]+)\"").find(b)?.groupValues?.get(1) ?: continue
                val title = Regex("(?s)search-snippet-title[^>]*>(.*?)</div>").find(b)?.groupValues?.get(1)?.let { stripHtml(it) } ?: continue
                val snip = Regex("(?s)generic-snippet[^>]*>(.*?)</div>").find(b)?.groupValues?.get(1)?.let { stripHtml(it).take(240) } ?: ""
                add(title, href, snip)
                if (results.size >= 8) break
            }
        } catch (_: Exception) {}
        // 2) Bing
        if (results.isEmpty()) try {
            val (code, h) = getRaw("https://www.bing.com/search?q=$q&setlang=ru")
            if (code in 200..299) for (blk in h.split("class=\"b_algo\"").drop(1)) {
                val b = blk.take(6000)
                val m = Regex("(?s)<h2[^>]*><a[^>]*href=\"(http[^\"]+)\"[^>]*>(.*?)</a>").find(b) ?: continue
                val snip = Regex("(?s)<p[^>]*>(.*?)</p>").find(b)?.groupValues?.get(1)?.let { stripHtml(it).take(240) } ?: ""
                add(stripHtml(m.groupValues[2]), m.groupValues[1], snip)
                if (results.size >= 8) break
            }
        } catch (_: Exception) {}
        // 3) DuckDuckGo html
        if (results.isEmpty()) try {
            val (code, h) = getRaw("https://html.duckduckgo.com/html/?q=$q&kl=ru-ru")
            if (code in 200..299) for (blk in h.split("class=\"result__a\"").drop(1)) {
                val b = blk.take(4000)
                var href = Regex("href=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: continue
                Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { href = java.net.URLDecoder.decode(it, "UTF-8") }
                val title = Regex("(?s)>(.*?)</a>").find(b)?.groupValues?.get(1)?.let { stripHtml(it) } ?: continue
                val snip = Regex("(?s)result__snippet[^>]*>(.*?)</a>").find(b)?.groupValues?.get(1)?.let { stripHtml(it).take(240) } ?: ""
                add(title, href, snip)
                if (results.size >= 8) break
            }
        } catch (_: Exception) {}
        if (results.isNotEmpty()) return "Результаты поиска «$query»:\n" + results.joinToString("\n")
        return try {
            val (code, text) = get("https://lite.duckduckgo.com/lite/?q=$q&kl=ru-ru")
            if (code in 200..299 && text.length > 300) "Результаты поиска «$query» (текст):\n" + text.take(6000) else "Поиск недоступен (HTTP $code) — попробуй open_url с адресом поисковика и read_page"
        } catch (e: Exception) { "Поиск недоступен: ${e.message}" }
    }

    private fun getRaw(url: String): Pair<Int, String> {
        val c = MediaGrabber.open(url, mapOf("Accept" to "text/html", "Accept-Language" to "ru,en;q=0.8"), "GET", 30_000)
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)).bufferedReader().readText()
        c.disconnect()
        return code to raw
    }
}
