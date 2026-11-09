package com.winwasm64.torrent

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v4.42.0: получение метаданных magnet-раздачи НАПРЯМУЮ У ПИРОВ (BEP-9 / ut_metadata).
 *
 * Почему это важно: у результата из открытого каталога есть только magnet (infoHash + трекеры),
 * а `.torrent`-файла нет. Раньше мы пытались взять метаданные с двух кэшей (itorrents, watercache) —
 * и когда они недоступны, плеер писал «не удалось получить метаданные». Правильный путь — попросить
 * метаданные у самих пиров по расширению ut_metadata: ровно так делает любой торрент-клиент.
 *
 * Протокол коротко:
 *  1) рукопожатие с битом расширений (reserved[5] = 0x10);
 *  2) расширенное рукопожатие: `{"m": {"ut_metadata": 1}}`;
 *  3) пир отвечает своим `ut_metadata`-номером и размером метаданных;
 *  4) качаем метаданные кусками по 16 КБ (`{"msg_type":0,"piece":N}`);
 *  5) склеиваем, сверяем SHA-1 с infoHash — иначе это не та раздача.
 */
object MagnetMetadata {

    private const val EXT_ID = 20
    private const val PIECE_SIZE = 16 * 1024
    private const val OUR_UT_METADATA = 1

    /** Скачать метаданные у первого пира, который согласится. Возвращает разобранный .torrent. */
    fun fetch(
        infoHash: ByteArray,
        infoHashHex: String,
        peers: List<PeerEndpoint>,
        timeoutMs: Int = 25_000
    ): TorrentMetadata? {
        if (peers.isEmpty()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        for (peer in peers.take(12)) {
            if (System.currentTimeMillis() > deadline) break
            val raw = try {
                fetchFromPeer(peer, infoHash)
            } catch (_: Throwable) {
                null
            }
            if (raw != null && BitTorrentParser.sha1(raw).contentEquals(infoHash)) {
                com.winwasm64.vpn.ConnTrace.torrent(
                    "метаданные с пира ${peer.ip}:${peer.port}",
                    "получено ${raw.size} байт · sha1 совпал",
                    true
                )
                return try {
                    BitTorrentParser.parseTorrentBytes(wrapInfo(raw))
                } catch (_: Throwable) {
                    null
                }
            }
        }
        com.winwasm64.vpn.ConnTrace.torrent(
            "метаданные $infoHashHex",
            "ни один пир не отдал метаданные",
            ok = false,
            note = "раздача без .torrent: нужен хотя бы один сид с упиранием в ut_metadata"
        )
        return null
    }

    /** Обернуть словарь info в полноценный .torrent — так его разбирает наш парсер. */
    private fun wrapInfo(infoBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(infoBytes.size + 16)
        out.write("d4:info".toByteArray()); out.write(infoBytes); out.write('e'.code)
        return out.toByteArray()
    }

    private fun fetchFromPeer(peer: PeerEndpoint, infoHash: ByteArray): ByteArray? {
        val socket = com.winwasm64.vpn.TorrentTunnel.connect(peer.ip, peer.port, timeoutMs = 6000) ?: try {
            Socket().also { s ->
                s.connect(InetSocketAddress(peer.ip, peer.port), 6000)
                s.soTimeout = 8000
            }
        } catch (_: Throwable) {
            return null
        }
        socket.use { s ->
            s.soTimeout = 8000
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())

            out.writeByte(19)
            out.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
            val reserved = ByteArray(8)
            reserved[5] = 0x10 // extension protocol
            out.write(reserved)
            out.write(infoHash)
            out.write("OMNI-4200-METADATA-01".toByteArray(Charsets.US_ASCII).copyOf(20))
            out.flush()

            val pstrlen = inp.readUnsignedByte()
            if (pstrlen != 19) return null
            inp.readFully(ByteArray(19))
            val theirReserved = ByteArray(8)
            inp.readFully(theirReserved)
            if ((theirReserved[5].toInt() and 0x10) == 0) return null
            val theirHash = ByteArray(20)
            inp.readFully(theirHash)
            inp.readFully(ByteArray(20))
            if (!theirHash.contentEquals(infoHash)) return null

            // наше расширенное рукопожатие
            val hello = "d1:md11:ut_metadatai${OUR_UT_METADATA}eee"
            sendExtended(out, 0, hello.toByteArray(Charsets.US_ASCII))

            var theirUtMetadata = 0
            var metadataSize = 0
            val until = System.currentTimeMillis() + 6000
            while ((theirUtMetadata == 0 || metadataSize == 0) && System.currentTimeMillis() < until) {
                val msg = readMessage(inp) ?: return null
                if (msg.first == EXT_ID) {
                    val dict = decodeDict(msg.second) ?: continue
                    val m = dict.entries["m"] as? BencodeValue.BDict
                    theirUtMetadata = (m?.entries?.get("ut_metadata") as? BencodeValue.BInt)?.value?.toInt() ?: 0
                    metadataSize = (dict.entries["metadata_size"] as? BencodeValue.BInt)?.value?.toInt() ?: 0
                }
            }
            if (theirUtMetadata == 0 || metadataSize <= 0 || metadataSize > 32 * 1024 * 1024) return null

            val pieces = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
            val buffer = ByteArray(metadataSize)
            var received = 0
            for (piece in 0 until pieces) {
                sendExtended(out, theirUtMetadata, "d8:msg_typei0e5:piecei${piece}ee".toByteArray(Charsets.US_ASCII))
                var ok = false
                val pieceDeadline = System.currentTimeMillis() + 6000
                while (!ok && System.currentTimeMillis() < pieceDeadline) {
                    val msg = readMessage(inp) ?: return null
                    if (msg.first != EXT_ID) continue
                    val payload = msg.second
                    if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != OUR_UT_METADATA) continue
                    val body = payload.copyOfRange(1, payload.size)
                    val dict = decodeDict(body) ?: continue
                    val msgType = (dict.entries["msg_type"] as? BencodeValue.BInt)?.value?.toInt() ?: -1
                    val idx = (dict.entries["piece"] as? BencodeValue.BInt)?.value?.toInt() ?: -1
                    if (msgType != 1 || idx != piece) continue
                    val dataStart = dict.rawEnd
                    if (dataStart <= 0 || dataStart > body.size) continue
                    val data = body.copyOfRange(dataStart, body.size)
                    val offset = piece * PIECE_SIZE
                    val len = minOf(data.size, metadataSize - offset)
                    if (len <= 0) continue
                    System.arraycopy(data, 0, buffer, offset, len)
                    received += len
                    ok = true
                }
                if (!ok) return null
            }
            return if (received >= metadataSize) buffer else null
        }
    }

    /** Сообщение: [длина][20][номер расширения][payload]. */
    private fun sendExtended(out: DataOutputStream, extId: Int, payload: ByteArray) {
        out.writeInt(payload.size + 2)
        out.writeByte(EXT_ID)
        out.writeByte(extId)
        out.write(payload)
        out.flush()
    }

    /** Прочитать одно сообщение целиком: (id, тело). */
    private fun readMessage(inp: DataInputStream): Pair<Int, ByteArray>? {
        return try {
            val len = inp.readInt()
            if (len <= 0 || len > 2 * 1024 * 1024) return null
            val id = inp.readUnsignedByte()
            val body = ByteArray(len - 1)
            if (body.isNotEmpty()) inp.readFully(body)
            id to body
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeDict(data: ByteArray): BencodeValue.BDict? =
        try {
            BencodeCodec.decode(data) as? BencodeValue.BDict
        } catch (_: Throwable) {
            null
        }
}
