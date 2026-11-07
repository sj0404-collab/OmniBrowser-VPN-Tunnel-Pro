package com.winwasm64.vpn

import java.io.File

/**
 * Определение UID приложения-владельца TCP/UDP-сокета на Android 5.0–9.0 (API 21–28),
 * где ещё нет `ConnectivityManager.getConnectionOwnerUid` (API 29+), но файлы ядра
 * `/proc/net/tcp`, `/proc/net/tcp6`, `/proc/net/udp`, `/proc/net/udp6` открыты для чтения.
 */
internal object ProcNetUidLookup {

    fun findTcpOwnerUid(localPort: Int, remotePort: Int = -1): Int {
        if (localPort !in 1..65535) return -1
        val u4 = scanProcFile("/proc/net/tcp", localPort, remotePort)
        if (u4 >= 0) return u4
        return scanProcFile("/proc/net/tcp6", localPort, remotePort)
    }

    fun findUdpOwnerUid(localPort: Int): Int {
        if (localPort !in 1..65535) return -1
        val u4 = scanProcFile("/proc/net/udp", localPort, -1)
        if (u4 >= 0) return u4
        return scanProcFile("/proc/net/udp6", localPort, -1)
    }

    private fun scanProcFile(path: String, localPort: Int, remotePort: Int): Int = try {
        val f = File(path)
        if (!f.canRead()) -1 else f.bufferedReader().useLines { parseLines(it, localPort, remotePort) }
    } catch (_: Throwable) {
        -1
    }

    internal fun parseLines(lines: Sequence<String>, localPort: Int, remotePort: Int = -1): Int {
        val localSuffix = ":%04X".format(localPort)
        val remoteSuffix = if (remotePort in 1..65535) ":%04X".format(remotePort) else null
        var fallbackUid = -1
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl")) continue
            if (!line.contains(localSuffix, ignoreCase = true)) continue
            val cols = line.split(WS_REGEX)
            // 0:sl 1:local_address 2:rem_address 3:st 4:tx:rx 5:tr:tm 6:retrnsmt 7:uid
            if (cols.size <= 7) continue
            if (!cols[1].endsWith(localSuffix, ignoreCase = true)) continue
            val uid = cols[7].toIntOrNull() ?: continue
            if (uid < 0) continue
            if (remoteSuffix == null || cols[2].endsWith(remoteSuffix, ignoreCase = true)) {
                return uid
            }
            if (fallbackUid < 0) fallbackUid = uid
        }
        return fallbackUid
    }

    private val WS_REGEX = Regex("\\s+")
}
