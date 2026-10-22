package com.winwasm64.vpn

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Эмулируем «приложение»: подаём в стек сырые TCP/IP-пакеты и проверяем, что он отвечает по протоколу,
 * а байты реально доходят до loopback-сервера (эхо) и обратно.
 */
class TunTcpStackTest {
    private val remoteIp = 0x8EFA4A2E.toInt() // 142.250.74.46
    private val outPackets = LinkedBlockingQueue<ByteArray>()
    private lateinit var server: ServerSocket
    private lateinit var stack: TunTcpStack
    private val accepted = LinkedBlockingQueue<Int>()

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Exception) { break }
                accepted.add(c.port)
                thread(isDaemon = true) {
                    try {
                        val inp = c.getInputStream()
                        val out = c.getOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                        c.shutdownOutput()
                        Thread.sleep(50)
                    } catch (_: Exception) {
                    } finally {
                        try { c.close() } catch (_: Exception) {}
                    }
                }
            }
        }
        stack = TunTcpStack({ b, off, len -> outPackets.add(b.copyOfRange(off, off + len)) }, server.localPort)
    }

    @After
    fun tearDown() {
        stack.shutdown()
        server.close()
    }

    private fun next(timeoutMs: Long = 3000): ByteArray {
        val p = outPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)
        assertNotNull("стек не ответил за $timeoutMs мс", p)
        return p!!
    }

    private fun flags(p: ByteArray): Int = TunPackets.u8(p, 20 + 13)
    private fun seq(p: ByteArray): Int = TunPackets.u32(p, 20 + 4)
    private fun ack(p: ByteArray): Int = TunPackets.u32(p, 20 + 8)
    private fun payloadOf(p: ByteArray): ByteArray {
        val off = 20 + ((TunPackets.u8(p, 32) ushr 4) * 4)
        return p.copyOfRange(off, p.size)
    }

    private fun send(appPort: Int, seq: Int, ack: Int, flags: Int, payload: ByteArray? = null, window: Int = 65535) {
        val p = TunPackets.buildTcpSegmentV4(TunPackets.TUN_IP, appPort, remoteIp, 443, seq, ack, flags, window, null, payload, 0, payload?.size ?: 0)
        stack.handlePacket(p, 20, p.size)
    }

    private fun handshake(appPort: Int, iss: Int): Int {
        val syn = TunPackets.buildTcpSegmentV4(TunPackets.TUN_IP, appPort, remoteIp, 443, iss, 0, TunPackets.SYN, 65535, byteArrayOf(2, 4, 5, 0xB4.toByte(), 1, 3, 3, 7))
        stack.handlePacket(syn, 20, syn.size)
        val synAck = next()
        assertEquals(TunPackets.SYN or TunPackets.ACK, flags(synAck))
        assertEquals(iss + 1, ack(synAck))
        assertTrue(TunPackets.ipv4ChecksumOk(synAck, 20))
        assertTrue(TunPackets.l4ChecksumOk(synAck, 20, synAck.size, 6))
        // ответ идёт «от сервера» к приложению
        assertEquals(remoteIp, TunPackets.u32(synAck, 12))
        assertEquals(443, TunPackets.u16(synAck, 20))
        assertEquals(appPort, TunPackets.u16(synAck, 22))
        val peerIss = seq(synAck)
        send(appPort, iss + 1, peerIss + 1, TunPackets.ACK)
        return peerIss + 1
    }

    @Test
    fun handshakeDataEchoAndClose() {
        val appPort = 40001
        val iss = 1000
        var theirSeq = handshake(appPort, iss)
        val loopbackPort = accepted.poll(3000, TimeUnit.MILLISECONDS)
        assertNotNull("стек не подключился к обработчику", loopbackPort)
        val conn = TunTcpStack.lookup(loopbackPort!!)
        assertNotNull(conn)
        assertEquals(remoteIp, conn!!.remoteIp)
        assertEquals(443, conn.remotePort)
        assertEquals(appPort, conn.appPort)

        val msg = "hello through userspace tcp".toByteArray()
        send(appPort, iss + 1, theirSeq, TunPackets.ACK or 0x08, msg)
        // стек подтверждает данные и присылает эхо (порядок пакетов может отличаться)
        var gotAckFor = false
        val echo = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && (echo.size() < msg.size || !gotAckFor)) {
            val p = outPackets.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (ack(p) == iss + 1 + msg.size) gotAckFor = true
            val pl = payloadOf(p)
            if (pl.isNotEmpty()) {
                assertEquals(theirSeq + echo.size(), seq(p))
                echo.write(pl)
                theirSeq += pl.size
                send(appPort, iss + 1 + msg.size, theirSeq, TunPackets.ACK)
            }
        }
        assertTrue(gotAckFor)
        assertArrayEquals(msg, echo.toByteArray())

        // приложение закрывает: FIN → стек ACK, обработчик закрывает → стек FIN
        send(appPort, iss + 1 + msg.size, theirSeq, TunPackets.FIN or TunPackets.ACK)
        var sawFin = false
        var sawAckOfFin = false
        val d2 = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < d2 && !(sawFin && sawAckOfFin)) {
            val p = outPackets.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (ack(p) == iss + 2 + msg.size) sawAckOfFin = true
            if ((flags(p) and TunPackets.FIN) != 0) {
                sawFin = true
                send(appPort, iss + 2 + msg.size, seq(p) + 1, TunPackets.ACK)
            }
        }
        assertTrue("стек должен подтвердить FIN приложения", sawAckOfFin)
        assertTrue("стек должен прислать FIN после закрытия обработчика", sawFin)
        Thread.sleep(200)
        assertEquals(0, stack.connectionCount())
    }

    @Test
    fun bulkTransferRespectsWindowAndOrder() {
        val appPort = 40002
        val iss = 5000
        var theirSeq = handshake(appPort, iss)
        assertNotNull(accepted.poll(3000, TimeUnit.MILLISECONDS))
        val total = 300 * 1024
        val data = ByteArray(total) { (it * 31).toByte() }
        // приложение шлёт по 1460 байт, окно у приложения маленькое (8 КБ) — стек обязан дробить и ждать ACK
        var sent = 0
        var mySeq = iss + 1
        val echo = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 20_000
        var inflightToUs = 0
        while (echo.size() < total && System.currentTimeMillis() < deadline) {
            if (sent < total) {
                val n = minOf(1460, total - sent)
                send(appPort, mySeq, theirSeq, TunPackets.ACK, data.copyOfRange(sent, sent + n), window = 8192)
                mySeq += n
                sent += n
            }
            while (true) {
                val p = outPackets.poll(if (sent < total) 1 else 300, TimeUnit.MILLISECONDS) ?: break
                val pl = payloadOf(p)
                if (pl.isEmpty()) continue
                assertTrue("сегмент не больше окна", pl.size <= 8192)
                assertEquals("сегменты строго по порядку", theirSeq, seq(p))
                echo.write(pl)
                theirSeq += pl.size
                inflightToUs += pl.size
                if (inflightToUs >= 4096 || sent >= total) {
                    send(appPort, mySeq, theirSeq, TunPackets.ACK, window = 8192)
                    inflightToUs = 0
                }
            }
        }
        assertEquals(total, echo.size())
        assertArrayEquals(data, echo.toByteArray())
    }

    @Test
    fun unknownSegmentGetsRst() {
        send(40003, 777, 0, TunPackets.ACK, "x".toByteArray())
        val p = next()
        assertTrue((flags(p) and TunPackets.RST) != 0)
    }

    @Test
    fun rstFromAppDropsConnection() {
        val appPort = 40004
        handshake(appPort, 1)
        assertNotNull(accepted.poll(3000, TimeUnit.MILLISECONDS))
        assertEquals(1, stack.connectionCount())
        send(appPort, 2, 0, TunPackets.RST)
        Thread.sleep(100)
        assertEquals(0, stack.connectionCount())
    }
}
