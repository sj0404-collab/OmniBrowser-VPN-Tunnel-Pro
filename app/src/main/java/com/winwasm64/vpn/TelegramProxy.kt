package com.winwasm64.vpn

import com.winwasm64.extensions.ExtensionProxy

/**
 * v4.14.0: «перебор прокси» для Telegram и других мессенджеров.
 *
 * Раньше кнопка «подключить встроенный прокси Telegram» просто открывала `tg://socks` на локальный порт —
 * независимо от того, работает ли через этот порт хоть один сервер. Если серверы Browsec в этот момент
 * лежали или не пускали DC Telegram, человек видел «Соединение…» и не понимал причину.
 *
 * Теперь сначала идёт ПРОВЕРКА по очереди: несколько серверов Browsec (и Psiphon, если он включён),
 * каждый пробуется реальным CONNECT к дата-центру Telegram. Приложение закрепляется за первым рабочим,
 * все шаги пишутся в журнал, а прокси предлагается только если проверка прошла.
 */
object TelegramProxy {

    /** DC1/DC2/DC4 Telegram (порты 443). Проверяем первые доступные. */
    private val DATACENTERS = listOf(
        "149.154.167.51" to 443,   // DC2
        "149.154.175.53" to 443,   // DC1
        "91.108.56.130" to 443,    // DC4
        "149.154.171.5" to 443     // DC5
    )

    data class Step(val server: String, val ok: Boolean, val ms: Long, val error: String? = null)

    /**
     * Пробует серверы по очереди и закрепляет [pkg] за первым, через который реально открывается DC Telegram.
     * Возвращает все шаги (для журнала и экрана) и ключ рабочего сервера (null — ни один не подошёл).
     */
    fun probeAndPin(pkg: String, maxServers: Int = 6): Pair<List<Step>, String?> {
        // v4.32.0: сначала проверяем УЖЕ закреплённый сервер.
        // Раньше перебор каждый раз выбирал заново, и рабочий прокси «сам себя менял» даже когда
        // всё работало: Telegram на каждой смене рвёт сессию и заново показывает «Соединение…».
        val pinned = try { ExtensionProxy.affinityServer(pkg) } catch (_: Exception) { null }
        if (pinned != null) {
            for ((host, port) in DATACENTERS) {
                val ms = ExtensionProxy.probeServer(pinned, host, port)
                if (ms != null) {
                    ExtensionProxy.refreshAffinity(pkg)
                    ExtensionProxy.resetAffinityMiss(pkg)
                    VpnLog.ok("Telegram", "сервер $pinned уже открывает Telegram ($host:$port, $ms мс) — оставляем его, перебор не нужен")
                    return listOf(Step(pinned, true, ms)) to pinned
                }
            }
            VpnLog.w("Telegram", "закреплённый сервер $pinned перестал открывать Telegram — ищем рабочий")
            ExtensionProxy.clearAffinity(pkg)
        }
        val keys = try { ExtensionProxy.tunnelServerKeysRanked() } catch (_: Exception) { emptyList() }
        if (keys.isEmpty()) {
            VpnLog.w("Telegram", "серверы Browsec ещё не получены — проверить нечего (включите расширение Browsec в браузере)")
            return emptyList<Step>() to null
        }
        val steps = ArrayList<Step>()
        var winner: String? = null
        for (key in keys.take(maxServers)) {
            var lastErr: String? = null
            for ((host, port) in DATACENTERS) {
                val ms = ExtensionProxy.probeServer(key, host, port)
                if (ms != null) {
                    steps += Step(key, true, ms)
                    VpnLog.ok("Telegram", "проверка: сервер $key открывает Telegram ($host:$port) за $ms мс")
                    winner = key
                    break
                }
                lastErr = "нет ответа на $host:$port"
            }
            if (winner != null) break
            steps += Step(key, false, 0, lastErr)
            VpnLog.w("Telegram", "проверка: сервер $key не пускает Telegram ($lastErr)")
        }
        if (winner != null) {
            ExtensionProxy.pinAppToServer(pkg, winner, "проверка Telegram")
            VpnLog.ok("Telegram", "приложение закреплено за сервером $winner (перебор прокси: проверено ${steps.size})")
        } else {
            VpnLog.e("Telegram", "ни один из ${steps.size} серверов не открыл Telegram — включите Psiphon (режим «ВСЕГДА») во вкладке VPN или запасной канал")
        }
        return steps to winner
    }

    fun tgUri(port: Int): String = "tg://socks?server=127.0.0.1&port=$port"

    /** Краткая сводка для экрана. */
    fun summary(steps: List<Step>, winner: String?): String = when {
        steps.isEmpty() -> "серверы Browsec ещё не получены — включите расширение Browsec в браузере"
        winner == null -> "проверено ${steps.size} серверов — ни один не открыл Telegram. Включите Psiphon (режим «ВСЕГДА»)."
        else -> "работает сервер $winner (${steps.first { it.ok }.ms} мс), проверено ${steps.size}"
    }
}
