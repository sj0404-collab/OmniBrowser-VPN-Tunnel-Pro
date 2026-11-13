package com.winwasm64.torrent

import android.webkit.CookieManager
import com.winwasm64.ui.browser.NetDirect
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.92.0: подписки YouTube из ВАШЕГО аккаунта.
 *
 * Браузер приложения и встроенный плеер используют один и тот же WebView-cookie-склад, поэтому
 * если вы вошли в YouTube внутри приложения, эти cookie видны здесь. Тогда:
 *  1) проверяем, есть ли вход;
 *  2) забираем настоящий список подписок (`/feed/channels`) и отдаём его «Кино»,
 *     чтобы в подписках YouTube были именно ваши каналы, а не то, что мы угадали.
 *
 * Никакого входа внутри «Кино» не требуется: логин — тот же, что в браузере.
 */
object YouTubeAccount {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    data class Channel(val name: String, val url: String, val avatar: String = "")

    /** Есть ли вход в аккаунт Google/YouTube в браузере приложения. */
    fun loggedIn(): Boolean {
        val cookies = cookies() ?: return false
        return cookies.contains("LOGIN_INFO", true) ||
            cookies.contains("SID=", true) ||
            cookies.contains("HSID=", true) ||
            cookies.contains("SSID=", true)
    }

    private fun cookies(): String? = try {
        val mgr = CookieManager.getInstance()
        val yt = mgr.getCookie("https://www.youtube.com").orEmpty()
        val google = mgr.getCookie("https://www.google.com").orEmpty()
        val all = listOf(yt, google).filter { it.isNotBlank() }.joinToString("; ")
        all.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    /**
     * Подписки аккаунта. Пустой список — либо нет входа, либо YouTube не отдал страницу.
     * [notLoggedIn] позволяет отличить одно от другого в интерфейсе.
     */
    data class Result(val channels: List<Channel>, val loggedIn: Boolean, val note: String = "")

    fun subscriptions(): Result {
        if (!loggedIn()) {
            return Result(emptyList(), false, "Войдите в YouTube в браузере приложения — и список подписок подтянется сам")
        }
        val cookie = cookies().orEmpty()
        val (code, html) = try {
            NetDirect.requestText(
                "https://www.youtube.com/feed/channels?hl=ru",
                mapOf(
                    "User-Agent" to UA,
                    "Cookie" to cookie,
                    "Accept-Language" to "ru-RU,ru;q=0.9",
                    "Referer" to "https://www.youtube.com/"
                ),
                timeoutMs = 15_000
            )
        } catch (t: Throwable) {
            return Result(emptyList(), true, "Сеть не ответила: " + (t.message ?: "ошибка").take(60))
        }
        if (code !in 200..299 || html.isBlank()) {
            return Result(emptyList(), true, "YouTube ответил $code")
        }

        val channels = ArrayList<Channel>()
        // 1) быстрый путь по разметке: ссылки каналов и их названия
        Regex(""""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { id ->
                val name = Regex(""""title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)"""")
                    .find(html)?.groupValues?.get(1)
                channels.add(Channel(clean(name ?: ""), "https://www.youtube.com/channel/$id"))
            }
        if (channels.size <= 1) {
            // 2) точный разбор: gridChannelRenderer / channelRenderer
            channels.clear()
            val root = extractInitialData(html)
            if (root != null) {
                val nodes = ArrayList<JSONObject>()
                collect(root, "gridChannelRenderer", nodes)
                collect(root, "channelRenderer", nodes)
                for (n in nodes) {
                    val id = n.optString("channelId")
                    if (!id.startsWith("UC")) continue
                    val name = titleOf(n.opt("title")).ifBlank { continue }
                    val avatar = n.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
                        .orEmpty()
                    channels.add(Channel(clean(name), "https://www.youtube.com/channel/$id", avatar))
                }
            }
        }
        val distinct = channels
            .filter { it.name.isNotBlank() }
            .distinctBy { it.url }
        return Result(
            distinct,
            true,
            if (distinct.isEmpty()) "YouTube не вернул ни одного канала (возможно, подписки пусты)" else ""
        )
    }

    // ── служебное ──
    private fun clean(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()

    private fun titleOf(obj: Any?): String = when (obj) {
        is JSONObject -> obj.optString("simpleText").ifBlank {
            val runs = obj.optJSONArray("runs")
            if (runs == null) "" else (0 until runs.length()).joinToString("") {
                runs.optJSONObject(it)?.optString("text").orEmpty()
            }
        }
        is String -> obj
        else -> ""
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == key) continue
                    collect(node.opt(k), key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun extractInitialData(html: String): JSONObject? {
        val at = listOf("ytInitialData = ", "ytInitialData=", "window[\"ytInitialData\"] = ")
            .map { html.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        val start = html.indexOf('{', at)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return try {
                            JSONObject(html.substring(start, i + 1))
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }
}
