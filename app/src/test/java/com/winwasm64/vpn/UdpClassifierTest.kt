package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.16.0: разбор UDP-датаграмм для решения «ICMP → TCP» или «UDP-релей».
 *
 * Ошибка здесь стоит дорого в обе стороны: принять игру за QUIC — игра не подключится
 * (именно так GeForce NOW получал «порт недоступен»); принять QUIC за игру — сайт пойдёт
 * мимо туннеля. Поэтому проверяем каждый реальный тип первого пакета.
 */
class UdpClassifierTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `QUIC v1 распознаётся как QUIC`() {
        val p = bytes(0xC0, 0x00, 0x00, 0x00, 0x01, 0x08, 0x11, 0x22, 0x33, 0x44)
        assertEquals(UdpClassifier.QUIC, UdpClassifier.kind(p, 0, p.size))
        assertTrue(UdpClassifier.looksLikeQuic(p, 0, p.size))
    }

    @Test
    fun `QUIC v2 распознаётся как QUIC`() {
        val p = bytes(0xC3, 0x6b, 0x33, 0x43, 0xcf, 0x08, 0x00, 0x00)
        assertEquals(UdpClassifier.QUIC, UdpClassifier.kind(p, 0, p.size))
    }

    @Test
    fun `DTLS ClientHello игры не считается QUIC`() {
        // record type 22 (handshake), версия DTLS 1.2 = 0xFE 0xFD
        val p = bytes(0x16, 0xFE, 0xFD, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(UdpClassifier.DTLS, UdpClassifier.kind(p, 0, p.size))
        assertFalse(UdpClassifier.looksLikeQuic(p, 0, p.size))
    }

    @Test
    fun `STUN binding request — это WebRTC, а не QUIC`() {
        val p = bytes(0x00, 0x01, 0x00, 0x08, 0x21, 0x12, 0xA4, 0x42, 0x00, 0x00, 0x00, 0x01)
        assertEquals(UdpClassifier.STUN, UdpClassifier.kind(p, 0, p.size))
        assertFalse(UdpClassifier.looksLikeQuic(p, 0, p.size))
    }

    @Test
    fun `RTP-медиа идёт в релей`() {
        val p = bytes(0x80, 0x60, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(UdpClassifier.RTP, UdpClassifier.kind(p, 0, p.size))
        assertFalse(UdpClassifier.looksLikeQuic(p, 0, p.size))
    }

    @Test
    fun `короткий или пустой пакет не роняет разбор`() {
        assertEquals(UdpClassifier.OTHER, UdpClassifier.kind(ByteArray(0), 0, 0))
        assertEquals(UdpClassifier.OTHER, UdpClassifier.kind(bytes(0x80), 0, 1))
        // смещение за пределами буфера
        assertEquals(UdpClassifier.OTHER, UdpClassifier.kind(bytes(0x80, 0x60), 5, 2))
    }

    @Test
    fun `в журнале понятное объяснение`() {
        assertTrue(UdpClassifier.title(UdpClassifier.DTLS).contains("релей"))
        assertTrue(UdpClassifier.title(UdpClassifier.QUIC).contains("TCP"))
    }
}
