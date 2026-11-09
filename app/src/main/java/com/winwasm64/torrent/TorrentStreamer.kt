package com.winwasm64.torrent

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.41.0: потоковое воспроизведение прямо из раздачи.
 *
 * Задача: не скачивать 10 песен или часовое видео целиком, чтобы посмотреть/послушать. Поэтому куски
 * качаются СТРОГО ПО ПОРЯДКУ (a не «какой первым пришёл»), и как только в начале файла накопилось
 * достаточно байт — локальный сервер отдаёт их плееру. Дальше воспроизведение и загрузка идут вместе.
 *
 * Ограничения честно: скорость равна скорости пиров; если у раздачи 0 сидов — потока не будет
 * (об этом скажет текст на экране плеера, а не бесконечное «загрузка…»).
 */
object TorrentStreamer {

    /** Сколько байт с начала файла уже непрерывно доступно. */
    @Volatile var availableBytes: Long = 0L
        private set
    @Volatile var totalBytes: Long = 0L
        private set
    @Volatile var statusText: String = "Подготовка…"
        private set
    @Volatile var speedBytesPerSec: Long = 0L
        private set
    @Volatile var fileName: String = ""
        private set
    /** Список файлов раздачи — чтобы человек выбрал, что смотреть (как в любом торрент-клиенте). */
    @Volatile var files: List<TorrentFileEntry> = emptyList()
        private set
    @Volatile var selectedFile: TorrentFileEntry? = null
        private set
    @Volatile var playing: Boolean = false
        private set

    private var file: File? = null
    /** Границы выбранного файла внутри раздачи (для многофайловых раздач). */
    @Volatile private var rangeStart: Long = 0L
    @Volatile private var rangeEnd: Long = 0L
    private val stopFlag = AtomicBoolean(false)
    private var worker: Thread? = null

    fun isRunning(): Boolean = playing

    fun outputFile(): File? = file

    /**
     * Начать поток. Возвращает true, если метаданные и пиры получены.
     * Блокирующий вызов (сеть) — звать из фонового потока.
     */
    fun start(
        engine: BitTorrentEngine,
        magnetUri: String?,
        torrentUrl: String?,
        referer: String?,
        dir: File,
        preferredFile: String? = null
    ): Boolean {
        stop()
        stopFlag.set(false)
        availableBytes = 0L
        speedBytesPerSec = 0L
        statusText = "Получение метаданных…"
        val source = engine.prepareStream(magnetUri, torrentUrl, referer)
        if (source == null) {
            statusText = "Не удалось получить метаданные раздачи — поток недоступен"
            return false
        }
        val meta = source.metadata
        files = meta.files
        // v4.42.0: если файлов несколько — берём выбранный, иначе самый крупный.
        // Так раздачи с папками (сезон сериала, дискография) не превращаются в «первый попавшийся» файл.
        val biggest = when {
            preferredFile != null -> meta.files.firstOrNull { it.relativePath == preferredFile }
                ?: meta.files.maxByOrNull { it.length }
            else -> meta.files.maxByOrNull { it.length }
        }
        selectedFile = biggest
        fileName = biggest?.relativePath?.substringAfterLast('/') ?: meta.name
        totalBytes = biggest?.length ?: meta.totalSize
        rangeStart = biggest?.offsetInTorrent ?: 0L
        rangeEnd = rangeStart + totalBytes
        val target = File(dir, fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        file = target
        if (!target.exists()) target.createNewFile()
        if (source.peers.isEmpty()) {
            statusText = "В раздаче 0 пиров — смотреть нечего (нужен сид)"
            return false
        }
        playing = true
        statusText = "Подключение к пирам…"
        worker = Thread({ pump(source, target, biggest) }, "TorrentStreamer").also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        stopFlag.set(true)
        playing = false
        worker?.interrupt()
        worker = null
    }

    /** Последовательная загрузка: куски по порядку, запись по смещениям, проверка хеша. */
    private fun pump(source: BitTorrentEngine.StreamSource, target: File, single: TorrentFileEntry?) {
        val meta = source.metadata
        val peers = source.peers
        var peerIndex = 0
        val pieceLen = meta.pieceLength
        val pieceCount = meta.pieceCount
        var downloaded = 0L
        val startedAt = System.currentTimeMillis()

        for (piece in 0 until pieceCount) {
            if (stopFlag.get()) break
            val pieceStart = piece.toLong() * pieceLen
            // куски вне выбранного файла пропускаем: не тратим трафик на сезон целиком
            if (pieceStart + pieceLen <= rangeStart || pieceStart >= rangeEnd) continue
            var ok = false
            var attempts = 0
            while (!ok && attempts < 3 && !stopFlag.get()) {
                val peer = peers[(peerIndex++) % peers.size]
                attempts++
                statusText = "Загрузка по порядку: кусок ${piece + 1} из $pieceCount"
                ok = try {
                    fetchPiece(peer, meta, piece, target)
                } catch (_: Throwable) {
                    false
                }
            }
            if (!ok) {
                statusText = "Пиры не отдали кусок ${piece + 1} — поток остановлен"
                break
            }
            downloaded += pieceLen.toLong()
            availableBytes = minOf(downloaded, totalBytes)
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            speedBytesPerSec = downloaded * 1000 / elapsed
        }
        statusText = if (availableBytes >= totalBytes && totalBytes > 0) "Загружено полностью" else "Поток: ${statusText}"
    }

    /** Скачать один кусок целиком, запросив все его блоки (16 КБ) конвейером. */
    private fun fetchPiece(peer: PeerEndpoint, meta: TorrentMetadata, piece: Int, target: File): Boolean {
        val offset = piece.toLong() * meta.pieceLength
        val len = if (piece == meta.pieceCount - 1) {
            (meta.totalSize - offset).toInt().coerceAtLeast(1)
        } else {
            meta.pieceLength
        }
        val socket = openPeer(peer, meta) ?: return false
        socket.use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            s.soTimeout = 8000

            // interested
            out.writeInt(1); out.writeByte(2); out.flush()

            val data = ByteArray(len)
            var received = 0
            val blocks = (len + 16383) / 16384
            var requested = 0
            var turns = 0
            val maxTurns = blocks * 6 + 40

            while (received < len && turns < maxTurns) {
                turns++
                // запрашиваем до 4 блоков вперёд
                while (requested < blocks && requested - (received / 16384) < 4) {
                    val begin = requested * 16384
                    val blockLen = minOf(16384, len - begin)
                    out.writeInt(13); out.writeByte(6)
                    out.writeInt(piece); out.writeInt(begin); out.writeInt(blockLen)
                    requested++
                }
                out.flush()

                val msgLen = try { inp.readInt() } catch (_: Throwable) { return false }
                if (msgLen <= 0 || msgLen > 2 * 1024 * 1024) continue
                val id = inp.readUnsignedByte()
                val payload = ByteArray(msgLen - 1)
                if (payload.isNotEmpty()) inp.readFully(payload)
                when (id) {
                    1 -> { /* unchoke */ }
                    7 -> {
                        if (payload.size > 8) {
                            val idx = ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
                                ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                            val begin = ((payload[4].toInt() and 0xFF) shl 24) or ((payload[5].toInt() and 0xFF) shl 16) or
                                ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
                            if (idx == piece && begin >= 0 && begin < len) {
                                val chunk = payload.copyOfRange(8, payload.size)
                                val copyLen = minOf(chunk.size, len - begin)
                                System.arraycopy(chunk, 0, data, begin, copyLen)
                                received += copyLen
                            }
                        }
                    }
                }
            }
            if (received < len) return false
            val digest = BitTorrentParser.sha1(data)
            if (piece < meta.pieceHashes.size && !digest.contentEquals(meta.pieceHashes[piece])) return false
            // пишем только ту часть куска, которая попадает в выбранный файл
            val fileStart = maxOf(offset, rangeStart)
            val fileEnd = minOf(offset + data.size, rangeEnd)
            if (fileEnd > fileStart) {
                RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(fileStart - rangeStart)
                    raf.write(data, (fileStart - offset).toInt(), (fileEnd - fileStart).toInt())
                }
            }
            return true
        }
    }

    private fun openPeer(peer: PeerEndpoint, meta: TorrentMetadata): Socket? {
        val sock = com.winwasm64.vpn.TorrentTunnel.connect(peer.ip, peer.port, timeoutMs = 5000) ?: try {
            Socket().also { s ->
                s.connect(InetSocketAddress(peer.ip, peer.port), 5000)
                s.soTimeout = 8000
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            val out = DataOutputStream(sock.getOutputStream())
            val inp = DataInputStream(sock.getInputStream())
            out.writeByte(19)
            out.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
            val reserved = ByteArray(8)
            reserved[5] = 0x10
            out.write(reserved)
            out.write(meta.infoHash)
            out.write("OMNI-4100-STREAM-0001".toByteArray(Charsets.US_ASCII).copyOf(20))
            out.flush()
            val pstrlen = inp.readUnsignedByte()
            if (pstrlen != 19) return null
            inp.readFully(ByteArray(19))
            inp.readFully(ByteArray(8))
            val theirHash = ByteArray(20)
            inp.readFully(theirHash)
            inp.readFully(ByteArray(20))
            if (!theirHash.contentEquals(meta.infoHash)) return null
            sock
        } catch (_: Throwable) {
            try { sock.close() } catch (_: Throwable) {}
            null
        }
    }
}
