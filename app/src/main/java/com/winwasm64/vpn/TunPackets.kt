package com.winwasm64.vpn

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Низкоуровневые операции с IP-пакетами (без Android-зависимостей — покрыто юнит-тестами).
 */
object TunPackets {
    /** 10.215.173.1 — адрес TUN (его видят приложения как свой IP). */
    const val TUN_IP: Int = 0x0AD7AD01

    /** 10.215.173.2 — DNS-сервер VPN. */
    const val DNS_IP: Int = 0x0AD7AD02

    /** 198.18.0.1 — адрес для самопроверки NAT (диапазон для тестов RFC 2544). */
    const val TEST_IP: Int = 0xC6120001.toInt()

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val ACK = 0x10

    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    fun u32(b: ByteArray, o: Int): Int = (u8(b, o) shl 24) or (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun sum16(b: ByteArray, off: Int, len: Int, initial: Long = 0L): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) s += ((b[i].toInt() and 0xFF) shl 8).toLong()
        return s
    }

    fun fold(s0: Long): Int {
        var s = s0
        while ((s ushr 16) != 0L) s = (s and 0xFFFFL) + (s ushr 16)
        return (s.inv() and 0xFFFFL).toInt()
    }

    fun fixIpv4Checksum(b: ByteArray, ihl: Int) {
        put16(b, 10, 0)
        put16(b, 10, fold(sum16(b, 0, ihl)))
    }

    /** Контрольная сумма TCP (proto 6) или UDP (proto 17) по псевдозаголовку IPv4. */
    fun fixL4ChecksumV4(b: ByteArray, ihl: Int, totalLen: Int, proto: Int) {
        val l4Len = totalLen - ihl
        val csumOff = ihl + if (proto == 6) 16 else 6
        put16(b, csumOff, 0)
        var s = sum16(b, 12, 8)
        s += proto.toLong()
        s += l4Len.toLong()
        s = sum16(b, ihl, l4Len, s)
        var c = fold(s)
        if (proto == 17 && c == 0) c = 0xFFFF
        put16(b, csumOff, c)
    }

    /** Проверка контрольной суммы TCP/UDP (для тестов). */
    fun l4ChecksumOk(b: ByteArray, ihl: Int, totalLen: Int, proto: Int): Boolean {
        val l4Len = totalLen - ihl
        var s = sum16(b, 12, 8)
        s += proto.toLong()
        s += l4Len.toLong()
        s = sum16(b, ihl, l4Len, s)
        return fold(s) == 0
    }

    fun ipv4ChecksumOk(b: ByteArray, ihl: Int): Boolean = fold(sum16(b, 0, ihl)) == 0

    private val ipId = AtomicInteger(Random.nextInt(0xFFFF))

    fun buildTcpV4(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, window: Int = 65535): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45
        put16(p, 2, 40)
        put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        put32(p, 12, srcIp)
        put32(p, 16, dstIp)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put32(p, 24, seq)
        put32(p, 28, ack)
        p[32] = 0x50
        p[33] = flags.toByte()
        put16(p, 34, window)
        fixIpv4Checksum(p, 20)
        fixL4ChecksumV4(p, 20, 40, 6)
        return p
    }

    /**
     * Полный TCP-сегмент IPv4: заголовок + опции (длина кратна 4) + данные. Используется userspace-стеком
     * прозрачного туннеля (SYN-ACK с MSS/wscale, сегменты данных, FIN, RST).
     */
    fun buildTcpSegmentV4(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        options: ByteArray? = null,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0
    ): ByteArray {
        val optLen = options?.size ?: 0
        require(optLen % 4 == 0 && optLen <= 40) { "bad tcp options length $optLen" }
        val hdrLen = 20 + optLen
        val total = 20 + hdrLen + payloadLen
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        put32(p, 12, srcIp)
        put32(p, 16, dstIp)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put32(p, 24, seq)
        put32(p, 28, ack)
        p[32] = ((hdrLen / 4) shl 4).toByte()
        p[33] = flags.toByte()
        put16(p, 34, window.coerceIn(0, 0xFFFF))
        if (optLen > 0) System.arraycopy(options!!, 0, p, 40, optLen)
        if (payloadLen > 0) System.arraycopy(payload!!, payloadOff, p, 20 + hdrLen, payloadLen)
        fixIpv4Checksum(p, 20)
        fixL4ChecksumV4(p, 20, total, 6)
        return p
    }

    /** Опции TCP для SYN-ACK: MSS + (NOP, window scale) — ровно 8 байт. */
    fun tcpSynAckOptions(mss: Int, wscale: Int?): ByteArray =
        if (wscale == null) byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte(), 1, 1, 1, 0)
        else byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte(), 1, 3, 3, wscale.toByte())

    /** Разбор опций TCP из SYN: (MSS или null, window scale или null). */
    fun parseTcpSynOptions(b: ByteArray, ihl: Int, total: Int): Pair<Int?, Int?> {
        var mss: Int? = null
        var ws: Int? = null
        val dataOff = (u8(b, ihl + 12) ushr 4) * 4
        var p = ihl + 20
        val end = minOf(total, ihl + dataOff)
        while (p < end) {
            val kind = u8(b, p)
            if (kind == 0) break
            if (kind == 1) { p++; continue }
            if (p + 1 >= end) break
            val l = u8(b, p + 1)
            if (l < 2 || p + l > end) break
            when (kind) {
                2 -> if (l == 4) mss = u16(b, p + 2)
                3 -> if (l == 3) ws = minOf(14, u8(b, p + 2))
            }
            p += l
        }
        return mss to ws
    }

    fun seqLt(a: Int, b: Int): Boolean = (a - b) < 0
    fun seqLe(a: Int, b: Int): Boolean = (a - b) <= 0

    fun buildUdpV4(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray, off: Int, len: Int): ByteArray {
        val total = 28 + len
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
        p[8] = 64
        p[9] = 17
        put32(p, 12, srcIp)
        put32(p, 16, dstIp)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + len)
        System.arraycopy(payload, off, p, 28, len)
        fixIpv4Checksum(p, 20)
        fixL4ChecksumV4(p, 20, total, 17)
        return p
    }

    /** ICMP «порт недоступен» в ответ на пакет [orig] (приложение мгновенно узнаёт, что QUIC недоступен). */
    fun buildIcmpPortUnreachable(orig: ByteArray, ihl: Int, total: Int): ByteArray {
        val quoted = minOf(total, ihl + 8)
        val icmpLen = 8 + quoted
        val p = ByteArray(20 + icmpLen)
        p[0] = 0x45
        put16(p, 2, p.size)
        put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
        p[8] = 64
        p[9] = 1
        put32(p, 12, u32(orig, 16))
        put32(p, 16, u32(orig, 12))
        p[20] = 3
        p[21] = 3
        System.arraycopy(orig, 0, p, 28, quoted)
        put16(p, 22, fold(sum16(p, 20, icmpLen)))
        fixIpv4Checksum(p, 20)
        return p
    }

    /** RST+ACK для IPv6 TCP SYN: приложение сразу переходит на IPv4 (который идёт через туннель). */
    fun buildTcpRstV6(orig: ByteArray, n: Int): ByteArray? {
        if (n < 60 || u8(orig, 6) != 6) return null
        val flags = u8(orig, 40 + 13)
        if ((flags and SYN) == 0 || (flags and ACK) != 0) return null
        val p = ByteArray(60)
        p[0] = 0x60
        put16(p, 4, 20)
        p[6] = 6
        p[7] = 64
        System.arraycopy(orig, 24, p, 8, 16)
        System.arraycopy(orig, 8, p, 24, 16)
        put16(p, 40, u16(orig, 42))
        put16(p, 42, u16(orig, 40))
        put32(p, 44, 0)
        put32(p, 48, u32(orig, 44) + 1)
        p[52] = 0x50
        p[53] = (RST or ACK).toByte()
        var s = sum16(p, 8, 32)
        s += 20L
        s += 6L
        s = sum16(p, 40, 20, s)
        put16(p, 56, fold(s))
        return p
    }

    fun ipToString(ip: Int): String = "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun ipToInet(ip: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    )

    fun inetToIp(a: InetAddress): Int {
        val b = a.address
        if (b.size != 4) return 0
        return u32(b, 0)
    }
}

/** Извлекает имя сайта (SNI) из TLS ClientHello. */
object TlsSni {
    fun extract(b: ByteArray, len: Int): String? {
        try {
            if (len < 48 || b[0] != 0x16.toByte() || b[5] != 0x01.toByte()) return null
            var p = 43
            p += 1 + (b[p].toInt() and 0xFF)
            if (p + 2 > len) return null
            p += 2 + TunPackets.u16(b, p)
            if (p + 1 > len) return null
            p += 1 + (b[p].toInt() and 0xFF)
            if (p + 2 > len) return null
            val extEnd = minOf(len, p + 2 + TunPackets.u16(b, p))
            p += 2
            while (p + 4 <= extEnd) {
                val type = TunPackets.u16(b, p)
                val l = TunPackets.u16(b, p + 2)
                p += 4
                if (type == 0) {
                    if (p + 5 > extEnd) return null
                    val nameLen = TunPackets.u16(b, p + 3)
                    if (b[p + 2].toInt() != 0 || nameLen <= 0 || p + 5 + nameLen > extEnd) return null
                    val name = String(b, p + 5, nameLen, Charsets.US_ASCII)
                    return if (name.all { it.code in 33..126 }) name.lowercase() else null
                }
                p += l
            }
        } catch (_: Exception) {
        }
        return null
    }
}

