package com.winwasm64.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Прозрачный туннель выбранных приложений (OmniBrowser v4.1.0).
 *
 * TCP: userspace-стек [TunTcpStack] (как tun2socks) — отвечает приложению сам, а поток байт отдаёт
 *   loopback-сокетом в локальный обработчик (обход DPI или туннель Browsec). Работает на любом Android,
 *   не зависит от правил маршрутизации ядра (старая NAT-петля на Android не работала).
 * UDP 443/80 (QUIC): ICMP «порт недоступен» → YouTube/Chrome сразу переходят на TCP, который идёт через обход.
 * UDP 53 на 10.215.173.2: DNS-форвардер сервиса. Прочий UDP (звонки, игры): релей через сокет вне VPN.
 * IPv6: TCP SYN получает RST → приложение уходит на IPv4 (через туннель), без утечек мимо VPN.
 */
class TransparentTunEngine(
    private val pfd: ParcelFileDescriptor,
    private val listenerPort: Int,
    private val protectDatagram: (DatagramSocket) -> Unit,
    private val onDnsQuery: (packet: ByteArray, len: Int, ihl: Int, out: OutputStream) -> Unit,
    private val onUdpTraffic: (tx: Long, rx: Long) -> Unit,
    /** v4.10.7: подпись приложения-владельца UDP-потока (для диагностики WebRTC); null → «приложение». */
    private val udpOwner: (appPort: Int, remoteIp: Int, remotePort: Int) -> String? = { _, _, _ -> null }
) {
    companion object {
        @Volatile
        var activeEngine: TransparentTunEngine? = null
            private set

        val packetsIn = AtomicLong(0L)
        val packetsOut = AtomicLong(0L)

        /** Для обработчика: исходный адрес назначения по локальному порту loopback-сокета. */
        fun lookupSession(loopbackPort: Int): TunTcpStack.TunTcpConnection? = TunTcpStack.lookup(loopbackPort)

        fun activeSessionCount(): Int = TunTcpStack.activeConnectionCount()
    }

    private data class UdpKey(val appPort: Int, val remoteIp: Int, val remotePort: Int)

    private class UdpFlow(val sock: DatagramSocket, val probe: UdpFlowProbe) {
        @Volatile
        var lastActive: Long = System.currentTimeMillis()
    }

    @Volatile
    private var running = false
    private val writeLock = Any()
    private val udpFlows = ConcurrentHashMap<UdpKey, UdpFlow>()

    val tcp = TunTcpStack(
        sink = { b, off, len -> writePacket(b, off, len) },
        listenerPort = listenerPort,
        onLog = { msg -> VpnLog.w("Туннель", msg) }
    )

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
        tcp.shutdown()
        for (f in udpFlows.values) {
            try { f.sock.close() } catch (_: Exception) {}
        }
        udpFlows.clear()
    }

    fun writePacket(b: ByteArray, off: Int, len: Int) {
        if (!running || len <= 0) return
        synchronized(writeLock) {
            var tries = 0
            while (tries < 40) {
                try {
                    Os.write(pfd.fileDescriptor, b, off, len)
                    packetsOut.incrementAndGet()
                    return
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) return
                    tries++
                    // Очередь TUN заполнена — ждём готовности к записи вместо потери пакета
                    try {
                        val pfds = arrayOf(StructPollfd().apply {
                            fd = pfd.fileDescriptor
                            events = OsConstants.POLLOUT.toShort()
                        })
                        Os.poll(pfds, 25)
                    } catch (_: Exception) {
                        try { Thread.sleep(2) } catch (_: InterruptedException) { return }
                    }
                } catch (_: Exception) {
                    return
                }
            }
        }
    }

    /** Самопроверка: обработчик принимает loopback-соединения (ядро для этого больше не нужно). */
    fun selfTest(timeoutMs: Long = 1500L): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), listenerPort), timeoutMs.toInt())
            true
        }
    } catch (_: Exception) {
        false
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
            if (ready <= 0) continue
            val rev = pollFds[0].revents.toInt()
            if ((rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) break
            // Вычитываем всё, что накопилось, не возвращаясь к poll на каждый пакет
            var drained = 0
            while (running && drained < 256) {
                val n = try {
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) break
                    if (e.errno == OsConstants.EINTR) continue
                    running = false
                    break
                } catch (_: Exception) {
                    running = false
                    break
                }
                if (n <= 0) break
                drained++
                packetsIn.incrementAndGet()
                try {
                    handlePacket(buf, n)
                } catch (_: Exception) {
                }
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
        if (TunPackets.u32(buf, 12) != TunPackets.TUN_IP) return
        when (TunPackets.u8(buf, 9)) {
            6 -> if (total >= ihl + 20) tcp.handlePacket(buf, ihl, total)
            17 -> handleUdp(buf, ihl, total)
            else -> {}
        }
    }

    private fun handleUdp(buf: ByteArray, ihl: Int, total: Int) {
        val dst = TunPackets.u32(buf, 16)
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
            val remoteLabel = TunPackets.ipToInet(dst).hostAddress + ":" + dport
            val created = UdpFlow(sock, UdpFlowProbe(remoteLabel) { VpnLog.label(udpOwner(sport, dst, dport)) })
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
            try { flow.probe.onTx(buf, payloadOff, payloadLen) } catch (_: Throwable) {}
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
                        try { flow.probe.tick() } catch (_: Throwable) {}
                        continue
                    }
                    flow.lastActive = System.currentTimeMillis()
                    val fromIp = TunPackets.inetToIp(pkt.address)
                    if (fromIp == 0) continue
                    try { flow.probe.onRx(rbuf, 0, pkt.length); flow.probe.tick() } catch (_: Throwable) {}
                    val out = TunPackets.buildUdpV4(fromIp, pkt.port, TunPackets.TUN_IP, k.appPort, rbuf, 0, pkt.length)
                    writePacket(out, 0, out.size)
                    onUdpTraffic(0L, pkt.length.toLong())
                }
            } catch (_: Exception) {
            } finally {
                udpFlows.remove(k, flow)
                try { flow.sock.close() } catch (_: Exception) {}
                try { flow.probe.onClose() } catch (_: Throwable) {}
            }
        }
    }
}
