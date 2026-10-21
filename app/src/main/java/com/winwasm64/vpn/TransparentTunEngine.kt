package com.winwasm64.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
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

/** Запись NAT: приложение (порт appPort) → настоящий сервер remoteIp:remotePort. */
class NatSession(val remoteIp: Int, val remotePort: Int, val appPort: Int) {
    @Volatile
    var lastActive: Long = System.currentTimeMillis()

    @Volatile
    var closing: Boolean = false

    @Volatile
    var profilePackage: String? = null
}

/**
 * Прозрачный туннель выбранных приложений (OmniBrowser v4.0.2).
 *
 * TCP: NAT-перенаправление каждого соединения в локальный обработчик с обходом DPI
 *   (пакет приложения A:a→D:d переписывается в D:a→TUN:P; ответ TUN:P→D:a обратно в D:d→A:a).
 *   Сам TCP ведёт ядро Android — без собственного стека, без потерь и с нормальной скоростью.
 * UDP 443/80 (QUIC): ICMP «порт недоступен» → YouTube/Chrome сразу переходят на TCP, который идёт через обход.
 * UDP 53 на 10.215.173.2: DNS-форвардер сервиса. Прочий UDP (звонки, игры): релей через сокет вне VPN.
 * IPv6: TCP SYN получает RST → приложение уходит на IPv4 (через туннель), без утечек мимо VPN.
 */
class TransparentTunEngine(
    private val pfd: ParcelFileDescriptor,
    private val listenerPort: Int,
    private val protectDatagram: (DatagramSocket) -> Unit,
    private val onDnsQuery: (packet: ByteArray, len: Int, ihl: Int, out: OutputStream) -> Unit,
    private val onUdpTraffic: (tx: Long, rx: Long) -> Unit
) {
    companion object {
        private val sessions = ConcurrentHashMap<Long, NatSession>()

        @Volatile
        var activeEngine: TransparentTunEngine? = null
            private set

        val packetsIn = AtomicLong(0L)
        val packetsOut = AtomicLong(0L)

        fun key(ip: Int, port: Int): Long = ((ip.toLong() and 0xFFFFFFFFL) shl 16) or (port.toLong() and 0xFFFFL)

        /** Для обработчика: исходный адрес назначения по (переписанный IP-источник, порт приложения). */
        fun lookupSession(remote: InetAddress, appPort: Int): NatSession? =
            sessions[key(TunPackets.inetToIp(remote), appPort)]

        fun activeSessionCount(): Int = sessions.size
    }

    private data class UdpKey(val appPort: Int, val remoteIp: Int, val remotePort: Int)

    private class UdpFlow(val sock: DatagramSocket) {
        @Volatile
        var lastActive: Long = System.currentTimeMillis()
    }

    @Volatile
    private var running = false
    private val writeLock = Any()
    private val udpFlows = ConcurrentHashMap<UdpKey, UdpFlow>()

    @Volatile
    private var testPort = -1

    @Volatile
    private var testOk = false

    private var lastSweep = 0L

    val tunOut: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            writePacket(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writePacket(b, off, len)
        }
    }

    fun start() {
        running = true
        activeEngine = this
        thread(name = "OmniTransparentTun", isDaemon = true) { readLoop() }
    }

    fun stop() {
        running = false
        if (activeEngine === this) activeEngine = null
        for (f in udpFlows.values) {
            try { f.sock.close() } catch (_: Exception) {}
        }
        udpFlows.clear()
        sessions.clear()
    }

    fun writePacket(b: ByteArray, off: Int, len: Int) {
        if (!running || len <= 0) return
        synchronized(writeLock) {
            var tries = 0
            while (tries < 3) {
                try {
                    Os.write(pfd.fileDescriptor, b, off, len)
                    packetsOut.incrementAndGet()
                    return
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) return
                    tries++
                    try { Thread.sleep(2) } catch (_: InterruptedException) {}
                } catch (_: Exception) {
                    return
                }
            }
        }
    }

    /**
     * Самопроверка NAT на этом устройстве: подаём в TUN SYN «от удалённого сервера» на порт обработчика
     * и ждём SYN-ACK обратно в TUN. Нет ответа → прозрачный режим не поддерживается, сервис откатится.
     */
    fun selfTest(timeoutMs: Long = 1800L): Boolean {
        val port = 40000 + Random.nextInt(20000)
        testPort = port
        testOk = false
        writePacket(TunPackets.buildTcpV4(TunPackets.TEST_IP, port, TunPackets.TUN_IP, listenerPort, 0x1000, 0, TunPackets.SYN), 0, 40)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!testOk && System.currentTimeMillis() < deadline && running) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
        writePacket(TunPackets.buildTcpV4(TunPackets.TEST_IP, port, TunPackets.TUN_IP, listenerPort, 0x1001, 0, TunPackets.RST), 0, 40)
        testPort = -1
        return testOk
    }

    private fun readLoop() {
        val fd = pfd.fileDescriptor
        val buf = ByteArray(65535)
        val pollFds = arrayOf(StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        })
        while (running) {
            val ready = try {
                Os.poll(pollFds, 500)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else break
            } catch (_: Exception) {
                break
            }
            sweepIfNeeded()
            if (ready <= 0) continue
            val rev = pollFds[0].revents.toInt()
            if ((rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) break
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else break
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            packetsIn.incrementAndGet()
            try {
                handlePacket(buf, n)
            } catch (_: Exception) {
            }
        }
        running = false
        if (activeEngine === this) activeEngine = null
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        val ver = (buf[0].toInt() ushr 4) and 0x0F
        if (ver == 6) {
            TunPackets.buildTcpRstV6(buf, n)?.let { writePacket(it, 0, it.size) }
            return
        }
        if (ver != 4 || n < 20) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val total = minOf(n, TunPackets.u16(buf, 2))
        if (ihl < 20 || total < ihl + 8) return
        // Фрагменты IPv4 (кроме первого) не трогаем
        if ((TunPackets.u16(buf, 6) and 0x1FFF) != 0) return
        when (TunPackets.u8(buf, 9)) {
            6 -> if (total >= ihl + 20) handleTcp(buf, ihl, total)
            17 -> handleUdp(buf, ihl, total)
            else -> {}
        }
    }

    private fun handleTcp(buf: ByteArray, ihl: Int, total: Int) {
        val src = TunPackets.u32(buf, 12)
        val dst = TunPackets.u32(buf, 16)
        val sport = TunPackets.u16(buf, ihl)
        val dport = TunPackets.u16(buf, ihl + 2)
        val flags = TunPackets.u8(buf, ihl + 13)
        val now = System.currentTimeMillis()

        if (src == TunPackets.TUN_IP && sport == listenerPort) {
            // Ответ локального обработчика → приложению
            if (dst == TunPackets.TEST_IP) {
                if (dport == testPort && (flags and (TunPackets.SYN or TunPackets.ACK)) == (TunPackets.SYN or TunPackets.ACK)) testOk = true
                return
            }
            val s = sessions[key(dst, dport)] ?: return
            s.lastActive = now
            if ((flags and (TunPackets.FIN or TunPackets.RST)) != 0) s.closing = true
            TunPackets.put32(buf, 12, dst)
            TunPackets.put32(buf, 16, TunPackets.TUN_IP)
            TunPackets.put16(buf, ihl, s.remotePort)
            TunPackets.fixIpv4Checksum(buf, ihl)
            TunPackets.fixL4ChecksumV4(buf, ihl, total, 6)
            writePacket(buf, 0, total)
            return
        }

        if (src != TunPackets.TUN_IP) return
        // Пакет приложения → в локальный обработчик
        val k = key(dst, sport)
        var s = sessions[k]
        val isNewSyn = (flags and TunPackets.SYN) != 0 && (flags and TunPackets.ACK) == 0
        if (s == null || s.remotePort != dport) {
            if (!isNewSyn) {
                // Чужое/устаревшее соединение — сбрасываем, чтобы приложение переподключилось через туннель
                if ((flags and TunPackets.RST) == 0) {
                    val seqIn = TunPackets.u32(buf, ihl + 4)
                    val ackIn = TunPackets.u32(buf, ihl + 8)
                    val rst = TunPackets.buildTcpV4(dst, dport, src, sport, ackIn, seqIn + 1, TunPackets.RST or TunPackets.ACK, 0)
                    writePacket(rst, 0, rst.size)
                }
                return
            }
            s = NatSession(dst, dport, sport)
            sessions[k] = s
        }
        s.lastActive = now
        if ((flags and (TunPackets.FIN or TunPackets.RST)) != 0) s.closing = true
        TunPackets.put32(buf, 12, dst)
        TunPackets.put32(buf, 16, TunPackets.TUN_IP)
        TunPackets.put16(buf, ihl + 2, listenerPort)
        TunPackets.fixIpv4Checksum(buf, ihl)
        TunPackets.fixL4ChecksumV4(buf, ihl, total, 6)
        writePacket(buf, 0, total)
    }

    private fun handleUdp(buf: ByteArray, ihl: Int, total: Int) {
        val src = TunPackets.u32(buf, 12)
        val dst = TunPackets.u32(buf, 16)
        if (src != TunPackets.TUN_IP) return
        val sport = TunPackets.u16(buf, ihl)
        val dport = TunPackets.u16(buf, ihl + 2)
        val payloadOff = ihl + 8
        val payloadLen = minOf(total, ihl + TunPackets.u16(buf, ihl + 4)) - payloadOff
        if (payloadLen < 0) return

        if (dport == 53 && dst == TunPackets.DNS_IP) {
            onDnsQuery(buf.copyOf(total), total, ihl, tunOut)
            return
        }
        if (dport == 443 || dport == 80) {
            // QUIC / HTTP3 → «порт недоступен»: приложение сразу берёт TCP/TLS (идёт через обход DPI)
            val icmp = TunPackets.buildIcmpPortUnreachable(buf, ihl, total)
            writePacket(icmp, 0, icmp.size)
            return
        }
        val k = UdpKey(sport, dst, dport)
        var flow = udpFlows[k]
        if (flow == null) {
            val sock = try {
                DatagramSocket().also {
                    protectDatagram(it)
                    it.soTimeout = 1000
                }
            } catch (_: Exception) {
                return
            }
            val created = UdpFlow(sock)
            val prev = udpFlows.putIfAbsent(k, created)
            if (prev != null) {
                try { sock.close() } catch (_: Exception) {}
                flow = prev
            } else {
                flow = created
                startUdpReceiver(k, created)
            }
        }
        try {
            flow.sock.send(DatagramPacket(buf, payloadOff, payloadLen, TunPackets.ipToInet(dst), dport))
            flow.lastActive = System.currentTimeMillis()
            onUdpTraffic(payloadLen.toLong(), 0L)
        } catch (_: Exception) {
        }
    }

    private fun startUdpReceiver(k: UdpKey, flow: UdpFlow) {
        thread(name = "OmniUdpRelay-${k.appPort}", isDaemon = true) {
            val rbuf = ByteArray(65507)
            val pkt = DatagramPacket(rbuf, rbuf.size)
            try {
                while (running && System.currentTimeMillis() - flow.lastActive < 60_000L) {
                    try {
                        pkt.length = rbuf.size
                        flow.sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    flow.lastActive = System.currentTimeMillis()
                    val fromIp = TunPackets.inetToIp(pkt.address)
                    if (fromIp == 0) continue
                    val out = TunPackets.buildUdpV4(fromIp, pkt.port, TunPackets.TUN_IP, k.appPort, rbuf, 0, pkt.length)
                    writePacket(out, 0, out.size)
                    onUdpTraffic(0L, pkt.length.toLong())
                }
            } catch (_: Exception) {
            } finally {
                udpFlows.remove(k, flow)
                try { flow.sock.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sweepIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastSweep < 15_000L) return
        lastSweep = now
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val idle = now - e.value.lastActive
            if ((e.value.closing && idle > 60_000L) || idle > 15 * 60_000L) it.remove()
        }
    }
}
