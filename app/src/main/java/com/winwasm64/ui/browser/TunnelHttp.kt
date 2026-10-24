package com.winwasm64.ui.browser

import com.winwasm64.extensions.ExtensionProxy
import com.winwasm64.vpn.VpnLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * v4.3.1: маленький HTTP/1.1-клиент поверх туннеля Browsec через ОДИН закреплённый сервер.
 *
 * Зачем: адреса googlevideo привязаны к IP, с которого получен ответ плеера. Обычный прокси браузера (127.0.0.1:8877)
 * для каждого соединения выбирает сервер гонкой — IP «прыгает», и YouTube отвечает 403. Здесь первый запрос выбирает
 * сервер (как для приложений), а все последующие идут строго через него ([serverKey]).
 */
class TunnelHttp(@Volatile var serverKey: String? = null) {

    class Response(val code: Int, val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : java.io.Closeable {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
        override fun close() { try { body.close() } catch (_: Exception) {}; try { socket.close() } catch (_: Exception) {} }
    }

    private fun openSocket(host: String, port: Int): Socket {
        val key = serverKey
        if (key != null) {
            val r = ExtensionProxy.openPinnedTunnel(host, port, key)
            if (r != null) return r.first
            throw IOException("сервер $key не отвечает")
        }
        val t = ExtensionProxy.openAppTunnel(host, port, null) ?: throw IOException("нет доступного сервера Browsec")
        serverKey = t.third
        return t.first
    }

    private fun tls(raw: Socket, host: String, port: Int): SSLSocket {
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        val s = f.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 25_000
        s.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
            try { s.close() } catch (_: Exception) {}
            throw IOException("сертификат не для $host")
        }
        return s
    }

    /**
     * Выполняет запрос; редиректы (до 4) следуют через тот же сервер. Тело — «сырой» поток
     * (Content-Length или chunked уже учтены; читать до конца/закрыть через [Response.close]).
     */
    fun request(url: String, headers: Map<String, String>, method: String = "GET", body: ByteArray? = null, extra: Map<String, String> = emptyMap()): Response {
        var cur = url
        var redirects = 0
        while (true) {
            val u = URL(cur)
            if (u.protocol != "https" && u.protocol != "http") throw IOException("схема ${u.protocol}")
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            val raw = openSocket(u.host, port)
            var sock: Socket = raw
            try {
                try { raw.soTimeout = 25_000 } catch (_: Exception) {}
                if (u.protocol == "https") sock = tls(raw, u.host, port)
                val path = (if (u.path.isNullOrEmpty()) "/" else u.path) + (if (u.query != null) "?" + u.query else "")
                val sb = StringBuilder()
                sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                sb.append("Host: ").append(u.host).append(if (port != 443 && port != 80) ":$port" else "").append("\r\n")
                val all = LinkedHashMap<String, String>()
                for ((k, v) in headers) if (v.isNotBlank()) all[k] = v
                for ((k, v) in extra) if (v.isNotBlank()) all[k] = v
                if (all.keys.none { it.equals("User-Agent", true) }) all["User-Agent"] = MediaGrabber.DEFAULT_UA
                if (all.keys.none { it.equals("Accept", true) }) all["Accept"] = "*/*"
                all["Accept-Encoding"] = "identity"
                all["Connection"] = "close"
                if (body != null) all["Content-Length"] = body.size.toString()
                for ((k, v) in all) sb.append(k).append(": ").append(v).append("\r\n")
                sb.append("\r\n")
                val out = sock.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (body != null) out.write(body)
                out.flush()
                val inp = BufferedInputStream(sock.getInputStream(), 64 * 1024)
                val head = readHead(inp) ?: throw IOException("пустой ответ от ${u.host}")
                val lines = head.split("\r\n")
                val code = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("неверный статус: ${lines[0].take(40)}")
                val hm = LinkedHashMap<String, String>()
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    val c = l.indexOf(':')
                    if (c > 0) hm[l.substring(0, c).trim().lowercase(Locale.US)] = l.substring(c + 1).trim()
                }
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = hm["location"]
                    try { sock.close() } catch (_: Exception) {}
                    if (loc.isNullOrBlank() || redirects >= 4) throw IOException("HTTP $code без Location")
                    redirects++
                    cur = MediaGrabber.resolveUrl(cur, loc)
                    continue
                }
                val bodyIn: InputStream = when {
                    method == "HEAD" || code == 204 || code == 304 -> ByteArrayInputStream0
                    hm["transfer-encoding"]?.contains("chunked", true) == true -> ChunkedInputStream(inp)
                    hm["content-length"] != null -> LimitedInputStream(inp, hm["content-length"]!!.toLongOrNull() ?: Long.MAX_VALUE)
                    else -> inp
                }
                return Response(code, hm, bodyIn, sock)
            } catch (e: Exception) {
                try { sock.close() } catch (_: Exception) {}
                try { raw.close() } catch (_: Exception) {}
                throw e
            }
        }
    }

    fun getText(url: String, headers: Map<String, String>, maxBytes: Int = 8 * 1024 * 1024): String {
        request(url, headers).use { r ->
            if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
            return MediaGrabber.readAll(r.body, maxBytes).toString(Charsets.UTF_8)
        }
    }

    fun postJson(url: String, headers: Map<String, String>, json: String, maxBytes: Int = 8 * 1024 * 1024): String {
        val h = LinkedHashMap(headers)
        h["Content-Type"] = "application/json"
        request(url, h, "POST", json.toByteArray(Charsets.UTF_8)).use { r ->
            if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
            return MediaGrabber.readAll(r.body, maxBytes).toString(Charsets.UTF_8)
        }
    }

    companion object {
        private val ByteArrayInputStream0: InputStream get() = java.io.ByteArrayInputStream(ByteArray(0))

        private fun readHead(inp: InputStream): String? {
            val bo = ByteArrayOutputStream()
            var last4 = 0
            while (bo.size() < 128 * 1024) {
                val b = inp.read()
                if (b < 0) return if (bo.size() == 0) null else bo.toString("ISO-8859-1")
                bo.write(b)
                last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
                if (last4 == 0x0D0A0D0A) return bo.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
            }
            throw IOException("слишком длинные заголовки")
        }

        fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

        fun log(msg: String) = VpnLog.i(MediaGrabber.TAG, msg)
    }

    private class LimitedInputStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() { inner.close() }
    }

    private class ChunkedInputStream(private val inner: InputStream) : InputStream() {
        private var left = 0L
        private var done = false
        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = inner.read()
                if (b < 0) break
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("chunk line")
            }
            return sb.toString()
        }
        private fun nextChunk(): Boolean {
            if (done) return false
            var line = readLine()
            if (line.isEmpty()) line = readLine()
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("неверный chunk: $line")
            if (size == 0L) {
                done = true
                while (true) { val t = readLine(); if (t.isEmpty()) break }
                return false
            }
            left = size
            return true
        }
        override fun read(): Int {
            if (left <= 0 && !nextChunk()) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0 && !nextChunk()) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() { inner.close() }
    }
}
