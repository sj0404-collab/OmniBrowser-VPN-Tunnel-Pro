package com.winwasm64.vpn

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v4.30.0: соединение через резервный канал.
 *
 * Умеет две вещи, которых не хватало для резервов:
 *  • HTTP-прокси с методом CONNECT — то есть обычный «туннель до host:port»;
 *  • SOCKS5 без пароля — самый распространённый вид публичных прокси.
 *
 * Возвращаем готовый сокет: дальше по нему идёт обычный TLS, как будто соединение прямое.
 * Проверка живости прокси ([check]) использует те же рукопожатия — если рукопожатие прошло,
 * прокси действительно работает, а не просто «принимает порт».
 */
object ReserveDialer {

    /** Открыть соединение до host:port через резерв. null — не получилось. */
    fun connect(
        up: ReserveUpstream,
        host: String,
        port: Int,
        protect: ((Socket) -> Unit)? = null,
        timeoutMs: Int = 9000
    ): Socket? {
        var sock: Socket? = null
        try {
            sock = Socket()
            protect?.invoke(sock)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.receiveBufferSize = 512 * 1024
            sock.sendBufferSize = 256 * 1024
            sock.connect(InetSocketAddress(up.host, up.port), timeoutMs)
            sock.soTimeout = timeoutMs

            val ok = when (up.kind) {
                ReserveChain.Kind.HTTP, ReserveChain.Kind.PSIPHON -> httpConnect(sock, host, port)
                ReserveChain.Kind.SOCKS5 -> socks5Connect(sock, host, port)
            }
            if (!ok) {
                closeQuietly(sock)
                return null
            }
            sock.soTimeout = 25_000
            return sock
        } catch (_: Throwable) {
            sock?.let { closeQuietly(it) }
            return null
        }
    }

    /** Проверка прокси из пула: рукопожатие до тестового адреса. Возвращает задержку в мс. */
    fun check(
        p: ReservePool.Proxy,
        testHost: String = "www.gstatic.com",
        testPort: Int = 80,
        protect: ((Socket) -> Unit)? = null,
        timeoutMs: Int = 5000
    ): Int? {
        val up = ReserveUpstream(
            kind = if (p.kind == ReservePool.Kind.SOCKS5) ReserveChain.Kind.SOCKS5 else ReserveChain.Kind.HTTP,
            host = p.host,
            port = p.port,
            label = p.label
        )
        val t0 = System.currentTimeMillis()
        val sock = connect(up, testHost, testPort, protect, timeoutMs) ?: return null
        val ms = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
        closeQuietly(sock)
        return ms
    }

    /**
     * Массовая проверка пула: параллельно, с коротким таймаутом.
     * Возвращает только ЖИВЫЕ прокси, отсортированные по задержке.
     * Живой = прошло рукопожатие до тестового адреса, а не просто «порт открыт».
     */
    fun validateAll(
        list: List<ReservePool.Proxy>,
        limit: Int = 24,
        protect: ((Socket) -> Unit)? = null,
        timeoutMs: Int = 4500
    ): List<Pair<ReservePool.Proxy, Int>> {
        if (list.isEmpty()) return emptyList()
        val exec = java.util.concurrent.Executors.newFixedThreadPool(8)
        val tasks = list.take(limit).map { p ->
            p to exec.submit(java.util.concurrent.Callable { check(p, protect = protect, timeoutMs = timeoutMs) })
        }
        val alive = ArrayList<Pair<ReservePool.Proxy, Int>>()
        for ((p, f) in tasks) {
            val ms = try {
                f.get(timeoutMs + 1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                null
            }
            if (ms != null) alive.add(p to ms)
        }
        exec.shutdownNow()
        val sorted = alive.sortedBy { it.second }
        VpnLog.i("Резерв", "проверка пула: живых ${sorted.size} из ${list.take(limit).size}")
        return sorted
    }

    /** `CONNECT host:port HTTP/1.1` — стандартный туннель HTTP-прокси. */
    private fun httpConnect(sock: Socket, host: String, port: Int): Boolean {
        val out = sock.getOutputStream()
        val req = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Connection: keep-alive\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val input = sock.getInputStream()
        val head = readHeaders(input, 4096) ?: return false
        val first = head.lineSequence().firstOrNull().orEmpty()
        return first.contains(" 200")
    }

    /** SOCKS5 без аутентификации (метод 0x00). */
    private fun socks5Connect(sock: Socket, host: String, port: Int): Boolean {
        val out = sock.getOutputStream()
        val input = sock.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greet = ByteArray(2)
        if (!readFully(input, greet, 2)) return false
        if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) return false

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03 // доменное имя
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (port and 0xFF).toByte()
        out.write(req)
        out.flush()

        val head = ByteArray(4)
        if (!readFully(input, head, 4)) return false
        if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) return false
        val addrType = head[3].toInt() and 0xFF
        val skip = when (addrType) {
            0x01 -> 4 + 2          // IPv4 + порт
            0x03 -> {               // имя: длина + имя + порт
                val len = ByteArray(1)
                if (!readFully(input, len, 1)) return false
                (len[0].toInt() and 0xFF) + 2
            }
            0x04 -> 16 + 2          // IPv6 + порт
            else -> return false
        }
        val tail = ByteArray(skip)
        return readFully(input, tail, skip)
    }

    private fun readHeaders(input: InputStream, limit: Int): String? {
        val buf = StringBuilder()
        val one = ByteArray(1)
        while (buf.length < limit) {
            val n = try {
                input.read(one)
            } catch (_: IOException) {
                -1
            }
            if (n <= 0) return if (buf.isEmpty()) null else buf.toString()
            val c = one[0].toInt() and 0xFF
            buf.append(c.toChar())
            if (buf.length >= 4 && buf.endsWith("\r\n\r\n")) return buf.toString()
        }
        return buf.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = try {
                input.read(buf, read, len - read)
            } catch (_: IOException) {
                -1
            }
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Throwable) {
        }
    }
}
