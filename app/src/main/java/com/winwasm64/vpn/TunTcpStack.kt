package com.winwasm64.vpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/** Куда стек пишет готовые IP-пакеты (TUN). */
fun interface TunPacketSink {
    fun writePacket(b: ByteArray, off: Int, len: Int)
}

/**
 * Userspace TCP-стек прозрачного туннеля (OmniBrowser v4.1.0) — по принципу tun2socks.
 *
 * Раньше прозрачный режим пытался «прокрутить» пакет через ядро (NAT-петля в TUN). На Android это не работает:
 * ответ локального сокета маршрутизируется по UID нашего приложения, которое исключено из VPN-таблицы, —
 * SYN-ACK никогда не возвращался в TUN, самопроверка проваливалась и сервис откатывался на «режим прокси»,
 * который YouTube и большинство приложений игнорируют.
 *
 * Теперь TCP приложения завершается здесь: мы сами отвечаем SYN-ACK, подтверждаем данные, следим за окном
 * и повторами, а поток байт отдаём обычному loopback-сокету в локальный обработчик (обход DPI / туннель Browsec).
 * Обработчик узнаёт исходный адрес назначения по локальному порту loopback-сокета ([lookup]).
 *
 * Без Android-зависимостей — покрывается юнит-тестами (см. TunTcpStackTest).
 */
class TunTcpStack(
    private val sink: TunPacketSink,
    private val listenerPort: Int,
    private val listenerAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val onLog: ((String) -> Unit)? = null
) {
    companion object {
        const val MSS_DEFAULT = 1460
        const val RCV_BUF = 256 * 1024
        const val SND_BUF = 128 * 1024
        const val OUR_WSCALE = 2
        private const val RTO_MIN = 300L
        private const val RTO_MAX = 3_000L
        private const val MAX_RETRIES = 12
        private const val IDLE_LIMIT_MS = 20 * 60_000L
        private const val TIME_WAIT_MS = 3_000L

        private val byLoopbackPort = ConcurrentHashMap<Int, TunTcpConnection>()

        /** Для обработчика: соединение приложения по локальному порту loopback-сокета (client.port на стороне сервера). */
        fun lookup(loopbackPort: Int): TunTcpConnection? = byLoopbackPort[loopbackPort]

        fun activeConnectionCount(): Int = byLoopbackPort.size

        val bytesToApp = AtomicLong(0L)
        val bytesFromApp = AtomicLong(0L)

        fun key(remoteIp: Int, remotePort: Int, appPort: Int): Long =
            ((remoteIp.toLong() and 0xFFFFFFFFL) shl 32) or ((remotePort.toLong() and 0xFFFFL) shl 16) or (appPort.toLong() and 0xFFFFL)
    }

    private val conns = ConcurrentHashMap<Long, TunTcpConnection>()
    private val threadNo = AtomicInteger(0)
    private val pool: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "OmniTcp-" + threadNo.incrementAndGet()).apply { isDaemon = true }
    })

    @Volatile
    private var running = true

    init {
        thread(name = "OmniTcpTimer", isDaemon = true) { timerLoop() }
    }

    fun connectionCount(): Int = conns.size

    fun shutdown() {
        running = false
        for (c in conns.values) c.abort(sendRst = false, reason = "stop")
        conns.clear()
        pool.shutdownNow()
    }

    /** Пакет TCP/IPv4 от приложения (src = адрес TUN). [total] — длина IP-пакета. */
    fun handlePacket(buf: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 20) return
        val src = TunPackets.u32(buf, 12)
        val dst = TunPackets.u32(buf, 16)
        val sport = TunPackets.u16(buf, ihl)
        val dport = TunPackets.u16(buf, ihl + 2)
        val seq = TunPackets.u32(buf, ihl + 4)
        val ack = TunPackets.u32(buf, ihl + 8)
        val dataOff = (TunPackets.u8(buf, ihl + 12) ushr 4) * 4
        if (dataOff < 20 || ihl + dataOff > total) return
        val flags = TunPackets.u8(buf, ihl + 13)
        val wnd = TunPackets.u16(buf, ihl + 14)
        val payloadOff = ihl + dataOff
        val payloadLen = total - payloadOff
        val k = key(dst, dport, sport)
        var c = conns[k]

        if (c != null && (flags and TunPackets.SYN) != 0 && (flags and TunPackets.ACK) == 0 && c.isDead()) {
            c.abort(sendRst = false, reason = "reuse")
            conns.remove(k, c)
            c = null
        }
        if (c == null) {
            if ((flags and TunPackets.RST) != 0) return
            if ((flags and TunPackets.SYN) == 0 || (flags and TunPackets.ACK) != 0) {
                // Соединение нам неизвестно (например, открыто до включения VPN) — сбрасываем, приложение переподключится через туннель
                val rst = if ((flags and TunPackets.ACK) != 0)
                    TunPackets.buildTcpSegmentV4(dst, dport, src, sport, ack, 0, TunPackets.RST, 0)
                else
                    TunPackets.buildTcpSegmentV4(dst, dport, src, sport, 0, seq + payloadLen + 1, TunPackets.RST or TunPackets.ACK, 0)
                sink.writePacket(rst, 0, rst.size)
                return
            }
            if (!running) return
            val (mss, ws) = TunPackets.parseTcpSynOptions(buf, ihl, total)
            val conn = TunTcpConnection(this, src, sport, dst, dport, seq, mss ?: MSS_DEFAULT, ws)
            conns[k] = conn
            conn.sendSynAck()
            pool.execute { conn.openLoopback() }
            return
        }
        c.onSegment(buf, seq, ack, flags, wnd, payloadOff, payloadLen)
    }

    internal fun write(p: ByteArray) = sink.writePacket(p, 0, p.size)

    internal fun execute(r: Runnable) {
        try {
            pool.execute(r)
        } catch (_: Exception) {
        }
    }

    internal fun register(conn: TunTcpConnection, loopbackPort: Int) {
        byLoopbackPort[loopbackPort] = conn
    }

    internal fun unregister(conn: TunTcpConnection) {
        conn.loopbackPort.takeIf { it > 0 }?.let { byLoopbackPort.remove(it, conn) }
        conns.remove(key(conn.remoteIp, conn.remotePort, conn.appPort), conn)
    }

    internal fun loopbackTarget(): InetSocketAddress = InetSocketAddress(listenerAddress, listenerPort)

    internal fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private fun timerLoop() {
        while (running) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            for (c in conns.values) {
                try {
                    c.onTimer(now)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    enum class State { SYN_RCVD, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT_1, FIN_WAIT_2, CLOSING, TIME_WAIT, CLOSED }

    private class Segment(val seq: Int, val data: ByteArray?, val len: Int, val flags: Int) {
        @Volatile
        var sentAt: Long = System.currentTimeMillis()
        var retries = 0
        val end: Int get() = seq + len + (if ((flags and (TunPackets.FIN or TunPackets.SYN)) != 0) 1 else 0)
    }

    /** Одно TCP-соединение приложения (A:appPort → remoteIp:remotePort), завершённое в userspace. */
    class TunTcpConnection internal constructor(
        private val stack: TunTcpStack,
        val appIp: Int,
        val appPort: Int,
        val remoteIp: Int,
        val remotePort: Int,
        irs: Int,
        peerMss: Int,
        private val peerWscale: Int?
    ) {
        private val lock = Object()

        @Volatile
        var state: State = State.SYN_RCVD
            private set

        /** Пакет приложения-владельца (кэш обработчика). */
        @Volatile
        var profilePackage: String? = null

        @Volatile
        var lastActive: Long = System.currentTimeMillis()

        val createdAt: Long = System.currentTimeMillis()

        @Volatile
        var loopbackPort: Int = 0
            private set

        /** Совместимость со старым обработчиком: соединение завершается. */
        val closing: Boolean get() = state != State.ESTABLISHED && state != State.SYN_RCVD

        val mss: Int = peerMss.coerceIn(536, MSS_DEFAULT)
        private val ourWscale: Int? = if (peerWscale != null) OUR_WSCALE else null

        // --- приём (приложение → обработчик)
        private var rcvNxt: Int = irs + 1
        private val inBuf = ByteArray(RCV_BUF)
        private var inHead = 0
        private var inCount = 0
        private var inEof = false

        // --- отправка (обработчик → приложение)
        private val iss: Int = Random.nextInt()
        private var sndUna: Int = iss
        private var sndNxt: Int = iss + 1
        private var peerWnd: Int = 65535
        private var dupAcks = 0
        private var rto = RTO_MIN
        private val unacked = ArrayDeque<Segment>()
        private var finSent = false
        private var zeroWndSince = 0L
        private var timeWaitAt = 0L

        @Volatile
        private var socket: Socket? = null

        @Volatile
        private var aborted = false

        fun isDead(): Boolean = state == State.CLOSED || state == State.TIME_WAIT || aborted

        private fun freeSpace(): Int = RCV_BUF - inCount

        private fun windowField(): Int {
            val free = freeSpace()
            return if (ourWscale != null) (free ushr ourWscale).coerceIn(0, 0xFFFF) else free.coerceIn(0, 0xFFFF)
        }

        /** SYN-ACK: окно в SYN-сегментах никогда не масштабируется. */
        private fun synAckPacket(): ByteArray = TunPackets.buildTcpSegmentV4(
            remoteIp, remotePort, appIp, appPort, iss, rcvNxt,
            TunPackets.SYN or TunPackets.ACK, minOf(freeSpace(), 0xFFFF),
            TunPackets.tcpSynAckOptions(MSS_DEFAULT, ourWscale)
        )

        internal fun sendSynAck() {
            synchronized(lock) {
                val p = synAckPacket()
                if (unacked.isEmpty()) unacked.addLast(Segment(iss, null, 0, TunPackets.SYN))
                stack.write(p)
            }
        }

        private fun sendAck() {
            val p = TunPackets.buildTcpSegmentV4(remoteIp, remotePort, appIp, appPort, sndNxt, rcvNxt, TunPackets.ACK, windowField())
            stack.write(p)
        }

        private fun sendRst() {
            val p = TunPackets.buildTcpSegmentV4(remoteIp, remotePort, appIp, appPort, sndNxt, rcvNxt, TunPackets.RST or TunPackets.ACK, 0)
            stack.write(p)
        }

        private fun transmit(seg: Segment) {
            val p = TunPackets.buildTcpSegmentV4(
                remoteIp, remotePort, appIp, appPort, seg.seq, rcvNxt,
                seg.flags or TunPackets.ACK, windowField(), null, seg.data, 0, seg.len
            )
            seg.sentAt = System.currentTimeMillis()
            stack.write(p)
        }

        /** Входящий сегмент от приложения (вызывается из потока чтения TUN). */
        internal fun onSegment(buf: ByteArray, seq: Int, ack: Int, flags: Int, wnd: Int, payloadOff: Int, payloadLen: Int) {
            synchronized(lock) {
                if (aborted) return
                lastActive = System.currentTimeMillis()
                if ((flags and TunPackets.RST) != 0) {
                    abortLocked(sendRst = false, reason = "RST от приложения")
                    return
                }
                if ((flags and TunPackets.SYN) != 0) {
                    if (state == State.SYN_RCVD) {
                        // Повтор SYN — повторяем SYN-ACK
                        stack.write(synAckPacket())
                    }
                    return
                }
                if ((flags and TunPackets.ACK) == 0) return

                // --- обработка ACK
                if (state == State.SYN_RCVD) {
                    if (ack != sndNxt) {
                        abortLocked(sendRst = true, reason = "неверный ACK на SYN-ACK")
                        return
                    }
                    state = State.ESTABLISHED
                }
                if (TunPackets.seqLt(sndUna, ack) && TunPackets.seqLe(ack, sndNxt)) {
                    sndUna = ack
                    dupAcks = 0
                    rto = RTO_MIN
                    while (unacked.isNotEmpty() && TunPackets.seqLe(unacked.first().end, ack)) unacked.removeFirst()
                    if (finSent && ack == sndNxt) {
                        when (state) {
                            State.FIN_WAIT_1 -> state = State.FIN_WAIT_2
                            State.CLOSING -> enterTimeWait()
                            State.LAST_ACK -> finishLocked()
                            else -> {}
                        }
                    }
                } else if (ack == sndUna && payloadLen == 0 && (flags and TunPackets.FIN) == 0 && unacked.isNotEmpty()) {
                    dupAcks++
                    if (dupAcks == 3) {
                        val first = unacked.first()
                        if ((first.flags and TunPackets.SYN) == 0) transmit(first)
                    }
                }
                val newWnd = if (peerWscale != null) wnd shl peerWscale else wnd
                if (newWnd != peerWnd) {
                    peerWnd = newWnd
                    if (newWnd > 0) zeroWndSince = 0L
                }
                lock.notifyAll()

                // --- данные / FIN
                val fin = (flags and TunPackets.FIN) != 0
                if (payloadLen <= 0 && !fin) return
                if (state == State.TIME_WAIT || state == State.CLOSED) {
                    sendAck()
                    return
                }
                var off = payloadOff
                var len = payloadLen
                var s = seq
                if (s != rcvNxt) {
                    if (TunPackets.seqLt(s, rcvNxt) && TunPackets.seqLt(rcvNxt, s + len + (if (fin) 1 else 0))) {
                        val skip = rcvNxt - s
                        off += minOf(skip, len)
                        len = maxOf(0, len - skip)
                        s = rcvNxt
                    } else {
                        // Не по порядку или уже получено — повторяем ACK, ядро приложения само повторит
                        sendAck()
                        return
                    }
                }
                val take = minOf(len, freeSpace())
                if (take > 0) {
                    var tail = (inHead + inCount) % RCV_BUF
                    var remaining = take
                    var src = off
                    while (remaining > 0) {
                        val chunk = minOf(remaining, RCV_BUF - tail)
                        System.arraycopy(buf, src, inBuf, tail, chunk)
                        tail = (tail + chunk) % RCV_BUF
                        src += chunk
                        remaining -= chunk
                    }
                    inCount += take
                    rcvNxt += take
                    bytesFromApp.addAndGet(take.toLong())
                }
                if (fin && take == len && !inEof) {
                    rcvNxt += 1
                    inEof = true
                    when (state) {
                        State.ESTABLISHED -> state = State.CLOSE_WAIT
                        State.FIN_WAIT_1 -> state = State.CLOSING
                        State.FIN_WAIT_2 -> enterTimeWait()
                        else -> {}
                    }
                }
                sendAck()
                lock.notifyAll()
            }
        }

        private fun enterTimeWait() {
            state = State.TIME_WAIT
            timeWaitAt = System.currentTimeMillis()
            closeSocketQuietly()
            lock.notifyAll()
        }

        private fun finishLocked() {
            state = State.CLOSED
            closeSocketQuietly()
            stack.unregister(this)
            lock.notifyAll()
        }

        private fun closeSocketQuietly() {
            val s = socket ?: return
            try {
                s.close()
            } catch (_: Exception) {
            }
        }

        private fun abortLocked(sendRst: Boolean, reason: String) {
            if (aborted) return
            aborted = true
            if (sendRst) {
                try {
                    sendRst()
                } catch (_: Exception) {
                }
            }
            state = State.CLOSED
            unacked.clear()
            closeSocketQuietly()
            stack.unregister(this)
            lock.notifyAll()
        }

        fun abort(sendRst: Boolean, reason: String) {
            synchronized(lock) { abortLocked(sendRst, reason) }
        }

        /** Таймер стека: повторы, зондирование нулевого окна, уборка TIME_WAIT и простоев. */
        internal fun onTimer(now: Long) {
            synchronized(lock) {
                if (aborted) return
                if (state == State.TIME_WAIT) {
                    if (now - timeWaitAt > TIME_WAIT_MS) finishLocked()
                    return
                }
                if (now - lastActive > IDLE_LIMIT_MS) {
                    abortLocked(sendRst = true, reason = "простой")
                    return
                }
                val first = unacked.firstOrNull() ?: return
                if (now - first.sentAt < rto) return
                if (first.retries >= MAX_RETRIES) {
                    abortLocked(sendRst = true, reason = "нет подтверждений")
                    return
                }
                first.retries++
                rto = minOf(RTO_MAX, rto * 2)
                if ((first.flags and TunPackets.SYN) != 0) {
                    first.sentAt = now
                    stack.write(synAckPacket())
                } else {
                    transmit(first)
                }
            }
        }

        // ------------------------------------------------------------------------------------------
        // Loopback-сокет к обработчику

        internal fun openLoopback() {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                loopbackPort = s.localPort
                stack.register(this, loopbackPort)
                socket = s
                s.connect(stack.loopbackTarget(), 5_000)
                if (aborted) {
                    closeSocketQuietly()
                    return
                }
            } catch (e: Exception) {
                stack.log("обработчик не принял соединение ${TunPackets.ipToString(remoteIp)}:$remotePort — ${e.message ?: e.javaClass.simpleName}")
                try { s.close() } catch (_: Exception) {}
                abort(sendRst = true, reason = "loopback connect")
                return
            }
            val inp: InputStream
            val out: OutputStream
            try {
                inp = s.getInputStream()
                out = s.getOutputStream()
            } catch (_: Exception) {
                abort(sendRst = true, reason = "loopback streams")
                return
            }
            stack.execute { readerLoop(inp) }
            writerLoop(out)
        }

        /** Данные приложения → обработчик (loopback). */
        private fun writerLoop(out: OutputStream) {
            val tmp = ByteArray(32 * 1024)
            try {
                while (true) {
                    var n: Int
                    var needWindowUpdate: Boolean
                    var eof: Boolean
                    synchronized(lock) {
                        while (inCount == 0 && !inEof && !aborted) lock.wait(500L)
                        if (aborted) return
                        val before = freeSpace()
                        n = minOf(inCount, tmp.size)
                        var copied = 0
                        while (copied < n) {
                            val chunk = minOf(n - copied, RCV_BUF - inHead)
                            System.arraycopy(inBuf, inHead, tmp, copied, chunk)
                            inHead = (inHead + chunk) % RCV_BUF
                            copied += chunk
                        }
                        inCount -= n
                        eof = inEof && inCount == 0
                        // Окно было маленьким (меньше четверти буфера) — сообщим приложению, что место освободилось
                        needWindowUpdate = n > 0 && before < RCV_BUF / 4 && (state == State.ESTABLISHED || state == State.FIN_WAIT_1 || state == State.FIN_WAIT_2)
                    }
                    if (n > 0) {
                        out.write(tmp, 0, n)
                        out.flush()
                    }
                    if (needWindowUpdate) synchronized(lock) { if (!aborted) sendAck() }
                    if (eof) {
                        try {
                            socket?.shutdownOutput()
                        } catch (_: Exception) {
                        }
                        return
                    }
                }
            } catch (_: IOException) {
                abort(sendRst = true, reason = "обработчик закрыл приём")
            } catch (_: InterruptedException) {
                abort(sendRst = false, reason = "interrupted")
            } catch (_: Exception) {
                abort(sendRst = true, reason = "writer")
            }
        }

        /** Ответ обработчика → приложение сегментами в пределах окна. */
        private fun readerLoop(inp: InputStream) {
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = try {
                        inp.read(buf)
                    } catch (_: IOException) {
                        -2
                    }
                    if (n == -2) {
                        // Обработчик оборвал соединение (ошибка upstream) — приложению RST, чтобы оно не висело
                        abort(sendRst = true, reason = "обработчик оборвал")
                        return
                    }
                    if (n < 0) {
                        sendFin()
                        return
                    }
                    if (n == 0) continue
                    bytesToApp.addAndGet(n.toLong())
                    var off = 0
                    while (off < n) {
                        synchronized(lock) {
                            while (true) {
                                if (aborted || state == State.CLOSED || state == State.TIME_WAIT) return
                                val inflight = sndNxt - sndUna
                                val allowed = minOf(peerWnd, SND_BUF) - inflight
                                if (allowed > 0) {
                                    val segLen = minOf(allowed, mss, n - off)
                                    val data = buf.copyOfRange(off, off + segLen)
                                    val seg = Segment(sndNxt, data, segLen, TunPackets.ACK or if (off + segLen >= n) 0x08 else 0)
                                    unacked.addLast(seg)
                                    sndNxt += segLen
                                    transmit(seg)
                                    off += segLen
                                    break
                                }
                                if (peerWnd == 0 && inflight == 0) {
                                    // Нулевое окно: зондируем одним байтом раз в секунду, чтобы не зависнуть, если ядро не пришлёт обновление
                                    val now = System.currentTimeMillis()
                                    if (zeroWndSince == 0L) zeroWndSince = now
                                    if (now - zeroWndSince > 1_000L) {
                                        val seg = Segment(sndNxt, byteArrayOf(buf[off]), 1, TunPackets.ACK)
                                        unacked.addLast(seg)
                                        sndNxt += 1
                                        transmit(seg)
                                        off += 1
                                        zeroWndSince = now
                                        break
                                    }
                                }
                                lock.wait(200L)
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                abort(sendRst = false, reason = "interrupted")
            } catch (_: Exception) {
                abort(sendRst = true, reason = "reader")
            }
        }

        private fun sendFin() {
            synchronized(lock) {
                if (aborted || finSent) return
                // Дождаться, пока окно позволит хотя бы FIN (данные уже в unacked)
                val seg = Segment(sndNxt, null, 0, TunPackets.FIN or TunPackets.ACK)
                unacked.addLast(seg)
                sndNxt += 1
                finSent = true
                state = when (state) {
                    State.CLOSE_WAIT -> State.LAST_ACK
                    State.ESTABLISHED, State.SYN_RCVD -> State.FIN_WAIT_1
                    else -> state
                }
                transmit(seg)
            }
        }

        override fun toString(): String =
            "${TunPackets.ipToString(appIp)}:$appPort→${TunPackets.ipToString(remoteIp)}:$remotePort[$state]"
    }
}
