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

    // v4.46.0: метаданные и пиры запоминаем по magnet/.torrent. Переключение файла внутри раздачи
    // больше не перекачивает метаданные заново — иначе выбор второго файла «зависал».
    private var cachedKey: String = ""
    private var cachedSource: BitTorrentEngine.StreamSource? = null
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
        val key = magnetUri.orEmpty() + "|" + torrentUrl.orEmpty()
        val cached = cachedSource?.takeIf { cachedKey == key }
        val source = cached ?: engine.prepareStream(magnetUri, torrentUrl, referer)?.also {
            cachedKey = key
            cachedSource = it
        }
        if (source == null) {
            // v4.44.0: говорим, НА КАКОМ шаге всё встало, а не «не удалось» одной фразой
            statusText = "Метаданные не получены. Этап: " + engine.prepareStage +
                "\nЕсли пиров 0 — у раздачи нет сидов; если пиров много, но метаданные не идут — пиры не поддерживают обмен метаданными или канал блокирует TCP."
            return false
        }
        val meta = source.metadata
        files = meta.files
        // v4.42.0: если файлов несколько — берём выбранный, иначе самый крупный.
        // Так раздачи с папками (сезон сериала, дискография) не превращаются в «первый попавшийся» файл.
        // v4.46.0: по умолчанию берём самый крупный ВИДЕО/аудио-файл, а не самый крупный вообще.
        // В раздачах рядом лежат .iso, .img и .txt — раньше выбирался именно такой файл, и плеер
        // показывал чёрный экран, хотя кино в раздаче есть.
        val biggest = when {
            preferredFile != null -> meta.files.firstOrNull { it.relativePath == preferredFile }
                ?: pickDefaultFile(meta.files)
            else -> pickDefaultFile(meta.files)
        }
        selectedFile = biggest
        fileName = biggest?.relativePath?.substringAfterLast('/') ?: meta.name
        totalBytes = biggest?.length ?: meta.totalSize
        rangeStart = biggest?.offsetInTorrent ?: 0L
        rangeEnd = rangeStart + totalBytes
        val target = File(dir, fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        file = target
        if (!target.exists()) target.createNewFile()
        // v4.45.0: бывает и 0 живых пиров, но файл лежит на сервере (веб-сид) — тогда поток возможен
        if (source.peers.isEmpty() && meta.webSeeds.isEmpty()) {
            statusText = "В раздаче 0 пиров и нет веб-сида — смотреть нечего (нужен сид)"
            return false
        }
        playing = true
        statusText = if (meta.webSeeds.isNotEmpty()) "Качаю напрямую с сервера (веб-сид)…"
            else "Подключение к пирам…"
        worker = Thread({ pump(source, target, biggest) }, "TorrentStreamer").also { it.isDaemon = true; it.start() }
        return true
    }

    /** Видео/аудио по расширению — такие файлы плеер точно умеет открывать. */
    fun isMedia(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "mp4", "mkv", "avi", "webm", "m4v", "mov", "ts", "mpg", "mpeg", "flv", "ogv", "3gp",
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "ac3", "dts"
        )
    }

    private fun pickDefaultFile(files: List<TorrentFileEntry>): TorrentFileEntry? {
        val media = files.filter { isMedia(it.relativePath) }
        return (if (media.isNotEmpty()) media else files).maxByOrNull { it.length }
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
        var piece = 0

        while (piece < pieceCount && !stopFlag.get()) {
            val pieceStart = piece.toLong() * pieceLen
            // куски вне выбранного файла пропускаем: не тратим трафик на сезон целиком
            if (pieceStart + pieceLen <= rangeStart || pieceStart >= rangeEnd) { piece++; continue }

            // v4.45.0: сначала пробуем веб-сид. У раздач Archive.org в .torrent прописан url-list:
            // файл доступен по обычному HTTPS, и это работает там, где пиры ничего не отдают.
            if (meta.webSeeds.isNotEmpty()) {
                statusText = "Загрузка по HTTP с сервера: кусок ${piece + 1} из $pieceCount"
                val got = webSeedPieces(meta.webSeeds, meta, piece, 4, target)
                if (got > 0) {
                    piece += got
                    downloaded += got.toLong() * pieceLen
                    availableBytes = minOf(downloaded, totalBytes)
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                    speedBytesPerSec = downloaded * 1000 / elapsed
                    continue
                }
            }

            var ok = false
            var attempts = 0
            while (!ok && attempts < 3 && peers.isNotEmpty() && !stopFlag.get()) {
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
                statusText = when {
                    peers.isEmpty() -> "Пиров нет, веб-сид не отдал кусок ${piece + 1} — поток остановлен"
                    else -> "Пиры не отдали кусок ${piece + 1} — поток остановлен"
                }
                break
            }
            piece++
            downloaded += pieceLen.toLong()
            availableBytes = minOf(downloaded, totalBytes)
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            speedBytesPerSec = downloaded * 1000 / elapsed
        }
        statusText = if (availableBytes >= totalBytes && totalBytes > 0) "Загружено полностью" else "Поток: ${statusText}"
    }

    /**
     * v4.45.0: взять до [maxPieces] кусков по HTTP из веб-сида (BEP 19 / url-list в .torrent).
     * Каждый кусок сверяется с SHA-1 из .torrent — в файл не попадёт ничего непроверенного.
     * Возвращает число записанных подряд кусков (0 — веб-сид не помог).
     */
    private fun webSeedPieces(
        seeds: List<String>,
        meta: TorrentMetadata,
        firstPiece: Int,
        maxPieces: Int,
        target: File
    ): Int {
        val pieceLen = meta.pieceLength
        val winStart = firstPiece.toLong() * pieceLen
        val winEnd = minOf(meta.totalSize, winStart + maxPieces.toLong() * pieceLen)
        if (winEnd <= winStart) return 0
        val buf = ByteArray((winEnd - winStart).toInt())

        // окно режем по файлам раздачи: у каждого файла свой адрес на сервере
        for (f in meta.files) {
            val from = maxOf(winStart, f.offsetInTorrent)
            val to = minOf(winEnd, f.offsetInTorrent + f.length)
            if (to <= from) continue
            val offInFile = from - f.offsetInTorrent
            val len = (to - from).toInt()
            var got: ByteArray? = null
            outer@ for (seed in seeds) {
                for (url in webSeedUrls(seed, meta, f)) {
                    val b = httpRange(url, offInFile, len)
                    if (b != null && b.size == len) {
                        got = b
                        break@outer
                    }
                }
            }
            if (got == null) return 0
            System.arraycopy(got, 0, buf, (from - winStart).toInt(), got.size)
        }

        var done = 0
        var written = 0L
        for (i in 0 until maxPieces) {
            val p = firstPiece + i
            if (p >= meta.pieceCount) break
            val off = p.toLong() * pieceLen
            if (off >= winEnd) break
            val len = minOf(pieceLen.toLong(), meta.totalSize - off).toInt()
            val from = (off - winStart).toInt()
            if (from + len > buf.size) break
            val data = buf.copyOfRange(from, from + len)
            if (p < meta.pieceHashes.size && !BitTorrentParser.sha1(data).contentEquals(meta.pieceHashes[p])) break
            // пишем только ту часть куска, что попадает в выбранный файл
            val fileStart = maxOf(off, rangeStart)
            val fileEnd = minOf(off + len, rangeEnd)
            if (fileEnd > fileStart) {
                RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(fileStart - rangeStart)
                    raf.write(data, (fileStart - off).toInt(), (fileEnd - fileStart).toInt())
                }
                written += fileEnd - fileStart
            }
            done++
        }
        if (done > 0) availableBytes = minOf(availableBytes + written, totalBytes)
        return done
    }

    /** Адреса файла на веб-сиде: <seed>/<путь> и <seed>/<имя раздачи>/<путь>. */
    private fun webSeedUrls(seed: String, meta: TorrentMetadata, f: TorrentFileEntry): List<String> {
        val base = seed.trimEnd('/')
        val rel = f.relativePath.trimStart('/')
        val urls = linkedSetOf<String>()
        if (base.startsWith("http")) urls.add("$base/${encodePath(rel)}")
        if (base.startsWith("http") && !rel.startsWith(meta.name + "/")) {
            urls.add("$base/${encodePath(meta.name)}/${encodePath(rel)}")
        }
        return urls.toList()
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** HTTP Range-запрос: ровно [len] байт с [offset]. Редиректы проходим сами. */
    private fun httpRange(url: String, offset: Long, len: Int, hops: Int = 0): ByteArray? {
        if (hops > 5 || len <= 0) return null
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OmniBrowser/4.45")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Range", "bytes=$offset-${offset + len - 1}")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) return null
                val next = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(url), loc).toString()
                return httpRange(next, offset, len, hops + 1)
            }
            if (code != 206 && code != 200) {
                c.disconnect()
                return null
            }
            val inp = c.inputStream
            if (code == 200) {
                var skip = offset
                while (skip > 0) {
                    val s = inp.skip(skip)
                    if (s <= 0) break
                    skip -= s
                }
            }
            val out = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = inp.read(out, read, len - read)
                if (r <= 0) break
                read += r
            }
            inp.close()
            c.disconnect()
            if (read == len) out else null
        } catch (_: Throwable) {
            null
        }
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
