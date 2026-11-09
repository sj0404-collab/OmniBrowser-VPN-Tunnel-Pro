package com.winwasm64.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.31.0: адрес «в себя» не должен попадать в список исходящих адресатов.
 *
 * Из журнала с устройства: `failed to connect to cnlogs.umeng.com/127.0.0.1 (port 443) from /127.0.0.1`
 * — браузер/приложение пыталось соединиться само с собой, потому что фильтр блокировки отвечал
 * на домен адресом 127.0.0.1, а этот ответ брался как настоящий IP сервера.
 */
class LoopbackCandidateTest {

    @Test
    fun `петля и неопределённые адреса отбрасываются`() {
        assertTrue(FastMultiDnsResolver.isLoopbackOrUnspecified("127.0.0.1"))
        assertTrue(FastMultiDnsResolver.isLoopbackOrUnspecified("127.10.20.30"))
        assertTrue(FastMultiDnsResolver.isLoopbackOrUnspecified("0.0.0.0"))
        assertTrue(FastMultiDnsResolver.isLoopbackOrUnspecified("::1"))
        assertTrue(FastMultiDnsResolver.isLoopbackOrUnspecified(""))
    }

    @Test
    fun `обычные адреса остаются`() {
        assertFalse(FastMultiDnsResolver.isLoopbackOrUnspecified("8.8.8.8"))
        assertFalse(FastMultiDnsResolver.isLoopbackOrUnspecified("93.158.213.92"))
        assertFalse(FastMultiDnsResolver.isLoopbackOrUnspecified("172.217.116.4"))
    }
}
