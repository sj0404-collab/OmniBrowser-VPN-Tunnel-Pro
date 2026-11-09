package com.winwasm64.torrent

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.41.0: крошечный HTTP-сервер для потока на 127.0.0.1.
 *
 * Плеер (VideoView/MediaPlayer) умеет играть только URL, а не «файл, который ещё качается». Поэтому
 * сервер отдаёт байты по мере их появления: пока нужного куска нет, запрос ждёт (до [WAIT_MS]),
 * затем отдаёт то, что уже есть, и плеер придёт за следующим диапазоном. Range-запросы поддержаны —
 * иначе перемотка не работала бы вообще.
 */
object TorrentStreamServer {

    /** Сколько ждать появления нужных байт, прежде чем ответить тем, что есть. */
    private const val WAIT_MS = 25_000L
    /** Размер одного ответа — достаточно, чтобы плеер не дёргался на каждый блок. */
    private const val CHUNK = 512 * 1024

    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun url(): String? = if (port > 0) "http://127.0.0.1:$port/stream" else null

    /** Поднять сервер. Файл берётся у [TorrentStreamer]. */
    fun start(): String? {
        if (running.get() && port > 0) return url()
        return try {
            val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            running.set(true)
            thread = Thread({ acceptLoop(ss) }, "TorrentStreamServer").also {
                it.isDaemon = true
                it.start()
            }
            url()
        } catch (_: Throwable) {
            port = 0
            null
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        server = null
        port = 0
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handle(client) }, "StreamClient").also { it.isDaemon = true; it.start() }
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 30_000
                val input = c.getInputStream().bufferedReader()
                var rangeStart = 0L
                var head = input.readLine() ?: return
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        val r = Regex("bytes=(\\d+)-").find(line)
                        rangeStart = r?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    }
                    if (line.isEmpty()) break
                }
                val file = TorrentStreamer.outputFile()
                if (file == null || !file.exists()) {
                    writeHeaders(c.getOutputStream(), 503, 0, 0, 0, "text/plain")
                    return
                }
                // ждём, пока нужный участок реально появится
                val deadline = System.currentTimeMillis() + WAIT_MS
                while (System.currentTimeMillis() < deadline &&
                    TorrentStreamer.availableBytes <= rangeStart &&
                    TorrentStreamer.isRunning()
                ) {
                    Thread.sleep(150)
                }
                val total = TorrentStreamer.totalBytes.takeIf { it > 0 } ?: file.length()
                val available = minOf(TorrentStreamer.availableBytes, total)
                if (available <= rangeStart) {
                    writeHeaders(c.getOutputStream(), 503, 0, 0, total, "text/plain")
                    return
                }
                val length = minOf(CHUNK.toLong(), available - rangeStart)
                writeHeaders(c.getOutputStream(), 206, rangeStart, length, total, contentType(file.name))
                val out = BufferedOutputStream(c.getOutputStream())
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(rangeStart)
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val want = minOf(buf.size.toLong(), left).toInt()
                        val read = raf.read(buf, 0, want)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        left -= read
                    }
                }
                out.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun writeHeaders(
        os: OutputStream,
        code: Int,
        start: Long,
        length: Long,
        total: Long,
        type: String
    ) {
        val head = buildString {
            append(if (code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 $code Error\r\n")
            append("Content-Type: $type\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (code == 206) {
                append("Content-Range: bytes $start-${start + length - 1}/${if (total > 0) total else start + length}\r\n")
                append("Content-Length: $length\r\n")
            } else {
                append("Content-Length: 0\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        os.write(head.toByteArray(Charsets.US_ASCII))
        os.flush()
    }
}
