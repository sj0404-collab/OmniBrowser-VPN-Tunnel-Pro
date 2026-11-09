package com.winwasm64.vpn

import java.net.InetSocketAddress
import java.net.Socket

/**
 * v4.36.0: диагностика каналов одной кнопкой.
 *
 * Повод простой: когда «нет сети» или «ошибка прокси», по экрану невозможно понять, что именно
 * отказало — DNS, серверы Browsec, Psiphon, пул резервов или сам выход в интернет. Здесь всё
 * проверяется по очереди короткими реальными соединениями и показывается таблицей:
 * канал → жив/нет → чем именно ответил → что делать.
 *
 * Ничего не «рисуется»: если проверка не прошла, так и написано, с причиной.
 */
object ChannelCheck {

    enum class State(val label: String) { OK("жив"), WARN("частично"), FAIL("нет") }

    data class Row(
        val name: String,
        val state: State,
        val detail: String,
        val hint: String = "",
        val ms: Long = -1L
    )

    /** Тестовый адресат: один и тот же для всех каналов, чтобы сравнивать честно. */
    private const val PROBE_HOST = "example.com"
    private const val PROBE_PORT = 443

    /** Проверить всё. Блокирующий вызов — запускать из фонового потока (занимает до ~30 с). */
    fun runAll(): List<Row> {
        val rows = ArrayList<Row>()
        runCatching { rows.add(checkDeviceNetwork()) }
        runCatching { rows.add(checkDnsFast()) }
        runCatching { rows.add(checkDnsExtended()) }
        runCatching { rows.add(checkBrowsec()) }
        runCatching { rows.add(checkPsiphon()) }
        runCatching { rows.add(checkReserve()) }
        runCatching { rows.add(checkEgress()) }
        for (r in rows) {
            VpnLog.i("Диагностика", "${r.name}: ${r.state.label} — ${r.detail}" + if (r.hint.isNotBlank()) " · ${r.hint}" else "")
        }
        return rows
    }

    /* ===================== отдельные проверки ===================== */

    private fun checkDeviceNetwork(): Row {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 443), 2500)
            }
            Row("Сеть устройства", State.OK, "TCP до 1.1.1.1:443 открывается", ms = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Row(
                "Сеть устройства", State.FAIL,
                "нет TCP наружу: ${e.javaClass.simpleName}",
                "проверьте Wi-Fi/мобильные данные; если включён чужой VPN — попробуйте выключить его на минуту",
                System.currentTimeMillis() - t0
            )
        }
    }

    private fun checkDnsFast(): Row {
        val t0 = System.currentTimeMillis()
        val ip = try {
            FastMultiDnsResolver.resolveFast("www.google.com")
        } catch (_: Throwable) {
            ""
        }
        val ms = System.currentTimeMillis() - t0
        return if (ip.isNotBlank()) {
            Row("DNS (быстрый путь)", State.OK, "www.google.com → $ip", ms = ms)
        } else {
            Row(
                "DNS (быстрый путь)", State.FAIL, "оба адреса DoH молчат",
                "расширенный путь ниже пробует ещё 8 провайдеров — смотрите строку ниже", ms
            )
        }
    }

    private fun checkDnsExtended(): Row {
        val t0 = System.currentTimeMillis()
        val ip = try {
            DohResolver.resolve("www.google.com", timeoutMs = 2200)
        } catch (_: Throwable) {
            null
        }
        val ms = System.currentTimeMillis() - t0
        return if (ip != null) {
            Row(
                "DNS (расширенный)", State.OK,
                "www.google.com → $ip через ${DohResolver.lastGood.ifBlank { "?" }}", ms = ms
            )
        } else {
            Row(
                "DNS (расширенный)", State.FAIL, "8 DoH + обычный DNS не ответили",
                "похоже, сеть режет DNS целиком — нужен сторонний VPN или смена сети", ms
            )
        }
    }

    private fun checkBrowsec(): Row {
        val port = try {
            BrowserDirectRepeater.ensureServerRunning()
        } catch (_: Throwable) {
            0
        }
        if (port <= 0) {
            return Row("Browsec (браузер)", State.FAIL, "локальный прокси не поднят", "включите расширение Browsec в браузере")
        }
        val t0 = System.currentTimeMillis()
        // v4.37.0: здесь был java.net.Socket(Proxy) с неразрешённым адресом — он падал с
        // IllegalArgumentException ещё до соединения, и живой канал выглядел мёртвым.
        // Проверяем тем же диалером, которым реально ходит торрент: он делает настоящий CONNECT.
        val localProxy = ReserveUpstream(ReserveChain.Kind.HTTP, "127.0.0.1", port, "Browsec")
        val probe = try {
            ReserveDialer.connect(localProxy, PROBE_HOST, PROBE_PORT, timeoutMs = 7000)
        } catch (_: Throwable) {
            null
        }
        return if (probe != null) {
            runCatching { probe.close() }
            val ms = System.currentTimeMillis() - t0
            val kept = BrowserDirectRepeater.enabled
            Row(
                "Browsec (браузер)", if (kept) State.OK else State.WARN,
                "CONNECT до $PROBE_HOST открылся за $ms мс · порт $port" + if (kept) "" else " · расширение выключено",
                if (kept) "" else "включите Browsec в браузере, иначе трафик идёт обходом DPI", ms
            )
        } else {
            Row(
                "Browsec (браузер)", State.FAIL,
                "CONNECT через 127.0.0.1:$port не открылся",
                "смените сервер в браузере или включите Psiphon (режим «всегда»)",
                System.currentTimeMillis() - t0
            )
        }
    }

    private fun checkPsiphon(): Row {
        val mode = PsiphonBackup.modeTitle()
        if (!PsiphonBackup.ready()) {
            val extra = if (PsiphonBackup.starting) {
                " · подключается, попыток всего ${PsiphonBackup.connectAttempts}, за минуту ${PsiphonBackup.attemptsLastMinute}"
            } else ""
            return Row(
                "Psiphon", if (PsiphonBackup.starting) State.WARN else State.FAIL,
                "не подключён (режим: $mode)$extra",
                "нажмите Psiphon → «всегда»: это независимый от Browsec канал"
            )
        }
        val port = PsiphonBackup.httpPort
        val t0 = System.currentTimeMillis()
        val up = ReserveUpstream(ReserveChain.Kind.PSIPHON, "127.0.0.1", port, "Psiphon")
        val sock = try {
            ReserveDialer.connect(up, PROBE_HOST, PROBE_PORT, timeoutMs = 8000)
        } catch (_: Throwable) {
            null
        }
        val ms = System.currentTimeMillis() - t0
        if (sock != null) {
            runCatching { sock.close() }
            return Row("Psiphon", State.OK, "CONNECT через 127.0.0.1:$port открылся за $ms мс" + regionSuffix(), ms = ms)
        }
        return Row(
            "Psiphon", State.FAIL, "прокси 127.0.0.1:$port не открыл CONNECT" + regionSuffix(),
            "перезапустите Psiphon; если не помогает — сеть режет и его", ms
        )
    }

    private fun regionSuffix(): String =
        if (PsiphonBackup.connectedRegion.isNotBlank()) " · регион ${PsiphonBackup.connectedRegion}" else ""

    private fun checkReserve(): Row {
        val pool = try {
            ReserveChain.poolList()
        } catch (_: Throwable) {
            emptyList()
        }
        val chainLine = ReserveChain.statusLine()
        if (pool.isEmpty()) {
            return Row(
                "Резервные прокси", State.FAIL, "$chainLine · пул пуст",
                "Настройки → «Обновить пул прокси»"
            )
        }
        val alive = try {
            ReserveDialer.validateAll(pool, limit = 4, timeoutMs = 4000)
        } catch (_: Throwable) {
            emptyList()
        }
        return if (alive.isNotEmpty()) {
            Row(
                "Резервные прокси", State.OK,
                "живых ${alive.size} из ${minOf(4, pool.size)} проверенных · лучший ${alive.first().first.label} ${alive.first().second} мс",
                ms = alive.first().second.toLong()
            )
        } else {
            Row(
                "Резервные прокси", State.WARN, "$chainLine · из ${minOf(4, pool.size)} проверенных не ответил ни один",
                "обновите пул позже: публичные прокси часто умирают пачками"
            )
        }
    }

    private fun checkEgress(): Row {
        val t0 = System.currentTimeMillis()
        val info = try {
            com.winwasm64.ui.browser.EgressCheck.viaBrowserProxy()
        } catch (e: Exception) {
            return Row(
                "Выход в интернет", State.FAIL, "проверка не удалась: ${e.javaClass.simpleName}",
                "канал не отвечает", System.currentTimeMillis() - t0
            )
        }
        val ms = System.currentTimeMillis() - t0
        return if (info.ok) {
            Row("Выход в интернет", State.OK, "IP ${info.ip}" + (if (info.where.isNotBlank()) " · ${info.where}" else ""), ms = ms)
        } else {
            Row(
                "Выход в интернет", State.FAIL,
                info.error ?: "нет ответа от сервиса определения IP",
                "если DNS и Browsec живы, а это нет — сервис проверки недоступен, попробуйте позже", ms
            )
        }
    }

    /* ===================== показ ===================== */

    /** Текст для панели: строка на канал + подсказка под неудачными. */
    fun summary(rows: List<Row>): String {
        if (rows.isEmpty()) return "Проверка ещё не запускалась"
        val sb = StringBuilder()
        for (r in rows) {
            val mark = when (r.state) {
                State.OK -> "✅"
                State.WARN -> "⚠️"
                State.FAIL -> "❌"
            }
            sb.append(mark).append(' ').append(r.name).append(" — ").append(r.state.label)
            if (r.ms > 0) sb.append(" · ").append(r.ms).append(" мс")
            sb.append('\n').append("   ").append(r.detail)
            if (r.hint.isNotBlank() && r.state != State.OK) sb.append('\n').append("   → ").append(r.hint)
            sb.append('\n')
        }
        val bad = rows.count { it.state != State.OK }
        sb.append(
            if (bad == 0) "Итог: все каналы отвечают."
            else "Итог: требуют внимания $bad из ${rows.size} — смотрите строки с ❌ и ⚠️."
        )
        return sb.toString()
    }
}
