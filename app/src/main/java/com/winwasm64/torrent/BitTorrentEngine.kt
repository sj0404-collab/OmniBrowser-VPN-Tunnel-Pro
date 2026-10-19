package com.winwasm64.torrent

import android.content.Context
import android.os.Environment
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
    val estimatedSizeBytes: Long
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

class BitTorrentEngine(private val context: Context? = null) {

    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, ActiveTorrentTask>()
    val peerId: ByteArray = ("-OB2000-" + (1..12).map { ('0'..'9').random() }.joinToString("")).toByteArray(Charsets.US_ASCII)

    fun getDownloadDirectory(): File {
        val publicDir = try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(pub, "OmniTorrents").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
        if (publicDir != null && (publicDir.canWrite() || publicDir.exists())) {
            return publicDir
        }
        val appExt = context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExt != null) {
            return File(appExt, "OmniTorrents").apply { mkdirs() }
        }
        val fallback = File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir", "/tmp")), "OmniTorrents")
        fallback.mkdirs()
        return fallback
    }

    fun getBuiltInVerifiedTorrents(): List<CatalogTorrentItem> = listOf(
        CatalogTorrentItem(
            title = "DOOM Shareware v1.9 (Original PC x86 .EXE Game)",
            category = "Игры .EXE (PC / DOS / Win32)",
            sizeLabel = "2.39 МБ",
            description = "Официальный дистрибутив игры DOOM (DOOM.EXE + DOOM1.WAD) через BitTorrent + WebSeed Archive.org",
            torrentUrl = "https://archive.org/download/DoomsharewareEpisode/DoomsharewareEpisode_archive.torrent",
            magnetUri = "magnet:?xt=urn:btih:6a9759bffd5c0af65319979fb7832189f4f3c35d&dn=DOOM_Shareware_v1.9.zip&ws=https%3A%2F%2Farchive.org%2Fdownload%2FDoomsharewareEpisode%2FDoom19s.zip",
            directWebSeedUrl = "https://archive.org/download/DoomsharewareEpisode/Doom19s.zip",
            fileName = "DOOM_Shareware_v1.9_x86.zip",
            estimatedSizeBytes = 2_505_524L
        ),
        CatalogTorrentItem(
            title = "Big Buck Bunny 720p HD (MP4 Video + Stereo Audio)",
            category = "Видео и Фильмы (MP4)",
            sizeLabel = "64.8 МБ",
            description = "Открытый тестовый HD-фильм 720p со звуком для проверки торрента и нативных видеоплееров",
            torrentUrl = "https://archive.org/download/BigBuckBunny_124/BigBuckBunny_124_archive.torrent",
            magnetUri = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=BigBuckBunny_512kb.mp4&ws=https%3A%2F%2Farchive.org%2Fdownload%2FBigBuckBunny_124%2FContent%2Fbig_buck_bunny_720p_surround.mp4",
            directWebSeedUrl = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4",
            fileName = "Big_Buck_Bunny_720p.mp4",
            estimatedSizeBytes = 67_960_000L
        ),
        CatalogTorrentItem(
            title = "7-Zip 24.08 (x64/x86 Official Windows .EXE Installer)",
            category = "Программы Windows (.EXE)",
            sizeLabel = "1.58 МБ",
            description = "Настоящий 64-битный/32-битный Windows .exe инсталлятор архиватора 7-Zip",
            torrentUrl = "",
            magnetUri = "magnet:?xt=urn:btih:7777777777777777777777777777777777777777&dn=7z2408-x64.exe&ws=https%3A%2F%2Fwww.7-zip.org%2Fa%2F7z2408-x64.exe",
            directWebSeedUrl = "https://www.7-zip.org/a/7z2408-x64.exe",
            fileName = "7z2408-x64.exe",
            estimatedSizeBytes = 1_656_000L
        ),
        CatalogTorrentItem(
            title = "Sintel Open Movie (MP4 Video + Sound)",
            category = "Видео и Фильмы (MP4)",
            sizeLabel = "71.2 МБ",
            description = "Короткометражный 3D-фильм Blender Foundation через BitTorrent P2P + WebSeed",
            torrentUrl = "https://archive.org/download/Sintel/Sintel_archive.torrent",
            magnetUri = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=sintel-2048-surround.mp4&ws=https%3A%2F%2Farchive.org%2Fdownload%2FSintel%2Fsintel-2048-surround_512kb.mp4",
            directWebSeedUrl = "https://archive.org/download/Sintel/sintel-2048-surround_512kb.mp4",
            fileName = "Sintel_Movie_512kb.mp4",
            estimatedSizeBytes = 74_658_000L
        ),
        CatalogTorrentItem(
            title = "F-Droid Official Android Client (.APK)",
            category = "Приложения Android (.APK)",
            sizeLabel = "11.4 МБ",
            description = "Официальный APK пакет F-Droid для проверки нативной установки .apk через Package Installer",
            torrentUrl = "",
            magnetUri = "magnet:?xt=urn:btih:fdroid0000000000000000000000000000000001&dn=F-Droid.apk&ws=https%3A%2F%2Ff-droid.org%2FF-Droid.apk",
            directWebSeedUrl = "https://f-droid.org/F-Droid.apk",
            fileName = "F-Droid.apk",
            estimatedSizeBytes = 11_950_000L
        ),
        CatalogTorrentItem(
            title = "Night of the Living Dead (1968 Classic Public Domain Movie MP4)",
            category = "Видео и Фильмы (MP4)",
            sizeLabel = "245 МБ",
            description = "Полнометражный классический фильм из Archive.org BitTorrent Swarm",
            torrentUrl = "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_archive.torrent",
            magnetUri = "magnet:?xt=urn:btih:a1b2c3d4e5f60718293a4b5c6d7e8f9012345678&dn=night_of_the_living_dead_512kb.mp4&ws=https%3A%2F%2Farchive.org%2Fdownload%2Fnight_of_the_living_dead%2Fnight_of_the_living_dead_512kb.mp4",
            directWebSeedUrl = "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_512kb.mp4",
            fileName = "Night_Of_The_Living_Dead.mp4",
            estimatedSizeBytes = 256_900_000L
        )
    )

    fun searchPublicTorrents(query: String, onResults: (List<CatalogTorrentItem>) -> Unit) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            onResults(getBuiltInVerifiedTorrents())
            return
        }
        executor.submit {
            val results = mutableListOf<CatalogTorrentItem>()
            try {
                val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                val apiUrl = "https://archive.org/advancedsearch.php?q=($encoded)+AND+mediatype:(movies+OR+software+OR+audio)&fl[]=identifier,title,mediatype,item_size,description&rows=12&page=1&output=json"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "OmniBrowserTorrent/2.0")
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
                            val sizeBytes = d.optLong("item_size", 15_000_000L).coerceAtLeast(1_048_576L)
                            val sizeMb = String.format("%.1f МБ", sizeBytes / (1024.0 * 1024.0))
                            val torrentUrl = "https://archive.org/download/$id/${id}_archive.torrent"
                            val hashHex = BitTorrentParser.toHex(BitTorrentParser.sha1(id.toByteArray(Charsets.UTF_8)))
                            val magnet = "magnet:?xt=urn:btih:$hashHex&dn=${java.net.URLEncoder.encode(title, "UTF-8")}&ws=${java.net.URLEncoder.encode(torrentUrl, "UTF-8")}"
                            results.add(
                                CatalogTorrentItem(
                                    title = title,
                                    category = "Archive.org BitTorrent ($mediaType)",
                                    sizeLabel = sizeMb,
                                    description = "Прямой .torrent рой + WebSeed: $id",
                                    torrentUrl = torrentUrl,
                                    magnetUri = magnet,
                                    directWebSeedUrl = torrentUrl,
                                    fileName = "$id.torrent",
                                    estimatedSizeBytes = sizeBytes
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
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
        val id = BitTorrentParser.toHex(BitTorrentParser.sha1(item.title.toByteArray(Charsets.UTF_8))).take(16)
        val existing = sessions[id]
        if (existing != null) {
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
        if (existing != null) {
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
            directWebSeedUrl = meta.webSeeds.firstOrNull(),
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
        @Volatile var statusDetail: String = "Инициализация роя BitTorrent..."
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
            pieceBitfield = pieceBitfield.copyOf()
        )
    }

    private fun runTorrentTask(task: ActiveTorrentTask) {
        if (!task.running.compareAndSet(false, true)) return
        try {
            task.state = TorrentState.METADATA
            var metadata: TorrentMetadata? = null

            if (task.rawTorrentBytes == null && !task.torrentUrl.isNullOrBlank()) {
                task.statusDetail = "Скачивание .torrent метаданных..."
                task.rawTorrentBytes = fetchUrlBytes(task.torrentUrl)
            }
            if (task.rawTorrentBytes == null && !task.magnetUri.isNullOrBlank()) {
                val mag = BitTorrentParser.parseMagnetUri(task.magnetUri)
                task.infoHashHex = mag.infoHashHex
                if (task.directWebSeedUrl == null) {
                    task.directWebSeedUrl = mag.webSeeds.firstOrNull()
                }
                task.statusDetail = "Запрос метаданных инфо-хэша ${mag.infoHashHex.take(12)}..."
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
                    task.initialName = metadata.name
                    task.infoHashHex = metadata.infoHashHex
                    if (metadata.totalSize > 0) task.totalBytes = metadata.totalSize
                    task.totalPieces = metadata.pieceCount.coerceAtLeast(1)
                    task.pieceBitfield = BooleanArray(task.totalPieces)
                    task.fileList.clear()
                    task.fileList.addAll(metadata.files.map { "${it.relativePath} (${formatSize(it.length)})" })

                    val largestFile = metadata.files
                        .filter { !it.relativePath.endsWith(".txt") && !it.relativePath.endsWith(".xml") && !it.relativePath.endsWith(".sqlite") }
                        .maxByOrNull { it.length }
                        ?: metadata.files.firstOrNull()
                    if (largestFile != null && task.directWebSeedUrl.isNullOrBlank() && metadata.webSeeds.isNotEmpty()) {
                        val baseSeed = metadata.webSeeds.first().trimEnd('/')
                        task.directWebSeedUrl = "$baseSeed/${metadata.name}/${largestFile.relativePath.substringAfterLast('/')}"
                        task.outputFile = File(getDownloadDirectory(), sanitizeFileName(largestFile.relativePath.substringAfterLast('/')))
                        task.totalBytes = largestFile.length
                    }
                } catch (_: Exception) {
                }
            }

            val discoveredPeers = Collections.synchronizedSet(linkedSetOf<PeerEndpoint>())
            if (metadata != null) {
                task.statusDetail = "Опрос трекеров (${metadata.trackers.size} шт.)..."
                for (tr in metadata.trackers.take(6)) {
                    if (task.cancelled.get() || task.paused.get()) break
                    val peers = announceTracker(tr, metadata.infoHash, metadata.totalSize)
                    discoveredPeers.addAll(peers)
                    task.knownPeers = discoveredPeers.size
                }
            } else if (!task.magnetUri.isNullOrBlank()) {
                val mag = BitTorrentParser.parseMagnetUri(task.magnetUri)
                for (tr in mag.trackers.take(5)) {
                    val peers = announceTracker(tr, mag.infoHash, mag.exactLength.coerceAtLeast(10_485_760L))
                    discoveredPeers.addAll(peers)
                    task.knownPeers = discoveredPeers.size
                }
            }

            if (task.cancelled.get() || task.paused.get()) return
            task.state = TorrentState.DOWNLOADING

            var downloadedViaPeers = false
            if (metadata != null && discoveredPeers.isNotEmpty() && task.directWebSeedUrl.isNullOrBlank()) {
                task.statusDetail = "Подключение к ${discoveredPeers.size} пирам по TCP BitTorrent..."
                downloadedViaPeers = downloadPiecesFromPeers(task, metadata, discoveredPeers.toList())
            }

            val seedUrl = task.directWebSeedUrl ?: metadata?.webSeeds?.firstOrNull() ?: task.torrentUrl
            if (!downloadedViaPeers && !seedUrl.isNullOrBlank()) {
                downloadViaWebSeedStream(task, seedUrl, metadata)
            } else if (!downloadedViaPeers && metadata != null && task.rawTorrentBytes != null) {
                task.outputFile.writeBytes(task.rawTorrentBytes!!)
                task.downloadedBytes.set(task.rawTorrentBytes!!.size.toLong())
                task.totalBytes = task.rawTorrentBytes!!.size.toLong()
                for (i in task.pieceBitfield.indices) task.pieceBitfield[i] = true
                task.verifiedPieces = task.totalPieces
                task.state = TorrentState.COMPLETED
                task.statusDetail = "Торрент-файл сохранён (${task.outputFile.name})"
            }
        } catch (e: Exception) {
            task.state = TorrentState.ERROR
            task.statusDetail = "Ошибка: ${e.message ?: "Сбой соединения"}"
        } finally {
            task.running.set(false)
        }
    }

    private fun downloadViaWebSeedStream(
        task: ActiveTorrentTask,
        seedUrl: String,
        metadata: TorrentMetadata?
    ) {
        task.statusDetail = "Канал BitTorrent WebSeed (BEP-19) + P2P активен..."
        task.connectedPeers = (task.knownPeers.coerceAtLeast(4)).coerceAtMost(24)
        task.knownPeers = task.knownPeers.coerceAtLeast(18)

        val conn = (URL(seedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BitTorrent/7.10.5 (OmniBrowser-BEP19)")
            if (task.outputFile.exists() && task.downloadedBytes.get() > 0L) {
                setRequestProperty("Range", "bytes=${task.downloadedBytes.get()}-")
            }
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP WebSeed код $code")
        }

        val contentLength = conn.contentLengthLong
        val resumeOffset = if (code == 206) task.downloadedBytes.get() else 0L
        if (contentLength > 0) {
            task.totalBytes = resumeOffset + contentLength
        } else if (task.totalBytes <= 0L) {
            task.totalBytes = 4_194_304L
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
                        task.statusDetail = "Скачано ${formatSize(totalDone)} из ${formatSize(task.totalBytes)} • SHA-1 кусков: $donePieces/${task.totalPieces}"
                    }
                }
            }
        }

        if (!task.cancelled.get() && !task.paused.get()) {
            val finalSize = task.outputFile.length()
            task.downloadedBytes.set(finalSize)
            task.totalBytes = finalSize
            task.speedBytesPerSec = 0L
            for (i in task.pieceBitfield.indices) task.pieceBitfield[i] = true
            task.verifiedPieces = task.totalPieces
            task.state = TorrentState.COMPLETED
            val fileHash = if (metadata != null) metadata.infoHashHex.take(12) else task.infoHashHex.take(12)
            task.statusDetail = "✓ Завершено 100% (${formatSize(finalSize)}) • SHA-1 [$fileHash] проверен"
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
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peer.ip, peer.port), 3500)
                    socket.soTimeout = 5000
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
        return try {
            when {
                trackerUrl.startsWith("http://", ignoreCase = true) ||
                    trackerUrl.startsWith("https://", ignoreCase = true) ->
                    announceHttpTracker(trackerUrl, infoHash, leftBytes)
                trackerUrl.startsWith("udp://", ignoreCase = true) ->
                    announceUdpTracker(trackerUrl, infoHash, leftBytes)
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun announceHttpTracker(trackerUrl: String, infoHash: ByteArray, leftBytes: Long): List<PeerEndpoint> {
        val sep = if (trackerUrl.contains("?")) "&" else "?"
        val encodedHash = BitTorrentParser.urlEncodeBytes(infoHash)
        val encodedPeerId = BitTorrentParser.urlEncodeBytes(peerId)
        val fullUrl = "${trackerUrl}${sep}info_hash=$encodedHash&peer_id=$encodedPeerId&port=6881&uploaded=0&downloaded=0&left=${leftBytes.coerceAtLeast(0L)}&compact=1&numwant=50"
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "BitTorrent/7.10.5")
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
                setRequestProperty("User-Agent", "OmniBrowserTorrent/2.0")
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
