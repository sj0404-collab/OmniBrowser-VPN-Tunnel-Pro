package com.winwasm64.ui.browser

import com.winwasm64.vpn.NetworkBypass
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * v4.67.0: «Проверь opencode провайдеры — Не удалось получить ответ.
 * • OpenCode Zen: нет соединения: socket failed: ENONET (Machine is not on the network)».
 *
 * Что происходило: все запросы ИИ шли через локальный прокси браузера
 * (`MediaGrabber.proxy()` → 127.0.0.1:<порт репитера>). Плюс режим «обход чужого VPN» привязывает
 * ВЕСЬ процесс к конкретной физической сети (`bindProcessToNetwork`). Когда эта сеть пропадает
 * (переключение Wi-Fi ↔ мобильная, включение/выключение VPN, смена вышки), ядро рвёт маршрут и
 * любой новый сокет падает с `ENONET` — «машина не в сети», хотя интернет есть. Плюс локальный
 * прокси мог быть не поднят — и тогда ИИ оставался без связи вообще.
 *
 * Теперь у ИИ свой транспорт с перебором маршрутов:
 *   1) напрямую (`Proxy.NO_PROXY`) — так и должно быть, провайдерам прокси не нужен;
 *   2) если сеть «потерялась» — снимаем привязку процесса (NetworkBypass.reset) и пробуем снова;
 *   3) через привязку к актуальной физической сети (`Network.openConnection`);
 *   4) и только в самом конце — через локальный прокси браузера.
 * Ошибка при этом человеческая: «сеть переключалась — повторите», а не «ENONET».
 */
object NetDirect {

    /** Признаки «сеть потерялась», после которых помогает сброс привязки. */
    private val NET_LOST = listOf(
        "enonet", "enetunreach", "ehostunreach", "enetdown", "eperm",
        "socket failed", "failed to connect", "network is unreachable", "no route to host"
    )

    fun looksLikeNetworkLoss(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return NET_LOST.any { m.contains(it) }
    }

    /** Человеческое объяснение вместо машинного кода. */
    fun humanError(message: String?): String {
        val m = message ?: return "нет соединения"
        return when {
            m.contains("ENONET", true) || m.contains("ENETUNREACH", true) || m.contains("ENETDOWN", true) ->
                "сеть переключалась (Wi-Fi/мобильная/VPN) — система потеряла маршрут, повторите запрос"
            m.contains("EHOSTUNREACH", true) || m.contains("no route to host", true) ->
                "хост недоступен по текущему маршруту — проверьте интернет"
            m.contains("EPERM", true) -> "система запретила сокет (EPERM) — вероятно, чужой VPN; повторите"
            m.contains("Unable to resolve host", true) || m.contains("UnknownHost", true) ->
                "не удалось разобрать имя (DNS) — проверьте интернет"
            m.contains("Connection refused", true) -> "сервер отклонил соединение"
            m.contains("timeout", true) || m.contains("timed out", true) -> "сервер не ответил вовремя"
            else -> m
        }
    }

    /**
     * Открыть соединение, перебирая маршруты. Возвращает уже подключённый `HttpURLConnection`
     * (код ответа доступен через `responseCode`). Бросает IOException с человеческим текстом.
     * [allowProxy] = false — локальный прокси браузера не использовать даже как последний вариант.
     */
    fun connect(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 25_000,
        allowProxy: Boolean = true
    ): HttpURLConnection {
        var lastError: String? = null
        val routes = mutableListOf<Route>()
        routes.add(Route.Direct)
        routes.add(Route.DirectAfterReset)
        if (NetworkBypass.physicalNetwork() != null) routes.add(Route.PhysicalNetwork)
        if (allowProxy) routes.add(Route.BrowserProxy)

        for (route in routes) {
            val conn = try {
                build(route, url, headers, method, body, timeoutMs)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
            if (conn == null) {
                if (route == Route.Direct) NetworkBypass.reset()
                continue
            }
            try {
                conn.responseCode // фактическое соединение происходит здесь
                return conn
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                try { conn.disconnect() } catch (_: Throwable) {}
                if (route == Route.Direct || route == Route.DirectAfterReset) {
                    // самая частая причина — «мёртвая» привязка процесса к пропавшей сети
                    NetworkBypass.reset()
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                }
            }
        }
        throw IOException(humanError(lastError))
    }

    /** Один запрос с чтением ответа целиком. Ошибки сети — уже человеческим текстом. */
    fun requestText(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 25_000,
        allowProxy: Boolean = true
    ): Pair<Int, String> {
        val c = connect(url, headers, method, body, timeoutMs, allowProxy)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            try { c.disconnect() } catch (_: Throwable) {}
        }
    }

    private enum class Route { Direct, DirectAfterReset, PhysicalNetwork, BrowserProxy }

    private fun build(
        route: Route,
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String?,
        timeoutMs: Int
    ): HttpURLConnection {
        val u = URL(url)
        val conn: HttpURLConnection = when (route) {
            Route.Direct, Route.DirectAfterReset -> u.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            Route.PhysicalNetwork ->
                NetworkBypass.physicalNetwork()?.openConnection(u) as? HttpURLConnection
                    ?: throw IOException("нет физической сети")
            Route.BrowserProxy -> {
                val port = BrowserDirectRepeater.ensureServerRunning()
                u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            }
        }
        conn.connectTimeout = 12_000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = method
        var hasUa = false
        for ((k, v) in headers) {
            if (v.isBlank()) continue
            if (k.equals("User-Agent", true)) hasUa = true
            conn.setRequestProperty(k, v)
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", MediaGrabber.DEFAULT_UA)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }
}
