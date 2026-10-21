package com.winwasm64.vpn

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransparentTunEngineTest {

    private val youtubeIp = 0x8EFA4A2E.toInt() // 142.250.74.46

    @Test
    fun tcpPacketHasValidChecksums() {
        val p = TunPackets.buildTcpV4(TunPackets.TUN_IP, 40000, youtubeIp, 443, 1, 0, TunPackets.SYN)
        assertTrue(TunPackets.ipv4ChecksumOk(p, 20))
        assertTrue(TunPackets.l4ChecksumOk(p, 20, 40, 6))
    }

    @Test
    fun natRewriteBothDirectionsKeepsChecksumsValid() {
        val listenerPort = 34567
        // приложение → сервер: A:a -> D:d  ==>  D:a -> TUN:P
        val p = TunPackets.buildTcpV4(TunPackets.TUN_IP, 40001, youtubeIp, 443, 7, 0, TunPackets.SYN)
        TunPackets.put32(p, 12, youtubeIp)
        TunPackets.put32(p, 16, TunPackets.TUN_IP)
        TunPackets.put16(p, 22, listenerPort)
        TunPackets.fixIpv4Checksum(p, 20)
        TunPackets.fixL4ChecksumV4(p, 20, 40, 6)
        assertTrue(TunPackets.ipv4ChecksumOk(p, 20))
        assertTrue(TunPackets.l4ChecksumOk(p, 20, 40, 6))
        assertEquals(youtubeIp, TunPackets.u32(p, 12))
        assertEquals(listenerPort, TunPackets.u16(p, 22))

        // ответ обработчика: TUN:P -> D:a  ==>  D:d -> TUN:a
        val r = TunPackets.buildTcpV4(TunPackets.TUN_IP, listenerPort, youtubeIp, 40001, 99, 8, TunPackets.SYN or TunPackets.ACK)
        TunPackets.put32(r, 12, youtubeIp)
        TunPackets.put32(r, 16, TunPackets.TUN_IP)
        TunPackets.put16(r, 20, 443)
        TunPackets.fixIpv4Checksum(r, 20)
        TunPackets.fixL4ChecksumV4(r, 20, 40, 6)
        assertTrue(TunPackets.ipv4ChecksumOk(r, 20))
        assertTrue(TunPackets.l4ChecksumOk(r, 20, 40, 6))
        assertEquals(443, TunPackets.u16(r, 20))
        assertEquals(40001, TunPackets.u16(r, 22))
    }

    @Test
    fun natKeySeparatesDestinations() {
        val k1 = TransparentTunEngine.key(youtubeIp, 40001)
        val k2 = TransparentTunEngine.key(youtubeIp + 1, 40001)
        val k3 = TransparentTunEngine.key(youtubeIp, 40002)
        assertTrue(k1 != k2 && k1 != k3 && k2 != k3)
    }

    @Test
    fun udpAndIcmpUnreachable() {
        val payload = "quic-initial".toByteArray()
        val u = TunPackets.buildUdpV4(TunPackets.TUN_IP, 50000, youtubeIp, 443, payload, 0, payload.size)
        assertTrue(TunPackets.ipv4ChecksumOk(u, 20))
        assertTrue(TunPackets.l4ChecksumOk(u, 20, u.size, 17))
        val icmp = TunPackets.buildIcmpPortUnreachable(u, 20, u.size)
        assertTrue(TunPackets.ipv4ChecksumOk(icmp, 20))
        assertEquals(1, TunPackets.u8(icmp, 9))
        assertEquals(3, TunPackets.u8(icmp, 20))
        assertEquals(3, TunPackets.u8(icmp, 21))
        assertEquals(youtubeIp, TunPackets.u32(icmp, 12))
        assertEquals(TunPackets.TUN_IP, TunPackets.u32(icmp, 16))
        // контрольная сумма ICMP корректна: сумма по сообщению вместе с полем = 0xFFFF → fold = 0
        assertEquals(0, TunPackets.fold(TunPackets.sum16(icmp, 20, icmp.size - 20)))
    }

    @Test
    fun ipv6SynGetsRst() {
        val syn = ByteArray(60)
        syn[0] = 0x60
        TunPackets.put16(syn, 4, 20)
        syn[6] = 6
        syn[7] = 64
        for (i in 8 until 40) syn[i] = (i * 7).toByte()
        TunPackets.put16(syn, 40, 51000)
        TunPackets.put16(syn, 42, 443)
        TunPackets.put32(syn, 44, 1000)
        syn[52] = 0x50
        syn[53] = TunPackets.SYN.toByte()
        val rst = TunPackets.buildTcpRstV6(syn, syn.size)
        assertNotNull(rst)
        rst!!
        assertEquals(443, TunPackets.u16(rst, 40))
        assertEquals(51000, TunPackets.u16(rst, 42))
        assertEquals(1001, TunPackets.u32(rst, 48))
        assertEquals(TunPackets.RST or TunPackets.ACK, TunPackets.u8(rst, 53))
        var s = TunPackets.sum16(rst, 8, 32)
        s += 20L
        s += 6L
        s = TunPackets.sum16(rst, 40, 20, s)
        assertEquals(0, TunPackets.fold(s))
        // не-SYN пакеты не трогаем
        syn[53] = TunPackets.ACK.toByte()
        assertNull(TunPackets.buildTcpRstV6(syn, syn.size))
    }

    @Test
    fun sniIsExtractedFromClientHello() {
        val hello = clientHello("rr3---sn-4g5e6nzz.googlevideo.com")
        assertEquals("rr3---sn-4g5e6nzz.googlevideo.com", TlsSni.extract(hello, hello.size))
        assertNull(TlsSni.extract(byteArrayOf(0x47, 0x45, 0x54, 0x20), 4))
        assertNull(TlsSni.extract(hello, 30))
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray()
        val ext = ByteArrayOutputStream().apply {
            u16(0x000b); u16(2); write(1); write(0) // ec_point_formats (до SNI — проверяем перебор)
            u16(0x0000); u16(name.size + 5); u16(name.size + 3); write(0); u16(name.size); write(name)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            u16(0x0303)
            write(ByteArray(32))
            write(32); write(ByteArray(32)) // session id
            u16(4); u16(0x1301); u16(0x1302)
            write(1); write(0)
            u16(ext.size); write(ext)
        }.toByteArray()
        val hs = ByteArrayOutputStream().apply {
            write(1)
            write((body.size ushr 16) and 0xFF); u16(body.size and 0xFFFF)
            write(body)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x16); u16(0x0301); u16(hs.size); write(hs)
        }.toByteArray()
    }
}
