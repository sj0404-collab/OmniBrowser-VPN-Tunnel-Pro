package com.winwasm64.torrent

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random
import org.json.JSONObject

sealed class BencodeValue {
    data class BInt(val value: Long) : BencodeValue()
    data class BBytes(val bytes: ByteArray) : BencodeValue() {
        fun asString(): String = String(bytes, Charsets.UTF_8)
        override fun equals(other: Any?): Boolean = other is BBytes && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class BList(val values: List<BencodeValue>) : BencodeValue()
    data class BDict(
        val entries: Map<String, BencodeValue>,
        val rawStart: Int = 0,
        val rawEnd: Int = 0
    ) : BencodeValue()
}

object BencodeCodec {

    fun decode(data: ByteArray): BencodeValue {
        val state = DecodeState(data, 0)
        return decodeNext(state)
    }

    private class DecodeState(val data: ByteArray, var pos: Int)

    private fun decodeNext(s: DecodeState): BencodeValue {
        if (s.pos >= s.data.size) throw IllegalArgumentException("Unexpected EOF in Bencode")
        return when (val ch = s.data[s.pos].toInt().toChar()) {
            'i' -> {
                s.pos++
                val end = findByte(s.data, 'e'.code.toByte(), s.pos)
                val numStr = String(s.data, s.pos, end - s.pos, Charsets.US_ASCII)
                s.pos = end + 1
                BencodeValue.BInt(numStr.toLong())
            }
            'l' -> {
                s.pos++
                val list = mutableListOf<BencodeValue>()
                while (s.pos < s.data.size && s.data[s.pos].toInt().toChar() != 'e') {
                    list.add(decodeNext(s))
                }
                if (s.pos < s.data.size && s.data[s.pos].toInt().toChar() == 'e') s.pos++
                BencodeValue.BList(list)
            }
            'd' -> {
                val start = s.pos
                s.pos++
                val map = linkedMapOf<String, BencodeValue>()
                while (s.pos < s.data.size && s.data[s.pos].toInt().toChar() != 'e') {
                    val keyVal = decodeNext(s) as? BencodeValue.BBytes
                        ?: throw IllegalArgumentException("Dictionary key must be byte string")
                    val value = decodeNext(s)
                    map[keyVal.asString()] = value
                }
                if (s.pos < s.data.size && s.data[s.pos].toInt().toChar() == 'e') s.pos++
                val end = s.pos
                BencodeValue.BDict(map, start, end)
            }
            in '0'..'9' -> {
                val colon = findByte(s.data, ':'.code.toByte(), s.pos)
                val lenStr = String(s.data, s.pos, colon - s.pos, Charsets.US_ASCII)
                val len = lenStr.toInt()
                val start = colon + 1
                val slice = s.data.copyOfRange(start, min(start + len, s.data.size))
                s.pos = start + len
                BencodeValue.BBytes(slice)
            }
            else -> throw IllegalArgumentException("Invalid Bencode token '$ch' at ${s.pos}")
        }
    }

    private fun findByte(data: ByteArray, target: Byte, from: Int): Int {
        for (i in from until data.size) {
            if (data[i] == target) return i
        }
        throw IllegalArgumentException("Byte $target not found from $from")
    }

    fun encode(value: BencodeValue): ByteArray {
        val out = ByteArrayOutputStream()
        encodeInto(value, out)
        return out.toByteArray()
    }

    private fun encodeInto(value: BencodeValue, out: ByteArrayOutputStream) {
        when (value) {
            is BencodeValue.BInt -> {
                out.write("i${value.value}e".toByteArray(Charsets.US_ASCII))
            }
            is BencodeValue.BBytes -> {
                out.write("${value.bytes.size}:".toByteArray(Charsets.US_ASCII))
                out.write(value.bytes)
            }
            is BencodeValue.BList -> {
                out.write('l'.code)
                for (item in value.values) encodeInto(item, out)
                out.write('e'.code)
            }
            is BencodeValue.BDict -> {
                out.write('d'.code)
                for (key in value.entries.keys.sorted()) {
                    val keyBytes = key.toByteArray(Charsets.UTF_8)
                    out.write("${keyBytes.size}:".toByteArray(Charsets.US_ASCII))
                    out.write(keyBytes)
                    encodeInto(value.entries.getValue(key), out)
                }
                out.write('e'.code)
            }
        }
    }
}

data class TorrentFileEntry(
    val relativePath: String,
    val length: Long,
    val offsetInTorrent: Long
)

data class TorrentMetadata(
    val name: String,
    val infoHash: ByteArray,
    val infoHashHex: String,
    val totalSize: Long,
    val pieceLength: Int,
    val pieceHashes: List<ByteArray>,
    val trackers: List<String>,
    val webSeeds: List<String>,
    val files: List<TorrentFileEntry>
) {
    val pieceCount: Int get() = pieceHashes.size
}

data class MagnetInfo(
    val infoHash: ByteArray,
    val infoHashHex: String,
    val displayName: String,
    val trackers: List<String>,
    val webSeeds: List<String>,
    val exactLength: Long = 0L
)

data class PeerEndpoint(
    val ip: String,
    val port: Int
) {
    override fun toString(): String = "$ip:$port"
}

enum class TorrentState(val labelRu: String) {
    METADATA("Получение метаданных..."),
    DOWNLOADING("Скачивание (BitTorrent P2P + WebSeed)"),
    PAUSED("Пауза"),
    COMPLETED("Раздача / Скачано 100%"),
    ERROR("Ошибка")
}

data class TorrentSessionStatus(
    val id: String,
    val name: String,
    val infoHashHex: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val downloadSpeedBytesPerSec: Long,
    val connectedPeers: Int,
    val knownPeers: Int,
    val verifiedPieces: Int,
    val totalPieces: Int,
    val state: TorrentState,
    val outputFilePath: String,
    val files: List<String>,
    val statusDetail: String,
    /** v4.39.0: аналоги с пирами, когда точной замены нет — показываются в карточке. */
    val suggestions: List<TorrentSearchAgent.Found> = emptyList(),
    val pieceBitfield: BooleanArray
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        else if (totalPieces > 0) (verifiedPieces.toFloat() / totalPieces.toFloat()).coerceIn(0f, 1f)
        else 0f
}

data class CatalogTorrentItem(
    val title: String,
    val category: String,
    val sizeLabel: String,
    val description: String,
    val torrentUrl: String,
    val magnetUri: String,
    val directWebSeedUrl: String,
    val fileName: String,
    val estimatedSizeBytes: Long,
    /** v4.39.0: сколько сидов нашёл агент и почему он это предложил. */
    val seeders: Int = 0,
    val reason: String = "",
    /** Some сайты (catorrent) отдают .torrent только с Referer страницы. */
    val referer: String = "",
    /** v4.41.0: обложка (og:image со страницы) — превью вместо голого текста. */
    val poster: String = "",
    /** v4.42.0: тип содержимого для фильтра — game/video/music/book/soft/other.
     *  (поле `category` уже занято источником/подписью, поэтому здесь `kind`) */
    val kind: String = "other",
    /** v4.43.0: разрешение и кодек из названия — «720p · x264». */
    val quality: String = "",
    val codec: String = ""
)

object BitTorrentParser {

    fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun decodeBase32(base32: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = base32.trim().uppercase().trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun urlEncodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = (c in 'a'.code..'z'.code) ||
                (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append(String.format("%%%02X", c))
            }
        }
        return sb.toString()
    }

    fun parseTorrentBytes(torrentBytes: ByteArray): TorrentMetadata {
        val root = BencodeCodec.decode(torrentBytes) as? BencodeValue.BDict
            ?: throw IllegalArgumentException("Root of .torrent must be a Bencode dictionary")

        val infoDict = root.entries["info"] as? BencodeValue.BDict
            ?: throw IllegalArgumentException(".torrent missing 'info' dictionary")

        val rawInfoBytes = if (infoDict.rawEnd > infoDict.rawStart && infoDict.rawEnd <= torrentBytes.size) {
            torrentBytes.copyOfRange(infoDict.rawStart, infoDict.rawEnd)
        } else {
            BencodeCodec.encode(infoDict)
        }
        val infoHash = sha1(rawInfoBytes)
        val infoHashHex = toHex(infoHash)

        val name = (infoDict.entries["name.utf-8"] as? BencodeValue.BBytes)?.asString()
            ?: (infoDict.entries["name"] as? BencodeValue.BBytes)?.asString()
            ?: "download_$infoHashHex"

        val pieceLength = ((infoDict.entries["piece length"] as? BencodeValue.BInt)?.value ?: 262144L).toInt()
        val piecesBlob = (infoDict.entries["pieces"] as? BencodeValue.BBytes)?.bytes ?: ByteArray(0)
        val pieceHashes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 20 <= piecesBlob.size) {
            pieceHashes.add(piecesBlob.copyOfRange(offset, offset + 20))
            offset += 20
        }

        val files = mutableListOf<TorrentFileEntry>()
        var totalSize = 0L
        val singleLength = (infoDict.entries["length"] as? BencodeValue.BInt)?.value
        if (singleLength != null) {
            files.add(TorrentFileEntry(name, singleLength, 0L))
            totalSize = singleLength
        } else {
            val filesList = (infoDict.entries["files"] as? BencodeValue.BList)?.values.orEmpty()
            for (fVal in filesList) {
                val fDict = fVal as? BencodeValue.BDict ?: continue
                val fLen = (fDict.entries["length"] as? BencodeValue.BInt)?.value ?: continue
                val pathParts = ((fDict.entries["path.utf-8"] as? BencodeValue.BList)
                    ?: (fDict.entries["path"] as? BencodeValue.BList))
                    ?.values
                    ?.mapNotNull { (it as? BencodeValue.BBytes)?.asString() }
                    .orEmpty()
                val relPath = if (pathParts.isEmpty()) name else pathParts.joinToString("/")
                files.add(TorrentFileEntry(relPath, fLen, totalSize))
                totalSize += fLen
            }
        }

        val trackers = linkedSetOf<String>()
        (root.entries["announce"] as? BencodeValue.BBytes)?.asString()?.takeIf { it.isNotBlank() }?.let {
            trackers.add(it)
        }
        val announceList = (root.entries["announce-list"] as? BencodeValue.BList)?.values.orEmpty()
        for (tier in announceList) {
            when (tier) {
                is BencodeValue.BList -> tier.values.forEach {
                    (it as? BencodeValue.BBytes)?.asString()?.takeIf { s -> s.isNotBlank() }?.let(trackers::add)
                }
                is BencodeValue.BBytes -> tier.asString().takeIf { it.isNotBlank() }?.let(trackers::add)
                else -> {}
            }
        }
        trackers.addAll(DEFAULT_PUBLIC_TRACKERS)

        val webSeeds = linkedSetOf<String>()
        for (seedKey in listOf("url-list", "httpseeds")) {
            when (val seedVal = root.entries[seedKey]) {
                is BencodeValue.BBytes -> seedVal.asString().takeIf { it.startsWith("http") }?.let(webSeeds::add)
                is BencodeValue.BList -> seedVal.values.forEach {
                    (it as? BencodeValue.BBytes)?.asString()?.takeIf { s -> s.startsWith("http") }?.let(webSeeds::add)
                }
                else -> {}
            }
        }

        return TorrentMetadata(
            name = name,
            infoHash = infoHash,
            infoHashHex = infoHashHex,
            totalSize = totalSize,
            pieceLength = pieceLength,
            pieceHashes = pieceHashes,
            trackers = trackers.toList(),
            webSeeds = webSeeds.toList(),
            files = files
        )
    }

    fun createSyntheticTorrentBytes(
        name: String,
        payload: ByteArray,
        pieceLength: Int = 16384,
        announceUrl: String = "http://tracker.opentrackr.org:1337/announce",
        webSeedUrl: String? = null
    ): ByteArray {
        val piecesOut = ByteArrayOutputStream()
        var pos = 0
        while (pos < payload.size) {
            val end = min(pos + pieceLength, payload.size)
            piecesOut.write(sha1(payload.copyOfRange(pos, end)))
            pos = end
        }
        if (payload.isEmpty()) {
            piecesOut.write(sha1(ByteArray(0)))
        }
        val infoMap = linkedMapOf<String, BencodeValue>(
            "length" to BencodeValue.BInt(payload.size.toLong()),
            "name" to BencodeValue.BBytes(name.toByteArray(Charsets.UTF_8)),
            "piece length" to BencodeValue.BInt(pieceLength.toLong()),
            "pieces" to BencodeValue.BBytes(piecesOut.toByteArray())
        )
        val rootMap = linkedMapOf<String, BencodeValue>(
            "announce" to BencodeValue.BBytes(announceUrl.toByteArray(Charsets.UTF_8)),
            "info" to BencodeValue.BDict(infoMap)
        )
        if (!webSeedUrl.isNullOrBlank()) {
            rootMap["url-list"] = BencodeValue.BBytes(webSeedUrl.toByteArray(Charsets.UTF_8))
        }
        return BencodeCodec.encode(BencodeValue.BDict(rootMap))
    }

    fun parseMagnetUri(uri: String): MagnetInfo {
        val trimmed = uri.trim()
        require(trimmed.startsWith("magnet:?", ignoreCase = true)) { "Invalid magnet URI: $uri" }
        val query = trimmed.substringAfter("?")
        var hashBytes: ByteArray? = null
        var displayName = ""
        val trackers = linkedSetOf<String>()
        val webSeeds = linkedSetOf<String>()
        var exactLength = 0L

        for (param in query.split("&")) {
            if (param.isBlank()) continue
            val eq = param.indexOf('=')
            if (eq <= 0) continue
            val k = param.substring(0, eq).lowercase()
            val rawV = param.substring(eq + 1)
            val v = try {
                URLDecoder.decode(rawV, "UTF-8")
            } catch (_: Exception) {
                rawV
            }
            when {
                k == "xt" || k.startsWith("xt.") -> {
                    val btih = v.substringAfter("urn:btih:", "")
                    if (btih.length == 40) {
                        hashBytes = fromHex(btih)
                    } else if (btih.length == 32) {
                        hashBytes = decodeBase32(btih)
                    }
                }
                k == "dn" -> displayName = v
                k == "tr" || k.startsWith("tr.") -> if (v.isNotBlank()) trackers.add(v)
                k == "ws" || k.startsWith("ws.") || k == "as" -> if (v.startsWith("http")) webSeeds.add(v)
                k == "xl" -> exactLength = v.toLongOrNull() ?: 0L
            }
        }

        val finalHash = hashBytes ?: sha1(trimmed.toByteArray(Charsets.UTF_8))
        val hex = toHex(finalHash)
        trackers.addAll(DEFAULT_PUBLIC_TRACKERS)
        return MagnetInfo(
            infoHash = finalHash,
            infoHashHex = hex,
            displayName = displayName.ifBlank { "torrent_$hex" },
            trackers = trackers.toList(),
            webSeeds = webSeeds.toList(),
            exactLength = exactLength
        )
    }

    val DEFAULT_PUBLIC_TRACKERS = listOf(
        "http://tracker.opentrackr.org:1337/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "http://bt1.archive.org:6969/announce",
        "http://bt2.archive.org:6969/announce"
    )
}

/** v4.33.0: суммарный трафик по всем задачам — для панели «Трафик и скорость». */
data class TorrentTotals(
    val tasks: Int,
    val downloaded: Long,
    val total: Long,
    val speedBytesPerSec: Long,
    val peers: Int
)

class BitTorrentEngine(private val context: Context? = null) {

    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, ActiveTorrentTask>()
    val peerId: ByteArray = ("-OB2100-" + (1..12).map { ('0'..'9').random() }.joinToString("")).toByteArray(Charsets.US_ASCII)

    /**
     * v4.38.0: автопоиск замены для мёртвой раздачи (0 пиров). По умолчанию включён.
     * Человек просил: не качать пустышку, а найти ту же вещь там, где она живая.
     */
    @Volatile var autoRescue: Boolean =
        context?.getSharedPreferences("omni_torrent", android.content.Context.MODE_PRIVATE)
            ?.getBoolean("auto_rescue", true) ?: true

    /** Явное сохранение настройки (имя не setAutoRescue — иначе конфликт со сеттером свойства). */
    fun updateAutoRescue(enabled: Boolean) {
        autoRescue = enabled
        context?.getSharedPreferences("omni_torrent", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("auto_rescue", enabled)?.apply()
    }

    @Volatile var useBrowsecVpn: Boolean = true
    @Volatile var browsecCountryCode: String = "DE"
    @Volatile var browsecEdgeIp: String = "185.220.101.42"

    /**
     * v4.41.0: результаты поиска живут в движке, а не в Composable.
     * Раньше они были `remember` в разделе торрентов и стирались при переходе на другую вкладку.
     */
    @Volatile var cachedSearchQuery: String = ""
    @Volatile var cachedSearchResults: List<CatalogTorrentItem> = emptyList()

    /**
     * Working directory for active RandomAccessFile downloads (always writable on Android 7-15),
     * and upon completion we also export/sync directly to public /storage/emulated/0/Download/OmniTorrents
     * via MediaStore.Downloads so it is 100% visible in Android's Files / Downloads app!
     */
    fun getDownloadDirectory(): File {
        val publicDir = try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(pub, "OmniTorrents")
            dir.mkdirs()
            val probe = File(dir, ".probe_${System.currentTimeMillis()}")
            if (probe.createNewFile()) {
                probe.delete()
                dir
            } else null
        } catch (_: Exception) {
            null
        }
        if (publicDir != null) return publicDir

        val appExt = context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExt != null) {
            return File(appExt, "OmniTorrents").apply { mkdirs() }
        }
        val fallback = File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir", "/tmp")), "OmniTorrents")
        fallback.mkdirs()
        return fallback
    }

    fun getPublicDisplayPath(): String = "/storage/emulated/0/Download/OmniTorrents"

    /**
     * Publishes a downloaded file into public /storage/emulated/0/Download/OmniTorrents
     * using both direct File copy (if MANAGE_EXTERNAL_STORAGE is granted) and Android 10+
     * MediaStore.Downloads so the system Files app always shows the file immediately.
     */
    private fun publishToPublicDownloads(sourceFile: File): File {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return sourceFile
        try {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(pubDownloads, "OmniTorrents")
            targetDir.mkdirs()
            val targetFile = File(targetDir, sourceFile.name)
            if (sourceFile.absolutePath != targetFile.absolutePath) {
                try {
                    sourceFile.copyTo(targetFile, overwrite = true)
                    if (targetFile.exists() && targetFile.length() == sourceFile.length()) {
                        MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                        return targetFile
                    }
                } catch (_: Exception) {
                }
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(sourceFile.absolutePath), null, null)
                return sourceFile
            }

            // Android 10+ (API 29+) MediaStore.Downloads insertion (works without MANAGE_EXTERNAL_STORAGE!)
            val ctx = context ?: return sourceFile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, guessMimeType(sourceFile.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniTorrents")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (_: Exception) {
        }
        return sourceFile
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "exe", "msi" -> "application/x-msdos-program"
            "apk" -> "application/vnd.android.package-archive"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "torrent" -> "application/x-bittorrent"
            else -> "application/octet-stream"
        }
    }

    /**
     * v4.33.0: встроенный список раздач убран — по умолчанию список пустой.
     * Раньше здесь лежали демонстрационные записи (DOOM, 7-Zip, PuTTY), и человек видел «чьи-то»
     * торренты вместо своего пустого списка. Функция оставлена, чтобы не ломать вызовы:
     * теперь она отдаёт пусто, а наполняется список только тем, что вы открыли или нашли.
     */
    fun getBuiltInVerifiedTorrents(): List<CatalogTorrentItem> = emptyList()

    /** v4.33.0: суммарный трафик и скорость по всем задачам. */
    fun totals(): TorrentTotals {
        var downloaded = 0L
        var total = 0L
        var speed = 0L
        var peers = 0
        for (st in getAllStatuses()) {
            downloaded += st.downloadedBytes
            total += st.totalBytes
            speed += st.downloadSpeedBytesPerSec
            peers += st.connectedPeers
        }
        return TorrentTotals(sessions.size, downloaded, total, speed, peers)
    }

    private fun applyBrowsecHeaders(conn: HttpURLConnection) {
        // Подставные заголовки X-Forwarded-For / CF-IPCountry удалены: они не меняют IP и только вводили в заблуждение
        conn.setRequestProperty("User-Agent", "OmniBrowser/4.0 (BitTorrent; Android)")
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8")
    }

    fun searchPublicTorrents(query: String, onResults: (List<CatalogTorrentItem>) -> Unit) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            onResults(getBuiltInVerifiedTorrents())
            return
        }
        executor.submit {
            val results = mutableListOf<CatalogTorrentItem>()

            // v4.39.0: сначала агент — catorrent (тот же сайт, откуда берутся .torrent) плюс открытые
            // индексы, с сидами и объяснением выбора. Если он ничего не нашёл, работает старый путь.
            try {
                // v4.41.0: намерение — из СЫРОГО запроса. Раньше cleanQuery выбрасывал «pc»/«switch»,
                // и «scarlet» с «scarlet pc» давали один и тот же список: уточнение просто терялось.
                val intent = TorrentSearchAgent.parseIntent(query)
                val found = TorrentSearchAgent.searchAll(cleanQuery)
                val rankedAll = TorrentSearchAgent.rank(found, intent)
                // если человек уточнил платформу/формат — подходящее поднимаем наверх, но не выбрасываем
                // остальное: иногда в названии платформы нет, а внутри она та самая
                val strict = if (intent.platform.isNotBlank() || intent.format.isNotBlank()) {
                    rankedAll.filter { f ->
                        f.platform.contains(intent.platform, true) ||
                            f.name.contains(intent.platform, true) ||
                            (intent.format.isNotBlank() && f.name.contains(intent.format, true)) ||
                            f.platform.isBlank()
                    }
                } else rankedAll
                var ranked = strict.ifEmpty { rankedAll }
                // v4.43.0: «шерлок 360p» — сначала точное качество, иначе ближайшие (что реально есть)
                if (intent.quality.isNotBlank() || intent.audio.isNotBlank()) {
                    ranked = TorrentSearchAgent.filterByQuality(ranked, intent.quality)
                }
                for (f in ranked.take(12)) {
                    results.add(
                        CatalogTorrentItem(
                            title = f.name,
                            category = "Найдено агентом · ${f.source}" + (if (f.platform.isNotBlank()) " · ${f.platform}" else ""),
                            sizeLabel = f.sizeLabel,
                            description = f.reason.ifBlank { "найдено по запросу «$cleanQuery»" },
                            torrentUrl = f.torrentUrl,
                            magnetUri = f.magnet,
                            directWebSeedUrl = "",
                            fileName = f.name.take(60) + ".torrent",
                            estimatedSizeBytes = f.sizeBytes,
                            seeders = f.seeders,
                            reason = f.reason,
                            referer = f.referer,
                            poster = f.poster,
                            kind = f.category,
                            quality = f.quality,
                            codec = f.codec
                        )
                    )
                }
                com.winwasm64.vpn.ConnTrace.torrent(
                    "агент: «$cleanQuery»",
                    "найдено ${found.size}, показано ${results.size}",
                    results.isNotEmpty()
                )
            } catch (_: Throwable) {
            }

            try {
                val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                val apiUrl = "https://archive.org/advancedsearch.php?q=($encoded)+AND+mediatype:(movies+OR+software+OR+audio)&fl[]=identifier,title,mediatype,item_size,description&rows=12&page=1&output=json"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    applyBrowsecHeaders(this)
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val docs = json.optJSONObject("response")?.optJSONArray("docs")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val d = docs.getJSONObject(i)
                            val id = d.optString("identifier")
                            if (id.isBlank()) continue
                            val title = d.optString("title", id)
                            val mediaType = d.optString("mediatype", "archive")
                            val sizeBytes = d.optLong("item_size", 0L).coerceAtLeast(0L)
                            val sizeMb = if (sizeBytes > 0L) String.format("%.1f МБ", sizeBytes / (1024.0 * 1024.0)) else "размер неизвестен"
                            val torrentUrl = "https://archive.org/download/$id/${id}_archive.torrent"
                            results.add(
                                CatalogTorrentItem(
                                    title = title,
                                    category = "Archive.org BitTorrent ($mediaType)",
                                    sizeLabel = sizeMb,
                                    description = "Archive.org: $id (настоящий .torrent файл)",
                                    torrentUrl = torrentUrl,
                                    magnetUri = "",
                                    directWebSeedUrl = "",
                                    fileName = "$id.torrent",
                                    estimatedSizeBytes = sizeBytes
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
            cachedSearchQuery = cleanQuery
            cachedSearchResults = results.toList()
            val matchingBuiltIn = getBuiltInVerifiedTorrents().filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                    it.category.contains(cleanQuery, ignoreCase = true) ||
                    it.description.contains(cleanQuery, ignoreCase = true)
            }
            val combined = (matchingBuiltIn + results).distinctBy { it.title }
            onResults(combined.ifEmpty { getBuiltInVerifiedTorrents() })
        }
    }


    fun startFromCatalogItem(item: CatalogTorrentItem): String {
        // v4.39.0: результат агента — запускаем через агента (magnet или .torrent с Referer)
        if (item.magnetUri.isNotBlank() || item.referer.isNotBlank()) {
            return startFromFound(
                TorrentSearchAgent.Found(
                    name = item.title,
                    magnet = item.magnetUri,
                    torrentUrl = item.torrentUrl,
                    referer = item.referer,
                    sizeBytes = item.estimatedSizeBytes,
                    seeders = item.seeders,
                    source = item.category
                )
            )
        }
        val id = BitTorrentParser.toHex(BitTorrentParser.sha1(item.title.toByteArray(Charsets.UTF_8))).take(16)
        val existing = sessions[id]
        if (existing != null && existing.state != TorrentState.ERROR) {
            existing.paused.set(false)
            return id
        }
        val outFile = File(getDownloadDirectory(), sanitizeFileName(item.fileName))
        val task = ActiveTorrentTask(
            id = id,
            initialName = item.title,
            infoHashHex = id.padEnd(40, '0'),
            torrentUrl = item.torrentUrl.takeIf { it.isNotBlank() },
            magnetUri = item.magnetUri.takeIf { it.isNotBlank() },
            directWebSeedUrl = item.directWebSeedUrl.takeIf { it.isNotBlank() },
            rawTorrentBytes = null,
            outputFile = outFile,
            estimatedSize = item.estimatedSizeBytes
        )
        sessions[id] = task
        executor.submit { runTorrentTask(task) }
        return id
    }

    fun startFromMagnetOrUrl(uriOrUrl: String): String {
        val clean = uriOrUrl.trim()
        val id = BitTorrentParser.toHex(BitTorrentParser.sha1(clean.toByteArray(Charsets.UTF_8))).take(16)
        val existing = sessions[id]
        if (existing != null && existing.state != TorrentState.ERROR) {
            existing.paused.set(false)
            return id
        }
        return if (clean.startsWith("magnet:", ignoreCase = true)) {
            val mag = BitTorrentParser.parseMagnetUri(clean)
            val safeName = sanitizeFileName(mag.displayName.ifBlank { "magnet_${mag.infoHashHex.take(10)}.bin" })
            val outFile = File(getDownloadDirectory(), safeName)
            val task = ActiveTorrentTask(
                id = id,
                initialName = mag.displayName,
                infoHashHex = mag.infoHashHex,
                torrentUrl = null,
                magnetUri = clean,
                directWebSeedUrl = mag.webSeeds.firstOrNull(),
                rawTorrentBytes = null,
                outputFile = outFile,
                estimatedSize = mag.exactLength
            )
            sessions[id] = task
            executor.submit { runTorrentTask(task) }
            id
        } else {
            val fileName = clean.substringAfterLast("/").substringBefore("?").ifBlank { "download_$id.torrent" }
            val outFile = File(getDownloadDirectory(), sanitizeFileName(fileName.removeSuffix(".torrent")))
            val task = ActiveTorrentTask(
                id = id,
                initialName = fileName,
                infoHashHex = id.padEnd(40, '0'),
                torrentUrl = if (clean.endsWith(".torrent", ignoreCase = true) || clean.contains("torrent", ignoreCase = true)) clean else null,
                magnetUri = null,
                directWebSeedUrl = if (!clean.endsWith(".torrent", ignoreCase = true)) clean else null,
                rawTorrentBytes = null,
                outputFile = outFile,
                estimatedSize = 0L
            )
            sessions[id] = task
            executor.submit { runTorrentTask(task) }
            id
        }
    }

    /**
     * v4.39.0: запуск того, что нашёл агент — либо magnet, либо .torrent по ссылке (с Referer).
     */
    fun startFromFound(found: TorrentSearchAgent.Found): String {
        if (found.magnet.isNotBlank()) return startFromMagnetOrUrl(found.magnet)
        executor.submit {
            val bytes = TorrentSearchAgent.downloadTorrent(found)
            if (bytes != null) {
                startFromTorrentBytes(bytes, found.name + ".torrent")
            } else {
                com.winwasm64.vpn.ConnTrace.torrent(
                    "агент",
                    ".torrent не скачался: ${found.name.take(50)}",
                    ok = false,
                    note = found.torrentUrl.take(80)
                )
            }
        }
        return BitTorrentParser.toHex(BitTorrentParser.sha1(found.name.toByteArray())).take(16)
    }

    /** v4.44.0: на каком шаге подготовка потока — это показывается в плеере вместо общей фразы. */
    @Volatile var prepareStage: String = ""

    /** Источник для потокового просмотра/прослушивания: метаданные + живые пиры. */
    data class StreamSource(
        val name: String,
        val metadata: TorrentMetadata,
        val peers: List<PeerEndpoint>,
        val infoHashHex: String
    )

    /**
     * v4.41.0: подготовить поток. Метаданные берём из .torrent (или кеша по infoHash), пиров —
     * у трекеров, включая дополнительные. Дальше [TorrentStreamer] тянет куски по порядку,
     * локальный сервер отдаёт их плееру — смотреть можно, не скачивая всю раздачу.
     */
    fun prepareStream(
        magnetUri: String? = null,
        torrentUrl: String? = null,
        referer: String? = null
    ): StreamSource? {
        prepareStage = "ищу .torrent по ссылке…"
        var bytes: ByteArray? = null
        if (!torrentUrl.isNullOrBlank()) bytes = fetchUrlBytes(torrentUrl, referer)
        val metadata: TorrentMetadata
        if (bytes != null && bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) {
            metadata = BitTorrentParser.parseTorrentBytes(bytes)
        } else if (!magnetUri.isNullOrBlank()) {
            val mag = BitTorrentParser.parseMagnetUri(magnetUri)
            prepareStage = "спрашиваю кэши метаданных…"
            val cacheUrls = listOf(
                "https://itorrents.org/torrent/${mag.infoHashHex.uppercase()}.torrent",
                "https://watercache.libertycorp.org/get/${mag.infoHashHex.lowercase()}"
            )
            var got: ByteArray? = null
            for (u in cacheUrls) {
                val f = fetchUrlBytes(u)
                if (f != null && f.isNotEmpty() && f[0] == 'd'.code.toByte()) {
                    got = f
                    break
                }
            }
            if (got != null) {
                metadata = BitTorrentParser.parseTorrentBytes(got)
            } else {
                // v4.42.0: кэши не отдали .torrent — берём метаданные прямо у пиров (ut_metadata).
                // Без этого magnet-раздачи из открытых каталогов вообще не открывались в плеере.
                prepareStage = "ищу пиров по трекерам…"
                val magTrackers = (mag.trackers + TorrentRescue.EXTRA_TRACKERS).distinct()
                val magPeers = LinkedHashSet<PeerEndpoint>()
                for (tr in magTrackers.take(8)) {
                    if (magPeers.size >= 40) break
                    magPeers.addAll(announceTracker(tr, mag.infoHash, mag.exactLength.coerceAtLeast(10_485_760L)))
                }
                prepareStage = "пиров ${magPeers.size} · забираю метаданные у пиров (ut_metadata)…"
                val fetched = MagnetMetadata.fetch(mag.infoHash, mag.infoHashHex, magPeers.toList())
                metadata = fetched ?: return null
            }
        } else {
            return null
        }

        val peers = LinkedHashSet<PeerEndpoint>()
        val trackers = (metadata.trackers + TorrentRescue.EXTRA_TRACKERS).distinct()
        for (tr in trackers.take(8)) {
            if (peers.size >= 40) break
            peers.addAll(announceTracker(tr, metadata.infoHash, metadata.totalSize))
        }
        com.winwasm64.vpn.ConnTrace.torrent(
            "поток: ${metadata.name.take(40)}",
            "пиров: ${peers.size} · ${formatSize(metadata.totalSize)}",
            peers.isNotEmpty()
        )
        return StreamSource(metadata.name, metadata, peers.toList(), metadata.infoHashHex)
    }

    /** Скачать байты по ссылке, при необходимости с Referer (catorrent и подобные). */
    private fun fetchUrlBytes(url: String, referer: String?): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                applyBrowsecHeaders(this)
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        } catch (_: Exception) {
            null
        }
    }

    fun startFromTorrentBytes(bytes: ByteArray, fallbackName: String = "custom.torrent"): String {
        val meta = BitTorrentParser.parseTorrentBytes(bytes)
        val id = meta.infoHashHex.take(16)
        val outFile = File(getDownloadDirectory(), sanitizeFileName(meta.name.ifBlank { fallbackName }))
        val task = ActiveTorrentTask(
            id = id,
            initialName = meta.name,
            infoHashHex = meta.infoHashHex,
            torrentUrl = null,
            magnetUri = null,
            directWebSeedUrl = null,
            rawTorrentBytes = bytes,
            outputFile = outFile,
            estimatedSize = meta.totalSize
        )
        sessions[id] = task
        executor.submit { runTorrentTask(task) }
        return id
    }

    fun togglePause(id: String) {
        val task = sessions[id] ?: return
        val next = !task.paused.get()
        task.paused.set(next)
        if (next) {
            task.state = TorrentState.PAUSED
            task.statusDetail = "Приостановлено пользователем"
        } else if (task.state != TorrentState.COMPLETED) {
            task.state = TorrentState.DOWNLOADING
            executor.submit { runTorrentTask(task) }
        }
    }

    fun removeTorrent(id: String, deleteFile: Boolean = false) {
        val task = sessions.remove(id) ?: return
        task.cancelled.set(true)
        if (deleteFile && task.outputFile.exists()) {
            task.outputFile.delete()
        }
    }

    fun getAllStatuses(): List<TorrentSessionStatus> =
        sessions.values.map { it.toStatus() }.sortedByDescending { it.id }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "torrent_file.bin" }

    private inner class ActiveTorrentTask(
        val id: String,
        var initialName: String,
        var infoHashHex: String,
        val torrentUrl: String?,
        val magnetUri: String?,
        var directWebSeedUrl: String?,
        var rawTorrentBytes: ByteArray?,
        var outputFile: File,
        var estimatedSize: Long
    ) {
        @Volatile var state: TorrentState = TorrentState.METADATA
        @Volatile var statusDetail: String = "Инициализация роя BitTorrent (Browsec $browsecCountryCode)..."
        val downloadedBytes = AtomicLong(0L)
        @Volatile var totalBytes: Long = estimatedSize
        @Volatile var speedBytesPerSec: Long = 0L
        @Volatile var connectedPeers: Int = 0
        @Volatile var knownPeers: Int = 0
        @Volatile var verifiedPieces: Int = 0
        @Volatile var totalPieces: Int = 64
        @Volatile var pieceBitfield: BooleanArray = BooleanArray(64)
        val fileList = CopyOnWriteArrayList<String>()
        val paused = AtomicBoolean(false)
        /** v4.38.0: замену ищем один раз на задачу, чтобы не долбить каталоги по кругу. */
        val rescueTried = AtomicBoolean(false)
        /** v4.39.0: аналоги, найденные агентом (показываются кнопками в карточке). */
        @Volatile var suggestions: List<TorrentSearchAgent.Found> = emptyList()
        val cancelled = AtomicBoolean(false)
        val running = AtomicBoolean(false)

        fun toStatus() = TorrentSessionStatus(
            id = id,
            name = initialName,
            infoHashHex = infoHashHex,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes.get(),
            downloadSpeedBytesPerSec = speedBytesPerSec,
            connectedPeers = connectedPeers,
            knownPeers = knownPeers,
            verifiedPieces = verifiedPieces,
            totalPieces = totalPieces,
            state = state,
            outputFilePath = outputFile.absolutePath,
            files = fileList.toList(),
            statusDetail = statusDetail,
            suggestions = suggestions,
            pieceBitfield = pieceBitfield.copyOf()
        )
    }

    private fun runTorrentTask(task: ActiveTorrentTask) {
        if (!task.running.compareAndSet(false, true)) return
        try {
            task.state = TorrentState.METADATA
            var metadata: TorrentMetadata? = null
            val vpnBadge = if (useBrowsecVpn) " [Browsec $browsecCountryCode]" else ""

            if (task.rawTorrentBytes == null && !task.torrentUrl.isNullOrBlank()) {
                task.statusDetail = "Скачивание .torrent метаданных$vpnBadge..."
                task.rawTorrentBytes = fetchUrlBytes(task.torrentUrl)
            }
            if (task.rawTorrentBytes == null && !task.magnetUri.isNullOrBlank()) {
                val mag = BitTorrentParser.parseMagnetUri(task.magnetUri)
                task.infoHashHex = mag.infoHashHex
                if (task.directWebSeedUrl.isNullOrBlank()) {
                    task.directWebSeedUrl = mag.webSeeds.firstOrNull()
                }
                task.statusDetail = "Запрос метаданных ${mag.infoHashHex.take(12)}$vpnBadge..."
                val cacheUrls = listOf(
                    "https://itorrents.org/torrent/${mag.infoHashHex.uppercase()}.torrent",
                    "https://watercache.libertycorp.org/get/${mag.infoHashHex.lowercase()}"
                )
                for (cUrl in cacheUrls) {
                    val fetched = fetchUrlBytes(cUrl)
                    if (fetched != null && fetched.isNotEmpty() && fetched[0] == 'd'.code.toByte()) {
                        task.rawTorrentBytes = fetched
                        break
                    }
                }
            }

            if (task.rawTorrentBytes != null) {
                try {
                    metadata = BitTorrentParser.parseTorrentBytes(task.rawTorrentBytes!!)
                    if (task.initialName.endsWith(".torrent", true)) {
                        task.initialName = metadata.name
                    }
                    task.infoHashHex = metadata.infoHashHex
                    if (task.totalBytes <= 0L && metadata.totalSize > 0) {
                        task.totalBytes = metadata.totalSize
                    }
                    task.totalPieces = metadata.pieceCount.coerceAtLeast(1)
                    task.pieceBitfield = BooleanArray(task.totalPieces)
                    task.fileList.clear()
                    task.fileList.addAll(metadata.files.map { "${it.relativePath} (${formatSize(it.length)})" })

                    // Only derive WebSeed URL from .torrent if task didn't already have an explicit directWebSeedUrl!
                    // v4.18.0: адрес WebSeed вида archive.org/download/<имя>/<файл> имеет смысл только
                    // для раздач, скачанных с archive.org. Раньше он подставлялся ЛЮБОМУ торренту —
                    // сервер отвечал 503/404, и загрузка падала с «HTTP WebSeed код 503».
                    val fromArchive = task.torrentUrl?.contains("archive.org", ignoreCase = true) == true
                    if (task.directWebSeedUrl.isNullOrBlank() && fromArchive) {
                        val largestFile = metadata.files
                            .filter {
                                !it.relativePath.endsWith(".txt", true) &&
                                    !it.relativePath.endsWith(".xml", true) &&
                                    !it.relativePath.endsWith(".sqlite", true) &&
                                    !it.relativePath.endsWith(".torrent", true)
                            }
                            .maxByOrNull { it.length }
                            ?: metadata.files.firstOrNull()

                        if (largestFile != null) {
                            val relClean = largestFile.relativePath.removePrefix("${metadata.name}/").trimStart('/')
                            task.directWebSeedUrl = "https://archive.org/download/${metadata.name}/$relClean"
                            task.outputFile = File(getDownloadDirectory(), sanitizeFileName(relClean.substringAfterLast('/')))
                            task.totalBytes = largestFile.length
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Query Trackers
            val discoveredPeers = Collections.synchronizedSet(linkedSetOf<PeerEndpoint>())
            if (metadata != null) {
                task.statusDetail = "Опрос трекеров (${metadata.trackers.size} шт.)$vpnBadge..."
                for (tr in metadata.trackers.take(5)) {
                    if (task.cancelled.get() || task.paused.get()) break
                    val peers = announceTracker(tr, metadata.infoHash, metadata.totalSize)
                    discoveredPeers.addAll(peers)
                    task.knownPeers = discoveredPeers.size
                }
            } else if (!task.magnetUri.isNullOrBlank()) {
                val mag = BitTorrentParser.parseMagnetUri(task.magnetUri)
                for (tr in mag.trackers.take(4)) {
                    val peers = announceTracker(tr, mag.infoHash, mag.exactLength.coerceAtLeast(10_485_760L))
                    discoveredPeers.addAll(peers)
                    task.knownPeers = discoveredPeers.size
                }
            }

            // v4.38.0: трекеры из .torrent часто знают только часть роя. Если пиров ноль — сначала
            // спрашиваем ещё полтора десятка публичных трекеров, и только потом идём искать замену.
            if (metadata != null && discoveredPeers.isEmpty() && !task.cancelled.get() && !task.paused.get()) {
                task.statusDetail = "Пиров 0 — спрашиваю дополнительные трекеры…$vpnBadge"
                val extra = TorrentRescue.EXTRA_TRACKERS.filter { it !in metadata.trackers }
                for (tr in extra.take(10)) {
                    if (task.cancelled.get() || task.paused.get()) break
                    val peers = announceTracker(tr, metadata.infoHash, metadata.totalSize)
                    discoveredPeers.addAll(peers)
                    task.knownPeers = discoveredPeers.size
                    if (discoveredPeers.isNotEmpty()) break
                }
                com.winwasm64.vpn.ConnTrace.torrent(
                    "доп. трекеры (${extra.size})",
                    if (discoveredPeers.isEmpty()) "пиров не добавили" else "нашли ${discoveredPeers.size} пиров",
                    discoveredPeers.isNotEmpty()
                )
            }

            if (task.cancelled.get() || task.paused.get()) return
            task.state = TorrentState.DOWNLOADING

            var downloadedViaPeers = false
            // v4.18.0: P2P пробуем ВСЕГДА, когда трекеры нашли пиров, даже если есть адрес WebSeed.
            // Раньше при наличии directWebSeedUrl пиры игнорировались полностью — и раздача
            // «качалась» через HTTP-ссылку, которую мы же и придумали (отсюда «HTTP WebSeed код 503»).
            if (metadata != null && discoveredPeers.isNotEmpty()) {
                task.statusDetail = "Подключение к ${discoveredPeers.size} реально найденным пирам по TCP BitTorrent$vpnBadge..."
                com.winwasm64.vpn.ConnTrace.torrent(
                    "${discoveredPeers.size} пиров",
                    if (com.winwasm64.vpn.TorrentTunnel.isAvailable()) "попытка P2P по TCP · через канал Browsec" else "попытка P2P по TCP · напрямую (канал не поднят)",
                    true
                )
                downloadedViaPeers = downloadPiecesFromPeers(task, metadata, discoveredPeers.toList())
                if (!downloadedViaPeers) {
                    com.winwasm64.vpn.ConnTrace.torrent("${discoveredPeers.size} пиров", "P2P не отдал ни одного куска", false, "пиры не отвечают (закрыт TCP, DHT без UDP)")
                }
            }

            val seedUrl = task.directWebSeedUrl ?: task.torrentUrl
            if (!downloadedViaPeers && !seedUrl.isNullOrBlank()) {
                // v4.18.0: 503/429 и обрывы — это «сервер занят», а не «файла нет». Повторяем до 3 раз
                // с паузой, показывая состояние в карточке, вместо мгновенной ошибки.
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        downloadViaWebSeedStream(task, seedUrl, metadata)
                        lastError = null
                        break
                    } catch (e: Exception) {
                        lastError = e
                        val msg = e.message ?: ""
                        val retryable = msg.contains("503") || msg.contains("429") || msg.contains("timed out") ||
                            msg.contains("timeout") || msg.contains("Unable to resolve host") || msg.contains("Connection reset")
                        com.winwasm64.vpn.ConnTrace.torrent(
                            seedUrl.take(120),
                            if (attempt < 3 && retryable) "WebSeed: повтор $attempt из 3" else "WebSeed не отдал файл",
                            ok = false,
                            note = msg.take(120)
                        )
                        if (!retryable || attempt == 3 || task.cancelled.get() || task.paused.get()) break
                        task.statusDetail = "Сервер отдал «$msg» — повтор $attempt из 3 через ${attempt * 2} с…"
                        try { Thread.sleep(1_000L * attempt * 2) } catch (_: InterruptedException) { break }
                    }
                }
                if (lastError != null) {
                    val e = lastError!!
                    // Fallback: если ссылка на файл не сработала — пробуем исходный .torrent/URL
                    if (!task.torrentUrl.isNullOrBlank() && task.torrentUrl != seedUrl) {
                        task.outputFile = File(getDownloadDirectory(), sanitizeFileName(task.initialName + ".torrent"))
                        downloadViaWebSeedStream(task, task.torrentUrl, metadata)
                    } else {
                        throw e
                    }
                }
            } else if (!downloadedViaPeers) {
                // v4.38.0: РАНЬШЕ здесь .torrent-файл записывался как результат и задача объявлялась
                // выполненной — отсюда «скачано 2,7 КБ из 2,7 КБ, 100%». Это не содержимое раздачи.
                // Теперь: пиров нет → автопоиск живой копии, иначе честная ошибка.
                if (attemptRescue(task, metadata)) return
                task.state = TorrentState.ERROR
                task.statusDetail = if (task.suggestions.isEmpty()) {
                    "Пиров 0: содержимого нет, а .torrent-файл вместо раздачи не сохраняем. " +
                        "Замены в проверенных каталогах не нашлось — попробуйте другой источник или magnet с сидами."
                } else {
                    "Пиров 0. Точной замены нет, но есть похожие с пирами — выберите из списка ниже."
                }
            }
        } catch (e: Exception) {
            // v4.38.0: пустышку не оставляем. Если ничего не пришло — ищем ту же раздачу там, где есть сиды.
            if (autoRescue && task.downloadedBytes.get() == 0L && attemptRescue(task, null)) {
                return
            }
            task.state = TorrentState.ERROR
            task.statusDetail = "Ошибка: ${e.message ?: "Сбой соединения"}"
        } finally {
            task.running.set(false)
        }
    }

    /**
     * v4.38.0: «раздача мертва → найти такую же живую».
     *
     * true — замена запущена (текущая задача остаётся на паузе с честной подписью).
     * false — не нашлось; вызывающий сам решает, что показать. Успех не выдумываем.
     */
    private fun attemptRescue(task: ActiveTorrentTask, metadata: TorrentMetadata?): Boolean {
        if (!autoRescue) return false
        if (!task.rescueTried.compareAndSet(false, true)) return false
        val expectedName = if (metadata != null && metadata.name.isNotBlank()) metadata.name else task.initialName
        val expectedBytes = if (metadata != null && metadata.totalSize > 0) metadata.totalSize else task.totalBytes
        val query = TorrentRescue.cleanQuery(expectedName)
        if (query.isBlank()) return false

        task.statusDetail = "Пиров 0 · ищу такую же раздачу в других источниках: «$query»…"
        com.winwasm64.vpn.ConnTrace.torrent("автозамена", "ищу «$query» (0 пиров)", true)

        // v4.39.0: ищем агентом — catorrent (тот сайт, откуда берутся .torrent) плюс открытые индексы,
        // с ранжированием по имени/сидам/размеру/формату и объяснением выбора.
        val intent = TorrentSearchAgent.parseIntent(expectedName).copy(title = query)
        val found = try {
            TorrentSearchAgent.searchAll(query)
        } catch (_: Throwable) {
            emptyList()
        }
        val ranked = TorrentSearchAgent.rank(found, intent, expectedName, expectedBytes)
        val best = TorrentSearchAgent.bestForAutoStart(ranked, intent, expectedName, expectedBytes)
        if (best == null) {
            val analogs = TorrentSearchAgent.analogs(ranked, expectedName)
            task.suggestions = analogs
            com.winwasm64.vpn.ConnTrace.torrent(
                "автозамена",
                "точного совпадения нет: проверено ${found.size} вариантов" +
                    (if (analogs.isNotEmpty()) ", предложено аналогов: ${analogs.size}" else ""),
                ok = false,
                note = "у остальных либо 0 сидов, либо другое название/размер — пустышку не качаем"
            )
            return false
        }

        com.winwasm64.vpn.ConnTrace.torrent(
            "автозамена",
            "выбрано: ${best.name.take(60)} (${best.reason})",
            true
        )
        task.paused.set(true)
        task.state = TorrentState.PAUSED
        task.statusDetail = "Пиров 0 — эта раздача мертва. Найдена замена: ${best.name.take(50)} " +
            "(${best.sizeLabel}, ${best.reason}) — запускаю её."
        try {
            startFromFound(best)
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    private fun downloadViaWebSeedStream(
        task: ActiveTorrentTask,
        seedUrl: String,
        metadata: TorrentMetadata?
    ) {
        val vpnBadge = if (useBrowsecVpn) " • Browsec $browsecCountryCode" else ""
        task.statusDetail = "Канал WebSeed (HTTP) активен$vpnBadge..."
        // v4.18.0: убраны «нарисованные» пиры. Раньше здесь присваивалось
        // connectedPeers = max(5) и knownPeers = max(19) — числа брались из воздуха, и по ним
        // нельзя было понять, работает P2P или нет. Показываем только то, что реально известно.

        // v4.35.0: ожидаемый размер знает .torrent (или WebSeed-запись), и его нельзя терять.
        // Из-за этого потерянного размера раздача «Elliot Quest» весом ~200 МБ «скачалась» за 2,7 КБ
        // и была помечена 100%: сервер отдал не файл, а служебную страницу.
        // для многофайловых раздач один WebSeed может отдавать один файл — тогда строгую сверку не делаем
        val strictSize = metadata == null || metadata.files.size <= 1
        val expectedBytes = if (!strictSize) 0L else (task.totalBytes.takeIf { it > 0L } ?: metadata?.totalSize ?: 0L)
        val conn = (URL(seedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            applyBrowsecHeaders(this)
            if (task.outputFile.exists() && task.downloadedBytes.get() > 0L) {
                setRequestProperty("Range", "bytes=${task.downloadedBytes.get()}-")
            }
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            com.winwasm64.vpn.ConnTrace.torrent(seedUrl.take(120), "WebSeed ответил HTTP $code", false, if (code == 503 || code == 429) "сервер занят/лимит — повторяем" else "недоступно")
            throw IllegalStateException("HTTP WebSeed код $code")
        }
        val contentType = (conn.contentType ?: "").lowercase()
        val contentLengthRaw = conn.contentLengthLong

        // v4.35.0: сервер отдал страницу/ошибку вместо файла — это не «скачано», это провал
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
            com.winwasm64.vpn.ConnTrace.torrent(
                seedUrl.take(120),
                "WebSeed отдал не файл ($contentType)",
                false,
                "сервер ответил страницей вместо содержимого раздачи"
            )
            throw IllegalStateException("сервер отдал $contentType вместо файла раздачи")
        }
        if (expectedBytes > 0L && contentLengthRaw > 0L && contentLengthRaw < expectedBytes) {
            com.winwasm64.vpn.ConnTrace.torrent(
                seedUrl.take(120),
                "WebSeed отдал ${formatSize(contentLengthRaw)} вместо ${formatSize(expectedBytes)}",
                false,
                "объём не совпал с .torrent — это не файл раздачи"
            )
            throw IllegalStateException("сервер отдал ${formatSize(contentLengthRaw)} вместо ${formatSize(expectedBytes)}")
        }
        com.winwasm64.vpn.ConnTrace.torrent(seedUrl.take(120), "WebSeed отвечает, качаем по HTTP", true)

        val contentLength = contentLengthRaw
        val resumeOffset = if (code == 206) task.downloadedBytes.get() else 0L
        if (contentLength > 0) {
            task.totalBytes = resumeOffset + contentLength
        } else if (task.totalBytes <= 0L) {
            task.totalBytes = 2_097_152L
        }

        if (task.fileList.isEmpty()) {
            task.fileList.add("${task.outputFile.name} (${formatSize(task.totalBytes)})")
        }

        RandomAccessFile(task.outputFile, "rw").use { raf ->
            raf.seek(resumeOffset)
            val buf = ByteArray(32 * 1024)
            var bytesInWindow = 0L
            var windowStart = System.currentTimeMillis()
            val sha1 = MessageDigest.getInstance("SHA-1")

            conn.inputStream.use { input ->
                while (!task.cancelled.get() && !task.paused.get()) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    raf.write(buf, 0, read)
                    sha1.update(buf, 0, read)
                    val totalDone = task.downloadedBytes.addAndGet(read.toLong())
                    bytesInWindow += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    if (elapsed >= 350L) {
                        task.speedBytesPerSec = (bytesInWindow * 1000L) / elapsed
                        bytesInWindow = 0L
                        windowStart = now
                    }

                    if (task.totalBytes > 0L) {
                        val ratio = (totalDone.toDouble() / task.totalBytes.toDouble()).coerceIn(0.0, 1.0)
                        val donePieces = (ratio * task.totalPieces).toInt().coerceIn(0, task.totalPieces)
                        task.verifiedPieces = donePieces
                        for (i in 0 until donePieces) {
                            task.pieceBitfield[i] = true
                        }
                        task.statusDetail = "Скачано ${formatSize(totalDone)} из ${formatSize(task.totalBytes)}$vpnBadge • Кусков: $donePieces/${task.totalPieces}"
                    }
                }
            }
        }

        if (!task.cancelled.get() && !task.paused.get()) {
            val publishedFile = publishToPublicDownloads(task.outputFile)
            task.outputFile = publishedFile
            val finalSize = publishedFile.length()
            // v4.35.0: главная защита от «100% за 2,7 КБ». Файл меньше ожидаемого — это не успех.
            if (expectedBytes > 0L && finalSize < expectedBytes) {
                task.speedBytesPerSec = 0L
                task.downloadedBytes.set(finalSize)
                task.state = TorrentState.ERROR
                task.statusDetail = "Ошибка: получено ${formatSize(finalSize)} из ${formatSize(expectedBytes)} — " +
                    "сервер отдал не тот файл. Файл удалён, попробуйте другой WebSeed или дождитесь пиров."
                try { publishedFile.delete() } catch (_: Exception) {}
                com.winwasm64.vpn.ConnTrace.torrent(
                    seedUrl.take(120),
                    "загрузка неполная: ${formatSize(finalSize)} из ${formatSize(expectedBytes)}",
                    false,
                    "сервер отдал не файл раздачи"
                )
                return
            }
            task.downloadedBytes.set(finalSize)
            task.totalBytes = finalSize
            task.speedBytesPerSec = 0L
            for (i in task.pieceBitfield.indices) task.pieceBitfield[i] = true
            task.verifiedPieces = task.totalPieces
            task.state = TorrentState.COMPLETED
            val fileHash = if (metadata != null) metadata.infoHashHex.take(12) else task.infoHashHex.take(12)
            task.statusDetail = "✓ Скачано в /Download/OmniTorrents/${publishedFile.name} (${formatSize(finalSize)}) • SHA-1 [$fileHash]"
        }
    }

    private fun downloadPiecesFromPeers(
        task: ActiveTorrentTask,
        meta: TorrentMetadata,
        peers: List<PeerEndpoint>
    ): Boolean {
        var anyPieceDownloaded = false
        for (peer in peers.take(8)) {
            if (task.cancelled.get() || task.paused.get()) break
            try {
                // v4.31.0: сначала канал браузера (Browsec через локальный прокси), иначе напрямую.
                // Раньше прямой TCP к пирам в мобильной сети просто не открывался.
                val tunneled = com.winwasm64.vpn.TorrentTunnel.connect(peer.ip, peer.port)
                val peerSocket = tunneled ?: Socket().also { s ->
                    s.connect(InetSocketAddress(peer.ip, peer.port), 3500)
                    s.soTimeout = 5000
                }
                peerSocket.use { socket ->
                    val out = DataOutputStream(socket.getOutputStream())
                    val inp = DataInputStream(socket.getInputStream())

                    out.writeByte(19)
                    out.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
                    val reserved = ByteArray(8)
                    reserved[5] = 0x10
                    out.write(reserved)
                    out.write(meta.infoHash)
                    out.write(peerId)
                    out.flush()

                    val pstrlen = inp.readUnsignedByte()
                    if (pstrlen != 19) return@use
                    val proto = ByteArray(19)
                    inp.readFully(proto)
                    val peerReserved = ByteArray(8)
                    inp.readFully(peerReserved)
                    val peerInfoHash = ByteArray(20)
                    inp.readFully(peerInfoHash)
                    val remotePeerId = ByteArray(20)
                    inp.readFully(remotePeerId)

                    if (!peerInfoHash.contentEquals(meta.infoHash)) return@use
                    task.connectedPeers++

                    out.writeInt(1)
                    out.writeByte(2)
                    out.flush()

                    var unchoked = false
                    var requestedPiece = false
                    val pieceIndex = task.pieceBitfield.indexOfFirst { !it }.coerceAtLeast(0)
                    val expectedPieceLen = if (pieceIndex == meta.pieceCount - 1) {
                        (meta.totalSize - pieceIndex.toLong() * meta.pieceLength).toInt().coerceAtLeast(1)
                    } else {
                        meta.pieceLength
                    }
                    val blockLen = min(16384, expectedPieceLen)

                    for (msgTurn in 0 until 12) {
                        val len = inp.readInt()
                        if (len == 0) continue
                        if (len < 0 || len > 2 * 1024 * 1024) break
                        val msgId = inp.readUnsignedByte()
                        val payloadLen = len - 1
                        val payload = ByteArray(payloadLen)
                        if (payloadLen > 0) inp.readFully(payload)

                        when (msgId) {
                            1 -> {
                                unchoked = true
                                if (!requestedPiece) {
                                    out.writeInt(13)
                                    out.writeByte(6)
                                    out.writeInt(pieceIndex)
                                    out.writeInt(0)
                                    out.writeInt(blockLen)
                                    out.flush()
                                    requestedPiece = true
                                }
                            }
                            7 -> {
                                if (payload.size > 8) {
                                    val blockData = payload.copyOfRange(8, payload.size)
                                    RandomAccessFile(task.outputFile, "rw").use { raf ->
                                        raf.seek(pieceIndex.toLong() * meta.pieceLength)
                                        raf.write(blockData)
                                    }
                                    task.downloadedBytes.addAndGet(blockData.size.toLong())
                                    if (blockData.size == expectedPieceLen && pieceIndex < meta.pieceHashes.size) {
                                        val digest = BitTorrentParser.sha1(blockData)
                                        if (digest.contentEquals(meta.pieceHashes[pieceIndex])) {
                                            task.pieceBitfield[pieceIndex] = true
                                            task.verifiedPieces++
                                            anyPieceDownloaded = true
                                        }
                                    }
                                    break
                                }
                            }
                        }
                        if (unchoked && !requestedPiece) {
                            out.writeInt(13)
                            out.writeByte(6)
                            out.writeInt(pieceIndex)
                            out.writeInt(0)
                            out.writeInt(blockLen)
                            out.flush()
                            requestedPiece = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return anyPieceDownloaded && task.verifiedPieces >= task.totalPieces
    }

    fun announceTracker(trackerUrl: String, infoHash: ByteArray, leftBytes: Long): List<PeerEndpoint> {
        // v4.18.0: каждый трекер виден в журнале соединений — сколько пиров он реально дал.
        // Именно этого не хватало, чтобы понять «0 пиров»: трекер молчит, отвечает отказом
        // или пиры есть, но не подключаются.
        val proto = if (trackerUrl.startsWith("udp://", ignoreCase = true)) "UDP" else "HTTP"
        val short = trackerUrl.removePrefix("http://").removePrefix("https://").removePrefix("udp://").take(80)
        return try {
            val result = when {
                trackerUrl.startsWith("http://", ignoreCase = true) ||
                    trackerUrl.startsWith("https://", ignoreCase = true) ->
                    announceHttpTracker(trackerUrl, infoHash, leftBytes)
                trackerUrl.startsWith("udp://", ignoreCase = true) ->
                    announceUdpTracker(trackerUrl, infoHash, leftBytes)
                else -> emptyList()
            }
            com.winwasm64.vpn.ConnTrace.torrent(
                target = short,
                what = "трекер ($proto) → ${result.size} пиров",
                ok = result.isNotEmpty(),
                note = if (result.isEmpty()) "трекер не дал пиров (отказ, таймаут или раздача без сидов)" else ""
            )
            result
        } catch (e: Throwable) {
            com.winwasm64.vpn.ConnTrace.torrent(
                short,
                "трекер ($proto) не ответил",
                false,
                (e.message ?: e.javaClass.simpleName) +
                    if (proto == "UDP") " · UDP-трекеры в мобильных сетях обычно закрыты, остаются HTTP-трекеры" else
                        if (!com.winwasm64.vpn.TorrentTunnel.isAvailable()) " · канал браузера не поднят — шли напрямую" else ""
            )
            emptyList()
        }
    }

    private fun announceHttpTracker(trackerUrl: String, infoHash: ByteArray, leftBytes: Long): List<PeerEndpoint> {
        val sep = if (trackerUrl.contains("?")) "&" else "?"
        val encodedHash = BitTorrentParser.urlEncodeBytes(infoHash)
        val encodedPeerId = BitTorrentParser.urlEncodeBytes(peerId)
        val fullUrl = "${trackerUrl}${sep}info_hash=$encodedHash&peer_id=$encodedPeerId&port=6881&uploaded=0&downloaded=0&left=${leftBytes.coerceAtLeast(0L)}&compact=1&numwant=50"
        // v4.31.0: HTTP-трекер — через тот же канал, что и браузер (если он поднят).
        // Напрямую такие запросы в мобильной сети не проходят: трекер молча не отвечал.
        val trackerProxy = com.winwasm64.vpn.TorrentTunnel.proxyOrNull()
        val conn = (URL(fullUrl).openConnection(trackerProxy ?: java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            applyBrowsecHeaders(this)
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.use { it.readBytes() }
        val dict = BencodeCodec.decode(body) as? BencodeValue.BDict ?: return emptyList()
        val peersVal = dict.entries["peers"] ?: return emptyList()
        val peers = mutableListOf<PeerEndpoint>()
        when (peersVal) {
            is BencodeValue.BBytes -> {
                val b = peersVal.bytes
                var i = 0
                while (i + 6 <= b.size) {
                    val ip = "${b[i].toInt() and 0xFF}.${b[i + 1].toInt() and 0xFF}.${b[i + 2].toInt() and 0xFF}.${b[i + 3].toInt() and 0xFF}"
                    val port = ((b[i + 4].toInt() and 0xFF) shl 8) or (b[i + 5].toInt() and 0xFF)
                    if (port in 1..65535) peers.add(PeerEndpoint(ip, port))
                    i += 6
                }
            }
            is BencodeValue.BList -> {
                for (item in peersVal.values) {
                    val pDict = item as? BencodeValue.BDict ?: continue
                    val ip = (pDict.entries["ip"] as? BencodeValue.BBytes)?.asString() ?: continue
                    val port = ((pDict.entries["port"] as? BencodeValue.BInt)?.value ?: 0L).toInt()
                    if (port in 1..65535) peers.add(PeerEndpoint(ip, port))
                }
            }
            else -> {}
        }
        return peers
    }

    private fun announceUdpTracker(trackerUrl: String, infoHash: ByteArray, leftBytes: Long): List<PeerEndpoint> {
        val uri = URI(trackerUrl)
        val host = uri.host ?: return emptyList()
        val port = if (uri.port > 0) uri.port else 80
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val address = InetAddress.getByName(host)

            val txId1 = Random.nextInt()
            val connReq = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(0x41727101980L)
                putInt(0)
                putInt(txId1)
            }.array()
            socket.send(DatagramPacket(connReq, connReq.size, address, port))

            val connRespBuf = ByteArray(16)
            val connRespPkt = DatagramPacket(connRespBuf, connRespBuf.size)
            socket.receive(connRespPkt)
            val connResp = ByteBuffer.wrap(connRespBuf).order(ByteOrder.BIG_ENDIAN)
            val action1 = connResp.int
            val rxTx1 = connResp.int
            if (action1 != 0 || rxTx1 != txId1) return emptyList()
            val connectionId = connResp.long

            val txId2 = Random.nextInt()
            val annReq = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1)
                putInt(txId2)
                put(infoHash)
                put(peerId)
                putLong(0L)
                putLong(leftBytes.coerceAtLeast(16384L))
                putLong(0L)
                putInt(2)
                putInt(0)
                putInt(Random.nextInt())
                putInt(50)
                putShort(6881.toShort())
            }.array()
            socket.send(DatagramPacket(annReq, annReq.size, address, port))

            val annRespBuf = ByteArray(1024)
            val annRespPkt = DatagramPacket(annRespBuf, annRespBuf.size)
            socket.receive(annRespPkt)
            if (annRespPkt.length < 20) return emptyList()
            val annResp = ByteBuffer.wrap(annRespBuf, 0, annRespPkt.length).order(ByteOrder.BIG_ENDIAN)
            val action2 = annResp.int
            val rxTx2 = annResp.int
            if (action2 != 1 || rxTx2 != txId2) return emptyList()
            annResp.int
            annResp.int
            annResp.int

            val peers = mutableListOf<PeerEndpoint>()
            while (annResp.remaining() >= 6) {
                val b0 = annResp.get().toInt() and 0xFF
                val b1 = annResp.get().toInt() and 0xFF
                val b2 = annResp.get().toInt() and 0xFF
                val b3 = annResp.get().toInt() and 0xFF
                val pPort = annResp.short.toInt() and 0xFFFF
                if (pPort in 1..65535) {
                    peers.add(PeerEndpoint("$b0.$b1.$b2.$b3", pPort))
                }
            }
            return peers
        }
    }

    private fun fetchUrlBytes(url: String): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                applyBrowsecHeaders(this)
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return "0 Б"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f ГБ", gb)
                mb >= 1.0 -> String.format("%.2f МБ", mb)
                kb >= 1.0 -> String.format("%.1f КБ", kb)
                else -> "$bytes Б"
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "0 КБ/с"
            return "${formatSize(bytesPerSec)}/с"
        }
    }
}
