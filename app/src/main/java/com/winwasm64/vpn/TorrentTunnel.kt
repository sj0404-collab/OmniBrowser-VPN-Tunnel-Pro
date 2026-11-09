package com.winwasm64.vpn

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * v4.31.0: выход для торрента через уже работающий канал браузера.
 *
 * Из журнала с устройства видно, что трекеры и пиры ходили напрямую с домашнего IP
 * (`/192.168.0.129`), а такой прямой TCP в мобильной сети закрыт: трекеры не отвечали,
 * из 9 найденных пиров не пришёл ни один кусок. При этом канал Browsec в этот момент работал.
 *
 * Решение без новой архитектуры: у браузера уже есть локальный HTTP-прокси
 * (`BrowserDirectRepeater`, 127.0.0.1:8877), который умеет `CONNECT` и ходит через Browsec.
 * Даём торренту тот же вход. Не получилось — честно возвращаем null, и код идёт напрямую,
 * как раньше: торрент не должен ломаться из-за того, что VPN выключен.
 */
object TorrentTunnel {

    /** Локальный прокси браузера, если он поднят. */
    fun proxyOrNull(): Proxy? {
        val port = try {
            BrowserDirectRepeater.ensureServerRunning()
        } catch (_: Throwable) {
            0
        }
        if (port <= 0) return null
        return try {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        } catch (_: Throwable) {
            null
        }
    }

    fun isAvailable(): Boolean = proxyOrNull() != null

    /**
     * TCP-соединение до пира через канал браузера.
     * null — канал недоступен или не пустил; вызывающий идёт напрямую.
     */
    fun connect(host: String, port: Int, timeoutMs: Int = 6000): Socket? {
        val proxy = proxyOrNull() ?: return null
        return try {
            val s = Socket(proxy)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 8000
            s
        } catch (_: Throwable) {
            null
        }
    }
}
